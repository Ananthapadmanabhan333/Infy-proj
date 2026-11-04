package com.nexus360.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.agent.AgentDtos.EvidenceRef;
import com.nexus360.agent.AgentDtos.ToolDto;
import com.nexus360.agent.AgentDtos.ToolStatus;
import com.nexus360.change.ChangeService;
import com.nexus360.cmdb.CmdbGraphService;
import com.nexus360.common.ApiException;
import com.nexus360.incident.IncidentAccess;
import com.nexus360.incident.IncidentQueryService;
import com.nexus360.incident.TextSimilarity;
import com.nexus360.knowledge.KnowledgeService;
import com.nexus360.observability.AnomalyService;
import com.nexus360.observability.TelemetryQueryService;
import com.nexus360.prediction.PredictionService;
import com.nexus360.security.Actor;
import com.nexus360.security.Roles;
import com.nexus360.twin.TwinService;
import com.nexus360.user.Role;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The read-only tools SA agents may call. Every tool runs <b>as the requesting user</b>: it first checks the role gate of the endpoint
 * that serves the same data (the {@code Roles} groups of {@code SecurityConfig}) and then calls the owning service with the requester's
 * {@link Actor}, so record-level rules apply too (e.g. incidents a user may not see are "not found"). A denied call returns no data.
 * No tool writes anything; prediction tools serve without recording. Arguments are validated (numeric ids, bounded text).
 */
@Component
public class AgentTools {

    static final int MAX_RESULT_BYTES = 16_384;

    /** Context of a tool call: who asks, whether simulated records may be read, and the data scope of the agent's policy. */
    public record ToolContext(Actor actor, boolean includeSimulated, int telemetryMinutes, int historyDays, Instant now) {}

    /** Data plus the records it came from (evidence references the agents may cite). */
    public record Out(Object data, List<EvidenceRef> evidence) {}

    @FunctionalInterface
    interface ToolFn {
        Out apply(ToolContext ctx, Map<String, String> args);
    }

    public record Tool(String name, String description, Map<String, String> args, Set<Role> roles, ToolFn fn) {}

    public record ToolResult(ToolStatus status, JsonNode data, String message, int size, boolean truncated, List<EvidenceRef> evidence, long durationMs) {}

    private static Set<Role> roles(String... names) {
        Set<Role> set = EnumSet.noneOf(Role.class);
        Arrays.stream(names).map(Role::valueOf).forEach(set::add);
        return set;
    }

    static final Set<Role> ANY_AGENT = roles(Roles.AGENT_READER_ROLES);
    static final Set<Role> READERS = roles(Roles.READER_ROLES);
    static final Set<Role> STAFF = roles(Roles.STAFF_ROLES);
    static final Set<Role> STAFF_AND_AUDITORS = roles("SYSTEM_ADMIN", "IT_ADMIN", "IT_MANAGER", "INCIDENT_MANAGER", "CHANGE_MANAGER", "ASSET_MANAGER",
            "SUPPORT_AGENT", "AUDITOR");
    static final Set<Role> OBSERVABILITY = roles(Roles.OBSERVABILITY_READER_ROLES);
    static final Set<Role> PROBLEM_EDITORS = roles(Roles.PROBLEM_EDITOR_ROLES);
    static final Set<Role> DEPLOYMENT_EDITORS = roles(Roles.DEPLOYMENT_EDITOR_ROLES);

