package com.nexus360.alert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.admin.SettingsService;
import com.nexus360.alert.AlertDtos.AlertCounts;
import com.nexus360.alert.AlertDtos.AlertDetail;
import com.nexus360.alert.AlertDtos.AlertGroup;
import com.nexus360.alert.AlertDtos.AlertSummary;
import com.nexus360.alert.AlertDtos.AssignTeamRequest;
import com.nexus360.alert.AlertDtos.BulkRequest;
import com.nexus360.alert.AlertDtos.BulkResult;
import com.nexus360.alert.AlertDtos.ConvertRequest;
import com.nexus360.alert.AlertDtos.ConvertResult;
import com.nexus360.alert.AlertDtos.CreateAlertRequest;
import com.nexus360.alert.AlertDtos.EventDto;
import com.nexus360.alert.AlertDtos.LinkIncidentRequest;
import com.nexus360.alert.AlertDtos.SuppressRequest;
import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.cmdb.ConfigurationItem;
import com.nexus360.cmdb.ConfigurationItemRepository;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentDtos.CreateIncidentResult;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentService;
import com.nexus360.incident.Level;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.Actor;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRouting;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The alert inbox (filters, counts, groups, detail, history) and the operator actions: acknowledge, resolve, suppress / unsuppress,
 * assign to a team, convert to an incident (intake pipeline incl. duplicate detection) or link to an existing incident, bulk
 * acknowledge/resolve/suppress, manual alerts. Every action is recorded in the alert history and audited. Workflow actions on alerts
 * ({@code ALERT_RECEIVED}) go through {@link #workflowCreateIncident}, {@link #workflowAssignTeam} and {@link #workflowComment}.
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    private static final Set<String> SORTS = Set.of("lastSeenAt", "firstSeenAt", "severity", "status", "number", "occurrenceCount");

    public record Filter(String search, List<Alert.Status> statuses, List<AlertSeverity> severities, List<Alert.SourceKind> sources, Long sourceId,
                         Long serviceId, Long ciId, Long teamId, Long incidentId, String groupKey, Boolean firing, Boolean hasIncident, String scope,
                         Instant from, Instant to) {}

    private final AlertRepository alerts;
    private final AlertIngestService ingest;
    private final AlertEscalationService escalation;
    private final AlertHistory history;
    private final BusinessServiceRepository services;
    private final ConfigurationItemRepository cis;
    private final IncidentRepository incidents;
    private final IncidentService incidentService;
    private final IncidentMapper incidentMapper;
    private final TeamRouting teamRouting;
    private final UserRepository users;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final AuditService audit;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public PageResponse<AlertSummary> list(Filter f, Actor actor, Integer page, Integer size, String sort) {
        String field = sort == null || sort.isBlank() ? "lastSeenAt" : sort.split(",")[0].trim();
        if (!SORTS.contains(field)) {
            throw ApiException.badRequest("Cannot sort by '" + field + "'");
        }
        boolean asc = sort != null && sort.split(",").length > 1 && "asc".equalsIgnoreCase(sort.split(",")[1].trim());
        Specification<Alert> spec = spec(f, actor);
        Sort order;
        if (field.equals("severity")) {
            spec = spec.and(severityOrder(asc));
            order = Sort.unsorted();
        } else {
            order = Sort.by(asc ? Sort.Direction.ASC : Sort.Direction.DESC, field).and(Sort.by(Sort.Direction.DESC, "id"));
        }
        Page<Alert> result = alerts.findAll(spec, PageResponse.pageable(page, size, order));
        return PageResponse.of(result, withCorrelationGroups(result.getContent().stream().map(AlertService::summary).toList()));
    }

    @Transactional(readOnly = true)
    public AlertCounts counts(String scope, Actor actor) {
        String where = scopeSql(scope, actor);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.query("select status, count(*) from alerts where true" + where + " group by status", rs -> {
            byStatus.put(rs.getString(1), rs.getLong(2));
        });
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (AlertSeverity s : AlertSeverity.values()) {
            bySeverity.put(s.name(), 0L);
        }
        jdbc.query("select severity, count(*) from alerts where status in ('OPEN', 'ACKNOWLEDGED')" + where + " group by severity", rs -> {
            bySeverity.put(rs.getString(1), rs.getLong(2));
        });
        Long resolved = jdbc.queryForObject("select count(*) from alerts where status = 'RESOLVED' and resolved_at >= ?" + where, Long.class,
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofHours(24))));
        Long critical = jdbc.queryForObject("select count(*) from alerts where status = 'OPEN' and severity = 'CRITICAL'" + where, Long.class);
        Long escalated = jdbc.queryForObject("select count(*) from alerts where status = 'OPEN' and escalation_level > 0" + where, Long.class);
        Long withIncident = jdbc.queryForObject("select count(*) from alerts where status in ('OPEN', 'ACKNOWLEDGED') and incident_id is not null" + where,
                Long.class);
        long open = byStatus.getOrDefault("OPEN", 0L);
        long ack = byStatus.getOrDefault("ACKNOWLEDGED", 0L);
        long suppressed = byStatus.getOrDefault("SUPPRESSED", 0L);
        return new AlertCounts(open, ack, suppressed, open + ack, resolved == null ? 0 : resolved, bySeverity, critical == null ? 0 : critical,
                escalated == null ? 0 : escalated, withIncident == null ? 0 : withIncident);
    }

    /** Alert groups (same {@code groupKey}) with at least one alert in {@code statuses} (default: active), most severe first. */
    @Transactional(readOnly = true)
    public List<AlertGroup> groups(List<Alert.Status> statuses, String scope, Actor actor, Integer limit) {
        List<Alert.Status> wanted = statuses == null || statuses.isEmpty() ? List.copyOf(Alert.Status.ACTIVE) : statuses;
        String in = String.join(",", wanted.stream().map(s -> "'" + s.name() + "'").toList());
        int max = limit == null ? 50 : Math.max(1, Math.min(limit, 100));
        List<String> keys = jdbc.queryForList("select group_key from alerts where group_key is not null and status in (" + in + ")" + scopeSql(scope, actor)
                + " group by group_key order by max(case severity when 'CRITICAL' then 5 when 'MAJOR' then 4 when 'MINOR' then 3 when 'WARNING' then 2 "
                + "else 1 end) desc, max(last_seen_at) desc limit ?", String.class, max);
        List<AlertGroup> out = new ArrayList<>();
        for (String key : keys) {
            List<Alert> members = alerts.findAll((root, q, cb) -> cb.equal(root.get("groupKey"), key));
            Map<String, Long> byStatus = new LinkedHashMap<>();
            members.forEach(m -> byStatus.merge(m.getStatus().name(), 1L, Long::sum));
            AlertSeverity max2 = members.stream().map(Alert::getSeverity).max((x, y) -> Integer.compare(x.rank(), y.rank())).orElse(AlertSeverity.INFO);
            Alert first = members.stream().min((x, y) -> x.getFirstSeenAt().compareTo(y.getFirstSeenAt())).orElseThrow();
            Incident incident = members.stream().map(Alert::getIncident).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            String title = first.getLabels() != null && first.getLabels().get("alertname") != null ? first.getLabels().get("alertname") : first.getTitle();
            out.add(new AlertGroup(key, title, members.size(), members.stream().filter(Alert::isFiring).count(), max2,
                    members.stream().mapToLong(Alert::getOccurrenceCount).sum(), first.getFirstSeenAt(),
                    members.stream().map(Alert::getLastSeenAt).max(Instant::compareTo).orElse(first.getLastSeenAt()),
                    incident == null ? null : incident.getId(), incident == null ? null : incident.getNumber(), first.getSourceKind(), byStatus));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public AlertDetail get(Long id, Actor actor) {
        return detail(load(id), actor);
    }

    @Transactional(readOnly = true)
    public List<EventDto> history(Long id) {
        load(id);
        return events(id);
    }

    /** Active alerts of the given teams, most severe first (My Work). */
    @Transactional(readOnly = true)
    public List<AlertSummary> workFor(Set<Long> teamIds, int limit) {
        if (teamIds.isEmpty()) {
            return List.of();
        }
        Specification<Alert> spec = (root, q, cb) -> cb.and(root.get("status").in(Alert.Status.OPEN, Alert.Status.ACKNOWLEDGED),
                root.get("team").get("id").in(teamIds));
        return withCorrelationGroups(alerts.findAll(spec.and(severityOrder(false)), PageRequest.of(0, Math.max(1, limit))).map(AlertService::summary)
                .getContent());
    }

    @Transactional(readOnly = true)
    public long workCount(Set<Long> teamIds) {
        if (teamIds.isEmpty()) {
            return 0;
        }
        return alerts.count((root, q, cb) -> cb.and(root.get("status").in(Alert.Status.OPEN, Alert.Status.ACKNOWLEDGED),
                root.get("team").get("id").in(teamIds)));
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public AlertDetail create(CreateAlertRequest r, Actor actor) {
        var service = r.serviceId() == null ? null : services.findById(r.serviceId())
                .orElseThrow(() -> ApiException.invalidField("serviceId", "Service does not exist"));
        ConfigurationItem ci = r.ciId() == null ? null : cis.findById(r.ciId())
                .orElseThrow(() -> ApiException.invalidField("ciId", "Configuration item does not exist"));
        Map<String, String> labels = r.labels() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(r.labels());
        String fingerprint = r.fingerprint() != null && !r.fingerprint().isBlank() ? r.fingerprint().trim()
                : com.nexus360.security.Hashing.sha256Hex(r.title().trim().toLowerCase(Locale.ROOT) + "|" + (service == null ? "" : service.getCode())
                + "|" + (ci == null ? "" : ci.getName().toLowerCase(Locale.ROOT)));
        AlertSignal signal = new AlertSignal(fingerprint, r.groupKey(), r.title().trim(), r.description(), r.severity(), !"resolved".equals(r.status()),
                labels, r.annotations() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(r.annotations()), r.externalUrl(),
                service == null ? null : service.getCode(), ci == null ? null : ci.getName(), null, null);
        AlertIngestService.Outcome o = ingest.ingest(new AlertIngestService.Origin(Alert.SourceKind.MANUAL, null, null, service, ci, AlertSeverity.WARNING,
                actor, false, false, null, null), signal);
        if (o.alert() == null) {
            throw ApiException.invalidField("status", "There is no open alert with this fingerprint to resolve");
        }
        audit.record("ALERT_CREATED", "ALERT", o.alert().getNumber(), Map.of("action", o.action(), "severity", o.alert().getSeverity()));
        return detail(o.alert(), actor);
    }

    @Transactional
    public AlertDetail acknowledge(Long id, String note, Actor actor) {
        Alert a = load(id);
        if (a.getStatus() != Alert.Status.OPEN) {
            throw ApiException.conflict(a.getNumber() + " is " + a.getStatus() + "; only OPEN alerts can be acknowledged");
        }
        acknowledgeInternal(a, note, actor);
        audit.record("ALERT_ACKNOWLEDGED", "ALERT", a.getNumber(), Map.of("severity", a.getSeverity()));
        return detail(a, actor);
    }

    private void acknowledgeInternal(Alert a, String note, Actor actor) {
        Instant now = clock.instant();
        a.setStatus(Alert.Status.ACKNOWLEDGED);
        a.setAcknowledgedAt(now);
        a.setAcknowledgedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        a.setNextEscalationAt(null);
        alerts.saveAndFlush(a);
        history.record(a.getId(), "ACKNOWLEDGED", actor, blank(note), Map.of("minutesToAcknowledge", Duration.between(a.getFirstSeenAt(), now).toMinutes()));
    }

    @Transactional
    public AlertDetail resolve(Long id, String note, Actor actor) {
        Alert a = load(id);
        if (!a.getStatus().active()) {
            throw ApiException.conflict(a.getNumber() + " is already resolved");
        }
        resolveInternal(a, note, actor);
        audit.record("ALERT_RESOLVED", "ALERT", a.getNumber(), Map.of("severity", a.getSeverity()));
        return detail(a, actor);
    }

    private void resolveInternal(Alert a, String note, Actor actor) {
        a.setStatus(Alert.Status.RESOLVED);
        a.setResolvedAt(clock.instant());
        a.setResolvedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        a.setResolutionNote(blank(note));
        a.setNextEscalationAt(null);
        alerts.saveAndFlush(a);
        history.record(a.getId(), "RESOLVED", actor, blank(note), null);
    }

    @Transactional
    public AlertDetail suppress(Long id, SuppressRequest r, Actor actor) {
        Alert a = load(id);
        if (!suppressible(a)) {
            throw ApiException.conflict(a.getNumber() + " is " + a.getStatus() + "; only open or acknowledged alerts can be suppressed");
        }
        suppressInternal(a, r.minutes(), r.reason(), actor);
        return detail(a, actor);
    }

    private static boolean suppressible(Alert a) {
        return a.getStatus() == Alert.Status.OPEN || a.getStatus() == Alert.Status.ACKNOWLEDGED;
    }

    /** Manual suppression of one alert (single and bulk): status, history event {@code SUPPRESSED} and audit {@code ALERT_SUPPRESSED}. */
    private void suppressInternal(Alert a, Integer minutes, String reason, Actor actor) {
        Instant now = clock.instant();
        a.setStatus(Alert.Status.SUPPRESSED);
        a.setSuppressedAt(now);
        a.setSuppressedUntil(minutes == null ? null : now.plus(Duration.ofMinutes(minutes)));
        a.setSuppressedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        a.setSuppressionRule(null);
        a.setNextEscalationAt(null);
        alerts.saveAndFlush(a);
        history.record(a.getId(), "SUPPRESSED", actor, reason.trim(), minutes == null ? null : Map.of("minutes", minutes));
        audit.record("ALERT_SUPPRESSED", "ALERT", a.getNumber(), Map.of("minutes", minutes == null ? "until resolved" : minutes));
    }

    @Transactional
    public AlertDetail unsuppress(Long id, Actor actor) {
        Alert a = load(id);
        if (a.getStatus() != Alert.Status.SUPPRESSED) {
            throw ApiException.conflict(a.getNumber() + " is not suppressed");
        }
        Instant now = clock.instant();
        a.setStatus(Alert.Status.OPEN);
        a.setSuppressedAt(null);
        a.setSuppressedUntil(null);
        a.setSuppressedBy(null);
        a.setSuppressionRule(null);
        if (a.getTeam() == null) {
            ingest.route(a, now);
        }
        escalation.schedule(a, now);
        alerts.saveAndFlush(a);
        history.record(a.getId(), "UNSUPPRESSED", actor, null, null);
        audit.record("ALERT_UNSUPPRESSED", "ALERT", a.getNumber(), Map.of());
        return detail(a, actor);
    }

    @Transactional
    public AlertDetail assignTeam(Long id, AssignTeamRequest r, Actor actor) {
        Alert a = load(id);
        if (!a.getStatus().active()) {
            throw ApiException.conflict(a.getNumber() + " is resolved");
        }
        Team team = teamRouting.activeById(r.teamId()).orElseThrow(() -> ApiException.invalidField("teamId", "Team does not exist or is inactive"));
        assignTeamInternal(a, team, blank(r.note()), actor);
        audit.record("ALERT_ASSIGNED", "ALERT", a.getNumber(), Map.of("team", team.getCode()));
        return detail(a, actor);
    }

    private void assignTeamInternal(Alert a, Team team, String note, Actor actor) {
        String from = a.getTeam() == null ? null : a.getTeam().getName();
        a.setTeam(team);
        if (a.getStatus() == Alert.Status.OPEN) {
            escalation.schedule(a, clock.instant());
        }
        alerts.saveAndFlush(a);
        history.record(a.getId(), "ASSIGNED", actor, (from == null ? "Assigned to " : "Reassigned from " + from + " to ") + team.getName()
                + (note == null ? "" : ": " + note), Map.of("teamId", team.getId()));
        List<Long> members = teamRouting.activeMembers(team).stream().map(User::getId).filter(uid -> !uid.equals(actor.id())).toList();
        notifications.notifyUsers(members, "ALERT_ROUTED", "Alert assigned to " + team.getName() + ": " + a.getNumber(), truncate(a.getTitle(), 300),
                "/alerts/" + a.getId(), a.getSeverity() == AlertSeverity.CRITICAL ? Severity.CRITICAL : Severity.WARNING);
    }

    @Transactional
    public ConvertResult convert(Long id, ConvertRequest r, Actor actor) {
        Alert a = load(id);
        if (a.getIncident() != null) {
            throw ApiException.conflict(a.getNumber() + " is already linked to " + a.getIncident().getNumber());
        }
        CreateIncidentResult result = ingest.convert(a, r == null ? null : r.title(), r == null ? null : r.description(), r == null ? null : r.impact(),
                r == null ? null : r.urgency(), actor, "converted by " + actor.name());
        if (a.getStatus() == Alert.Status.OPEN) {
            acknowledgeInternal(a, "Acknowledged by converting to " + result.incident().number(), actor);
        }
        audit.record("ALERT_CONVERTED", "ALERT", a.getNumber(), Map.of("incident", result.incident().number()));
        long linked = alerts.findByIncidentId(result.incident().id()).size();
        return new ConvertResult(detail(a, actor), result.incident().id(), result.incident().number(),
                result.duplicate() != null && result.duplicate().detected(), result.duplicate() == null ? null : result.duplicate().parentNumber(), (int) linked);
    }

    @Transactional
    public ConvertResult linkIncident(Long id, LinkIncidentRequest r, Actor actor) {
        Alert a = load(id);
        if (a.getIncident() != null) {
            throw ApiException.conflict(a.getNumber() + " is already linked to " + a.getIncident().getNumber());
        }
        Incident incident = incidents.findById(r.incidentId()).orElseThrow(() -> ApiException.invalidField("incidentId", "Incident does not exist"));
        a.setIncident(incident);
        alerts.saveAndFlush(a);
        history.record(a.getId(), "LINKED_INCIDENT", actor, "Linked to " + incident.getNumber(), Map.of("incident", incident.getNumber()));
        incidentService.addCommentInternal(incident, actor, "Alert " + a.getNumber() + " (" + a.getSeverity() + ", " + truncate(a.getTitle(), 150)
                + ") was linked to this incident.", true);
        audit.record("ALERT_LINKED", "ALERT", a.getNumber(), Map.of("incident", incident.getNumber()));
        return new ConvertResult(detail(a, actor), incident.getId(), incident.getNumber(), false, null, alerts.findByIncidentId(incident.getId()).size());
    }

    /**
     * Bulk ACKNOWLEDGE (OPEN only), RESOLVE (active only) or SUPPRESS (OPEN/ACKNOWLEDGED only, {@code minutes?}, {@code reason} required) with the
     * same per-alert rules, history and audit as the single-alert actions; alerts that are not found or not eligible are skipped with a message.
     */
    @Transactional
    public BulkResult bulk(BulkRequest r, Actor actor) {
        boolean suppress = "SUPPRESS".equals(r.action());
        if (suppress && (r.reason() == null || r.reason().isBlank())) {
            throw ApiException.invalidField("reason", "A reason is required to suppress alerts");
        }
        int processed = 0;
        List<String> messages = new ArrayList<>();
        for (Long id : r.ids().stream().distinct().toList()) {
            Alert a = alerts.findWithDetailsById(id).orElse(null);
            if (a == null) {
                messages.add("Alert " + id + " was not found");
                continue;
            }
            if (suppress) {
                if (!suppressible(a)) {
                    messages.add(a.getNumber() + " is " + a.getStatus() + "; only open or acknowledged alerts can be suppressed");
                    continue;
                }
                suppressInternal(a, r.minutes(), r.reason(), actor);
            } else if ("ACKNOWLEDGE".equals(r.action())) {
                if (a.getStatus() != Alert.Status.OPEN) {
                    messages.add(a.getNumber() + " is " + a.getStatus());
                    continue;
                }
                acknowledgeInternal(a, r.note(), actor);
            } else {
                if (!a.getStatus().active()) {
                    messages.add(a.getNumber() + " is already resolved");
                    continue;
                }
                resolveInternal(a, r.note(), actor);
            }
            processed++;
        }
        Map<String, Object> details = new LinkedHashMap<>(Map.of("processed", processed, "requested", r.ids().size()));
        if (suppress) {
            details.put("minutes", r.minutes() == null ? "until resolved" : r.minutes());
        }
        audit.record("ALERTS_BULK_" + r.action(), "ALERT", null, details);
        return new BulkResult(processed, r.ids().size() - processed, messages);
    }

    // ------------------------------------------------------------------ workflow actions (ALERT_RECEIVED)

    @Transactional
    public String workflowCreateIncident(Long alertId, Level impact, Level urgency, Actor actor) {
        Alert a = load(alertId);
        if (a.getIncident() != null) {
            return null;
        }
        CreateIncidentResult result = ingest.convertOrJoinGroup(a, null, impact, urgency, actor, actor.name());
        return result == null ? "Linked to " + a.getIncident().getNumber() + " (incident of the alert group)"
                : "Incident " + result.incident().number() + " created" + (result.duplicate() != null && result.duplicate().detected()
                ? " and linked to " + result.duplicate().parentNumber() : "");
    }

    @Transactional
    public String workflowAssignTeam(Long alertId, Team team, Actor actor) {
        Alert a = load(alertId);
        assignTeamInternal(a, team, null, actor);
        return "Alert assigned to team " + team.getName();
    }

    @Transactional
    public void workflowComment(Long alertId, String message, Actor actor) {
        load(alertId);
        history.record(alertId, "COMMENT", actor, message, null);
    }

    // ------------------------------------------------------------------ helpers

    Alert load(Long id) {
        return alerts.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Alert", id));
    }

    private AlertDetail detail(Alert a, Actor actor) {
        List<AlertSummary> group = a.getGroupKey() == null ? List.of() : alerts.findGroupSiblings(a.getGroupKey(), a.getId(), PageRequest.of(0, 50)).stream()
                .map(AlertService::summary).toList();
        group = withCorrelationGroups(group);
        AlertEscalationPolicy policy = a.getStatus() == Alert.Status.OPEN ? escalation.policyFor(a) : null;
        return new AlertDetail(withCorrelationGroups(List.of(summary(a))).get(0), a.getDescription(), a.getFingerprint(), a.getLabels(), a.getAnnotations(), a.getExternalUrl(), a.getStartsAt(),
                a.getEndsAt(), a.getResolvedBy() == null ? null : a.getResolvedBy().getFullName(), a.getResolutionNote(), a.getSuppressedAt(),
                a.getSuppressedUntil(), a.getSuppressionRule() == null ? null : a.getSuppressionRule().getId(),
                a.getSuppressionRule() == null ? null : a.getSuppressionRule().getName(), a.getRoutingRule() == null ? null : a.getRoutingRule().getId(),
                a.getRoutingRule() == null ? null : a.getRoutingRule().getName(), a.getIntegration() == null ? null : a.getIntegration().getId(),
                a.getIntegration() == null ? null : a.getIntegration().getName(),
                a.getIncident() == null ? null : incidentMapper.summary(incidents.findById(a.getIncident().getId()).orElseThrow()), group, events(a.getId()),
                actions(a, actor), policy == null ? null : policy.name());
    }

    private static List<String> actions(Alert a, Actor actor) {
        if (actor == null || !actor.isStaff()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        switch (a.getStatus()) {
            case OPEN -> out.addAll(List.of("ACKNOWLEDGE", "RESOLVE", "SUPPRESS", "ASSIGN"));
            case ACKNOWLEDGED -> out.addAll(List.of("RESOLVE", "SUPPRESS", "ASSIGN"));
            case SUPPRESSED -> out.addAll(List.of("UNSUPPRESS", "RESOLVE"));
            case RESOLVED -> { }
        }
        if (a.getIncident() == null) {
            out.add("CONVERT");
            out.add("LINK_INCIDENT");
        }
        return out;
    }

    private List<EventDto> events(Long alertId) {
        return jdbc.query("select id, event_type, actor_name, note, details::text, created_at from alert_events where alert_id = ? order by created_at desc, id desc "
                + "limit 300", (rs, n) -> new EventDto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), parse(rs.getString(5)),
                rs.getTimestamp(6).toInstant()), alertId);
    }

    private Map<String, Object> parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    public static AlertSummary summary(Alert a) {
        return new AlertSummary(a.getId(), a.getNumber(), a.getTitle(), a.getSeverity(), a.getStatus(), a.isFiring(), a.getSourceKind(),
                a.getSource() == null ? null : a.getSource().getId(), a.getSource() == null ? (a.getIntegration() == null ? null : a.getIntegration().getName())
                : a.getSource().getName(), a.getGroupKey(), a.getService() == null ? null : a.getService().getId(),
                a.getService() == null ? null : a.getService().getName(), a.getCi() == null ? null : a.getCi().getId(),
                a.getCi() == null ? null : a.getCi().getName(), a.getTeam() == null ? null : a.getTeam().getId(), a.getTeam() == null ? null : a.getTeam().getName(),
                a.getIncident() == null ? null : a.getIncident().getId(), a.getIncident() == null ? null : a.getIncident().getNumber(), a.getOccurrenceCount(),
                a.getFirstSeenAt(), a.getLastSeenAt(), a.getAcknowledgedAt(), a.getAcknowledgedBy() == null ? null : a.getAcknowledgedBy().getFullName(),
                a.getResolvedAt(), a.getEscalationLevel(), a.getNextEscalationAt(), a.isDemo(), a.isSimulated(), null);
    }

    /** Release 4: fills {@code correlationGroupId} (current correlation group membership) in one query. */
    public List<AlertSummary> withCorrelationGroups(List<AlertSummary> list) {
        if (list.isEmpty()) {
            return list;
        }
        Map<Long, Long> groups = new java.util.HashMap<>();
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
                "select alert_id, group_id from correlation_group_members where alert_id in (:ids)",
                Map.of("ids", list.stream().map(AlertSummary::id).toList()), rs -> {
                    groups.put(rs.getLong(1), rs.getLong(2));
                });
        return list.stream().map(s -> s.withCorrelationGroup(groups.get(s.id()))).toList();
    }

    private Specification<Alert> spec(Filter f, Actor actor) {
        Set<Long> myTeams = "mine".equalsIgnoreCase(f.scope()) ? teamRouting.teamIdsOf(actor.id()) : Set.of();
        if (f.scope() != null && !Set.of("all", "mine", "unassigned").contains(f.scope().toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("scope must be all, mine or unassigned");
        }
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.statuses() != null && !f.statuses().isEmpty()) {
                p.add(root.get("status").in(f.statuses()));
            }
            if (f.severities() != null && !f.severities().isEmpty()) {
                p.add(root.get("severity").in(f.severities()));
            }
            if (f.sources() != null && !f.sources().isEmpty()) {
                p.add(root.get("sourceKind").in(f.sources()));
            }
            if (f.sourceId() != null) {
                p.add(cb.equal(root.get("source").get("id"), f.sourceId()));
            }
            if (f.serviceId() != null) {
                p.add(cb.equal(root.get("service").get("id"), f.serviceId()));
            }
            if (f.ciId() != null) {
                p.add(cb.equal(root.get("ci").get("id"), f.ciId()));
            }
            if (f.teamId() != null) {
                p.add(cb.equal(root.get("team").get("id"), f.teamId()));
            }
            if (f.incidentId() != null) {
                p.add(cb.equal(root.get("incident").get("id"), f.incidentId()));
            }
            if (f.groupKey() != null && !f.groupKey().isBlank()) {
                p.add(cb.equal(root.get("groupKey"), f.groupKey()));
            }
            if (f.firing() != null) {
                p.add(cb.equal(root.get("firing"), f.firing()));
            }
            if (f.hasIncident() != null) {
                p.add(f.hasIncident() ? cb.isNotNull(root.get("incident")) : cb.isNull(root.get("incident")));
            }
            if (f.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("lastSeenAt"), f.from()));
            }
            if (f.to() != null) {
                p.add(cb.lessThan(root.get("firstSeenAt"), f.to()));
            }
            if ("mine".equalsIgnoreCase(f.scope())) {
                p.add(myTeams.isEmpty() ? cb.disjunction() : root.get("team").get("id").in(myTeams));
            } else if ("unassigned".equalsIgnoreCase(f.scope())) {
                p.add(cb.isNull(root.get("team")));
            }
            if (f.search() != null && !f.search().isBlank()) {
                String like = "%" + f.search().trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("number")), like, '\\'), cb.like(cb.lower(root.get("title")), like, '\\'),
                        cb.like(cb.lower(root.get("fingerprint")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    /** Orders by severity rank (and last seen); a no-op for count queries. */
    private static Specification<Alert> severityOrder(boolean asc) {
        return (root, query, cb) -> {
            Class<?> type = query.getResultType();
            if (type != Long.class && type != long.class) {
                Expression<Integer> rank = cb.<Integer>selectCase().when(cb.equal(root.get("severity"), AlertSeverity.CRITICAL), 5)
                        .when(cb.equal(root.get("severity"), AlertSeverity.MAJOR), 4).when(cb.equal(root.get("severity"), AlertSeverity.MINOR), 3)
                        .when(cb.equal(root.get("severity"), AlertSeverity.WARNING), 2).otherwise(1);
                query.orderBy(asc ? cb.asc(rank) : cb.desc(rank), cb.desc(root.get("lastSeenAt")), cb.desc(root.get("id")));
            }
            return cb.conjunction();
        };
    }

    private String scopeSql(String scope, Actor actor) {
        if (scope == null || scope.isBlank() || scope.equalsIgnoreCase("all")) {
            return "";
        }
        if (scope.equalsIgnoreCase("unassigned")) {
            return " and team_id is null";
        }
        if (scope.equalsIgnoreCase("mine")) {
            Set<Long> teams = teamRouting.teamIdsOf(actor.id());
            return teams.isEmpty() ? " and false" : " and team_id in (" + String.join(",", teams.stream().map(String::valueOf).toList()) + ")";
        }
        throw ApiException.badRequest("scope must be all, mine or unassigned");
    }

    static final Set<Alert.Status> ACTIVE = EnumSet.copyOf(Alert.Status.ACTIVE);

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
