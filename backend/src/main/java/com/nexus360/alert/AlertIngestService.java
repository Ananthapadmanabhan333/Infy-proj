package com.nexus360.alert;

import com.nexus360.admin.SettingsService;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.cmdb.CmdbGraphService;
import com.nexus360.cmdb.ConfigurationItem;
import com.nexus360.cmdb.ConfigurationItemRepository;
import com.nexus360.common.ApiException;
import com.nexus360.events.DomainEvents.AlertEvent;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentDtos.CreateIncidentRequest;
import com.nexus360.incident.IncidentDtos.CreateIncidentResult;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentService;
import com.nexus360.incident.IncidentSource;
import com.nexus360.incident.Level;
import com.nexus360.integration.Integration;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.Actor;
import com.nexus360.security.Hashing;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRouting;
import com.nexus360.user.User;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The alert pipeline shared by every source (webhooks, the legacy inbound integration endpoint, manual alerts):
 * <ol>
 *   <li><b>Attach</b> — CI by name (hint, Prometheus {@code instance} host, source default) and business service by code, else the
 *       nearest business service that depends on the CI (CMDB traversal), else the source default.</li>
 *   <li><b>Deduplicate</b> — one active alert per scope (source / integration / manual) and fingerprint, serialised by an advisory
 *       lock; a repeat increments {@code occurrenceCount}; a resolved notification resolves it; an alert that fires again within
 *       {@code alert.grouping.window_minutes} after resolving is reopened.</li>
 *   <li><b>Suppress</b> — the first enabled suppression rule whose window is open and whose matcher matches.</li>
 *   <li><b>Route</b> — the first matching routing rule sets the team (and fills service/CI), else the service's default team or the
 *       CI's team; team members are notified ({@code ALERT_ROUTED}) from {@code alert.notify.min_severity}.</li>
 *   <li><b>Escalate</b> — the next escalation is planned by the {@link AlertEscalationPolicy}.</li>
 *   <li><b>Convert</b> — by routing rule ({@code createIncident}, from its minimum severity) or for legacy integrations, through the
 *       incident intake pipeline (classification, priority, duplicate detection, assignment, SLA); alerts of the same group reuse the
 *       group's open incident.</li>
 *   <li><b>Workflows</b> — {@code ALERT_RECEIVED} is published for every new or reopened, unsuppressed alert.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AlertIngestService {

    static final int ADVISORY_LOCK_CLASS = 360_009;

    /** Where an alert comes from and how its source wants it handled. */
    public record Origin(Alert.SourceKind kind, AlertSource source, Integration integration, BusinessService defaultService,
                         ConfigurationItem defaultCi, AlertSeverity defaultSeverity, Actor actor, boolean demo, boolean convertToIncident,
                         Level impact, Level urgency, boolean simulated) {

        /** Release 3 signature: not simulated. */
        public Origin(Alert.SourceKind kind, AlertSource source, Integration integration, BusinessService defaultService,
                      ConfigurationItem defaultCi, AlertSeverity defaultSeverity, Actor actor, boolean demo, boolean convertToIncident,
                      Level impact, Level urgency) {
            this(kind, source, integration, defaultService, defaultCi, defaultSeverity, actor, demo, convertToIncident, impact, urgency, false);
        }

        /** Deduplication scope: source, integration, SA Observability (simulated alerts deduplicate separately) or manual. */
        String scopeKey() {
            if (source != null) {
                return "S" + source.getId();
            }
            if (integration != null) {
                return "I" + integration.getId();
            }
            if (kind == Alert.SourceKind.OBSERVABILITY) {
                return simulated ? "OBS-SIM" : "OBS";
            }
            return "MANUAL";
        }
    }

    /** {@code action}: CREATED, REOPENED, UPDATED (repeat), RESOLVED, IGNORED (resolved notification for an unknown alert). */
    public record Outcome(Alert alert, String action, boolean activated, CreateIncidentResult conversion) {}

    private final AlertRepository alerts;
    private final AlertSuppressionRuleRepository suppressionRules;
    private final AlertRoutingRuleRepository routingRules;
    private final BusinessServiceRepository services;
    private final ConfigurationItemRepository cis;
    private final CmdbGraphService cmdbGraph;
    private final IncidentService incidentService;
    private final IncidentRepository incidents;
    private final TeamRouting teamRouting;
    private final AlertEscalationService escalation;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final AlertHistory history;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final java.time.Clock clock;

    @Transactional
    public Outcome ingest(Origin origin, AlertSignal s) {
        Instant now = clock.instant();
        String scope = origin.scopeKey();
        String fingerprint = s.fingerprint() == null || s.fingerprint().isBlank() ? Hashing.sha256Hex(s.title().toLowerCase(Locale.ROOT))
                : truncate(s.fingerprint().trim(), 128);
        jdbc.query("select pg_advisory_xact_lock(?, hashtext(?))", (org.springframework.jdbc.core.RowCallbackHandler) rs -> { }, ADVISORY_LOCK_CLASS,
                scope + "|" + fingerprint);
        Alert a = alerts.findActive(scope, fingerprint, Alert.Status.ACTIVE).orElse(null);
        if (a != null) {
            return repeat(origin, a, s, now);
        }
        List<Alert> resolved = alerts.findRecentlyResolved(scope, fingerprint, now.minus(Duration.ofMinutes(Math.max(0,
                settings.getInt("alert.grouping.window_minutes", 30)))));
        if (!s.firing()) {
            if (!resolved.isEmpty()) {
                Alert last = resolved.get(0);
                last.setOccurrenceCount(last.getOccurrenceCount() + 1);
                last.setLastSeenAt(now);
                last.setFiring(false);
                if (s.endsAt() != null) {
                    last.setEndsAt(s.endsAt());
                }
                return new Outcome(last, "UPDATED", false, null);
            }
            return new Outcome(null, "IGNORED", false, null);
        }
        boolean reopened = !resolved.isEmpty();
        if (reopened) {
            a = resolved.get(0);
            a.setStatus(Alert.Status.OPEN);
            a.setOccurrenceCount(a.getOccurrenceCount() + 1);
            a.setResolvedAt(null);
            a.setResolvedBy(null);
            a.setResolutionNote(null);
            a.setAcknowledgedAt(null);
            a.setAcknowledgedBy(null);
            a.setEscalationLevel(0);
            a.setLastEscalatedAt(null);
            a.setEndsAt(null);
        } else {
            a = new Alert();
            a.setNumber("ALR-" + alerts.nextNumber());
            a.setSourceKind(origin.kind());
            a.setSource(origin.source());
            a.setIntegration(origin.integration());
            a.setScopeKey(scope);
            a.setFingerprint(fingerprint);
            a.setFirstSeenAt(now);
            a.setDemo(origin.demo());
            a.setSimulated(origin.simulated());
            a.setCreatedAt(now);
        }
        a.setFiring(true);
        a.setLastSeenAt(now);
        applySignal(origin, a, s, true);
        a.setStartsAt(s.startsAt() == null ? (a.getStartsAt() == null ? now : a.getStartsAt()) : s.startsAt());
        alerts.saveAndFlush(a);
        history.record(a.getId(), reopened ? "REOPENED" : "CREATED", origin.actor(), reopened ? "Fired again within the grouping window"
                : "Received from " + sourceLabel(origin), Map.of("severity", a.getSeverity().name(), "fingerprint", truncate(fingerprint, 64)));
        CreateIncidentResult conversion = activate(origin, a, now, true);
        return new Outcome(a, reopened ? "REOPENED" : "CREATED", true, conversion);
    }

    private Outcome repeat(Origin origin, Alert a, AlertSignal s, Instant now) {
        a.setOccurrenceCount(a.getOccurrenceCount() + 1);
        a.setLastSeenAt(now);
        applySignal(origin, a, s, false);
        if (!s.firing()) {
            a.setFiring(false);
            a.setStatus(Alert.Status.RESOLVED);
            a.setResolvedAt(now);
            a.setResolvedBy(null);
            a.setResolutionNote("Resolved by the source");
            a.setEndsAt(s.endsAt() == null ? now : s.endsAt());
            a.setNextEscalationAt(null);
            alerts.saveAndFlush(a);
            history.record(a.getId(), "AUTO_RESOLVED", origin.actor(), "The source reports the alert as resolved", null);
            if (a.getIncident() != null) {
                incidentService.addCommentInternal(a.getIncident(), Actor.SYSTEM, "Alert " + a.getNumber() + " (" + truncate(a.getTitle(), 150)
                        + ") was resolved by " + sourceLabel(origin) + ".", true);
            }
            return new Outcome(a, "RESOLVED", false, null);
        }
        a.setFiring(true);
        boolean activated = false;
        CreateIncidentResult conversion = null;
        if (a.getStatus() == Alert.Status.SUPPRESSED && a.getSuppressionRule() != null) {
            AlertSuppressionRule rule = a.getSuppressionRule();
            if (!rule.isEnabled() || !rule.windowActive(now, zone()) || !rule.getMatchCriteria().matches(a)) {
                a.setStatus(Alert.Status.OPEN);
                a.setSuppressionRule(null);
                a.setSuppressedAt(null);
                alerts.saveAndFlush(a);
                history.record(a.getId(), "UNSUPPRESSED", origin.actor(), "Fired again after the suppression rule " + rule.getName() + " stopped applying",
                        null);
                conversion = activate(origin, a, now, false);
                activated = true;
            }
        }
        alerts.saveAndFlush(a);
        if (!activated) {
            history.record(a.getId(), "REPEATED", origin.actor(), null, Map.of("count", a.getOccurrenceCount()));
        }
        return new Outcome(a, "UPDATED", activated, conversion);
    }

    /** Suppression, routing, notification, escalation planning, conversion and the workflow event for a (re)activated alert. */
    private CreateIncidentResult activate(Origin origin, Alert a, Instant now, boolean checkSuppression) {
        if (checkSuppression) {
            ZoneId zone = zone();
            for (AlertSuppressionRule rule : suppressionRules.findByEnabledTrueOrderByIdAsc()) {
                if (rule.windowActive(now, zone) && rule.getMatchCriteria().matches(a)) {
                    a.setStatus(Alert.Status.SUPPRESSED);
                    a.setSuppressionRule(rule);
                    a.setSuppressedAt(now);
                    a.setNextEscalationAt(null);
                    rule.setMatchCount(rule.getMatchCount() + 1);
                    rule.setLastMatchedAt(now);
                    alerts.saveAndFlush(a);
                    history.record(a.getId(), "SUPPRESSED", origin.actor(), "Suppressed by rule " + rule.getName(), Map.of("ruleId", rule.getId()));
                    return null;
                }
            }
        }
        AlertRoutingRule rule = route(a, now);
        Team team = a.getTeam();
        history.record(a.getId(), "ROUTED", origin.actor(), team == null ? "No team found for this alert"
                : "Routed to " + team.getName() + (rule == null ? " (default team of the service)" : " by rule " + rule.getName()),
                rule == null ? null : Map.of("ruleId", rule.getId()));
        escalation.schedule(a, now);
        alerts.saveAndFlush(a);
        AlertSeverity notifyFrom = AlertSeverity.parse(settings.getString("alert.notify.min_severity", "MAJOR"));
        if (team != null && a.getSeverity().atLeast(notifyFrom == null ? AlertSeverity.MAJOR : notifyFrom)) {
            List<Long> members = teamRouting.activeMembers(team).stream().map(User::getId).toList();
            notifications.notifyUsers(members, "ALERT_ROUTED", a.getSeverity() + " alert for " + team.getName() + ": " + a.getNumber(),
                    truncate(a.getTitle(), 300) + (a.getService() == null ? "" : " (" + a.getService().getName() + ")"), "/alerts/" + a.getId(),
                    a.getSeverity() == AlertSeverity.CRITICAL ? Severity.CRITICAL : Severity.WARNING);
        }
        CreateIncidentResult conversion = null;
        boolean byRule = rule != null && rule.isCreateIncident() && a.getSeverity().atLeast(rule.getIncidentMinSeverity());
        if (a.getIncident() == null && (byRule || origin.convertToIncident())) {
            conversion = convertOrJoinGroup(a, null, origin.impact(), origin.urgency(), Actor.SYSTEM, byRule ? "routing rule " + rule.getName()
                    : sourceLabel(origin));
        }
        events.publishEvent(new AlertEvent(a.getId(), "RECEIVED", origin.actor() == null ? 0 : origin.actor().depth()));
        return conversion;
    }

    /** Result of the legacy inbound integration endpoint: the alert and the incident it created or belongs to. */
    public record IntegrationOutcome(Alert alert, String incidentNumber, boolean duplicate, String parentNumber) {}

    /**
     * Legacy inbound monitoring integration ({@code POST /api/integrations/inbound/{id}}): the alert goes through this pipeline and,
     * unless it is suppressed, becomes an incident as before (same title, description, impact and urgency mapping). A repeat of an
     * alert that already has an incident is deduplicated and answered with that incident ({@code duplicate: true}).
     */
    @Transactional
    public IntegrationOutcome ingestIntegration(Integration integration, String title, String description, String severityText, String serviceCode,
                                                String sourceText) {
        String severity = severityText == null || severityText.isBlank() ? "warning" : severityText.trim().toLowerCase(Locale.ROOT);
        Level impact = switch (severity) {
            case "critical", "major", "error" -> Level.HIGH;
            case "warning" -> Level.MEDIUM;
            default -> Level.LOW;
        };
        Level urgency = switch (severity) {
            case "critical" -> Level.HIGH;
            case "major", "error", "warning" -> Level.MEDIUM;
            default -> Level.LOW;
        };
        BusinessService service = serviceCode == null || serviceCode.isBlank() ? integration.getDefaultService()
                : services.findByCodeIgnoreCase(serviceCode.trim()).orElse(integration.getDefaultService());
        String text = (description == null ? "" : description) + "\n\nReceived from " + integration.getName()
                + (sourceText == null ? "" : " (" + sourceText + ")") + ", severity " + severity + ".";
        AlertSeverity parsed = AlertSeverity.parse(severity);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("severity", severity);
        if (sourceText != null && !sourceText.isBlank()) {
            labels.put("source", truncate(sourceText.trim(), 80));
        }
        if (service != null) {
            labels.put("service", service.getCode());
        }
        String fingerprint = Hashing.sha256Hex(title.trim().toLowerCase(Locale.ROOT) + "|" + (service == null ? "" : service.getCode()) + "|"
                + (sourceText == null ? "" : sourceText.trim().toLowerCase(Locale.ROOT)));
        Actor actor = new Actor(null, "integration:" + integration.getId(), integration.getName(), com.nexus360.user.Role.SYSTEM_ADMIN, 0);
        Outcome o = ingest(new Origin(Alert.SourceKind.INTEGRATION, null, integration, service, null, parsed == null ? AlertSeverity.MINOR : parsed, actor,
                integration.isDemo(), true, impact, urgency), new AlertSignal(fingerprint, null, title.trim(), text.trim(), parsed == null ? AlertSeverity.MINOR
                : parsed, true, labels, new LinkedHashMap<>(), null, service == null ? null : service.getCode(), null, null, null));
        Alert a = o.alert();
        if (o.conversion() != null) {
            return new IntegrationOutcome(a, o.conversion().incident().number(), o.conversion().duplicate() != null && o.conversion().duplicate().detected(),
                    o.conversion().duplicate() == null ? null : o.conversion().duplicate().parentNumber());
        }
        if (a != null && a.getIncident() != null) {
            Incident i = a.getIncident();
            return new IntegrationOutcome(a, i.getNumber(), true, i.getParent() == null ? i.getNumber() : i.getParent().getNumber());
        }
        return new IntegrationOutcome(a, null, false, null);
    }

    /** Applies the first matching routing rule (or the default team); returns the rule. */
    AlertRoutingRule route(Alert a, Instant now) {
        for (AlertRoutingRule rule : routingRules.findByEnabledTrueOrderBySortOrderAscIdAsc()) {
            if (rule.getMatchCriteria().matches(a)) {
                if (rule.getService() != null && a.getService() == null) {
                    a.setService(rule.getService());
                }
                if (rule.getCi() != null && a.getCi() == null) {
                    a.setCi(rule.getCi());
                }
                a.setTeam(rule.getTeam() != null && rule.getTeam().isActive() ? rule.getTeam() : defaultTeam(a));
                a.setRoutingRule(rule);
                rule.setMatchCount(rule.getMatchCount() + 1);
                rule.setLastMatchedAt(now);
                return rule;
            }
        }
        a.setRoutingRule(null);
        a.setTeam(defaultTeam(a));
        return null;
    }

    private Team defaultTeam(Alert a) {
        BusinessService s = a.getService();
        if (s != null) {
            if (s.getDefaultTeam() != null && s.getDefaultTeam().isActive()) {
                return s.getDefaultTeam();
            }
            Team t = teamRouting.defaultTeam(s.getDepartment()).orElse(null);
            if (t != null) {
                return t;
            }
        }
        if (a.getCi() != null && a.getCi().getTeam() != null && a.getCi().getTeam().isActive()) {
            return a.getCi().getTeam();
        }
        return a.getTeam();
    }

    /**
     * Converts the alert into an incident through the intake pipeline, unless another alert of its group already has an active incident
     * (then the alert joins it). Returns the intake result ({@code null} when joined).
     */
    CreateIncidentResult convertOrJoinGroup(Alert a, String title, Level impact, Level urgency, Actor actor, String reason) {
        if (a.getGroupKey() != null) {
            List<Long> groupIncidents = jdbc.queryForList("select a.incident_id from alerts a join incidents i on i.id = a.incident_id where a.group_key = ? "
                    + "and a.id <> ? and i.status in " + com.nexus360.incident.IncidentStatus.activeSqlList() + " order by a.first_seen_at limit 1", Long.class,
                    a.getGroupKey(), a.getId());
            if (!groupIncidents.isEmpty()) {
                Incident incident = incidents.findById(groupIncidents.get(0)).orElseThrow();
                a.setIncident(incident);
                alerts.saveAndFlush(a);
                history.record(a.getId(), "LINKED_INCIDENT", actor, "Joined " + incident.getNumber() + ", the incident of its alert group", null);
                return null;
            }
        }
        return convert(a, title, null, impact, urgency, actor, reason);
    }

    /** Creates the incident for an alert (intake pipeline incl. duplicate detection) and links the alert and its unlinked group siblings. */
    CreateIncidentResult convert(Alert a, String title, String description, Level impact, Level urgency, Actor actor, String reason) {
        if (a.getIncident() != null) {
            throw ApiException.conflict(a.getNumber() + " is already linked to " + a.getIncident().getNumber());
        }
        String incidentTitle = title != null && !title.isBlank() ? title.trim() : incidentTitle(a);
        String incidentDescription = description != null && !description.isBlank() ? description.trim() : incidentDescription(a);
        CreateIncidentResult result = incidentService.create(new CreateIncidentRequest(incidentTitle, incidentDescription,
                a.getService() == null ? null : a.getService().getId(), null, impact == null ? a.getSeverity().impact() : impact,
                urgency == null ? a.getSeverity().urgency() : urgency, 1, null, IncidentSource.MONITORING), actor);
        Incident incident = incidents.findById(result.incident().id()).orElseThrow();
        if (a.isSimulated() && !incident.isSimulated()) {
            // Release 4: an incident raised from a simulated alert is simulated too (labelled, excluded from analytics, purged).
            incident.setSimulated(true);
            incidents.save(incident);
        }
        a.setIncident(incident);
        alerts.saveAndFlush(a);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("incident", incident.getNumber());
        details.put("duplicate", result.duplicate() != null && result.duplicate().detected());
        if (result.duplicate() != null && result.duplicate().parentNumber() != null) {
            details.put("parent", result.duplicate().parentNumber());
        }
        history.record(a.getId(), "CONVERTED", actor, "Incident " + incident.getNumber() + " created (" + reason + ")", details);
        if (a.getGroupKey() != null) {
            for (Alert sibling : alerts.findGroupSiblings(a.getGroupKey(), a.getId(), org.springframework.data.domain.PageRequest.of(0, 100))) {
                if (sibling.getIncident() == null && sibling.getStatus().active()) {
                    sibling.setIncident(incident);
                    alerts.save(sibling);
                    history.record(sibling.getId(), "LINKED_INCIDENT", actor, "Linked to " + incident.getNumber() + " with its alert group ("
                            + a.getNumber() + ")", null);
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private void applySignal(Origin origin, Alert a, AlertSignal s, boolean fresh) {
        a.setTitle(truncate(s.title(), 300));
        if (s.description() != null) {
            a.setDescription(truncate(s.description(), 10_000));
        }
        AlertSeverity severity = s.severity() != null ? s.severity() : origin.defaultSeverity() != null ? origin.defaultSeverity() : AlertSeverity.WARNING;
        a.setSeverity(severity);
        if (s.labels() != null && (fresh || !s.labels().isEmpty())) {
            a.setLabels(new LinkedHashMap<>(s.labels()));
        }
        if (s.annotations() != null && (fresh || !s.annotations().isEmpty())) {
            a.setAnnotations(new LinkedHashMap<>(s.annotations()));
        }
        if (s.externalUrl() != null) {
            a.setExternalUrl(s.externalUrl());
        }
        if (s.groupKey() != null) {
            a.setGroupKey(truncate(s.groupKey(), 300));
        }
        if (fresh || a.getCi() == null) {
            ConfigurationItem ci = s.ciName() == null ? null : cis.findByNameIgnoreCase(s.ciName().trim()).orElse(null);
            a.setCi(ci != null ? ci : a.getCi() != null ? a.getCi() : origin.defaultCi());
        }
        if (fresh || a.getService() == null) {
            BusinessService service = s.serviceCode() == null ? null : services.findByCodeIgnoreCase(s.serviceCode().trim()).orElse(null);
            if (service == null && a.getCi() != null) {
                service = cmdbGraph.nearestServiceId(a.getCi().getId()).flatMap(services::findById).orElse(null);
            }
            a.setService(service != null ? service : a.getService() != null ? a.getService() : origin.defaultService());
        }
    }

    static String incidentTitle(Alert a) {
        String t = "[" + a.getSeverity() + "] " + a.getTitle();
        t = t.length() > 200 ? t.substring(0, 197) + "..." : t;
        return t.length() < 5 ? t + " alert" : t;
    }

    static String incidentDescription(Alert a) {
        StringBuilder sb = new StringBuilder();
        if (a.getDescription() != null && !a.getDescription().isBlank()) {
            sb.append(a.getDescription().trim()).append("\n\n");
        }
        sb.append("Created from alert ").append(a.getNumber()).append(" (").append(a.getSourceKind()).append(", severity ").append(a.getSeverity())
                .append(", ").append(a.getOccurrenceCount()).append(a.getOccurrenceCount() == 1 ? " occurrence" : " occurrences").append(").");
        if (a.getCi() != null) {
            sb.append("\nConfiguration item: ").append(a.getCi().getName());
        }
        if (a.getLabels() != null && !a.getLabels().isEmpty()) {
            sb.append("\nLabels: ");
            sb.append(String.join(", ", a.getLabels().entrySet().stream().limit(15).map(e -> e.getKey() + "=" + e.getValue()).toList()));
        }
        if (a.getExternalUrl() != null) {
            sb.append("\nSource: ").append(a.getExternalUrl());
        }
        String out = sb.toString();
        return out.length() > 5000 ? out.substring(0, 5000) : out;
    }

    private static String sourceLabel(Origin o) {
        if (o.source() != null) {
            return o.source().getName() + " (" + o.source().getProvider() + ")";
        }
        if (o.integration() != null) {
            return o.integration().getName();
        }
        return o.actor() == null || o.actor().isSystem() ? "SA" : o.actor().name();
    }

    ZoneId zone() {
        try {
            return ZoneId.of(settings.getString("org.timezone", "UTC"));
        } catch (DateTimeException e) {
            return ZoneId.of("UTC");
        }
    }

    private static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
