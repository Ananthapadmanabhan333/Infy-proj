package com.nexus360.change;

import com.nexus360.admin.SettingsService;
import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalService;
import com.nexus360.audit.AuditService;
import com.nexus360.change.ChangeDtos.CalendarConflict;
import com.nexus360.change.ChangeDtos.CalendarEntry;
import com.nexus360.change.ChangeDtos.ChangeCalendar;
import com.nexus360.change.ChangeDtos.ChangeDetail;
import com.nexus360.change.ChangeDtos.ChangeMetrics;
import com.nexus360.change.ChangeDtos.ChangeRequest;
import com.nexus360.change.ChangeDtos.ChangeSummary;
import com.nexus360.change.ChangeDtos.Conflict;
import com.nexus360.change.ChangeDtos.CorrelatedIncident;
import com.nexus360.change.ChangeDtos.Correlation;
import com.nexus360.change.ChangeDtos.DeploymentRef;
import com.nexus360.change.ChangeDtos.FreezeDto;
import com.nexus360.change.ChangeDtos.FreezeRef;
import com.nexus360.change.ChangeDtos.FreezeRequest;
import com.nexus360.change.ChangeDtos.HistoryDto;
import com.nexus360.change.ChangeDtos.LinkedIncident;
import com.nexus360.change.ChangeDtos.ServiceRef;
import com.nexus360.change.ChangeDtos.TemplateDto;
import com.nexus360.change.ChangeDtos.TemplateRequest;
import com.nexus360.change.ChangeDtos.TransitionRequest;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.email.EmailService;
import com.nexus360.email.EmailTemplates;
import com.nexus360.events.DomainEvents.ChangeEvent;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.Level;
import com.nexus360.incident.Priority;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.problem.ProblemRepository;
import com.nexus360.security.Actor;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRouting;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Change management (docs/RELEASE3_SPEC.md §5 BE-2): the lifecycle in {@link ChangeStatus} enforced here, risk assessment
 * ({@link ChangeRisk}), CAB approval through the approval engine (flow {@code CHANGE_NORMAL}: change authority, plus the CAB
 * stage when the risk is HIGH; {@code CHANGE_EMERGENCY} for emergency changes), standard changes from pre-approved
 * templates, scheduling with conflict detection (same service or CI, overlapping windows) and freeze windows, the change
 * calendar, success metrics and incident correlation.
 */
@Service
@RequiredArgsConstructor
public class ChangeService {

    public static final String NORMAL_FLOW = "CHANGE_NORMAL";
    public static final String EMERGENCY_FLOW = "CHANGE_EMERGENCY";
    private static final Set<Role> EDITORS = EnumSet.of(Role.SYSTEM_ADMIN, Role.IT_ADMIN, Role.IT_MANAGER, Role.CHANGE_MANAGER);
    private static final Set<Role> APPROVERS = EnumSet.of(Role.SYSTEM_ADMIN, Role.IT_MANAGER, Role.CHANGE_MANAGER);
    /** My Work: changes in these statuses are work for their implementer or requester. */
    static final Set<ChangeStatus> WORK = EnumSet.of(ChangeStatus.DRAFT, ChangeStatus.SUBMITTED, ChangeStatus.ASSESSMENT,
            ChangeStatus.APPROVED, ChangeStatus.SCHEDULED, ChangeStatus.IMPLEMENTING, ChangeStatus.REJECTED);

    /** List filters ({@code scope}: all, mine = assigned to me, requested = raised by me, team = my teams). */
    public record Filter(String search, List<ChangeStatus> statuses, Change.Type type, Change.Risk risk, Long serviceId, Long assigneeId,
                         Long teamId, String scope, Instant from, Instant to) {}

