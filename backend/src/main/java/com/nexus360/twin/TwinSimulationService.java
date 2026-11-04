package com.nexus360.twin;

import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.CmdbGraphService;
import com.nexus360.cmdb.CmdbGraphService.DependencyEdge;
import com.nexus360.cmdb.ConfigurationItem.CiType;
import com.nexus360.common.ApiException;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.observability.TelemetryJson;
import com.nexus360.security.Actor;
import com.nexus360.twin.TwinDtos.ChangeRef;
import com.nexus360.twin.TwinDtos.CiBrief;
import com.nexus360.twin.TwinDtos.ImpactedCi;
import com.nexus360.twin.TwinDtos.ImpactedService;
import com.nexus360.twin.TwinDtos.Layer;
import com.nexus360.twin.TwinDtos.SimulationRequest;
import com.nexus360.twin.TwinDtos.SimulationResult;
import com.nexus360.twin.TwinDtos.SimulationSummary;
import com.nexus360.twin.TwinDtos.SlaRisk;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failure and change impact simulation on the digital twin. Propagation follows dependency edges from the targets to everything
 * that (transitively) depends on them. Impact levels: DOWN (2) and DEGRADED (1); a HARD failure starts at DOWN, a DEGRADED one (and,
 * by default, a change) at DEGRADED. A dependent's level is the maximum over its impacted dependencies of the dependency's level,
 * reduced by one when the dependency is <b>redundant</b>:
 * <ul>
 *   <li>the dependency CI has a {@code cluster} attribute and another member of that cluster (same value) is not impacted, or</li>
 *   <li>the relationship has {@code redundant: true} and either another {@code redundant} relationship of the same type from the
 *       dependent points at an unimpacted CI, or it is the only one (the counterpart is outside the model).</li>
 * </ul>
 * Results list impacted CIs (with paths), business services (with the affected-user estimate and its basis), open SLA clocks at
 * risk and scheduled changes touching them. Simulations are persisted (who, when, inputs, result) and never change real data.
 */
@Service
@RequiredArgsConstructor
public class TwinSimulationService {

    static final int MAX_IMPACTED = 2000;
    static final int DOWN = 2;
    static final int DEGRADED = 1;

    private final CmdbGraphService graph;
    private final TwinService twin;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    record Ci(long id, String name, CiType type, String status, Long serviceId, String cluster) {}

    record Via(long from, DependencyEdge edge, boolean redundancyApplied) {}

