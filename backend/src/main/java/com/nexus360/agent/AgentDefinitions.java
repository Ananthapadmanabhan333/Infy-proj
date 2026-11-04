package com.nexus360.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.agent.AgentDtos.AgentCode;
import com.nexus360.agent.AgentDtos.ChangeRiskOutput;
import com.nexus360.agent.AgentDtos.Citation;
import com.nexus360.agent.AgentDtos.EvidenceRef;
import com.nexus360.agent.AgentDtos.Finding;
import com.nexus360.agent.AgentDtos.Hypothesis;
import com.nexus360.agent.AgentDtos.InfraOutput;
import com.nexus360.agent.AgentDtos.KnowledgeOutput;
import com.nexus360.agent.AgentDtos.ProposedAction;
import com.nexus360.agent.AgentDtos.ResolutionOutput;
import com.nexus360.agent.AgentDtos.RootCauseOutput;
import com.nexus360.agent.AgentDtos.RunType;
import com.nexus360.agent.AgentDtos.RunbookRef;
import com.nexus360.agent.AgentDtos.TriageOutput;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.incident.IncidentCategoryService;
import com.nexus360.incident.IncidentClassifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The six server-defined SA agents: their version, prompt, declared (read-only) tools, JSON output schema, the deterministic data
 * gathering plan and the deterministic fallback that turns the gathered tool data into a useful structured answer when no LLM is
 * configured or the LLM fails. Agents are code, not user input; the version is recorded on every run.
 */
@Component
public class AgentDefinitions {

    /** Input of one agent step: the run's subject plus the outputs of earlier stages. */
    public record StepInput(RunType runType, Long incidentId, Long changeId, String question, Map<String, JsonNode> upstream, Instant now) {}

    /** Records tool calls of the deterministic plan; returns the data, or null when the call returned nothing usable. */
    public interface Toolbox {
        JsonNode call(String tool, Map<String, String> args);
    }

    public interface Agent {
        AgentCode code();

        String name();

        String description();

        String version();

        String prompt();

        List<String> tools();

        Map<String, Object> schema();

        Class<?> outputType();

        Map<String, JsonNode> gather(StepInput in, Toolbox tb);

        Object fallback(StepInput in, Map<String, JsonNode> facts);
    }

    static final List<String> EVIDENCE_TYPES = List.of("INCIDENT", "ALERT", "CHANGE", "DEPLOYMENT", "PROBLEM", "KB_ARTICLE", "CI", "SERVICE", "ANOMALY",
            "CORRELATION_GROUP", "RUNBOOK", "PREDICTION_MODEL", "METRIC_SERIES", "LOG");
    static final List<String> CATEGORIES = List.of("APPLICATION", "NETWORK", "HARDWARE", "ACCESS", "DATABASE", "EMAIL", "SECURITY", "FACILITIES", "OTHER");
    static final List<String> PRIORITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    private final Map<AgentCode, Agent> agents = new EnumMap<>(AgentCode.class);
    private final IncidentClassifier classifier;
    private final BusinessServiceRepository services;
    private final IncidentCategoryService categories;
    private final ObjectMapper json;

    public AgentDefinitions(IncidentClassifier classifier, BusinessServiceRepository services, IncidentCategoryService categories, ObjectMapper json) {
        this.classifier = classifier;
        this.services = services;
        this.categories = categories;
        this.json = json;
        for (Agent a : List.of(new IncidentAgent(), new RootCauseAgent(), new ChangeRiskAgent(), new KnowledgeAgent(), new InfraAgent(),
                new ResolutionAgent())) {
            agents.put(a.code(), a);
        }
    }

    public Agent get(AgentCode code) {
        return agents.get(code);
    }

    public List<Agent> all() {
        return List.copyOf(agents.values());
    }

    /** The stages of a run type: agents in one stage run in parallel; later stages see earlier outputs. */
    public static List<List<AgentCode>> stages(RunType type) {
        return switch (type) {
            case INVESTIGATE_INCIDENT -> List.of(List.of(AgentCode.INCIDENT), List.of(AgentCode.ROOT_CAUSE, AgentCode.INFRA_INVESTIGATION, AgentCode.KNOWLEDGE),
                    List.of(AgentCode.RESOLUTION));
            case ASSESS_CHANGE -> List.of(List.of(AgentCode.CHANGE_RISK, AgentCode.INFRA_INVESTIGATION));
            case ANSWER_QUESTION -> List.of(List.of(AgentCode.KNOWLEDGE));
        };
    }

    /** The agent whose policy decides whether a run of the type may start at all (others are skipped when disabled). */
    public static AgentCode primary(RunType type) {
        return switch (type) {
            case INVESTIGATE_INCIDENT -> AgentCode.INCIDENT;
            case ASSESS_CHANGE -> AgentCode.CHANGE_RISK;
            case ANSWER_QUESTION -> AgentCode.KNOWLEDGE;
        };
    }

    public static List<RunType> runTypes(AgentCode code) {
        return java.util.Arrays.stream(RunType.values()).filter(t -> stages(t).stream().anyMatch(s -> s.contains(code))).toList();
    }

    // ------------------------------------------------------------------ schema helpers

