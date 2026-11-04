package com.nexus360.observability;

import com.nexus360.admin.SettingsService;
import com.nexus360.alert.AlertDtos.ConvertRequest;
import com.nexus360.alert.AlertDtos.ConvertResult;
import com.nexus360.alert.AlertDtos.LinkIncidentRequest;
import com.nexus360.alert.AlertService;
import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.CmdbGraphService;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.events.DomainEvents.AlertEvent;
import com.nexus360.observability.ObservabilityDtos.ConvertGroupRequest;
import com.nexus360.observability.ObservabilityDtos.GroupDto;
import com.nexus360.observability.ObservabilityDtos.MemberDto;
import com.nexus360.observability.ObservabilityDtos.Reason;
import com.nexus360.security.Actor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Alert and anomaly correlation. When an alert arrives (or reopens) — and for anomalies that raise no alert — the engine scores it
 * against the alerts and anomalies of the last {@code observability.correlation.window_minutes} (same simulated flag, not in a closed
 * or dismissed group):
 *
 * <pre>
 *   time        0.25 × (1 − Δt / window)
 *   topology    same CI 0.35 · same service 0.30 · CMDB distance d ≤ max_distance: 0.35 × (1 − (d − 1) / max_distance)
 *   labels      same fingerprint 0.20, else 0.20 × Jaccard(shared key=value labels)
 *   score       sum, capped at 1; joins when ≥ observability.correlation.min_score (0.5)
 * </pre>
 * The best match decides: join its open group, or open a new group with both. Groups carry {@code reasons[]} (time window, topology,
 * shared labels, deployments and changes on the members' services within the window), a suggested root component (the member CI the
 * most other members depend on, else the earliest) and, when a member alert has an incident, that incident. Humans can link or
 * convert a group to an incident, split members out, merge groups or dismiss a group. Serialised by advisory lock 360015.
 */
@Slf4j
@Service
public class CorrelationService {

    static final long LOCK_KEY = 360_015L;
    static final Set<String> NOISY_LABELS = Set.of("severity", "anomaly", "series", "source", "alertname");

    record Item(String type, long id, String number, Instant at, Long serviceId, Long ciId, Map<String, String> labels, String fingerprint, String title,
                String severity, String status, boolean simulated, Long incidentId) {}

    record Scored(Item item, double score, List<Reason> reasons) {}

    private final JdbcTemplate jdbc;
    private final CmdbGraphService graph;
    private final AlertService alerts;
    private final SettingsService settings;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate newTx;
    /**
     * Runs alert correlation off the committing thread: an AFTER_COMMIT listener still holds the alert transaction's
     * connection, so correlating inline (in a new transaction) would need a second connection per alert and can starve the
     * pool under load. A single worker keeps groups serialised in-process; the advisory lock serialises across instances.
     * Integration tests correlate synchronously (`app.observability.correlation-async=false`) for deterministic results.
     * Deliberately not a Spring {@code Executor} bean: one would make Boot drop its bounded {@code applicationTaskExecutor}.
     */
    private final java.util.concurrent.ExecutorService correlationExecutor;