    @Transactional
    public SimulationResult simulate(SimulationRequest r, Actor actor) {
        String type = r.type();
        Long changeId = r.changeId();
        String changeNumber = null;
        Set<Long> targets = new LinkedHashSet<>(r.targets() == null ? List.of() : r.targets());
        if ("CHANGE".equals(type)) {
            if (changeId == null) {
                throw ApiException.invalidField("changeId", "A CHANGE simulation needs the change");
            }
            changeNumber = jdbc.query("select number from changes where id = ?", rs -> rs.next() ? rs.getString(1) : null, changeId);
            if (changeNumber == null) {
                throw ApiException.invalidField("changeId", "Change does not exist");
            }
            if (targets.isEmpty()) {
                targets.addAll(jdbc.queryForList("select ci_id from change_cis where change_id = ? union select c.id from change_services cs join "
                        + "configuration_items c on c.service_id = cs.service_id where cs.change_id = ?", Long.class, changeId, changeId));
            }
            if (targets.isEmpty()) {
                throw ApiException.badRequest("The change has no linked configuration items or services: pass targets explicitly");
            }
        } else if (changeId != null) {
            throw ApiException.invalidField("changeId", "changeId is only used by CHANGE simulations");
        } else if (targets.isEmpty()) {
            throw ApiException.invalidField("targets", "At least one target CI is required");
        }
        String mode = r.mode() != null ? r.mode() : "CHANGE".equals(type) ? "DEGRADED" : "HARD";
        Map<Long, Ci> cis = new HashMap<>();
        jdbc.query("select id, name, ci_type, status, service_id, attributes ->> 'cluster' from configuration_items", rs -> {
            long sid = rs.getLong(5);
            boolean noService = rs.wasNull();
            cis.put(rs.getLong(1), new Ci(rs.getLong(1), rs.getString(2), CiType.valueOf(rs.getString(3)), rs.getString(4), noService ? null : sid,
                    blank(rs.getString(6))));
        });
        for (Long t : targets) {
            if (!cis.containsKey(t)) {
                throw ApiException.invalidField("targets", "Configuration item " + t + " does not exist");
            }
        }
        List<DependencyEdge> edges = graph.dependencyEdges();
        Map<Long, List<DependencyEdge>> dependentsOf = new HashMap<>();
        Map<Long, List<DependencyEdge>> dependenciesOf = new HashMap<>();
        Map<String, List<Long>> clusters = new HashMap<>();
        for (DependencyEdge e : edges) {
            dependentsOf.computeIfAbsent(e.dependencyId(), k -> new ArrayList<>()).add(e);
            dependenciesOf.computeIfAbsent(e.dependentId(), k -> new ArrayList<>()).add(e);
        }
        cis.values().stream().filter(c -> c.cluster() != null && !"RETIRED".equals(c.status()))
                .forEach(c -> clusters.computeIfAbsent(c.cluster(), k -> new ArrayList<>()).add(c.id()));

        int start = "HARD".equals(mode) ? DOWN : DEGRADED;
        Map<Long, Integer> level = new LinkedHashMap<>();
        Map<Long, Via> via = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>();
        for (Long t : targets) {
            level.put(t, start);
            queue.add(t);
        }
        List<String> notes = new ArrayList<>();
        int iterations = 0;
        while (!queue.isEmpty() && iterations++ < 50_000 && level.size() < MAX_IMPACTED) {
            long n = queue.poll();
            Set<Long> affected = new LinkedHashSet<>();
            dependentsOf.getOrDefault(n, List.of()).forEach(e -> affected.add(e.dependentId()));
            Ci nc = cis.get(n);
            if (nc != null && nc.cluster() != null) {
                // A cluster member's state changes the protection of everything that depends on any member of the cluster.
                for (Long peer : clusters.getOrDefault(nc.cluster(), List.of())) {
                    dependentsOf.getOrDefault(peer, List.of()).forEach(e -> affected.add(e.dependentId()));
                }
            }
            for (Long x : affected) {
                if (targets.contains(x)) {
                    continue;
                }
                int best = 0;
                Via bestVia = null;
                for (DependencyEdge e : dependenciesOf.getOrDefault(x, List.of())) {
                    int dep = level.getOrDefault(e.dependencyId(), 0);
                    if (dep == 0) {
                        continue;
                    }
                    boolean protectedEdge = isProtected(e, x, level, cis, clusters, dependenciesOf);
                    int contribution = dep - (protectedEdge ? 1 : 0);
                    // Highest contribution wins; on a tie the unprotected edge explains the impact better.
                    if (bestVia == null || contribution > best || (contribution == best && bestVia.redundancyApplied() && !protectedEdge)) {
                        best = contribution;
                        bestVia = new Via(e.dependencyId(), e, protectedEdge);
                    }
                }
                int current = level.getOrDefault(x, 0);
                if (best != current) {
                    if (best == 0) {
                        level.remove(x);
                    } else {
                        level.put(x, best);
                    }
                    queue.add(x);
                }
                if (bestVia != null) {
                    via.put(x, bestVia);
                } else {
                    via.remove(x);
                }
            }
        }
        if (level.size() >= MAX_IMPACTED) {
            notes.add("Propagation stopped at " + MAX_IMPACTED + " configuration items");
        }
        // Results
        List<ImpactedCi> impactedCis = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : level.entrySet()) {
            if (targets.contains(e.getKey())) {
                continue;
            }
            Ci c = cis.get(e.getKey());
            List<Long> pathIds = path(e.getKey(), via, targets);
            boolean redundancy = pathIds.stream().anyMatch(id -> via.containsKey(id) && via.get(id).redundancyApplied());
            Via v = via.get(e.getKey());
            String reason = v == null ? null : "Depends on " + cis.get(v.from()).name() + " (" + v.edge().type().name() + ")"
                    + (v.redundancyApplied() ? ", redundant dependency: impact reduced" : "");
            impactedCis.add(new ImpactedCi(c.id(), c.name(), c.type(), TwinService.layerOf(c.type()), e.getValue() == DOWN ? "DOWN" : "DEGRADED",
                    pathIds.size() - 1, pathIds.stream().map(id -> cis.get(id).name()).toList(), pathIds, redundancy, reason));
        }
        // Protected dependents that stayed unimpacted are reported as notes (redundancy absorbed the failure).
        for (Map.Entry<Long, Via> e : via.entrySet()) {
            if (!level.containsKey(e.getKey()) && e.getValue().redundancyApplied() && !targets.contains(e.getKey())) {
                notes.add(cis.get(e.getKey()).name() + " is not impacted: its dependency " + cis.get(e.getValue().from()).name() + " is redundant");
            }
        }
        impactedCis.sort(Comparator.comparingInt(ImpactedCi::depth).thenComparing(ImpactedCi::name));
        Set<Long> impactedIds = new LinkedHashSet<>(level.keySet());
        List<ImpactedService> services = new ArrayList<>();
        for (Long id : impactedIds) {
            Ci c = cis.get(id);
            if (c.serviceId() == null) {
                continue;
            }
            Map<String, Object> s = jdbc.query("select code, name, tier from business_services where id = ?", rs -> rs.next() ? Map.<String, Object>of("code",
                    rs.getString(1), "name", rs.getString(2), "tier", rs.getInt(3)) : null, c.serviceId());
            if (s == null) {
                continue;
            }
            Object[] users = affectedUsers(id, c.serviceId());
            List<Long> pathIds = path(id, via, targets);
            services.add(new ImpactedService(c.serviceId(), (String) s.get("code"), (String) s.get("name"), (Integer) s.get("tier"),
                    level.get(id) == DOWN ? "DOWN" : "DEGRADED", id, pathIds.stream().map(p -> cis.get(p).name()).toList(), (Integer) users[0],
                    (String) users[1]));
        }
        services.sort(Comparator.comparingInt(ImpactedService::tier).thenComparing(ImpactedService::name));
        Integer totalUsers = null;
        String basis;
        if (services.isEmpty()) {
            basis = "NO_SERVICES_IMPACTED";
        } else {
            long known = services.stream().filter(s -> s.estimatedAffectedUsers() != null).count();
            if (known > 0) {
                totalUsers = services.stream().filter(s -> s.estimatedAffectedUsers() != null).mapToInt(ImpactedService::estimatedAffectedUsers).max().orElse(0);
            }
            basis = known == 0 ? "UNKNOWN" : known == services.size() ? "MAX_OVER_SERVICES" : "PARTIAL_MAX_OVER_SERVICES";
        }
        Map<String, Long> byLayer = new LinkedHashMap<>();
        for (Layer l : Layer.values()) {
            byLayer.put(l.name(), impactedCis.stream().filter(c -> c.layer() == l).count());
        }
        List<Long> serviceIds = services.stream().map(ImpactedService::serviceId).toList();
        List<SlaRisk> slas = serviceIds.isEmpty() ? List.of() : jdbc.query("select i.id, i.number, i.title, i.priority, s.name, sl.health, sl.resolution_due_at "
                + "from incidents i join incident_slas sl on sl.incident_id = i.id join business_services s on s.id = i.service_id where i.service_id in ("
                + TwinService.in(serviceIds) + ") and i.status in " + IncidentStatus.activeSqlList() + " and sl.outcome is null and not i.simulated order by "
                + "sl.resolution_due_at nulls last limit 50", (rs, n) -> new SlaRisk(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()));
        Instant now = clock.instant();
        List<ChangeRef> changes = jdbc.query("select distinct c.id, c.number, c.title, c.status, c.planned_start, c.planned_end, case when cc.ci_id is not null "
                + "then 'CI' else 'SERVICE' end from changes c left join change_cis cc on cc.change_id = c.id and cc.ci_id in (" + TwinService.in(impactedIds)
                + ") left join change_services cs on cs.change_id = c.id and cs.service_id in (" + TwinService.in(serviceIds) + ") where (cc.ci_id is not null "
                + "or cs.service_id is not null) and c.status in ('AWAITING_APPROVAL', 'APPROVED', 'SCHEDULED', 'IMPLEMENTING') and (c.planned_end is null or "
                + "c.planned_end >= ?)" + (changeId == null ? "" : " and c.id <> " + changeId) + " order by c.planned_start nulls last limit 50", (rs, n) ->
                new ChangeRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), ts(rs.getTimestamp(5)), ts(rs.getTimestamp(6)),
                        rs.getString(7)), Timestamp.from(now));
        List<CiBrief> targetBriefs = targets.stream().map(id -> {
            Ci c = cis.get(id);
            return new CiBrief(c.id(), c.name(), c.type(), TwinService.layerOf(c.type()));
        }).toList();
        if (impactedCis.isEmpty()) {
            notes.add("Nothing depends on the targets in the CMDB (no dependents impacted)");
        }
        String name = r.name() == null || r.name().isBlank() ? ("CHANGE".equals(type) ? "Change " + changeNumber : "Failure of "
                + String.join(", ", targetBriefs.stream().map(CiBrief::name).limit(3).toList())) : r.name().trim();
        SimulationResult draft = new SimulationResult(null, "Simulation", type, mode, name, targetBriefs, changeId, changeNumber, impactedCis, services, byLayer,
                totalUsers, basis, slas, changes, notes, actor.name(), now);
        Long id = jdbc.queryForObject("insert into twin_simulations (sim_type, mode, name, targets, change_id, result, impacted_ci_count, impacted_service_count, "
                + "created_by_id, created_by_name, created_at) values (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?) returning id", Long.class, type, mode,
                truncate(name, 200), TelemetryJson.write(List.copyOf(targets)), changeId, TelemetryJson.write(draft), impactedCis.size(), services.size(),
                actor.id(), actor.name(), Timestamp.from(now));
        audit.record("TWIN_SIMULATION_RUN", "TWIN_SIMULATION", id, Map.of("type", type, "mode", mode, "targets", List.copyOf(targets), "impactedCis",
                impactedCis.size(), "impactedServices", services.size()));
        return withId(draft, id);
    }

    private static boolean isProtected(DependencyEdge e, long dependent, Map<Long, Integer> level, Map<Long, Ci> cis, Map<String, List<Long>> clusters,
                                       Map<Long, List<DependencyEdge>> dependenciesOf) {
        Ci dep = cis.get(e.dependencyId());
        if (dep != null && dep.cluster() != null) {
            for (Long peer : clusters.getOrDefault(dep.cluster(), List.of())) {
                if (peer != e.dependencyId() && level.getOrDefault(peer, 0) == 0) {
                    return true;
                }
            }
            return false;
        }
        if (e.redundant()) {
            List<DependencyEdge> peers = dependenciesOf.getOrDefault(dependent, List.of()).stream()
                    .filter(p -> p.relationshipId() != e.relationshipId() && p.redundant() && p.type() == e.type()).toList();
            return peers.isEmpty() || peers.stream().anyMatch(p -> level.getOrDefault(p.dependencyId(), 0) == 0);
        }
        return false;
    }

    /** Target → ... → ci, following the recorded propagation edges. */
    private static List<Long> path(long ci, Map<Long, Via> via, Set<Long> targets) {
        List<Long> out = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        Long cur = ci;
        while (cur != null && seen.add(cur)) {
            out.add(cur);
            if (targets.contains(cur)) {
                break;
            }
            Via v = via.get(cur);
            cur = v == null ? null : v.from();
        }
        Collections.reverse(out);
        return out;
    }

    /** {estimate, basis}: the BUSINESS_SERVICE CI's users/userCount attribute, else the largest affected-user count of the service's incidents in 180 days. */
    private Object[] affectedUsers(long ciId, long serviceId) {
        String attr = jdbc.query("select coalesce(attributes ->> 'users', attributes ->> 'userCount', attributes ->> 'affectedUsers') from configuration_items "
                + "where id = ?", rs -> rs.next() ? rs.getString(1) : null, ciId);
        if (attr != null) {
            try {
                return new Object[] {(int) Math.round(Double.parseDouble(attr.trim())), "CI_ATTRIBUTE"};
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        Integer max = jdbc.queryForObject("select max(affected_users) from incidents where service_id = ? and created_at >= ? and not simulated", Integer.class,
                serviceId, Timestamp.from(clock.instant().minus(Duration.ofDays(180))));
        return max == null || max <= 0 ? new Object[] {null, "UNKNOWN"} : new Object[] {max, "MAX_RECORDED_INCIDENT_IMPACT_180D"};
    }

    @Transactional(readOnly = true)
    public List<SimulationSummary> list(Integer limit) {
        int max = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return jdbc.query("select id, sim_type, mode, name, impacted_ci_count, impacted_service_count, created_by_name, created_at from twin_simulations order by "
                + "created_at desc, id desc limit ?", (rs, n) -> new SimulationSummary(rs.getLong(1), "Simulation", rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getString(7), rs.getTimestamp(8).toInstant()), max);
    }

    @Transactional(readOnly = true)
    public SimulationResult get(Long id) {
        String json = jdbc.query("select result::text from twin_simulations where id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
        if (json == null) {
            throw ApiException.notFound("Twin simulation", id);
        }
        SimulationResult r = TelemetryJson.read(json, SimulationResult.class);
        if (r == null) {
            throw new IllegalStateException("Stored simulation result " + id + " cannot be read");
        }
        return withId(r, id);
    }

    private static SimulationResult withId(SimulationResult r, Long id) {
        return new SimulationResult(id, r.label(), r.type(), r.mode(), r.name(), r.targets(), r.changeId(), r.changeNumber(), r.impactedCis(),
                r.impactedServices(), r.impactByLayer(), r.estimatedAffectedUsers(), r.affectedUsersBasis(), r.slasAtRisk(), r.scheduledChanges(), r.notes(),
                r.createdByName(), r.createdAt());
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