    private final NamedParameterJdbcTemplate jdbc;
    private final IncidentQueryService incidents;
    private final KnowledgeService knowledge;
    private final PredictionService predictions;
    private final CmdbGraphService cmdb;
    private final TelemetryQueryService telemetry;
    private final AnomalyService anomalies;
    private final TwinService twin;
    private final ChangeService changes;
    private final ObjectMapper json;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public AgentTools(NamedParameterJdbcTemplate jdbc, IncidentQueryService incidents, KnowledgeService knowledge, PredictionService predictions,
                      CmdbGraphService cmdb, TelemetryQueryService telemetry, AnomalyService anomalies, TwinService twin, ChangeService changes,
                      ObjectMapper json) {
        this.jdbc = jdbc;
        this.incidents = incidents;
        this.knowledge = knowledge;
        this.predictions = predictions;
        this.cmdb = cmdb;
        this.telemetry = telemetry;
        this.anomalies = anomalies;
        this.twin = twin;
        this.changes = changes;
        this.json = json;
        register("get_incident", "The incident record (title, description, category, priority, status, service, team, timestamps, root cause)",
                Map.of("incidentId", "number, required"), ANY_AGENT, this::getIncident);
        register("similar_incidents", "Resolved incidents similar to an incident (or to free text) with their root causes and resolutions",
                Map.of("incidentId", "number", "query", "text (when no incidentId)"), STAFF_AND_AUDITORS, this::similarIncidents);
        register("kb_search", "Published knowledge articles relevant to text, ranked, with a relevance score",
                Map.of("query", "text, required", "serviceId", "number"), ANY_AGENT, this::kbSearch);
        register("sla_prediction", "Breach probability of the incident's running resolution SLA (ACTIVE model, else the labelled statistic)",
                Map.of("incidentId", "number, required"), OBSERVABILITY, this::slaPrediction);
        register("alerts", "Alerts linked to an incident or on a service in the history window", Map.of("incidentId", "number", "serviceId", "number"),
                READERS, this::alerts);
        register("recent_changes", "Changes on a service that started in the 72 hours before a time (default now)",
                Map.of("serviceId", "number, required", "before", "ISO instant"), STAFF, this::recentChanges);
        register("recent_deployments", "Deployments of a service in the 72 hours before a time (default now)",
                Map.of("serviceId", "number, required", "before", "ISO instant"), DEPLOYMENT_EDITORS, this::recentDeployments);
        register("correlation_groups", "Alert/anomaly correlation groups linked to an incident or rooted at a service",
                Map.of("incidentId", "number", "serviceId", "number"), OBSERVABILITY, this::correlationGroups);
        register("anomalies", "Telemetry anomalies on a service in the history window", Map.of("serviceId", "number, required"), OBSERVABILITY,
                this::anomalies);
        register("problems", "Problems and known errors on a service or linked to an incident", Map.of("serviceId", "number", "incidentId", "number"),
                PROBLEM_EDITORS, this::problems);
        register("cmdb_impact", "CMDB dependencies (upstream and downstream) of a service's or CI's configuration item",
                Map.of("serviceId", "number", "ciId", "number"), READERS, this::cmdbImpact);
        register("service_red", "Rate, errors and p95 duration of a service over the telemetry window", Map.of("serviceId", "number, required"),
                OBSERVABILITY, this::serviceRed);
        register("telemetry_metrics", "The service's metrics with min/max/mean/last over the telemetry window", Map.of("serviceId", "number, required"),
                OBSERVABILITY, this::telemetryMetrics);
        register("telemetry_logs", "WARN and higher log records of a service over the telemetry window", Map.of("serviceId", "number, required"),
                OBSERVABILITY, this::telemetryLogs);
        register("twin_changes", "Unified change timeline (CMDB history, changes, deployments, telemetry attribute changes) of a service or CI",
                Map.of("serviceId", "number", "ciId", "number"), OBSERVABILITY, this::twinChanges);
        register("get_change", "The change request (type, status, risk, plan, services, CIs, schedule)", Map.of("changeId", "number, required"), STAFF,
                this::getChange);
        register("incidents_after_changes", "Incidents on the change's services created within 24 hours after it started",
                Map.of("changeId", "number, required"), STAFF, this::incidentsAfterChange);
        register("runbooks", "Active runbooks that fit an incident (service, category, wording), scored 0-100", Map.of("incidentId", "number, required"),
                STAFF, this::runbooks);
    }

    private void register(String name, String description, Map<String, String> args, Set<Role> roles, ToolFn fn) {
        tools.put(name, new Tool(name, description, args, roles, fn));
    }

    public Set<String> names() {
        return tools.keySet();
    }

    public boolean exists(String name) {
        return tools.containsKey(name);
    }

    public List<ToolDto> catalogue() {
        return tools.values().stream().map(t -> new ToolDto(t.name(), t.description(), t.args(), t.roles().stream().map(Enum::name).sorted().toList()))
                .toList();
    }

    public Tool tool(String name) {
        return tools.get(name);
    }