    public CorrelationService(JdbcTemplate jdbc, CmdbGraphService graph, AlertService alerts, SettingsService settings, AuditService audit, Clock clock,
                              PlatformTransactionManager transactions,
                              @org.springframework.beans.factory.annotation.Value("${app.observability.correlation-async:true}") boolean async) {
        this.correlationExecutor = async
                ? new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<>(10_000), r -> {
                            Thread t = new Thread(r, "correlation-worker");
                            t.setDaemon(true);
                            return t;
                        })
                : null;
        this.jdbc = jdbc;
        this.graph = graph;
        this.alerts = alerts;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Re-evaluated on every new or reopened (unsuppressed) alert, after its transaction committed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAlert(AlertEvent event) {
        if (!"RECEIVED".equals(event.type())) {
            return;
        }
        long alertId = event.alertId();
        if (correlationExecutor == null) {
            try {
                correlateAlert(alertId);
            } catch (RuntimeException e) {
                log.error("Correlation of alert {} failed", alertId, e);
            }
            return;
        }
        try {
            correlationExecutor.execute(() -> {
                try {
                    correlateAlert(alertId);
                } catch (RuntimeException e) {
                    log.error("Correlation of alert {} failed", alertId, e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("Correlation queue full; alert {} was not correlated automatically", alertId);
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() throws InterruptedException {
        if (correlationExecutor != null) {
            correlationExecutor.shutdown();
            correlationExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    public Long correlateAlert(long alertId) {
        return newTx.execute(s -> {
            lock();
            Item item = alertItem(alertId);
            return item == null ? null : correlate(item);
        });
    }

    /** Anomalies that raised no alert are correlated directly (their alert, when there is one, is correlated instead). */
    public Long correlateAnomaly(long anomalyId) {
        return newTx.execute(s -> {
            lock();
            Item item = anomalyItem(anomalyId);
            return item == null ? null : correlate(item);
        });
    }

    private void lock() {
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> { }, LOCK_KEY);
    }

    private Long correlate(Item x) {
        if (memberGroup(x) != null) {
            return memberGroup(x);
        }
        Duration window = Duration.ofMinutes(Math.max(1, settings.getInt("observability.correlation.window_minutes", 30)));
        int maxDistance = Math.max(1, settings.getInt("observability.correlation.max_distance", 3));
        double minScore = settings.getDouble("observability.correlation.min_score", 0.5);
        Topology topology = new Topology(graph.dependencyEdges(), jdbc);
        List<Item> candidates = candidates(x, window);
        Scored best = null;
        for (Item c : candidates) {
            Scored s = score(x, c, window, maxDistance, topology);
            if (best == null || s.score() > best.score()) {
                best = s;
            }
        }
        if (best == null || best.score() < minScore) {
            return null;
        }
        Instant now = clock.instant();
        Long groupId = memberGroup(best.item());
        if (groupId == null) {
            groupId = jdbc.queryForObject("insert into correlation_groups (title, status, first_seen_at, last_activity_at, simulated, created_at, updated_at) values "
                    + "(?, 'OPEN', ?, ?, ?, ?, ?) returning id", Long.class, OtlpJsonParser.truncate(best.item().title(), 300),
                    Timestamp.from(min(x.at(), best.item().at())), Timestamp.from(max(x.at(), best.item().at())), x.simulated(), Timestamp.from(now),
                    Timestamp.from(now));
            addMember(groupId, best.item(), best.score(), List.of(new Reason("SEED", "Correlated with " + label(x), best.score())), "SA Correlation", now);
        }
        addMember(groupId, x, best.score(), best.reasons(), "SA Correlation", now);
        recompute(groupId, topology, window);
        return groupId;
    }

    // ------------------------------------------------------------------ scoring

    Scored score(Item x, Item c, Duration window, int maxDistance, Topology topology) {
        List<Reason> reasons = new ArrayList<>();
        double total = 0;
        long dt = Math.abs(Duration.between(x.at(), c.at()).toSeconds());
        double t = 0.25 * Math.max(0, 1 - (double) dt / Math.max(1, window.toSeconds()));
        if (t > 0) {
            total += t;
            reasons.add(new Reason("TIME_WINDOW", "Within " + Math.max(1, Math.round(dt / 60.0)) + " min of " + label(c), round(t)));
        }
        Long xn = topology.node(x);
        Long cn = topology.node(c);
        int d = xn != null && cn != null && !xn.equals(cn) ? topology.distance(xn, cn, maxDistance) : 0;
        double topo = d > 0 ? 0.35 * (1 - (double) (d - 1) / maxDistance) : 0;
        boolean sameService = x.serviceId() != null && x.serviceId().equals(c.serviceId());
        if (xn != null && xn.equals(cn)) {
            total += 0.35;
            reasons.add(new Reason("SAME_CI", "Same configuration item as " + label(c), 0.35));
        } else if (d > 0 && (topo >= 0.30 || !sameService)) {
            total += topo;
            reasons.add(new Reason("TOPOLOGY", topology.name(xn) + " is " + d + (d == 1 ? " hop" : " hops") + " from " + topology.name(cn)
                    + " in the CMDB", round(topo)));
        } else if (sameService) {
            total += 0.30;
            reasons.add(new Reason("SAME_SERVICE", "Same service as " + label(c), 0.30));
        }
        if (x.fingerprint() != null && x.fingerprint().equals(c.fingerprint())) {
            total += 0.20;
            reasons.add(new Reason("SAME_FINGERPRINT", "Same fingerprint as " + label(c), 0.20));
        } else {
            Set<String> a = pairs(x.labels());
            Set<String> b = pairs(c.labels());
            Set<String> shared = new LinkedHashSet<>(a);
            shared.retainAll(b);
            if (!shared.isEmpty()) {
                Set<String> union = new HashSet<>(a);
                union.addAll(b);
                double w = 0.20 * shared.size() / union.size();
                total += w;
                reasons.add(new Reason("SHARED_LABELS", "Shares " + String.join(", ", shared.stream().limit(4).toList()) + " with " + label(c), round(w)));
            }
        }
        return new Scored(c, round(Math.min(1.0, total)), reasons);
    }

    private static Set<String> pairs(Map<String, String> labels) {
        Set<String> out = new LinkedHashSet<>();
        if (labels != null) {
            labels.forEach((k, v) -> {
                if (!NOISY_LABELS.contains(k) && v != null && !v.isBlank()) {
                    out.add(k + "=" + v);
                }
            });
        }
        return out;
    }

    /** CMDB topology: undirected adjacency of CIs; services are represented by their BUSINESS_SERVICE CI. */
    static final class Topology {
        private final Map<Long, Set<Long>> adjacent = new HashMap<>();
        private final Map<Long, Long> serviceCi = new HashMap<>();
        private final Map<Long, String> names = new HashMap<>();
        private final JdbcTemplate jdbc;
        private final Map<Long, List<Long>> dependents = new HashMap<>();

        Topology(List<CmdbGraphService.DependencyEdge> edges, JdbcTemplate jdbc) {
            this.jdbc = jdbc;
            for (CmdbGraphService.DependencyEdge e : edges) {
                adjacent.computeIfAbsent(e.dependentId(), k -> new HashSet<>()).add(e.dependencyId());
                adjacent.computeIfAbsent(e.dependencyId(), k -> new HashSet<>()).add(e.dependentId());
                dependents.computeIfAbsent(e.dependencyId(), k -> new ArrayList<>()).add(e.dependentId());
            }
        }

        Long node(Item i) {
            if (i.ciId() != null) {
                return i.ciId();
            }
            if (i.serviceId() == null) {
                return null;
            }
            return serviceCi.computeIfAbsent(i.serviceId(), sid -> jdbc.query("select id from configuration_items where service_id = ?",
                    rs -> rs.next() ? rs.getLong(1) : -1L, sid)) == -1L ? null : serviceCi.get(i.serviceId());
        }

        String name(Long ci) {
            return names.computeIfAbsent(ci, id -> jdbc.query("select name from configuration_items where id = ?", rs -> rs.next() ? rs.getString(1)
                    : "CI " + id, id));
        }

        /** Undirected hop distance (1..max), 0 when not connected within {@code max} hops. */
        int distance(long from, long to, int max) {
            if (from == to) {
                return 0;
            }
            Map<Long, Integer> depth = new HashMap<>();
            depth.put(from, 0);
            Deque<Long> queue = new ArrayDeque<>(List.of(from));
            while (!queue.isEmpty()) {
                long cur = queue.poll();
                int d = depth.get(cur);
                if (d >= max) {
                    continue;
                }
                for (Long next : adjacent.getOrDefault(cur, Set.of())) {
                    if (depth.containsKey(next)) {
                        continue;
                    }
                    if (next == to) {
                        return d + 1;
                    }
                    depth.put(next, d + 1);
                    queue.add(next);
                }
            }
            return 0;
        }

        /** CIs that (transitively, within {@code max} hops) depend on {@code ci}. */
        Set<Long> dependentsOf(long ci, int max) {
            Set<Long> out = new HashSet<>();
            Map<Long, Integer> depth = new HashMap<>(Map.of(ci, 0));
            Deque<Long> queue = new ArrayDeque<>(List.of(ci));
            while (!queue.isEmpty()) {
                long cur = queue.poll();
                if (depth.get(cur) >= max) {
                    continue;
                }
                for (Long next : dependents.getOrDefault(cur, List.of())) {
                    if (depth.putIfAbsent(next, depth.get(cur) + 1) == null) {
                        out.add(next);
                        queue.add(next);
                    }
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ items

    private static final String ALERT_ITEM = "select a.id, a.number, coalesce(a.starts_at, a.first_seen_at), a.service_id, a.ci_id, a.labels::text, a.fingerprint, "
            + "a.title, a.severity, a.status, a.simulated, a.incident_id from alerts a";

    private Item alertItem(long id) {
        return jdbc.query(ALERT_ITEM + " where a.id = ?", (rs, n) -> alertRow(rs), id).stream().findFirst().orElse(null);
    }

    private static Item alertRow(ResultSet rs) throws SQLException {
        return new Item("ALERT", rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(), MappingRuleService.nullableLong(rs, 4),
                MappingRuleService.nullableLong(rs, 5), TelemetryJson.stringMap(rs.getString(6)), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getBoolean(11), MappingRuleService.nullableLong(rs, 12));
    }

    private static final String ANOMALY_ITEM = "select a.id, a.started_at, a.service_id, a.ci_id, ms.attributes::text, a.metric_name, d.alert_severity, a.status, "
            + "a.simulated, d.name from telemetry_anomalies a join anomaly_detectors d on d.id = a.detector_id join telemetry_metric_series ms on ms.id = a.series_id";

    private Item anomalyItem(long id) {
        return jdbc.query(ANOMALY_ITEM + " where a.id = ? and a.alert_id is null", (rs, n) -> anomalyRow(rs), id).stream().findFirst().orElse(null);
    }

    private static Item anomalyRow(ResultSet rs) throws SQLException {
        Map<String, String> labels = TelemetryJson.stringMap(rs.getString(5));
        labels.put("metric", rs.getString(6));
        return new Item("ANOMALY", rs.getLong(1), "ANOMALY-" + rs.getLong(1), rs.getTimestamp(2).toInstant(), MappingRuleService.nullableLong(rs, 3),
                MappingRuleService.nullableLong(rs, 4), labels, null, "Anomaly: " + rs.getString(6) + " (" + rs.getString(10) + ")", rs.getString(7),
                rs.getString(8), rs.getBoolean(9), null);
    }

    private List<Item> candidates(Item x, Duration window) {
        Timestamp from = Timestamp.from(x.at().minus(window));
        Timestamp to = Timestamp.from(x.at().plus(window));
        String notClosed = " and not exists (select 1 from correlation_group_members m join correlation_groups g on g.id = m.group_id where %s and g.status <> 'OPEN')";
        List<Item> out = new ArrayList<>(jdbc.query(ALERT_ITEM + " where coalesce(a.starts_at, a.first_seen_at) between ? and ? and a.simulated = ? and a.id <> ?"
                + String.format(notClosed, "m.alert_id = a.id") + " order by a.first_seen_at desc limit 200", (rs, n) -> alertRow(rs), from, to, x.simulated(),
                "ALERT".equals(x.type()) ? x.id() : -1L));
        out.addAll(jdbc.query(ANOMALY_ITEM + " where a.alert_id is null and a.started_at between ? and ? and a.simulated = ? and a.id <> ?"
                + String.format(notClosed, "m.anomaly_id = a.id") + " order by a.started_at desc limit 100", (rs, n) -> anomalyRow(rs), from, to,
                x.simulated(), "ANOMALY".equals(x.type()) ? x.id() : -1L));
        return out;
    }

    private Long memberGroup(Item i) {
        List<Long> g = jdbc.queryForList("select group_id from correlation_group_members where " + ("ALERT".equals(i.type()) ? "alert_id" : "anomaly_id")
                + " = ?", Long.class, i.id());
        return g.isEmpty() ? null : g.get(0);
    }

    private void addMember(long groupId, Item i, double score, List<Reason> reasons, String addedBy, Instant now) {
        jdbc.update("insert into correlation_group_members (group_id, member_type, alert_id, anomaly_id, score, reasons, added_by, joined_at, simulated) values "
                + "(?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)", groupId, i.type(), "ALERT".equals(i.type()) ? i.id() : null, "ANOMALY".equals(i.type()) ? i.id() : null,
                score, TelemetryJson.write(reasons), addedBy, Timestamp.from(now), i.simulated());
    }

    // ------------------------------------------------------------------ group state

    /** Recomputes counts, time range, score, reasons, root suggestion, title and incident link of a group. */
    void recompute(long groupId, Topology topology, Duration window) {
        List<Item> members = members(groupId);
        Instant now = clock.instant();
        if (members.isEmpty()) {
            jdbc.update("update correlation_groups set member_count = 0, status = case when status = 'OPEN' then 'CLOSED' else status end, updated_at = ? "
                    + "where id = ?", Timestamp.from(now), groupId);
            return;
        }
        Topology topo = topology != null ? topology : new Topology(graph.dependencyEdges(), jdbc);
        Duration w = window != null ? window : Duration.ofMinutes(Math.max(1, settings.getInt("observability.correlation.window_minutes", 30)));
        Instant first = members.stream().map(Item::at).min(Instant::compareTo).orElse(now);
        Instant last = members.stream().map(Item::at).max(Instant::compareTo).orElse(now);
        record MemberScore(double score, String reasons) {}
        List<MemberScore> scores = jdbc.query("select score, reasons::text from correlation_group_members where group_id = ?", (rs, n) -> new MemberScore(
                rs.getDouble(1), rs.getString(2)), groupId);
        double score = scores.stream().mapToDouble(MemberScore::score).max().orElse(0);
        Map<String, Reason> byCode = new LinkedHashMap<>();
        for (MemberScore m : scores) {
            for (Map<String, Object> r : TelemetryJson.objectList(m.reasons())) {
                String code = String.valueOf(r.get("code"));
                double weight = r.get("weight") instanceof Number num ? num.doubleValue() : 0;
                if ("SEED".equals(code)) {
                    continue;
                }
                Reason existing = byCode.get(code);
                if (existing == null || weight > existing.weight()) {
                    byCode.put(code, new Reason(code, String.valueOf(r.get("text")), weight));
                }
            }
        }
        boolean simulated = members.stream().allMatch(Item::simulated);
        Set<Long> serviceIds = members.stream().map(Item::serviceId).filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!serviceIds.isEmpty()) {
            String in = serviceIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbc.query("select d.number, s.name, d.version, d.started_at from deployments d join business_services s on s.id = d.service_id where d.service_id in ("
                    + in + ") and d.started_at between ? and ?" + (simulated ? "" : " and not d.simulated") + " order by d.started_at desc limit 3", rs -> {
                        long minutes = Duration.between(rs.getTimestamp(4).toInstant(), first).toMinutes();
                        byCode.putIfAbsent("DEPLOYMENT", new Reason("DEPLOYMENT", "Deployment " + rs.getString(1) + " of " + rs.getString(2) + " "
                                + rs.getString(3) + (minutes >= 0 ? " " + minutes + " min before the first alert" : " during the group"), 0.2));
                    }, Timestamp.from(first.minus(w)), Timestamp.from(last));
            jdbc.query("select c.number, c.title, coalesce(c.actual_start, c.planned_start) from changes c where c.status in ('IMPLEMENTING', 'COMPLETED', "
                    + "'FAILED', 'ROLLED_BACK') and coalesce(c.actual_start, c.planned_start) between ? and ? and c.id in (select cs.change_id from "
                    + "change_services cs where cs.service_id in (" + in + ")) order by 3 desc limit 3", rs -> {
                        byCode.putIfAbsent("CHANGE", new Reason("CHANGE", "Change " + rs.getString(1) + " (" + OtlpJsonParser.truncate(rs.getString(2), 80)
                                + ") started within the window", 0.2));
                    }, Timestamp.from(first.minus(w)), Timestamp.from(last));
        }
        // Root suggestion: the member CI that the most other members depend on (within 6 hops), else the earliest member's CI.
        Long rootCi = null;
        int bestCount = -1;
        Instant bestAt = null;
        Map<Item, Long> nodes = new LinkedHashMap<>();
        for (Item m : members) {
            nodes.put(m, topo.node(m));
        }
        for (Map.Entry<Item, Long> e : nodes.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            Set<Long> deps = topo.dependentsOf(e.getValue(), 6);
            int count = (int) nodes.values().stream().filter(n -> n != null && !n.equals(e.getValue()) && deps.contains(n)).count();
            if (count > bestCount || (count == bestCount && e.getKey().at().isBefore(bestAt))) {
                bestCount = count;
                rootCi = e.getValue();
                bestAt = e.getKey().at();
            }
        }
        String rootReason = null;
        Long rootService = null;
        if (rootCi != null) {
            rootService = jdbc.query("select service_id from configuration_items where id = ?", rs -> rs.next() ? MappingRuleService.nullableLong(rs, 1) : null,
                    rootCi);
            if (rootService == null) {
                Long finalRoot = rootCi;
                rootService = members.stream().filter(m -> finalRoot.equals(m.ciId())).map(Item::serviceId).filter(Objects::nonNull).findFirst().orElse(null);
            }
            rootReason = bestCount > 0 ? topo.name(rootCi) + ": " + bestCount + " other member(s) depend on it in the CMDB"
                    : topo.name(rootCi) + ": earliest member (no dependency between the members' components)";
            if (byCode.containsKey("DEPLOYMENT")) {
                rootReason += "; " + byCode.get("DEPLOYMENT").text();
            }
        }
        Long incidentId = jdbc.query("select incident_id from correlation_groups where id = ?", rs -> rs.next() ? MappingRuleService.nullableLong(rs, 1) : null,
                groupId);
        if (incidentId == null) {
            incidentId = members.stream().map(Item::incidentId).filter(Objects::nonNull).findFirst().orElse(null);
            if (incidentId != null) {
                String number = jdbc.queryForObject("select number from incidents where id = ?", String.class, incidentId);
                byCode.putIfAbsent("INCIDENT", new Reason("INCIDENT", "Member alert raised incident " + number, 0));
            }
        }
        String rootName = rootCi == null ? null : topo.name(rootCi);
        String title = (rootName != null ? rootName : members.get(0).title()) + ": " + members.size() + " correlated "
                + (members.stream().allMatch(m -> "ALERT".equals(m.type())) ? "alerts" : "signals");
        List<Reason> reasons = new ArrayList<>(byCode.values());
        reasons.sort(Comparator.comparingDouble(Reason::weight).reversed());
        jdbc.update("update correlation_groups set title = ?, score = ?, reasons = ?::jsonb, root_ci_id = ?, root_service_id = ?, root_reason = ?, incident_id = ?, "
                + "first_seen_at = ?, last_activity_at = greatest(last_activity_at, ?), member_count = ?, simulated = ?, updated_at = ? where id = ?",
                OtlpJsonParser.truncate(title, 300), score, TelemetryJson.write(reasons), rootCi, rootService, OtlpJsonParser.truncate(rootReason, 1000),
                incidentId, Timestamp.from(first), Timestamp.from(last), members.size(), simulated, Timestamp.from(now), groupId);
    }

    private List<Item> members(long groupId) {
        List<Item> out = new ArrayList<>(jdbc.query(ALERT_ITEM + " join correlation_group_members m on m.alert_id = a.id where m.group_id = ? order by "
                + "a.first_seen_at", (rs, n) -> alertRow(rs), groupId));
        out.addAll(jdbc.query(ANOMALY_ITEM + " join correlation_group_members m on m.anomaly_id = a.id where m.group_id = ? order by a.started_at",
                (rs, n) -> anomalyRow(rs), groupId));
        out.sort(Comparator.comparing(Item::at));
        return out;
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public PageResponse<GroupDto> list(String status, boolean includeSimulated, Integer page, Integer size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where g.member_count > 0");
        if (!includeSimulated) {
            where.append(" and not g.simulated");
        }
        if (status != null && !status.isBlank()) {
            String st = status.trim().toUpperCase(Locale.ROOT);
            if (!List.of("OPEN", "CLOSED", "DISMISSED").contains(st)) {
                throw ApiException.invalidField("status", "status must be OPEN, CLOSED or DISMISSED");
            }
            where.append(" and g.status = ?");
            args.add(st);
        }
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, PageResponse.MAX_PAGE_SIZE);
        Long total = jdbc.queryForObject("select count(*) from correlation_groups g" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<GroupDto> content = jdbc.query(GROUP_SELECT + where + " order by g.last_activity_at desc, g.id desc limit ? offset ?", (rs, n) -> groupRow(rs, null),
                pageArgs.toArray());
        long t = total == null ? 0 : total;
        return new PageResponse<>(content, p, s, t, (int) ((t + s - 1) / s));
    }

    @Transactional(readOnly = true)
    public GroupDto get(Long id) {
        GroupDto g = jdbc.query(GROUP_SELECT + " where g.id = ?", (rs, n) -> groupRow(rs, null), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Correlation group", id));
        List<MemberDto> members = jdbc.query("select m.id, m.member_type, m.alert_id, a.number, m.anomaly_id, coalesce(a.title, 'Anomaly: ' || an.metric_name), "
                + "coalesce(a.severity, d.alert_severity), coalesce(a.status, an.status), coalesce(a.service_id, an.service_id), s.name, coalesce(a.ci_id, "
                + "an.ci_id), c.name, coalesce(a.starts_at, a.first_seen_at, an.started_at), m.score, m.reasons::text, m.added_by, m.joined_at, m.simulated "
                + "from correlation_group_members m left join alerts a on a.id = m.alert_id left join telemetry_anomalies an on an.id = m.anomaly_id left join "
                + "anomaly_detectors d on d.id = an.detector_id left join business_services s on s.id = coalesce(a.service_id, an.service_id) left join "
                + "configuration_items c on c.id = coalesce(a.ci_id, an.ci_id) where m.group_id = ? order by 13", (rs, n) -> new MemberDto(rs.getLong(1),
                rs.getString(2), MappingRuleService.nullableLong(rs, 3), rs.getString(4), MappingRuleService.nullableLong(rs, 5), rs.getString(6),
                rs.getString(7), rs.getString(8), MappingRuleService.nullableLong(rs, 9), rs.getString(10), MappingRuleService.nullableLong(rs, 11),
                rs.getString(12), rs.getTimestamp(13).toInstant(), rs.getDouble(14), reasons(rs.getString(15)), rs.getString(16),
                rs.getTimestamp(17).toInstant(), rs.getBoolean(18)), id);
        return new GroupDto(g.id(), g.title(), g.status(), g.score(), g.reasons(), g.memberCount(), g.rootCiId(), g.rootCiName(), g.rootServiceId(),
                g.rootServiceName(), g.rootReason(), g.incidentId(), g.incidentNumber(), g.firstSeenAt(), g.lastActivityAt(), g.highestSeverity(),
                g.simulated(), g.dismissedReason(), g.dismissedByName(), g.dismissedAt(), members);
    }

    // ------------------------------------------------------------------ human actions

    /** Links the group (and its member alerts that have no incident) to an existing incident. */
    @Transactional
    public GroupDto link(Long id, Long incidentId, Actor actor) {
        GroupDto g = requireOpen(id);
        if (jdbc.queryForObject("select count(*) from incidents where id = ?", Long.class, incidentId) == 0) {
            throw ApiException.invalidField("incidentId", "Incident does not exist");
        }
        for (Long alertId : jdbc.queryForList("select a.id from correlation_group_members m join alerts a on a.id = m.alert_id where m.group_id = ? and "
                + "a.incident_id is null", Long.class, id)) {
            alerts.linkIncident(alertId, new LinkIncidentRequest(incidentId), actor);
        }
        jdbc.update("update correlation_groups set incident_id = ?, updated_at = ? where id = ?", incidentId, Timestamp.from(clock.instant()), id);
        audit.record("CORRELATION_GROUP_LINKED", "CORRELATION_GROUP", id, Map.of("incidentId", incidentId, "members", g.memberCount()));
        return get(id);
    }

    /** Creates an incident from the group's root alert (intake pipeline) and links the other member alerts to it. */
    @Transactional
    public GroupDto convert(Long id, ConvertGroupRequest r, Actor actor) {
        GroupDto g = requireOpen(id);
        if (g.incidentId() != null) {
            throw ApiException.conflict("The group is already linked to " + g.incidentNumber());
        }
        List<Long> alertIds = jdbc.queryForList("select a.id from correlation_group_members m join alerts a on a.id = m.alert_id where m.group_id = ? order by "
                + "(a.ci_id is not distinct from ?) desc, case a.severity when 'CRITICAL' then 1 when 'MAJOR' then 2 when 'MINOR' then 3 when 'WARNING' then 4 "
                + "else 5 end, a.first_seen_at", Long.class, id, g.rootCiId());
        if (alertIds.isEmpty()) {
            throw ApiException.conflict("The group has no alerts to convert (anomalies only): link it to an incident instead");
        }
        Long rootAlert = jdbc.queryForObject("select id from alerts where id = ? ", Long.class, alertIds.get(0));
        Long existing = jdbc.query("select incident_id from alerts where id = ?", rs -> rs.next() ? MappingRuleService.nullableLong(rs, 1) : null, rootAlert);
        long incidentId;
        if (existing != null) {
            incidentId = existing;
        } else {
            String title = r == null || r.title() == null ? OtlpJsonParser.truncate(g.title(), 200) : r.title();
            ConvertResult result = alerts.convert(rootAlert, new ConvertRequest(title.length() < 5 ? title + " (group)" : title, groupDescription(g),
                    r == null ? null : r.impact(), r == null ? null : r.urgency()), actor);
            incidentId = result.incidentId();
        }
        for (Long alertId : alertIds) {
            Long current = jdbc.query("select incident_id from alerts where id = ?", rs -> rs.next() ? MappingRuleService.nullableLong(rs, 1) : null, alertId);
            if (current == null) {
                alerts.linkIncident(alertId, new LinkIncidentRequest(incidentId), actor);
            }
        }
        jdbc.update("update correlation_groups set incident_id = ?, updated_at = ? where id = ?", incidentId, Timestamp.from(clock.instant()), id);
        audit.record("CORRELATION_GROUP_CONVERTED", "CORRELATION_GROUP", id, Map.of("incidentId", incidentId, "members", g.memberCount()));
        return get(id);
    }

    private String groupDescription(GroupDto g) {
        StringBuilder sb = new StringBuilder("Created from correlation group #" + g.id() + " (" + g.memberCount() + " members, score " + g.score() + ").");
        if (g.rootReason() != null) {
            sb.append("\nSuggested root component: ").append(g.rootReason());
        }
        for (Reason r : g.reasons()) {
            sb.append("\n- ").append(r.text());
        }
        String out = sb.toString();
        return out.length() > 5000 ? out.substring(0, 5000) : out;
    }

    /** Moves the given members into a new group. */
    @Transactional
    public GroupDto split(Long id, List<Long> memberIds, String title, Actor actor) {
        GroupDto g = requireOpen(id);
        Set<Long> ids = new LinkedHashSet<>(memberIds);
        List<Long> present = jdbc.queryForList("select id from correlation_group_members where group_id = ? and id in (" + ids.stream().map(String::valueOf)
                .collect(Collectors.joining(",")) + ")", Long.class, id);
        if (present.size() != ids.size()) {
            throw ApiException.badRequest("Every memberId must be a member of this group");
        }
        if (present.size() >= g.memberCount()) {
            throw ApiException.badRequest("Splitting needs at least one member to stay in the group");
        }
        Instant now = clock.instant();
        Long newGroup = jdbc.queryForObject("insert into correlation_groups (title, status, first_seen_at, last_activity_at, simulated, created_at, updated_at) "
                + "values (?, 'OPEN', ?, ?, ?, ?, ?) returning id", Long.class, title == null || title.isBlank() ? "Split from group #" + id : title.trim(),
                Timestamp.from(g.firstSeenAt()), Timestamp.from(g.lastActivityAt()), g.simulated(), Timestamp.from(now), Timestamp.from(now));
        String manual = TelemetryJson.write(List.of(new Reason("MANUAL", "Split from group #" + id + " by " + actor.name(), 0)));
        jdbc.update("update correlation_group_members set group_id = ?, added_by = ?, reasons = ?::jsonb where id in (" + present.stream().map(String::valueOf)
                .collect(Collectors.joining(",")) + ")", newGroup, actor.name(), manual);
        jdbc.update("update correlation_groups set incident_id = null where id = ?", newGroup);
        recompute(id, null, null);
        recompute(newGroup, null, null);
        audit.record("CORRELATION_GROUP_SPLIT", "CORRELATION_GROUP", id, Map.of("newGroupId", newGroup, "members", present.size()));
        return get(newGroup);
    }

    /** Moves every member of the given groups into this group and closes them. */
    @Transactional
    public GroupDto merge(Long id, List<Long> groupIds, Actor actor) {
        requireOpen(id);
        Set<Long> others = new LinkedHashSet<>(groupIds);
        others.remove(id);
        if (others.isEmpty()) {
            throw ApiException.badRequest("groupIds must name at least one other group");
        }
        for (Long other : others) {
            requireOpen(other);
        }
        String in = others.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("update correlation_group_members set group_id = ?, added_by = ? where group_id in (" + in + ")", id, actor.name());
        jdbc.update("update correlation_groups set status = 'CLOSED', member_count = 0, root_reason = ?, updated_at = ? where id in (" + in + ")",
                "Merged into group #" + id, Timestamp.from(clock.instant()));
        recompute(id, null, null);
        audit.record("CORRELATION_GROUP_MERGED", "CORRELATION_GROUP", id, Map.of("merged", List.copyOf(others)));
        return get(id);
    }

    @Transactional
    public GroupDto dismiss(Long id, String reason, Actor actor) {
        requireOpen(id);
        Instant now = clock.instant();
        jdbc.update("update correlation_groups set status = 'DISMISSED', dismissed_reason = ?, dismissed_by_name = ?, dismissed_at = ?, updated_at = ? where id = ?",
                reason.trim(), actor.name(), Timestamp.from(now), Timestamp.from(now), id);
        audit.record("CORRELATION_GROUP_DISMISSED", "CORRELATION_GROUP", id, Map.of("reason", reason.trim()));
        return get(id);
    }

    private GroupDto requireOpen(Long id) {
        GroupDto g = jdbc.query(GROUP_SELECT + " where g.id = ?", (rs, n) -> groupRow(rs, null), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Correlation group", id));
        if (!"OPEN".equals(g.status())) {
            throw ApiException.conflict("Correlation group #" + id + " is " + g.status());
        }
        return g;
    }

    // ------------------------------------------------------------------ mapping

    private static final String GROUP_SELECT = "select g.id, g.title, g.status, g.score, g.reasons::text, g.member_count, g.root_ci_id, c.name, g.root_service_id, "
            + "s.name, g.root_reason, g.incident_id, i.number, g.first_seen_at, g.last_activity_at, (select a.severity from correlation_group_members m join "
            + "alerts a on a.id = m.alert_id where m.group_id = g.id order by case a.severity when 'CRITICAL' then 1 when 'MAJOR' then 2 when 'MINOR' then 3 "
            + "when 'WARNING' then 4 else 5 end limit 1), g.simulated, g.dismissed_reason, g.dismissed_by_name, g.dismissed_at from correlation_groups g "
            + "left join configuration_items c on c.id = g.root_ci_id left join business_services s on s.id = g.root_service_id left join incidents i on "
            + "i.id = g.incident_id";

    private static GroupDto groupRow(ResultSet rs, List<MemberDto> members) throws SQLException {
        return new GroupDto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4), reasons(rs.getString(5)), rs.getInt(6),
                MappingRuleService.nullableLong(rs, 7), rs.getString(8), MappingRuleService.nullableLong(rs, 9), rs.getString(10), rs.getString(11),
                MappingRuleService.nullableLong(rs, 12), rs.getString(13), rs.getTimestamp(14).toInstant(), rs.getTimestamp(15).toInstant(), rs.getString(16),
                rs.getBoolean(17), rs.getString(18), rs.getString(19), MappingRuleService.instant(rs, 20), members);
    }

    static List<Reason> reasons(String json) {
        List<Reason> out = new ArrayList<>();
        for (Map<String, Object> r : TelemetryJson.objectList(json)) {
            out.add(new Reason(String.valueOf(r.get("code")), String.valueOf(r.get("text")), r.get("weight") instanceof Number n ? n.doubleValue() : 0));
        }
        return out;
    }

    private static String label(Item i) {
        return i.number() != null ? i.number() : i.type() + " " + i.id();
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
