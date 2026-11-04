package com.nexus360.runbook;

import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalHandler;
import com.nexus360.approval.ApprovalRepository;
import com.nexus360.approval.ApprovalService;
import com.nexus360.approval.ApprovalService.ApprovalDto;
import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.incident.Category;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentTimeline;
import com.nexus360.runbook.Runbook.Step;
import com.nexus360.runbook.RunbookExecution.StepState;
import com.nexus360.security.Actor;
import com.nexus360.user.Role;
import com.nexus360.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runbooks and their executions. Approval-gated runbooks wait for an incident manager before they can start; sensitive
 * steps ({@code requiresApproval}) are completed only after an incident manager approves (or completes them directly).
 */
@Service
@RequiredArgsConstructor
public class RunbookService implements ApprovalHandler {

    /** Who may approve runbook executions and sensitive steps directly. */
    static final Set<Role> STEP_APPROVERS = EnumSet.of(Role.INCIDENT_MANAGER, Role.SYSTEM_ADMIN);

    public record StepRequest(@Min(1) int order, @NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 4000) String instruction,
                              boolean requiresApproval) {}

    public record RunbookRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 1000) String description,
            Long serviceId,
            @Size(max = 9) List<Category> categories,
            @Size(max = 20) List<@NotBlank @Size(max = 300) String> prerequisites,
            @NotEmpty @Size(max = 50) List<@Valid StepRequest> steps,
            boolean requiresApproval,
            @Min(1) @Max(1440) Integer estimatedMinutes,
            boolean active) {}

    public record RunbookDto(Long id, String title, String description, Long serviceId, String serviceName, List<Category> categories,
                             List<String> prerequisites, List<Step> steps, boolean requiresApproval, Integer estimatedMinutes,
                             boolean active, String authorName, long executionCount, Instant updatedAt, boolean demo) {}

    public record ExecutionDto(Long id, Long runbookId, String runbookTitle, Long incidentId, String incidentNumber,
                               RunbookExecution.Status status, String startedByName, ApprovalDto approval, List<StepState> stepStates,
                               String outcomeNotes, Instant startedAt, Instant completedAt, Instant createdAt) {}

    private final RunbookRepository runbooks;
    private final RunbookExecutionRepository executions;
    private final BusinessServiceRepository services;
    private final IncidentRepository incidents;
    private final UserRepository users;
    private final ApprovalService approvals;
    private final ApprovalRepository approvalRepository;
    private final IncidentTimeline timeline;
    private final AuditService audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<RunbookDto> list(String search, Long serviceId, Category category) {
        Map<Long, Long> counts = executionCounts();
        String q = search == null ? null : search.trim().toLowerCase(Locale.ROOT);
        return runbooks.findAllByOrderByTitleAsc().stream()
                .filter(r -> serviceId == null || (r.getService() != null && r.getService().getId().equals(serviceId)))
                .filter(r -> category == null || r.getCategories().contains(category))
                .filter(r -> q == null || q.isEmpty() || r.getTitle().toLowerCase(Locale.ROOT).contains(q)
                        || (r.getDescription() != null && r.getDescription().toLowerCase(Locale.ROOT).contains(q)))
                .map(r -> dto(r, counts.getOrDefault(r.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RunbookDto get(Long id) {
        Runbook r = runbooks.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Runbook", id));
        return dto(r, executionCounts().getOrDefault(id, 0L));
    }

    @Transactional
    public RunbookDto save(Long id, RunbookRequest request, Actor actor) {
        Runbook r = id == null ? new Runbook() : runbooks.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Runbook", id));
        if (id == null && runbooks.existsByTitleIgnoreCase(request.title().trim())) {
            throw ApiException.conflict("A runbook with this title already exists");
        }
        r.setTitle(request.title().trim());
        r.setDescription(request.description());
        r.setService(request.serviceId() == null ? null
                : services.findById(request.serviceId()).orElseThrow(() -> ApiException.badRequest("Service does not exist")));
        Set<Category> categories = request.categories() == null ? new HashSet<>()
                : request.categories().stream().filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
        r.getCategories().clear();
        r.getCategories().addAll(categories);
        r.setPrerequisites(request.prerequisites() == null ? new ArrayList<>()
                : request.prerequisites().stream().map(String::trim).filter(p -> !p.isEmpty()).collect(Collectors.toCollection(ArrayList::new)));
        List<StepRequest> sorted = request.steps().stream().sorted((a, b) -> Integer.compare(a.order(), b.order())).toList();
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            steps.add(new Step(i + 1, sorted.get(i).title().trim(), sorted.get(i).instruction().trim(), sorted.get(i).requiresApproval()));
        }
        r.setSteps(steps);
        r.setRequiresApproval(request.requiresApproval());
        r.setEstimatedMinutes(request.estimatedMinutes());
        r.setActive(request.active());
        if (id == null) {
            r.setAuthor(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        }
        runbooks.save(r);
        audit.record(id == null ? "RUNBOOK_CREATED" : "RUNBOOK_UPDATED", "RUNBOOK", r.getId(),
                Map.of("title", r.getTitle(), "requiresApproval", r.isRequiresApproval(),
                        "sensitiveSteps", steps.stream().filter(Step::requiresApproval).count()));
        return dto(r, executionCounts().getOrDefault(r.getId(), 0L));
    }

    /** Starts a runbook for an incident. Approval-gated runbooks wait in PENDING_APPROVAL until an incident manager decides. */
    @Transactional
    public ExecutionDto startExecution(Long runbookId, Long incidentId, Actor actor) {
        return start(runbookId, incidentId, actor, false);
    }

    /** Starts a runbook whose execution an approver has already approved (e.g. an approved SA AI suggestion). */
    @Transactional
    public ExecutionDto startPreApproved(Long runbookId, Long incidentId, Actor approver) {
        if (!approver.isSystem() && !Role.APPROVERS.contains(approver.role())) {
            throw ApiException.forbidden("Only managers can pre-approve a runbook execution");
        }
        return start(runbookId, incidentId, approver, true);
    }

    private ExecutionDto start(Long runbookId, Long incidentId, Actor actor, boolean preApproved) {
        Runbook runbook = runbooks.findWithDetailsById(runbookId).filter(Runbook::isActive)
                .orElseThrow(() -> ApiException.badRequest("Runbook is not available"));
        Incident incident = incidentId == null ? null
                : incidents.findById(incidentId).orElseThrow(() -> ApiException.badRequest("Incident does not exist"));
        RunbookExecution e = new RunbookExecution();
        e.setRunbook(runbook);
        e.setIncident(incident);
        e.setStartedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        e.setStepStates(runbook.getSteps().stream()
                .map(s -> new StepState(s.order(), s.title(), false, null, null, null, s.requiresApproval(), StepState.NOT_REQUIRED, null))
                .toList());
        e.setDemo(runbook.isDemo() || (incident != null && incident.isDemo()));
        boolean gated = runbook.isRequiresApproval() && !preApproved;
        e.setStatus(gated ? RunbookExecution.Status.PENDING_APPROVAL : RunbookExecution.Status.READY);
        executions.save(e);
        if (gated) {
            Approval approval = approvals.request(Approval.Type.RUNBOOK_EXECUTION, e.getId(),
                    incident == null ? null : incident.getNumber(), "Run '" + runbook.getTitle() + "'" + (incident == null ? "" : " for " + incident.getNumber()),
                    runbook.getDescription(), Map.of("runbookId", runbook.getId(), "steps", runbook.getSteps().size()), Role.INCIDENT_MANAGER, actor,
                    e.isDemo(), incident == null ? "/runbooks/" + runbook.getId() : "/incidents/" + incident.getId());
            e.setApprovalId(approval.getId());
        }
        if (incident != null) {
            timeline.record(incident, actor, "RUNBOOK", "Runbook '" + runbook.getTitle() + "' "
                    + (gated ? "requested (awaiting incident manager approval)" : preApproved ? "started (approved by " + actor.name() + ")" : "started"));
        }
        audit.record("RUNBOOK_EXECUTION_STARTED", "RUNBOOK", runbook.getId(), Map.of("execution", e.getId(),
                "incident", incident == null ? "" : incident.getNumber(), "status", e.getStatus()));
        return dtos(List.of(e)).get(0);
    }

    /** Execution history across runbooks, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<ExecutionDto> executions(Long incidentId, Long runbookId, RunbookExecution.Status status, Integer page, Integer size) {
        Specification<RunbookExecution> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (incidentId != null) {
                p.add(cb.equal(root.get("incident").get("id"), incidentId));
            }
            if (runbookId != null) {
                p.add(cb.equal(root.get("runbook").get("id"), runbookId));
            }
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<RunbookExecution> result = executions.findAll(spec, PageResponse.pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return PageResponse.of(result, dtos(result.getContent()));
    }

    /**
     * Marks a step done or not done. Completing a sensitive step by someone who is not an incident manager or system
     * administrator creates a RUNBOOK_STEP approval and leaves the step open (PENDING) until it is decided.
     */
    @Transactional
    public ExecutionDto updateStep(Long executionId, int order, boolean done, String notes, Actor actor) {
        RunbookExecution e = load(executionId);
        if (e.getStatus() != RunbookExecution.Status.READY && e.getStatus() != RunbookExecution.Status.IN_PROGRESS) {
            throw ApiException.conflict(e.getStatus() == RunbookExecution.Status.PENDING_APPROVAL
                    ? "This runbook is awaiting manager approval" : "This runbook execution is " + e.getStatus());
        }
        List<StepState> steps = new ArrayList<>(e.getStepStates());
        int index = indexOf(steps, order);
        StepState s = steps.get(index);
        String note = notes == null ? s.notes() : notes.trim();
        Instant now = clock.instant();
        StepState updated;
        if (!done) {
            if (StepState.PENDING.equals(s.approvalStatus()) && s.approvalId() != null) {
                approvalRepository.findById(s.approvalId()).filter(a -> a.getStatus() == Approval.Status.PENDING)
                        .ifPresent(a -> approvals.cancel(a, "Step reopened", actor));
            }
            updated = new StepState(s.order(), s.title(), false, note, null, null, s.requiresApproval(), StepState.NOT_REQUIRED, null);
        } else if (s.requiresApproval() && !s.done()) {
            if (StepState.PENDING.equals(s.approvalStatus())) {
                throw ApiException.conflict("Step " + order + " is already awaiting approval");
            }
            if (actor.isSystem() || STEP_APPROVERS.contains(actor.role())) {
                updated = new StepState(s.order(), s.title(), true, note, actor.name(), now, true, StepState.APPROVED, null);
                audit.record("RUNBOOK_STEP_COMPLETED", "RUNBOOK_EXECUTION", e.getId(), Map.of("step", order, "sensitive", true,
                        "approvedDirectly", true));
            } else {
                String reference = e.getIncident() == null ? null : e.getIncident().getNumber();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("executionId", e.getId());
                payload.put("stepOrder", order);
                payload.put("runbookId", e.getRunbook().getId());
                Approval approval = approvals.request(Approval.Type.RUNBOOK_STEP, e.getId(), reference,
                        "Sensitive step " + order + " '" + s.title() + "' of '" + e.getRunbook().getTitle() + "'"
                                + (reference == null ? "" : " for " + reference),
                        note, payload, Role.INCIDENT_MANAGER, actor, e.isDemo(),
                        e.getIncident() == null ? "/runbooks/" + e.getRunbook().getId() : "/incidents/" + e.getIncident().getId());
                updated = new StepState(s.order(), s.title(), false, note, actor.name(), null, true, StepState.PENDING, approval.getId());
                if (e.getIncident() != null) {
                    timeline.record(e.getIncident(), actor, "RUNBOOK", "Sensitive step " + order + " of '" + e.getRunbook().getTitle()
                            + "' sent for incident manager approval");
                }
            }
        } else {
            updated = new StepState(s.order(), s.title(), true, note, s.done() ? s.completedByName() : actor.name(),
                    s.done() ? s.completedAt() : now, s.requiresApproval(), s.approvalStatus(), s.approvalId());
        }
        steps.set(index, updated);
        e.setStepStates(steps);
        if (e.getStatus() == RunbookExecution.Status.READY) {
            e.setStatus(RunbookExecution.Status.IN_PROGRESS);
            e.setStartedAt(now);
        }
        return dtos(List.of(e)).get(0);
    }

    @Transactional
    public ExecutionDto complete(Long executionId, String outcome, Actor actor) {
        RunbookExecution e = load(executionId);
        if (e.getStatus() != RunbookExecution.Status.IN_PROGRESS && e.getStatus() != RunbookExecution.Status.READY) {
            throw ApiException.conflict("This runbook execution is " + e.getStatus());
        }
        long open = e.getStepStates().stream().filter(s -> !s.done()).count();
        if (open > 0) {
            long awaiting = e.getStepStates().stream().filter(s -> StepState.PENDING.equals(s.approvalStatus())).count();
            throw ApiException.conflict(open + " step(s) are not completed yet" + (awaiting > 0 ? " (" + awaiting + " awaiting approval)" : ""));
        }
        e.setStatus(RunbookExecution.Status.COMPLETED);
        e.setOutcomeNotes(outcome);
        e.setCompletedAt(clock.instant());
        if (e.getIncident() != null) {
            timeline.record(e.getIncident(), actor, "RUNBOOK", "Runbook '" + e.getRunbook().getTitle() + "' completed"
                    + (outcome == null || outcome.isBlank() ? "" : ": " + outcome));
        }
        audit.record("RUNBOOK_EXECUTION_COMPLETED", "RUNBOOK", e.getRunbook().getId(), Map.of("execution", e.getId()));
        return dtos(List.of(e)).get(0);
    }

    @Transactional
    public ExecutionDto abort(Long executionId, String reason, Actor actor) {
        RunbookExecution e = load(executionId);
        if (e.getStatus() == RunbookExecution.Status.COMPLETED || e.getStatus() == RunbookExecution.Status.ABORTED
                || e.getStatus() == RunbookExecution.Status.REJECTED) {
            throw ApiException.conflict("This runbook execution is " + e.getStatus());
        }
        e.setStatus(RunbookExecution.Status.ABORTED);
        e.setOutcomeNotes("Aborted: " + reason);
        e.setCompletedAt(clock.instant());
        e.getStepStates().stream().filter(s -> StepState.PENDING.equals(s.approvalStatus()) && s.approvalId() != null)
                .forEach(s -> approvalRepository.findById(s.approvalId()).filter(a -> a.getStatus() == Approval.Status.PENDING)
                        .ifPresent(a -> approvals.cancel(a, "Runbook execution aborted: " + reason, actor)));
        if (e.getIncident() != null) {
            timeline.record(e.getIncident(), actor, "RUNBOOK", "Runbook '" + e.getRunbook().getTitle() + "' aborted: " + reason);
        }
        audit.record("RUNBOOK_EXECUTION_ABORTED", "RUNBOOK", e.getRunbook().getId(), Map.of("execution", e.getId(), "reason", reason));
        return dtos(List.of(e)).get(0);
    }

    @Override
    public Approval.Type type() {
        return Approval.Type.RUNBOOK_EXECUTION;
    }

    @Override
    public void onDecision(Approval approval, boolean approved, Actor approver) {
        RunbookExecution e = load(approval.getReferenceId());
        if (e.getStatus() != RunbookExecution.Status.PENDING_APPROVAL) {
            throw ApiException.conflict("Runbook execution is no longer awaiting approval");
        }
        e.setStatus(approved ? RunbookExecution.Status.READY : RunbookExecution.Status.REJECTED);
        if (!approved) {
            e.setOutcomeNotes("Rejected: " + approval.getDecisionComment());
            e.setCompletedAt(clock.instant());
        }
        if (e.getIncident() != null) {
            timeline.record(e.getIncident(), approver, "RUNBOOK", "Runbook '" + e.getRunbook().getTitle() + "' "
                    + (approved ? "approved by " + approver.name() : "rejected by " + approver.name()));
        }
    }

    /** Applies a RUNBOOK_STEP decision: the step becomes done when approved, and stays open (REJECTED) otherwise. */
    @Transactional
    public void onStepDecision(Approval approval, boolean approved, Actor approver) {
        RunbookExecution e = load(approval.getReferenceId());
        Object orderValue = approval.getPayload() == null ? null : approval.getPayload().get("stepOrder");
        if (!(orderValue instanceof Number number)) {
            throw ApiException.conflict("Approval does not reference a runbook step");
        }
        int order = number.intValue();
        List<StepState> steps = new ArrayList<>(e.getStepStates());
        int index = indexOf(steps, order);
        StepState s = steps.get(index);
        if (!StepState.PENDING.equals(s.approvalStatus()) || !Objects.equals(s.approvalId(), approval.getId())) {
            throw ApiException.conflict("Step " + order + " is no longer awaiting this approval");
        }
        if (e.getStatus() != RunbookExecution.Status.IN_PROGRESS && e.getStatus() != RunbookExecution.Status.READY) {
            throw ApiException.conflict("This runbook execution is " + e.getStatus());
        }
        Instant now = clock.instant();
        steps.set(index, approved
                ? new StepState(s.order(), s.title(), true, s.notes(), s.completedByName(), now, true, StepState.APPROVED, approval.getId())
                : new StepState(s.order(), s.title(), false, s.notes(), null, null, true, StepState.REJECTED, approval.getId()));
        e.setStepStates(steps);
        if (e.getIncident() != null) {
            timeline.record(e.getIncident(), approver, "RUNBOOK", "Sensitive step " + order + " of '" + e.getRunbook().getTitle() + "' "
                    + (approved ? "approved by " + approver.name() : "rejected by " + approver.name()));
        }
    }

    private RunbookExecution load(Long id) {
        return executions.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Runbook execution", id));
    }

    private static int indexOf(List<StepState> steps, int order) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).order() == order) {
                return i;
            }
        }
        throw ApiException.notFound("Step", order);
    }

    private Map<Long, Long> executionCounts() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : runbooks.countExecutions()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    /** Maps executions with their approvals loaded in one query. */
    private List<ExecutionDto> dtos(Collection<RunbookExecution> list) {
        List<Long> approvalIds = list.stream().map(RunbookExecution::getApprovalId).filter(Objects::nonNull).toList();
        Map<Long, ApprovalDto> byId = approvalIds.isEmpty() ? Map.of()
                : approvalRepository.findByTypeAndIdIn(Approval.Type.RUNBOOK_EXECUTION, approvalIds).stream()
                .map(ApprovalDto::of).collect(Collectors.toMap(ApprovalDto::id, Function.identity()));
        return list.stream().map(e -> new ExecutionDto(e.getId(), e.getRunbook().getId(), e.getRunbook().getTitle(),
                e.getIncident() == null ? null : e.getIncident().getId(), e.getIncident() == null ? null : e.getIncident().getNumber(),
                e.getStatus(), e.getStartedBy() == null ? "SA Automation" : e.getStartedBy().getFullName(),
                e.getApprovalId() == null ? null : byId.get(e.getApprovalId()), e.getStepStates(),
                e.getOutcomeNotes(), e.getStartedAt(), e.getCompletedAt(), e.getCreatedAt())).toList();
    }

    private static RunbookDto dto(Runbook r, long executions) {
        return new RunbookDto(r.getId(), r.getTitle(), r.getDescription(), r.getService() == null ? null : r.getService().getId(),
                r.getService() == null ? null : r.getService().getName(), r.getCategories().stream().sorted().toList(), r.getPrerequisites(),
                r.getSteps(), r.isRequiresApproval(), r.getEstimatedMinutes(), r.isActive(),
                r.getAuthor() == null ? null : r.getAuthor().getFullName(), executions, r.getUpdatedAt(), r.isDemo());
    }
}
