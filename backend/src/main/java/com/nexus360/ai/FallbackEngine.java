package com.nexus360.ai;

import com.nexus360.ai.AiDtos.AskResponse;
import com.nexus360.ai.AiDtos.ClassificationSuggestion;
import com.nexus360.ai.AiDtos.DataPoint;
import com.nexus360.ai.AiDtos.Hypothesis;
import com.nexus360.ai.AiDtos.IncidentSummaryAi;
import com.nexus360.ai.AiDtos.Insight;
import com.nexus360.ai.AiDtos.OperationalInsights;
import com.nexus360.ai.AiDtos.RootCauseAnalysis;
import com.nexus360.analytics.AnalyticsDtos.Forecast;
import com.nexus360.analytics.AnalyticsDtos.Kpis;
import com.nexus360.analytics.AnalyticsService;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentClassifier;
import com.nexus360.incident.IncidentDtos.SlaSnapshot;
import com.nexus360.incident.Level;
import com.nexus360.incident.Priority;
import com.nexus360.incident.PriorityCalculator;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.sla.SlaAdminService;
import com.nexus360.sla.SlaDtos.SlaOverview;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deterministic engine used when no LLM is configured, the LLM is disabled, or a call fails. Every output is built
 * from rules and database statistics and is labelled "fallback" by {@link AiService} — it never claims to be AI.
 */
@Component
@RequiredArgsConstructor
public class FallbackEngine {

    private record Cause(Pattern pattern, String cause, List<String> checks) {}

    /** A failure pattern found in incident text: the cause, the first matching phrase, how many phrases matched and checks. */
    public record CauseMatch(String cause, String matched, int hits, List<String> checks) {}

    private static final List<Cause> CAUSES = List.of(
            new Cause(Pattern.compile("connection pool|too many connections|db timeout|database|deadlock|sql"),
                    "Database saturation or connection-pool exhaustion",
                    List.of("Check active vs. maximum pool connections on the application", "Look for long-running or blocked queries",
                            "Review recent schema or query changes")),
            new Cause(Pattern.compile("503|502|500|bad gateway|service unavailable|not loading|blank page|spinning|timeout|timed out"),
                    "Application tier or an upstream dependency is unavailable (HTTP 5xx / timeouts)",
                    List.of("Check application health endpoints and pod/instance status", "Inspect load balancer target health",
                            "Review error-rate dashboards for the affected service")),
            new Cause(Pattern.compile("deploy|release|update|upgrade|patch|change|since this morning|after the"),
                    "A recent change or deployment introduced a regression",
                    List.of("List changes deployed in the last 24 hours for this service", "Compare error onset time with deployment time",
                            "Prepare a rollback plan with the change owner")),
            new Cause(Pattern.compile("login|log in|sso|password|mfa|unauthori|access denied|locked out|token"),
                    "Identity provider or SSO integration failure",
                    List.of("Check identity provider status and certificate expiry", "Verify SSO configuration and recent IdP changes",
                            "Test sign-in with a known-good account")),
            new Cause(Pattern.compile("vpn|latency|slow|packet|dns|network|wifi|unreachable"),
                    "Network congestion, DNS or VPN gateway degradation",
                    List.of("Check VPN concentrator load and tunnel errors", "Run traceroute / DNS resolution from affected sites",
                            "Review bandwidth utilisation on WAN links")),
            new Cause(Pattern.compile("disk|storage|full|quota|space"),
                    "Storage capacity exhausted on a dependent server",
                    List.of("Check disk usage on application and database hosts", "Clear or rotate logs", "Extend volume if required")),
            new Cause(Pattern.compile("email|outlook|mailbox|smtp|exchange|sync"),
                    "Mail transport or synchronisation backlog",
                    List.of("Check mail queue length and transport service health", "Verify connector certificates",
                            "Test mailbox synchronisation for an affected user")));

    private static final Pattern LAST_N_DAYS = Pattern.compile("(?:last|past)\\s+(\\d{1,3})\\s+days?");

    private final IncidentClassifier classifier;
    private final com.nexus360.incident.IncidentCategoryService categoryConfig;
    private final BusinessServiceRepository services;
    private final AnalyticsService analytics;
    private final SlaAdminService sla;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    // ------------------------------------------------------------------ incident features