    /** Runs a tool as {@code ctx.actor()}; never throws for business errors (they become the call's status, with no data). */
    public ToolResult invoke(String name, Map<String, String> args, ToolContext ctx) {
        long started = System.nanoTime();
        Tool t = tools.get(name);
        if (t == null) {
            return new ToolResult(ToolStatus.INVALID, null, "Unknown tool " + name, 0, false, List.of(), 0);
        }
        if (!t.roles().contains(ctx.actor().role())) {
            return new ToolResult(ToolStatus.DENIED, null, "Role " + ctx.actor().role() + " has no access to " + name, 0, false, List.of(), ms(started));
        }
        try {
            Out out = t.fn().apply(ctx, args == null ? Map.of() : args);
            JsonNode node = json.valueToTree(out.data());
            boolean empty = node == null || node.isNull() || (node.isContainerNode() && node.isEmpty());
            String text = json.writeValueAsString(node);
            int size = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            boolean truncated = size > MAX_RESULT_BYTES;
            if (truncated) {
                node = truncate(node);
            }
            return new ToolResult(empty ? ToolStatus.EMPTY : ToolStatus.OK, node, truncated ? "Result truncated to fit " + MAX_RESULT_BYTES + " bytes" : null,
                    size, truncated, out.evidence(), ms(started));
        } catch (ApiException e) {
            HttpStatus s = e.getStatus();
            ToolStatus status = s == HttpStatus.FORBIDDEN || s == HttpStatus.UNAUTHORIZED ? ToolStatus.DENIED : s == HttpStatus.NOT_FOUND ? ToolStatus.NOT_FOUND
                    : ToolStatus.INVALID;
            return new ToolResult(status, null, e.getMessage(), 0, false, List.of(), ms(started));
        } catch (IllegalArgumentException e) {
            return new ToolResult(ToolStatus.INVALID, null, e.getMessage(), 0, false, List.of(), ms(started));
        } catch (RuntimeException e) {
            return new ToolResult(ToolStatus.ERROR, null, "Tool failed: " + e.getClass().getSimpleName(), 0, false, List.of(), ms(started));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return new ToolResult(ToolStatus.ERROR, null, "Tool result could not be serialised", 0, false, List.of(), ms(started));
        }
    }

