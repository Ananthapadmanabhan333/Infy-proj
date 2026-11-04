package com.nexus360.ai;

import com.nexus360.admin.SettingsService;
import com.nexus360.ai.AiDtos.AiResult;
import com.nexus360.ai.OpsAiDtos.AlertGroupRef;
import com.nexus360.ai.OpsAiDtos.CmdbImpact;
import com.nexus360.ai.OpsAiDtos.CmdbImpactOut;
import com.nexus360.ai.OpsAiDtos.CorrelatedChange;
import com.nexus360.ai.OpsAiDtos.CorrelatedIncident;
import com.nexus360.ai.OpsAiDtos.ImpactedServiceRef;
import com.nexus360.ai.OpsAiDtos.ProblemCorrelation;
import com.nexus360.ai.OpsAiDtos.ProblemCorrelationOut;
import com.nexus360.ai.OpsAiDtos.RunbookOut;
import com.nexus360.ai.OpsAiDtos.RunbookRecommendation;
import com.nexus360.ai.OpsAiDtos.RunbookRecommendations;
import com.nexus360.ai.OpsAiDtos.SlaRisk;
import com.nexus360.ai.OpsAiDtos.SlaRiskOut;
import com.nexus360.ai.OpsAiDtos.SlaRiskOverview;
import com.nexus360.ai.OpsAiDtos.Trend;
import com.nexus360.ai.OpsAiDtos.TrendAnalysis;
import com.nexus360.ai.OpsAiDtos.TrendOut;
import com.nexus360.cmdb.CmdbDtos.ImpactAnalysis;
import com.nexus360.cmdb.CmdbGraphService;
import com.nexus360.common.ApiException;
import com.nexus360.incident.IncidentDtos.SlaSnapshot;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.incident.TextSimilarity;
import com.nexus360.security.Actor;
import com.nexus360.sla.IncidentSla;
import com.nexus360.sla.IncidentSlaRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Release 3 SA AI features (docs/RELEASE3_SPEC.md §5 BE-4): problem correlation, CMDB impact analysis, runbook recommendations,
 * predictive SLA risk and trend analysis. Each feature first computes its evidence, scores and probabilities from the database
 * (the deterministic engine), then — through {@link AiService#runGrounded} — asks the LLM via {@link GuardedLlm} (timeout, retry,
 * schema and content validation; no SQL or commands) to select among and explain the computed candidates; any failure answers
 * with the deterministic result, labelled FALLBACK. Change risk reuses {@code ChangeAiAdvisor} ({@code POST /api/ai/changes/{id}/risk}).
 */
@Component
@RequiredArgsConstructor
public class OpsAiAdvisor {

    static final int MIN_SAMPLES = 5;
    private static final String ACTIVE = IncidentStatus.activeSqlList();

    private final AiService ai;
    private final GuardedLlm llm;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CmdbGraphService cmdb;
    private final IncidentSlaRepository slas;
    private final IncidentMapper mapper;
    private final SettingsService settings;
    private final Clock clock;

    // ------------------------------------------------------------------ problem correlation

    public AiResult<ProblemCorrelation> problemCorrelation(Long problemId, Actor actor) {
        ProblemCorrelation base = correlate(problemId);
        return ai.runGrounded("problem_correlation", base.number() + " · " + base.title(), actor, () -> {
            String data = "<data>\nProblem " + base.number() + ": " + base.title() + "\nCandidates:\n" + base.candidates().stream()
                    .map(c -> c.number() + " (" + c.status() + ", score " + c.score() + "): " + c.title() + " — " + String.join("; ", c.reasons()))
                    .collect(Collectors.joining("\n")) + "\nChanges before the linked incidents: " + base.changes().stream()
                    .map(c -> c.number() + " " + c.title() + " (" + c.status() + ", " + c.incidentsWithin48h() + " incidents within 48 h)")
                    .collect(Collectors.joining("; ")) + "\nAlert groups: " + base.alertGroups().stream().map(g -> g.title() + " x" + g.alerts())
                    .collect(Collectors.joining("; ")) + "\n</data>";
            Set<String> allowed = base.candidates().stream().map(CorrelatedIncident::number).collect(Collectors.toSet());
            ProblemCorrelationOut out = llm.generate("Correlate incidents with a problem record. Choose only among the candidate incidents.", data,
                    ProblemCorrelationOut.class, o -> {
                        requireText(o.summary, "summary");
                        if (o.relatedIncidentNumbers == null || !allowed.containsAll(o.relatedIncidentNumbers.stream().map(String::trim).toList())) {
                            throw invalid("relatedIncidentNumbers must be candidate numbers");
                        }
                    });
            List<String> order = out.relatedIncidentNumbers.stream().map(String::trim).distinct().toList();
            List<CorrelatedIncident> chosen = order.stream().map(n -> base.candidates().stream().filter(c -> c.number().equals(n)).findFirst().orElseThrow())
                    .toList();
            return new ProblemCorrelation(base.problemId(), base.number(), base.title(), chosen, base.changes(), base.alertGroups(), out.summary.trim(),
                    "LLM selection among the deterministic candidates; scores from text similarity, service and category");
        }, () -> base);
    }