    static Map<String, Object> obj(List<String> required, Map<String, Object> props) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("required", required);
        m.put("properties", props);
        m.put("additionalProperties", false);
        return m;
    }

    static Map<String, Object> str(int min, int max) {
        return Map.of("type", "string", "minLength", min, "maxLength", max);
    }

    static Map<String, Object> optStr(int max) {
        return Map.of("type", List.of("string", "null"), "maxLength", max);
    }

    static Map<String, Object> enumOf(List<String> values, boolean nullable) {
        return Map.of("type", nullable ? List.of("string", "null") : List.of("string"), "enum", values);
    }

    static Map<String, Object> num(double min, double max, boolean nullable) {
        return Map.of("type", nullable ? List.of("number", "null") : List.of("number"), "minimum", min, "maximum", max);
    }

    static Map<String, Object> arr(Object items, int max) {
        return Map.of("type", "array", "items", items, "maxItems", max);
    }

    static final Map<String, Object> EVIDENCE = arr(obj(List.of("type", "id"), map("type", enumOf(EVIDENCE_TYPES, false),
            "id", Map.of("type", "integer", "minimum", 1), "label", optStr(200))), 40);
    static final Map<String, Object> ACTIONS = arr(obj(List.of("kind", "params", "rationale"), map("kind",
            enumOf(java.util.Arrays.stream(AgentDtos.ActionKind.values()).map(Enum::name).toList(), false),
            "params", Map.of("type", "object", "additionalProperties", Map.of("type", "string", "maxLength", 50_000)),
            "rationale", str(3, 1000))), 6);
    static final Map<String, Object> CONFIDENCE = num(0, 1, false);
    static final Map<String, Object> TEXT_LIST = arr(str(1, 500), 15);

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ fact helpers

    static boolean present(JsonNode n) {
        return n != null && !n.isNull() && !(n.isContainerNode() && n.isEmpty());
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    static Long id(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() || !v.canConvertToLong() ? null : v.asLong();
    }

    static double dbl(JsonNode n, String field, double fallback) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isNumber() ? fallback : v.asDouble();
    }

    static Instant instant(JsonNode n, String field) {
        String t = text(n, field);
        try {
            return t == null ? null : Instant.parse(t);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Iterable<JsonNode> items(JsonNode n) {
        return n == null || !n.isArray() ? List.of() : n;
    }

    static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static Map<String, String> args(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], String.valueOf(kv[i + 1]));
            }
        }
        return m;
    }

    static List<EvidenceRef> dedupe(List<EvidenceRef> refs) {
        Set<String> seen = new LinkedHashSet<>();
        List<EvidenceRef> out = new ArrayList<>();
        for (EvidenceRef r : refs) {
            if (r.id() != null && seen.add(r.type() + ":" + r.id())) {
                out.add(r);
            }
        }
        return out.size() > 40 ? out.subList(0, 40) : out;
    }

    static String trunc(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static final String COMMON_RULES = "Use only the tool data you were given or fetch with the listed tools; cite records as evidence {type, id} that "
            + "appeared in tool results. Never invent records, numbers or people. Proposed actions are suggestions for a human; you never apply "
            + "anything. Answer with one JSON object that matches the output schema exactly.";

    // ------------------------------------------------------------------ INCIDENT

    final class IncidentAgent implements Agent {
        public AgentCode code() {
            return AgentCode.INCIDENT;
        }

        public String name() {
            return "Incident triage agent";
        }

        public String description() {
            return "Triage summary, classification check and priority advice for an incident";
        }

        public String version() {
            return "1.0.0";
        }

        public String prompt() {
            return "You are the SA incident triage agent. Summarise the incident for a responder, check its category and advise on its priority "
                    + "from the SLA breach probability, linked alerts and similar incidents. " + COMMON_RULES;
        }

        public List<String> tools() {
            return List.of("get_incident", "similar_incidents", "sla_prediction", "alerts");
        }

        public Map<String, Object> schema() {
            return obj(List.of("summary", "keyFacts", "evidence", "confidence", "proposedActions"), map("summary", str(10, 2000),
                    "category", enumOf(CATEGORIES, true), "recommendedCategory", enumOf(CATEGORIES, true), "priority", enumOf(PRIORITIES, true),
                    "recommendedPriority", enumOf(PRIORITIES, true), "priorityReason", optStr(1000), "keyFacts", TEXT_LIST,
                    "slaBreachProbability", num(0, 1, true), "evidence", EVIDENCE, "confidence", CONFIDENCE, "proposedActions", ACTIONS));
        }

        public Class<?> outputType() {
            return TriageOutput.class;
        }

        public Map<String, JsonNode> gather(StepInput in, Toolbox tb) {
            Map<String, JsonNode> f = new LinkedHashMap<>();
            f.put("incident", tb.call("get_incident", args("incidentId", in.incidentId())));
            f.put("similar", tb.call("similar_incidents", args("incidentId", in.incidentId())));
            f.put("sla", tb.call("sla_prediction", args("incidentId", in.incidentId())));
            f.put("alerts", tb.call("alerts", args("incidentId", in.incidentId())));
            return f;
        }

        public Object fallback(StepInput in, Map<String, JsonNode> f) {
            JsonNode inc = f.get("incident");
            if (!present(inc)) {
                return new TriageOutput("The incident could not be read with your permissions, so no triage is possible.", null, null, null, null, null,
                        List.of("get_incident returned no data"), null, List.of(), 0.0, List.of());
            }
            List<EvidenceRef> ev = new ArrayList<>();
            long incidentId = id(inc, "id");
            ev.add(new EvidenceRef("INCIDENT", incidentId, text(inc, "number")));
            String category = text(inc, "category");
            String priority = text(inc, "priority");
            IncidentClassifier.Result r = classifier.classify(text(inc, "title"), text(inc, "description"),
                    services.findAllByOrderByTierAscNameAsc(), categories.rules());
            String recommendedCategory = r.category() == null ? category : r.category().name();
            double classConfidence = r.confidence();
            int similarCount = 0;
            Map<String, Integer> similarCategories = new LinkedHashMap<>();
            for (JsonNode s : items(f.get("similar"))) {
                similarCount++;
                ev.add(new EvidenceRef("INCIDENT", id(s, "id"), text(s, "number")));
                if (dbl(s, "similarity", 0) >= 0.3 && text(s, "category") != null) {
                    similarCategories.merge(text(s, "category"), 1, Integer::sum);
                }
            }
            if (classConfidence < 0.5) {
                String majority = similarCategories.entrySet().stream().filter(e -> e.getValue() >= 2).max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                        .orElse(null);
                if (majority != null) {
                    recommendedCategory = majority;
                    classConfidence = 0.5;
                }
            }
            JsonNode sla = f.get("sla");
            Double breach = sla != null && sla.get("probability") != null && sla.get("probability").isNumber() ? sla.get("probability").asDouble() : null;
            int criticalAlerts = 0;
            int alertCount = 0;
            for (JsonNode a : items(f.get("alerts"))) {
                alertCount++;
                ev.add(new EvidenceRef("ALERT", id(a, "id"), text(a, "number")));
                if ("CRITICAL".equals(text(a, "severity"))) {
                    criticalAlerts++;
                }
            }
            String recommendedPriority = priority;
            String reason = "The current priority fits the available evidence";
            int idx = PRIORITIES.indexOf(priority);
            boolean active = !List.of("RESOLVED", "CLOSED").contains(text(inc, "status"));
            if (active && breach != null && breach >= 0.7 && idx > 0) {
                recommendedPriority = PRIORITIES.get(idx - 1);
                reason = "The resolution SLA breach probability is " + round2(breach) + " (" + text(sla, "basis") + ")";
            } else if (active && criticalAlerts > 0 && idx > 1) {
                recommendedPriority = "HIGH";
                reason = criticalAlerts + " linked CRITICAL alert(s)";
            }
            List<ProposedAction> actions = new ArrayList<>();
            if (active && recommendedPriority != null && !recommendedPriority.equals(priority)) {
                actions.add(new ProposedAction("CHANGE_PRIORITY", Map.of("priority", recommendedPriority), reason));
            }
            Long defaultTeam = id(inc, "defaultTeamId");
            if (active && id(inc, "teamId") == null && defaultTeam != null) {
                actions.add(new ProposedAction("ASSIGN_TEAM", Map.of("teamId", String.valueOf(defaultTeam)),
                        "The incident has no team; route it to the service's default team"));
            }
            long duplicates = id(inc, "duplicateReports") == null ? 0 : id(inc, "duplicateReports");
            boolean major = inc.path("isMajor").asBoolean(false);
            if (active && !major && "CRITICAL".equals(priority) && (duplicates >= 3 || inc.path("affectedUsers").asInt(0) >= 100)) {
                actions.add(new ProposedAction("DECLARE_MAJOR", Map.of(), duplicates + " duplicate report(s), " + inc.path("affectedUsers").asInt(0)
                        + " affected users on a CRITICAL incident"));
            }
            List<String> facts = new ArrayList<>();
            facts.add("Status " + text(inc, "status") + ", priority " + priority + ", category " + category);
            facts.add("Service: " + (text(inc, "serviceName") == null ? "none" : text(inc, "serviceName")) + "; team: "
                    + (text(inc, "teamName") == null ? "unassigned" : text(inc, "teamName")));
            facts.add(alertCount + " linked alert(s), " + criticalAlerts + " critical");
            facts.add(similarCount + " similar resolved incident(s) in the history window");
            if (breach != null) {
                facts.add("SLA breach probability " + round2(breach) + " (" + text(sla, "basis") + ")");
            } else if (sla != null && text(sla, "message") != null) {
                facts.add("SLA: " + text(sla, "message"));
            }
            if (duplicates > 0) {
                facts.add(duplicates + " duplicate report(s) grouped under this incident");
            }
            Instant created = instant(inc, "createdAt");
            String age = created == null ? "" : " open for " + Math.max(0, Duration.between(created, in.now()).toHours()) + " h,";
            String summary = text(inc, "number") + " (" + priority + ", " + text(inc, "status") + ")" + age + " on "
                    + (text(inc, "serviceName") == null ? "no service" : text(inc, "serviceName")) + ": " + text(inc, "title") + ". "
                    + (recommendedCategory != null && !recommendedCategory.equals(category) ? "The text suggests category " + recommendedCategory + ". " : "")
                    + (recommendedPriority != null && !recommendedPriority.equals(priority) ? "Consider priority " + recommendedPriority + ": " + reason + "." : reason + ".");
            double confidence = Math.min(0.9, 0.35 + 0.3 * classConfidence + 0.05 * Math.min(3, similarCount) + (breach != null ? 0.1 : 0));
            return new TriageOutput(trunc(summary, 2000), category, recommendedCategory, priority, recommendedPriority, reason, facts,
                    breach == null ? null : round2(breach), dedupe(ev), round2(confidence), actions);
        }
    }

    // ------------------------------------------------------------------ ROOT_CAUSE

    final class RootCauseAgent implements Agent {
        public AgentCode code() {
            return AgentCode.ROOT_CAUSE;
        }

        public String name() {
            return "Root cause agent";
        }

        public String description() {
            return "Ranked root-cause hypotheses with evidence: recent changes and deployments, correlation groups, anomalies, known errors and "
                    + "similar resolved incidents";
        }

        public String version() {
            return "1.0.0";
        }

        public String prompt() {
            return "You are the SA root cause agent. Rank plausible root causes of the incident with a likelihood between 0 and 1, the affected "
                    + "component and checks that would confirm each hypothesis. " + COMMON_RULES;
        }

        public List<String> tools() {
            return List.of("get_incident", "similar_incidents", "recent_changes", "recent_deployments", "correlation_groups", "anomalies", "problems");
        }

        public Map<String, Object> schema() {
            return obj(List.of("summary", "hypotheses", "evidence", "confidence", "proposedActions"), map("summary", str(10, 2000),
                    "hypotheses", arr(obj(List.of("cause", "likelihood", "supporting", "evidence", "suggestedChecks"), map("cause", str(3, 500),
                            "component", optStr(200), "componentCiId", Map.of("type", List.of("integer", "null")), "likelihood", num(0, 1, false),
                            "supporting", TEXT_LIST, "evidence", EVIDENCE, "suggestedChecks", TEXT_LIST)), 6),
                    "evidence", EVIDENCE, "confidence", CONFIDENCE, "proposedActions", ACTIONS));
        }

        public Class<?> outputType() {
            return RootCauseOutput.class;
        }

        public Map<String, JsonNode> gather(StepInput in, Toolbox tb) {
            Map<String, JsonNode> f = new LinkedHashMap<>();
            JsonNode inc = upstreamIncident(in, tb);
            f.put("incident", inc);
            Long svc = id(inc, "serviceId");
            String created = text(inc, "createdAt");
            f.put("changes", svc == null ? null : tb.call("recent_changes", args("serviceId", svc, "before", created)));
            f.put("deployments", svc == null ? null : tb.call("recent_deployments", args("serviceId", svc, "before", created)));
            f.put("groups", tb.call("correlation_groups", args("incidentId", in.incidentId(), "serviceId", svc)));
            f.put("anomalies", svc == null ? null : tb.call("anomalies", args("serviceId", svc)));
            f.put("problems", tb.call("problems", args("incidentId", in.incidentId(), "serviceId", svc)));
            f.put("similar", tb.call("similar_incidents", args("incidentId", in.incidentId())));
            return f;
        }

        public Object fallback(StepInput in, Map<String, JsonNode> f) {
            JsonNode inc = f.get("incident");
            if (!present(inc)) {
                return new RootCauseOutput("No root cause analysis: the incident could not be read with your permissions.", List.of(), List.of(), 0.0,
                        List.of());
            }
            Instant created = instant(inc, "createdAt");
            Instant ref = created == null ? in.now() : created;
            String service = text(inc, "serviceName");
            List<Hypothesis> hs = new ArrayList<>();
            for (JsonNode c : items(f.get("changes"))) {
                Instant at = instant(c, "actualStart") != null ? instant(c, "actualStart") : instant(c, "plannedStart");
                double hours = at == null ? 72 : Math.max(0, Duration.between(at, ref).toMinutes() / 60.0);
                double l = 0.35 + 0.25 * Math.max(0, 1 - hours / 72) + ("HIGH".equals(text(c, "risk")) ? 0.1 : 0)
                        + (List.of("FAILED", "ROLLED_BACK").contains(text(c, "status")) ? 0.15 : 0);
                hs.add(new Hypothesis("Change " + text(c, "number") + " '" + trunc(text(c, "title"), 120) + "' (" + text(c, "status") + ", risk "
                        + text(c, "risk") + ") started " + Math.round(hours) + " h before the incident", text(c, "cis") == null ? service : trunc(text(c, "cis"), 200),
                        id(c, "ciId"), round2(Math.min(0.95, l)), List.of("Change on the incident's service within 72 h"),
                        List.of(new EvidenceRef("CHANGE", id(c, "id"), text(c, "number"))), List.of("Review the change's implementation and back-out plan",
                        "Compare the incident start with the change window")));
            }
            for (JsonNode d : items(f.get("deployments"))) {
                Instant at = instant(d, "startedAt");
                double hours = at == null ? 72 : Math.max(0, Duration.between(at, ref).toMinutes() / 60.0);
                double l = 0.35 + 0.3 * Math.max(0, 1 - hours / 72) + (List.of("FAILED", "ROLLED_BACK").contains(text(d, "status")) ? 0.2 : 0);
                hs.add(new Hypothesis("Deployment " + text(d, "number") + " of version " + text(d, "version") + " to " + text(d, "environment") + " ("
                        + text(d, "status") + ") " + Math.round(hours) + " h before the incident", service, null, round2(Math.min(0.95, l)),
                        List.of("Deployment of the incident's service within 72 h"), List.of(new EvidenceRef("DEPLOYMENT", id(d, "id"), text(d, "number"))),
                        List.of("Check the release notes and error rates since the deployment", "Consider a rollback if errors started with it")));
            }
            for (JsonNode g : items(f.get("groups"))) {
                double l = 0.4 + 0.4 * dbl(g, "score", 0);
                String root = text(g, "rootCiName");
                hs.add(new Hypothesis("Correlated alerts point to " + (root == null ? "a shared component" : root) + ": " + trunc(text(g, "rootReason") == null
                        ? text(g, "title") : text(g, "rootReason"), 300), root, id(g, "rootCiId"), round2(Math.min(0.95, l)),
                        List.of(g.path("memberCount").asInt(0) + " correlated alerts/anomalies, score " + round2(dbl(g, "score", 0))),
                        List.of(new EvidenceRef("CORRELATION_GROUP", id(g, "id"), trunc(text(g, "title"), 200))),
                        List.of("Check the health of " + (root == null ? "the suggested root component" : root))));
            }
            Map<String, JsonNode> byMetric = new LinkedHashMap<>();
            for (JsonNode a : items(f.get("anomalies"))) {
                byMetric.merge(text(a, "metricName"), a, (x, y) -> dbl(y, "peakScore", 0) > dbl(x, "peakScore", 0) ? y : x);
            }
            for (JsonNode a : byMetric.values()) {
                double l = 0.3 + Math.min(0.3, dbl(a, "peakScore", 0) / 20);
                hs.add(new Hypothesis("Anomalous " + text(a, "metricName") + " (" + text(a, "direction") + ": observed " + round2(dbl(a, "observedValue", 0))
                        + " vs expected " + round2(dbl(a, "expectedValue", 0)) + ")", text(a, "ciName") == null ? service : text(a, "ciName"), id(a, "ciId"),
                        round2(l), List.of("Telemetry anomaly on the service"), List.of(new EvidenceRef("ANOMALY", id(a, "id"), text(a, "metricName"))),
                        List.of("Inspect " + text(a, "metricName") + " around the incident start")));
            }
            List<ProposedAction> actions = new ArrayList<>();
            for (JsonNode p : items(f.get("problems"))) {
                boolean known = p.path("knownError").asBoolean(false);
                hs.add(new Hypothesis((known ? "Known error " : "Open problem ") + text(p, "number") + ": " + trunc(text(p, "rootCause") == null
                        ? text(p, "title") : text(p, "rootCause"), 300), service, null, known ? 0.45 : 0.3, List.of("Problem record on the service"),
                        List.of(new EvidenceRef("PROBLEM", id(p, "id"), text(p, "number"))), text(p, "workaround") == null ? List.of()
                        : List.of("Apply the documented workaround: " + trunc(text(p, "workaround"), 300))));
                if (known && !p.path("linkedToIncident").asBoolean(false) && actions.isEmpty() && in.incidentId() != null) {
                    actions.add(new ProposedAction("LINK_PROBLEM", Map.of("problemId", String.valueOf(id(p, "id"))),
                            "The incident matches known error " + text(p, "number") + " on the same service"));
                }
            }
            for (JsonNode s : items(f.get("similar"))) {
                if (text(s, "rootCause") != null && dbl(s, "similarity", 0) >= 0.3) {
                    hs.add(new Hypothesis("Same cause as " + text(s, "number") + ": " + trunc(text(s, "rootCause"), 300), text(s, "serviceName"), null,
                            round2(Math.min(0.8, 0.25 + 0.4 * dbl(s, "similarity", 0))), List.of("Similar resolved incident (similarity "
                            + dbl(s, "similarity", 0) + ")"), List.of(new EvidenceRef("INCIDENT", id(s, "id"), text(s, "number"))),
                            List.of("Check whether the resolution of " + text(s, "number") + " applies")));
                }
            }
            hs.sort(Comparator.comparingDouble((Hypothesis h) -> -h.likelihood()));
            List<Hypothesis> top = hs.stream().limit(5).toList();
            List<EvidenceRef> ev = new ArrayList<>();
            ev.add(new EvidenceRef("INCIDENT", id(inc, "id"), text(inc, "number")));
            top.forEach(h -> ev.addAll(h.evidence()));
            String summary = top.isEmpty() ? "No changes, deployments, correlated alerts, anomalies, problems or similar incidents point to a cause yet."
                    : "Most likely: " + top.get(0).cause() + " (likelihood " + top.get(0).likelihood() + ")" + (top.size() > 1 ? "; " + (top.size() - 1)
                    + " further hypothesis(es)." : ".");
            return new RootCauseOutput(trunc(summary, 2000), top, dedupe(ev), top.isEmpty() ? 0.1 : round2(Math.min(0.95, top.get(0).likelihood())), actions);
        }
    }

    /** The incident record from the INCIDENT stage when present, else fetched. */
    private static JsonNode upstreamIncident(StepInput in, Toolbox tb) {
        return in.incidentId() == null ? null : tb.call("get_incident", args("incidentId", in.incidentId()));
    }

    // ------------------------------------------------------------------ INFRA_INVESTIGATION

    final class InfraAgent implements Agent {
        public AgentCode code() {
            return AgentCode.INFRA_INVESTIGATION;
        }

        public String name() {
            return "Infrastructure investigation agent";
        }

        public String description() {
            return "Telemetry (rate, errors, duration, metrics, error logs), anomalies, CMDB topology and recent configuration changes of the service";
        }

        public String version() {
            return "1.0.0";
        }

        public String prompt() {
            return "You are the SA infrastructure investigation agent. Report findings from telemetry, anomalies, topology and recent configuration "
                    + "changes of the affected service; say plainly when there is no telemetry. " + COMMON_RULES;
        }

        public List<String> tools() {
            return List.of("cmdb_impact", "service_red", "telemetry_metrics", "telemetry_logs", "anomalies", "twin_changes", "get_incident", "get_change");
        }

        public Map<String, Object> schema() {
            return obj(List.of("summary", "findings", "impactedComponents", "evidence", "confidence", "proposedActions"), map("summary", str(10, 2000),
                    "findings", arr(obj(List.of("title", "detail", "severity", "evidence"), map("title", str(3, 200), "detail", str(3, 1000),
                            "severity", enumOf(List.of("INFO", "WARNING", "CRITICAL"), false), "evidence", EVIDENCE)), 12),
                    "impactedComponents", arr(str(1, 200), 30), "evidence", EVIDENCE, "confidence", CONFIDENCE, "proposedActions", ACTIONS));
        }

        public Class<?> outputType() {
            return InfraOutput.class;
        }

        public Map<String, JsonNode> gather(StepInput in, Toolbox tb) {
            Map<String, JsonNode> f = new LinkedHashMap<>();
            Long svc = null;
            if (in.incidentId() != null) {
                svc = id(tb.call("get_incident", args("incidentId", in.incidentId())), "serviceId");
            } else if (in.changeId() != null) {
                JsonNode c = tb.call("get_change", args("changeId", in.changeId()));
                JsonNode services = c == null ? null : c.has("services") ? c.get("services") : c.path("change").get("services");
                for (JsonNode s : items(services)) {
                    svc = id(s, "id");
                    break;
                }
            }
            f.put("serviceId", svc == null ? null : json.getNodeFactory().numberNode(svc));
            if (svc != null) {
                f.put("impact", tb.call("cmdb_impact", args("serviceId", svc)));
                f.put("red", tb.call("service_red", args("serviceId", svc)));
                f.put("metrics", tb.call("telemetry_metrics", args("serviceId", svc)));
                f.put("logs", tb.call("telemetry_logs", args("serviceId", svc)));
                f.put("anomalies", tb.call("anomalies", args("serviceId", svc)));
                f.put("changes", tb.call("twin_changes", args("serviceId", svc)));
            }
            return f;
        }

        public Object fallback(StepInput in, Map<String, JsonNode> f) {
            if (!present(f.get("serviceId"))) {
                return new InfraOutput("No business service is known for the subject, so there is no service telemetry or topology to investigate.",
                        List.of(), List.of(), List.of(), 0.1, List.of());
            }
            long svc = f.get("serviceId").asLong();
            List<Finding> findings = new ArrayList<>();
            List<EvidenceRef> ev = new ArrayList<>();
            ev.add(new EvidenceRef("SERVICE", svc, null));
            int signals = 0;
            JsonNode red = f.get("red");
            if (present(red) && "OK".equals(text(red, "status"))) {
                signals++;
                double errorRate = dbl(red, "errorRate", 0);
                String sev = errorRate >= 0.2 ? "CRITICAL" : errorRate >= 0.05 ? "WARNING" : "INFO";
                findings.add(new Finding("Service RED: " + Math.round(errorRate * 1000) / 10.0 + "% errors", red.path("requests").asLong() + " requests ("
                        + red.path("ratePerMinute").asText("n/a") + "/min), " + red.path("errors").asLong() + " errors, p95 " + red.path("durationP95Ms").asText("n/a")
                        + " ms (" + text(red, "basis") + ")", sev, List.of(new EvidenceRef("SERVICE", svc, text(red, "serviceName")))));
            }
            JsonNode logs = f.get("logs");
            if (present(logs) && logs.path("total").asLong() > 0) {
                signals++;
                List<String> msgs = new ArrayList<>();
                for (JsonNode m : items(logs.get("topMessages"))) {
                    msgs.add(m.path("count").asLong() + "× " + trunc(text(m, "message"), 120));
                }
                boolean severe = logs.path("bySeverity").has("FATAL") || logs.path("bySeverity").has("ERROR");
                findings.add(new Finding(logs.path("total").asLong() + " warning/error log records", String.join("; ", msgs), severe ? "WARNING" : "INFO",
                        List.of()));
            }
            Map<String, JsonNode> byMetric = new LinkedHashMap<>();
            for (JsonNode a : items(f.get("anomalies"))) {
                byMetric.putIfAbsent(text(a, "metricName"), a);
                ev.add(new EvidenceRef("ANOMALY", id(a, "id"), text(a, "metricName")));
            }
            for (JsonNode a : byMetric.values()) {
                signals++;
                findings.add(new Finding("Anomaly on " + text(a, "metricName"), text(a, "status") + " " + text(a, "direction") + " anomaly: observed "
                        + round2(dbl(a, "observedValue", 0)) + " vs expected " + round2(dbl(a, "expectedValue", 0)) + " (peak z " + round2(dbl(a, "peakScore", 0))
                        + ")", "OPEN".equals(text(a, "status")) ? "WARNING" : "INFO", List.of(new EvidenceRef("ANOMALY", id(a, "id"), text(a, "metricName")))));
            }
            for (JsonNode m : items(f.get("metrics"))) {
                ev.add(new EvidenceRef("METRIC_SERIES", id(m, "seriesId"), text(m, "metric")));
                double first = dbl(m, "first", 0);
                double last = dbl(m, "last", 0);
                if (Math.abs(first) > 1e-9 && Math.abs(last - first) / Math.abs(first) >= 0.5) {
                    signals++;
                    findings.add(new Finding(text(m, "metric") + " changed " + Math.round((last - first) * 100 / Math.abs(first)) + "% in the window",
                            "From " + round2(first) + " to " + round2(last) + " (min " + dbl(m, "min", 0) + ", max " + dbl(m, "max", 0) + ")", "WARNING",
                            List.of(new EvidenceRef("METRIC_SERIES", id(m, "seriesId"), text(m, "metric")))));
                }
            }
            for (JsonNode c : items(f.get("changes"))) {
                if (findings.size() >= 12) {
                    break;
                }
                signals++;
                findings.add(new Finding("Recent " + text(c, "kind").toLowerCase(Locale.ROOT) + " change: " + trunc(text(c, "title"), 150),
                        "At " + text(c, "at") + (text(c, "ciName") == null ? "" : " on " + text(c, "ciName")), "INFO", List.of()));
            }
            List<String> components = new ArrayList<>();
            JsonNode impact = f.get("impact");
            if (present(impact)) {
                for (JsonNode d : items(impact.get("dependencies"))) {
                    components.add(text(d, "name") + " (" + text(d, "type") + ", " + text(d, "status") + ")");
                    ev.add(new EvidenceRef("CI", id(d, "id"), text(d, "name")));
                }
                for (JsonNode a : items(impact.get("openAlerts"))) {
                    ev.add(new EvidenceRef("ALERT", id(a, "id"), text(a, "number")));
                }
                if (impact.path("openAlerts").size() > 0) {
                    findings.add(new Finding(impact.path("openAlerts").size() + " open alert(s) on the service's components", "Firing alerts in the CMDB "
                            + "impact scope", "WARNING", List.of()));
                }
            }
            if (findings.size() > 12) {
                findings = findings.subList(0, 12);
            }
            boolean noTelemetry = !present(red) || !"OK".equals(text(red, "status"));
            String summary = (noTelemetry ? "No telemetry was received for the service in the window. " : "")
                    + (findings.isEmpty() ? "No infrastructure signals found." : findings.size() + " finding(s): " + findings.get(0).title()
                    + (findings.size() > 1 ? "; " + findings.get(1).title() : "") + ".") + (components.isEmpty() ? "" : " " + components.size()
                    + " upstream dependencies in the CMDB.");
            return new InfraOutput(trunc(summary, 2000), findings, components.size() > 30 ? components.subList(0, 30) : components, dedupe(ev),
                    round2(Math.min(0.9, 0.2 + 0.12 * signals)), List.of());
        }
    }

    // ------------------------------------------------------------------ KNOWLEDGE

    final class KnowledgeAgent implements Agent {
        public AgentCode code() {
            return AgentCode.KNOWLEDGE;
        }

        public String name() {
            return "Knowledge agent";
        }

        public String description() {
            return "Retrieves relevant knowledge articles and resolved incidents and answers with citations";
        }

        public String version() {
            return "1.0.0";
        }

        public String prompt() {
            return "You are the SA knowledge agent. Answer from the knowledge articles and resolved incidents only, citing each article you use "
                    + "(articleId); if nothing relevant exists say so. " + COMMON_RULES;
        }

        public List<String> tools() {
            return List.of("kb_search", "similar_incidents", "get_incident");
        }

        public Map<String, Object> schema() {
            return obj(List.of("answer", "citations", "evidence", "confidence", "proposedActions"), map("answer", str(5, 4000),
                    "citations", arr(obj(List.of("articleId", "title", "relevance"), map("articleId", Map.of("type", "integer", "minimum", 1),
                            "number", optStr(30), "title", str(1, 200), "relevance", num(0, 1, false), "excerpt", optStr(500))), 10),
                    "evidence", EVIDENCE, "confidence", CONFIDENCE, "proposedActions", ACTIONS));
        }

        public Class<?> outputType() {
            return KnowledgeOutput.class;
        }

        public Map<String, JsonNode> gather(StepInput in, Toolbox tb) {
            Map<String, JsonNode> f = new LinkedHashMap<>();
            String query = in.question();
            Long svc = null;
            if (in.incidentId() != null) {
                JsonNode inc = tb.call("get_incident", args("incidentId", in.incidentId()));
                f.put("incident", inc);
                if (present(inc)) {
                    query = text(inc, "title") + " " + (text(inc, "description") == null ? "" : text(inc, "description"));
                    svc = id(inc, "serviceId");
                }
            }
            if (query != null && !query.isBlank()) {
                f.put("articles", tb.call("kb_search", args("query", trunc(query, 2000), "serviceId", svc)));
                f.put("similar", in.incidentId() != null ? tb.call("similar_incidents", args("incidentId", in.incidentId()))
                        : tb.call("similar_incidents", args("query", trunc(query, 500))));
            }
            return f;
        }

        public Object fallback(StepInput in, Map<String, JsonNode> f) {
            List<Citation> citations = new ArrayList<>();
            List<EvidenceRef> ev = new ArrayList<>();
            for (JsonNode a : items(f.get("articles"))) {
                double rel = Math.max(0, Math.min(1, dbl(a, "relevance", 0)));
                citations.add(new Citation(id(a, "id"), text(a, "number"), trunc(text(a, "title"), 200), round2(rel), trunc(text(a, "summary"), 500)));
                ev.add(new EvidenceRef("KB_ARTICLE", id(a, "id"), text(a, "number")));
            }
            List<String> resolutions = new ArrayList<>();
            JsonNode bestResolved = null;
            for (JsonNode s : items(f.get("similar"))) {
                ev.add(new EvidenceRef("INCIDENT", id(s, "id"), text(s, "number")));
                String how = text(s, "resolutionNotes") != null ? text(s, "resolutionNotes") : text(s, "rootCause");
                if (how != null) {
                    resolutions.add(text(s, "number") + ": " + trunc(how, 200));
                    if (bestResolved == null) {
                        bestResolved = s;
                    }
                }
            }
            if (present(f.get("incident"))) {
                ev.add(0, new EvidenceRef("INCIDENT", id(f.get("incident"), "id"), text(f.get("incident"), "number")));
            }
            String answer;
            if (citations.isEmpty() && resolutions.isEmpty()) {
                answer = f.containsKey("articles") ? "No knowledge article or resolved incident matches." : "There was nothing to search for (no readable incident or question).";
            } else {
                StringBuilder b = new StringBuilder();
                if (!citations.isEmpty()) {
                    Citation top = citations.get(0);
                    b.append("The most relevant article is ").append(top.number()).append(" '").append(top.title()).append("' (relevance ")
                            .append(top.relevance()).append(")");
                    if (citations.size() > 1) {
                        b.append("; also ").append(citations.stream().skip(1).limit(3).map(c -> c.number() + " '" + c.title() + "'").reduce((x, y) -> x + ", " + y)
                                .orElse(""));
                    }
                    b.append(". ");
                }
                if (!resolutions.isEmpty()) {
                    b.append("Similar resolved incidents: ").append(String.join(" | ", resolutions.stream().limit(3).toList())).append(".");
                }
                answer = b.toString();
            }
            List<ProposedAction> actions = new ArrayList<>();
            boolean noGoodArticle = citations.stream().noneMatch(c -> c.relevance() >= 0.3);
            JsonNode inc = f.get("incident");
            if (noGoodArticle && bestResolved != null && present(inc) && in.incidentId() != null) {
                String body = "Symptoms: " + trunc(text(inc, "title"), 200) + "\n\nCause: " + (text(bestResolved, "rootCause") == null ? "see the resolution"
                        : text(bestResolved, "rootCause")) + "\n\nResolution: " + (text(bestResolved, "resolutionNotes") == null ? text(bestResolved, "rootCause")
                        : text(bestResolved, "resolutionNotes")) + "\n\nSource incidents: " + text(bestResolved, "number") + ", " + text(inc, "number");
                actions.add(new ProposedAction("PUBLISH_KB_DRAFT", args("title", trunc("How to resolve: " + text(inc, "title"), 200), "summary",
                        trunc("Resolution of recurring issue seen in " + text(bestResolved, "number") + " and " + text(inc, "number"), 500), "content", body,
                        "category", text(inc, "category") == null ? "OTHER" : text(inc, "category"), "serviceId", id(inc, "serviceId")),
                        "No knowledge article covers this issue while a similar incident was resolved"));
            }
            double conf = citations.isEmpty() ? (resolutions.isEmpty() ? 0.1 : 0.4) : Math.min(0.9, 0.3 + 0.6 * citations.get(0).relevance());
            return new KnowledgeOutput(trunc(answer, 4000), citations, dedupe(ev), round2(conf), actions);
        }
    }

    // ------------------------------------------------------------------ RESOLUTION

    final class ResolutionAgent implements Agent {
        public AgentCode code() {
            return AgentCode.RESOLUTION;
        }

        public String name() {
            return "Resolution agent";
        }

        public String description() {
            return "Synthesises triage, root cause, infrastructure and knowledge results into a resolution plan and a playbook (runbook) recommendation";
        }

        public String version() {
            return "1.0.0";
        }

        public String prompt() {
            return "You are the SA resolution agent. Combine the earlier agents' outputs (given as data) into an ordered resolution plan for a human "
                    + "operator and recommend a runbook when one fits. Describe actions in plain language. " + COMMON_RULES;
        }

        public List<String> tools() {
            return List.of("runbooks", "kb_search", "problems", "similar_incidents");
        }

        public Map<String, Object> schema() {
            return obj(List.of("summary", "steps", "evidence", "confidence", "proposedActions"), map("summary", str(10, 2000), "steps", arr(str(3, 500), 12),
                    "runbook", Map.of("type", List.of("object", "null"), "required", List.of("id", "title", "requiresApproval", "score"), "properties",
                            map("id", Map.of("type", "integer"), "title", str(1, 200), "requiresApproval", Map.of("type", "boolean"), "score",
                                    Map.of("type", "integer", "minimum", 0, "maximum", 100)), "additionalProperties", false),
                    "evidence", EVIDENCE, "confidence", CONFIDENCE, "proposedActions", ACTIONS));
        }

        public Class<?> outputType() {
            return ResolutionOutput.class;
        }

        public Map<String, JsonNode> gather(StepInput in, Toolbox tb) {
            Map<String, JsonNode> f = new LinkedHashMap<>(in.upstream());
            if (in.incidentId() != null) {
                f.put("runbooks", tb.call("runbooks", args("incidentId", in.incidentId())));
            }
            return f;
        }

        public Object fallback(StepInput in, Map<String, JsonNode> f) {
            List<String> steps = new ArrayList<>();
            List<EvidenceRef> ev = new ArrayList<>();
            double conf = 0.2;
            JsonNode root = f.get(AgentCode.ROOT_CAUSE.name());
            JsonNode infra = f.get(AgentCode.INFRA_INVESTIGATION.name());
            JsonNode kb = f.get(AgentCode.KNOWLEDGE.name());
            JsonNode triage = f.get(AgentCode.INCIDENT.name());
            if (present(triage) && text(triage, "recommendedPriority") != null && !text(triage, "recommendedPriority").equals(text(triage, "priority"))) {
                steps.add("Re-prioritise to " + text(triage, "recommendedPriority") + ": " + text(triage, "priorityReason"));
            }
            JsonNode top = root == null ? null : root.path("hypotheses").path(0);
            if (present(top)) {
                steps.add("Verify the leading hypothesis: " + trunc(text(top, "cause"), 300));
                for (JsonNode c : items(top.get("suggestedChecks"))) {
                    if (steps.size() < 6) {
                        steps.add(trunc(c.asText(), 300));
                    }
                }
                for (JsonNode e : items(top.get("evidence"))) {
                    ev.add(new EvidenceRef(text(e, "type"), id(e, "id"), text(e, "label")));
                }
                conf = Math.max(conf, dbl(top, "likelihood", 0) * 0.8);
            }
            if (present(infra)) {
                for (JsonNode fi : items(infra.get("findings"))) {
                    if (!"INFO".equals(text(fi, "severity")) && steps.size() < 9) {
                        steps.add("Investigate: " + trunc(text(fi, "title"), 200));
                    }
                }
            }
            JsonNode cite = kb == null ? null : kb.path("citations").path(0);
            if (present(cite)) {
                steps.add("Follow knowledge article " + text(cite, "number") + " '" + trunc(text(cite, "title"), 150) + "'");
                ev.add(new EvidenceRef("KB_ARTICLE", id(cite, "articleId"), text(cite, "number")));
                conf = Math.max(conf, 0.3 + 0.5 * dbl(cite, "relevance", 0));
            }
            RunbookRef runbook = null;
            List<ProposedAction> actions = new ArrayList<>();
            JsonNode rb = f.get("runbooks") == null ? null : f.get("runbooks").path(0);
            if (present(rb)) {
                runbook = new RunbookRef(id(rb, "id"), trunc(text(rb, "title"), 200), rb.path("requiresApproval").asBoolean(false), rb.path("score").asInt(0));
                ev.add(new EvidenceRef("RUNBOOK", runbook.id(), runbook.title()));
                steps.add("Run runbook '" + runbook.title() + "'" + (runbook.requiresApproval() ? " (requires a manager's approval)" : ""));
                if (runbook.score() >= 40 && in.incidentId() != null) {
                    actions.add(new ProposedAction("RUN_PLAYBOOK", Map.of("runbookId", String.valueOf(runbook.id())),
                            "Runbook '" + runbook.title() + "' fits the incident (score " + runbook.score() + ")"));
                }
                conf = Math.max(conf, runbook.score() / 120.0);
            }
            if (steps.isEmpty()) {
                steps.add("No specific evidence was found; gather diagnostics from the affected users and the service owner");
            }
            steps.add("Record the root cause and resolution on the incident once confirmed");
            String summary = present(top) ? "Plan built on the leading hypothesis (" + trunc(text(top, "cause"), 200) + ")"
                    + (runbook == null ? "" : " with runbook '" + runbook.title() + "'") + "."
                    : runbook != null ? "Plan built around runbook '" + runbook.title() + "'." : "Generic plan: no strong evidence was found.";
            return new ResolutionOutput(trunc(summary, 2000), steps.size() > 12 ? steps.subList(0, 12) : steps, runbook, dedupe(ev),
                    round2(Math.min(0.9, conf)), actions);
        }
    }

    // ------------------------------------------------------------------ CHANGE_RISK

    final class ChangeRiskAgent implements Agent {
        public AgentCode code() {
            return AgentCode.CHANGE_RISK;
        }

        public String name() {
            return "Change risk agent";
        }

        public String description() {
            return "Risk of a change from its own risk assessment, the CMDB blast radius, the deployment history of its services and incidents after it";
        }

        public String version() {
            return "1.0.0";
        }

        public String prompt() {
            return "You are the SA change risk agent. Assess the change's risk (LOW, MEDIUM or HIGH) from the change record, its CMDB impact, the "
                    + "deployment history of its services and incidents that followed it, with factors and recommendations. " + COMMON_RULES;
        }

        public List<String> tools() {
            return List.of("get_change", "cmdb_impact", "recent_deployments", "incidents_after_changes");
        }

        public Map<String, Object> schema() {
            return obj(List.of("summary", "riskLevel", "score", "factors", "recommendations", "impactedServices", "evidence", "confidence",
                    "proposedActions"), map("summary", str(10, 2000), "riskLevel", enumOf(List.of("LOW", "MEDIUM", "HIGH", "UNKNOWN"), false),
                    "score", Map.of("type", "integer", "minimum", 0, "maximum", 100), "factors", TEXT_LIST, "recommendations", TEXT_LIST,
                    "impactedServices", arr(str(1, 200), 30), "evidence", EVIDENCE, "confidence", CONFIDENCE, "proposedActions", ACTIONS));
        }

        public Class<?> outputType() {
            return ChangeRiskOutput.class;
        }

        public Map<String, JsonNode> gather(StepInput in, Toolbox tb) {
            Map<String, JsonNode> f = new LinkedHashMap<>();
            JsonNode c = tb.call("get_change", args("changeId", in.changeId()));
            f.put("change", c);
            if (present(c)) {
                JsonNode services = c.has("services") ? c.get("services") : c.path("change").get("services");
                int n = 0;
                for (JsonNode s : items(services)) {
                    if (n++ >= 3) {
                        break;
                    }
                    f.put("impact:" + id(s, "id"), tb.call("cmdb_impact", args("serviceId", id(s, "id"))));
                    f.put("deployments:" + id(s, "id"), tb.call("recent_deployments", args("serviceId", id(s, "id"), "hours", 24 * 30)));
                }
                f.put("incidents", tb.call("incidents_after_changes", args("changeId", in.changeId())));
            }
            return f;
        }

        public Object fallback(StepInput in, Map<String, JsonNode> f) {
            JsonNode c = f.get("change");
            if (!present(c)) {
                return new ChangeRiskOutput("The change could not be read with your permissions, so its risk cannot be assessed.", "UNKNOWN", 0,
                        List.of("get_change returned no data"), List.of("Ask a change manager to review the change"), List.of(), List.of(), 0.0, List.of());
            }
            JsonNode change = c.has("number") ? c : c.path("change");
            List<String> factors = new ArrayList<>();
            List<EvidenceRef> ev = new ArrayList<>();
            ev.add(new EvidenceRef("CHANGE", in.changeId(), text(change, "number")));
            String stored = text(change, "risk");
            int score = "HIGH".equals(stored) ? 45 : "MEDIUM".equals(stored) ? 25 : 10;
            factors.add("Recorded risk assessment: " + (stored == null ? "none" : stored));
            if ("EMERGENCY".equals(text(change, "type")) || "EMERGENCY".equals(text(change, "changeType"))) {
                score += 10;
                factors.add("Emergency change (expedited approval)");
            }
            Set<String> impacted = new LinkedHashSet<>();
            int tier1 = 0;
            int deployments = 0;
            int failed = 0;
            for (Map.Entry<String, JsonNode> e : f.entrySet()) {
                if (e.getKey().startsWith("impact:") && present(e.getValue())) {
                    for (JsonNode s : items(e.getValue().get("impactedServices"))) {
                        if (impacted.add(text(s, "name")) && s.path("tier").asInt(3) == 1) {
                            tier1++;
                        }
                    }
                    ev.add(new EvidenceRef("CI", id(e.getValue().path("ci"), "id"), text(e.getValue().path("ci"), "name")));
                }
                if (e.getKey().startsWith("deployments:")) {
                    for (JsonNode d : items(e.getValue())) {
                        deployments++;
                        if (List.of("FAILED", "ROLLED_BACK").contains(text(d, "status"))) {
                            failed++;
                            ev.add(new EvidenceRef("DEPLOYMENT", id(d, "id"), text(d, "number")));
                        }
                    }
                }
            }
            if (!impacted.isEmpty()) {
                score += Math.min(20, 4 * impacted.size());
                factors.add(impacted.size() + " business service(s) in the blast radius" + (tier1 > 0 ? ", " + tier1 + " of them tier 1" : ""));
            }
            if (tier1 > 0) {
                score += 10;
            }
            if (deployments >= 3 && failed * 100 / deployments >= 30) {
                score += 15;
                factors.add(failed + " of " + deployments + " deployments of the services in the last 30 days failed or were rolled back");
            } else if (deployments > 0) {
                factors.add(failed + " of " + deployments + " recent deployments failed or were rolled back");
            }
            JsonNode inc = f.get("incidents");
            int after = 0;
            for (JsonNode x : items(inc == null ? null : inc.get("correlated"))) {
                after++;
                ev.add(new EvidenceRef("INCIDENT", id(x.path("incident"), "id"), text(x.path("incident"), "number")));
            }
            if (after > 0) {
                score += Math.min(20, 5 * after);
                factors.add(after + " incident(s) on the change's services started within 24 h after the change");
            }
            score = Math.min(100, score);
            String level = score >= 60 ? "HIGH" : score >= 30 ? "MEDIUM" : "LOW";
            List<String> rec = new ArrayList<>();
            if (!"LOW".equals(level)) {
                rec.add("Confirm a tested back-out plan before the change window");
                rec.add("Schedule the change outside the business peak of the impacted services");
            }
            if (tier1 > 0) {
                rec.add("Notify the owners of the tier 1 services in the blast radius");
            }
            if (after > 0) {
                rec.add("Review the incidents that followed the change before closing it");
            }
            if (rec.isEmpty()) {
                rec.add("Proceed with the standard change procedure");
            }
            String summary = text(change, "number") + " risk " + level + " (score " + score + "): " + factors.get(0) + (factors.size() > 1 ? "; "
                    + factors.get(1) : "") + ".";
            return new ChangeRiskOutput(trunc(summary, 2000), level, score, factors, rec, new ArrayList<>(impacted).subList(0, Math.min(30, impacted.size())),
                    dedupe(ev), round2(Math.min(0.9, 0.4 + 0.1 * Math.min(4, factors.size()))), List.of());
        }
    }
}