    public ClassificationSuggestion classify(Incident i) {
        List<BusinessService> catalogue = services.findAllByOrderByTierAscNameAsc();
        IncidentClassifier.Result r = classifier.classify(i.getTitle(), i.getDescription(), catalogue, categoryConfig.rules());
        BusinessService service = r.service() != null ? r.service() : i.getService();
        Level impact = PriorityCalculator.higher(i.getImpact(),
                PriorityCalculator.deriveImpact(i.getAffectedUsers(), service == null ? null : service.getTier(), 1));
        Level urgency = PriorityCalculator.deriveUrgency(i.getTitle() + " " + i.getDescription());
        return new ClassificationSuggestion(r.category(), service == null ? null : service.getId(), service == null ? null : service.getName(),
                impact, urgency, PriorityCalculator.calculate(impact, urgency), r.confidence(),
                "Rule-based: " + r.rationale() + "; priority from the impact × urgency matrix.");
    }

    public IncidentSummaryAi summarize(Incident i, SlaSnapshot snapshot, int children, int comments) {
        Instant now = clock.instant();
        String age = human(Duration.between(i.getCreatedAt(), now).toMinutes());
        String service = i.getService() == null ? "an unspecified service" : i.getService().getName();
        StringBuilder summary = new StringBuilder();
        summary.append(i.getNumber()).append(" is a ").append(i.getPriority().label()).append(' ').append(title(i.getPriority().name()))
                .append(' ').append(title(i.getCategory().name()).toLowerCase(Locale.ROOT)).append(" incident affecting ").append(service)
                .append(", opened ").append(age).append(" ago. ");
        if (children > 0) {
            summary.append(children).append(" duplicate report(s) are linked to it").append(i.isMajor() ? " and it is a declared major incident. " : ". ");
        }
        if (i.getAssignee() == null) {
            summary.append("It is ").append(title(i.getStatus().name()).toLowerCase(Locale.ROOT)).append(" and not yet assigned to an engineer");
        } else if (i.getStatus() == com.nexus360.incident.IncidentStatus.ASSIGNED) {
            summary.append("It is assigned to ").append(i.getAssignee().getFullName());
        } else {
            summary.append("It is ").append(title(i.getStatus().name()).toLowerCase(Locale.ROOT)).append(" with ").append(i.getAssignee().getFullName());
        }
        summary.append(i.getDepartment() == null ? "." : " (" + i.getDepartment().getName() + ").");
        if (snapshot != null) {
            summary.append(" SLA: ").append(title(snapshot.health().name()).toLowerCase(Locale.ROOT));
            if (snapshot.clockStatus().name().equals("RUNNING")) {
                long remaining = snapshot.remainingSeconds() / 60;
                summary.append(remaining >= 0 ? ", " + human(remaining) + " to resolution target." : ", " + human(-remaining) + " past the target.");
            } else {
                summary.append(" (clock ").append(snapshot.clockStatus().name().toLowerCase(Locale.ROOT)).append(").");
            }
        }
        List<String> facts = new ArrayList<>();
        facts.add("Impact " + i.getImpact() + " × urgency " + i.getUrgency() + " → priority " + i.getPriority().label());
        facts.add("Affected users: " + i.getAffectedUsers());
        facts.add("Escalation level: " + i.getEscalationLevel());
        facts.add(comments + " comment(s) and work note(s) recorded");
        if (i.getRootCause() != null) {
            facts.add("Recorded root cause: " + i.getRootCause());
        }
        List<String> next = new ArrayList<>();
        if (i.getAssignee() == null && i.getStatus().isActive()) {
            next.add("Assign an engineer from " + (i.getDepartment() == null ? "the owning team" : i.getDepartment().getName()));
        }
        if (snapshot != null && snapshot.health().name().equals("AT_RISK")) {
            next.add("SLA is at risk — prioritise this incident or escalate");
        }
        if (snapshot != null && snapshot.health().name().equals("BREACHED")) {
            next.add("SLA breached — inform the service owner and agree a recovery time");
        }
        if (snapshot != null && snapshot.respondedAt() == null && i.getStatus().isActive()) {
            next.add("Acknowledge the requester (first response not yet recorded)");
        }
        if (children > 0) {
            next.add("Post a public update; it reaches all " + (children + 1) + " affected reporters when the parent is resolved");
        }
        if (i.getStatus().isActive()) {
            next.add("Check the recommended knowledge articles and runbooks for this service");
        }
        return new IncidentSummaryAi(summary.toString().trim(), facts, next);
    }