    private JsonNode truncate(JsonNode node) {
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode copy = json.createArrayNode();
            int bytes = 2;
            for (JsonNode item : node) {
                int n = item.toString().length() + 1;
                if (bytes + n > MAX_RESULT_BYTES) {
                    break;
                }
                copy.add(item);
                bytes += n;
            }
            return copy;
        }
        String text = node.toString();
        return json.getNodeFactory().textNode(text.substring(0, Math.min(text.length(), MAX_RESULT_BYTES - 64)) + "…");
    }

    // ------------------------------------------------------------------ incidents

    private Out getIncident(ToolContext ctx, Map<String, String> args) {
        long id = requireId(args, "incidentId");
        IncidentAccess.requireView(incidents.load(id, ctx.actor()), ctx.actor());
        Map<String, Object> row = jdbc.queryForList("select i.id, i.number, i.title, left(i.description, 2000) as description, i.category, i.priority, i.impact, "
                + "i.urgency, i.status, i.service_id, s.name as service_name, s.tier as service_tier, s.default_team_id, i.team_id, t.name as team_name, "
                + "d.name as department_name, u.full_name as assignee_name, i.is_major, i.affected_users, i.escalation_level, i.created_at, i.resolved_at, "
                + "left(i.root_cause, 1000) as root_cause, i.resolution_code, left(i.resolution_notes, 1000) as resolution_notes, i.simulated, "
                + "(select count(*) from incidents c where c.parent_id = i.id) as duplicate_reports, "
                + "(select ci.id from configuration_items ci where ci.service_id = i.service_id and ci.ci_type = 'BUSINESS_SERVICE' limit 1) as service_ci_id "
                + "from incidents i left join business_services s on s.id = i.service_id left join teams t on t.id = i.team_id "
                + "left join departments d on d.id = i.department_id left join users u on u.id = i.assignee_id where i.id = :id",
                new MapSqlParameterSource("id", id)).stream().findFirst().orElseThrow(() -> ApiException.notFound("Incident", id));
        return new Out(clean(row), List.of(new EvidenceRef("INCIDENT", id, (String) row.get("number"))));
    }

    private Out similarIncidents(ToolContext ctx, Map<String, String> args) {
        String title;
        String description;
        Long serviceId = null;
        String category = null;
        Long self = optId(args, "incidentId");
        if (self != null) {
            IncidentAccess.requireView(incidents.load(self, ctx.actor()), ctx.actor());
            Map<String, Object> i = jdbc.queryForMap("select title, description, service_id, category from incidents where id = :id", new MapSqlParameterSource("id", self));
            title = (String) i.get("title");
            description = (String) i.get("description");
            serviceId = (Long) i.get("service_id");
            category = (String) i.get("category");
        } else {
            title = text(args, "query", 500, true);
            description = null;
        }
        MapSqlParameterSource p = new MapSqlParameterSource("from", at(ctx.now().minus(Duration.ofDays(ctx.historyDays())))).addValue("self", self == null ? -1 : self)
                .addValue("svc", serviceId).addValue("cat", category);
        String scope = self == null ? "" : " and (i.service_id = :svc or i.category = :cat)";
        List<Map<String, Object>> rows = jdbc.queryForList("select i.id, i.number, i.title, i.description, i.category, i.service_id, s.name as service_name, "
                + "i.resolved_at, left(i.root_cause, 500) as root_cause, left(i.resolution_notes, 500) as resolution_notes, i.resolution_code, "
                + "i.resolution_article_id from incidents i left join business_services s on s.id = i.service_id where i.parent_id is null and i.id <> :self "
                + "and i.resolved_at is not null and coalesce(i.resolution_code, '') <> 'CANCELLED' and i.resolved_at >= :from"
                + (ctx.includeSimulated() ? "" : " and not i.simulated") + scope + " order by i.resolved_at desc limit 500", p);
        Long svc = serviceId;
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            double sim = TextSimilarity.score(title, description, (String) r.get("title"), (String) r.get("description"));
            if (svc != null && svc.equals(r.get("service_id"))) {
                sim += 0.15;
            }
            if (sim >= 0.15) {
                Map<String, Object> m = new LinkedHashMap<>(r);
                m.remove("description");
                m.put("similarity", Math.round(Math.min(1, sim) * 1000) / 1000.0);
                scored.add(clean(m));
            }
        }
        scored.sort(Comparator.comparingDouble((Map<String, Object> m) -> -((Number) m.get("similarity")).doubleValue()));
        List<Map<String, Object>> top = scored.stream().limit(5).toList();
        return new Out(top, top.stream().map(m -> new EvidenceRef("INCIDENT", ((Number) m.get("id")).longValue(), (String) m.get("number"))).toList());
    }

    private Out kbSearch(ToolContext ctx, Map<String, String> args) {
        String q = text(args, "query", 2000, true);
        Long serviceId = optId(args, "serviceId");
        var hits = knowledge.recommend(q, serviceId, 5);
        List<Map<String, Object>> out = hits.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("number", a.number());
            m.put("title", a.title());
            m.put("summary", a.summary());
            m.put("type", a.articleType());
            m.put("category", a.category());
            m.put("serviceName", a.serviceName());
            m.put("relevance", a.score() == null ? null : Math.round(a.score() * 1000) / 1000.0);
            return m;
        }).toList();
        return new Out(out, hits.stream().map(a -> new EvidenceRef("KB_ARTICLE", a.id(), a.number())).toList());
    }

    private Out slaPrediction(ToolContext ctx, Map<String, String> args) {
        long id = requireId(args, "incidentId");
        var p = predictions.slaBreach(id, ctx.actor(), false);
        List<EvidenceRef> ev = p.modelId() == null ? List.of() : List.of(new EvidenceRef("PREDICTION_MODEL", p.modelId(), p.type() + " v" + p.modelVersion()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", p.status());
        m.put("basis", p.basis());
        m.put("probability", p.probability());
        m.put("riskLevel", p.riskLevel());
        m.put("modelVersion", p.modelVersion());
        m.put("message", p.message());
        m.put("method", p.method());
        return new Out(m, ev);
    }

    private Out alerts(ToolContext ctx, Map<String, String> args) {
        Long incidentId = optId(args, "incidentId");
        Long serviceId = optId(args, "serviceId");
        if (incidentId == null && serviceId == null) {
            throw new IllegalArgumentException("incidentId or serviceId is required");
        }
        MapSqlParameterSource p = new MapSqlParameterSource("inc", incidentId).addValue("svc", serviceId)
                .addValue("from", at(ctx.now().minus(Duration.ofDays(Math.min(ctx.historyDays(), 30)))));
        String where = incidentId != null ? "a.incident_id = :inc" : "a.service_id = :svc and a.first_seen_at >= :from";
        List<Map<String, Object>> rows = jdbc.queryForList("select a.id, a.number, a.title, a.severity, a.status, a.source_kind, a.first_seen_at, a.last_seen_at, "
                + "a.occurrence_count, a.service_id, a.ci_id, c.name as ci_name, a.incident_id, a.simulated from alerts a left join configuration_items c on c.id = a.ci_id "
                + "where " + where + (ctx.includeSimulated() ? "" : " and not a.simulated") + " order by a.first_seen_at desc limit 25", p);
        return new Out(rows.stream().map(AgentTools::clean).toList(), refs(rows, "ALERT"));
    }

    private Out recentChanges(ToolContext ctx, Map<String, String> args) {
        long serviceId = requireId(args, "serviceId");
        Instant before = instant(args, "before", ctx.now());
        List<Map<String, Object>> rows = jdbc.queryForList("select c.id, c.number, c.title, c.change_type, c.status, c.risk, c.planned_start, c.actual_start, "
                + "c.actual_end, (select string_agg(ci.name, ', ') from change_cis cc join configuration_items ci on ci.id = cc.ci_id where cc.change_id = c.id) as cis, "
                + "(select min(cc.ci_id) from change_cis cc where cc.change_id = c.id) as ci_id from changes c join change_services cs on cs.change_id = c.id "
                + "where cs.service_id = :svc and coalesce(c.actual_start, c.planned_start) >= :from and coalesce(c.actual_start, c.planned_start) <= :to "
                + "order by coalesce(c.actual_start, c.planned_start) desc limit 20", new MapSqlParameterSource("svc", serviceId)
                .addValue("from", at(before.minus(Duration.ofHours(72)))).addValue("to", at(before)));
        return new Out(rows.stream().map(AgentTools::clean).toList(), refs(rows, "CHANGE"));
    }

    private Out recentDeployments(ToolContext ctx, Map<String, String> args) {
        long serviceId = requireId(args, "serviceId");
        Instant before = instant(args, "before", ctx.now());
        int hours = Math.max(1, Math.min(intArg(args, "hours", 72), 24 * 30));
        List<Map<String, Object>> rows = jdbc.queryForList("select d.id, d.number, d.version, d.environment, d.status, d.started_at, d.finished_at, d.change_id, "
                + "d.simulated from deployments d where d.service_id = :svc and d.started_at >= :from and d.started_at <= :to"
                + (ctx.includeSimulated() ? "" : " and not d.simulated") + " order by d.started_at desc limit 25", new MapSqlParameterSource("svc", serviceId)
                .addValue("from", at(before.minus(Duration.ofHours(hours)))).addValue("to", at(before)));
        return new Out(rows.stream().map(AgentTools::clean).toList(), refs(rows, "DEPLOYMENT"));
    }

    private Out correlationGroups(ToolContext ctx, Map<String, String> args) {
        Long incidentId = optId(args, "incidentId");
        Long serviceId = optId(args, "serviceId");
        if (incidentId == null && serviceId == null) {
            throw new IllegalArgumentException("incidentId or serviceId is required");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("select g.id, g.title, g.status, g.score, g.member_count, g.root_ci_id, ci.name as root_ci_name, "
                + "g.root_service_id, g.root_reason, g.incident_id, g.first_seen_at, g.last_activity_at, g.simulated from correlation_groups g "
                + "left join configuration_items ci on ci.id = g.root_ci_id where (g.incident_id = :inc or g.root_service_id = :svc "
                + "or g.id in (select m.group_id from correlation_group_members m join alerts a on a.id = m.alert_id where a.incident_id = :inc)) "
                + "and g.status <> 'DISMISSED' and g.last_activity_at >= :from" + (ctx.includeSimulated() ? "" : " and not g.simulated")
                + " order by g.last_activity_at desc limit 10", new MapSqlParameterSource("inc", incidentId == null ? -1 : incidentId)
                .addValue("svc", serviceId == null ? -1 : serviceId).addValue("from", at(ctx.now().minus(Duration.ofDays(Math.min(ctx.historyDays(), 30))))));
        return new Out(rows.stream().map(AgentTools::clean).toList(), refs(rows, "CORRELATION_GROUP"));
    }

    private Out anomalies(ToolContext ctx, Map<String, String> args) {
        long serviceId = requireId(args, "serviceId");
        Instant to = ctx.now();
        Instant from = to.minus(Duration.ofDays(Math.min(ctx.historyDays(), 7)));
        var page = anomalies.anomalies(null, null, serviceId, from, to, ctx.includeSimulated(), 0, 20);
        List<Map<String, Object>> out = page.content().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("metricName", a.metricName());
            m.put("status", a.status());
            m.put("direction", a.direction());
            m.put("startedAt", a.startedAt());
            m.put("endedAt", a.endedAt());
            m.put("peakScore", a.peakScore());
            m.put("observedValue", a.observedValue());
            m.put("expectedValue", a.expectedValue());
            m.put("ciId", a.ciId());
            m.put("ciName", a.ciName());
            m.put("simulated", a.simulated());
            return m;
        }).toList();
        return new Out(out, page.content().stream().map(a -> new EvidenceRef("ANOMALY", a.id(), a.metricName())).toList());
    }

    private Out problems(ToolContext ctx, Map<String, String> args) {
        Long serviceId = optId(args, "serviceId");
        Long incidentId = optId(args, "incidentId");
        if (incidentId == null && serviceId == null) {
            throw new IllegalArgumentException("incidentId or serviceId is required");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("select distinct p.id, p.number, p.title, p.status, p.category, p.known_error, "
                + "left(p.root_cause, 500) as root_cause, left(p.workaround, 500) as workaround, p.updated_at, "
                + "exists (select 1 from problem_incidents x where x.problem_id = p.id and x.incident_id = :inc) as linked_to_incident from problems p "
                + "left join problem_services ps on ps.problem_id = p.id left join problem_incidents pi on pi.problem_id = p.id "
                + "where (ps.service_id = :svc or pi.incident_id = :inc) and p.status not in ('CLOSED', 'CANCELLED') order by p.updated_at desc limit 10",
                new MapSqlParameterSource("svc", serviceId == null ? -1 : serviceId).addValue("inc", incidentId == null ? -1 : incidentId));
        return new Out(rows.stream().map(AgentTools::clean).toList(), refs(rows, "PROBLEM"));
    }

    private Out cmdbImpact(ToolContext ctx, Map<String, String> args) {
        Long ciId = optId(args, "ciId");
        if (ciId == null) {
            long serviceId = requireId(args, "serviceId");
            ciId = jdbc.queryForList("select id from configuration_items where service_id = :svc order by case when ci_type = 'BUSINESS_SERVICE' then 0 else 1 end, "
                    + "id limit 1", new MapSqlParameterSource("svc", serviceId), Long.class).stream().findFirst()
                    .orElseThrow(() -> ApiException.notFound("Configuration item of service", serviceId));
        }
        var impact = cmdb.impact(ciId, "both", 3);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ci", impact.ci());
        m.put("dependencies", impact.upstream().stream().limit(30).map(n -> Map.of("id", n.ci().id(), "name", n.ci().name(), "type", n.ci().ciType(),
                "status", n.ci().status(), "depth", n.depth())).toList());
        m.put("dependents", impact.downstream().stream().limit(30).map(n -> Map.of("id", n.ci().id(), "name", n.ci().name(), "type", n.ci().ciType(),
                "status", n.ci().status(), "depth", n.depth())).toList());
        m.put("impactedServices", impact.impactedServices());
        m.put("openAlerts", impact.openAlerts());
        List<EvidenceRef> ev = new ArrayList<>();
        ev.add(new EvidenceRef("CI", impact.ci().id(), impact.ci().name()));
        impact.upstream().stream().limit(30).forEach(n -> ev.add(new EvidenceRef("CI", n.ci().id(), n.ci().name())));
        impact.downstream().stream().limit(30).forEach(n -> ev.add(new EvidenceRef("CI", n.ci().id(), n.ci().name())));
        return new Out(m, ev);
    }

    private Out serviceRed(ToolContext ctx, Map<String, String> args) {
        long serviceId = requireId(args, "serviceId");
        var red = telemetry.red(serviceId, ctx.now().minus(Duration.ofMinutes(ctx.telemetryMinutes())), ctx.now(), ctx.includeSimulated());
        return new Out(red, List.of(new EvidenceRef("SERVICE", serviceId, red.serviceName())));
    }

    private Out telemetryMetrics(ToolContext ctx, Map<String, String> args) {
        long serviceId = requireId(args, "serviceId");
        Instant to = ctx.now();
        Instant from = to.minus(Duration.ofMinutes(ctx.telemetryMinutes()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var name : telemetry.metricNames(null, serviceId, ctx.includeSimulated()).stream().limit(8).toList()) {
            var series = telemetry.series(new TelemetryQueryService.SeriesQuery(name.name(), serviceId, null, null, Map.of(), from, to, null, "avg",
                    ctx.includeSimulated()));
            for (var s : series.series().stream().limit(3).toList()) {
                double[] v = s.points().stream().mapToDouble(pt -> pt.v()).toArray();
                if (v.length == 0) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("seriesId", s.seriesId());
                m.put("metric", s.metric());
                m.put("unit", s.unit());
                m.put("attributes", s.attributes());
                m.put("points", v.length);
                m.put("min", round(Arrays.stream(v).min().orElse(0)));
                m.put("max", round(Arrays.stream(v).max().orElse(0)));
                m.put("mean", round(Arrays.stream(v).average().orElse(0)));
                m.put("first", round(v[0]));
                m.put("last", round(v[v.length - 1]));
                m.put("simulated", s.simulated());
                out.add(m);
            }
        }
        return new Out(out, out.stream().map(m -> new EvidenceRef("METRIC_SERIES", ((Number) m.get("seriesId")).longValue(), (String) m.get("metric")))
                .toList());
    }

    private Out telemetryLogs(ToolContext ctx, Map<String, String> args) {
        long serviceId = requireId(args, "serviceId");
        var page = telemetry.logs(new TelemetryQueryService.LogQuery(null, "WARN", serviceId, null, null,
                ctx.now().minus(Duration.ofMinutes(ctx.telemetryMinutes())), ctx.now(), ctx.includeSimulated()), 0, 25);
        Map<String, Long> bySeverity = page.content().stream().collect(Collectors.groupingBy(l -> l.severity(), LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> messages = page.content().stream().collect(Collectors.groupingBy(l -> l.body().length() > 160 ? l.body().substring(0, 160) : l.body(),
                LinkedHashMap::new, Collectors.counting()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", page.totalElements());
        m.put("bySeverity", bySeverity);
        m.put("topMessages", messages.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5)
                .map(e -> Map.of("message", e.getKey(), "count", e.getValue())).toList());
        return new Out(page.totalElements() == 0 ? Map.of() : m, page.content().stream().limit(5).map(l -> new EvidenceRef("LOG", l.id(), l.severity())).toList());
    }

    private Out twinChanges(ToolContext ctx, Map<String, String> args) {
        Long ciId = optId(args, "ciId");
        Long serviceId = optId(args, "serviceId");
        if (ciId == null && serviceId == null) {
            throw new IllegalArgumentException("serviceId or ciId is required");
        }
        var timeline = twin.changes(ciId, serviceId, ctx.now().minus(Duration.ofHours(72)), ctx.now(), ctx.includeSimulated(), 30);
        return new Out(timeline.entries(), List.of());
    }

    private Out getChange(ToolContext ctx, Map<String, String> args) {
        long id = requireId(args, "changeId");
        var detail = changes.get(id, ctx.actor());
        JsonNode node = json.valueToTree(detail);
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("id", "number", "title", "type", "changeType", "status", "risk", "riskScore", "riskFactors", "plannedStart", "plannedEnd",
                "actualStart", "services", "cis", "implementationPlan", "backoutPlan", "testPlan", "conflicts", "simulated")) {
            if (node.has(k)) {
                m.put(k, node.get(k));
            }
        }
        if (node.has("change")) {
            m.put("change", node.get("change"));
        }
        return new Out(m, List.of(new EvidenceRef("CHANGE", id, node.path("number").asText(node.path("change").path("number").asText(null)))));
    }

    private Out incidentsAfterChange(ToolContext ctx, Map<String, String> args) {
        long id = requireId(args, "changeId");
        changes.get(id, ctx.actor());
        var c = changes.correlation(id, null);
        List<EvidenceRef> ev = c.correlated().stream().map(x -> new EvidenceRef("INCIDENT", x.incident().id(), x.incident().number())).toList();
        return new Out(c, ev);
    }

    private Out runbooks(ToolContext ctx, Map<String, String> args) {
        long id = requireId(args, "incidentId");
        IncidentAccess.requireView(incidents.load(id, ctx.actor()), ctx.actor());
        Map<String, Object> i = jdbc.queryForMap("select title, description, service_id, category from incidents where id = :id", new MapSqlParameterSource("id", id));
        List<Map<String, Object>> rows = jdbc.queryForList("select id, title, description, service_id, "
                + "(select string_agg(rc.category, ', ') from runbook_categories rc where rc.runbook_id = runbooks.id) as category, "
                + "exists(select 1 from runbook_categories rc where rc.runbook_id = runbooks.id and rc.category = :cat) as same_cat, "
                + "requires_approval, estimated_minutes, jsonb_array_length(steps) as step_count from runbooks where active order by id limit 500",
                new MapSqlParameterSource("cat", i.get("category")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            int score = 0;
            List<String> reasons = new ArrayList<>();
            if (i.get("service_id") != null && i.get("service_id").equals(r.get("service_id"))) {
                score += 40;
                reasons.add("same service");
            }
            if (Boolean.TRUE.equals(r.get("same_cat"))) {
                score += 20;
                reasons.add("same category");
            }
            int text = (int) Math.round(30 * TextSimilarity.score((String) i.get("title"), (String) i.get("description"), (String) r.get("title"),
                    (String) r.get("description")));
            if (text >= 5) {
                score += text;
                reasons.add("similar wording");
            }
            if (score >= 15) {
                Map<String, Object> m = new LinkedHashMap<>(clean(r));
                m.put("score", Math.min(100, score));
                m.put("reasons", reasons);
                out.add(m);
            }
        }
        out.sort(Comparator.comparingInt((Map<String, Object> m) -> -((Number) m.get("score")).intValue()));
        List<Map<String, Object>> top = out.stream().limit(3).toList();
        return new Out(top, refs(top, "RUNBOOK"));
    }

    // ------------------------------------------------------------------ helpers

    private static List<EvidenceRef> refs(List<Map<String, Object>> rows, String type) {
        return rows.stream().filter(r -> r.get("id") != null).map(r -> new EvidenceRef(type, ((Number) r.get("id")).longValue(),
                r.get("number") == null ? (r.get("title") == null ? null : String.valueOf(r.get("title"))) : String.valueOf(r.get("number")))).toList();
    }

    /** JDBC timestamps become ISO instants. */
    static Map<String, Object> clean(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((k, v) -> m.put(camel(k), v instanceof Timestamp t ? t.toInstant().toString() : v));
        return m;
    }

    private static String camel(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : s.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return b.toString();
    }

    static long requireId(Map<String, String> args, String name) {
        Long v = optId(args, name);
        if (v == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return v;
    }

    static Long optId(Map<String, String> args, String name) {
        String v = args.get(name);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(v.trim());
            if (id <= 0) {
                throw new IllegalArgumentException(name + " must be a positive id");
            }
            return id;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a numeric id");
        }
    }

    private static int intArg(Map<String, String> args, String name, int fallback) {
        String v = args.get(name);
        try {
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static String text(Map<String, String> args, String name, int max, boolean required) {
        String v = args.get(name);
        if (v == null || v.isBlank()) {
            if (required) {
                throw new IllegalArgumentException(name + " is required");
            }
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static Instant instant(Map<String, String> args, String name, Instant fallback) {
        String v = args.get(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(v.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(name + " must be an ISO-8601 instant");
        }
    }

    private static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static long ms(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