    private final ChangeRepository changes;
    private final ChangeTemplateRepository templates;
    private final ChangeFreezeWindowRepository freezes;
    private final ChangeHistoryRepository history;
    private final ChangeIncidentRepository incidentLinks;
    private final ApprovalService approvals;
    private final IncidentRepository incidents;
    private final IncidentMapper incidentMapper;
    private final BusinessServiceRepository services;
    private final ProblemRepository problems;
    private final UserRepository users;
    private final TeamRouting teamRouting;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final EmailService email;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final JdbcTemplate jdbc;
    private final com.nexus360.cmdb.CiLinkService ciLinks;
    private final Clock clock;

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public PageResponse<ChangeSummary> list(Filter f, Integer page, Integer size, String sort, Actor actor) {
        Set<Long> myTeams = "team".equals(f.scope()) ? teamRouting.teamIdsOf(actor.id()) : Set.of();
        Specification<Change> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.statuses() != null && !f.statuses().isEmpty()) {
                p.add(root.get("status").in(f.statuses()));
            }
            if (f.type() != null) {
                p.add(cb.equal(root.get("type"), f.type()));
            }
            if (f.risk() != null) {
                p.add(cb.equal(root.get("risk"), f.risk()));
            }
            if (f.assigneeId() != null) {
                p.add(cb.equal(root.get("assignee").get("id"), f.assigneeId()));
            }
            if (f.teamId() != null) {
                p.add(cb.equal(root.get("team").get("id"), f.teamId()));
            }
            if (f.serviceId() != null) {
                Join<Object, Object> s = root.join("services", JoinType.INNER);
                p.add(cb.equal(s.get("id"), f.serviceId()));
                query.distinct(true);
            }
            if (f.from() != null) {
                p.add(cb.greaterThan(root.get("plannedEnd"), f.from()));
            }
            if (f.to() != null) {
                p.add(cb.lessThan(root.get("plannedStart"), f.to()));
            }
            switch (f.scope() == null ? "all" : f.scope()) {
                case "mine" -> p.add(cb.equal(root.get("assignee").get("id"), actor.id()));
                case "requested" -> p.add(cb.equal(root.get("requester").get("id"), actor.id()));
                case "team" -> p.add(myTeams.isEmpty() ? cb.disjunction() : root.get("team").get("id").in(myTeams));
                default -> { }
            }
            if (f.search() != null && !f.search().isBlank()) {
                String like = "%" + f.search().trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'), cb.like(cb.lower(root.get("number")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<Change> result = changes.findAll(spec, PageResponse.pageable(page, size, sortOf(sort,
                Set.of("createdAt", "updatedAt", "plannedStart", "riskScore", "number", "status", "priority"), Sort.by(Sort.Direction.DESC, "createdAt"))));
        return PageResponse.of(result, summaries(result.getContent()));
    }

    @Transactional(readOnly = true)
    public List<ChangeSummary> summaries(List<Change> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ServiceRef>> refs = serviceRefs(list.stream().map(Change::getId).toList());
        return list.stream().map(c -> new ChangeSummary(c.getId(), c.getNumber(), c.getTitle(), c.getType(), c.getStatus(), c.getRisk(),
                c.getRiskScore(), c.getPriority(), c.getPlannedStart(), c.getPlannedEnd(), refs.getOrDefault(c.getId(), List.of()),
                c.getAssignee() == null ? null : c.getAssignee().getId(), c.getAssignee() == null ? null : c.getAssignee().getFullName(),
                c.getTeam() == null ? null : c.getTeam().getId(), c.getTeam() == null ? null : c.getTeam().getName(),
                c.getRequester() == null ? null : c.getRequester().getId(), c.getRequester() == null ? null : c.getRequester().getFullName(),
                c.getApproval() == null ? null : c.getApproval().getId(), c.getApproval() == null ? null : c.getApproval().getStatus().name(),
                c.isPirRequired(), c.getPirCompletedAt() != null, c.getCreatedAt(), c.getUpdatedAt(), c.isDemo())).toList();
    }

    @Transactional(readOnly = true)
    public ChangeDetail get(Long id, Actor actor) {
        return detail(load(id), actor);
    }

    @Transactional(readOnly = true)
    public List<HistoryDto> history(Long id) {
        load(id);
        return history.findByChangeIdOrderByCreatedAtAscIdAsc(id).stream()
                .map(h -> new HistoryDto(h.getCreatedAt(), h.getActorName(), h.getAction(), h.getFromValue(), h.getToValue(), h.getNote())).toList();
    }

    public List<ChangeRisk.Question> riskQuestions() {
        return ChangeRisk.QUESTIONS;
    }

    // ------------------------------------------------------------------ create / update

    @Transactional
    public ChangeDetail create(ChangeRequest r, Actor actor) {
        Change.Type type = r.type() == null ? (r.templateId() != null ? Change.Type.STANDARD : Change.Type.NORMAL) : r.type();
        if (!EDITORS.contains(actor.role())) {
            if (actor.role() != Role.INCIDENT_MANAGER) {
                throw ApiException.forbidden("Only change editors can raise changes");
            }
            if (type != Change.Type.EMERGENCY) {
                throw ApiException.forbidden("Incident managers may only raise EMERGENCY changes");
            }
        }
        if (r.title() == null && r.templateId() == null) {
            throw ApiException.invalidField("title", "A change needs a title");
        }
        Change c = new Change();
        c.setNumber("CHG-" + changes.nextNumber());
        c.setType(type);
        c.setRequester(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        c.setCreatedAt(clock.instant());
        if (type == Change.Type.STANDARD) {
            if (r.templateId() == null) {
                throw ApiException.invalidField("templateId", "Standard changes are created from a pre-approved template");
            }
            ChangeTemplate t = templates.findById(r.templateId()).filter(ChangeTemplate::isActive)
                    .orElseThrow(() -> ApiException.invalidField("templateId", "Template does not exist or is inactive"));
            c.setTemplate(t);
            c.setTitle(t.getName());
            c.setDescription(t.getDescription());
            c.setCategory(t.getCategory());
            c.setImplementationPlan(t.getImplementationPlan());
            c.setRollbackPlan(t.getRollbackPlan());
            c.setTestPlan(t.getTestPlan());
            for (Long sid : t.getServiceIds()) {
                services.findById(sid).ifPresent(s -> c.getServices().add(s));
            }
            if (r.plannedStart() != null && r.plannedEnd() == null) {
                c.setPlannedStart(r.plannedStart());
                c.setPlannedEnd(r.plannedStart().plus(Duration.ofMinutes(t.getDefaultDurationMinutes())));
            }
        } else if (r.templateId() != null) {
            throw ApiException.invalidField("templateId", "Only STANDARD changes use templates");
        }
        apply(c, r, actor, true);
        if (type == Change.Type.EMERGENCY) {
            c.setPirRequired(true);
        }
        reassess(c);
        changes.save(c);
        changes.flush();
        c.setAffectedCis(new ArrayList<>(ciLinks.sync(com.nexus360.cmdb.CiLinkService.Subject.CHANGE, c.getId(), r.ciIds(), c.getAffectedCis(), true)));
        record(c, actor, "CREATED", null, c.getStatus().name(), c.getType() + " change, risk " + c.getRisk());
        audit.record("CHANGE_CREATED", "CHANGE", c.getNumber(), Map.of("type", c.getType(), "risk", c.getRisk(), "title", c.getTitle()));
        publisher.publishEvent(new ChangeEvent(c.getId(), "CREATED", actor.depth()));
        return detail(c, actor);
    }

    @Transactional
    public ChangeDetail update(Long id, ChangeRequest r, Actor actor) {
        Change c = load(id);
        requireManage(c, actor);
        if (!ChangeStatus.EDITABLE.contains(c.getStatus())) {
            throw ApiException.conflict(c.getNumber() + " is " + c.getStatus() + " and can no longer be edited");
        }
        if (r.type() != null && r.type() != c.getType()) {
            throw ApiException.invalidField("type", "The type of a change cannot change; raise a new change instead");
        }
        apply(c, r, actor, false);
        c.setAffectedCis(new ArrayList<>(ciLinks.sync(com.nexus360.cmdb.CiLinkService.Subject.CHANGE, c.getId(), r.ciIds(), c.getAffectedCis(),
                r.affectedCis() != null)));
        reassess(c);
        c.setUpdatedAt(clock.instant());
        record(c, actor, "UPDATED", null, null, "Risk " + c.getRisk() + " (" + c.getRiskScore() + ")");
        audit.record("CHANGE_UPDATED", "CHANGE", c.getNumber(), Map.of("risk", c.getRisk()));
        return detail(c, actor);
    }

    private void apply(Change c, ChangeRequest r, Actor actor, boolean creating) {
        if (r.title() != null) {
            c.setTitle(r.title().trim());
        }
        if (r.description() != null || creating && c.getDescription() == null) {
            c.setDescription(r.description() == null ? c.getDescription() : r.description());
        }
        if (r.category() != null) {
            c.setCategory(r.category());
        }
        if (r.priority() != null) {
            c.setPriority(r.priority());
        }
        if (r.impact() != null) {
            c.setImpact(r.impact());
        }
        if (r.affectedUsers() != null) {
            c.setAffectedUsers(r.affectedUsers());
        }
        if (r.affectedCis() != null) {
            c.setAffectedCis(new ArrayList<>(r.affectedCis().stream().map(String::trim).distinct().toList()));
        }
        if (r.justification() != null) {
            c.setJustification(r.justification());
        }
        if (r.implementationPlan() != null) {
            c.setImplementationPlan(r.implementationPlan());
        }
        if (r.rollbackPlan() != null) {
            c.setRollbackPlan(r.rollbackPlan());
        }
        if (r.testPlan() != null) {
            c.setTestPlan(r.testPlan());
        }
        if (r.plannedStart() != null || r.plannedEnd() != null) {
            Instant start = r.plannedStart() != null ? r.plannedStart() : c.getPlannedStart();
            Instant end = r.plannedEnd() != null ? r.plannedEnd() : c.getPlannedEnd();
            validateWindow(start, end);
            c.setPlannedStart(start);
            c.setPlannedEnd(end);
        }
        if (r.serviceIds() != null) {
            Set<BusinessService> chosen = new LinkedHashSet<>();
            for (Long sid : r.serviceIds()) {
                chosen.add(services.findById(sid).orElseThrow(() -> ApiException.invalidField("serviceIds", "Service " + sid + " does not exist")));
            }
            c.getServices().clear();
            c.getServices().addAll(chosen);
        }
        if (r.teamId() != null) {
            c.setTeam(teamRouting.activeById(r.teamId()).orElseThrow(() -> ApiException.invalidField("teamId", "Team does not exist or is inactive")));
        } else if (creating && c.getTeam() == null) {
            c.getServices().stream().map(BusinessService::getDefaultTeam).filter(t -> t != null && t.isActive()).findFirst().ifPresent(c::setTeam);
        }
        if (r.assigneeId() != null) {
            User assignee = users.findById(r.assigneeId()).filter(u -> u.isActive() && u.getRole().isStaff())
                    .orElseThrow(() -> ApiException.invalidField("assigneeId", "The implementer must be active support staff"));
            if (c.getAssignee() == null || !c.getAssignee().getId().equals(assignee.getId())) {
                if (!creating) {
                    record(c, actor, "ASSIGNED", c.getAssignee() == null ? null : c.getAssignee().getFullName(), assignee.getFullName(), null);
                }
                c.setAssignee(assignee);
                notifications.notifyUser(assignee.getId(), "CHANGE_ASSIGNED", "Change assigned: " + c.getNumber(), c.getTitle(), link(c), Severity.INFO);
            }
        }
        if (r.problemId() != null) {
            c.setProblem(problems.findById(r.problemId()).orElseThrow(() -> ApiException.invalidField("problemId", "Problem does not exist")));
        }
        if (r.riskAnswers() != null) {
            c.setRiskAnswers(new LinkedHashMap<>(ChangeRisk.validate(r.riskAnswers())));
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public ChangeDetail transition(Long id, TransitionRequest r, Actor actor) {
        Change c = load(id);
        ChangeStatus from = c.getStatus();
        ChangeStatus to = r.status();
        if (from == to) {
            throw ApiException.conflict(c.getNumber() + " is already " + to);
        }
        if (!from.canMoveTo(to)) {
            throw ApiException.conflict("A change cannot move from " + from + " to " + to + " (allowed: " + from.next() + ")");
        }
        if (to == ChangeStatus.REJECTED || to == ChangeStatus.APPROVED && from == ChangeStatus.AWAITING_APPROVAL) {
            throw ApiException.conflict("Approval and rejection are decided through the approval engine (CAB); see /api/approvals");
        }
        boolean implementing = EnumSet.of(ChangeStatus.IMPLEMENTING, ChangeStatus.COMPLETED, ChangeStatus.FAILED, ChangeStatus.ROLLED_BACK).contains(to);
        if (implementing) {
            requireImplement(c, actor);
        } else {
            requireManage(c, actor);
        }
        Instant now = clock.instant();
        String note = r.reason();
        switch (to) {
            case SUBMITTED -> {
                submit(c, actor, now);
                return detail(c, actor);
            }
            case ASSESSMENT -> {
                if (r.riskAnswers() != null) {
                    c.getRiskAnswers().putAll(ChangeRisk.validate(r.riskAnswers()));
                }
                if (!ChangeRisk.complete(c.getRiskAnswers())) {
                    throw ApiException.invalidField("riskAnswers", "Answer every risk question before the assessment ("
                            + ChangeRisk.QUESTIONS.stream().map(ChangeRisk.Question::key).filter(k -> !c.getRiskAnswers().containsKey(k)).toList() + " missing)");
                }
                reassess(c);
                c.setRiskAssessedAt(now);
                note = "Risk " + c.getRisk() + " (score " + c.getRiskScore() + ")";
            }
            case AWAITING_APPROVAL -> {
                if (c.getPlannedStart() == null || c.getPlannedEnd() == null) {
                    throw ApiException.invalidField("plannedStart", "Plan the implementation window before requesting CAB approval");
                }
                setStatus(c, ChangeStatus.AWAITING_APPROVAL, actor, null);
                requestApproval(c, NORMAL_FLOW, actor);
                return detail(c, actor);
            }
            case SCHEDULED -> {
                Instant start = r.plannedStart() != null ? r.plannedStart() : c.getPlannedStart();
                Instant end = r.plannedEnd() != null ? r.plannedEnd() : c.getPlannedEnd();
                if (start == null || end == null) {
                    throw ApiException.invalidField("plannedStart", "Give the implementation window (plannedStart and plannedEnd)");
                }
                validateWindow(start, end);
                if (!end.isAfter(now)) {
                    throw ApiException.invalidField("plannedEnd", "The window must end in the future");
                }
                c.setPlannedStart(start);
                c.setPlannedEnd(end);
                note = checkSchedule(c, r, actor);
            }
            case IMPLEMENTING -> c.setActualStart(now);
            case COMPLETED, FAILED, ROLLED_BACK -> {
                if (to != ChangeStatus.COMPLETED && (r.outcomeNotes() == null || r.outcomeNotes().isBlank())) {
                    throw ApiException.invalidField("outcomeNotes", "Describe what happened (" + to + ")");
                }
                if (c.getActualStart() == null) {
                    c.setActualStart(now);
                }
                if (c.getActualEnd() == null) {
                    c.setActualEnd(now);
                }
                c.setClosedAt(now);
                if (r.outcomeNotes() != null && !r.outcomeNotes().isBlank()) {
                    c.setOutcomeNotes(c.getOutcomeNotes() == null || from != ChangeStatus.FAILED ? r.outcomeNotes().trim()
                            : c.getOutcomeNotes() + "\n\nRollback: " + r.outcomeNotes().trim());
                }
                note = r.outcomeNotes();
            }
            case CANCELLED -> {
                requireReason(r.reason(), "Give a reason for cancelling the change");
                approvals.cancelFor(Approval.Type.CHANGE, c.getId(), "Change cancelled: " + r.reason(), actor);
                c.setClosedAt(now);
                c.setOutcomeNotes("Cancelled: " + r.reason().trim());
            }
            case DRAFT -> {
                if (from == ChangeStatus.ASSESSMENT) {
                    requireReason(r.reason(), "Say what needs rework before sending the change back to draft");
                }
                c.setSubmittedAt(null);
            }
            case APPROVED -> note = r.reason() == null ? "Unscheduled" : r.reason();
            default -> { }
        }
        setStatus(c, to, actor, note);
        if (to == ChangeStatus.SCHEDULED) {
            notifyTeamMailbox(c, "Change scheduled: " + c.getNumber(), c.getTitle() + " is scheduled from " + c.getPlannedStart() + " to "
                    + c.getPlannedEnd() + ".");
        }
        return detail(c, actor);
    }

    private void submit(Change c, Actor actor, Instant now) {
        if (c.getServices().isEmpty()) {
            throw ApiException.invalidField("serviceIds", "Name at least one affected service before submitting");
        }
        if (c.getType() != Change.Type.STANDARD) {
            requireReason(c.getImplementationPlan(), "An implementation plan is required", "implementationPlan");
            requireReason(c.getRollbackPlan(), "A rollback plan is required", "rollbackPlan");
        }
        c.setSubmittedAt(now);
        reassess(c);
        setStatus(c, ChangeStatus.SUBMITTED, actor, null);
        switch (c.getType()) {
            case STANDARD -> {
                c.setApprovedAt(now);
                setStatus(c, ChangeStatus.APPROVED, Actor.SYSTEM, "Pre-approved standard change (template "
                        + (c.getTemplate() == null ? "-" : c.getTemplate().getCode()) + "); no CAB needed");
            }
            case EMERGENCY -> {
                setStatus(c, ChangeStatus.AWAITING_APPROVAL, Actor.SYSTEM, "Emergency change: expedited approval (ECAB)");
                requestApproval(c, EMERGENCY_FLOW, actor);
            }
            default -> { }
        }
    }

    /** Requests the CAB approval of a change through the approval engine (also used by the REQUEST_APPROVAL workflow action). */
    @Transactional
    public Approval requestApproval(Change c, String flowCode, Actor actor) {
        Actor requester = c.getRequester() == null ? actor : Actor.of(users.findById(c.getRequester().getId()).orElseThrow());
        String window = c.getPlannedStart() == null ? "window not planned" : c.getPlannedStart() + " to " + c.getPlannedEnd();
        Approval a = approvals.requestFlow(new ApprovalService.FlowRequest(Approval.Type.CHANGE, c.getId(), c.getNumber(),
                "Change " + c.getNumber() + ": " + c.getTitle(), c.getType() + " change, risk " + c.getRisk() + " (score " + c.getRiskScore() + "), " + window,
                Map.of("type", c.getType().name(), "risk", c.getRisk().name(), "riskScore", c.getRiskScore(),
                        "services", c.getServices().stream().map(BusinessService::getName).toList()),
                flowCode, null, facts(c), requester, c.isDemo(), link(c)));
        c.setApproval(a);
        record(c, actor, "APPROVAL_REQUESTED", null, a.getFlowName(), a.getStageCount() + " stage(s)"
                + (a.getStatus() == Approval.Status.PENDING ? "" : ", " + a.getStatus().name().toLowerCase(Locale.ROOT)));
        return a;
    }

    /** Workflow action REQUEST_APPROVAL on a change: only for changes that wait for approval steps (SUBMITTED, ASSESSMENT). */
    @Transactional
    public String requestApprovalFromWorkflow(Long changeId, String flowCode, Actor actor) {
        Change c = load(changeId);
        if (c.getStatus() != ChangeStatus.SUBMITTED && c.getStatus() != ChangeStatus.ASSESSMENT) {
            return null;
        }
        setStatus(c, ChangeStatus.AWAITING_APPROVAL, actor, "Approval requested by workflow");
        Approval a = requestApproval(c, flowCode, actor);
        return "Approval requested for " + c.getNumber() + " (" + a.getFlowName() + ")";
    }

    /** Approval engine outcome (called by {@link ChangeApprovalHandler} inside the decision transaction). */
    @Transactional
    public void onApprovalDecision(Approval approval, boolean approved, Actor approver) {
        Change c = changes.findWithDetailsById(approval.getReferenceId())
                .orElseThrow(() -> ApiException.notFound("Change", approval.getReferenceId()));
        if (c.getStatus() != ChangeStatus.AWAITING_APPROVAL) {
            throw ApiException.conflict(c.getNumber() + " is no longer awaiting approval");
        }
        Instant now = clock.instant();
        if (approved) {
            c.setApprovedAt(now);
            setStatus(c, ChangeStatus.APPROVED, approver, approval.getDecisionComment());
            notifyTeamMailbox(c, "Change approved: " + c.getNumber(), c.getTitle() + " was approved and can be scheduled.");
        } else {
            c.setOutcomeNotes("Rejected: " + approval.getDecisionComment());
            setStatus(c, ChangeStatus.REJECTED, approver, approval.getDecisionComment());
        }
    }

    /** Post-implementation review (required for emergency changes once they are finished). */
    @Transactional
    public ChangeDetail review(Long id, String notes, Actor actor) {
        Change c = load(id);
        requireManage(c, actor);
        if (!ChangeStatus.OUTCOMES.contains(c.getStatus())) {
            throw ApiException.conflict("A review is recorded after implementation (COMPLETED, FAILED or ROLLED_BACK)");
        }
        c.setPirNotes(notes.trim());
        c.setPirCompletedAt(clock.instant());
        c.setPirBy(users.getReferenceById(actor.id()));
        record(c, actor, "REVIEWED", null, null, notes);
        audit.record("CHANGE_REVIEWED", "CHANGE", c.getNumber(), Map.of("type", c.getType()));
        return detail(c, actor);
    }

    // ------------------------------------------------------------------ incidents

    @Transactional
    public ChangeDetail linkIncident(Long id, Long incidentId, ChangeIncident.Relation relation, Actor actor) {
        Change c = load(id);
        Incident i = incidents.findById(incidentId).orElseThrow(() -> ApiException.invalidField("incidentId", "Incident does not exist"));
        ChangeIncident link = incidentLinks.findById(new ChangeIncident.Key(id, incidentId)).orElseGet(ChangeIncident::new);
        link.setId(new ChangeIncident.Key(id, incidentId));
        link.setRelation(relation);
        link.setCreatedById(actor.id());
        link.setCreatedAt(clock.instant());
        incidentLinks.save(link);
        record(c, actor, "INCIDENT_LINKED", null, i.getNumber(), relation.name());
        audit.record("CHANGE_INCIDENT_LINKED", "CHANGE", c.getNumber(), Map.of("incident", i.getNumber(), "relation", relation));
        return detail(c, actor);
    }

    @Transactional
    public ChangeDetail unlinkIncident(Long id, Long incidentId, Actor actor) {
        Change c = load(id);
        ChangeIncident.Key key = new ChangeIncident.Key(id, incidentId);
        if (!incidentLinks.existsById(key)) {
            throw ApiException.notFound("Linked incident", incidentId);
        }
        incidentLinks.deleteById(key);
        record(c, actor, "INCIDENT_UNLINKED", String.valueOf(incidentId), null, null);
        audit.record("CHANGE_INCIDENT_UNLINKED", "CHANGE", c.getNumber(), Map.of("incident", incidentId));
        return detail(c, actor);
    }

    /** Incidents on the changed services created within {@code hours} after the change started (actual start, else planned). */
    @Transactional(readOnly = true)
    public Correlation correlation(Long id, Integer hours) {
        Change c = load(id);
        int h = hours == null ? settings.getInt("change.correlation.hours", 24) : Math.max(1, Math.min(hours, 168));
        List<LinkedIncident> linked = linkedIncidents(c);
        Instant start = c.getActualStart() != null ? c.getActualStart() : c.getPlannedStart();
        if (start == null || c.getServices().isEmpty()) {
            return new Correlation(start, start == null ? null : start.plus(Duration.ofHours(h)), h, linked, List.of());
        }
        Instant end = start.plus(Duration.ofHours(h));
        Set<Long> linkedIds = linked.stream().map(l -> l.incident().id()).collect(Collectors.toSet());
        List<Long> ids = new NamedParameterJdbcTemplate(jdbc).queryForList("select id from incidents where service_id in (:services) "
                + "and created_at >= :start and created_at < :end and parent_id is null order by created_at", Map.of(
                "services", c.getServices().stream().map(BusinessService::getId).toList(), "start", Timestamp.from(start), "end", Timestamp.from(end)), Long.class);
        List<Incident> found = ids.isEmpty() ? List.of() : incidents.findAllById(ids).stream().sorted(Comparator.comparing(Incident::getCreatedAt)).toList();
        List<CorrelatedIncident> correlated = new ArrayList<>();
        for (Incident i : found) {
            correlated.add(new CorrelatedIncident(incidentMapper.summary(i), Duration.between(start, i.getCreatedAt()).toMinutes(), linkedIds.contains(i.getId())));
        }
        return new Correlation(start, end, h, linked, correlated);
    }

    // ------------------------------------------------------------------ calendar, conflicts, freeze

    @Transactional(readOnly = true)
    public ChangeCalendar calendar(Instant from, Instant to, Long serviceId) {
        Instant now = clock.instant();
        Instant f = from == null ? now.minus(Duration.ofDays(7)) : from;
        Instant t = to == null ? f.plus(Duration.ofDays(37)) : to;
        if (!t.isAfter(f) || Duration.between(f, t).toDays() > 92) {
            throw ApiException.badRequest("The calendar range must be positive and at most 92 days");
        }
        Set<ChangeStatus> shown = EnumSet.of(ChangeStatus.SUBMITTED, ChangeStatus.ASSESSMENT, ChangeStatus.AWAITING_APPROVAL, ChangeStatus.APPROVED,
                ChangeStatus.SCHEDULED, ChangeStatus.IMPLEMENTING, ChangeStatus.COMPLETED, ChangeStatus.FAILED, ChangeStatus.ROLLED_BACK);
        List<Change> list = changes.findOverlapping(shown, f, t).stream()
                .filter(c -> serviceId == null || c.getServices().stream().anyMatch(s -> s.getId().equals(serviceId))).toList();
        List<ChangeFreezeWindow> windows = freezes.findOverlapping(f, t);
        List<CalendarConflict> conflicts = new ArrayList<>();
        Map<Long, List<String>> conflictsOf = new HashMap<>();
        List<Change> planned = list.stream().filter(c -> ChangeStatus.PLANNED.contains(c.getStatus())).toList();
        for (int i = 0; i < planned.size(); i++) {
            for (int j = i + 1; j < planned.size(); j++) {
                Change a = planned.get(i);
                Change b = planned.get(j);
                Conflict x = conflictBetween(a, b, a.getPlannedStart(), a.getPlannedEnd());
                if (x != null) {
                    conflicts.add(new CalendarConflict(a.getNumber(), b.getNumber(), x.services(), x.cis(), max(a.getPlannedStart(), b.getPlannedStart()),
                            min(a.getPlannedEnd(), b.getPlannedEnd())));
                    conflictsOf.computeIfAbsent(a.getId(), k -> new ArrayList<>()).add(b.getNumber());
                    conflictsOf.computeIfAbsent(b.getId(), k -> new ArrayList<>()).add(a.getNumber());
                }
            }
        }
        List<CalendarEntry> entries = list.stream().map(c -> new CalendarEntry(c.getId(), c.getNumber(), c.getTitle(), c.getType(), c.getStatus(),
                c.getRisk(), c.getPlannedStart(), c.getPlannedEnd(), c.getActualStart(), c.getActualEnd(),
                c.getServices().stream().map(s -> new ServiceRef(s.getId(), s.getCode(), s.getName())).toList(),
                conflictsOf.getOrDefault(c.getId(), List.of()), !freezesFor(c, c.getPlannedStart(), c.getPlannedEnd(), windows).isEmpty())).toList();
        return new ChangeCalendar(f, t, entries, windows.stream().map(ChangeService::freezeDto).toList(), conflicts);
    }

    /** Other planned changes overlapping the window on a shared service or CI. */
    @Transactional(readOnly = true)
    public List<Conflict> conflicts(Change c, Instant start, Instant end) {
        if (start == null || end == null) {
            return List.of();
        }
        List<Conflict> out = new ArrayList<>();
        for (Change other : changes.findOverlapping(ChangeStatus.PLANNED, start, end)) {
            if (!other.getId().equals(c.getId())) {
                Conflict x = conflictBetween(c, other, start, end);
                if (x != null) {
                    out.add(x);
                }
            }
        }
        return out;
    }

    private static Conflict conflictBetween(Change c, Change other, Instant start, Instant end) {
        if (other.getPlannedStart() == null || other.getPlannedEnd() == null || !other.getPlannedStart().isBefore(end)
                || !other.getPlannedEnd().isAfter(start)) {
            return null;
        }
        Set<Long> mine = c.getServices().stream().map(BusinessService::getId).collect(Collectors.toSet());
        List<String> sharedServices = other.getServices().stream().filter(s -> mine.contains(s.getId())).map(BusinessService::getName).toList();
        Set<String> myCis = c.getAffectedCis().stream().map(x -> x.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<String> sharedCis = other.getAffectedCis().stream().filter(x -> myCis.contains(x.toLowerCase(Locale.ROOT))).toList();
        if (sharedServices.isEmpty() && sharedCis.isEmpty()) {
            return null;
        }
        return new Conflict(other.getId(), other.getNumber(), other.getTitle(), other.getStatus(), other.getPlannedStart(), other.getPlannedEnd(),
                sharedServices, sharedCis);
    }

    private List<ChangeFreezeWindow> freezesFor(Change c, Instant start, Instant end, List<ChangeFreezeWindow> candidates) {
        if (start == null || end == null) {
            return List.of();
        }
        Set<Long> mine = c.getServices().stream().map(BusinessService::getId).collect(Collectors.toSet());
        return candidates.stream().filter(w -> w.isActive() && w.getStartsAt().isBefore(end) && w.getEndsAt().isAfter(start))
                .filter(w -> w.getServiceIds().isEmpty() || w.getServiceIds().stream().anyMatch(mine::contains)).toList();
    }

    /** Conflicts and freeze windows for scheduling; returns the history note. */
    private String checkSchedule(Change c, TransitionRequest r, Actor actor) {
        List<String> notes = new ArrayList<>();
        List<ChangeFreezeWindow> frozen = freezesFor(c, c.getPlannedStart(), c.getPlannedEnd(), freezes.findOverlapping(c.getPlannedStart(), c.getPlannedEnd()));
        if (!frozen.isEmpty()) {
            String names = frozen.stream().map(ChangeFreezeWindow::getName).collect(Collectors.joining(", "));
            if (c.getType() != Change.Type.EMERGENCY && settings.getBoolean("change.freeze.enforce", true)) {
                throw ApiException.conflict("The window falls into the change freeze “" + names + "”; only emergency changes may be scheduled then");
            }
            notes.add("Inside change freeze: " + names);
        }
        List<Conflict> conflicts = conflicts(c, c.getPlannedStart(), c.getPlannedEnd());
        if (!conflicts.isEmpty()) {
            String list = conflicts.stream().map(x -> x.number() + " (" + String.join(", ", concat(x.services(), x.cis())) + ")").collect(Collectors.joining("; "));
            if (!Boolean.TRUE.equals(r.overrideConflicts())) {
                throw ApiException.conflict("Scheduling conflict with " + list + ". Choose another window or override (change approvers, with a reason).");
            }
            if (!APPROVERS.contains(actor.role())) {
                throw ApiException.forbidden("Only change approvers can schedule over a conflict");
            }
            requireReason(r.overrideReason(), "Give a reason for scheduling over the conflict", "overrideReason");
            c.setConflictOverrideReason(r.overrideReason().trim());
            notes.add("Conflict overridden (" + list + "): " + r.overrideReason().trim());
        }
        return notes.isEmpty() ? "Window " + c.getPlannedStart() + " to " + c.getPlannedEnd() : String.join("; ", notes);
    }

    // ------------------------------------------------------------------ metrics

    @Transactional(readOnly = true)
    public ChangeMetrics metrics(Integer days) {
        int d = days == null ? 90 : Math.max(1, Math.min(days, 730));
        Instant to = clock.instant();
        Instant from = to.minus(Duration.ofDays(d));
        Timestamp f = Timestamp.from(from);
        Map<String, Long> byType = zeroes(Change.Type.values());
        jdbc.query("select change_type, count(*) from changes where created_at >= ? group by change_type", rs -> {
            byType.put(rs.getString(1), rs.getLong(2));
        }, f);
        Map<String, Long> byStatus = zeroes(ChangeStatus.values());
        jdbc.query("select status, count(*) from changes where created_at >= ? group by status", rs -> {
            byStatus.put(rs.getString(1), rs.getLong(2));
        }, f);
        Map<String, Long> byRisk = zeroes(Change.Risk.values());
        jdbc.query("select risk, count(*) from changes where created_at >= ? group by risk", rs -> {
            byRisk.put(rs.getString(1), rs.getLong(2));
        }, f);
        long created = byType.values().stream().mapToLong(Long::longValue).sum();
        long completed = num("select count(*) from changes where status = 'COMPLETED' and closed_at >= ?", f);
        long failed = num("select count(*) from changes where status = 'FAILED' and closed_at >= ?", f);
        long rolledBack = num("select count(*) from changes where status = 'ROLLED_BACK' and closed_at >= ?", f);
        long outcomes = completed + failed + rolledBack;
        long emergency = byType.getOrDefault("EMERGENCY", 0L);
        Double approvalHours = jdbc.queryForObject("select avg(extract(epoch from (approved_at - submitted_at)) / 3600.0) from changes "
                + "where approved_at is not null and submitted_at is not null and approved_at >= ?", Double.class, f);
        Double leadHours = jdbc.queryForObject("select avg(extract(epoch from (actual_start - created_at)) / 3600.0) from changes "
                + "where actual_start is not null and actual_start >= ?", Double.class, f);
        Long onTimeBase = jdbc.queryForObject("select count(*) from changes where actual_end is not null and planned_end is not null and closed_at >= ?",
                Long.class, f);
        Long onTime = jdbc.queryForObject("select count(*) from changes where actual_end is not null and planned_end is not null and actual_end <= planned_end "
                + "and closed_at >= ?", Long.class, f);
        int hours = settings.getInt("change.correlation.hours", 24);
        long withIncidents = num("select count(distinct c.id) from changes c join change_services cs on cs.change_id = c.id "
                + "join incidents i on i.service_id = cs.service_id and i.parent_id is null and not i.simulated and i.created_at >= coalesce(c.actual_start, c.planned_start) "
                + "and i.created_at < coalesce(c.actual_start, c.planned_start) + make_interval(hours => ?) "
                + "where c.status in ('COMPLETED', 'FAILED', 'ROLLED_BACK') and c.closed_at >= ?", hours, f);
        long reviewsOutstanding = num("select count(*) from changes where pir_required and pir_completed_at is null "
                + "and status in ('COMPLETED', 'FAILED', 'ROLLED_BACK')");
        return new ChangeMetrics(LocalDate.ofInstant(from, ZoneOffset.UTC), LocalDate.ofInstant(to, ZoneOffset.UTC), created, byType, byStatus, byRisk,
                completed, failed, rolledBack, rate(completed, outcomes), rate(failed, outcomes), rate(rolledBack, outcomes), rate(emergency, created),
                round(approvalHours), round(leadHours), rate(onTime == null ? 0 : onTime, onTimeBase == null ? 0 : onTimeBase), withIncidents, reviewsOutstanding);
    }

    // ------------------------------------------------------------------ templates and freeze windows

    @Transactional(readOnly = true)
    public List<TemplateDto> templates(Boolean active) {
        return templates.findAllByOrderByNameAsc().stream().filter(t -> active == null || t.isActive() == active).map(ChangeService::templateDto).toList();
    }

    @Transactional
    public TemplateDto saveTemplate(Long id, TemplateRequest r, Actor actor) {
        ChangeTemplate t = id == null ? new ChangeTemplate() : templates.findById(id).orElseThrow(() -> ApiException.notFound("Change template", id));
        if (id == null && templates.existsByCodeIgnoreCase(r.code())) {
            throw ApiException.conflict("Template code " + r.code() + " is already in use");
        }
        if (id != null && !t.getCode().equalsIgnoreCase(r.code())) {
            throw ApiException.invalidField("code", "The code of a template cannot change");
        }
        List<Long> serviceIds = r.serviceIds() == null ? List.of() : r.serviceIds().stream().distinct().toList();
        for (Long sid : serviceIds) {
            if (!services.existsById(sid)) {
                throw ApiException.invalidField("serviceIds", "Service " + sid + " does not exist");
            }
        }
        t.setCode(r.code());
        t.setName(r.name().trim());
        t.setDescription(r.description());
        t.setCategory(r.category());
        t.setImplementationPlan(r.implementationPlan());
        t.setRollbackPlan(r.rollbackPlan());
        t.setTestPlan(r.testPlan());
        t.setDefaultDurationMinutes(r.defaultDurationMinutes() == null ? 60 : r.defaultDurationMinutes());
        t.setServiceIds(new ArrayList<>(serviceIds));
        t.setActive(r.active() == null || r.active());
        if (id == null && !actor.isSystem()) {
            t.setCreatedBy(users.getReferenceById(actor.id()));
        }
        templates.save(t);
        audit.record(id == null ? "CHANGE_TEMPLATE_CREATED" : "CHANGE_TEMPLATE_UPDATED", "CHANGE_TEMPLATE", t.getCode(), Map.of("name", t.getName()));
        return templateDto(t);
    }

    @Transactional(readOnly = true)
    public List<FreezeDto> freezeWindows(boolean includePast) {
        Instant since = includePast ? Instant.EPOCH : clock.instant().minus(Duration.ofDays(1));
        return freezes.findEndingAfter(since).stream().map(ChangeService::freezeDto).toList();
    }

    @Transactional
    public FreezeDto saveFreeze(Long id, FreezeRequest r, Actor actor) {
        ChangeFreezeWindow w = id == null ? new ChangeFreezeWindow() : freezes.findById(id).orElseThrow(() -> ApiException.notFound("Freeze window", id));
        if (!r.endsAt().isAfter(r.startsAt())) {
            throw ApiException.invalidField("endsAt", "The freeze must end after it starts");
        }
        if (Duration.between(r.startsAt(), r.endsAt()).toDays() > 62) {
            throw ApiException.invalidField("endsAt", "A freeze window can last at most 62 days");
        }
        List<Long> serviceIds = r.serviceIds() == null ? List.of() : r.serviceIds().stream().distinct().toList();
        for (Long sid : serviceIds) {
            if (!services.existsById(sid)) {
                throw ApiException.invalidField("serviceIds", "Service " + sid + " does not exist");
            }
        }
        w.setName(r.name().trim());
        w.setReason(r.reason());
        w.setStartsAt(r.startsAt());
        w.setEndsAt(r.endsAt());
        w.setServiceIds(new ArrayList<>(serviceIds));
        w.setActive(r.active() == null || r.active());
        if (id == null && !actor.isSystem()) {
            w.setCreatedBy(users.getReferenceById(actor.id()));
        }
        freezes.save(w);
        audit.record(id == null ? "CHANGE_FREEZE_CREATED" : "CHANGE_FREEZE_UPDATED", "CHANGE_FREEZE", w.getId(),
                Map.of("name", w.getName(), "startsAt", w.getStartsAt().toString(), "endsAt", w.getEndsAt().toString()));
        return freezeDto(w);
    }

    @Transactional
    public void deleteFreeze(Long id) {
        ChangeFreezeWindow w = freezes.findById(id).orElseThrow(() -> ApiException.notFound("Freeze window", id));
        freezes.delete(w);
        audit.record("CHANGE_FREEZE_DELETED", "CHANGE_FREEZE", id, Map.of("name", w.getName()));
    }

    // ------------------------------------------------------------------ extension helpers (workflow, My Work, maintenance)

    /** Facts of a change for workflow conditions and conditional approval stages ({@code changeRisk}, {@code changeType}, ...). */
    public static Map<String, String> facts(Change c) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("changeRisk", c.getRisk().name());
        f.put("changeType", c.getType().name());
        f.put("riskScore", String.valueOf(c.getRiskScore()));
        f.put("status", c.getStatus().name());
        f.put("priority", c.getPriority().name());
        f.put("impact", c.getImpact().name());
        f.put("category", c.getCategory() == null ? "" : c.getCategory().name());
        List<BusinessService> svc = c.getServices().stream().sorted(Comparator.comparing(BusinessService::getName)).toList();
        f.put("serviceCode", svc.isEmpty() ? "" : svc.get(0).getCode());
        f.put("serviceCodes", svc.stream().map(BusinessService::getCode).collect(Collectors.joining(",")));
        f.put("teamCode", c.getTeam() == null ? "" : c.getTeam().getCode());
        f.put("assigneeEmail", c.getAssignee() == null ? "" : c.getAssignee().getEmail());
        f.put("title", c.getTitle());
        f.put("affectedUsers", String.valueOf(c.getAffectedUsers()));
        f.put("source", "CHANGE");
        return f;
    }

    @Transactional(readOnly = true)
    public List<ChangeSummary> workFor(Long userId, int limit) {
        return summaries(changes.findWorkFor(userId, WORK, ChangeStatus.OUTCOMES, PageRequest.of(0, limit)));
    }

    @Transactional(readOnly = true)
    public long workCount(Long userId) {
        return changes.countWorkFor(userId, WORK, ChangeStatus.OUTCOMES);
    }

    // ------------------------------------------------------------------ helpers

    Change load(Long id) {
        return changes.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Change", id));
    }

    private void reassess(Change c) {
        long failed = 0;
        if (!c.getServices().isEmpty()) {
            Long n = new NamedParameterJdbcTemplate(jdbc).queryForObject("select count(distinct c.id) from changes c join change_services cs on cs.change_id = c.id "
                    + "where cs.service_id in (:services) and c.status in ('FAILED', 'ROLLED_BACK') and c.closed_at >= :since and c.id <> :id",
                    Map.of("services", c.getServices().stream().map(BusinessService::getId).toList(), "since",
                            Timestamp.from(clock.instant().minus(Duration.ofDays(90))), "id", c.getId() == null ? -1L : c.getId()), Long.class);
            failed = n == null ? 0 : n;
        }
        ChangeRisk.Assessment a = ChangeRisk.assess(c.getType(), c.getRiskAnswers(), c.getServices(), c.getPlannedStart(), zone(), failed);
        c.setRisk(a.risk());
        c.setRiskScore(a.score());
        c.setRiskFactors(new ArrayList<>(a.factors()));
        if (c.getImpact() == Level.LOW && "ALL".equals(c.getRiskAnswers().get("userImpact"))) {
            c.setImpact(Level.HIGH);
        }
    }

    private void setStatus(Change c, ChangeStatus to, Actor actor, String note) {
        ChangeStatus from = c.getStatus();
        c.setStatus(to);
        c.setUpdatedAt(clock.instant());
        record(c, actor, "STATUS_CHANGED", from.name(), to.name(), note);
        audit.record("CHANGE_STATUS_CHANGED", "CHANGE", c.getNumber(), Map.of("from", from, "to", to));
        Set<Long> recipients = new LinkedHashSet<>();
        if (c.getRequester() != null) {
            recipients.add(c.getRequester().getId());
        }
        if (c.getAssignee() != null) {
            recipients.add(c.getAssignee().getId());
        }
        notifications.notifyUsers(recipients, "CHANGE_UPDATED", c.getNumber() + " is now " + to.name().replace('_', ' ').toLowerCase(Locale.ROOT),
                c.getTitle() + (note == null || note.isBlank() ? "" : " — " + note), link(c),
                to == ChangeStatus.FAILED || to == ChangeStatus.REJECTED ? Severity.WARNING : Severity.INFO);
        publisher.publishEvent(new ChangeEvent(c.getId(), "STATUS_CHANGED", actor.depth()));
    }

    private void notifyTeamMailbox(Change c, String subject, String text) {
        if (c.getTeam() == null || c.getTeam().getEmail() == null || c.getTeam().getEmail().isBlank()) {
            return;
        }
        email.queue(EmailService.Request.of(EmailService.NOTIFICATION, c.getTeam().getEmail(), null, subject,
                new EmailTemplates.Content(subject, List.of(text), List.of(new EmailTemplates.Fact("Change", c.getNumber()),
                        new EmailTemplates.Fact("Risk", c.getRisk().name()), new EmailTemplates.Fact("Window",
                                c.getPlannedStart() == null ? "not planned" : c.getPlannedStart() + " to " + c.getPlannedEnd())),
                        "Open in SA", email.link(link(c)), "You receive this because your team is assigned to this change."), false));
    }

    private ChangeDetail detail(Change c, Actor actor) {
        List<Conflict> conflicts = ChangeStatus.CLOSED.contains(c.getStatus()) ? List.of() : conflicts(c, c.getPlannedStart(), c.getPlannedEnd());
        List<FreezeRef> frozen = c.getPlannedStart() == null ? List.of() : freezesFor(c, c.getPlannedStart(), c.getPlannedEnd(),
                freezes.findOverlapping(c.getPlannedStart(), c.getPlannedEnd())).stream()
                .map(w -> new FreezeRef(w.getId(), w.getName(), w.getStartsAt(), w.getEndsAt())).toList();
        List<DeploymentRef> deployments = jdbc.query("select id, number, environment, version, status, started_at from deployments where change_id = ? "
                + "order by started_at desc", (rs, n) -> new DeploymentRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getTimestamp(6).toInstant()), c.getId());
        List<ChangeStatus> allowed = c.getStatus().next().stream().filter(s -> !(s == ChangeStatus.REJECTED
                || s == ChangeStatus.APPROVED && c.getStatus() == ChangeStatus.AWAITING_APPROVAL)).toList();
        return new ChangeDetail(c.getId(), c.getNumber(), c.getTitle(), c.getDescription(), c.getType(), c.getStatus(), c.getCategory(), c.getPriority(),
                c.getRisk(), c.getRiskScore(), c.getRiskAnswers(), c.getRiskFactors(), c.getRiskAssessedAt(), c.getImpact(), c.getAffectedUsers(),
                c.getAffectedCis(), c.getJustification(), c.getImplementationPlan(), c.getRollbackPlan(), c.getTestPlan(), c.getPlannedStart(),
                c.getPlannedEnd(), c.getActualStart(), c.getActualEnd(),
                c.getServices().stream().map(s -> new ServiceRef(s.getId(), s.getCode(), s.getName())).toList(),
                c.getRequester() == null ? null : c.getRequester().getId(), c.getRequester() == null ? null : c.getRequester().getFullName(),
                c.getAssignee() == null ? null : c.getAssignee().getId(), c.getAssignee() == null ? null : c.getAssignee().getFullName(),
                c.getTeam() == null ? null : c.getTeam().getId(), c.getTeam() == null ? null : c.getTeam().getName(),
                c.getTemplate() == null ? null : c.getTemplate().getId(), c.getTemplate() == null ? null : c.getTemplate().getName(),
                c.getApproval() == null ? null : approvals.dto(c.getApproval(), actor), c.getProblem() == null ? null : c.getProblem().getId(),
                c.getProblem() == null ? null : c.getProblem().getNumber(), c.getOutcomeNotes(), c.isPirRequired(), c.getPirNotes(), c.getPirCompletedAt(),
                c.getPirBy() == null ? null : c.getPirBy().getFullName(), c.getConflictOverrideReason(), c.getSubmittedAt(), c.getApprovedAt(),
                c.getClosedAt(), conflicts, frozen, linkedIncidents(c), deployments, allowed, c.getCreatedAt(), c.getUpdatedAt(), c.isDemo(),
                ciLinks.refs(com.nexus360.cmdb.CiLinkService.Subject.CHANGE, c.getId()));
    }

    private List<LinkedIncident> linkedIncidents(Change c) {
        List<ChangeIncident> list = incidentLinks.findByChange(c.getId());
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Incident> byId = incidents.findAllById(list.stream().map(l -> l.getId().getIncidentId()).toList()).stream()
                .collect(Collectors.toMap(Incident::getId, i -> i));
        return list.stream().filter(l -> byId.containsKey(l.getId().getIncidentId()))
                .map(l -> new LinkedIncident(l.getRelation(), incidentMapper.summary(byId.get(l.getId().getIncidentId())))).toList();
    }

    private Map<Long, List<ServiceRef>> serviceRefs(List<Long> ids) {
        Map<Long, List<ServiceRef>> out = new HashMap<>();
        new NamedParameterJdbcTemplate(jdbc).query("select cs.change_id, s.id, s.code, s.name from change_services cs join business_services s "
                + "on s.id = cs.service_id where cs.change_id in (:ids) order by s.name", Map.of("ids", ids), rs -> {
            out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new ServiceRef(rs.getLong(2), rs.getString(3), rs.getString(4)));
        });
        return out;
    }

    private void requireManage(Change c, Actor actor) {
        if (!canManage(c, actor)) {
            throw ApiException.forbidden("Only change editors (or the incident manager who raised this emergency change) can do this");
        }
    }

    private void requireImplement(Change c, Actor actor) {
        boolean assignee = c.getAssignee() != null && c.getAssignee().getId().equals(actor.id());
        if (!assignee && !canManage(c, actor)) {
            throw ApiException.forbidden("Only the implementer or a change editor can do this");
        }
    }

    static boolean canManage(Change c, Actor actor) {
        return EDITORS.contains(actor.role()) || actor.role() == Role.INCIDENT_MANAGER && c.getType() == Change.Type.EMERGENCY
                && c.getRequester() != null && c.getRequester().getId().equals(actor.id());
    }

    private static void validateWindow(Instant start, Instant end) {
        if (start == null || end == null) {
            throw ApiException.invalidField("plannedEnd", "Give both plannedStart and plannedEnd");
        }
        if (!end.isAfter(start)) {
            throw ApiException.invalidField("plannedEnd", "The window must end after it starts");
        }
        if (Duration.between(start, end).toDays() > 14) {
            throw ApiException.invalidField("plannedEnd", "A change window can last at most 14 days");
        }
    }

    private static void requireReason(String value, String message) {
        requireReason(value, message, "reason");
    }

    private static void requireReason(String value, String message, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.invalidField(field, message);
        }
    }

    private void record(Change c, Actor actor, String action, String from, String to, String note) {
        ChangeHistory h = new ChangeHistory();
        h.setChangeId(c.getId());
        h.setActorId(actor.id());
        h.setActorName(actor.name());
        h.setAction(action);
        h.setFromValue(truncate(from, 200));
        h.setToValue(truncate(to, 200));
        h.setNote(truncate(note, 1000));
        h.setCreatedAt(clock.instant());
        history.save(h);
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(settings.getString(SettingsService.ORG_TIMEZONE, "Asia/Kolkata"));
        } catch (DateTimeException e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }

    static String link(Change c) {
        return "/changes/" + c.getId();
    }

    private long num(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    private static Map<String, Long> zeroes(Enum<?>[] values) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Enum<?> v : values) {
            m.put(v.name(), 0L);
        }
        return m;
    }

    private static Double rate(long part, long whole) {
        return whole == 0 ? null : Math.round(1000.0 * part / whole) / 10.0;
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static TemplateDto templateDto(ChangeTemplate t) {
        return new TemplateDto(t.getId(), t.getCode(), t.getName(), t.getDescription(), t.getCategory(), t.getImplementationPlan(), t.getRollbackPlan(),
                t.getTestPlan(), t.getDefaultDurationMinutes(), t.getServiceIds(), t.isActive(), t.isDemo());
    }

    static FreezeDto freezeDto(ChangeFreezeWindow w) {
        return new FreezeDto(w.getId(), w.getName(), w.getReason(), w.getStartsAt(), w.getEndsAt(), w.getServiceIds(), w.isActive(), w.isDemo());
    }

    static Sort sortOf(String sort, Set<String> allowed, Sort fallback) {
        if (sort == null || sort.isBlank()) {
            return fallback;
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!allowed.contains(field)) {
            throw ApiException.badRequest("Cannot sort by '" + field + "'");
        }
        return Sort.by(parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.ASC : Sort.Direction.DESC, field);
    }
}
