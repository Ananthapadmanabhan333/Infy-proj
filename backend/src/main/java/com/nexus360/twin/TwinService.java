package com.nexus360.twin;

import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.CmdbDtos.Graph;
import com.nexus360.cmdb.CmdbDtos.GraphNode;
import com.nexus360.cmdb.CmdbGraphService;
import com.nexus360.cmdb.CmdbGraphService.DependencyEdge;
import com.nexus360.cmdb.ConfigurationItem.CiType;
import com.nexus360.cmdb.ServiceCatalogDtos.ServiceDto;
import com.nexus360.cmdb.ServiceHealthService;
import com.nexus360.common.ApiException;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.observability.TelemetryJson;
import com.nexus360.security.Actor;
import com.nexus360.twin.TwinDtos.BusinessServiceTree;
import com.nexus360.twin.TwinDtos.ChangeTimeline;
import com.nexus360.twin.TwinDtos.DiffEntry;
import com.nexus360.twin.TwinDtos.Layer;
import com.nexus360.twin.TwinDtos.NodeState;
import com.nexus360.twin.TwinDtos.ServiceRef;
import com.nexus360.twin.TwinDtos.TimelineEntry;
import com.nexus360.twin.TwinDtos.TreeNode;
import com.nexus360.twin.TwinDtos.TwinEdge;
import com.nexus360.twin.TwinDtos.TwinGraph;
import com.nexus360.twin.TwinDtos.TwinNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SA Digital Twin read model, built on the CMDB (CIs, relationships, business services and their dependencies — no second inventory):
 * layered graph with live state overlays, business-service dependency trees, relationship redundancy attributes and the unified
 * configuration change timeline (CMDB history, change requests, deployments, telemetry resource attribute changes).
 */
@Service
@RequiredArgsConstructor
public class TwinService {

    static final int MAX_TREE_NODES = 400;

    private final CmdbGraphService graph;
    private final ServiceHealthService health;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public static Layer layerOf(CiType type) {
        return switch (type) {
            case BUSINESS_SERVICE -> Layer.BUSINESS;
            case APPLICATION, SOFTWARE -> Layer.APPLICATION;
            default -> Layer.INFRASTRUCTURE;
        };
    }