    public RootCauseAnalysis rootCause(Incident i, int children) {
        String text = ((i.getTitle() == null ? "" : i.getTitle()) + " " + (i.getDescription() == null ? "" : i.getDescription())).toLowerCase(Locale.ROOT);
        List<Hypothesis> hypotheses = new ArrayList<>();
        // Historical: the most common recorded root cause for the same service in the last 90 days.
        if (i.getService() != null) {
            List<Map<String, Object>> rows = jdbc.queryForList("select root_cause, count(*) as c, string_agg(number, ', ' order by resolved_at desc) as refs "
                    + "from incidents where service_id = :sid and root_cause is not null and resolved_at >= :from and id <> :id "
                    + "group by root_cause order by c desc limit 1", new MapSqlParameterSource()
                    .addValue("sid", i.getService().getId()).addValue("id", i.getId())
                    .addValue("from", OffsetDateTime.ofInstant(clock.instant().minus(Duration.ofDays(90)), ZoneOffset.UTC)));
            if (!rows.isEmpty()) {
                Map<String, Object> r = rows.get(0);
                String refs = String.valueOf(r.get("refs"));
                hypotheses.add(new Hypothesis("Recurrence of a known issue: " + r.get("root_cause"),
                        ((Number) r.get("c")).longValue() >= 2 ? "HIGH" : "MEDIUM",
                        List.of("Recorded as the root cause of " + r.get("c") + " resolved " + i.getService().getName() + " incident(s): "
                                + (refs.length() > 120 ? refs.substring(0, 120) + "…" : refs)),
                        List.of("Compare symptoms with the earlier incidents", "Check whether the previous fix was permanent")));
            }
        }
        for (Cause c : CAUSES) {
            Matcher m = c.pattern().matcher(text);
            if (m.find()) {
                List<String> evidence = new ArrayList<>();
                evidence.add("Report mentions \"" + m.group() + "\"");
                if (children > 0) {
                    evidence.add((children + 1) + " users reported the same symptoms, which suggests a shared component rather than a single device");
                }
                hypotheses.add(new Hypothesis(c.cause(), hypotheses.isEmpty() && children > 0 ? "HIGH" : "MEDIUM", evidence, c.checks()));
            }
            if (hypotheses.size() >= 4) {
                break;
            }
        }
        if (hypotheses.isEmpty()) {
            hypotheses.add(new Hypothesis("Insufficient signal in the report to infer a cause", "LOW",
                    List.of("No known failure pattern matched the title or description"),
                    List.of("Collect error messages, screenshots and the time the problem started", "Check monitoring for the affected service")));
        }
        return new RootCauseAnalysis(hypotheses);
    }

