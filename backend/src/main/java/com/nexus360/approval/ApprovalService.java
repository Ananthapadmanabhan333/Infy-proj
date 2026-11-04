package com.nexus360.approval;

import com.nexus360.approval.ApprovalModel.ApproverDef;
import com.nexus360.approval.ApprovalModel.ApproverKind;
import com.nexus360.approval.ApprovalModel.EscalationDef;
import com.nexus360.approval.ApprovalModel.Quorum;
import com.nexus360.approval.ApprovalModel.StageDef;
import com.nexus360.approval.ApprovalModel.StageMode;
import com.nexus360.approval.ApprovalModel.StageStatus;
import com.nexus360.approval.ApprovalModel.TaskStatus;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.common.JsonMaps;
import com.nexus360.common.PageResponse;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.Actor;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRepository;
import com.nexus360.team.TeamRouting;
import com.nexus360.user.Department;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import com.nexus360.workflow.ConditionEvaluator;
import com.nexus360.workflow.WorkflowModel;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approval engine v2 (docs/RELEASE3_SPEC.md §5 BE-2). Every approval consists of ordered stages with one task per approver
 * (see {@link ApprovalModel} for modes, quorum and conditions). Release 2 single-step approvals ({@link #request}) are a
 * one-stage approval with one role task, so existing handlers, endpoints and routing keep working unchanged.
 *
 * <p>Rules: the requester never decides a task of their own approval (segregation of duties); a person decides at most one
 * task per stage; a rejection needs a reason; a {@code SYSTEM_ADMIN} sees every approval and may decide any open task
 * (recorded as an administrator decision); delegation moves a task to another user; stages past their deadline are
 * escalated once ({@link #escalateStage}). Handlers ({@link ApprovalHandler}) run when the whole approval is decided.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    public static final String SOD_MESSAGE = "Segregation of duties: you cannot approve your own request";
    static final String DEFAULT_LINK = "/service-desk?tab=approvals";
    private static final Set<TaskStatus> OPEN_TASK = Set.of(TaskStatus.PENDING, TaskStatus.WAITING);

    // ------------------------------------------------------------------ DTOs

    /**
     * Release 2 fields first (unchanged), then the Release 3 engine fields. {@code canDecide}/{@code myTaskId} refer to the
     * caller (false/null where no caller context is available); {@code pendingWith} lists who the active stage waits for.
     */
    public record ApprovalDto(Long id, Approval.Type type, Long referenceId, String referenceNumber, String title, String details,
                              String requestedByName, Role approverRole, Approval.Status status, String decidedByName,
                              String decisionComment, Instant decidedAt, Instant createdAt, Map<String, Object> payload,
                              Long requestedById, String flowName, Integer currentStage, int stageCount, String currentStageName,
                              Instant dueAt, boolean overdue, String link, boolean canDecide, Long myTaskId, List<String> pendingWith,
                              boolean demo) {

        public static ApprovalDto of(Approval a) {
            return of(a, null, false, null, List.of(), null);
        }

        static ApprovalDto of(Approval a, String stageName, boolean canDecide, Long myTaskId, List<String> pendingWith, Instant now) {
            boolean overdue = now != null && a.getStatus() == Approval.Status.PENDING && a.getDueAt() != null && a.getDueAt().isBefore(now);
            return new ApprovalDto(a.getId(), a.getType(), a.getReferenceId(), a.getReferenceNumber(), a.getTitle(), a.getDetails(),
                    a.getRequestedBy() == null ? null : a.getRequestedBy().getFullName(), a.getApproverRole(), a.getStatus(),
                    a.getDecidedBy() == null ? null : a.getDecidedBy().getFullName(), a.getDecisionComment(), a.getDecidedAt(),
                    a.getCreatedAt(), a.getPayload(), a.getRequestedBy() == null ? null : a.getRequestedBy().getId(), a.getFlowName(),
                    a.getCurrentStage(), a.getStageCount(), stageName, a.getDueAt(), overdue, a.getLink(), canDecide, myTaskId, pendingWith,
                    a.isDemo());
        }
    }

    public record TaskDto(Long id, int seq, ApprovalTask.Kind approverKind, Long approverUserId, String approverUserName, Role approverRole,
                          Long approverTeamId, String approverTeamName, String approverLabel, TaskStatus status, Long decidedById,
                          String decidedByName, Instant decidedAt, String comment, Long delegatedFromId, String delegatedFromName,
                          Instant delegatedAt, boolean canDecide) {}

    public record StageDto(Long id, int order, String name, StageMode mode, Quorum quorum, int requiredCount, StageStatus status,
                           WorkflowModel.Conditions condition, Integer deadlineHours, Instant dueAt, EscalationDef escalation,
                           Instant escalatedAt, Instant startedAt, Instant completedAt, long approvedCount, List<TaskDto> tasks) {}

    public record EventDto(Long id, String type, Integer stageOrder, Long taskId, String actorName, String comment,
                           Map<String, Object> details, Instant at) {}

    public record ApprovalDetail(ApprovalDto approval, List<StageDto> stages, List<EventDto> history) {}

    /** One task in the caller's task inbox, with its approval. */
    public record TaskInboxItem(Long taskId, TaskStatus taskStatus, String approverLabel, int stageOrder, String stageName, Instant dueAt,
                                boolean overdue, boolean canDecide, ApprovalDto approval) {}

    public record InboxCounts(long pendingTasks, long overdueTasks, long requestedPending) {}

    public record DecisionRequest(boolean approved, @Size(max = 1000) String comment) {}

    public record DelegateRequest(@NotNull Long userId, @Size(max = 1000) String comment) {}

    public record DelegationRequest(@NotNull Long delegateId, @NotNull Instant startsAt, @NotNull Instant endsAt, @Size(max = 500) String reason) {}

    public record DelegationDto(Long id, Long delegatorId, String delegatorName, Long delegateId, String delegateName, Instant startsAt,
                                Instant endsAt, String reason, boolean active, Instant createdAt) {}

    /** Inbox filters: {@code scope} mine (default) | requested | all (admins and auditors). */
    public record Filter(Approval.Status status, Approval.Type type, String scope, String search, Boolean overdue) {}

    /**
     * A new multi-stage approval. Either {@code flowCode} (a stored, active flow) or explicit {@code stages}; {@code facts}
     * are the subject facts conditional stages are evaluated against (e.g. {@code changeRisk}).
     */
    public record FlowRequest(Approval.Type type, Long referenceId, String referenceNumber, String title, String details,
                              Map<String, Object> payload, String flowCode, List<StageDef> stages, Map<String, String> facts,
                              Actor requester, boolean demo, String link) {}

    private final ApprovalRepository approvals;
    private final ApprovalStageRepository stages;
    private final ApprovalTaskRepository tasks;
    private final ApprovalEventRepository events;
    private final ApprovalDelegationRepository delegations;
    private final ApprovalFlowRepository flows;
    private final UserRepository users;
    private final TeamRepository teams;
    private final TeamRouting teamRouting;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ObjectProvider<ApprovalHandler> handlers;
    private final Clock clock;

    // ------------------------------------------------------------------ requesting

    /** Release 2 single-step approval routed to one approver role (IT_MANAGER, INCIDENT_MANAGER or SYSTEM_ADMIN). */
    @Transactional
    public Approval request(Approval.Type type, Long referenceId, String referenceNumber, String title, String details,
                            Map<String, Object> payload, Role approverRole, Actor requester, boolean demo, String link) {
        Role role = routeTo(approverRole);
        StageDef single = new StageDef("Approval", StageMode.PARALLEL, Quorum.ANY, 1,
                List.of(new ApproverDef(ApproverKind.ROLE, null, role, null)), null, null, null);
        Approval approval = start(new FlowRequest(type, referenceId, referenceNumber, title, details, payload, null, List.of(single), Map.of(),
                requester, demo, link), true);
        approval.setApproverRole(role);
        notifications.notifyRoles(role == Role.SYSTEM_ADMIN ? List.of(Role.SYSTEM_ADMIN) : List.of(role, Role.SYSTEM_ADMIN),
                "APPROVAL_REQUESTED", "Approval required: " + approval.getTitle(), details == null ? approval.getTitle() : details,
                link == null ? DEFAULT_LINK : link, Severity.WARNING);
        return approval;
    }

    /** Multi-stage approval from a stored flow or explicit stages; may complete immediately when every stage is skipped. */
    @Transactional
    public Approval requestFlow(FlowRequest r) {
        return start(r, false);
    }

    private Approval start(FlowRequest r, boolean legacy) {
        List<StageDef> defs = r.stages();
        ApprovalFlow flow = null;
        if (defs == null || defs.isEmpty()) {
            flow = flows.findByCodeIgnoreCase(r.flowCode() == null ? "" : r.flowCode().trim())
                    .filter(ApprovalFlow::isActive)
                    .orElseThrow(() -> ApiException.badRequest("Approval flow '" + r.flowCode() + "' does not exist or is inactive"));
            defs = flow.getStages();
        }
        Instant now = clock.instant();
        Approval a = new Approval();
        a.setType(r.type());
        a.setReferenceId(r.referenceId());
        a.setReferenceNumber(r.referenceNumber());
        a.setTitle(r.title().length() > 250 ? r.title().substring(0, 249) : r.title());
        a.setDetails(r.details());
        a.setPayload(JsonMaps.normalize(r.payload()));
        a.setRequestedBy(r.requester().isSystem() ? null : users.getReferenceById(r.requester().id()));
        a.setDemo(r.demo());
        a.setLink(r.link());
        a.setFlowId(flow == null ? null : flow.getId());
        a.setFlowName(flow == null ? (legacy ? null : "Custom approval") : flow.getName());
        a.setStageCount(defs.size());
        a.setApproverRole(defs.stream().flatMap(d -> d.approvers().stream()).filter(x -> x.kind() == ApproverKind.ROLE)
                .map(ApproverDef::role).filter(Objects::nonNull).findFirst().orElse(null));
        Map<String, Object> facts = new LinkedHashMap<>();
        if (r.facts() != null) {
            r.facts().forEach((k, v) -> facts.put(k, v == null ? "" : v));
        }
        a.setFacts(facts);
        a.setCreatedAt(now);
        approvals.save(a);
        event(a, null, null, "REQUESTED", r.requester(), null, Map.of("stages", defs.size()));
        int order = 1;
        for (StageDef def : defs) {
            ApprovalStage s = new ApprovalStage();
            s.setApproval(a);
            s.setStageOrder(order++);
            s.setName(def.name());
            s.setMode(def.mode() == null ? StageMode.PARALLEL : def.mode());
            s.setQuorum(def.quorum() == null ? Quorum.ANY : def.quorum());
            s.setRequiredCount(s.getQuorum() == Quorum.N && def.requiredCount() != null ? Math.max(1, def.requiredCount()) : 1);
            s.setCondition(def.condition() == null || def.condition().rules() == null || def.condition().rules().isEmpty() ? null : def.condition());
            s.setDeadlineHours(def.deadlineHours());
            s.setEscalation(def.escalation());
            s.setStatus(StageStatus.WAITING);
            stages.save(s);
            int seq = 1;
            for (ApproverDef approver : def.approvers()) {
                tasks.save(newTask(a, s, seq++, approver, r.requester()));
            }
        }
        advance(a, 1, Actor.SYSTEM, "No approval stage applies", !legacy);
        audit.record("APPROVAL_REQUESTED", "APPROVAL", a.getId(), Map.of("type", r.type(), "reference", Objects.toString(r.referenceNumber(), ""),
                "flow", Objects.toString(a.getFlowName(), "single step"), "stages", defs.size()));
        return a;
    }

    private ApprovalTask newTask(Approval a, ApprovalStage s, int seq, ApproverDef def, Actor requester) {
        ApprovalTask t = new ApprovalTask();
        t.setApproval(a);
        t.setStage(s);
        t.setSeq(seq);
        t.setStatus(TaskStatus.WAITING);
        switch (def.kind()) {
            case USER -> {
                t.setApproverKind(ApprovalTask.Kind.USER);
                t.setApproverUser(users.findById(def.userId() == null ? -1L : def.userId()).filter(User::isActive)
                        .orElseThrow(() -> ApiException.badRequest("Approver user " + def.userId() + " does not exist or is inactive")));
            }
            case ROLE -> {
                t.setApproverKind(ApprovalTask.Kind.ROLE);
                t.setApproverRole(def.role() == null ? Role.IT_MANAGER : def.role());
            }
            case TEAM -> {
                t.setApproverKind(ApprovalTask.Kind.TEAM);
                t.setApproverTeam(teams.findById(def.teamId() == null ? -1L : def.teamId()).filter(Team::isActive)
                        .orElseThrow(() -> ApiException.badRequest("Approver team " + def.teamId() + " does not exist or is inactive")));
            }
            case MANAGER -> {
                User manager = requester.isSystem() ? null : users.findWithDepartmentById(requester.id()).map(User::getDepartment)
                        .map(Department::getManager).filter(User::isActive).orElse(null);
                if (manager != null && !manager.getId().equals(requester.id())) {
                    t.setApproverKind(ApprovalTask.Kind.USER);
                    t.setApproverUser(manager);
                } else {
                    // No (other) department manager: IT managers decide instead.
                    t.setApproverKind(ApprovalTask.Kind.ROLE);
                    t.setApproverRole(Role.IT_MANAGER);
                }
            }
        }
        return t;
    }

    /** Activates the first applicable stage from {@code fromOrder}; completes the approval when no stage is left. */
    private void advance(Approval a, int fromOrder, Actor actor, String comment, boolean notifyApprovers) {
        Map<String, String> facts = new HashMap<>();
        a.getFacts().forEach((k, v) -> facts.put(k, v == null ? "" : String.valueOf(v)));
        for (ApprovalStage s : stages.findByApprovalIdOrderByStageOrderAsc(a.getId())) {
            if (s.getStageOrder() < fromOrder || s.getStatus() != StageStatus.WAITING) {
                continue;
            }
            if (s.getCondition() != null && !ConditionEvaluator.evaluate(s.getCondition(), facts).matched()) {
                s.setStatus(StageStatus.SKIPPED);
                s.setCompletedAt(clock.instant());
                tasks.findByStageIdOrderBySeqAsc(s.getId()).forEach(t -> t.setStatus(TaskStatus.SKIPPED));
                event(a, s.getStageOrder(), null, "STAGE_SKIPPED", Actor.SYSTEM, "Condition not met", null);
                continue;
            }
            activate(a, s, notifyApprovers);
            return;
        }
        complete(a, true, actor, comment);
    }

    private void activate(Approval a, ApprovalStage s, boolean notifyApprovers) {
        Instant now = clock.instant();
        s.setStatus(StageStatus.ACTIVE);
        s.setStartedAt(now);
        s.setDueAt(s.getDeadlineHours() == null ? null : now.plus(Duration.ofHours(s.getDeadlineHours())));
        a.setCurrentStage(s.getStageOrder());
        a.setDueAt(s.getDueAt());
        event(a, s.getStageOrder(), null, "STAGE_STARTED", Actor.SYSTEM, s.getName(),
                s.getDueAt() == null ? null : Map.of("dueAt", s.getDueAt().toString()));
        List<ApprovalTask> list = tasks.findByStageIdOrderBySeqAsc(s.getId());
        for (ApprovalTask t : list) {
            if (t.getStatus() == TaskStatus.WAITING && (s.getMode() == StageMode.PARALLEL || t == list.get(0))) {
                open(a, s, t, notifyApprovers);
            }
        }
    }

    /** WAITING → PENDING; applies an active out-of-office delegation of a personal approver and notifies the approvers. */
    private void open(Approval a, ApprovalStage s, ApprovalTask t, boolean notify) {
        Instant now = clock.instant();
        t.setStatus(TaskStatus.PENDING);
        if (t.getApproverKind() == ApprovalTask.Kind.USER && t.getApproverUser() != null) {
            delegations.findActiveFor(t.getApproverUser().getId(), now).stream()
                    .filter(d -> d.getDelegate().isActive() && !isRequester(a, d.getDelegate().getId()))
                    .findFirst().ifPresent(d -> {
                        t.setDelegatedFrom(t.getApproverUser());
                        t.setDelegatedAt(now);
                        t.setApproverUser(d.getDelegate());
                        event(a, s.getStageOrder(), t.getId(), "DELEGATED", Actor.SYSTEM, "Out-of-office delegation"
                                + (d.getReason() == null ? "" : ": " + d.getReason()), Map.of("from", d.getDelegator().getFullName(),
                                "to", d.getDelegate().getFullName()));
                    });
        }
        if (notify) {
            Set<Long> recipients = recipients(t);
            if (a.getRequestedBy() != null) {
                recipients.remove(a.getRequestedBy().getId());
            }
            notifications.notifyUsers(recipients, "APPROVAL_REQUESTED", "Approval required: " + a.getTitle(),
                    s.getName() + (a.getStageCount() > 1 ? " (stage " + s.getStageOrder() + " of " + a.getStageCount() + ")" : "")
                            + (a.getDetails() == null ? "" : " — " + a.getDetails()), inboxLink(t), Severity.WARNING);
        }
    }

    // ------------------------------------------------------------------ deciding

    /**
     * Release 2 endpoint: decides the caller's open task in the active stage (user task first, then team, then role).
     * Check order (unchanged): routed to the caller (403), still pending (409), segregation of duties (403), reason (400).
     */
    @Transactional
    public ApprovalDto decide(Long id, boolean approved, String comment, Actor actor) {
        Approval approval = approvals.findById(id).orElseThrow(() -> ApiException.notFound("Approval", id));
        List<ApprovalTask> all = tasks.findByApprovalIdOrderByIdAsc(id);
        Context ctx = context(actor);
        if (!actor.hasRole(Role.SYSTEM_ADMIN) && all.stream().noneMatch(t -> routed(t, ctx))) {
            throw ApiException.forbidden(approval.getApproverRole() == null ? "This approval is not routed to you"
                    : "This approval is routed to " + approval.getApproverRole() + " approvers");
        }
        requireDecidable(approval, approved, comment, actor);
        Integer stage = approval.getCurrentStage();
        boolean decidedThisStage = all.stream().anyMatch(t -> t.getStage().getStageOrder() == (stage == null ? -1 : stage)
                && t.getDecidedBy() != null && t.getDecidedBy().getId().equals(actor.id()));
        if (decidedThisStage) {
            throw ApiException.conflict("You have already decided this stage");
        }
        ApprovalTask task = all.stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING && t.getStage().getStatus() == StageStatus.ACTIVE && eligible(t, ctx))
                .min(Comparator.comparingInt(t -> t.getApproverKind() == ApprovalTask.Kind.USER ? 0 : t.getApproverKind() == ApprovalTask.Kind.TEAM ? 1 : 2))
                .orElseThrow(() -> ApiException.forbidden("No approval task of this approval is waiting for you"));
        decideInternal(approval, task, approved, comment, actor);
        return dto(approval, actor);
    }

    /** Decides one task (task inbox). */
    @Transactional
    public ApprovalDto decideTask(Long taskId, boolean approved, String comment, Actor actor) {
        ApprovalTask task = tasks.findById(taskId).orElseThrow(() -> ApiException.notFound("Approval task", taskId));
        Approval approval = task.getApproval();
        Context ctx = context(actor);
        if (!eligible(task, ctx)) {
            throw ApiException.forbidden("This approval task is assigned to " + label(task));
        }
        requireDecidable(approval, approved, comment, actor);
        if (task.getStatus() != TaskStatus.PENDING || task.getStage().getStatus() != StageStatus.ACTIVE) {
            throw ApiException.conflict("This task is not waiting for a decision (" + task.getStatus() + ")");
        }
        boolean decidedThisStage = tasks.findByStageIdOrderBySeqAsc(task.getStage().getId()).stream()
                .anyMatch(t -> t.getDecidedBy() != null && t.getDecidedBy().getId().equals(actor.id()));
        if (decidedThisStage) {
            throw ApiException.conflict("You have already decided this stage");
        }
        decideInternal(approval, task, approved, comment, actor);
        return dto(approval, actor);
    }

    private void requireDecidable(Approval approval, boolean approved, String comment, Actor actor) {
        if (approval.getStatus() != Approval.Status.PENDING) {
            throw ApiException.conflict("This approval has already been " + approval.getStatus().name().toLowerCase(Locale.ROOT));
        }
        if (isRequester(approval, actor.id())) {
            throw ApiException.forbidden(SOD_MESSAGE);
        }
        if (!approved && (comment == null || comment.isBlank())) {
            throw ApiException.badRequest("Please give a reason when rejecting");
        }
    }

    private void decideInternal(Approval a, ApprovalTask task, boolean approved, String comment, Actor actor) {
        Instant now = clock.instant();
        String note = comment == null || comment.isBlank() ? null : comment.trim();
        task.setStatus(approved ? TaskStatus.APPROVED : TaskStatus.REJECTED);
        task.setDecidedBy(users.getReferenceById(actor.id()));
        task.setDecidedAt(now);
        task.setComment(note);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("approver", label(task));
        if (actor.hasRole(Role.SYSTEM_ADMIN) && !matchesIdentity(task, context(actor), false)) {
            details.put("administratorDecision", true);
        }
        ApprovalStage s = task.getStage();
        event(a, s.getStageOrder(), task.getId(), approved ? "TASK_APPROVED" : "TASK_REJECTED", actor, note, details);
        evaluateStage(a, s, actor, note);
    }

    private void evaluateStage(Approval a, ApprovalStage s, Actor actor, String comment) {
        List<ApprovalTask> list = tasks.findByStageIdOrderBySeqAsc(s.getId());
        List<ApprovalTask> live = list.stream().filter(t -> t.getStatus() != TaskStatus.SKIPPED && t.getStatus() != TaskStatus.CANCELLED).toList();
        long approvedCount = live.stream().filter(t -> t.getStatus() == TaskStatus.APPROVED).count();
        long open = live.stream().filter(t -> OPEN_TASK.contains(t.getStatus())).count();
        int required = required(s, live.size());
        Instant now = clock.instant();
        if (approvedCount >= required) {
            s.setStatus(StageStatus.APPROVED);
            s.setCompletedAt(now);
            list.stream().filter(t -> OPEN_TASK.contains(t.getStatus())).forEach(t -> t.setStatus(TaskStatus.SKIPPED));
            event(a, s.getStageOrder(), null, "STAGE_APPROVED", actor, s.getName(), Map.of("approvals", approvedCount, "required", required));
            advance(a, s.getStageOrder() + 1, actor, comment, true);
        } else if (approvedCount + open < required
                || s.getQuorum() == Quorum.ANY && live.stream().anyMatch(t -> t.getStatus() == TaskStatus.REJECTED)) {
            // ANY: the first decision decides the stage; ALL and N: rejected once the quorum can no longer be reached.
            s.setStatus(StageStatus.REJECTED);
            s.setCompletedAt(now);
            list.stream().filter(t -> OPEN_TASK.contains(t.getStatus())).forEach(t -> t.setStatus(TaskStatus.SKIPPED));
            event(a, s.getStageOrder(), null, "STAGE_REJECTED", actor, comment, Map.of("approvals", approvedCount, "required", required));
            complete(a, false, actor, comment);
        } else if (s.getMode() == StageMode.SEQUENTIAL && list.stream().noneMatch(t -> t.getStatus() == TaskStatus.PENDING)) {
            list.stream().filter(t -> t.getStatus() == TaskStatus.WAITING).findFirst().ifPresent(t -> open(a, s, t, true));
        }
    }

    private static int required(ApprovalStage s, int liveTasks) {
        return switch (s.getQuorum()) {
            case ANY -> 1;
            case ALL -> Math.max(1, liveTasks);
            case N -> Math.max(1, Math.min(s.getRequiredCount(), liveTasks));
        };
    }

    private void complete(Approval a, boolean approved, Actor actor, String comment) {
        Instant now = clock.instant();
        a.setStatus(approved ? Approval.Status.APPROVED : Approval.Status.REJECTED);
        a.setDecidedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        a.setDecidedAt(now);
        a.setDecisionComment(comment == null ? null : comment.trim());
        a.setDueAt(null);
        event(a, a.getCurrentStage(), null, approved ? "APPROVED" : "REJECTED", actor, comment, null);
        handlers.orderedStream().filter(h -> h.type() == a.getType()).forEach(h -> h.onDecision(a, approved, actor));
        if (a.getRequestedBy() != null) {
            notifications.notifyUser(a.getRequestedBy().getId(), "APPROVAL_DECIDED",
                    (approved ? "Approved: " : "Rejected: ") + a.getTitle(),
                    approved ? "Your request was approved by " + actor.name() + "." : "Rejected by " + actor.name() + ": " + comment,
                    a.getLink(), approved ? Severity.SUCCESS : Severity.WARNING);
        }
        audit.record(approved ? "APPROVAL_APPROVED" : "APPROVAL_REJECTED", "APPROVAL", a.getId(),
                Map.of("type", a.getType(), "reference", Objects.toString(a.getReferenceNumber(), "")));
    }

    /** Withdraws a pending approval (the subject was cancelled); handlers are not called. */
    @Transactional
    public void cancel(Approval a, String reason, Actor actor) {
        if (a == null || a.getStatus() != Approval.Status.PENDING) {
            return;
        }
        a.setStatus(Approval.Status.CANCELLED);
        a.setDueAt(null);
        for (ApprovalStage s : stages.findByApprovalIdOrderByStageOrderAsc(a.getId())) {
            if (s.getStatus() == StageStatus.ACTIVE || s.getStatus() == StageStatus.WAITING) {
                s.setStatus(StageStatus.CANCELLED);
                s.setCompletedAt(clock.instant());
            }
        }
        tasks.findByApprovalIdOrderByIdAsc(a.getId()).stream().filter(t -> OPEN_TASK.contains(t.getStatus()))
                .forEach(t -> t.setStatus(TaskStatus.CANCELLED));
        event(a, a.getCurrentStage(), null, "CANCELLED", actor, reason, null);
        audit.record("APPROVAL_CANCELLED", "APPROVAL", a.getId(), Map.of("type", a.getType(), "reference",
                Objects.toString(a.getReferenceNumber(), ""), "reason", Objects.toString(reason, "")));
    }

    /** Cancels the latest pending approval of a subject, if any. */
    @Transactional
    public void cancelFor(Approval.Type type, Long referenceId, String reason, Actor actor) {
        approvals.findFirstByTypeAndReferenceIdOrderByCreatedAtDesc(type, referenceId)
                .filter(a -> a.getStatus() == Approval.Status.PENDING).ifPresent(a -> cancel(a, reason, actor));
    }

    // ------------------------------------------------------------------ delegation

    /** Moves an open task to another user (the caller must be able to decide it). */
    @Transactional
    public ApprovalDto delegate(Long taskId, Long toUserId, String comment, Actor actor) {
        ApprovalTask task = tasks.findById(taskId).orElseThrow(() -> ApiException.notFound("Approval task", taskId));
        Approval a = task.getApproval();
        if (!eligible(task, context(actor))) {
            throw ApiException.forbidden("Only an approver of this task can delegate it");
        }
        if (a.getStatus() != Approval.Status.PENDING || !OPEN_TASK.contains(task.getStatus())) {
            throw ApiException.conflict("This task is no longer open");
        }
        User target = users.findById(toUserId).filter(User::isActive)
                .orElseThrow(() -> ApiException.invalidField("userId", "The delegate must be an active user"));
        if (isRequester(a, target.getId())) {
            throw ApiException.invalidField("userId", "Segregation of duties: the requester cannot approve their own request");
        }
        if (target.getId().equals(actor.id())) {
            throw ApiException.invalidField("userId", "You cannot delegate a task to yourself");
        }
        boolean decided = tasks.findByStageIdOrderBySeqAsc(task.getStage().getId()).stream()
                .anyMatch(t -> t.getDecidedBy() != null && t.getDecidedBy().getId().equals(target.getId())
                        || t.getStatus() == TaskStatus.PENDING && t.getApproverUser() != null && t.getApproverUser().getId().equals(target.getId())
                        && !t.getId().equals(task.getId()));
        if (decided) {
            throw ApiException.invalidField("userId", target.getFullName() + " already has a task in this stage");
        }
        String from = label(task);
        task.setDelegatedFrom(users.getReferenceById(actor.id()));
        task.setDelegatedAt(clock.instant());
        task.setApproverKind(ApprovalTask.Kind.USER);
        task.setApproverUser(target);
        task.setApproverRole(null);
        task.setApproverTeam(null);
        event(a, task.getStage().getStageOrder(), task.getId(), "DELEGATED", actor, comment, Map.of("from", from, "to", target.getFullName()));
        if (task.getStatus() == TaskStatus.PENDING) {
            notifications.notifyUser(target.getId(), "APPROVAL_REQUESTED", "Approval delegated to you: " + a.getTitle(),
                    actor.name() + " delegated an approval to you" + (comment == null || comment.isBlank() ? "." : ": " + comment),
                    inboxLink(task), Severity.WARNING);
        }
        audit.record("APPROVAL_DELEGATED", "APPROVAL", a.getId(), Map.of("task", task.getId(), "from", from, "to", target.getEmail()));
        return dto(a, actor);
    }

    @Transactional(readOnly = true)
    public List<DelegationDto> delegations(Actor actor) {
        Instant now = clock.instant();
        return delegations.findCurrentAndUpcoming(actor.id(), now).stream().map(d -> delegationDto(d, now)).toList();
    }

    @Transactional
    public DelegationDto createDelegation(DelegationRequest r, Actor actor) {
        Instant now = clock.instant();
        if (!r.endsAt().isAfter(r.startsAt())) {
            throw ApiException.invalidField("endsAt", "The end must be after the start");
        }
        if (!r.endsAt().isAfter(now)) {
            throw ApiException.invalidField("endsAt", "The delegation must end in the future");
        }
        if (Duration.between(r.startsAt(), r.endsAt()).toDays() > 90) {
            throw ApiException.invalidField("endsAt", "A delegation can last at most 90 days");
        }
        if (r.delegateId().equals(actor.id())) {
            throw ApiException.invalidField("delegateId", "You cannot delegate to yourself");
        }
        User delegate = users.findById(r.delegateId()).filter(u -> u.isActive() && u.getRole() != Role.AUDITOR)
                .orElseThrow(() -> ApiException.invalidField("delegateId", "The delegate must be an active user"));
        if (delegations.overlaps(actor.id(), r.startsAt(), r.endsAt())) {
            throw ApiException.conflict("You already have a delegation in this period");
        }
        ApprovalDelegation d = new ApprovalDelegation();
        d.setDelegator(users.getReferenceById(actor.id()));
        d.setDelegate(delegate);
        d.setStartsAt(r.startsAt());
        d.setEndsAt(r.endsAt());
        d.setReason(r.reason() == null || r.reason().isBlank() ? null : r.reason().trim());
        d.setCreatedAt(now);
        delegations.save(d);
        notifications.notifyUser(delegate.getId(), "APPROVAL_REQUESTED", "You cover approvals for " + actor.name(),
                actor.name() + " delegated their personal approvals to you from " + r.startsAt() + " to " + r.endsAt() + ".", "/approvals",
                Severity.INFO);
        audit.record("APPROVAL_DELEGATION_CREATED", "APPROVAL_DELEGATION", d.getId(), Map.of("delegate", delegate.getEmail(),
                "startsAt", r.startsAt().toString(), "endsAt", r.endsAt().toString()));
        return delegationDto(d, now);
    }

    @Transactional
    public void revokeDelegation(Long id, Actor actor) {
        ApprovalDelegation d = delegations.findById(id).filter(x -> x.getDelegator().getId().equals(actor.id()) || actor.hasRole(Role.SYSTEM_ADMIN))
                .orElseThrow(() -> ApiException.notFound("Delegation", id));
        if (d.getRevokedAt() == null) {
            d.setRevokedAt(clock.instant());
            audit.record("APPROVAL_DELEGATION_REVOKED", "APPROVAL_DELEGATION", d.getId(), null);
        }
    }

    private static DelegationDto delegationDto(ApprovalDelegation d, Instant now) {
        return new DelegationDto(d.getId(), d.getDelegator().getId(), d.getDelegator().getFullName(), d.getDelegate().getId(),
                d.getDelegate().getFullName(), d.getStartsAt(), d.getEndsAt(), d.getReason(), d.activeAt(now), d.getCreatedAt());
    }

    // ------------------------------------------------------------------ deadlines and escalation

    /**
     * Escalates one overdue stage exactly once (claimed with a conditional update): reminds the open approvers, notifies
     * their managers ({@code notifyManager}) and/or a role ({@code notifyRole}; default: system administrators) and, with
     * {@code autoReject}, rejects the approval. Returns whether this call escalated the stage.
     */
    @Transactional
    public boolean escalateStage(Long stageId, Instant now) {
        if (stages.claimEscalation(stageId, now) != 1) {
            return false;
        }
        ApprovalStage s = stages.findById(stageId).orElse(null);
        if (s == null || s.getStatus() != StageStatus.ACTIVE || s.getApproval().getStatus() != Approval.Status.PENDING) {
            return false;
        }
        Approval a = s.getApproval();
        EscalationDef esc = s.getEscalation() == null ? new EscalationDef(true, Role.SYSTEM_ADMIN, false) : s.getEscalation();
        List<ApprovalTask> open = tasks.findByStageIdOrderBySeqAsc(s.getId()).stream().filter(t -> t.getStatus() == TaskStatus.PENDING).toList();
        Set<Long> approvers = new LinkedHashSet<>();
        open.forEach(t -> approvers.addAll(recipients(t)));
        Set<Long> escalateTo = new LinkedHashSet<>();
        if (esc.notifyManager()) {
            for (ApprovalTask t : open) {
                escalateTo.addAll(managersOf(t));
            }
        }
        if (esc.notifyRole() != null) {
            users.findByActiveTrueAndRoleIn(List.of(esc.notifyRole())).forEach(u -> escalateTo.add(u.getId()));
        }
        if (a.getRequestedBy() != null) {
            approvers.remove(a.getRequestedBy().getId());
            escalateTo.remove(a.getRequestedBy().getId());
        }
        String due = s.getDueAt() == null ? "" : " (due " + s.getDueAt() + ")";
        notifications.notifyUsers(approvers, "APPROVAL_ESCALATED", "Overdue approval: " + a.getTitle(),
                "The stage “" + s.getName() + "” passed its deadline" + due + ". Please decide.", "/approvals", Severity.WARNING);
        escalateTo.removeAll(approvers);
        notifications.notifyUsers(escalateTo, "APPROVAL_ESCALATED", "Escalated approval: " + a.getTitle(),
                "Approval stage “" + s.getName() + "”" + (a.getReferenceNumber() == null ? "" : " for " + a.getReferenceNumber())
                        + " passed its deadline" + due + " without a decision.", "/approvals", Severity.WARNING);
        event(a, s.getStageOrder(), null, "ESCALATED", Actor.SYSTEM, "Deadline passed" + due,
                Map.of("notified", escalateTo.size() + approvers.size(), "autoReject", esc.autoReject()));
        audit.record("APPROVAL_ESCALATED", "APPROVAL", a.getId(), Map.of("stage", s.getStageOrder(), "autoReject", esc.autoReject()));
        if (esc.autoReject()) {
            String reason = "Automatically rejected: the approval deadline passed";
            open.forEach(t -> t.setStatus(TaskStatus.SKIPPED));
            tasks.findByStageIdOrderBySeqAsc(s.getId()).stream().filter(t -> t.getStatus() == TaskStatus.WAITING)
                    .forEach(t -> t.setStatus(TaskStatus.SKIPPED));
            s.setStatus(StageStatus.REJECTED);
            s.setCompletedAt(now);
            event(a, s.getStageOrder(), null, "AUTO_REJECTED", Actor.SYSTEM, reason, null);
            complete(a, false, Actor.SYSTEM, reason);
        }
        return true;
    }

    @Transactional(readOnly = true)
    public List<Long> overdueStageIds(Instant now, int limit) {
        return stages.findOverdue(now, PageRequest.of(0, limit));
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public PageResponse<ApprovalDto> list(Approval.Status status, Integer page, Integer size, Actor actor) {
        return list(new Filter(status, null, null, null, null), page, size, null, actor);
    }

    /** The approvals inbox: approvals with a task for me (scope mine), that I requested, or all (admins and auditors). */
    @Transactional(readOnly = true)
    public PageResponse<ApprovalDto> list(Filter f, Integer page, Integer size, String sort, Actor actor) {
        Context ctx = context(actor);
        String scope = f.scope() == null || f.scope().isBlank() ? "mine" : f.scope().trim().toLowerCase(Locale.ROOT);
        if ("all".equals(scope) && !actor.hasRole(Role.SYSTEM_ADMIN, Role.IT_ADMIN, Role.AUDITOR)) {
            throw ApiException.forbidden("Only administrators and auditors can list every approval");
        }
        if (!Set.of("mine", "requested", "all").contains(scope)) {
            throw ApiException.badRequest("scope must be mine, requested or all");
        }
        Instant now = clock.instant();
        Specification<Approval> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            switch (scope) {
                case "requested" -> p.add(cb.equal(root.get("requestedBy").get("id"), actor.id()));
                case "mine" -> {
                    if (!actor.hasRole(Role.SYSTEM_ADMIN)) {
                        Subquery<Long> sq = query.subquery(Long.class);
                        Root<ApprovalTask> t = sq.from(ApprovalTask.class);
                        List<Predicate> tp = new ArrayList<>();
                        tp.add(cb.equal(t.get("approval"), root));
                        tp.add(mine(cb, t, ctx));
                        if (f.status() == Approval.Status.PENDING) {
                            tp.add(cb.equal(t.get("status"), TaskStatus.PENDING));
                        }
                        sq.select(t.get("id")).where(tp.toArray(new Predicate[0]));
                        p.add(cb.exists(sq));
                    }
                }
                default -> { }
            }
            if (f.status() != null) {
                p.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.type() != null) {
                p.add(cb.equal(root.get("type"), f.type()));
            }
            if (Boolean.TRUE.equals(f.overdue())) {
                p.add(cb.equal(root.get("status"), Approval.Status.PENDING));
                p.add(cb.lessThan(root.get("dueAt"), now));
            }
            if (f.search() != null && !f.search().isBlank()) {
                String like = "%" + f.search().trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'), cb.like(cb.lower(root.get("referenceNumber")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<Approval> result = approvals.findAll(spec, PageResponse.pageable(page, size,
                sort(sort, Set.of("createdAt", "dueAt", "title", "status", "type", "decidedAt"), Sort.by(Sort.Direction.DESC, "createdAt"))));
        return PageResponse.of(result, dtos(result.getContent(), ctx));
    }

    /** Task inbox: open tasks I can decide now ({@code status=PENDING}, default) or tasks I decided ({@code DECIDED}). */
    @Transactional(readOnly = true)
    public PageResponse<TaskInboxItem> taskInbox(String status, Approval.Type type, Boolean overdue, Integer page, Integer size, String sort,
                                                 Actor actor) {
        Context ctx = context(actor);
        String mode = status == null || status.isBlank() ? "PENDING" : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING", "DECIDED").contains(mode)) {
            throw ApiException.badRequest("status must be PENDING or DECIDED");
        }
        Instant now = clock.instant();
        Specification<ApprovalTask> spec = taskSpec(mode, type, overdue, ctx, now);
        Page<ApprovalTask> result = tasks.findAll(spec, PageResponse.pageable(page, size,
                sort(sort == null ? null : sort.replace("createdAt", "approval.createdAt").replace("dueAt", "stage.dueAt"),
                        Set.of("approval.createdAt", "stage.dueAt", "decidedAt"),
                        "DECIDED".equals(mode) ? Sort.by(Sort.Direction.DESC, "decidedAt") : Sort.by(Sort.Direction.ASC, "approval.createdAt"))));
        List<TaskInboxItem> items = result.getContent().stream().map(t -> {
            Approval a = t.getApproval();
            boolean can = "PENDING".equals(mode) && !isRequester(a, actor.id());
            boolean late = t.getStatus() == TaskStatus.PENDING && t.getStage().getDueAt() != null && t.getStage().getDueAt().isBefore(now);
            return new TaskInboxItem(t.getId(), t.getStatus(), label(t), t.getStage().getStageOrder(), t.getStage().getName(), t.getStage().getDueAt(),
                    late, can, ApprovalDto.of(a, t.getStage().getName(), can, t.getId(), List.of(label(t)), now));
        }).toList();
        return PageResponse.of(result, items);
    }

    @Transactional(readOnly = true)
    public InboxCounts counts(Actor actor) {
        Context ctx = context(actor);
        Instant now = clock.instant();
        long pending = tasks.count(taskSpec("PENDING", null, null, ctx, now));
        long overdue = tasks.count(taskSpec("PENDING", null, true, ctx, now));
        long requested = approvals.countByStatusAndRequestedById(Approval.Status.PENDING, actor.id());
        return new InboxCounts(pending, overdue, requested);
    }

    private Specification<ApprovalTask> taskSpec(String mode, Approval.Type type, Boolean overdue, Context ctx, Instant now) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            Join<Object, Object> approval = root.join("approval");
            Join<Object, Object> stage = root.join("stage");
            if ("DECIDED".equals(mode)) {
                p.add(cb.equal(root.get("decidedBy").get("id"), ctx.actor().id()));
            } else {
                p.add(cb.equal(root.get("status"), TaskStatus.PENDING));
                p.add(cb.equal(stage.get("status"), StageStatus.ACTIVE));
                p.add(cb.equal(approval.get("status"), Approval.Status.PENDING));
                p.add(cb.or(cb.isNull(approval.get("requestedBy")), cb.notEqual(approval.get("requestedBy").get("id"), ctx.actor().id())));
                if (!ctx.actor().hasRole(Role.SYSTEM_ADMIN)) {
                    p.add(mine(cb, root, ctx));
                }
            }
            if (type != null) {
                p.add(cb.equal(approval.get("type"), type));
            }
            if (Boolean.TRUE.equals(overdue)) {
                p.add(cb.lessThan(stage.get("dueAt"), now));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    /** Tasks that are (or were) mine: personal (incl. covered delegators), my role, my teams, decided or delegated by me. */
    private static Predicate mine(jakarta.persistence.criteria.CriteriaBuilder cb, jakarta.persistence.criteria.From<?, ApprovalTask> t, Context ctx) {
        List<Predicate> or = new ArrayList<>();
        Join<Object, Object> user = t.join("approverUser", JoinType.LEFT);
        if (ctx.personalIds().isEmpty()) {
            return cb.disjunction();
        }
        or.add(cb.and(cb.equal(t.get("approverKind"), ApprovalTask.Kind.USER), user.get("id").in(ctx.personalIds())));
        or.add(cb.and(cb.equal(t.get("approverKind"), ApprovalTask.Kind.ROLE), cb.equal(t.get("approverRole"), ctx.actor().role())));
        if (!ctx.teamIds().isEmpty()) {
            Join<Object, Object> team = t.join("approverTeam", JoinType.LEFT);
            or.add(cb.and(cb.equal(t.get("approverKind"), ApprovalTask.Kind.TEAM), team.get("id").in(ctx.teamIds())));
        }
        Join<Object, Object> decider = t.join("decidedBy", JoinType.LEFT);
        or.add(cb.equal(decider.get("id"), ctx.actor().id()));
        return cb.or(or.toArray(new Predicate[0]));
    }

    @Transactional(readOnly = true)
    public ApprovalDetail detail(Long id, Actor actor) {
        Approval a = approvals.findById(id).orElseThrow(() -> ApiException.notFound("Approval", id));
        Context ctx = context(actor);
        List<ApprovalTask> all = tasks.findByApprovalIdOrderByIdAsc(id);
        boolean visible = actor.hasRole(Role.SYSTEM_ADMIN, Role.IT_ADMIN, Role.AUDITOR) || isRequester(a, actor.id())
                || all.stream().anyMatch(t -> routed(t, ctx));
        if (!visible) {
            throw ApiException.notFound("Approval", id);
        }
        Map<Long, List<ApprovalTask>> byStage = all.stream().collect(Collectors.groupingBy(t -> t.getStage().getId(), LinkedHashMap::new, Collectors.toList()));
        List<StageDto> stageDtos = stages.findByApprovalIdOrderByStageOrderAsc(id).stream().map(s -> {
            List<ApprovalTask> st = byStage.getOrDefault(s.getId(), List.of()).stream().sorted(Comparator.comparingInt(ApprovalTask::getSeq)).toList();
            return new StageDto(s.getId(), s.getStageOrder(), s.getName(), s.getMode(), s.getQuorum(), s.getRequiredCount(), s.getStatus(),
                    s.getCondition(), s.getDeadlineHours(), s.getDueAt(), s.getEscalation(), s.getEscalatedAt(), s.getStartedAt(), s.getCompletedAt(),
                    st.stream().filter(t -> t.getStatus() == TaskStatus.APPROVED).count(),
                    st.stream().map(t -> taskDto(t, canDecide(a, t, ctx))).toList());
        }).toList();
        List<EventDto> history = events.findByApprovalIdOrderByCreatedAtAscIdAsc(id).stream()
                .map(e -> new EventDto(e.getId(), e.getEventType(), e.getStageOrder(), e.getTaskId(), e.getActorName(), e.getComment(),
                        e.getDetails(), e.getCreatedAt())).toList();
        return new ApprovalDetail(dtos(List.of(a), ctx).get(0), stageDtos, history);
    }

    /** Pending approvals in which I have a pending task (dashboard count; includes my own requests, as in Release 2). */
    @Transactional(readOnly = true)
    public long pendingCount(Actor actor) {
        if (!actor.isStaff()) {
            return 0;
        }
        return list(new Filter(Approval.Status.PENDING, null, "mine", null, null), 0, 1, null, actor).totalElements();
    }

    /** Pending approvals the actor may decide now (not requested by them), oldest first (My Work). */
    @Transactional(readOnly = true)
    public List<ApprovalDto> decidable(Actor actor, int limit) {
        Context ctx = context(actor);
        Page<ApprovalTask> page = tasks.findAll(taskSpec("PENDING", null, null, ctx, clock.instant()),
                PageRequest.of(0, Math.max(1, limit) * 3, Sort.by(Sort.Direction.ASC, "approval.createdAt")));
        Map<Long, Approval> unique = new LinkedHashMap<>();
        page.getContent().forEach(t -> unique.putIfAbsent(t.getApproval().getId(), t.getApproval()));
        return dtos(unique.values().stream().limit(limit).toList(), ctx);
    }

    @Transactional(readOnly = true)
    public long decidableCount(Actor actor) {
        Context ctx = context(actor);
        return tasks.findAll(taskSpec("PENDING", null, null, ctx, clock.instant())).stream().map(t -> t.getApproval().getId()).distinct().count();
    }

    @Transactional(readOnly = true)
    public ApprovalDto latestFor(Approval.Type type, Long referenceId) {
        return approvals.findFirstByTypeAndReferenceIdOrderByCreatedAtDesc(type, referenceId).map(ApprovalDto::of).orElse(null);
    }

    /** Approval DTO with the caller's context (can decide, own task, who the active stage waits for). */
    @Transactional(readOnly = true)
    public ApprovalDto dto(Approval a, Actor actor) {
        return dtos(List.of(a), context(actor)).get(0);
    }

    private List<ApprovalDto> dtos(List<Approval> list, Context ctx) {
        if (list.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        Map<Long, List<ApprovalTask>> byApproval = tasks.findByApprovalIdIn(list.stream().map(Approval::getId).toList()).stream()
                .collect(Collectors.groupingBy(t -> t.getApproval().getId()));
        List<ApprovalDto> out = new ArrayList<>();
        for (Approval a : list) {
            List<ApprovalTask> all = byApproval.getOrDefault(a.getId(), List.of());
            Integer stage = a.getCurrentStage();
            List<ApprovalTask> current = all.stream().filter(t -> stage != null && t.getStage().getStageOrder() == stage)
                    .sorted(Comparator.comparingInt(ApprovalTask::getSeq)).toList();
            String stageName = current.isEmpty() ? null : current.get(0).getStage().getName();
            ApprovalTask mine = current.stream().filter(t -> canDecide(a, t, ctx)).findFirst().orElse(null);
            List<String> pendingWith = a.getStatus() != Approval.Status.PENDING ? List.of()
                    : current.stream().filter(t -> t.getStatus() == TaskStatus.PENDING).map(ApprovalService::label).distinct().toList();
            out.add(ApprovalDto.of(a, stageName, mine != null, mine == null ? null : mine.getId(), pendingWith, now));
        }
        return out;
    }

    private boolean canDecide(Approval a, ApprovalTask t, Context ctx) {
        return a.getStatus() == Approval.Status.PENDING && t.getStatus() == TaskStatus.PENDING && t.getStage().getStatus() == StageStatus.ACTIVE
                && !isRequester(a, ctx.actor().id()) && eligible(t, ctx);
    }

    private TaskDto taskDto(ApprovalTask t, boolean canDecide) {
        return new TaskDto(t.getId(), t.getSeq(), t.getApproverKind(), t.getApproverUser() == null ? null : t.getApproverUser().getId(),
                t.getApproverUser() == null ? null : t.getApproverUser().getFullName(), t.getApproverRole(),
                t.getApproverTeam() == null ? null : t.getApproverTeam().getId(), t.getApproverTeam() == null ? null : t.getApproverTeam().getName(),
                label(t), t.getStatus(), t.getDecidedBy() == null ? null : t.getDecidedBy().getId(),
                t.getDecidedBy() == null ? null : t.getDecidedBy().getFullName(), t.getDecidedAt(), t.getComment(),
                t.getDelegatedFrom() == null ? null : t.getDelegatedFrom().getId(),
                t.getDelegatedFrom() == null ? null : t.getDelegatedFrom().getFullName(), t.getDelegatedAt(), canDecide);
    }

    // ------------------------------------------------------------------ eligibility

    /** Who is asking: ids whose personal tasks they may decide (own + covered delegators) and their team ids. */
    private record Context(Actor actor, Set<Long> personalIds, Set<Long> teamIds) {}

    private Context context(Actor actor) {
        Set<Long> personal = new HashSet<>();
        Set<Long> teamIds = Set.of();
        if (actor.id() != null) {
            personal.add(actor.id());
            personal.addAll(delegations.findDelegatorsCoveredBy(actor.id(), clock.instant()));
            teamIds = actor.isStaff() ? teamRouting.teamIdsOf(actor.id()) : Set.of();
        }
        return new Context(actor, personal, teamIds);
    }

    /** May the actor decide this task (ignoring its status)? A system administrator may decide every task. */
    private boolean eligible(ApprovalTask t, Context ctx) {
        return ctx.actor().id() != null && (ctx.actor().hasRole(Role.SYSTEM_ADMIN) || matchesIdentity(t, ctx, false));
    }

    /** Routing visibility: the task is (or was) addressed to the actor, or they decided or delegated it. */
    private boolean routed(ApprovalTask t, Context ctx) {
        return matchesIdentity(t, ctx, true);
    }

    private static boolean matchesIdentity(ApprovalTask t, Context ctx, boolean includeHistory) {
        Long me = ctx.actor().id();
        if (me == null) {
            return false;
        }
        boolean identity = switch (t.getApproverKind()) {
            case USER -> t.getApproverUser() != null && ctx.personalIds().contains(t.getApproverUser().getId());
            case ROLE -> t.getApproverRole() == ctx.actor().role();
            case TEAM -> t.getApproverTeam() != null && ctx.teamIds().contains(t.getApproverTeam().getId());
        };
        if (identity || !includeHistory) {
            return identity;
        }
        return t.getDecidedBy() != null && t.getDecidedBy().getId().equals(me) || t.getDelegatedFrom() != null && t.getDelegatedFrom().getId().equals(me);
    }

    private static boolean isRequester(Approval a, Long userId) {
        return userId != null && a.getRequestedBy() != null && a.getRequestedBy().getId().equals(userId);
    }

    private Set<Long> recipients(ApprovalTask t) {
        Set<Long> ids = new LinkedHashSet<>();
        switch (t.getApproverKind()) {
            case USER -> ids.add(t.getApproverUser().getId());
            case ROLE -> users.findByActiveTrueAndRoleIn(List.of(t.getApproverRole())).forEach(u -> ids.add(u.getId()));
            case TEAM -> teamRouting.activeMembers(t.getApproverTeam()).forEach(u -> ids.add(u.getId()));
        }
        return ids;
    }

    /** Escalation targets of a task: the user's department manager; the team lead and its department manager; for a role, admins. */
    private Set<Long> managersOf(ApprovalTask t) {
        Set<Long> ids = new LinkedHashSet<>();
        switch (t.getApproverKind()) {
            case USER -> users.findWithDepartmentById(t.getApproverUser().getId()).map(User::getDepartment).map(Department::getManager)
                    .filter(User::isActive).ifPresent(m -> ids.add(m.getId()));
            case TEAM -> teams.findWithDetailsById(t.getApproverTeam().getId()).ifPresent(team -> {
                if (team.getLead() != null && team.getLead().isActive()) {
                    ids.add(team.getLead().getId());
                }
                if (team.getDepartment() != null && team.getDepartment().getManager() != null) {
                    ids.add(team.getDepartment().getManager().getId());
                }
            });
            case ROLE -> users.findByActiveTrueAndRoleIn(List.of(Role.SYSTEM_ADMIN)).forEach(u -> ids.add(u.getId()));
        }
        return ids;
    }

    static String label(ApprovalTask t) {
        return switch (t.getApproverKind()) {
            case USER -> t.getApproverUser() == null ? "User" : t.getApproverUser().getFullName();
            case ROLE -> t.getApproverRole() == null ? "Role" : t.getApproverRole().label();
            case TEAM -> t.getApproverTeam() == null ? "Team" : t.getApproverTeam().getName();
        };
    }

    private static String inboxLink(ApprovalTask t) {
        return "/approvals?task=" + t.getId();
    }

    private void event(Approval a, Integer stageOrder, Long taskId, String type, Actor actor, String comment, Map<String, ?> details) {
        ApprovalEvent e = new ApprovalEvent();
        e.setApprovalId(a.getId());
        e.setStageOrder(stageOrder);
        e.setTaskId(taskId);
        e.setEventType(type);
        e.setActorId(actor.id());
        e.setActorName(actor.name() == null ? "SA Automation" : actor.name());
        e.setComment(comment == null ? null : comment.length() > 1000 ? comment.substring(0, 999) : comment);
        e.setDetails(details == null ? null : JsonMaps.normalize(details));
        e.setCreatedAt(clock.instant());
        events.save(e);
    }

    /** Parses {@code field,dir} against an allow-list; unknown fields → 400. */
    static Sort sort(String sort, Collection<String> allowed, Sort fallback) {
        if (sort == null || sort.isBlank()) {
            return fallback;
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!allowed.contains(field)) {
            throw ApiException.badRequest("Cannot sort by '" + field + "'");
        }
        Sort.Direction dir = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, field);
    }

    /** Release 2 routing: single-step approvals go to IT_MANAGER, INCIDENT_MANAGER or SYSTEM_ADMIN (anything else: IT managers). */
    static Role routeTo(Role requested) {
        return requested != null && Role.APPROVERS.contains(requested) ? requested : Role.IT_MANAGER;
    }
}
