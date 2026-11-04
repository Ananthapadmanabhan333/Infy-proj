package com.nexus360.cmdb;

import com.nexus360.cmdb.CmdbDtos.AlertBrief;
import com.nexus360.cmdb.CmdbDtos.CiRef;
import com.nexus360.cmdb.CmdbDtos.Graph;
import com.nexus360.cmdb.CmdbDtos.GraphEdge;
import com.nexus360.cmdb.CmdbDtos.GraphNode;
import com.nexus360.cmdb.CmdbDtos.ImpactAnalysis;
import com.nexus360.cmdb.CmdbDtos.ImpactNode;
import com.nexus360.cmdb.CmdbDtos.ImpactedService;
import com.nexus360.cmdb.CmdbDtos.RelationshipType;
import com.nexus360.cmdb.ConfigurationItem.CiType;
import com.nexus360.common.ApiException;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CMDB traversal: impact analysis (upstream = what a CI depends on, downstream = what depends on it), the dependency graph for
 * the visual map, and the nearest business service of a CI (used to attach alerts to services). Every traversal is a
 * breadth-first search with a visited set, a depth limit and a node limit, so cycles in the relationship data are harmless.
 */
@Service
@RequiredArgsConstructor
public class CmdbGraphService {

    public static final int MAX_DEPTH = 10;
    public static final int DEFAULT_DEPTH = 5;
    public static final int MAX_NODES = 500;
    public static final int MAX_GRAPH_NODES = 300;

    /** One relationship; {@code dependent} needs {@code dependency} (normalised from the relationship type). */
    record Edge(long id, long source, long target, RelationshipType type, String origin) {
        long dependent() {
            return type.sourceDependsOnTarget() ? source : target;
        }

        long dependency() {
            return type.sourceDependsOnTarget() ? target : source;
        }
    }

    /** A CI reached by traversal. */
    record Hop(long ciId, int depth, RelationshipType via, long from) {}