    /** The first known failure pattern in the text (patterns are ordered by specificity), or null. */
    public static CauseMatch matchCause(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (Cause c : CAUSES) {
            Matcher m = c.pattern().matcher(t);
            if (m.find()) {
                String first = m.group();
                int hits = 1;
                while (m.find()) {
                    hits++;
                }
                return new CauseMatch(c.cause(), first, hits, c.checks());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ operational analytics

    /** HIGH with 20 or more supporting records, MEDIUM with 5 or more, LOW otherwise. */
    static String confidence(long supportingRecords) {
        return supportingRecords >= 20 ? "HIGH" : supportingRecords >= 5 ? "MEDIUM" : "LOW";
    }

    public OperationalInsights insights() {
        Instant now = clock.instant();
        List<Insight> insights = new ArrayList<>();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("w1", at(now.minus(Duration.ofDays(7)))).addValue("w2", at(now.minus(Duration.ofDays(14))));
        List<Map<String, Object>> top = jdbc.queryForList("select s.name, count(*) filter (where i.created_at >= :w1) as cur, "
                + "count(*) filter (where i.created_at >= :w2 and i.created_at < :w1) as prev from incidents i "
                + "join business_services s on s.id = i.service_id where i.created_at >= :w2 and not i.simulated group by s.name order by cur desc limit 1", p);
        if (!top.isEmpty() && ((Number) top.get(0).get("cur")).longValue() > 0) {
            long cur = ((Number) top.get(0).get("cur")).longValue();
            long prev = ((Number) top.get(0).get("prev")).longValue();
            String change = prev == 0 ? "new this week" : (cur >= prev ? "+" : "") + Math.round((cur - prev) * 100.0 / prev) + "% vs prior week";
            insights.add(new Insight(top.get(0).get("name") + " leads incident volume",
                    cur + " reports in the last 7 days (" + change + ").", cur > prev * 1.3 && prev > 0 ? Severity.WARNING : Severity.INFO,
                    String.valueOf(cur), confidence(cur + prev)));
            String service = String.valueOf(top.get(0).get("name"));
            List<Map<String, Object>> hours = jdbc.queryForList("select extract(hour from i.created_at at time zone :tz)::int as h, count(*) as c "
                    + "from incidents i join business_services s on s.id = i.service_id where s.name = :name and i.created_at >= :w2 and not i.simulated "
                    + "group by 1 order by c desc", p.addValue("tz", analyticsZone()).addValue("name", service));
            long total = hours.stream().mapToLong(h -> ((Number) h.get("c")).longValue()).sum();
            if (total >= 5) {
                int h = ((Number) hours.get(0).get("h")).intValue();
                long peak = hours.stream().filter(x -> {
                    int hh = ((Number) x.get("h")).intValue();
                    return hh == h || hh == (h + 1) % 24;
                }).mapToLong(x -> ((Number) x.get("c")).longValue()).sum();
                insights.add(new Insight(service + " incidents cluster around " + String.format("%02d:00–%02d:00", h, (h + 2) % 24),
                        Math.round(peak * 100.0 / total) + "% of " + service + " reports in the last 14 days arrived in this two-hour window.",
                        Severity.INFO, Math.round(peak * 100.0 / total) + "%", confidence(total)));
            }
        }
        SlaOverview overview = sla.overview(30);
        if (overview.breachedActiveCount() > 0 || overview.atRiskCount() > 0) {
            insights.add(new Insight("SLA exposure", overview.breachedActiveCount() + " open incident(s) have breached and "
                    + overview.atRiskCount() + " are at risk of breaching their resolution SLA.",
                    overview.breachedActiveCount() > 0 ? Severity.CRITICAL : Severity.WARNING,
                    String.valueOf(overview.breachedActiveCount() + overview.atRiskCount()), "HIGH")); // live clock state, not an estimate
        } else if (overview.compliancePercent() != null) {
            insights.add(new Insight("SLA compliance healthy", overview.compliancePercent() + "% of incidents completed in the last 30 days met their resolution SLA.",
                    Severity.SUCCESS, overview.compliancePercent() + "%", confidence(overview.metCount() + overview.missedCount())));
        }
        List<Map<String, Object>> recurring = jdbc.queryForList("select coalesce(s.name, 'General') as name, i.category, count(*) as c, "
                + "count(i.resolution_article_id) as with_kb from incidents i left join business_services s on s.id = i.service_id "
                + "where i.created_at >= :w2 and i.parent_id is null and not i.simulated group by 1, 2 having count(*) >= 3 order by c desc limit 1", p);
        if (!recurring.isEmpty() && ((Number) recurring.get(0).get("with_kb")).longValue() == 0) {
            Map<String, Object> r = recurring.get(0);
            insights.add(new Insight("Recurring " + title(String.valueOf(r.get("category"))).toLowerCase(Locale.ROOT) + " issues on " + r.get("name"),
                    r.get("c") + " incidents in 14 days, none resolved with a knowledge article — consider a problem record and a KB solution.",
                    Severity.WARNING, String.valueOf(r.get("c")), confidence(((Number) r.get("c")).longValue())));
        }
        Kpis kpis = analytics.kpis(7, now);
        if (kpis.deltas().mttr() != null) {
            double d = kpis.deltas().mttr();
            insights.add(new Insight(d <= 0 ? "Faster resolution" : "Resolution is slowing",
                    "Mean time to resolve is " + kpis.mttrMinutes() + " min over the last 7 days (" + (d > 0 ? "+" : "") + d + "% vs the previous week).",
                    d <= 0 ? Severity.SUCCESS : Severity.WARNING, (d > 0 ? "+" : "") + d + "%", confidence(kpis.resolvedInPeriod())));
        }
        Map<String, Object> grouped = jdbc.queryForMap("select count(distinct parent_id) as parents, count(*) as children from incidents "
                + "where parent_id is not null and created_at >= :w1 and not simulated", p);
        if (((Number) grouped.get("children")).longValue() > 0) {
            insights.add(new Insight("Duplicate detection at work", grouped.get("children") + " duplicate reports were grouped under "
                    + grouped.get("parents") + " parent incident(s) in the last 7 days.", Severity.INFO, String.valueOf(grouped.get("children")),
                    "HIGH"));
        }
        if (insights.isEmpty()) {
            insights.add(new Insight("Not enough data yet", "Insights appear once incidents have been recorded.", Severity.INFO, null, "LOW"));
        }
        return new OperationalInsights(insights.stream().limit(5).toList(), analytics.hourlyCreated(30));
    }

    /** Natural-language reporting by intent matching over a fixed set of deterministic queries. */
    public AskResponse ask(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        int days = days(q);
        Instant from = now.minus(Duration.ofDays(days));
        String window = days == 1 ? "the last 24 hours" : "the last " + days + " days";
        Optional<Priority> priority = priority(q);
        Optional<BusinessService> service = services.findAllByOrderByTierAscNameAsc().stream()
                .filter(s -> q.contains(s.getName().toLowerCase(Locale.ROOT)) || q.contains(s.getCode().toLowerCase(Locale.ROOT))
                        || s.keywordList().stream().anyMatch(k -> k.length() > 3 && q.contains(k)))
                .findFirst();

        if (q.contains("mttr") || q.contains("resolution time") || q.contains("time to resolve") || q.contains("how long")) {
            Kpis k = analytics.kpis(days, now);
            List<DataPoint> data = new ArrayList<>();
            analytics.overview(days).byPriority().forEach(r -> {
                if (r.mttrMinutes() != null) {
                    data.add(new DataPoint(r.priority().label() + " " + title(r.priority().name()) + " MTTR (min)", r.mttrMinutes()));
                }
            });
            return new AskResponse(k.mttrMinutes() == null ? "No incidents were resolved in " + window + "."
                    : "Mean time to resolve over " + window + " is " + k.mttrMinutes() + " minutes across " + k.resolvedInPeriod() + " resolved incidents.",
                    data, "Average of resolved_at − created_at for top-level incidents resolved in " + window + ".");
        }
        if (q.contains("sla") || q.contains("compliance") || q.contains("breach") || q.contains("at risk")) {
            SlaOverview o = sla.overview(days);
            List<DataPoint> data = new ArrayList<>();
            o.byPriority().forEach(r -> {
                if (r.compliance() != null) {
                    data.add(new DataPoint(r.priority().label() + " compliance %", r.compliance()));
                }
            });
            data.add(new DataPoint("At risk now", o.atRiskCount()));
            data.add(new DataPoint("Breached (open)", o.breachedActiveCount()));
            return new AskResponse("Resolution SLA compliance over " + window + " is "
                    + (o.compliancePercent() == null ? "not yet measurable (no completed SLAs)" : o.compliancePercent() + "% (" + o.metCount() + " met, " + o.missedCount() + " missed)")
                    + ". Right now " + o.atRiskCount() + " incident(s) are at risk and " + o.breachedActiveCount() + " open incident(s) have breached.",
                    data, "SLA clocks completed in " + window + " plus live clock health.");
        }
        if (q.contains("forecast") || q.contains("predict") || q.contains("tomorrow") || q.contains("next week") || q.contains("expect")) {
            Forecast f = analytics.forecast(28, 7);
            List<DataPoint> data = f.forecast().stream().map(p -> new DataPoint(p.date(), p.predicted())).toList();
            double week = f.forecast().stream().mapToDouble(p -> p.predicted()).sum();
            return new AskResponse("The statistical trend projects about " + Math.round(week) + " incidents over the next 7 days ("
                    + (f.forecast().isEmpty() ? 0 : f.forecast().get(0).predicted()) + " tomorrow).", data,
                    AnalyticsService.FORECAST_METHOD + " over the last 28 full days.");
        }
        if (q.contains("top") || q.contains("most") || q.contains("which service") || q.contains("worst")) {
            List<DataPoint> data = analytics.overview(days).byService().stream().limit(5)
                    .map(r -> new DataPoint(r.name(), r.created())).toList();
            return new AskResponse(data.isEmpty() ? "No incidents were recorded in " + window + "."
                    : data.get(0).label() + " had the most incidents in " + window + " (" + Math.round(data.get(0).value()) + ").",
                    data, "Top-level incidents created in " + window + ", grouped by business service.");
        }
        if (q.contains("categor")) {
            List<DataPoint> data = analytics.overview(days).byCategory().stream()
                    .map(r -> new DataPoint(title(r.category().name()), r.count())).toList();
            return new AskResponse(data.isEmpty() ? "No incidents were recorded in " + window + "."
                    : "The most common category in " + window + " is " + data.get(0).label() + ".", data,
                    "Top-level incidents created in " + window + ", grouped by category.");
        }
        if (q.contains("backlog") || q.contains("aging") || q.contains("oldest")) {
            List<DataPoint> data = analytics.overview(days).backlogAging().stream().map(b -> new DataPoint(b.bucket(), b.count())).toList();
            long total = data.stream().mapToLong(d -> (long) d.value()).sum();
            return new AskResponse("There are " + total + " open incidents in the backlog.", data, "Open top-level incidents by age.");
        }
        boolean openOnly = q.contains("open") || q.contains("active") || q.contains("unresolved") || q.contains("current");
        StringBuilder sql = new StringBuilder("select count(*) as total, count(*) filter (where resolved_at is not null) as resolved "
                + "from incidents where parent_id is null and not simulated");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (openOnly) {
            sql.append(" and status in ").append(com.nexus360.incident.IncidentStatus.activeSqlList());
        } else {
            sql.append(" and created_at >= :from");
            params.addValue("from", at(from));
        }
        priority.ifPresent(p -> {
            sql.append(" and priority = :priority");
            params.addValue("priority", p.name());
        });
        service.ifPresent(s -> {
            sql.append(" and service_id = :sid");
            params.addValue("sid", s.getId());
        });
        Map<String, Object> r = jdbc.queryForMap(sql.toString(), params);
        long total = ((Number) r.get("total")).longValue();
        long resolved = ((Number) r.get("resolved")).longValue();
        String scope = (priority.map(p -> p.label() + " " + title(p.name()).toLowerCase(Locale.ROOT) + " ").orElse(""))
                + "incident" + (total == 1 ? "" : "s") + service.map(s -> " on " + s.getName()).orElse("");
        boolean recognised = openOnly || q.contains("how many") || q.contains("count") || q.contains("number of") || priority.isPresent()
                || service.isPresent() || q.contains("incident");
        if (!recognised) {
            Kpis k = analytics.kpis(7, now);
            return new AskResponse("I can answer questions about incident counts, open incidents, MTTR, SLA compliance, top services, "
                    + "categories, backlog age and the 7-day forecast — for example \"How many critical incidents this week?\" or "
                    + "\"What is our SLA compliance?\". Current snapshot: " + k.openIncidents() + " open, " + k.criticalOpen() + " critical.",
                    List.of(new DataPoint("Open incidents", k.openIncidents()), new DataPoint("Critical open", k.criticalOpen())),
                    "Live KPI snapshot (the question did not match a supported report).");
        }
        String answer = openOnly ? (total == 1 ? "There is 1 open " : "There are " + total + " open ") + scope + " right now."
                : total + " " + scope + (total == 1 ? " was" : " were") + " reported in " + window + "; "
                + (total == 1 ? (resolved == 1 ? "it is resolved." : "it is not resolved yet.") : resolved + " of them are resolved.");
        return new AskResponse(answer, openOnly ? List.of(new DataPoint("Open", total))
                : List.of(new DataPoint("Reported", total), new DataPoint("Resolved", resolved)),
                "Count of top-level incidents" + (openOnly ? " currently open" : " created in " + window)
                        + priority.map(p -> ", priority " + p).orElse("") + service.map(s -> ", service " + s.getName()).orElse("") + ".");
    }

    // ------------------------------------------------------------------ helpers

    private String analyticsZone() {
        List<String> zones = jdbc.queryForList("select timezone from business_hours_calendars where is_default limit 1",
                new MapSqlParameterSource(), String.class);
        return zones.isEmpty() ? "UTC" : zones.get(0);
    }

    static int days(String q) {
        Matcher m = LAST_N_DAYS.matcher(q);
        if (m.find()) {
            return Math.max(1, Math.min(365, Integer.parseInt(m.group(1))));
        }
        if (q.contains("today") || q.contains("24 hours")) {
            return 1;
        }
        if (q.contains("week")) {
            return 7;
        }
        if (q.contains("quarter")) {
            return 90;
        }
        return 30;
    }

    static Optional<Priority> priority(String q) {
        Map<String, Priority> terms = new LinkedHashMap<>();
        terms.put("critical", Priority.CRITICAL);
        terms.put("p1", Priority.CRITICAL);
        terms.put("high", Priority.HIGH);
        terms.put("p2", Priority.HIGH);
        terms.put("medium", Priority.MEDIUM);
        terms.put("p3", Priority.MEDIUM);
        terms.put(" low", Priority.LOW);
        terms.put("p4", Priority.LOW);
        return terms.entrySet().stream().filter(e -> q.contains(e.getKey())).map(Map.Entry::getValue).findFirst();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String title(String value) {
        String lower = value.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    static String human(long minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        if (minutes < 48 * 60) {
            return (minutes / 60) + " h " + (minutes % 60) + " min";
        }
        return (minutes / 1440) + " days";
    }
}