    ProblemCorrelation correlate(Long problemId) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", problemId);
        List<Map<String, Object>> rows = jdbc.queryForList("select id, number, title, description, root_cause, category from problems where id = :id", p);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Problem", problemId);
        }
        Map<String, Object> problem = rows.get(0);
        String title = (String) problem.get("title");
        String text = join((String) problem.get("description"), (String) problem.get("root_cause"));
        String category = (String) problem.get("category");
        Set<Long> serviceIds = new HashSet<>(jdbc.queryForList("select service_id from problem_services where problem_id = :id", p, Long.class));
        List<Map<String, Object>> linked = jdbc.queryForList("select i.id, i.title, i.created_at, i.service_id, i.category from problem_incidents pi "
                + "join incidents i on i.id = pi.incident_id where pi.problem_id = :id order by i.created_at", p);
        linked.forEach(l -> {
            if (l.get("service_id") != null) {
                serviceIds.add(((Number) l.get("service_id")).longValue());
            }
        });
        Map<Long, String> serviceNames = new HashMap<>();
        jdbc.query("select id, name from business_services", rs -> {
            serviceNames.put(rs.getLong(1), rs.getString(2));
        });
        Instant now = clock.instant();
        List<CorrelatedIncident> candidates = new ArrayList<>();
        jdbc.query("select i.id, i.number, i.title, i.description, i.status, i.created_at, i.service_id, i.category from incidents i "
                + "where i.parent_id is null and coalesce(i.resolution_code, '') <> 'CANCELLED' and i.created_at >= :from "
                + "and not exists (select 1 from problem_incidents pi where pi.incident_id = i.id) order by i.created_at desc limit 2000",
                new MapSqlParameterSource("from", at(now.minus(Duration.ofDays(90)))), rs -> {
                    String t = rs.getString("title");
                    String d = rs.getString("description");
                    double sim = TextSimilarity.score(title, text, t, d);
                    for (Map<String, Object> l : linked) {
                        sim = Math.max(sim, TextSimilarity.score((String) l.get("title"), null, t, d));
                    }
                    Long svc = (Long) rs.getObject("service_id");
                    boolean sameService = svc != null && serviceIds.contains(svc);
                    boolean sameCategory = category != null && category.equals(rs.getString("category"));
                    double score = Math.round((0.7 * sim + (sameService ? 0.2 : 0) + (sameCategory ? 0.1 : 0)) * 100) / 100.0;
                    if (score >= 0.3 && sim >= 0.15) {
                        List<String> reasons = new ArrayList<>();
                        reasons.add("Text similarity " + Math.round(sim * 100) + "%");
                        if (sameService) {
                            reasons.add("Same service: " + serviceNames.get(svc));
                        }
                        if (sameCategory) {
                            reasons.add("Same category: " + category);
                        }
                        candidates.add(new CorrelatedIncident(rs.getLong("id"), rs.getString("number"), t, rs.getString("status"),
                                rs.getTimestamp("created_at").toInstant(), score, reasons));
                    }
                });
        candidates.sort(Comparator.comparingDouble(CorrelatedIncident::score).reversed().thenComparing(CorrelatedIncident::createdAt,
                Comparator.reverseOrder()));
        List<CorrelatedIncident> top = candidates.size() > 10 ? List.copyOf(candidates.subList(0, 10)) : candidates;
        List<CorrelatedChange> changes = new ArrayList<>();
        List<Instant> linkedTimes = linked.stream().map(l -> ((java.sql.Timestamp) l.get("created_at")).toInstant()).toList();
        if (!serviceIds.isEmpty() && !linkedTimes.isEmpty()) {
            Instant first = linkedTimes.stream().min(Comparator.naturalOrder()).orElseThrow();
            Instant last = linkedTimes.stream().max(Comparator.naturalOrder()).orElseThrow();
            jdbc.query("select distinct c.id, c.number, c.title, c.status, coalesce(c.actual_start, c.planned_start) as started from changes c "
                    + "join change_services cs on cs.change_id = c.id where cs.service_id in (:services) and c.status in ('IMPLEMENTING', 'COMPLETED', "
                    + "'FAILED', 'ROLLED_BACK') and coalesce(c.actual_start, c.planned_start) between :from and :to order by started desc limit 10",
                    new MapSqlParameterSource("services", serviceIds).addValue("from", at(first.minus(Duration.ofDays(14)))).addValue("to", at(last)), rs -> {
                        Instant started = rs.getTimestamp("started").toInstant();
                        long within = linkedTimes.stream().filter(t -> !t.isBefore(started) && t.isBefore(started.plus(Duration.ofHours(48)))).count();
                        changes.add(new CorrelatedChange(rs.getLong("id"), rs.getString("number"), rs.getString("title"), rs.getString("status"), started,
                                within));
                    });
            changes.sort(Comparator.comparingLong(CorrelatedChange::incidentsWithin48h).reversed());
        }
        List<AlertGroupRef> groups = serviceIds.isEmpty() ? List.of() : jdbc.query("select coalesce(a.group_key, a.fingerprint) as k, min(a.title) as t, "
                + "count(*) as n, max(case a.severity when 'CRITICAL' then 5 when 'MAJOR' then 4 when 'MINOR' then 3 when 'WARNING' then 2 else 1 end) as r "
                + "from alerts a where a.service_id in (:services) and a.first_seen_at >= :from group by 1 order by n desc limit 5",
                new MapSqlParameterSource("services", serviceIds).addValue("from", at(now.minus(Duration.ofDays(30)))),
                (rs, n) -> new AlertGroupRef(rs.getString("k"), rs.getString("t"), rs.getLong("n"), severity(rs.getInt("r"))));
        String summary = top.isEmpty() ? "No unlinked incident of the last 90 days resembles " + problem.get("number") + "."
                : top.size() + " unlinked incident(s) resemble " + problem.get("number") + "; the closest is " + top.get(0).number() + " ("
                + Math.round(top.get(0).score() * 100) + "% match)." + (changes.isEmpty() ? "" : " Change " + changes.get(0).number()
                + " preceded " + changes.get(0).incidentsWithin48h() + " of the linked incidents within 48 hours.");
        return new ProblemCorrelation(problemId, (String) problem.get("number"), title, top, changes, groups, summary,
                "Deterministic: text similarity (tokens and trigrams) to the problem and its linked incidents, same service +0.2, same category +0.1; "
                        + "changes on the problem's services in the 14 days before its incidents; alert groups on those services (30 days)");
    }

    // ------------------------------------------------------------------ CMDB impact

    public AiResult<CmdbImpact> cmdbImpact(Long ciId, Actor actor) {
        CmdbImpact base = impact(ciId);
        return ai.runGrounded("cmdb_impact", base.ciName(), actor, () -> {
            String data = "<data>\nCI: " + base.ciName() + " (" + base.ciType() + ", criticality " + base.criticality() + ")\nImpacted business services: "
                    + base.impactedServices().stream().map(s -> s.name() + " (tier " + s.tier() + ", " + s.status() + ")").collect(Collectors.joining("; "))
                    + "\nDepends on (critical): " + String.join(", ", base.criticalUpstream()) + "\nUpstream CIs: " + base.upstreamCount()
                    + ", downstream CIs: " + base.downstreamCount() + "\nOpen incidents on impacted services: " + base.openIncidents()
                    + "\nOpen alerts on impacted CIs: " + base.openAlerts() + "\nComputed risk: " + base.riskLevel() + "\n</data>";
            CmdbImpactOut out = llm.generate("Explain the impact of a configuration item failing or changing, for operators and change planners.", data,
                    CmdbImpactOut.class, o -> {
                        requireText(o.summary, "summary");
                        if (clean(o.recommendations).isEmpty()) {
                            throw invalid("No recommendations returned");
                        }
                    });
            return new CmdbImpact(base.ciId(), base.ciName(), base.ciType(), base.criticality(), base.riskLevel(), base.impactedServices(),
                    base.upstreamCount(), base.downstreamCount(), base.criticalUpstream(), base.openIncidents(), base.openAlerts(), out.summary.trim(),
                    clean(out.recommendations), base.truncated(), "LLM explanation of the CMDB traversal; services, counts and risk computed from the CMDB");
        }, () -> base);
    }

    CmdbImpact impact(Long ciId) {
        ImpactAnalysis a = cmdb.impact(ciId, "both", 4);
        String criticality = jdbc.queryForObject("select criticality from configuration_items where id = :id", new MapSqlParameterSource("id", ciId),
                String.class);
        List<ImpactedServiceRef> services = a.impactedServices().stream().map(s -> new ImpactedServiceRef(s.serviceId(), s.code(), s.name(), s.tier(),
                s.status())).toList();
        List<String> criticalUpstream = a.upstream().stream().filter(n -> n.criticality() != null && n.criticality().ordinal() >= 2)
                .map(n -> n.ci().name() + " (" + n.ci().ciType() + ")").distinct().limit(8).toList();
        boolean tier1 = services.stream().anyMatch(s -> s.tier() == 1);
        boolean outage = services.stream().anyMatch(s -> "OUTAGE".equals(s.status()));
        String risk = tier1 || "CRITICAL".equals(criticality) || outage ? "HIGH"
                : !services.isEmpty() || a.downstream().size() >= 5 || "HIGH".equals(criticality) ? "MEDIUM" : "LOW";
        List<String> rec = new ArrayList<>();
        if (!services.isEmpty()) {
            rec.add("Inform the owners of the " + services.size() + " dependent business service(s) before any change: "
                    + services.stream().map(ImpactedServiceRef::name).collect(Collectors.joining(", ")));
        }
        if (tier1) {
            rec.add("A tier-1 service depends on " + a.ci().name() + ": schedule changes in an approved maintenance window with a tested rollback");
        }
        if (!criticalUpstream.isEmpty()) {
            rec.add("When it fails, check its critical dependencies first: " + String.join(", ", criticalUpstream));
        }
        if (!a.openAlerts().isEmpty()) {
            rec.add(a.openAlerts().size() + " alert(s) are open on the affected CIs; review them before acting");
        }
        if (!a.openIncidents().isEmpty()) {
            rec.add(a.openIncidents().size() + " active incident(s) on the impacted services may be related: link them to one investigation");
        }
        if (rec.isEmpty()) {
            rec.add("No business service depends on " + a.ci().name() + " in the CMDB; verify the relationships are complete");
        }
        String summary = a.ci().name() + " (" + a.ci().ciType() + ") has " + a.downstream().size() + " dependent CI(s) and " + a.upstream().size()
                + " dependencies; " + (services.isEmpty() ? "no business service depends on it." : services.size() + " business service(s) would be "
                + "affected" + (tier1 ? ", including tier-1 " + services.stream().filter(s -> s.tier() == 1).map(ImpactedServiceRef::name)
                .collect(Collectors.joining(", ")) : "") + ".");
        return new CmdbImpact(ciId, a.ci().name(), a.ci().ciType().name(), criticality, risk, services, a.upstream().size(), a.downstream().size(),
                criticalUpstream, a.openIncidents().size(), a.openAlerts().size(), summary, rec, a.truncated(),
                "Deterministic: CMDB traversal (both directions, depth 4); risk HIGH with a tier-1 service, a CRITICAL CI or an outage, MEDIUM with any "
                        + "dependent service or ≥ 5 dependent CIs");
    }

    // ------------------------------------------------------------------ runbooks

    public AiResult<RunbookRecommendations> runbooks(Long incidentId, Actor actor) {
        RunbookRecommendations base = rankRunbooks(incidentId);
        return ai.runGrounded("runbook_recommendation", base.incidentNumber(), actor, () -> {
            if (base.recommendations().isEmpty()) {
                return base;
            }
            Map<String, Object> incident = jdbc.queryForMap("select title, description from incidents where id = :id", new MapSqlParameterSource("id", incidentId));
            String data = "<incident>\n" + incident.get("title") + "\n" + Objects.toString(incident.get("description"), "") + "\n</incident>\n<candidates>\n"
                    + base.recommendations().stream().map(r -> r.runbookId() + ": " + r.title() + " (score " + r.score() + "; " + String.join("; ", r.reasons()) + ")")
                    .collect(Collectors.joining("\n")) + "\n</candidates>";
            Set<Long> allowed = base.recommendations().stream().map(RunbookRecommendation::runbookId).collect(Collectors.toSet());
            RunbookOut out = llm.generate("Recommend runbooks for an incident. Choose only among the candidate runbooks.", data, RunbookOut.class, o -> {
                requireText(o.rationale, "rationale");
                if (o.runbookIds == null || o.runbookIds.isEmpty() || !allowed.containsAll(o.runbookIds)) {
                    throw invalid("runbookIds must be candidate ids");
                }
            });
            List<RunbookRecommendation> chosen = out.runbookIds.stream().distinct().map(id -> base.recommendations().stream()
                    .filter(r -> r.runbookId().equals(id)).findFirst().orElseThrow()).toList();
            return new RunbookRecommendations(base.incidentId(), base.incidentNumber(), chosen, out.rationale.trim(),
                    "LLM selection among the deterministic candidates; scores from service, category, text and execution history");
        }, () -> base);
    }

    RunbookRecommendations rankRunbooks(Long incidentId) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", incidentId);
        List<Map<String, Object>> rows = jdbc.queryForList("select number, title, description, category, service_id from incidents where id = :id", p);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Incident", incidentId);
        }
        Map<String, Object> inc = rows.get(0);
        Long serviceId = inc.get("service_id") == null ? null : ((Number) inc.get("service_id")).longValue();
        String category = (String) inc.get("category");
        Map<Long, long[]> history = new HashMap<>();
        if (serviceId != null) {
            jdbc.query("select e.runbook_id, count(*) filter (where e.status = 'COMPLETED') as ok, count(*) filter (where e.status in ('COMPLETED', "
                    + "'ABORTED')) as done from runbook_executions e join incidents i on i.id = e.incident_id where i.service_id = :svc group by e.runbook_id",
                    new MapSqlParameterSource("svc", serviceId), rs -> {
                        history.put(rs.getLong(1), new long[]{rs.getLong(2), rs.getLong(3)});
                    });
        }
        List<RunbookRecommendation> list = new ArrayList<>();
        jdbc.query("select r.id, r.title, r.description, r.service_id, r.requires_approval, r.estimated_minutes, "
                + "coalesce((select string_agg(c.category, ',') from runbook_categories c where c.runbook_id = r.id), '') as cats "
                + "from runbooks r where r.active", rs -> {
            int score = 0;
            List<String> reasons = new ArrayList<>();
            Long svc = (Long) rs.getObject("service_id");
            if (serviceId != null && serviceId.equals(svc)) {
                score += 40;
                reasons.add("Written for the incident's service");
            }
            Set<String> cats = Set.of(rs.getString("cats").split(","));
            if (category != null && cats.contains(category)) {
                score += 20;
                reasons.add("Category " + category);
            }
            double sim = TextSimilarity.score((String) inc.get("title"), (String) inc.get("description"), rs.getString("title"), rs.getString("description"));
            score += (int) Math.round(30 * sim);
            if (sim >= 0.1) {
                reasons.add("Text similarity " + Math.round(sim * 100) + "%");
            }
            long[] h = history.get(rs.getLong("id"));
            if (h != null && h[1] > 0) {
                score += (int) Math.round(10.0 * h[0] / h[1]);
                reasons.add(h[0] + " of " + h[1] + " finished runs on this service completed");
            }
            if (score >= 15) {
                list.add(new RunbookRecommendation(rs.getLong("id"), rs.getString("title"), Math.min(100, score), rs.getBoolean("requires_approval"),
                        (Integer) rs.getObject("estimated_minutes"), reasons));
            }
        });
        list.sort(Comparator.comparingInt(RunbookRecommendation::score).reversed().thenComparing(RunbookRecommendation::title));
        List<RunbookRecommendation> top = list.size() > 5 ? List.copyOf(list.subList(0, 5)) : list;
        String rationale = top.isEmpty() ? "No active runbook matches this incident's service, category or wording."
                : top.get(0).title() + " fits best (" + String.join(", ", top.get(0).reasons()).toLowerCase(Locale.ROOT) + ")."
                + (top.get(0).requiresApproval() ? " It needs an incident manager's approval before it runs." : "");
        return new RunbookRecommendations(incidentId, (String) inc.get("number"), top, rationale,
                "Deterministic score 0–100: same service 40, matching category 20, text similarity up to 30, completed share of earlier runs on the "
                        + "service up to 10; candidates scoring ≥ 15");
    }

    // ------------------------------------------------------------------ predictive SLA risk

    public AiResult<SlaRisk> slaRisk(Long incidentId, Actor actor) {
        SlaRisk base = predict(incidentId);
        return ai.runGrounded("sla_risk", base.number(), actor, () -> {
            String data = "<data>\nIncident " + base.number() + " (" + base.priority() + "): " + base.title() + "\nSLA policy: " + base.policyName()
                    + "\nElapsed: " + base.elapsedPercent() + "% of the resolution target, " + base.remainingMinutes() + " minutes remaining\n"
                    + base.probabilityLabel() + "\nFactors: " + String.join("; ", base.factors()) + "\n</data>";
            SlaRiskOut out = llm.generate("Recommend how to avoid an SLA breach. The breach probability is computed; do not change it.", data,
                    SlaRiskOut.class, o -> {
                        if (clean(o.recommendations).isEmpty()) {
                            throw invalid("No recommendations returned");
                        }
                    });
            return new SlaRisk(base.incidentId(), base.number(), base.title(), base.priority(), base.policyName(), base.clockStatus(), base.elapsedPercent(),
                    base.remainingMinutes(), base.resolutionDueAt(), base.probability(), base.riskLevel(), base.probabilityLabel(), base.sampleSize(),
                    base.historicalBreaches(), base.historyDays(), base.method(), base.factors(), clean(out.recommendations));
        }, () -> base);
    }

    /** Active incidents ranked by the computed breach probability (statistics only — served by analytics, not labelled as AI). */
    public SlaRiskOverview slaRiskOverview(Integer limit) {
        int n = limit == null ? 10 : Math.max(1, Math.min(limit, 50));
        List<Long> ids = jdbc.queryForList("select i.id from incidents i join incident_slas s on s.incident_id = i.id where i.parent_id is null and not i.simulated and i.status in "
                + ACTIVE + " and s.clock_status <> 'STOPPED' order by s.resolution_due_at nulls last limit 200", new MapSqlParameterSource(), Long.class);
        List<SlaRisk> risks = ids.stream().map(this::predict).filter(r -> r.probability() != null)
                .sorted(Comparator.comparing(SlaRisk::probability).reversed().thenComparing(SlaRisk::remainingMinutes)).limit(n).toList();
        return new SlaRiskOverview(clock.instant(), risks, METHOD);
    }

    static final String METHOD = "Empirical conditional breach rate: among completed resolution clocks of the same priority (last N days) that were "
            + "still running at this elapsed share, the share that breached; smoothed toward the elapsed share below 5 comparable clocks";

    /**
     * The Release 3 statistical SLA breach probability without any LLM involvement (no AI interaction is logged). Release 4 Predictive
     * Intelligence serves it as the labelled fallback while no SLA_BREACH model is ACTIVE.
     */
    public SlaRisk statisticalSlaRisk(Long incidentId) {
        return predict(incidentId);
    }

    SlaRisk predict(Long incidentId) {
        return tx.execute(s -> {
            Map<String, Object> inc = jdbc.queryForList("select number, title, priority from incidents where id = :id", new MapSqlParameterSource("id", incidentId))
                    .stream().findFirst().orElseThrow(() -> ApiException.notFound("Incident", incidentId));
            IncidentSla sla = slas.findByIncidentId(incidentId).orElseThrow(() -> ApiException.notFound("SLA for incident", incidentId));
            Instant now = clock.instant();
            SlaSnapshot snap = mapper.snapshot(sla, now);
            int days = Math.max(7, Math.min(settings.getInt("ai.sla_risk.history_days", 180), 730));
            double share = snap.percentElapsed() / 100.0;
            List<Object[]> history = jdbc.query("select s.consumed_seconds, s.resolution_target_minutes, s.outcome from incident_slas s join incidents i on i.id = s.incident_id "
                    + "where s.priority = :p and s.outcome in ('MET', 'MISSED') and s.completed_at >= :from and s.incident_id <> :id and not i.simulated", new MapSqlParameterSource("p", sla.getPriority().name())
                    .addValue("from", at(now.minus(Duration.ofDays(days)))).addValue("id", incidentId), (rs, n) -> new Object[]{
                    rs.getLong(1) / (rs.getInt(2) * 60.0), "MISSED".equals(rs.getString(3))});
            List<String> factors = new ArrayList<>();
            Double probability;
            int sample = 0;
            int breaches = 0;
            String label;
            String clock = sla.getClockStatus().name();
            if (sla.getOutcome() != null) {
                probability = sla.getOutcome() == IncidentSla.Outcome.MISSED ? 1.0 : sla.getOutcome() == IncidentSla.Outcome.MET ? 0.0 : null;
                label = "The resolution clock is complete (" + sla.getOutcome() + "); nothing to predict";
            } else if (share >= 1.0 || sla.getHealth() == IncidentSla.Health.BREACHED) {
                probability = 1.0;
                label = "Computed probability 1.00: the resolution target has already been exceeded";
                factors.add("Already breached");
            } else {
                for (Object[] h : history) {
                    if ((double) h[0] > share) {
                        sample++;
                        if ((boolean) h[1]) {
                            breaches++;
                        }
                    }
                }
                double prior = Math.max(0, Math.min(1, share));
                double p = sample >= MIN_SAMPLES ? (double) breaches / sample : (breaches + prior * 2) / (sample + 2.0);
                probability = Math.round(p * 100) / 100.0;
                label = "Computed probability " + String.format(Locale.ROOT, "%.2f", probability) + " from " + sample + " comparable completed "
                        + sla.getPriority() + " clock(s) (" + breaches + " breached) of the last " + days + " days" + (sample >= MIN_SAMPLES ? ""
                        : ", smoothed toward the elapsed share " + String.format(Locale.ROOT, "%.2f", prior)) + " — a statistic, not an AI estimate";
                factors.add(Math.round(snap.percentElapsed()) + "% of the resolution target elapsed");
                factors.add(history.size() + " completed " + sla.getPriority() + " clocks in the sample window, " + sample + " still running at this point");
                if (sla.getClockStatus() == IncidentSla.ClockStatus.PAUSED) {
                    factors.add("The clock is paused (pending)");
                }
            }
            String level = probability == null ? "N/A" : probability >= 0.7 ? "HIGH" : probability >= 0.4 ? "MEDIUM" : "LOW";
            List<String> rec = new ArrayList<>();
            if (probability != null && probability >= 0.4 && sla.getOutcome() == null) {
                rec.add("Escalate to the team lead now and confirm an owner is actively working the incident");
                rec.add("Check the linked runbooks and known errors for a proven workaround");
            }
            if (sla.getClockStatus() == IncidentSla.ClockStatus.PAUSED) {
                rec.add("Follow up on the pending reason so the clock does not resume close to the deadline");
            }
            if (rec.isEmpty() && sla.getOutcome() == null) {
                rec.add("No action needed beyond normal progress updates");
            }
            return new SlaRisk(incidentId, (String) inc.get("number"), (String) inc.get("title"), (String) inc.get("priority"), sla.getPolicyName(), clock,
                    Math.round(snap.percentElapsed() * 10) / 10.0, Math.max(0, snap.remainingSeconds() / 60), snap.resolutionDueAt(), probability, level,
                    label, sample, breaches, days, METHOD, factors, rec);
        });
    }

    // ------------------------------------------------------------------ trends

    public AiResult<TrendAnalysis> trends(Integer days, Actor actor) {
        TrendAnalysis base = analyseTrends(days == null ? 28 : days);
        return ai.runGrounded("trend_analysis", "Last " + base.days() + " days", actor, () -> {
            String data = "<data>\nCurrent window " + base.from() + " to " + base.to() + " vs previous " + base.previousFrom() + " to " + base.previousTo()
                    + "\n" + base.trends().stream().limit(25).map(t -> t.metric() + " / " + t.dimension() + ": " + t.previous() + " → " + t.current()
                    + (t.changePercent() == null ? "" : " (" + t.changePercent() + "%)") + (t.significant() ? " [significant]" : ""))
                    .collect(Collectors.joining("\n")) + "\n</data>";
            TrendOut out = llm.generate("Summarise operational trends for IT leadership. Cite the numbers from the data; do not invent any.", data,
                    TrendOut.class, o -> {
                        requireText(o.narrative, "narrative");
                        if (clean(o.highlights).isEmpty()) {
                            throw invalid("No highlights returned");
                        }
                    });
            return new TrendAnalysis(base.from(), base.to(), base.previousFrom(), base.previousTo(), base.days(), base.trends(), clean(out.highlights),
                    out.narrative.trim(), "LLM narrative over the computed period comparison");
        }, () -> base);
    }

    TrendAnalysis analyseTrends(int requestedDays) {
        int d = Math.max(7, Math.min(requestedDays, 90));
        Instant now = clock.instant();
        Instant from = now.minus(Duration.ofDays(d));
        Instant prevFrom = from.minus(Duration.ofDays(d));
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("now", at(now)).addValue("from", at(from)).addValue("prev", at(prevFrom));
        List<Trend> trends = new ArrayList<>();
        counts(trends, "Incidents", "All services", "select count(*) filter (where i.created_at >= :from) as c, count(*) filter (where i.created_at < :from) as p "
                + "from incidents i where i.parent_id is null and not i.simulated and i.created_at >= :prev and i.created_at < :now", p, null);
        counts(trends, "Incidents by service", null, "select coalesce(s.name, 'No service') as k, count(*) filter (where i.created_at >= :from) as c, "
                + "count(*) filter (where i.created_at < :from) as p from incidents i left join business_services s on s.id = i.service_id "
                + "where i.parent_id is null and not i.simulated and i.created_at >= :prev and i.created_at < :now group by 1", p, "k");
        counts(trends, "Incidents by category", null, "select i.category as k, count(*) filter (where i.created_at >= :from) as c, "
                + "count(*) filter (where i.created_at < :from) as p from incidents i where i.parent_id is null and not i.simulated and i.created_at >= :prev "
                + "and i.created_at < :now group by 1", p, "k");
        counts(trends, "Alerts by severity", null, "select a.severity as k, count(*) filter (where a.first_seen_at >= :from) as c, "
                + "count(*) filter (where a.first_seen_at < :from) as p from alerts a where not a.simulated and a.first_seen_at >= :prev and a.first_seen_at < :now group by 1", p, "k");
        counts(trends, "Changes failed or rolled back", "All changes", "select count(*) filter (where coalesce(c.closed_at, c.updated_at) >= :from) as c, "
                + "count(*) filter (where coalesce(c.closed_at, c.updated_at) < :from) as p from changes c where c.status in ('FAILED', 'ROLLED_BACK') "
                + "and coalesce(c.closed_at, c.updated_at) >= :prev and coalesce(c.closed_at, c.updated_at) < :now", p, null);
        Map<String, Object> mttr = jdbc.queryForMap("select avg(extract(epoch from (i.resolved_at - i.created_at)) / 60) filter (where i.resolved_at >= :from) as c, "
                + "count(*) filter (where i.resolved_at >= :from) as cn, avg(extract(epoch from (i.resolved_at - i.created_at)) / 60) "
                + "filter (where i.resolved_at < :from) as p, count(*) filter (where i.resolved_at < :from) as pn from incidents i where i.parent_id is null and not i.simulated "
                + "and i.resolved_at >= :prev and i.resolved_at < :now and coalesce(i.resolution_code, '') <> 'CANCELLED'", p);
        if (mttr.get("c") != null && mttr.get("p") != null) {
            double c = round1(((Number) mttr.get("c")).doubleValue());
            double pv = round1(((Number) mttr.get("p")).doubleValue());
            Double change = pv == 0 ? null : round1((c - pv) * 100 / pv);
            boolean sig = change != null && Math.abs(change) >= 25 && ((Number) mttr.get("cn")).longValue() >= MIN_SAMPLES
                    && ((Number) mttr.get("pn")).longValue() >= MIN_SAMPLES;
            trends.add(new Trend("Mean time to resolve (minutes)", "All incidents", c, pv, change, c > pv ? "UP" : c < pv ? "DOWN" : "FLAT", null, sig));
        }
        trends.sort(Comparator.comparing(Trend::significant).reversed().thenComparing(t -> -Math.abs(t.zScore() == null ? 0 : t.zScore())));
        List<String> highlights = trends.stream().filter(Trend::significant).limit(6).map(t -> t.metric() + (t.dimension() == null ? "" : " (" + t.dimension() + ")")
                + (t.direction().equals("UP") ? " rose" : " fell") + " from " + fmt(t.previous()) + " to " + fmt(t.current())
                + (t.changePercent() == null ? "" : " (" + (t.changePercent() > 0 ? "+" : "") + t.changePercent() + "%)")).toList();
        String narrative = highlights.isEmpty() ? "No statistically notable change between the last " + d + " days and the " + d + " days before."
                : highlights.size() + " notable change(s) compared with the previous " + d + " days; the largest: " + highlights.get(0) + ".";
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return new TrendAnalysis(today.minusDays(d).toString(), today.toString(), today.minusDays(2L * d).toString(), today.minusDays(d).toString(), d, trends,
                highlights, narrative, "Deterministic: counts per window; a change is significant when |z| = |current − previous| / √(current + previous) ≥ 2 "
                + "with at least 6 events (MTTR: ≥ 25 % with ≥ 5 resolutions in each window)");
    }

    private void counts(List<Trend> out, String metric, String fixedDimension, String sql, MapSqlParameterSource p, String keyColumn) {
        jdbc.query(sql, p, rs -> {
            long c = rs.getLong("c");
            long pv = rs.getLong("p");
            if (c + pv == 0) {
                return;
            }
            double z = Math.round((c - pv) / Math.sqrt(c + pv) * 100) / 100.0;
            Double change = pv == 0 ? null : round1((c - pv) * 100.0 / pv);
            out.add(new Trend(metric, keyColumn == null ? fixedDimension : rs.getString(keyColumn), c, pv, change, c > pv ? "UP" : c < pv ? "DOWN" : "FLAT",
                    z, Math.abs(z) >= 2 && c + pv >= 6));
        });
    }

    // ------------------------------------------------------------------ helpers

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String severity(int rank) {
        return switch (rank) {
            case 5 -> "CRITICAL";
            case 4 -> "MAJOR";
            case 3 -> "MINOR";
            case 2 -> "WARNING";
            default -> "INFO";
        };
    }

    private static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String join(String a, String b) {
        return (a == null ? "" : a) + " " + (b == null ? "" : b);
    }

    static void requireText(String v, String field) {
        if (v == null || v.isBlank()) {
            throw invalid("Empty " + field);
        }
    }

    static LlmException invalid(String message) {
        return new LlmException(LlmException.Reason.INVALID_OUTPUT, message);
    }

    static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().limit(10).toList();
    }

}
