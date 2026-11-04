package com.nexus360.major;

import com.nexus360.admin.SettingsService;
import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalModel.ApproverDef;
import com.nexus360.approval.ApprovalModel.ApproverKind;
import com.nexus360.approval.ApprovalModel.EscalationDef;
import com.nexus360.approval.ApprovalModel.Quorum;
import com.nexus360.approval.ApprovalModel.StageDef;
import com.nexus360.approval.ApprovalModel.StageMode;
import com.nexus360.approval.ApprovalRepository;
import com.nexus360.approval.ApprovalService;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiError;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentDtos.ActivityItem;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentQueryService;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.major.MajorDtos.ActionItemDto;
import com.nexus360.major.MajorDtos.ActionRequest;
import com.nexus360.major.MajorDtos.CreatePirRequest;
import com.nexus360.major.MajorDtos.PersonRef;
import com.nexus360.major.MajorDtos.PirDetail;
import com.nexus360.major.MajorDtos.PirSummary;
import com.nexus360.major.MajorDtos.PirUpdateRequest;
import com.nexus360.major.PostIncidentReview.TimelineItem;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.report.ReportPdf;
import com.nexus360.security.Actor;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Post-incident reviews (docs/RELEASE3_SPEC.md §5 BE-4). A review is created for a top-level incident (automatically when its major
 * incident is closed); its timeline is built from the incident activity (history, comments, SLA events, escalations, approvals),
 * the linked alerts and their on-call pages, the duplicate reports and the war-room timeline and stakeholder updates; key times
 * (detect, acknowledge, declare, resolve) are computed from the same records. Submitting requests an approval of type {@code PIR}
 * routed to the PIR approvers (IT and incident managers; system administrators may decide any approval) — the requester never
 * approves their own review; the decision is applied by {@link #onApprovalDecision}.
 */
@Service
@RequiredArgsConstructor
public class PirService {

    static final Set<Role> PIR_APPROVERS = Set.of(Role.SYSTEM_ADMIN, Role.IT_MANAGER, Role.INCIDENT_MANAGER);
    private static final int MAX_TIMELINE = 400;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneId.of("UTC"));

    private final PirRepository reviews;
    private final MajorIncidentRepository majors;
    private final MajorTimelineRepository warRoom;
    private final StakeholderUpdateRepository updates;
    private final ActionItemService actions;
    private final IncidentRepository incidents;
    private final IncidentQueryService incidentQueries;
    private final IncidentMapper mapper;
    private final ApprovalService approvals;
    private final ApprovalRepository approvalRepository;
    private final UserRepository users;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<PirSummary> list(List<PostIncidentReview.Status> statuses, String search, Integer page, Integer size) {
        List<PostIncidentReview.Status> s = statuses == null || statuses.isEmpty() ? List.of(PostIncidentReview.Status.values()) : statuses;
        String q = search == null || search.isBlank() ? "" : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        Page<PostIncidentReview> result = reviews.search(s, q, PageResponse.pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result, this::summary);
    }

    PirSummary summary(PostIncidentReview p) {
        String miNumber = p.getMajorIncidentId() == null ? null : majors.findById(p.getMajorIncidentId()).map(MajorIncident::getNumber).orElse(null);
        List<ActionItemDto> acts = actions.ofReview(p.getId());
        return new PirSummary(p.getId(), p.getNumber(), p.getTitle(), p.getStatus(), p.getIncident().getId(), p.getIncident().getNumber(),
                p.getIncident().getTitle(), p.getMajorIncidentId(), miNumber, person(p.getOwner()),
                acts.stream().filter(a -> ActionItem.Status.OPEN_STATES.contains(a.status())).count(), acts.size(), p.getSubmittedAt(), p.getApprovedAt(),
                p.isDemo(), p.getCreatedAt(), p.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public PirDetail get(Long id, Actor actor) {
        return detail(load(id), actor);
    }

    @Transactional(readOnly = true)
    public PirDetail byIncident(Long incidentId, Actor actor) {
        return detail(reviews.findByIncidentId(incidentId).orElseThrow(() -> ApiException.notFound("Post-incident review for incident", incidentId)),
                actor);
    }

    PirDetail detail(PostIncidentReview p, Actor actor) {
        MajorIncident mi = p.getMajorIncidentId() == null ? null : majors.findById(p.getMajorIncidentId()).orElse(null);
        String approvalStatus = p.getApprovalId() == null ? null
                : approvalRepository.findById(p.getApprovalId()).map(a -> a.getStatus().name()).orElse(null);
        Incident i = incidents.findDetailedById(p.getIncident().getId()).orElse(p.getIncident());
        boolean editable = p.getStatus() == PostIncidentReview.Status.DRAFT || p.getStatus() == PostIncidentReview.Status.REJECTED;
        return new PirDetail(p.getId(), p.getNumber(), p.getTitle(), p.getStatus(), mapper.summary(i), p.getMajorIncidentId(),
                mi == null ? null : mi.getNumber(), person(p.getOwner()), p.getSummary(), p.getRootCause(), List.copyOf(p.getContributingFactors()),
                p.getImpact(), p.getDetectionGaps(), p.getResponseAnalysis(), p.getLessonsLearned(), List.copyOf(p.getTimeline()),
                new LinkedHashMap<>(p.getMetrics()), p.getTimelineBuiltAt(), actions.ofReview(p.getId()), p.getApprovalId(), approvalStatus,
                p.getSubmittedAt(), p.getApprovedAt(), p.getApprovedBy() == null ? null : p.getApprovedBy().getFullName(), p.getReviewComment(),
                editable && canEdit(p, mi, actor), editable && canEdit(p, mi, actor), p.isDemo(), p.getCreatedAt(), p.getUpdatedAt());
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public PirDetail create(CreatePirRequest r, Actor actor) {
        Incident incident = incidents.findDetailedById(r.incidentId())
                .orElseThrow(() -> ApiException.invalidField("incidentId", "Incident " + r.incidentId() + " does not exist"));
        MajorIncident mi = majors.findByIncidentId(incident.getId()).orElse(null);
        boolean commander = mi != null && mi.getCommander() != null && Objects.equals(mi.getCommander().getId(), actor.id());
        if (!actor.isAdminOrManager() && !actor.isSystem() && !commander) {
            throw ApiException.forbidden("Managers, administrators and the incident commander start post-incident reviews");
        }
        PostIncidentReview p = createInternal(incident, mi, r.ownerId() == null ? null : staff(r.ownerId(), "ownerId"), r.title(), actor);
        return detail(p, actor);
    }

    /** Creates the review of an incident (also when its major incident is closed); {@code 409} when it already has one. */
    @Transactional
    public PostIncidentReview createInternal(Incident incident, MajorIncident mi, User owner, String title, Actor actor) {
        if (incident.getParent() != null) {
            throw ApiException.badRequest(incident.getNumber() + " is a linked report; review its parent " + incident.getParent().getNumber());
        }
        if (reviews.existsByIncidentId(incident.getId())) {
            throw ApiException.conflict(incident.getNumber() + " already has a post-incident review");
        }
        PostIncidentReview p = new PostIncidentReview();
        p.setNumber("PIR-" + reviews.nextNumber());
        p.setIncident(incident);
        p.setMajorIncidentId(mi == null ? null : mi.getId());
        String t = title == null || title.isBlank() ? "Post-incident review: " + incident.getTitle() : title.trim();
        p.setTitle(t.length() > 200 ? t.substring(0, 199) + "…" : t);
        User defaultOwner = owner != null ? owner : mi != null && mi.getCommander() != null ? mi.getCommander()
                : actor.isSystem() ? null : users.findById(actor.id()).orElse(null);
        p.setOwner(defaultOwner);
        p.setCreatedById(actor.id());
        p.setDemo(incident.isDemo());
        p.setRootCause(incident.getRootCause());
        p.setSummary(mi != null && mi.getResolutionSummary() != null ? mi.getResolutionSummary() : incident.getResolutionNotes());
        p.setImpact(mi != null && mi.getImpactSummary() != null ? mi.getImpactSummary() : null);
        reviews.save(p);
        rebuild(p);
        if (p.getOwner() != null) {
            notifications.notifyUser(p.getOwner().getId(), "PIR_UPDATED", "Post-incident review " + p.getNumber() + " assigned to you",
                    p.getTitle() + ". The timeline was built from the incident records; add the analysis and corrective actions.",
                    link(p), Severity.INFO);
        }
        audit.record("PIR_CREATED", "PIR", p.getId(), Map.of("number", p.getNumber(), "incident", incident.getNumber(),
                "majorIncident", mi == null ? "" : mi.getNumber()));
        return p;
    }

    @Transactional
    public PirDetail update(Long id, PirUpdateRequest r, Actor actor) {
        PostIncidentReview p = editable(id, actor);
        if (r.title() != null) {
            p.setTitle(r.title().trim());
        }
        if (r.ownerId() != null) {
            p.setOwner(staff(r.ownerId(), "ownerId"));
        }
        if (r.summary() != null) {
            p.setSummary(blankToNull(r.summary()));
        }
        if (r.rootCause() != null) {
            p.setRootCause(blankToNull(r.rootCause()));
        }
        if (r.contributingFactors() != null) {
            p.setContributingFactors(new ArrayList<>(r.contributingFactors().stream().map(String::trim).toList()));
        }
        if (r.impact() != null) {
            p.setImpact(blankToNull(r.impact()));
        }
        if (r.detectionGaps() != null) {
            p.setDetectionGaps(blankToNull(r.detectionGaps()));
        }
        if (r.responseAnalysis() != null) {
            p.setResponseAnalysis(blankToNull(r.responseAnalysis()));
        }
        if (r.lessonsLearned() != null) {
            p.setLessonsLearned(blankToNull(r.lessonsLearned()));
        }
        reviews.saveAndFlush(p);
        audit.record("PIR_UPDATED", "PIR", p.getId(), Map.of("number", p.getNumber()));
        return detail(p, actor);
    }

    @Transactional
    public PirDetail rebuildTimeline(Long id, Actor actor) {
        PostIncidentReview p = editable(id, actor);
        rebuild(p);
        audit.record("PIR_TIMELINE_REBUILT", "PIR", p.getId(), Map.of("entries", p.getTimeline().size()));
        return detail(p, actor);
    }

    /** Submits the review for approval (approval type PIR, routed to the PIR approvers). */
    @Transactional
    public PirDetail submit(Long id, Actor actor) {
        PostIncidentReview p = editable(id, actor);
        Incident i = p.getIncident();
        if (i.getStatus() != IncidentStatus.RESOLVED && i.getStatus() != IncidentStatus.CLOSED) {
            throw ApiException.conflict("Resolve " + i.getNumber() + " before submitting its review");
        }
        List<ApiError.FieldError> missing = new ArrayList<>();
        if (p.getSummary() == null || p.getSummary().isBlank()) {
            missing.add(new ApiError.FieldError("summary", "A summary is required"));
        }
        if (p.getRootCause() == null || p.getRootCause().isBlank()) {
            missing.add(new ApiError.FieldError("rootCause", "The root cause is required"));
        }
        if (p.getImpact() == null || p.getImpact().isBlank()) {
            missing.add(new ApiError.FieldError("impact", "The impact is required"));
        }
        boolean hasAction = actions.ofReview(p.getId()).stream().anyMatch(a -> a.actionType() != ActionItem.Type.FOLLOW_UP
                && a.status() != ActionItem.Status.CANCELLED);
        if (!hasAction) {
            missing.add(new ApiError.FieldError("actions", "Add at least one corrective or preventive action"));
        }
        if (!missing.isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, missing.get(0).message(), missing);
        }
        StageDef stage = new StageDef("Post-incident review approval", StageMode.PARALLEL, Quorum.ANY, 1,
                List.of(new ApproverDef(ApproverKind.ROLE, null, Role.INCIDENT_MANAGER, null), new ApproverDef(ApproverKind.ROLE, null, Role.IT_MANAGER, null)),
                null, 72, new EscalationDef(false, Role.SYSTEM_ADMIN, false));
        Approval a = approvals.requestFlow(new ApprovalService.FlowRequest(Approval.Type.PIR, p.getId(), p.getNumber(), "Review " + p.getNumber() + ": "
                + p.getTitle(), "Post-incident review of " + i.getNumber() + " (" + i.getTitle() + ")", Map.of("incident", i.getNumber(),
                "actions", actions.ofReview(p.getId()).size()), null, List.of(stage), Map.of(), actor, p.isDemo(), link(p)));
        p.setApprovalId(a.getId());
        p.setSubmittedAt(clock.instant());
        p.setReviewComment(null);
        p.setStatus(PostIncidentReview.Status.IN_REVIEW);
        audit.record("PIR_SUBMITTED", "PIR", p.getId(), Map.of("number", p.getNumber(), "approval", a.getId()));
        if (a.getStatus() != Approval.Status.PENDING) {
            onApprovalDecision(a, a.getStatus() == Approval.Status.APPROVED, Actor.SYSTEM);
        }
        return detail(p, actor);
    }

    /** Withdraws a review from approval (cancels the pending approval) so it can be edited again. */
    @Transactional
    public PirDetail withdraw(Long id, Actor actor) {
        PostIncidentReview p = load(id);
        if (p.getStatus() != PostIncidentReview.Status.IN_REVIEW) {
            throw ApiException.conflict(p.getNumber() + " is not waiting for approval");
        }
        requireEdit(p, actor);
        if (p.getApprovalId() != null) {
            approvalRepository.findById(p.getApprovalId()).ifPresent(a -> approvals.cancel(a, "Review withdrawn for changes", actor));
        }
        p.setStatus(PostIncidentReview.Status.DRAFT);
        audit.record("PIR_WITHDRAWN", "PIR", p.getId(), Map.of("number", p.getNumber()));
        return detail(p, actor);
    }

    /** Approval engine outcome for type PIR (inside the decision transaction). */
    @Transactional
    public void onApprovalDecision(Approval approval, boolean approved, Actor approver) {
        PostIncidentReview p = reviews.findWithDetailsById(approval.getReferenceId()).orElse(null);
        if (p == null || !Objects.equals(p.getApprovalId(), approval.getId())) {
            return; // a PIR-type approval that was not requested by a review submission (e.g. a custom flow): nothing to apply
        }
        if (p.getStatus() != PostIncidentReview.Status.IN_REVIEW) {
            throw ApiException.conflict(p.getNumber() + " is no longer waiting for approval");
        }
        if (approved) {
            p.setStatus(PostIncidentReview.Status.APPROVED);
            p.setApprovedAt(clock.instant());
            p.setApprovedBy(approver.isSystem() ? null : users.findById(approver.id()).orElse(null));
        } else {
            p.setStatus(PostIncidentReview.Status.REJECTED);
        }
        p.setReviewComment(approval.getDecisionComment());
        if (p.getOwner() != null) {
            notifications.notifyUser(p.getOwner().getId(), "PIR_UPDATED", p.getNumber() + (approved ? " approved" : " needs changes"),
                    p.getTitle() + (approval.getDecisionComment() == null ? "" : ": " + approval.getDecisionComment()), link(p),
                    approved ? Severity.INFO : Severity.WARNING);
        }
        audit.record(approved ? "PIR_APPROVED" : "PIR_REJECTED", "PIR", p.getId(), Map.of("number", p.getNumber(), "by", approver.name()));
    }

    // ------------------------------------------------------------------ actions

    @Transactional
    public ActionItemDto addAction(Long id, ActionRequest r, Actor actor) {
        PostIncidentReview p = load(id);
        if (p.getStatus() == PostIncidentReview.Status.IN_REVIEW) {
            throw ApiException.conflict("Withdraw " + p.getNumber() + " from approval to change its actions");
        }
        requireEdit(p, actor);
        return actions.dto(actions.add(parent(p), r, actor));
    }

    @Transactional
    public ActionItemDto updateAction(ActionItem item, ActionRequest r, Actor actor) {
        PostIncidentReview p = load(item.getPirId());
        // Owners update the status of their own actions at any time (also after approval); editors change the rest.
        boolean owner = item.getOwner() != null && Objects.equals(item.getOwner().getId(), actor.id());
        if (!owner) {
            requireEdit(p, actor);
        }
        return actions.dto(actions.update(item, parent(p), r, actor));
    }

    ActionItemService.Parent parent(PostIncidentReview p) {
        return new ActionItemService.Parent(null, p.getId(), p.getNumber(), link(p), p.isDemo());
    }

    // ------------------------------------------------------------------ PDF

    @Transactional(readOnly = true)
    public byte[] pdf(Long id) {
        PostIncidentReview p = load(id);
        PirDetail d = detail(p, Actor.SYSTEM);
        List<ReportPdf.Table> tables = new ArrayList<>();
        List<List<String>> analysis = new ArrayList<>();
        analysis.add(List.of("Summary", nz(d.summary())));
        analysis.add(List.of("Impact", nz(d.impact())));
        analysis.add(List.of("Root cause", nz(d.rootCause())));
        analysis.add(List.of("Contributing factors", d.contributingFactors().isEmpty() ? "-" : String.join("\n", d.contributingFactors().stream()
                .map(f -> "• " + f).toList())));
        analysis.add(List.of("Detection gaps", nz(d.detectionGaps())));
        analysis.add(List.of("Response analysis", nz(d.responseAnalysis())));
        analysis.add(List.of("Lessons learned", nz(d.lessonsLearned())));
        tables.add(new ReportPdf.Table("Analysis", List.of("Section", "Finding"), new float[]{2, 7}, analysis, false));
        List<List<String>> times = new ArrayList<>();
        d.metrics().forEach((k, v) -> times.add(List.of(METRIC_LABELS.getOrDefault(k, k), v == null ? "n/a" : String.valueOf(v))));
        tables.add(new ReportPdf.Table("Key times", List.of("Measure", "Value"), new float[]{3, 4}, times, false));
        tables.add(new ReportPdf.Table("Corrective and preventive actions", List.of("Action", "Type", "Owner", "Due", "Status"),
                new float[]{6, 2, 3, 2, 2}, d.actions().stream().map(a -> List.of(a.title(), a.actionType().name(), nz(a.ownerName()),
                a.dueDate() == null ? "-" : a.dueDate().toString(), a.status().name())).toList(), false));
        tables.add(new ReportPdf.Table("Timeline (UTC)", List.of("Time", "Source", "Event"), new float[]{3, 2, 8},
                d.timeline().stream().map(t -> List.of(WHEN.format(Instant.parse(t.at())), t.source(), t.title() + (t.detail() == null
                        || t.detail().isBlank() ? "" : " — " + truncate(t.detail(), 400)))).toList(), false));
        List<String> intro = new ArrayList<>();
        intro.add("Incident " + d.incident().number() + ": " + d.incident().title() + " (" + d.incident().priority() + ", " + d.incident().status() + ")");
        if (d.majorIncidentNumber() != null) {
            intro.add("Major incident " + d.majorIncidentNumber());
        }
        intro.add("Status: " + d.status() + (d.approvedAt() == null ? "" : " — approved by " + nz(d.approvedByName()) + " on " + WHEN.format(d.approvedAt())));
        intro.add("Owner: " + (d.owner() == null ? "-" : d.owner().name()) + " · Generated " + WHEN.format(clock.instant()));
        if (p.isDemo()) {
            intro.add("DEMO record");
        }
        return ReportPdf.render(d.number() + " " + d.title(), intro, tables);
    }

    static final Map<String, String> METRIC_LABELS = Map.ofEntries(Map.entry("detectedAt", "First signal (alert or report)"),
            Map.entry("reportedAt", "Incident recorded"), Map.entry("firstResponseAt", "First response"), Map.entry("declaredAt", "Declared major"),
            Map.entry("acknowledgedAt", "First alert acknowledged"), Map.entry("resolvedAt", "Resolved"),
            Map.entry("minutesToDetect", "Minutes to detect (first alert → incident)"), Map.entry("minutesToAcknowledge", "Minutes to first response"),
            Map.entry("minutesToDeclare", "Minutes to declare major"), Map.entry("minutesToResolve", "Minutes to resolve"),
            Map.entry("alerts", "Linked alerts"), Map.entry("pages", "On-call pages"), Map.entry("linkedReports", "Duplicate reports"),
            Map.entry("slaOutcome", "Resolution SLA"));

    // ------------------------------------------------------------------ timeline

    /** Re-builds the timeline and key times from the stored records. */
    void rebuild(PostIncidentReview p) {
        Incident i = p.getIncident();
        List<TimelineItem> items = new ArrayList<>();
        for (ActivityItem a : incidentQueries.activity(i.getId(), Actor.SYSTEM)) {
            items.add(item(a.at(), a.kind(), "Incident", a.title(), a.detail(), a.actorName()));
        }
        jdbc.query("select number, title, created_at from incidents where parent_id = ? order by created_at", rs -> {
            items.add(item(rs.getTimestamp("created_at").toInstant(), "REPORT", "Incident", "Duplicate report " + rs.getString("number"),
                    rs.getString("title"), null));
        }, i.getId());
        List<Instant> alertStarts = new ArrayList<>();
        List<Instant> acks = new ArrayList<>();
        jdbc.query("select a.number, a.title, a.severity, a.first_seen_at, a.acknowledged_at, a.resolved_at, ack.full_name as ack_by "
                + "from alerts a left join users ack on ack.id = a.acknowledged_by_id where a.incident_id = ? order by a.first_seen_at", rs -> {
            Instant first = rs.getTimestamp("first_seen_at").toInstant();
            alertStarts.add(first);
            items.add(item(first, "ALERT", "Alert", "Alert " + rs.getString("number") + " fired (" + rs.getString("severity") + ")",
                    rs.getString("title"), null));
            Timestamp ack = rs.getTimestamp("acknowledged_at");
            if (ack != null) {
                acks.add(ack.toInstant());
                items.add(item(ack.toInstant(), "ALERT", "Alert", "Alert " + rs.getString("number") + " acknowledged", null, rs.getString("ack_by")));
            }
            Timestamp res = rs.getTimestamp("resolved_at");
            if (res != null) {
                items.add(item(res.toInstant(), "ALERT", "Alert", "Alert " + rs.getString("number") + " resolved", null, null));
            }
        }, i.getId());
        long[] pages = {0};
        jdbc.query("select p.level, p.target, p.paged_at, u.full_name, a.number from oncall_pages p join alerts a on a.id = p.alert_id "
                + "join users u on u.id = p.user_id where a.incident_id = ? order by p.paged_at", rs -> {
            pages[0]++;
            items.add(item(rs.getTimestamp("paged_at").toInstant(), "PAGE", "On-call", "Paged " + rs.getString("full_name") + " for "
                    + rs.getString("number") + " (level " + rs.getInt("level") + ", " + rs.getString("target").toLowerCase(Locale.ROOT) + ")", null, null));
        }, i.getId());
        MajorIncident mi = p.getMajorIncidentId() == null ? null : majors.findById(p.getMajorIncidentId()).orElse(null);
        if (mi != null) {
            for (MajorTimelineEntry e : warRoom.findByMajorIncidentIdOrderByOccurredAtAscIdAsc(mi.getId())) {
                items.add(item(e.getOccurredAt(), "WAR_ROOM", "Major incident", humanize(e.getEntryType().name()), e.getBody(), e.getActorName()));
            }
            for (StakeholderUpdate u : updates.findByMajorIncidentIdOrderByPostedAtDescIdDesc(mi.getId())) {
                items.add(item(u.getPostedAt(), "UPDATE", "Stakeholders", "Stakeholder update (" + u.getStatus() + "): " + u.getTitle()
                        + (u.getStatusUpdateId() == null ? "" : " — published on the status page"), u.getMessage(), u.getPostedByName()));
            }
        }
        items.sort(Comparator.comparing(TimelineItem::at));
        p.setTimeline(new ArrayList<>(items.size() > MAX_TIMELINE ? items.subList(0, MAX_TIMELINE) : items));
        Map<String, Object> m = new LinkedHashMap<>();
        Instant firstAlert = alertStarts.stream().min(Comparator.naturalOrder()).orElse(null);
        Instant detected = firstAlert != null && firstAlert.isBefore(i.getCreatedAt()) ? firstAlert : i.getCreatedAt();
        m.put("detectedAt", detected.toString());
        m.put("reportedAt", i.getCreatedAt().toString());
        m.put("firstResponseAt", i.getFirstResponseAt() == null ? null : i.getFirstResponseAt().toString());
        m.put("declaredAt", mi == null ? null : mi.getDeclaredAt().toString());
        m.put("acknowledgedAt", acks.stream().min(Comparator.naturalOrder()).map(Instant::toString).orElse(null));
        m.put("resolvedAt", i.getResolvedAt() == null ? null : i.getResolvedAt().toString());
        m.put("minutesToDetect", firstAlert == null || firstAlert.isAfter(i.getCreatedAt()) ? null : minutes(firstAlert, i.getCreatedAt()));
        m.put("minutesToAcknowledge", i.getFirstResponseAt() == null ? null : minutes(i.getCreatedAt(), i.getFirstResponseAt()));
        m.put("minutesToDeclare", mi == null ? null : minutes(i.getCreatedAt(), mi.getDeclaredAt()));
        m.put("minutesToResolve", i.getResolvedAt() == null ? null : minutes(i.getCreatedAt(), i.getResolvedAt()));
        m.put("alerts", alertStarts.size());
        m.put("pages", pages[0]);
        m.put("linkedReports", jdbc.queryForObject("select count(*) from incidents where parent_id = ?", Long.class, i.getId()));
        m.put("slaOutcome", jdbc.queryForList("select outcome from incident_slas where incident_id = ?", String.class, i.getId()).stream()
                .filter(Objects::nonNull).findFirst().orElse(null));
        p.setMetrics(m);
        p.setTimelineBuiltAt(clock.instant());
    }

    private static TimelineItem item(Instant at, String kind, String source, String title, String detail, String actor) {
        return new TimelineItem(at.toString(), kind, source, title, detail == null ? null : truncate(detail, 1000), actor);
    }

    private static Double minutes(Instant a, Instant b) {
        return Math.round(Duration.between(a, b).getSeconds() / 6.0) / 10.0;
    }

    // ------------------------------------------------------------------ helpers

    PostIncidentReview load(Long id) {
        return reviews.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Post-incident review", id));
    }

    private PostIncidentReview editable(Long id, Actor actor) {
        PostIncidentReview p = load(id);
        if (p.getStatus() != PostIncidentReview.Status.DRAFT && p.getStatus() != PostIncidentReview.Status.REJECTED) {
            throw ApiException.conflict(p.getNumber() + " is " + p.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " and cannot be edited");
        }
        requireEdit(p, actor);
        return p;
    }

    private void requireEdit(PostIncidentReview p, Actor actor) {
        MajorIncident mi = p.getMajorIncidentId() == null ? null : majors.findById(p.getMajorIncidentId()).orElse(null);
        if (!canEdit(p, mi, actor)) {
            throw ApiException.forbidden("Only the review owner, the major incident roles, managers and administrators edit " + p.getNumber());
        }
    }

    private static boolean canEdit(PostIncidentReview p, MajorIncident mi, Actor actor) {
        if (actor.isSystem() || actor.isAdminOrManager()) {
            return true;
        }
        if (!actor.isStaff()) {
            return false;
        }
        Long me = actor.id();
        return (p.getOwner() != null && Objects.equals(p.getOwner().getId(), me))
                || (mi != null && ((mi.getCommander() != null && Objects.equals(mi.getCommander().getId(), me))
                || (mi.getTechLead() != null && Objects.equals(mi.getTechLead().getId(), me))
                || (mi.getCommsLead() != null && Objects.equals(mi.getCommsLead().getId(), me))));
    }

    private User staff(Long id, String field) {
        return users.findById(id).filter(u -> u.isActive() && u.getRole().isStaff())
                .orElseThrow(() -> ApiException.invalidField(field, "User " + id + " is not an active staff member"));
    }

    static PersonRef person(User u) {
        return u == null ? null : new PersonRef(u.getId(), u.getFullName(), u.getEmail());
    }

    static String link(PostIncidentReview p) {
        return "/reviews/" + p.getId();
    }

    private static String nz(String v) {
        return v == null || v.isBlank() ? "-" : v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String truncate(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }

    private static String humanize(String v) {
        String s = v.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Days after resolution a review is due (setting {@code pir.due_days}). */
    int dueDays() {
        return Math.max(1, settings.getInt("pir.due_days", 5));
    }
}