    static List<CiType> typesOf(Layer layer) {
        List<CiType> out = new ArrayList<>();
        for (CiType t : CiType.values()) {
            if (layerOf(t) == layer) {
                out.add(t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ graph

    @Transactional(readOnly = true)
    public TwinGraph graph(Long root, Integer depth, String layer, boolean includeState, boolean includeSimulated) {
        Layer l = parseLayer(layer);
        Graph g = graph.graph(root, depth, l == null ? null : typesOf(l), null, false);
        Map<Long, DependencyEdge> attributes = graph.dependencyEdges().stream().collect(Collectors.toMap(DependencyEdge::relationshipId, e -> e, (a, b) -> a));
        Map<Long, CiRow> rows = ciRows(g.nodes().stream().map(GraphNode::id).toList());
        Map<Long, NodeState> states = includeState ? states(rows.values(), includeSimulated) : Map.of();
        List<TwinNode> nodes = g.nodes().stream().map(n -> {
            CiRow r = rows.get(n.id());
            return new TwinNode(n.id(), n.name(), n.ciType(), layerOf(n.ciType()), n.status().name(), n.environment() == null ? null : n.environment().name(),
                    n.criticality().name(), n.serviceId(), r == null ? null : r.serviceCode(), n.depth(), r == null ? null : r.cluster(), states.get(n.id()));
        }).toList();
        List<TwinEdge> edges = g.edges().stream().map(e -> {
            DependencyEdge d = attributes.get(e.id());
            return new TwinEdge(e.id(), e.source(), e.target(), e.type(), d == null ? null : d.dependentId(), d == null ? null : d.dependencyId(),
                    d != null && d.redundant(), d == null ? Map.of() : d.attributes());
        }).toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Layer x : Layer.values()) {
            counts.put(x.name(), nodes.stream().filter(n -> n.layer() == x).count());
        }
        return new TwinGraph(root, g.depth(), l == null ? "ALL" : l.name(), includeState, includeSimulated, nodes, edges, counts, g.truncated());
    }

    private static Layer parseLayer(String layer) {
        if (layer == null || layer.isBlank() || "ALL".equalsIgnoreCase(layer.trim())) {
            return null;
        }
        try {
            return Layer.valueOf(layer.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidField("layer", "layer must be BUSINESS, APPLICATION, INFRASTRUCTURE or ALL");
        }
    }

    // ------------------------------------------------------------------ business service tree

    /** The dependency tree of a business service down to infrastructure (what it needs), cycle-safe, with state overlays. */
    @Transactional(readOnly = true)
    public BusinessServiceTree businessService(Long serviceId, Integer depth, boolean includeState, boolean includeSimulated) {
        Map<String, Object> s = jdbc.query("select s.id, s.code, s.name, s.tier, c.id from business_services s left join configuration_items c on c.service_id = s.id "
                + "where s.id = ?", rs -> rs.next() ? Map.<String, Object>of("id", rs.getLong(1), "code", rs.getString(2), "name", rs.getString(3), "tier",
                rs.getInt(4), "ci", rs.getObject(5) == null ? -1L : rs.getLong(5)) : null, serviceId);
        if (s == null) {
            throw ApiException.notFound("Service", serviceId);
        }
        long ciId = (Long) s.get("ci");
        if (ciId < 0) {
            throw ApiException.conflict("The service has no BUSINESS_SERVICE configuration item yet");
        }
        int d = depth == null ? 6 : depth;
        if (d < 1 || d > CmdbGraphService.MAX_DEPTH) {
            throw ApiException.badRequest("depth must be between 1 and " + CmdbGraphService.MAX_DEPTH);
        }
        Map<Long, List<DependencyEdge>> deps = new HashMap<>();
        for (DependencyEdge e : graph.dependencyEdges()) {
            deps.computeIfAbsent(e.dependentId(), k -> new ArrayList<>()).add(e);
        }
        Set<Long> reached = new HashSet<>();
        collect(ciId, d, deps, reached);
        Map<Long, CiRow> rows = ciRows(reached);
        Map<Long, NodeState> states = includeState ? states(rows.values(), includeSimulated) : Map.of();
        int[] budget = {MAX_TREE_NODES};
        TreeNode root = tree(ciId, null, false, d, deps, rows, states, new HashSet<>(), budget);
        Map<String, String> statuses = health.list().stream().collect(Collectors.toMap(x -> String.valueOf(x.id()), x -> x.status().name(), (a, b) -> a));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Layer x : Layer.values()) {
            counts.put(x.name(), rows.values().stream().filter(r -> layerOf(r.type()) == x).count());
        }
        return new BusinessServiceTree(new ServiceRef(serviceId, (String) s.get("code"), (String) s.get("name"), (Integer) s.get("tier"),
                statuses.getOrDefault(String.valueOf(serviceId), "OPERATIONAL")), ciId, d, root, counts, budget[0] <= 0);
    }

    private static void collect(long ci, int depth, Map<Long, List<DependencyEdge>> deps, Set<Long> reached) {
        if (!reached.add(ci) || depth <= 0) {
            return;
        }
        for (DependencyEdge e : deps.getOrDefault(ci, List.of())) {
            collect(e.dependencyId(), depth - 1, deps, reached);
        }
    }

    private TreeNode tree(long ci, DependencyEdge via, boolean redundant, int depth, Map<Long, List<DependencyEdge>> deps, Map<Long, CiRow> rows,
                          Map<Long, NodeState> states, Set<Long> path, int[] budget) {
        CiRow r = rows.get(ci);
        budget[0]--;
        boolean repeated = path.contains(ci);
        List<TreeNode> children = new ArrayList<>();
        if (!repeated && depth > 0 && budget[0] > 0) {
            path.add(ci);
            List<DependencyEdge> next = new ArrayList<>(deps.getOrDefault(ci, List.of()));
            next.sort(Comparator.comparing((DependencyEdge e) -> rows.containsKey(e.dependencyId()) ? layerOf(rows.get(e.dependencyId()).type()).ordinal() : 9)
                    .thenComparing(e -> rows.containsKey(e.dependencyId()) ? rows.get(e.dependencyId()).name().toLowerCase(Locale.ROOT) : ""));
            for (DependencyEdge e : next) {
                if (budget[0] <= 0 || !rows.containsKey(e.dependencyId())) {
                    continue;
                }
                children.add(tree(e.dependencyId(), e, e.redundant(), depth - 1, deps, rows, states, path, budget));
            }
            path.remove(ci);
        }
        return new TreeNode(ci, r == null ? "CI " + ci : r.name(), r == null ? null : r.type(), r == null ? null : layerOf(r.type()), via == null ? null
                : via.type(), redundant, r == null ? null : r.cluster(), states.get(ci), repeated, children);
    }

    // ------------------------------------------------------------------ redundancy attributes

    @Transactional
    public TwinEdge setRedundant(Long relationshipId, boolean redundant, String note, Actor actor) {
        DependencyEdge edge = graph.dependencyEdges().stream().filter(e -> e.relationshipId() == relationshipId).findFirst()
                .orElseThrow(() -> ApiException.notFound("Relationship", relationshipId));
        Instant now = clock.instant();
        Map<String, Object> attributes = new LinkedHashMap<>(edge.attributes());
        Object before = attributes.get("redundant");
        if (redundant) {
            attributes.put("redundant", true);
        } else {
            attributes.remove("redundant");
        }
        jdbc.update("update ci_relationships set attributes = ?::jsonb, updated_at = ? where id = ?", TelemetryJson.write(attributes), Timestamp.from(now),
                relationshipId);
        String names = jdbc.queryForObject("select s.name || ' → ' || t.name from ci_relationships r join configuration_items s on s.id = r.source_id join "
                + "configuration_items t on t.id = r.target_id where r.id = ?", String.class, relationshipId);
        jdbc.update("insert into ci_history (ci_id, actor_id, actor_name, action, from_value, to_value, note, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                edge.dependentId(), actor.id(), actor.name(), "RELATIONSHIP_UPDATED", "redundant: " + (before == null ? "false" : before),
                "redundant: " + redundant, note == null ? edge.type().outgoingLabel() + " (" + names + ")" : note, Timestamp.from(now));
        audit.record("TWIN_RELATIONSHIP_REDUNDANCY_CHANGED", "CI_RELATIONSHIP", relationshipId, Map.of("redundant", redundant, "relationship", names));
        DependencyEdge after = graph.dependencyEdges().stream().filter(e -> e.relationshipId() == relationshipId).findFirst().orElseThrow();
        long source = jdbc.queryForObject("select source_id from ci_relationships where id = ?", Long.class, relationshipId);
        long target = jdbc.queryForObject("select target_id from ci_relationships where id = ?", Long.class, relationshipId);
        return new TwinEdge(relationshipId, source, target, after.type(), after.dependentId(), after.dependencyId(), after.redundant(), after.attributes());
    }

    // ------------------------------------------------------------------ state overlays

    record CiRow(long id, String name, CiType type, String status, Long serviceId, String serviceCode, String cluster) {}

    Map<Long, CiRow> ciRows(Collection<Long> ids) {
        Map<Long, CiRow> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.query("select c.id, c.name, c.ci_type, c.status, c.service_id, s.code, c.attributes ->> 'cluster' from configuration_items c left join "
                + "business_services s on s.id = c.service_id where c.id in (" + in(ids) + ")", rs -> {
                    long sid = rs.getLong(5);
                    out.put(rs.getLong(1), new CiRow(rs.getLong(1), rs.getString(2), CiType.valueOf(rs.getString(3)), rs.getString(4), rs.wasNull() ? null : sid,
                            rs.getString(6), rs.getString(7)));
                });
        return out;
    }

    /** State overlays in a handful of bulk queries. */
    Map<Long, NodeState> states(Collection<CiRow> rows, boolean includeSimulated) {
        Map<Long, NodeState> out = new HashMap<>();
        if (rows.isEmpty()) {
            return out;
        }
        String ids = in(rows.stream().map(CiRow::id).toList());
        List<Long> serviceIdList = rows.stream().map(CiRow::serviceId).filter(java.util.Objects::nonNull).toList();
        String services = serviceIdList.isEmpty() ? "-1" : in(serviceIdList);
        String simAlerts = includeSimulated ? "" : " and not a.simulated";
        String simIncidents = includeSimulated ? "" : " and not i.simulated";
        String simAnomalies = includeSimulated ? "" : " and not an.simulated";
        Map<Long, Long> ciAlerts = new HashMap<>();
        Map<Long, String> ciWorst = new HashMap<>();
        jdbc.query("select a.ci_id, count(*), min(case a.severity when 'CRITICAL' then 1 when 'MAJOR' then 2 when 'MINOR' then 3 else 4 end) from alerts a "
                + "where a.status in ('OPEN', 'ACKNOWLEDGED') and a.firing and a.ci_id in (" + ids + ")" + simAlerts + " group by a.ci_id", rs -> {
                    ciAlerts.put(rs.getLong(1), rs.getLong(2));
                    ciWorst.put(rs.getLong(1), rs.getInt(3) == 1 ? "CRITICAL" : rs.getInt(3) == 2 ? "MAJOR" : "MINOR");
                });
        Map<Long, Long> serviceAlerts = new HashMap<>();
        jdbc.query("select a.service_id, count(*) from alerts a where a.status in ('OPEN', 'ACKNOWLEDGED') and a.firing and a.service_id in (" + services + ")"
                + simAlerts + " group by a.service_id", rs -> {
                    serviceAlerts.put(rs.getLong(1), rs.getLong(2));
                });
        Map<Long, Long> serviceIncidents = new HashMap<>();
        jdbc.query("select i.service_id, count(*) from incidents i where i.status in " + IncidentStatus.activeSqlList() + " and i.service_id in (" + services + ")"
                + simIncidents + " group by i.service_id", rs -> {
                    serviceIncidents.put(rs.getLong(1), rs.getLong(2));
                });
        Map<Long, Long> ciIncidents = new HashMap<>();
        jdbc.query("select a.ci_id, count(distinct a.incident_id) from alerts a join incidents i on i.id = a.incident_id where i.status in "
                + IncidentStatus.activeSqlList() + " and a.ci_id in (" + ids + ")" + simIncidents + " group by a.ci_id", rs -> {
                    ciIncidents.put(rs.getLong(1), rs.getLong(2));
                });
        Map<Long, Long> ciAnomalies = new HashMap<>();
        jdbc.query("select an.ci_id, count(*) from telemetry_anomalies an where an.status = 'OPEN' and an.ci_id in (" + ids + ")" + simAnomalies
                + " group by an.ci_id", rs -> {
                    ciAnomalies.put(rs.getLong(1), rs.getLong(2));
                });
        Map<Long, Long> serviceAnomalies = new HashMap<>();
        jdbc.query("select an.service_id, count(*) from telemetry_anomalies an where an.status = 'OPEN' and an.ci_id is null and an.service_id in (" + services
                + ")" + simAnomalies + " group by an.service_id", rs -> {
                    serviceAnomalies.put(rs.getLong(1), rs.getLong(2));
                });
        Map<Long, Object[]> lastCiChange = new HashMap<>();
        jdbc.query("select distinct on (ci_id) ci_id, created_at, action, coalesce(to_value, note, '') from ci_history where ci_id in (" + ids + ") order by ci_id, "
                + "created_at desc, id desc", rs -> {
                    lastCiChange.put(rs.getLong(1), new Object[] {rs.getTimestamp(2).toInstant(), rs.getString(3) + " " + rs.getString(4)});
                });
        Map<Long, Object[]> lastDeployment = new HashMap<>();
        jdbc.query("select distinct on (service_id) service_id, started_at, number, version from deployments where service_id in (" + services + ")"
                + (includeSimulated ? "" : " and not simulated") + " order by service_id, started_at desc", rs -> {
                    lastDeployment.put(rs.getLong(1), new Object[] {rs.getTimestamp(2).toInstant(), "Deployment " + rs.getString(3) + " " + rs.getString(4)});
                });
        Map<Long, ServiceDto> serviceHealth = serviceIdList.isEmpty() ? Map.of() : health.list().stream()
                .collect(Collectors.toMap(ServiceDto::id, x -> x, (a, b) -> a));
        for (CiRow r : rows) {
            long alerts = ciAlerts.getOrDefault(r.id(), 0L) + (r.serviceId() == null ? 0 : serviceAlerts.getOrDefault(r.serviceId(), 0L));
            long incidents = r.serviceId() != null ? serviceIncidents.getOrDefault(r.serviceId(), 0L) : ciIncidents.getOrDefault(r.id(), 0L);
            long anomalies = ciAnomalies.getOrDefault(r.id(), 0L) + (r.serviceId() == null ? 0 : serviceAnomalies.getOrDefault(r.serviceId(), 0L));
            String h;
            String reason;
            ServiceDto sd = r.serviceId() == null ? null : serviceHealth.get(r.serviceId());
            if (sd != null) {
                h = sd.status().name();
                reason = sd.statusReason();
            } else if ("MAINTENANCE".equals(r.status())) {
                h = "MAINTENANCE";
                reason = "CI status MAINTENANCE";
            } else if ("CRITICAL".equals(ciWorst.get(r.id()))) {
                h = "OUTAGE";
                reason = "Critical alert firing";
            } else if (alerts > 0 || anomalies > 0) {
                h = "DEGRADED";
                reason = alerts > 0 ? alerts + " firing alert(s)" : anomalies + " open anomaly(ies)";
            } else {
                h = "OPERATIONAL";
                reason = null;
            }
            Object[] ci = lastCiChange.get(r.id());
            Object[] dep = r.serviceId() == null ? null : lastDeployment.get(r.serviceId());
            Object[] last = ci == null ? dep : dep == null ? ci : ((Instant) ci[0]).isAfter((Instant) dep[0]) ? ci : dep;
            out.put(r.id(), new NodeState(h, reason, incidents, alerts, anomalies, last == null ? null : (Instant) last[0],
                    last == null ? null : truncate((String) last[1], 200)));
        }
        return out;
    }

    // ------------------------------------------------------------------ configuration change timeline

    /**
     * Unified timeline for a CI (and the business service it is or that depends on it) or for a service: CMDB history, change requests
     * (and their transitions), deployments (with the version they replaced) and telemetry resource attribute changes (service.version,
     * container image, ...). Newest first; at most {@code limit} entries.
     */
    @Transactional(readOnly = true)
    public ChangeTimeline changes(Long ciId, Long serviceId, Instant from, Instant to, boolean includeSimulated, Integer limit) {
        Instant t = to == null ? clock.instant() : to;
        Instant f = from == null ? t.minus(Duration.ofDays(30)) : from;
        if (!f.isBefore(t)) {
            throw ApiException.badRequest("from must be before to");
        }
        int max = limit == null ? 200 : Math.max(1, Math.min(limit, 500));
        Set<Long> ciIds = new java.util.LinkedHashSet<>();
        Set<Long> serviceIds = new java.util.LinkedHashSet<>();
        if (ciId != null) {
            if (jdbc.queryForObject("select count(*) from configuration_items where id = ?", Long.class, ciId) == 0) {
                throw ApiException.notFound("Configuration item", ciId);
            }
            ciIds.add(ciId);
            graph.nearestServiceId(ciId).ifPresent(serviceIds::add);
        }
        if (serviceId != null) {
            if (jdbc.queryForObject("select count(*) from business_services where id = ?", Long.class, serviceId) == 0) {
                throw ApiException.notFound("Service", serviceId);
            }
            serviceIds.add(serviceId);
            ciIds.addAll(jdbc.queryForList("select id from configuration_items where service_id = ?", Long.class, serviceId));
        }
        boolean global = ciId == null && serviceId == null;
        String ciFilter = global ? "true" : "h.ci_id in (" + in(ciIds) + ")";
        String serviceFilter = global ? "true" : "d.service_id in (" + in(serviceIds) + ")";
        Timestamp tf = Timestamp.from(f);
        Timestamp tt = Timestamp.from(t);
        List<TimelineEntry> entries = new ArrayList<>();
        jdbc.query("select h.id, h.created_at, h.action, h.from_value, h.to_value, h.note, h.actor_name, h.ci_id, c.name, c.service_id from ci_history h join "
                + "configuration_items c on c.id = h.ci_id where " + ciFilter + " and h.created_at >= ? and h.created_at < ? order by h.created_at desc limit ?", rs -> {
                    List<DiffEntry> diff = rs.getString(4) == null && rs.getString(5) == null ? List.of()
                            : List.of(new DiffEntry(rs.getString(3), rs.getString(4), rs.getString(5)));
                    entries.add(new TimelineEntry(rs.getTimestamp(2).toInstant(), "CMDB", rs.getString(3).replace('_', ' ').toLowerCase(Locale.ROOT)
                            + (rs.getString(6) == null ? "" : ": " + rs.getString(6)), rs.getString(7), rs.getLong(8), rs.getString(9),
                            nullable(rs.getLong(10), rs.wasNull()), ref("CI_HISTORY", rs.getLong(1), null), diff, false));
                }, tf, tt, max);
        String changeScope = global ? "true" : "(c.id in (select change_id from change_cis where ci_id in (" + in(ciIds) + ")) or c.id in (select change_id "
                + "from change_services where service_id in (" + in(serviceIds) + ")))";
        jdbc.query("select h.id, h.created_at, h.action, h.from_value, h.to_value, h.note, h.actor_name, c.id, c.number, c.title from change_history h join "
                + "changes c on c.id = h.change_id where " + changeScope + " and h.created_at >= ? and h.created_at < ? order by h.created_at desc limit ?", rs -> {
                    List<DiffEntry> diff = rs.getString(4) == null && rs.getString(5) == null ? List.of()
                            : List.of(new DiffEntry(rs.getString(3).toLowerCase(Locale.ROOT), rs.getString(4), rs.getString(5)));
                    entries.add(new TimelineEntry(rs.getTimestamp(2).toInstant(), "CHANGE", rs.getString(9) + " " + truncate(rs.getString(10), 120) + " — "
                            + rs.getString(3).replace('_', ' ').toLowerCase(Locale.ROOT) + (rs.getString(6) == null ? "" : ": " + truncate(rs.getString(6), 200)),
                            rs.getString(7), ciId, null, serviceId, ref("CHANGE", rs.getLong(8), rs.getString(9)), diff, false));
                }, tf, tt, max);
        jdbc.query("select d.id, d.started_at, d.number, d.version, d.status, d.environment, d.actor_name, d.service_id, s.name, d.simulated, (select p.version "
                + "from deployments p where p.service_id = d.service_id and p.environment = d.environment and p.started_at < d.started_at and p.status = "
                + "'SUCCEEDED' order by p.started_at desc limit 1) from deployments d join business_services s on s.id = d.service_id where " + serviceFilter
                + " and d.started_at >= ? and d.started_at < ?" + (includeSimulated ? "" : " and not d.simulated") + " order by d.started_at desc limit ?", rs -> {
                    entries.add(new TimelineEntry(rs.getTimestamp(2).toInstant(), "DEPLOYMENT", rs.getString(3) + " " + rs.getString(9) + " " + rs.getString(4)
                            + " to " + rs.getString(6) + " (" + rs.getString(5) + ")", rs.getString(7), ciId, null, rs.getLong(8),
                            ref("DEPLOYMENT", rs.getLong(1), rs.getString(3)), List.of(new DiffEntry("version", rs.getString(11), rs.getString(4))),
                            rs.getBoolean(10)));
                }, tf, tt, max);
        String telemetryScope = global ? "true" : "(r.ci_id in (" + in(ciIds) + ") or r.service_id in (" + in(serviceIds) + "))";
        jdbc.query("select x.id, x.observed_at, x.attribute_key, x.from_value, x.to_value, x.simulated, r.id, r.service_name, r.ci_id, r.service_id from "
                + "telemetry_resource_changes x join telemetry_resources r on r.id = x.resource_id where " + telemetryScope + " and x.observed_at >= ? and "
                + "x.observed_at < ?" + (includeSimulated ? "" : " and not x.simulated") + " order by x.observed_at desc limit ?", rs -> {
                    long rci = rs.getLong(9);
                    boolean noCi = rs.wasNull();
                    long rsvc = rs.getLong(10);
                    boolean noSvc = rs.wasNull();
                    entries.add(new TimelineEntry(rs.getTimestamp(2).toInstant(), "TELEMETRY", rs.getString(3) + " changed on " + (rs.getString(8) == null
                            ? "resource " + rs.getLong(7) : rs.getString(8)) + " (reported by telemetry)", "Telemetry", noCi ? null : rci, null, noSvc ? null : rsvc,
                            ref("TELEMETRY_RESOURCE", rs.getLong(7), null), List.of(new DiffEntry(rs.getString(3), rs.getString(4), rs.getString(5))),
                            rs.getBoolean(6)));
                }, tf, tt, max);
        entries.sort(Comparator.comparing(TimelineEntry::at).reversed());
        boolean truncated = entries.size() > max;
        return new ChangeTimeline(ciId, serviceId, f, t, includeSimulated, truncated ? entries.subList(0, max) : entries, truncated);
    }

    private static Map<String, Object> ref(String type, long id, String number) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        if (number != null) {
            m.put("number", number);
        }
        return m;
    }

    private static Long nullable(long v, boolean wasNull) {
        return wasNull ? null : v;
    }

    static String in(Collection<Long> ids) {
        return ids.isEmpty() ? "-1" : ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    static Map<Layer, Long> emptyLayerCounts() {
        Map<Layer, Long> m = new EnumMap<>(Layer.class);
        for (Layer l : Layer.values()) {
            m.put(l, 0L);
        }
        return m;
    }
}
