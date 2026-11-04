package com.nexus360.workflow;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.incident.Category;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentSource;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.incident.Level;
import com.nexus360.incident.Priority;
import com.nexus360.incident.ResolutionCode;
import com.nexus360.integration.IntegrationRepository;
import com.nexus360.runbook.RunbookRepository;
import com.nexus360.security.Actor;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.Role;
import com.nexus360.user.UserRepository;
import com.nexus360.workflow.WorkflowModel.ActionDef;
import com.nexus360.workflow.WorkflowModel.ActionType;
import com.nexus360.workflow.WorkflowModel.ConditionRule;
import com.nexus360.workflow.WorkflowModel.Definition;
import com.nexus360.workflow.WorkflowModel.Operator;
import com.nexus360.workflow.WorkflowModel.StepLog;
import com.nexus360.workflow.WorkflowModel.Trigger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    public record WorkflowDto(Long id, String name, String description, Trigger triggerEvent, boolean active, int currentVersion,
                              int maxExecutionsPerHour, int maxRetries, int retryBackoffSeconds, Definition definition,
                              long executionsLast24h, long failuresLast24h, Instant lastExecutedAt, String createdByName,
                              Instant updatedAt, boolean demo) {}

    public record WorkflowRequest(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 1000) String description,
            @NotNull Trigger triggerEvent,
            boolean active,
            @Min(1) @Max(10_000) int maxExecutionsPerHour,
            @Min(0) @Max(10) int maxRetries,
            @Min(1) @Max(3600) int retryBackoffSeconds,
            @NotNull @Valid Definition definition,
            @Size(max = 500) String changeNote) {}

    public record VersionDto(int versionNumber, Definition definition, String changeNote, String createdByName, Instant createdAt) {}

    public record ExecutionDto(Long id, Long workflowId, String workflowName, int workflowVersion, Trigger triggerEvent, Long incidentId,
                               String incidentNumber, WorkflowExecution.Status status, int attempt, Instant nextAttemptAt,
                               String errorMessage, List<StepLog> steps, Instant startedAt, Instant finishedAt,
                               WorkflowModel.Branch branch, Long serviceRequestId, Long changeId, Long alertId) {}

    public record RuleResultDto(ConditionRule rule, boolean matched, String actual) {}

    /** {@code plannedActions} describe the branch that would run: the actions on a match, else the else-actions. */
    public record TestResult(boolean matched, List<RuleResultDto> ruleResults, List<String> plannedActions, WorkflowModel.Branch branch) {}

    public record ParamSpec(String key, String label, boolean required, List<String> options) {}

    public record ActionSpec(ActionType type, String label, List<ParamSpec> params) {}

    public record FieldSpec(String field, String label, List<String> values) {}

    public record Meta(List<Trigger> triggers, List<FieldSpec> fields, List<Operator> operators, List<ActionSpec> actions) {}

    static final List<FieldSpec> FIELDS = List.of(
            new FieldSpec("priority", "Priority", names(Priority.values())),
            new FieldSpec("status", "Status", names(IncidentStatus.values())),
            new FieldSpec("category", "Category", names(Category.values())),
            new FieldSpec("serviceCode", "Service code", null),
            new FieldSpec("departmentCode", "Department code", null),
            new FieldSpec("assignedTeam", "Assigned team (department code)", null),
            new FieldSpec("teamCode", "Team code", null),
            new FieldSpec("assigneeEmail", "Assignee e-mail", null),
            new FieldSpec("slaRemainingMinutes", "Resolution SLA remaining (minutes)", null),
            new FieldSpec("impact", "Impact", names(Level.values())),
            new FieldSpec("urgency", "Urgency", names(Level.values())),
            new FieldSpec("major", "Major incident", List.of("true", "false")),
            new FieldSpec("title", "Title", null),
            new FieldSpec("affectedUsers", "Affected users", null),
            new FieldSpec("escalationLevel", "Escalation level", List.of("0", "1", "2", "3")),
            new FieldSpec("source", "Source", names(IncidentSource.values())),
            new FieldSpec("childCount", "Linked reports", null),
            new FieldSpec("linkedToParent", "Linked to a parent incident", List.of("true", "false")),
            // Release 3 change facts (CHANGE_REQUEST_CREATED).
            new FieldSpec("changeRisk", "Change risk", List.of("LOW", "MEDIUM", "HIGH")),
            new FieldSpec("changeType", "Change type", List.of("STANDARD", "NORMAL", "EMERGENCY")),
            new FieldSpec("riskScore", "Change risk score", null),
            // Release 3 alert facts (ALERT_RECEIVED).
            new FieldSpec("alertSeverity", "Alert severity", List.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO")),
            new FieldSpec("alertSource", "Alert source", List.of("PROMETHEUS", "GRAFANA", "CLOUDWATCH", "GENERIC", "INTEGRATION", "MANUAL")),
            new FieldSpec("alertName", "Alert name (alertname / alarm name)", null),
            new FieldSpec("ciName", "Configuration item", null),
            new FieldSpec("ciType", "Configuration item type", List.of("APPLICATION", "SERVER", "DATABASE", "NETWORK_DEVICE", "CLOUD_RESOURCE",
                    "OPERATING_SYSTEM", "SOFTWARE", "BUSINESS_SERVICE", "INFRASTRUCTURE")),
            new FieldSpec("occurrenceCount", "Alert occurrences", null));

    private final WorkflowRepository workflows;
    private final WorkflowVersionRepository versions;
    private final WorkflowExecutionRepository executions;
    private final IncidentRepository incidents;
    private final DepartmentRepository departments;
    private final com.nexus360.team.TeamRepository teams;
    private final UserRepository users;
    private final IntegrationRepository integrations;
    private final RunbookRepository runbooks;
    private final com.nexus360.approval.ApprovalFlowRepository approvalFlows;
    private final WorkflowFactSource factSource;
    private final WorkflowTemplates templates;
    private final AuditService audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<WorkflowDto> list() {
        return workflows.findAllByOrderByNameAsc().stream().map(this::dto).toList();
    }

    @Transactional(readOnly = true)
    public WorkflowDto get(Long id) {
        return dto(load(id));
    }

    @Transactional(readOnly = true)
    public List<VersionDto> versions(Long id) {
        load(id);
        return versions.findByWorkflowIdOrderByVersionNumberDesc(id).stream()
                .map(v -> new VersionDto(v.getVersionNumber(), v.getDefinition(), v.getChangeNote(),
                        v.getCreatedById() == null ? null : users.findById(v.getCreatedById()).map(u -> u.getFullName()).orElse(null),
                        v.getCreatedAt()))
                .toList();
    }

    @Transactional
    public WorkflowDto create(WorkflowRequest r, Actor actor) {
        if (workflows.existsByNameIgnoreCase(r.name().trim())) {
            throw ApiException.conflict("A workflow with this name already exists");
        }
        validate(r.definition(), r.triggerEvent());
        Workflow wf = new Workflow();
        apply(wf, r);
        wf.setCurrentVersion(1);
        wf.setCreatedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        workflows.save(wf);
        saveVersion(wf, 1, r.definition(), r.changeNote() == null ? "Initial version" : r.changeNote(), actor);
        audit.record("WORKFLOW_CREATED", "WORKFLOW", wf.getId(), Map.of("name", wf.getName(), "trigger", wf.getTriggerEvent()));
        audit.record("WORKFLOW_PUBLISHED", "WORKFLOW", wf.getId(), Map.of("version", 1));
        if (wf.isActive()) {
            audit.record("WORKFLOW_ACTIVATED", "WORKFLOW", wf.getId(), Map.of("name", wf.getName()));
        }
        return dto(wf);
    }

    @Transactional
    public WorkflowDto update(Long id, WorkflowRequest r, Actor actor) {
        Workflow wf = load(id);
        validate(r.definition(), r.triggerEvent());
        WorkflowVersion currentVersion = versions.findByWorkflowIdAndVersionNumber(id, wf.getCurrentVersion()).orElse(null);
        Definition current = currentVersion == null ? null : currentVersion.getDefinition();
        boolean wasActive = wf.isActive();
        if (!Objects.equals(wf.getName(), r.name().trim()) && workflows.existsByNameIgnoreCase(r.name().trim())) {
            throw ApiException.conflict("A workflow with this name already exists");
        }
        apply(wf, r);
        if (current != null && !Objects.equals(current, r.definition())
                && Objects.equals(current.withLayout(null), r.definition().withLayout(null))) {
            // Only node positions changed: presentation data, kept on the current version without a new version.
            currentVersion.setDefinition(r.definition());
        } else if (!Objects.equals(current, r.definition())) {
            int next = versions.findByWorkflowIdOrderByVersionNumberDesc(id).stream().mapToInt(WorkflowVersion::getVersionNumber).max().orElse(0) + 1;
            saveVersion(wf, next, r.definition(), r.changeNote(), actor);
            wf.setCurrentVersion(next);
            audit.record("WORKFLOW_PUBLISHED", "WORKFLOW", wf.getId(), Map.of("version", next));
        }
        audit.record("WORKFLOW_UPDATED", "WORKFLOW", wf.getId(), Map.of("version", wf.getCurrentVersion(), "active", wf.isActive()));
        if (wasActive != wf.isActive()) {
            audit.record(wf.isActive() ? "WORKFLOW_ACTIVATED" : "WORKFLOW_DEACTIVATED", "WORKFLOW", id, Map.of("name", wf.getName()));
        }
        return dto(wf);
    }

    @Transactional
    public WorkflowDto setActive(Long id, boolean active) {
        Workflow wf = load(id);
        wf.setActive(active);
        audit.record(active ? "WORKFLOW_ACTIVATED" : "WORKFLOW_DEACTIVATED", "WORKFLOW", id, Map.of("name", wf.getName()));
        return dto(wf);
    }

    /** Rollback creates a new version that copies the chosen one, so history stays append-only. */
    @Transactional
    public WorkflowDto rollback(Long id, int versionNumber, Actor actor) {
        Workflow wf = load(id);
        WorkflowVersion target = versions.findByWorkflowIdAndVersionNumber(id, versionNumber)
                .orElseThrow(() -> ApiException.notFound("Workflow version", versionNumber));
        int next = versions.findByWorkflowIdOrderByVersionNumberDesc(id).stream().mapToInt(WorkflowVersion::getVersionNumber).max().orElse(0) + 1;
        validate(target.getDefinition(), wf.getTriggerEvent());
        saveVersion(wf, next, target.getDefinition(), "Rollback to version " + versionNumber, actor);
        wf.setCurrentVersion(next);
        audit.record("WORKFLOW_ROLLED_BACK", "WORKFLOW", id, Map.of("toVersion", versionNumber, "newVersion", next));
        audit.record("WORKFLOW_PUBLISHED", "WORKFLOW", id, Map.of("version", next));
        return dto(wf);
    }

    @Transactional(readOnly = true)
    public TestResult test(Long id, Long incidentId) {
        Workflow wf = load(id);
        Definition definition = versions.findByWorkflowIdAndVersionNumber(id, wf.getCurrentVersion()).orElseThrow().getDefinition();
        Incident incident = incidents.findDetailedById(incidentId).orElseThrow(() -> ApiException.notFound("Incident", incidentId));
        ConditionEvaluator.Evaluation evaluation = ConditionEvaluator.evaluate(definition.conditions(), factSource.facts(incident));
        WorkflowModel.Branch branch = evaluation.matched() ? WorkflowModel.Branch.YES : WorkflowModel.Branch.NO;
        return new TestResult(evaluation.matched(),
                evaluation.results().stream().map(r -> new RuleResultDto(r.rule(), r.matched(), r.actual())).toList(),
                definition.branch(branch).stream().map(ActionExecutor::describe).toList(), branch);
    }

    @Transactional(readOnly = true)
    public PageResponse<ExecutionDto> executions(Long workflowId, WorkflowExecution.Status status, Integer page, Integer size) {
        PageRequest pageable = PageRequest.of(page == null ? 0 : Math.max(0, page), size == null ? 20 : Math.min(Math.max(size, 1), 100));
        Page<WorkflowExecution> result;
        if (workflowId != null && status != null) {
            result = executions.findByWorkflowIdAndStatusOrderByStartedAtDesc(workflowId, status, pageable);
        } else if (workflowId != null) {
            result = executions.findByWorkflowIdOrderByStartedAtDesc(workflowId, pageable);
        } else if (status != null) {
            result = executions.findByStatusOrderByStartedAtDesc(status, pageable);
        } else {
            result = executions.findAllByOrderByStartedAtDesc(pageable);
        }
        Map<Long, String> numbers = new LinkedHashMap<>();
        List<Long> incidentIds = result.getContent().stream().map(WorkflowExecution::getIncidentId).filter(Objects::nonNull).distinct().toList();
        if (!incidentIds.isEmpty()) {
            incidents.findAllById(incidentIds).forEach(i -> numbers.put(i.getId(), i.getNumber()));
        }
        return PageResponse.of(result, e -> new ExecutionDto(e.getId(), e.getWorkflow().getId(), e.getWorkflow().getName(),
                e.getWorkflowVersion(), e.getTriggerEvent(), e.getIncidentId(), numbers.get(e.getIncidentId()), e.getStatus(),
                e.getAttempt(), e.getNextAttemptAt(), e.getErrorMessage(), e.getSteps(), e.getStartedAt(), e.getFinishedAt(), e.getBranch(),
                e.getServiceRequestId(), e.getChangeId(), e.getAlertId()));
    }

    @Transactional
    public void retry(Long executionId) {
        WorkflowExecution e = executions.findById(executionId).orElseThrow(() -> ApiException.notFound("Execution", executionId));
        if (e.getStatus() != WorkflowExecution.Status.FAILED && e.getStatus() != WorkflowExecution.Status.RETRY_SCHEDULED) {
            throw ApiException.conflict("Only failed executions can be retried");
        }
        e.setStatus(WorkflowExecution.Status.RETRY_SCHEDULED);
        e.setNextAttemptAt(clock.instant());
        e.setFinishedAt(null);
        audit.record("WORKFLOW_EXECUTION_RETRIED", "WORKFLOW_EXECUTION", executionId, null);
    }

    @Transactional(readOnly = true)
    public List<WorkflowTemplates.Template> templates() {
        return templates.all();
    }

    public Meta meta() {
        List<String> roles = names(Role.values());
        List<String> priorities = names(Priority.values());
        List<String> statuses = names(IncidentStatus.values());
        List<String> resolutionCodes = Arrays.stream(ResolutionCode.values()).filter(c -> c != ResolutionCode.CANCELLED).map(Enum::name).toList();
        List<ActionSpec> actions = List.of(
                new ActionSpec(ActionType.ASSIGN_DEPARTMENT, "Assign to department", List.of(new ParamSpec("departmentCode", "Department code", true, null))),
                new ActionSpec(ActionType.ASSIGN_TEAM, "Assign to team", List.of(new ParamSpec("teamCode", "Team code", true,
                        teams.findAllByOrderByNameAsc().stream().filter(com.nexus360.team.Team::isActive).map(com.nexus360.team.Team::getCode).toList()))),
                new ActionSpec(ActionType.ASSIGN_USER, "Assign to user", List.of(new ParamSpec("userEmail", "User e-mail", true, null))),
                new ActionSpec(ActionType.SET_PRIORITY, "Set priority", List.of(new ParamSpec("priority", "Priority", true, priorities))),
                new ActionSpec(ActionType.CHANGE_STATUS, "Change status", List.of(new ParamSpec("status", "New status (must be a valid transition)", true, statuses),
                        new ParamSpec("resolutionCode", "Resolution code (required for RESOLVED)", false, resolutionCodes),
                        new ParamSpec("note", "Reason / note ({{number}}, {{title}})", false, null))),
                new ActionSpec(ActionType.ADD_COMMENT, "Add comment", List.of(new ParamSpec("message", "Message ({{number}}, {{title}}, {{priority}})", true, null),
                        new ParamSpec("internal", "Internal work note", false, List.of("true", "false")))),
                new ActionSpec(ActionType.NOTIFY_ROLE, "Send notification to role", List.of(new ParamSpec("role", "Role", true, roles),
                        new ParamSpec("message", "Message", false, null))),
                new ActionSpec(ActionType.NOTIFY_DEPARTMENT, "Send notification to department", List.of(new ParamSpec("departmentCode", "Department code (blank = incident's)", false, null),
                        new ParamSpec("message", "Message", false, null))),
                new ActionSpec(ActionType.NOTIFY_ASSIGNEE, "Send notification to assignee", List.of(new ParamSpec("message", "Message", false, null))),
                new ActionSpec(ActionType.NOTIFY_USER, "Send notification to user", List.of(new ParamSpec("userEmail", "User e-mail", true, null),
                        new ParamSpec("message", "Message", false, null))),
                new ActionSpec(ActionType.ESCALATE, "Create escalation", List.of(new ParamSpec("reason", "Reason", false, null))),
                new ActionSpec(ActionType.DECLARE_MAJOR, "Declare major incident", List.of()),
                new ActionSpec(ActionType.SEND_WEBHOOK, "Send webhook", List.of(new ParamSpec("integrationId", "Integration id", true, null))),
                new ActionSpec(ActionType.START_RUNBOOK, "Trigger approved runbook", List.of(new ParamSpec("runbookId", "Runbook id", true, null))),
                new ActionSpec(ActionType.REQUEST_APPROVAL, "Request approval", List.of(new ParamSpec("flowCode", "Approval flow", true,
                        approvalFlows.findAllByOrderByNameAsc().stream().filter(com.nexus360.approval.ApprovalFlow::isActive)
                                .map(com.nexus360.approval.ApprovalFlow::getCode).toList()),
                        new ParamSpec("title", "Approval title ({{number}}, {{title}}; optional)", false, null))),
                new ActionSpec(ActionType.CREATE_INCIDENT, "Create incident from alert (ALERT_RECEIVED)", List.of(
                        new ParamSpec("impact", "Impact (default from the alert severity)", false, names(Level.values())),
                        new ParamSpec("urgency", "Urgency (default from the alert severity)", false, names(Level.values())))));
        return new Meta(List.of(Trigger.values()), FIELDS, List.of(Operator.values()), actions);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Validates a definition against the workflow model and the referenced records (departments, users, integrations,
     * runbooks). Also used to check SA AI workflow suggestions and templates before they are returned.
     */
    public void validate(Definition definition, Trigger trigger) {
        if (definition == null || definition.conditions() == null || definition.actions() == null || definition.actions().isEmpty()) {
            throw ApiException.badRequest("A workflow needs conditions and at least one action");
        }
        if (!"ALL".equals(definition.conditions().match()) && !"ANY".equals(definition.conditions().match())) {
            throw ApiException.badRequest("Condition match must be ALL or ANY");
        }
        if (definition.actions().size() > 20 || definition.elseActions().size() > 20) {
            throw ApiException.badRequest("At most 20 actions per branch");
        }
        if (trigger == Trigger.SCHEDULED && (definition.schedule() == null || definition.schedule().intervalMinutes() < 15)) {
            throw ApiException.badRequest("SCHEDULED workflows need schedule.intervalMinutes of at least 15");
        }
        if (definition.schedule() != null && definition.schedule().intervalMinutes() > 10_080) {
            throw ApiException.badRequest("schedule.intervalMinutes must be at most 10080 (one week)");
        }
        List<String> knownFields = FIELDS.stream().map(FieldSpec::field).toList();
        if (definition.conditions().rules() != null) {
            for (ConditionRule rule : definition.conditions().rules()) {
                if (rule == null || rule.field() == null || rule.operator() == null) {
                    throw ApiException.badRequest("Every condition needs a field and an operator");
                }
                if (!knownFields.contains(rule.field())) {
                    throw ApiException.badRequest("Unknown condition field '" + rule.field() + "'");
                }
            }
        }
        List<ActionDef> all = new java.util.ArrayList<>(definition.actions());
        all.addAll(definition.elseActions());
        for (ActionDef action : all) {
            if (action == null || action.type() == null) {
                throw ApiException.badRequest("Every action needs a type");
            }
            Map<String, String> p = action.params() == null ? Map.of() : action.params();
            switch (action.type()) {
                case ASSIGN_DEPARTMENT -> departments.findByCodeIgnoreCase(require(p, "departmentCode", action))
                        .orElseThrow(() -> ApiException.badRequest("Unknown department code " + p.get("departmentCode")));
                case ASSIGN_TEAM -> teams.findByCodeIgnoreCase(require(p, "teamCode", action)).filter(com.nexus360.team.Team::isActive)
                        .orElseThrow(() -> ApiException.badRequest("Unknown or inactive team code " + p.get("teamCode")));
                case NOTIFY_DEPARTMENT -> {
                    String code = p.get("departmentCode");
                    if (code != null && !code.isBlank() && departments.findByCodeIgnoreCase(code).isEmpty()) {
                        throw ApiException.badRequest("Unknown department code " + code);
                    }
                }
                case ASSIGN_USER -> users.findByEmailIgnoreCase(require(p, "userEmail", action)).filter(u -> u.getRole().isStaff())
                        .orElseThrow(() -> ApiException.badRequest("No staff member with e-mail " + p.get("userEmail")));
                case SET_PRIORITY -> parseEnum(Priority.class, require(p, "priority", action), "priority");
                case CHANGE_STATUS -> {
                    parseEnum(IncidentStatus.class, require(p, "status", action), "status");
                    if (IncidentStatus.RESOLVED.name().equalsIgnoreCase(p.get("status").trim())) {
                        parseEnum(ResolutionCode.class, require(p, "resolutionCode", action), "resolutionCode");
                        if (ResolutionCode.CANCELLED.name().equalsIgnoreCase(p.get("resolutionCode").trim())) {
                            throw ApiException.badRequest("Use status CLOSED to cancel; CANCELLED is not a resolution code");
                        }
                    }
                }
                case NOTIFY_USER -> users.findByEmailIgnoreCase(require(p, "userEmail", action)).filter(u -> u.isActive())
                        .orElseThrow(() -> ApiException.badRequest("No active user with e-mail " + p.get("userEmail")));
                case ADD_COMMENT -> require(p, "message", action);
                case NOTIFY_ROLE -> parseEnum(Role.class, require(p, "role", action), "role");
                case SEND_WEBHOOK -> {
                    Long integrationId = parseId(require(p, "integrationId", action));
                    if (!integrations.existsById(integrationId)) {
                        throw ApiException.badRequest("Integration " + integrationId + " does not exist");
                    }
                }
                case START_RUNBOOK -> {
                    Long runbookId = parseId(require(p, "runbookId", action));
                    if (!runbooks.existsById(runbookId)) {
                        throw ApiException.badRequest("Runbook " + runbookId + " does not exist");
                    }
                }
                case REQUEST_APPROVAL -> approvalFlows.findByCodeIgnoreCase(require(p, "flowCode", action)).filter(com.nexus360.approval.ApprovalFlow::isActive)
                        .orElseThrow(() -> ApiException.badRequest("Unknown or inactive approval flow " + p.get("flowCode")));
                case CREATE_INCIDENT -> {
                    if (trigger != Trigger.ALERT_RECEIVED) {
                        throw ApiException.badRequest("CREATE_INCIDENT applies to ALERT_RECEIVED workflows only");
                    }
                    if (p.get("impact") != null && !p.get("impact").isBlank()) {
                        parseEnum(Level.class, p.get("impact"), "impact");
                    }
                    if (p.get("urgency") != null && !p.get("urgency").isBlank()) {
                        parseEnum(Level.class, p.get("urgency"), "urgency");
                    }
                }
                default -> { }
            }
        }
    }

    private static String require(Map<String, String> params, String key, ActionDef action) {
        String v = params.get(key);
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest(action.type() + " requires '" + key + "'");
        }
        return v.trim();
    }

    private static <E extends Enum<E>> void parseEnum(Class<E> type, String value, String label) {
        try {
            Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid " + label + " '" + value + "'");
        }
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("'" + value + "' is not a valid id");
        }
    }

    private void saveVersion(Workflow wf, int number, Definition definition, String note, Actor actor) {
        WorkflowVersion v = new WorkflowVersion();
        v.setWorkflow(wf);
        v.setVersionNumber(number);
        v.setDefinition(definition);
        v.setChangeNote(note);
        v.setCreatedById(actor.id());
        versions.save(v);
    }

    private static void apply(Workflow wf, WorkflowRequest r) {
        wf.setName(r.name().trim());
        wf.setDescription(r.description());
        wf.setTriggerEvent(r.triggerEvent());
        wf.setActive(r.active());
        wf.setMaxExecutionsPerHour(r.maxExecutionsPerHour());
        wf.setMaxRetries(r.maxRetries());
        wf.setRetryBackoffSeconds(r.retryBackoffSeconds());
    }

    private Workflow load(Long id) {
        return workflows.findById(id).orElseThrow(() -> ApiException.notFound("Workflow", id));
    }

    private WorkflowDto dto(Workflow wf) {
        Instant since = clock.instant().minus(Duration.ofHours(24));
        Definition definition = versions.findByWorkflowIdAndVersionNumber(wf.getId(), wf.getCurrentVersion())
                .map(WorkflowVersion::getDefinition).orElse(null);
        return new WorkflowDto(wf.getId(), wf.getName(), wf.getDescription(), wf.getTriggerEvent(), wf.isActive(), wf.getCurrentVersion(),
                wf.getMaxExecutionsPerHour(), wf.getMaxRetries(), wf.getRetryBackoffSeconds(), definition,
                executions.countByWorkflowIdAndStartedAtAfter(wf.getId(), since),
                executions.countByWorkflowIdAndStatusAndStartedAtAfter(wf.getId(), WorkflowExecution.Status.FAILED, since),
                executions.lastExecutedAt(wf.getId()), wf.getCreatedBy() == null ? null : wf.getCreatedBy().getFullName(),
                wf.getUpdatedAt(), wf.isDemo());
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