    /**
     * One relationship normalised to dependent → dependency with its free-form attributes (Release 4 digital twin and alert
     * correlation; {@code {"redundant": true}} marks a dependency that has a redundant counterpart).
     */
    public record DependencyEdge(long relationshipId, long dependentId, long dependencyId, RelationshipType type, String origin,
                                 Map<String, Object> attributes) {
        public boolean redundant() {
            Object v = attributes == null ? null : attributes.get("redundant");
            return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper ATTRIBUTES_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final ConfigurationItemRepository cis;
    private final IncidentRepository incidents;
    private final IncidentMapper incidentMapper;
    private final JdbcTemplate jdbc;

    /** Every relationship as a dependency edge (dependent needs dependency), with attributes. */
    @Transactional(readOnly = true)
    public List<DependencyEdge> dependencyEdges() {
        return jdbc.query("select id, source_id, target_id, rel_type, origin, attributes::text from ci_relationships", (rs, n) -> {
            RelationshipType type = RelationshipType.valueOf(rs.getString(4));
            long source = rs.getLong(2);
            long target = rs.getLong(3);
            Map<String, Object> attributes;
            try {
                attributes = ATTRIBUTES_JSON.readValue(rs.getString(6) == null ? "{}" : rs.getString(6),
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                attributes = Map.of();
            }
            return new DependencyEdge(rs.getLong(1), type.sourceDependsOnTarget() ? source : target, type.sourceDependsOnTarget() ? target : source,
                    type, rs.getString(5), attributes);
        });
    }

    List<Edge> edges() {
        return jdbc.query("select id, source_id, target_id, rel_type, origin from ci_relationships", (rs, n) -> new Edge(rs.getLong(1), rs.getLong(2),
                rs.getLong(3), RelationshipType.valueOf(rs.getString(4)), rs.getString(5)));
    }

    /**
     * Breadth-first traversal from {@code start}: {@code upstream} follows dependent → dependency, otherwise dependency → dependent.
     * The start CI is not included. Stops at {@code depth} hops or {@link #MAX_NODES} CIs ({@code truncated[0]} is set).
     */
    static List<Hop> traverse(long start, boolean upstream, int depth, List<Edge> edges, boolean[] truncated) {
        Map<Long, List<Edge>> next = new HashMap<>();
        for (Edge e : edges) {
            next.computeIfAbsent(upstream ? e.dependent() : e.dependency(), k -> new ArrayList<>()).add(e);
        }
        Set<Long> visited = new LinkedHashSet<>();
        visited.add(start);
        List<Hop> out = new ArrayList<>();
        Deque<Hop> queue = new ArrayDeque<>();
        queue.add(new Hop(start, 0, null, start));
        while (!queue.isEmpty()) {
            Hop h = queue.poll();
            if (h.depth() >= depth) {
                continue;
            }
            for (Edge e : next.getOrDefault(h.ciId(), List.of())) {
                long other = upstream ? e.dependency() : e.dependent();
                if (!visited.add(other)) {
                    continue;
                }
                if (out.size() >= MAX_NODES) {
                    truncated[0] = true;
                    return out;
                }
                Hop hop = new Hop(other, h.depth() + 1, e.type(), h.ciId());
                out.add(hop);
                queue.add(hop);
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ImpactAnalysis impact(Long id, String direction, Integer depth) {
        ConfigurationItem root = cis.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Configuration item", id));
        String dir = direction == null || direction.isBlank() ? "both" : direction.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("both", "upstream", "downstream").contains(dir)) {
            throw ApiException.badRequest("direction must be upstream, downstream or both");
        }
        int d = clampDepth(depth, DEFAULT_DEPTH);
        List<Edge> edges = edges();
        boolean[] truncated = {false};
        List<Hop> up = dir.equals("downstream") ? List.of() : traverse(id, true, d, edges, truncated);
        List<Hop> down = dir.equals("upstream") ? List.of() : traverse(id, false, d, edges, truncated);
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(id);
        up.forEach(h -> ids.add(h.ciId()));
        down.forEach(h -> ids.add(h.ciId()));
        Map<Long, ConfigurationItem> byId = cis.findAllWithDetails(ids).stream().collect(Collectors.toMap(ConfigurationItem::getId, c -> c));
        // Business services impacted: the CI itself and everything that depends on it (only when downstream is analysed).
        Set<Long> impactedCis = new LinkedHashSet<>();
        impactedCis.add(id);
        if (!dir.equals("upstream")) {
            down.forEach(h -> impactedCis.add(h.ciId()));
        }
        Map<Long, String> statuses = serviceStatuses();
        List<ImpactedService> services = impactedCis.stream().map(byId::get).filter(c -> c != null && c.getService() != null)
                .map(c -> new ImpactedService(c.getId(), c.getService().getId(), c.getService().getCode(), c.getService().getName(),
                        c.getService().getTier(), statuses.getOrDefault(c.getService().getId(), "OPERATIONAL")))
                .sorted(Comparator.comparingInt(ImpactedService::tier).thenComparing(ImpactedService::name)).toList();
        List<Long> serviceIds = services.stream().map(ImpactedService::serviceId).toList();
        return new ImpactAnalysis(ref(root), dir, d, nodes(up, byId), nodes(down, byId), services, openIncidents(serviceIds, impactedCis),
                openAlerts(impactedCis), truncated[0]);
    }

    @Transactional(readOnly = true)
    public Graph graph(Long rootId, Integer depth, List<CiType> types, ConfigurationItem.Environment environment, boolean includeRetired) {
        int d = clampDepth(depth, 2);
        List<Edge> edges = edges();
        Map<Long, Integer> depthOf = new LinkedHashMap<>();
        boolean truncated = false;
        if (rootId != null) {
            cis.findById(rootId).orElseThrow(() -> ApiException.notFound("Configuration item", rootId));
            // Neighbourhood in both directions, ignoring direction for reachability.
            Map<Long, List<Long>> adjacent = new HashMap<>();
            for (Edge e : edges) {
                adjacent.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
                adjacent.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(e.source());
            }
            depthOf.put(rootId, 0);
            Deque<Long> queue = new ArrayDeque<>(List.of(rootId));
            while (!queue.isEmpty() && !truncated) {
                Long cur = queue.poll();
                int cd = depthOf.get(cur);
                if (cd >= d) {
                    continue;
                }
                for (Long other : adjacent.getOrDefault(cur, List.of())) {
                    if (depthOf.containsKey(other)) {
                        continue;
                    }
                    if (depthOf.size() >= MAX_GRAPH_NODES) {
                        truncated = true;
                        break;
                    }
                    depthOf.put(other, cd + 1);
                    queue.add(other);
                }
            }
        } else {
            List<Long> all = jdbc.queryForList("select id from configuration_items order by case ci_type when 'BUSINESS_SERVICE' then 0 else 1 end, "
                    + "lower(name) limit ?", Long.class, MAX_GRAPH_NODES + 1);
            truncated = all.size() > MAX_GRAPH_NODES;
            all.stream().limit(MAX_GRAPH_NODES).forEach(x -> depthOf.put(x, 0));
        }
        Map<Long, ConfigurationItem> byId = cis.findAllWithDetails(depthOf.keySet()).stream()
                .filter(c -> c.getId().equals(rootId) || ((types == null || types.isEmpty() || types.contains(c.getCiType()))
                        && (environment == null || environment == c.getEnvironment())
                        && (includeRetired || c.getStatus() != ConfigurationItem.Status.RETIRED)))
                .collect(Collectors.toMap(ConfigurationItem::getId, c -> c));
        Map<Long, Long> alerts = alertCounts(byId.keySet());
        List<GraphNode> nodes = depthOf.entrySet().stream().filter(e -> byId.containsKey(e.getKey())).map(e -> {
            ConfigurationItem c = byId.get(e.getKey());
            return new GraphNode(c.getId(), c.getName(), c.getCiType(), c.getStatus(), c.getEnvironment(), c.getCriticality(),
                    c.getService() == null ? null : c.getService().getId(), alerts.getOrDefault(c.getId(), 0L), e.getValue());
        }).toList();
        List<GraphEdge> graphEdges = edges.stream().filter(e -> byId.containsKey(e.source()) && byId.containsKey(e.target()))
                .map(e -> new GraphEdge(e.id(), e.source(), e.target(), e.type(), e.origin())).toList();
        return new Graph(rootId, d, nodes, graphEdges, truncated);
    }

    /** The CI's own service (BUSINESS_SERVICE CI), else the nearest business service that depends on it (within 5 hops). */
    @Transactional(readOnly = true)
    public Optional<Long> nearestServiceId(Long ciId) {
        if (ciId == null) {
            return Optional.empty();
        }
        List<Long> own = jdbc.queryForList("select service_id from configuration_items where id = ? and service_id is not null", Long.class, ciId);
        if (!own.isEmpty()) {
            return Optional.of(own.get(0));
        }
        boolean[] truncated = {false};
        List<Hop> down = traverse(ciId, false, DEFAULT_DEPTH, edges(), truncated);
        if (down.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, Long> serviceOf = new HashMap<>();
        jdbc.query("select id, service_id from configuration_items where service_id is not null and id in (" + ids(down.stream().map(Hop::ciId).toList())
                + ")", rs -> {
                    serviceOf.put(rs.getLong(1), rs.getLong(2));
                });
        return down.stream().filter(h -> serviceOf.containsKey(h.ciId())).findFirst().map(h -> serviceOf.get(h.ciId()));
    }

    // ------------------------------------------------------------------ helpers

    private List<ImpactNode> nodes(List<Hop> hops, Map<Long, ConfigurationItem> byId) {
        return hops.stream().filter(h -> byId.containsKey(h.ciId())).map(h -> {
            ConfigurationItem c = byId.get(h.ciId());
            return new ImpactNode(ref(c), c.getCriticality(), h.depth(), h.via(), h.from());
        }).toList();
    }

    private List<IncidentSummary> openIncidents(Collection<Long> serviceIds, Collection<Long> ciIds) {
        String services = serviceIds.isEmpty() ? "-1" : ids(serviceIds);
        List<Long> found = jdbc.queryForList("select i.id from incidents i where i.status in " + IncidentStatus.activeSqlList()
                + " and (i.service_id in (" + services + ") or i.id in (select a.incident_id from alerts a where a.incident_id is not null "
                + "and a.status in ('OPEN', 'ACKNOWLEDGED', 'SUPPRESSED') and a.ci_id in (" + ids(ciIds) + "))) order by i.created_at desc limit 50", Long.class);
        if (found.isEmpty()) {
            return List.of();
        }
        List<Incident> list = new ArrayList<>(incidents.findAllById(found));
        list.sort(Comparator.comparing(Incident::getPriority).thenComparing(Incident::getCreatedAt, Comparator.reverseOrder()));
        return incidentMapper.summaries(list);
    }

    private List<AlertBrief> openAlerts(Collection<Long> ciIds) {
        return jdbc.query("select a.id, a.number, a.title, a.severity, a.status, a.ci_id, c.name from alerts a join configuration_items c on c.id = a.ci_id "
                + "where a.status in ('OPEN', 'ACKNOWLEDGED') and a.ci_id in (" + ids(ciIds) + ") order by a.last_seen_at desc limit 50",
                (rs, n) -> new AlertBrief(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7)));
    }

    Map<Long, Long> alertCounts(Collection<Long> ciIds) {
        Map<Long, Long> out = new HashMap<>();
        if (ciIds.isEmpty()) {
            return out;
        }
        jdbc.query("select ci_id, count(*) from alerts where status in ('OPEN', 'ACKNOWLEDGED') and ci_id in (" + ids(ciIds) + ") group by ci_id",
                rs -> {
                    out.put(rs.getLong(1), rs.getLong(2));
                });
        return out;
    }

    /** Service status by active incident priority (P1 → OUTAGE, P2 → DEGRADED); the full model is in ServiceHealthService. */
    private Map<Long, String> serviceStatuses() {
        Map<Long, String> out = new HashMap<>();
        jdbc.query("select service_id, min(case priority when 'CRITICAL' then 1 when 'HIGH' then 2 else 3 end) from incidents where service_id is not null "
                + "and status in " + IncidentStatus.activeSqlList() + " group by service_id", rs -> {
                    int p = rs.getInt(2);
                    out.put(rs.getLong(1), p == 1 ? "OUTAGE" : p == 2 ? "DEGRADED" : "OPERATIONAL");
                });
        return out;
    }

    static CiRef ref(ConfigurationItem c) {
        return new CiRef(c.getId(), c.getName(), c.getCiType(), c.getStatus(), c.getEnvironment());
    }

    private static int clampDepth(Integer depth, int fallback) {
        if (depth == null) {
            return fallback;
        }
        if (depth < 1 || depth > MAX_DEPTH) {
            throw ApiException.badRequest("depth must be between 1 and " + MAX_DEPTH);
        }
        return depth;
    }

    /** Comma-separated numeric ids for IN lists (values are Longs, never user text). */
    static String ids(Collection<Long> ids) {
        return ids.isEmpty() ? "-1" : ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
