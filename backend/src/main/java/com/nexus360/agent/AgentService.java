package com.nexus360.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.agent.AgentDefinitions.Agent;
import com.nexus360.agent.AgentDtos.ActionDto;
import com.nexus360.agent.AgentDtos.ActionKind;
import com.nexus360.agent.AgentDtos.AgentCode;
import com.nexus360.agent.AgentDtos.AgentDto;
import com.nexus360.agent.AgentDtos.DismissRequest;
import com.nexus360.agent.AgentDtos.EvalCaseDto;
import com.nexus360.agent.AgentDtos.EvalCaseRequest;
import com.nexus360.agent.AgentDtos.EvalRunDto;
import com.nexus360.agent.AgentDtos.EvalRunRequest;
import com.nexus360.agent.AgentDtos.FeedbackDto;
import com.nexus360.agent.AgentDtos.FeedbackRequest;
import com.nexus360.agent.AgentDtos.PolicyDto;
import com.nexus360.agent.AgentDtos.PolicyRequest;
import com.nexus360.agent.AgentDtos.RunDetail;
import com.nexus360.agent.AgentDtos.RunDto;
import com.nexus360.agent.AgentDtos.RunType;
import com.nexus360.agent.AgentDtos.StartRunRequest;
import com.nexus360.agent.AgentDtos.ToolDto;
import com.nexus360.agent.AgentDtos.VersionDto;
import com.nexus360.agent.AgentPolicyService.Policy;
import com.nexus360.agent.AgentRunStore.RunRow;
import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalHandler;
import com.nexus360.approval.ApprovalService;
import com.nexus360.audit.AuditService;
import com.nexus360.change.ChangeDtos.ChangeDetail;
import com.nexus360.change.ChangeService;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.incident.Category;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentQueryService;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentService;
import com.nexus360.incident.IncidentTimeline;
import com.nexus360.incident.Priority;
import com.nexus360.knowledge.KnowledgeArticle;
import com.nexus360.knowledge.KnowledgeDtos;
import com.nexus360.knowledge.KnowledgeService;
import com.nexus360.problem.ProblemService;
import com.nexus360.runbook.RunbookService;
import com.nexus360.security.Actor;
import com.nexus360.security.Hashing;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRouting;
import com.nexus360.user.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SA Agentic AI orchestration, action-approval lifecycle, version registry, evaluation suites and governance metrics
 * (Release 4 §4.4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService implements ApplicationRunner, ApprovalHandler {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> LIST_STR_TYPE = new TypeReference<>() {};

    private final AgentDefinitions definitions;
    private final AgentPolicyService policies;
    private final AgentTools tools;
    private final AgentRunStore store;
    private final AgentRunner runner;
    private final IncidentQueryService incidentQueries;
    private final IncidentRepository incidents;
    private final IncidentService incidentService;
    private final IncidentTimeline timeline;
    private final ChangeService changes;
    private final ProblemService problemService;
    private final KnowledgeService knowledgeService;
    private final RunbookService runbookService;
    private final TeamRouting teamRouting;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        registerVersions();
    }

    /** Ensures every server-defined agent version is recorded in {@code agent_versions}. */
    public void registerVersions() {
        for (Agent a : definitions.all()) {
            jdbc.update("""
                    insert into agent_versions (agent_code, version, prompt_sha256, prompt, output_schema, tools, first_seen_at)
                    values (?, ?, ?, ?, ?::jsonb, ?::jsonb, now())
                    on conflict (agent_code, version) do update set
                        prompt_sha256 = excluded.prompt_sha256,
                        prompt = excluded.prompt,
                        output_schema = excluded.output_schema,
                        tools = excluded.tools
                    """, a.code().name(), a.version(), Hashing.sha256Hex(a.prompt()), a.prompt(),
                    store.write(a.schema()), store.write(a.tools()));
        }
    }

    // ------------------------------------------------------------------ catalog & policies

    public List<AgentDto> agents() {
        Map<AgentCode, PolicyDto> byCode = policies.all().stream()
                .map(Policy::dto)
                .collect(Collectors.toMap(PolicyDto::agentCode, Function.identity()));
        List<AgentDto> out = new ArrayList<>();
        for (Agent a : definitions.all()) {
            List<RunType> runTypes = new ArrayList<>();
            for (RunType rt : RunType.values()) {
                boolean present = AgentDefinitions.stages(rt).stream().anyMatch(s -> s.contains(a.code()));
                if (present) {
                    runTypes.add(rt);
                }
            }
            out.add(new AgentDto(a.code(), a.name(), a.description(), a.version(), a.tools(), runTypes, a.schema(),
                    byCode.getOrDefault(a.code(), policies.get(a.code()).dto())));
        }
        return out;
    }

    public List<ToolDto> tools() {
        return tools.catalogue();
    }

    public List<VersionDto> versions(AgentCode agentCode) {
        if (agentCode != null) {
            return jdbc.query("select * from agent_versions where agent_code = ? order by first_seen_at desc, id desc",
                    this::mapVersion, agentCode.name());
        }
        return jdbc.query("select * from agent_versions order by agent_code asc, first_seen_at desc, id desc", this::mapVersion);
    }

    public List<PolicyDto> policies() {
        return policies.all().stream().map(Policy::dto).toList();
    }

    public PolicyDto updatePolicy(AgentCode code, PolicyRequest r, Actor actor) {
        return policies.update(code, r, actor);
    }

    // ------------------------------------------------------------------ runs

    public RunDetail startRun(StartRunRequest r, boolean async, Actor actor) {
        if (r == null || r.runType() == null) {
            throw ApiException.badRequest("runType is required");
        }
        String subjectType = null;
        Long subjectId = null;
        String subjectNumber = null;
        String question = r.question() == null || r.question().isBlank() ? null : r.question().trim();
        boolean simulated = false;
        boolean demo = false;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("runType", r.runType().name());

        switch (r.runType()) {
            case INVESTIGATE_INCIDENT -> {
                if (r.incidentId() == null) {
                    throw ApiException.badRequest("incidentId is required for INVESTIGATE_INCIDENT");
                }
                Incident inc = incidentQueries.load(r.incidentId(), actor);
                subjectType = "INCIDENT";
                subjectId = inc.getId();
                subjectNumber = inc.getNumber();
                simulated = inc.isSimulated();
                demo = inc.isDemo();
                input.put("incidentId", inc.getId());
                input.put("incidentNumber", inc.getNumber());
                input.put("title", inc.getTitle());
                if (question != null) {
                    input.put("question", question);
                }
            }
            case ASSESS_CHANGE -> {
                if (r.changeId() == null) {
                    throw ApiException.badRequest("changeId is required for ASSESS_CHANGE");
                }
                ChangeDetail chg = changes.get(r.changeId(), actor);
                subjectType = "CHANGE";
                subjectId = chg.id();
                subjectNumber = chg.number();
                simulated = false;
                demo = chg.demo();
                input.put("changeId", chg.id());
                input.put("changeNumber", chg.number());
                input.put("title", chg.title());
                if (question != null) {
                    input.put("question", question);
                }
            }
            case ANSWER_QUESTION -> {
                if (question == null || question.length() < 3) {
                    throw ApiException.badRequest("question of at least 3 characters is required for ANSWER_QUESTION");
                }
                if (r.incidentId() != null) {
                    Incident inc = incidentQueries.load(r.incidentId(), actor);
                    subjectType = "INCIDENT";
                    subjectId = inc.getId();
                    subjectNumber = inc.getNumber();
                    simulated = inc.isSimulated();
                    demo = inc.isDemo();
                    input.put("incidentId", inc.getId());
                }
                input.put("question", question);
            }
        }

        AgentCode primary = AgentDefinitions.primary(r.runType());
        Policy policy = policies.get(primary);
        if (!policy.enabled()) {
            throw ApiException.conflict("Agent " + primary + " is disabled by governance policy");
        }
        if (!policy.roleAllowed(actor.role().name())) {
            throw ApiException.forbidden("Your role (" + actor.role() + ") is not permitted by the " + primary + " agent policy");
        }

        int timeout = r.timeoutSeconds() != null
                ? Math.max(5, Math.min(600, r.timeoutSeconds()))
                : defaultTimeout(r.runType(), policy.timeoutSeconds());

        if (!runner.tryReserve()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Agent runner is at capacity (at most " + runner.maxConcurrent() + " concurrent runs); try again shortly");
        }

        Map<String, String> versionMap = new LinkedHashMap<>();
        AgentDefinitions.stages(r.runType()).forEach(stage -> stage.forEach(c -> versionMap.put(c.name(), definitions.get(c).version())));
        Instant now = clock.instant();
        Long runId;
        try {
            runId = jdbc.queryForObject("""
                    insert into agent_runs (
                        run_type, status, subject_type, subject_id, subject_number, question, input,
                        agent_versions, timeout_seconds, requested_by_id, requested_by_name, requested_by_role,
                        simulated, is_demo, queued_at, created_at, updated_at
                    ) values (?, 'QUEUED', ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    returning id
                    """, Long.class, r.runType().name(), subjectType, subjectId, subjectNumber, question,
                    store.write(input), store.write(versionMap), timeout, actor.id(), actor.name(), actor.role().name(),
                    simulated, demo, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        } catch (RuntimeException e) {
            runner.release();
            throw e;
        }

        audit.record("AGENT_RUN_STARTED", "AGENT_RUN", runId,
                Map.of("runType", r.runType().name(), "subject", Objects.toString(subjectNumber, ""), "simulated", simulated));

        Future<?> future = runner.submit(runId, actor);
        if (!async) {
            long waitMs = Math.min(timeout * 1000L, 5_000L);
            try {
                future.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Still running in the background; return current progress.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.warn("Agent run {} execution error: {}", runId, e.getMessage());
            }
        }
        return getRun(runId, actor);
    }

    private int defaultTimeout(RunType runType, int primaryTimeout) {
        if (runType == RunType.INVESTIGATE_INCIDENT) {
            return Math.max(30, Math.min(300, primaryTimeout * 3));
        }
        return Math.max(10, Math.min(300, primaryTimeout));
    }

    public PageResponse<RunDto> listRuns(RunType runType, String status, String subjectType, Long subjectId, Long requestedById,
                                         boolean includeSimulated, Integer page, Integer size, Actor actor) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 25 : Math.min(size, 100);
        StringBuilder where = new StringBuilder(" where r.eval_run_id is null");
        List<Object> params = new ArrayList<>();
        if (!includeSimulated) {
            where.append(" and not r.simulated");
        }
        if (runType != null) {
            where.append(" and r.run_type = ?");
            params.add(runType.name());
        }
        if (status != null && !status.isBlank()) {
            where.append(" and r.status = ?");
            params.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (subjectType != null && !subjectType.isBlank()) {
            where.append(" and r.subject_type = ?");
            params.add(subjectType.trim().toUpperCase(Locale.ROOT));
        }
        if (subjectId != null) {
            where.append(" and r.subject_id = ?");
            params.add(subjectId);
        }
        if (requestedById != null) {
            where.append(" and r.requested_by_id = ?");
            params.add(requestedById);
        }
        if (!actor.isStaff() && actor.role() != Role.AUDITOR) {
            where.append(" and r.requested_by_id = ?");
            params.add(actor.id());
        }
        long total = OptionalLong.of(jdbc.queryForObject("select count(*) from agent_runs r" + where, Long.class, params.toArray()));
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(s);
        pageParams.add((long) p * s);
        List<RunDto> rows = jdbc.query("select r.* from agent_runs r" + where + " order by r.created_at desc, r.id desc limit ? offset ?",
                (rs, i) -> store.runDto(rs, actor.id()), pageParams.toArray());
        int totalPages = s == 0 ? 0 : (int) Math.ceil((double) total / s);
        return new PageResponse<>(rows, p, s, total, totalPages);
    }

    public RunDetail getRun(long id, Actor actor) {
        RunRow raw = store.run(id);
        if (raw == null) {
            throw ApiException.notFound("Agent run", id);
        }
        requireRunView(raw, actor);
        RunDto runDto = store.runDto(id, actor.id());
        return new RunDetail(
                runDto,
                store.runJson(id, "input"),
                store.runJson(id, "output"),
                store.steps(id),
                store.actions(id),
                store.feedback(id));
    }

    private void requireRunView(RunRow run, Actor actor) {
        if (!actor.isStaff() && actor.role() != Role.AUDITOR && !Objects.equals(run.requestedById(), actor.id())) {
            throw ApiException.forbidden("You do not have permission to view this agent run");
        }
        if ("INCIDENT".equals(run.subjectType()) && run.subjectId() != null && !actor.isStaff() && actor.role() != Role.AUDITOR) {
            incidentQueries.load(run.subjectId(), actor);
        }
    }

    public RunDetail cancel(long id, Actor actor) {
        RunRow run = store.run(id);
        if (run == null) {
            throw ApiException.notFound("Agent run", id);
        }
        boolean canCancel = Objects.equals(run.requestedById(), actor.id())
                || actor.hasRole(Role.SYSTEM_ADMIN, Role.IT_ADMIN, Role.IT_MANAGER, Role.INCIDENT_MANAGER);
        if (!canCancel) {
            throw ApiException.forbidden("Only the requester or AI governance administrators can cancel a run");
        }
        if (List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(run.status())) {
            throw ApiException.conflict("Agent run " + id + " has already finished (" + run.status() + ")");
        }
        Instant now = clock.instant();
        jdbc.update("update agent_runs set cancel_requested = true, cancelled_by_name = ?, updated_at = ? where id = ?",
                actor.name(), Timestamp.from(now), id);
        runner.cancel(id);
        RunRow after = store.run(id);
        if (after != null && List.of("QUEUED", "RUNNING").contains(after.status())) {
            store.finish(id, "CANCELLED", null, "Cancelled by " + actor.name(), null, null, "Cancelled by user", null, now);
        }
        audit.record("AGENT_RUN_CANCELLED", "AGENT_RUN", id, Map.of("cancelledBy", actor.name()));
        return getRun(id, actor);
    }

    @Transactional
    public FeedbackDto feedback(long id, FeedbackRequest r, Actor actor) {
        getRun(id, actor);
        Instant now = clock.instant();
        String comment = r.comment() == null || r.comment().isBlank() ? null : r.comment().trim();
        Long fbId = jdbc.queryForObject("""
                insert into agent_feedback (run_id, user_id, user_name, helpful, comment, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (run_id, user_id) do update set
                    helpful = excluded.helpful,
                    comment = excluded.comment,
                    updated_at = excluded.updated_at
                returning id
                """, Long.class, id, actor.id(), actor.name(), r.helpful(), comment, Timestamp.from(now), Timestamp.from(now));
        audit.record("AGENT_FEEDBACK_RECORDED", "AGENT_RUN", id, Map.of("helpful", r.helpful()));
        return store.feedback(id).stream().filter(f -> Objects.equals(f.id(), fbId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ proposed actions & approvals

    @Transactional
    public ActionDto proposeAction(long runId, long actionId, Actor actor) {
        RunDetail detail = getRun(runId, actor);
        ActionDto action = store.action(runId, actionId);
        if (action == null) {
            throw ApiException.notFound("Agent proposed action", actionId);
        }
        if (!"PROPOSED".equals(action.status())) {
            throw ApiException.conflict("Action " + actionId + " is already " + action.status());
        }
        Role approverRole = approverRoleFor(action.kind());
        Long refId = action.targetId() != null ? action.targetId() : runId;
        String refNumber = detail.run().subjectNumber() != null ? detail.run().subjectNumber() : "RUN-" + runId;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentActionId", action.id());
        payload.put("runId", runId);
        payload.put("actionKind", action.kind().name());
        payload.put("targetType", action.targetType());
        payload.put("targetId", action.targetId());
        payload.put("params", action.params() == null ? Map.of() : action.params());

        Approval approval = approvals.request(
                Approval.Type.AI_ACTION,
                refId,
                refNumber,
                "SA Agent action: " + action.title(),
                action.rationale(),
                payload,
                approverRole,
                actor,
                detail.run().demo(),
                "/agents?runId=" + runId);

        jdbc.update("update agent_run_actions set status = 'PENDING_APPROVAL', approval_id = ? where id = ?",
                approval.getId(), actionId);

        if ("INCIDENT".equals(action.targetType()) && action.targetId() != null) {
            incidents.findDetailedById(action.targetId()).ifPresent(inc ->
                    timeline.record(inc, actor, "AI", "Sent SA Agent action for approval: " + action.title()));
        }
        audit.record("AGENT_ACTION_APPROVAL_REQUESTED", "AGENT_ACTION", actionId,
                Map.of("runId", runId, "actionKind", action.kind().name(), "approvalId", approval.getId()));
        return store.actionById(actionId);
    }

    @Transactional
    public ActionDto dismissAction(long runId, long actionId, DismissRequest r, Actor actor) {
        getRun(runId, actor);
        ActionDto action = store.action(runId, actionId);
        if (action == null) {
            throw ApiException.notFound("Agent proposed action", actionId);
        }
        if (!"PROPOSED".equals(action.status())) {
            throw ApiException.conflict("Action " + actionId + " is already " + action.status());
        }
        Instant now = clock.instant();
        String reason = r.reason().trim();
        jdbc.update("""
                update agent_run_actions
                set status = 'DISMISSED', decided_by_name = ?, decided_at = ?, decision_comment = ?, updated_at = ?
                where id = ?
                """, actor.name(), Timestamp.from(now), reason, Timestamp.from(now), actionId);
        audit.record("AGENT_ACTION_DISMISSED", "AGENT_ACTION", actionId,
                Map.of("runId", runId, "actionKind", action.kind().name(), "reason", reason));
        return store.actionById(actionId);
    }

    @Override
    public Approval.Type type() {
        return Approval.Type.AI_ACTION;
    }

    @Override
    @Transactional
    public void onDecision(Approval approval, boolean approved, Actor approver) {
        if (approval.getPayload() == null || !approval.getPayload().containsKey("agentActionId")) {
            return;
        }
        Object rawId = approval.getPayload().get("agentActionId");
        long actionId = rawId instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(rawId));
        ActionDto action = store.actionById(actionId);
        if (action == null) {
            return;
        }
        Instant now = clock.instant();

        if (!approved) {
            jdbc.update("""
                    update agent_run_actions
                    set status = 'REJECTED', decided_by_name = ?, decided_at = ?, decision_comment = ?, updated_at = ?
                    where id = ?
                    """, approver.name(), Timestamp.from(now), approval.getDecisionComment(), Timestamp.from(now), actionId);
            if ("INCIDENT".equals(action.targetType()) && action.targetId() != null) {
                incidents.findDetailedById(action.targetId()).ifPresent(inc ->
                        timeline.record(inc, approver, "AI", "SA Agent action rejected by " + approver.name() + ": " + approval.getDecisionComment()));
            }
            audit.record("AGENT_ACTION_REJECTED", "AGENT_ACTION", actionId,
                    Map.of("runId", action.runId(), "actionKind", action.kind().name(), "approver", approver.name()));
            return;
        }

        try {
            String resultSummary = applyApprovedAction(action, approver);
            jdbc.update("""
                    update agent_run_actions
                    set status = 'APPLIED', decided_by_name = ?, decided_at = ?, decision_comment = ?, result = ?, updated_at = ?
                    where id = ?
                    """, approver.name(), Timestamp.from(now), approval.getDecisionComment(), resultSummary, Timestamp.from(now), actionId);
            if ("INCIDENT".equals(action.targetType()) && action.targetId() != null) {
                incidents.findDetailedById(action.targetId()).ifPresent(inc ->
                        timeline.record(inc, approver, "AI", "SA Agent action approved and applied by " + approver.name() + ": " + action.title()));
            }
            audit.record("AGENT_ACTION_APPLIED", "AGENT_ACTION", actionId,
                    Map.of("runId", action.runId(), "actionKind", action.kind().name(), "approver", approver.name(), "result", resultSummary));
        } catch (RuntimeException e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jdbc.update("""
                    update agent_run_actions
                    set status = 'FAILED', decided_by_name = ?, decided_at = ?, decision_comment = ?, result = ?, updated_at = ?
                    where id = ?
                    """, approver.name(), Timestamp.from(now), approval.getDecisionComment(), err, Timestamp.from(now), actionId);
            throw e;
        }
    }

    private String applyApprovedAction(ActionDto action, Actor approver) {
        Map<String, Object> params = action.params() == null ? Map.of() : action.params();
        return switch (action.kind()) {
            case ASSIGN_TEAM -> {
                Long incId = action.targetId() != null ? action.targetId() : parseLong(params.get("incidentId"), "incidentId");
                Long teamId = parseLong(params.get("teamId"), "teamId");
                Incident inc = incidents.findDetailedById(incId).orElseThrow(() -> ApiException.notFound("Incident", incId));
                Team team = teamRouting.activeById(teamId).orElseThrow(() -> ApiException.badRequest("Team " + teamId + " does not exist or is inactive"));
                incidentService.assignToTeamInternal(inc, team, null, approver);
                yield "Assigned " + inc.getNumber() + " to team " + team.getName();
            }
            case CHANGE_PRIORITY -> {
                Long incId = action.targetId() != null ? action.targetId() : parseLong(params.get("incidentId"), "incidentId");
                Priority priority = Priority.valueOf(String.valueOf(params.getOrDefault("priority", "HIGH")).trim().toUpperCase(Locale.ROOT));
                Incident inc = incidents.findDetailedById(incId).orElseThrow(() -> ApiException.notFound("Incident", incId));
                incidentService.setPriorityInternal(inc, priority, approver, "Approved SA Agent action: " + action.title());
                yield "Priority of " + inc.getNumber() + " set to " + priority.name();
            }
            case LINK_PROBLEM -> {
                Long incId = action.targetId() != null ? action.targetId() : parseLong(params.get("incidentId"), "incidentId");
                Long problemId = parseLong(params.get("problemId"), "problemId");
                problemService.link(problemId, List.of(incId), approver);
                yield "Linked incident " + incId + " to problem " + problemId;
            }
            case PUBLISH_KB_DRAFT -> {
                Category category = Category.OTHER;
                Long serviceId = null;
                if (action.targetId() != null) {
                    Incident inc = incidents.findDetailedById(action.targetId()).orElse(null);
                    if (inc != null) {
                        category = inc.getCategory();
                        serviceId = inc.getService() == null ? null : inc.getService().getId();
                    }
                }
                if (params.containsKey("category")) {
                    try {
                        category = Category.valueOf(String.valueOf(params.get("category")).trim().toUpperCase(Locale.ROOT));
                    } catch (RuntimeException ignored) { }
                }
                String rawTitle = String.valueOf(params.getOrDefault("title", action.title())).trim();
                String title = rawTitle.length() < 5 ? rawTitle + " — troubleshooting guide" : rawTitle.length() > 200 ? rawTitle.substring(0, 200) : rawTitle;
                String rawSummary = String.valueOf(params.getOrDefault("summary", action.rationale() == null ? title : action.rationale())).trim();
                String summary = rawSummary.length() < 10 ? title + " (" + rawSummary + ")" : rawSummary.length() > 500 ? rawSummary.substring(0, 500) : rawSummary;
                String rawContent = String.valueOf(params.getOrDefault("content",
                        "## Symptoms\n" + summary + "\n\n## Resolution steps\n1. Verify service health and recent changes.\n2. Apply standard recovery steps and confirm monitoring returns to normal.")).trim();
                String content = rawContent.length() < 20 ? rawContent + "\n\nAdditional troubleshooting details pending review." : rawContent;
                KnowledgeDtos.ArticleDetail created = knowledgeService.create(new KnowledgeDtos.ArticleRequest(
                        title, summary, content, KnowledgeArticle.ArticleType.SOLUTION, category, serviceId,
                        List.of("agent-draft"), KnowledgeArticle.Status.DRAFT, "Draft created from approved SA Agent action"), approver);
                yield "Created draft article " + created.number();
            }
            case RUN_PLAYBOOK -> {
                Long incId = action.targetId() != null ? action.targetId() : parseLong(params.get("incidentId"), "incidentId");
                Long runbookId = parseLong(params.get("runbookId"), "runbookId");
                RunbookService.ExecutionDto exec = runbookService.startPreApproved(runbookId, incId, approver);
                yield "Started runbook execution #" + exec.id() + " (" + exec.status() + ")";
            }
            case DECLARE_MAJOR -> {
                Long incId = action.targetId() != null ? action.targetId() : parseLong(params.get("incidentId"), "incidentId");
                Incident inc = incidents.findDetailedById(incId).orElseThrow(() -> ApiException.notFound("Incident", incId));
                incidentService.setMajorInternal(inc, true, approver);
                yield "Declared " + inc.getNumber() + " as a major incident";
            }
        };
    }

    private static Role approverRoleFor(ActionKind kind) {
        return switch (kind) {
            case CHANGE_PRIORITY, DECLARE_MAJOR, RUN_PLAYBOOK -> Role.INCIDENT_MANAGER;
            case ASSIGN_TEAM, LINK_PROBLEM, PUBLISH_KB_DRAFT -> Role.IT_MANAGER;
        };
    }

    private static Long parseLong(Object v, String name) {
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Invalid numeric parameter " + name);
        }
    }

    // ------------------------------------------------------------------ evaluation suites

    public List<EvalCaseDto> listEvalCases(AgentCode agentCode) {
        if (agentCode != null) {
            return jdbc.query("select * from agent_eval_cases where agent_code = ? order by id asc", this::mapEvalCase, agentCode.name());
        }
        return jdbc.query("select * from agent_eval_cases order by id asc", this::mapEvalCase);
    }

    @Transactional
    public EvalCaseDto saveEvalCase(Long id, EvalCaseRequest r, Actor actor) {
        String subjectType = null;
        Long subjectId = null;
        String question = r.question() == null || r.question().isBlank() ? null : r.question().trim();
        switch (r.runType()) {
            case INVESTIGATE_INCIDENT -> {
                if (r.incidentId() == null) {
                    throw ApiException.badRequest("incidentId is required for INVESTIGATE_INCIDENT evaluation cases");
                }
                subjectType = "INCIDENT";
                subjectId = r.incidentId();
            }
            case ASSESS_CHANGE -> {
                if (r.changeId() == null) {
                    throw ApiException.badRequest("changeId is required for ASSESS_CHANGE evaluation cases");
                }
                subjectType = "CHANGE";
                subjectId = r.changeId();
            }
            case ANSWER_QUESTION -> {
                if (question == null || question.length() < 3) {
                    throw ApiException.badRequest("question is required for ANSWER_QUESTION evaluation cases");
                }
                if (r.incidentId() != null) {
                    subjectType = "INCIDENT";
                    subjectId = r.incidentId();
                }
            }
        }
        Map<String, Object> expected = new LinkedHashMap<>();
        if (r.expected().category() != null && !r.expected().category().isBlank()) {
            expected.put("category", r.expected().category().trim());
        }
        if (r.expected().priority() != null && !r.expected().priority().isBlank()) {
            expected.put("priority", r.expected().priority().trim());
        }
        if (r.expected().rootCauseComponent() != null && !r.expected().rootCauseComponent().isBlank()) {
            expected.put("rootCauseComponent", r.expected().rootCauseComponent().trim());
        }
        if (r.expected().kbArticleIds() != null && !r.expected().kbArticleIds().isEmpty()) {
            expected.put("kbArticleIds", r.expected().kbArticleIds());
        }
        if (r.expected().keywords() != null && !r.expected().keywords().isEmpty()) {
            expected.put("keywords", r.expected().keywords().stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
        }
        if (expected.isEmpty()) {
            throw ApiException.badRequest("expected must specify at least one check (category, priority, rootCauseComponent, kbArticleIds or keywords)");
        }
        boolean enabled = r.enabled() == null || r.enabled();
        Instant now = clock.instant();
        Long savedId;
        if (id == null) {
            savedId = jdbc.queryForObject("""
                    insert into agent_eval_cases (
                        name, agent_code, run_type, subject_type, subject_id, question, expected,
                        enabled, is_demo, created_by_name, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, false, ?, ?, ?)
                    returning id
                    """, Long.class, r.name().trim(), r.agentCode().name(), r.runType().name(), subjectType, subjectId,
                    question, store.write(expected), enabled, actor.name(), Timestamp.from(now), Timestamp.from(now));
            audit.record("AGENT_EVAL_CASE_CREATED", "AGENT_EVAL_CASE", savedId, Map.of("name", r.name().trim(), "agentCode", r.agentCode().name()));
        } else {
            int updated = jdbc.update("""
                    update agent_eval_cases set
                        name = ?, agent_code = ?, run_type = ?, subject_type = ?, subject_id = ?, question = ?,
                        expected = ?::jsonb, enabled = ?, updated_at = ?
                    where id = ?
                    """, r.name().trim(), r.agentCode().name(), r.runType().name(), subjectType, subjectId,
                    question, store.write(expected), enabled, Timestamp.from(now), id);
            if (updated == 0) {
                throw ApiException.notFound("Agent evaluation case", id);
            }
            savedId = id;
            audit.record("AGENT_EVAL_CASE_UPDATED", "AGENT_EVAL_CASE", savedId, Map.of("name", r.name().trim(), "agentCode", r.agentCode().name()));
        }
        return jdbc.queryForObject("select * from agent_eval_cases where id = ?", this::mapEvalCase, savedId);
    }

    @Transactional
    public void deleteEvalCase(long id, Actor actor) {
        int deleted = jdbc.update("delete from agent_eval_cases where id = ?", id);
        if (deleted == 0) {
            throw ApiException.notFound("Agent evaluation case", id);
        }
        audit.record("AGENT_EVAL_CASE_DELETED", "AGENT_EVAL_CASE", id, Map.of());
    }

    public List<EvalRunDto> listEvalRuns(AgentCode agentCode) {
        if (agentCode != null) {
            return jdbc.query("select * from agent_eval_runs where agent_code = ? order by created_at desc, id desc limit 50",
                    this::mapEvalRun, agentCode.name());
        }
        return jdbc.query("select * from agent_eval_runs order by created_at desc, id desc limit 50", this::mapEvalRun);
    }

    public EvalRunDto getEvalRun(long id) {
        List<EvalRunDto> list = jdbc.query("select * from agent_eval_runs where id = ?", this::mapEvalRun, id);
        if (list.isEmpty()) {
            throw ApiException.notFound("Agent evaluation run", id);
        }
        return list.get(0);
    }

    public EvalRunDto runEvaluations(EvalRunRequest r, Actor actor) {
        AgentCode filterCode = r == null ? null : r.agentCode();
        List<Long> caseIds = r == null || r.caseIds() == null ? List.of() : r.caseIds();
        List<EvalCaseDto> cases = listEvalCases(filterCode).stream()
                .filter(EvalCaseDto::enabled)
                .filter(c -> caseIds.isEmpty() || caseIds.contains(c.id()))
                .toList();
        if (cases.isEmpty()) {
            throw ApiException.badRequest("No enabled evaluation cases matched");
        }
        if (!runner.tryReserve()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Agent runner is at capacity; try again shortly");
        }
        Instant started = clock.instant();
        Map<String, String> versions = new LinkedHashMap<>();
        definitions.all().forEach(a -> versions.put(a.code().name(), a.version()));
        Long evalRunId = jdbc.queryForObject("""
                insert into agent_eval_runs (
                    agent_code, status, agent_versions, case_count, passed, failed,
                    started_by_id, started_by_name, started_at, created_at
                ) values (?, 'RUNNING', ?::jsonb, ?, 0, 0, ?, ?, ?, ?)
                returning id
                """, Long.class, filterCode == null ? null : filterCode.name(), store.write(versions), cases.size(),
                actor.id(), actor.name(), Timestamp.from(started), Timestamp.from(started));

        try {
            int passed = 0;
            int failed = 0;
            List<Map<String, Object>> caseResults = new ArrayList<>();
            for (EvalCaseDto c : cases) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("evalCaseId", c.id());
                input.put("evalCaseName", c.name());
                if ("INCIDENT".equals(c.subjectType()) && c.subjectId() != null) {
                    input.put("incidentId", c.subjectId());
                } else if ("CHANGE".equals(c.subjectType()) && c.subjectId() != null) {
                    input.put("changeId", c.subjectId());
                }
                if (c.question() != null) {
                    input.put("question", c.question());
                }
                Instant now = clock.instant();
                Long runId = jdbc.queryForObject("""
                        insert into agent_runs (
                            run_type, status, subject_type, subject_id, question, input,
                            agent_versions, timeout_seconds, requested_by_id, requested_by_name, requested_by_role,
                            eval_run_id, simulated, is_demo, queued_at, created_at, updated_at
                        ) values (?, 'QUEUED', ?, ?, ?, ?::jsonb, ?::jsonb, 30, ?, ?, ?, ?, false, ?, ?, ?, ?)
                        returning id
                        """, Long.class, c.runType().name(), c.subjectType(), c.subjectId(), c.question(),
                        store.write(input), store.write(versions), actor.id(), actor.name(), actor.role().name(),
                        evalRunId, c.demo(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));

                AgentRunner.Result result = runner.execute(runId, actor, false);
                Map<String, Object> evaluation = evaluateCase(c, runId, result);
                if (Boolean.TRUE.equals(evaluation.get("passed"))) {
                    passed++;
                } else {
                    failed++;
                }
                caseResults.add(evaluation);
            }
            double score = Math.round((100.0 * passed / cases.size()) * 100.0) / 100.0;
            Instant finished = clock.instant();
            jdbc.update("""
                    update agent_eval_runs set
                        status = 'COMPLETED', passed = ?, failed = ?, score = ?, results = ?::jsonb, finished_at = ?, updated_at = ?
                    where id = ?
                    """, passed, failed, score, store.write(caseResults), Timestamp.from(finished), Timestamp.from(finished), evalRunId);
            audit.record("AGENT_EVAL_RUN_COMPLETED", "AGENT_EVAL_RUN", evalRunId,
                    Map.of("cases", cases.size(), "passed", passed, "failed", failed, "score", score));
        } catch (RuntimeException e) {
            jdbc.update("update agent_eval_runs set status = 'FAILED', error = ?, finished_at = ? where id = ?",
                    e.getMessage(), Timestamp.from(clock.instant()), evalRunId);
            throw e;
        } finally {
            runner.release();
        }
        return getEvalRun(evalRunId);
    }

    private Map<String, Object> evaluateCase(EvalCaseDto c, long runId, AgentRunner.Result res) {
        List<Map<String, Object>> checks = new ArrayList<>();
        JsonNode agentNode = res.outputs().get(c.agentCode().name());
        String agentText = agentNode == null ? "" : agentNode.toString().toLowerCase(Locale.ROOT);
        String allText = store.write(res.outputs()).toLowerCase(Locale.ROOT);
        Map<String, Object> exp = c.expected() == null ? Map.of() : c.expected();

        if (exp.get("category") instanceof String expectedCat && !expectedCat.isBlank()) {
            String rec = agentNode != null && agentNode.hasNonNull("recommendedCategory")
                    ? agentNode.get("recommendedCategory").asText()
                    : agentNode != null && agentNode.hasNonNull("category") ? agentNode.get("category").asText() : "";
            boolean ok = expectedCat.equalsIgnoreCase(rec) || allText.contains(expectedCat.toLowerCase(Locale.ROOT));
            checks.add(Map.of("field", "category", "expected", expectedCat, "actual", rec, "passed", ok));
        }
        if (exp.get("priority") instanceof String expectedPri && !expectedPri.isBlank()) {
            String rec = agentNode != null && agentNode.hasNonNull("recommendedPriority")
                    ? agentNode.get("recommendedPriority").asText()
                    : agentNode != null && agentNode.hasNonNull("priority") ? agentNode.get("priority").asText() : "";
            boolean ok = expectedPri.equalsIgnoreCase(rec);
            checks.add(Map.of("field", "priority", "expected", expectedPri, "actual", rec, "passed", ok));
        }
        if (exp.get("rootCauseComponent") instanceof String comp && !comp.isBlank()) {
            String needle = comp.toLowerCase(Locale.ROOT);
            boolean ok = agentText.contains(needle) || allText.contains(needle);
            checks.add(Map.of("field", "rootCauseComponent", "expected", comp, "passed", ok));
        }
        if (exp.get("kbArticleIds") instanceof List<?> kbIds && !kbIds.isEmpty()) {
            List<Long> expectedIds = kbIds.stream().map(x -> ((Number) x).longValue()).toList();
            List<Long> found = new ArrayList<>();
            if (agentNode != null && agentNode.has("citations") && agentNode.get("citations").isArray()) {
                agentNode.get("citations").forEach(n -> {
                    if (n.hasNonNull("articleId")) {
                        found.add(n.get("articleId").asLong());
                    }
                });
            }
            if (agentNode != null && agentNode.has("evidence") && agentNode.get("evidence").isArray()) {
                agentNode.get("evidence").forEach(n -> {
                    if ("KB_ARTICLE".equals(n.path("type").asText()) && n.hasNonNull("id")) {
                        found.add(n.get("id").asLong());
                    }
                });
            }
            boolean ok = expectedIds.stream().anyMatch(found::contains);
            checks.add(Map.of("field", "kbArticleIds", "expected", expectedIds, "actual", found, "passed", ok));
        }
        if (exp.get("keywords") instanceof List<?> kwList && !kwList.isEmpty()) {
            List<String> matched = new ArrayList<>();
            List<String> missed = new ArrayList<>();
            for (Object raw : kwList) {
                String kw = String.valueOf(raw).trim();
                if (kw.isEmpty()) {
                    continue;
                }
                if (agentText.contains(kw.toLowerCase(Locale.ROOT)) || allText.contains(kw.toLowerCase(Locale.ROOT))) {
                    matched.add(kw);
                } else {
                    missed.add(kw);
                }
            }
            boolean ok = missed.isEmpty();
            checks.add(Map.of("field", "keywords", "matched", matched, "missed", missed, "passed", ok));
        }
        boolean allChecksPassed = "SUCCEEDED".equals(res.status()) && checks.stream().allMatch(ch -> Boolean.TRUE.equals(ch.get("passed")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseId", c.id());
        out.put("caseName", c.name());
        out.put("agentCode", c.agentCode().name());
        out.put("runType", c.runType().name());
        out.put("runId", runId);
        out.put("runStatus", res.status());
        out.put("passed", allChecksPassed);
        out.put("checks", checks);
        return out;
    }

    // ------------------------------------------------------------------ governance summary

    public Map<String, Object> governance(Integer days) {
        int window = days == null ? 30 : Math.max(1, Math.min(365, days));
        Instant to = clock.instant();
        Instant from = to.minus(Duration.ofDays(window));
        Timestamp fromTs = Timestamp.from(from);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String st : List.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED")) {
            byStatus.put(st, 0L);
        }
        jdbc.query("select status, count(*) as cnt from agent_runs where created_at >= ? group by status", rs -> {
            byStatus.put(rs.getString("status"), rs.getLong("cnt"));
        }, fromTs);

        Map<String, Long> bySource = new LinkedHashMap<>();
        for (String src : List.of("LLM", "FALLBACK", "MIXED")) {
            bySource.put(src, 0L);
        }
        jdbc.query("select source, count(*) as cnt from agent_runs where created_at >= ? and source is not null group by source", rs -> {
            bySource.put(rs.getString("source"), rs.getLong("cnt"));
        }, fromTs);

        Map<String, Long> byRunType = new LinkedHashMap<>();
        for (RunType rt : RunType.values()) {
            byRunType.put(rt.name(), 0L);
        }
        jdbc.query("select run_type, count(*) as cnt from agent_runs where created_at >= ? group by run_type", rs -> {
            byRunType.put(rs.getString("run_type"), rs.getLong("cnt"));
        }, fromTs);

        long totalRuns = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long succeededRuns = byStatus.getOrDefault("SUCCEEDED", 0L);
        long failedRuns = byStatus.getOrDefault("FAILED", 0L);
        long timedOutRuns = byStatus.getOrDefault("TIMED_OUT", 0L);
        long cancelledRuns = byStatus.getOrDefault("CANCELLED", 0L);
        long runningRuns = byStatus.getOrDefault("RUNNING", 0L) + byStatus.getOrDefault("QUEUED", 0L);

        long llmRuns = bySource.getOrDefault("LLM", 0L);
        long fallbackRuns = bySource.getOrDefault("FALLBACK", 0L);
        long mixedRuns = bySource.getOrDefault("MIXED", 0L);
        long completedWithSource = llmRuns + fallbackRuns + mixedRuns;

        double failureRate = totalRuns == 0 ? 0.0 : round2(100.0 * (failedRuns + timedOutRuns) / totalRuns);
        double fallbackRate = completedWithSource == 0 ? 0.0 : round2(100.0 * (fallbackRuns + mixedRuns) / completedWithSource);

        Map<String, Object> perfRow = jdbc.queryForMap("""
                select coalesce(avg(duration_ms), 0) as avg_ms,
                       coalesce(sum(tokens_in), 0) as tok_in,
                       coalesce(sum(tokens_out), 0) as tok_out
                from agent_runs
                where created_at >= ?
                """, fromTs);
        long avgDurationMs = Math.round(((Number) perfRow.get("avg_ms")).doubleValue());
        long totalTokensIn = ((Number) perfRow.get("tok_in")).longValue();
        long totalTokensOut = ((Number) perfRow.get("tok_out")).longValue();

        Map<String, Long> actionCounts = new LinkedHashMap<>();
        for (String st : List.of("PROPOSED", "PENDING_APPROVAL", "APPLIED", "REJECTED", "DISMISSED", "FAILED")) {
            actionCounts.put(st, 0L);
        }
        jdbc.query("select status, count(*) as cnt from agent_run_actions where created_at >= ? group by status", rs -> {
            actionCounts.put(rs.getString("status"), rs.getLong("cnt"));
        }, fromTs);

        long helpful = OptionalLong.of(jdbc.queryForObject(
                "select count(*) from agent_feedback where created_at >= ? and helpful = true", Long.class, fromTs));
        long notHelpful = OptionalLong.of(jdbc.queryForObject(
                "select count(*) from agent_feedback where created_at >= ? and helpful = false", Long.class, fromTs));
        double helpfulRate = (helpful + notHelpful) == 0 ? 0.0 : round2(100.0 * helpful / (helpful + notHelpful));

        Map<AgentCode, PolicyDto> policyMap = policies.all().stream()
                .map(Policy::dto)
                .collect(Collectors.toMap(PolicyDto::agentCode, Function.identity()));
        List<EvalRunDto> recentEvalRuns = listEvalRuns(null);
        Double overallEvalScore = recentEvalRuns.stream().filter(er -> er.score() != null).map(EvalRunDto::score).findFirst().orElse(null);

        List<Map<String, Object>> agentStats = new ArrayList<>();
        for (Agent a : definitions.all()) {
            Map<String, Object> stepAgg = jdbc.queryForMap("""
                    select count(*) as total,
                           count(*) filter (where s.status = 'SUCCEEDED') as succeeded,
                           count(*) filter (where s.status in ('FAILED', 'TIMED_OUT')) as failed,
                           count(*) filter (where s.source = 'LLM') as llm,
                           count(*) filter (where s.source = 'FALLBACK') as fallback,
                           coalesce(avg(s.duration_ms), 0) as avg_ms
                    from agent_run_steps s
                    join agent_runs r on r.id = s.run_id
                    where s.agent_code = ? and r.created_at >= ?
                    """, a.code().name(), fromTs);
            long stepTotal = ((Number) stepAgg.get("total")).longValue();
            long stepFall = ((Number) stepAgg.get("fallback")).longValue();
            long stepLlm = ((Number) stepAgg.get("llm")).longValue();
            EvalRunDto latestAgentEval = recentEvalRuns.stream()
                    .filter(er -> er.agentCode() == a.code() || er.agentCode() == null)
                    .findFirst().orElse(null);
            PolicyDto pol = policyMap.getOrDefault(a.code(), policies.get(a.code()).dto());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agentCode", a.code());
            item.put("name", a.name());
            item.put("version", a.version());
            item.put("enabled", pol.enabled());
            item.put("stepRuns", stepTotal);
            item.put("succeededSteps", ((Number) stepAgg.get("succeeded")).longValue());
            item.put("failedSteps", ((Number) stepAgg.get("failed")).longValue());
            item.put("llmSteps", stepLlm);
            item.put("fallbackSteps", stepFall);
            item.put("fallbackRate", (stepLlm + stepFall) == 0 ? 0.0 : round2(100.0 * stepFall / (stepLlm + stepFall)));
            item.put("avgLatencyMs", Math.round(((Number) stepAgg.get("avg_ms")).doubleValue()));
            item.put("latestEvalScore", latestAgentEval == null ? null : latestAgentEval.score());
            item.put("latestEvalAt", latestAgentEval == null ? null : latestAgentEval.finishedAt());
            agentStats.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", window);
        out.put("from", from);
        out.put("to", to);
        out.put("totalRuns", totalRuns);
        out.put("succeededRuns", succeededRuns);
        out.put("failedRuns", failedRuns);
        out.put("timedOutRuns", timedOutRuns);
        out.put("cancelledRuns", cancelledRuns);
        out.put("runningRuns", runningRuns);
        out.put("llmRuns", llmRuns);
        out.put("fallbackRuns", fallbackRuns);
        out.put("mixedRuns", mixedRuns);
        out.put("failureRate", failureRate);
        out.put("fallbackRate", fallbackRate);
        out.put("avgDurationMs", avgDurationMs);
        out.put("totalTokensIn", totalTokensIn);
        out.put("totalTokensOut", totalTokensOut);
        out.put("latestEvalScore", overallEvalScore);
        out.put("byStatus", byStatus);
        out.put("bySource", bySource);
        out.put("byRunType", byRunType);
        out.put("actionCounts", actionCounts);
        out.put("feedback", Map.of("helpful", helpful, "notHelpful", notHelpful, "helpfulRate", helpfulRate));
        out.put("agents", agentStats);
        out.put("versions", versions(null));
        out.put("recentEvalRuns", recentEvalRuns.size() > 10 ? recentEvalRuns.subList(0, 10) : recentEvalRuns);
        return out;
    }

    // ------------------------------------------------------------------ mappers & helpers

    private VersionDto mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new VersionDto(
                rs.getLong("id"),
                AgentCode.valueOf(rs.getString("agent_code")),
                rs.getString("version"),
                rs.getString("prompt_sha256"),
                rs.getString("prompt"),
                store.read(rs.getString("output_schema"), MAP_TYPE),
                store.read(rs.getString("tools"), LIST_STR_TYPE),
                AgentRunStore.inst(rs, "first_seen_at"));
    }

    private EvalCaseDto mapEvalCase(ResultSet rs, int rowNum) throws SQLException {
        return new EvalCaseDto(
                rs.getLong("id"),
                rs.getString("name"),
                AgentCode.valueOf(rs.getString("agent_code")),
                RunType.valueOf(rs.getString("run_type")),
                rs.getString("subject_type"),
                (Long) rs.getObject("subject_id"),
                rs.getString("question"),
                store.read(rs.getString("expected"), MAP_TYPE),
                rs.getBoolean("enabled"),
                rs.getBoolean("is_demo"),
                rs.getString("created_by_name"),
                AgentRunStore.inst(rs, "created_at"),
                AgentRunStore.inst(rs, "updated_at"));
    }

    private EvalRunDto mapEvalRun(ResultSet rs, int rowNum) throws SQLException {
        String code = rs.getString("agent_code");
        return new EvalRunDto(
                rs.getLong("id"),
                code == null ? null : AgentCode.valueOf(code),
                rs.getString("status"),
                store.read(rs.getString("agent_versions"), MAP_TYPE),
                rs.getInt("case_count"),
                rs.getInt("passed"),
                rs.getInt("failed"),
                rs.getObject("score") == null ? null : rs.getDouble("score"),
                store.tree(rs.getString("results")),
                rs.getString("error"),
                rs.getString("started_by_name"),
                AgentRunStore.inst(rs, "started_at"),
                AgentRunStore.inst(rs, "finished_at"),
                AgentRunStore.inst(rs, "created_at"));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static final class OptionalLong {
        static long of(Long v) {
            return v == null ? 0L : v;
        }
    }
}
