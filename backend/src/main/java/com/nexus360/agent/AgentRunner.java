package com.nexus360.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus360.agent.AgentDefinitions.Agent;
import com.nexus360.agent.AgentDefinitions.StepInput;
import com.nexus360.agent.AgentDtos.ActionKind;
import com.nexus360.agent.AgentDtos.AgentCode;
import com.nexus360.agent.AgentDtos.AgentTurn;
import com.nexus360.agent.AgentDtos.EvidenceRef;
import com.nexus360.agent.AgentDtos.ToolStatus;
import com.nexus360.agent.AgentPolicyService.Policy;
import com.nexus360.agent.AgentRunStore.RunRow;
import com.nexus360.ai.AiDtos.AiResult;
import com.nexus360.ai.AiService;
import com.nexus360.ai.GuardedLlm;
import com.nexus360.ai.LlmException;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.Actor;
import com.nexus360.user.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes agent runs. Runs are queued on a <b>bounded executor owned by this component</b> ({@code app.agents.max-concurrent-runs}
 * workers, default 2, plus {@code app.agents.queue-capacity} waiting runs, default 8; beyond that a start is refused with 429). It is
 * deliberately not a Spring {@code Executor} bean, which would replace Boot's bounded {@code applicationTaskExecutor}. Runs are submitted
 * after the run row committed (no transactional event listener, no REQUIRES_NEW on a request thread) and every write is a short
 * auto-committed statement, so a run never holds a connection while waiting.
 *
 * <p>A run executes the stages of its type ({@link AgentDefinitions#stages}); agents of one stage run in parallel on virtual threads, each
 * with the requester's security context (tools run as the requester). Each step: the deterministic tool plan, then — when an LLM is
 * configured and enabled — a bounded tool-use loop through {@link GuardedLlm} (at most the policy's {@code maxSteps} turns, structured
 * {@link AgentTurn} output, the agent's JSON schema and grounding checks; invalid output gets one retry, then the deterministic fallback),
 * labelled {@code LLM} or {@code FALLBACK} through {@link AiService#runGrounded}. Per-step timeouts (policy) and the run's total timeout
 * are enforced; requesters and AI governance can cancel.
 */
@Slf4j
@Component
public class AgentRunner {

    static final int MAX_PROMPT_DATA = 24_000;

    private final AgentRunStore store;
    private final AgentDefinitions definitions;
    private final AgentPolicyService policies;
    private final AgentTools tools;
    private final AiService ai;
    private final GuardedLlm llm;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final int maxConcurrent;
    private final int queueCapacity;
    private final Semaphore slots;
    private final ThreadPoolExecutor runExecutor;
    private final ExecutorService stepThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Long, Handle> handles = new ConcurrentHashMap<>();

    /** Cancellation state of a queued or running run. */
    static final class Handle {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Set<Future<?>> futures = ConcurrentHashMap.newKeySet();
        volatile Future<?> runFuture;
    }

    public AgentRunner(AgentRunStore store, AgentDefinitions definitions, AgentPolicyService policies, AgentTools tools, AiService ai, GuardedLlm llm,
                       NotificationService notifications, JdbcTemplate jdbc, ObjectMapper json, Clock clock, PlatformTransactionManager transactions,
                       @Value("${app.agents.max-concurrent-runs:2}") int maxConcurrent, @Value("${app.agents.queue-capacity:8}") int queueCapacity) {
        this.store = store;
        this.definitions = definitions;
        this.policies = policies;
        this.tools = tools;
        this.ai = ai;
        this.llm = llm;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.queueCapacity = Math.max(0, queueCapacity);
        this.slots = new Semaphore(this.maxConcurrent + this.queueCapacity);
        AtomicInteger n = new AtomicInteger();
        this.runExecutor = new ThreadPoolExecutor(this.maxConcurrent, this.maxConcurrent, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "agent-run-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @jakarta.annotation.PreDestroy
    void shutdown() throws InterruptedException {
        handles.values().forEach(h -> h.cancelled.set(true));
        runExecutor.shutdownNow();
        stepThreads.shutdownNow();
        runExecutor.awaitTermination(10, TimeUnit.SECONDS);
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    /** Reserves capacity for one run or evaluation (false when {@code maxConcurrent + queueCapacity} are in use). */
    public boolean tryReserve() {
        return slots.tryAcquire();
    }

    public void release() {
        slots.release();
    }

    /** Queues a committed run on the bounded executor (capacity already reserved). */
    public Future<?> submit(long runId, Actor actor) {
        Handle h = handles.computeIfAbsent(runId, k -> new Handle());
        Future<?> f = runExecutor.submit(() -> {
            try {
                execute(runId, actor, true);
            } catch (RuntimeException e) {
                log.error("Agent run {} failed", runId, e);
            } finally {
                handles.remove(runId);
                release();
            }
        });
        h.runFuture = f;
        return f;
    }

    /** Submits a task (an evaluation run) that holds one reserved slot. */
    public Future<?> submitTask(Runnable task) {
        return runExecutor.submit(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Agent task failed", e);
            } finally {
                release();
            }
        });
    }

    /** Signals cancellation; running steps are interrupted. */
    public void cancel(long runId) {
        Handle h = handles.get(runId);
        if (h != null) {
            h.cancelled.set(true);
            h.futures.forEach(f -> f.cancel(true));
        }
    }

    // ------------------------------------------------------------------ run

    /** Outcome of one run: the final status and the outputs per agent. */
    public record Result(String status, Map<String, JsonNode> outputs) {}

    public Result execute(long runId, Actor actor, boolean notify) {
        Handle handle = handles.computeIfAbsent(runId, k -> new Handle());
        RunRow run = store.run(runId);
        Instant started = clock.instant();
        if (run == null) {
            return new Result("FAILED", Map.of());
        }
        if ("CANCELLED".equals(run.status())) {
            return new Result("CANCELLED", Map.of());
        }
        if (run.cancelRequested() || handle.cancelled.get()) {
            store.finish(runId, "CANCELLED", null, "Cancelled before it started", null, null, null, null, started);
            return new Result("CANCELLED", Map.of());
        }
        store.markRunning(runId, started);
        Instant deadline = started.plus(Duration.ofSeconds(run.timeoutSeconds()));
        Map<String, JsonNode> outputs = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, Double> confidence = new LinkedHashMap<>();
        Set<String> evidence = Collections.synchronizedSet(new LinkedHashSet<>());
        List<String> skipped = new ArrayList<>();
        Map<String, Object> versions = new LinkedHashMap<>();
        String status = null;
        String error = null;
        int seq = 0;
        AgentCode primary = AgentDefinitions.primary(run.runType());
        List<List<AgentCode>> stages = AgentDefinitions.stages(run.runType());
        stages.forEach(s -> s.forEach(c -> versions.put(c.name(), definitions.get(c).version())));
        jdbc.update("update agent_runs set agent_versions = ?::jsonb where id = ?", store.write(versions), runId);
        try {
            for (int stage = 0; stage < stages.size() && status == null; stage++) {
                List<Future<StepOutcome>> futures = new ArrayList<>();
                List<long[]> stepIds = new ArrayList<>();
                List<Policy> stagePolicies = new ArrayList<>();
                Map<String, JsonNode> upstream = Map.copyOf(outputs);
                for (AgentCode code : stages.get(stage)) {
                    Agent agent = definitions.get(code);
                    Policy policy = policies.get(code);
                    StepInput in = new StepInput(run.runType(), "INCIDENT".equals(run.subjectType()) ? run.subjectId() : null,
                            "CHANGE".equals(run.subjectType()) ? run.subjectId() : null, run.question(), upstream, clock.instant());
                    Map<String, Object> inputJson = new LinkedHashMap<>();
                    inputJson.put("runType", run.runType());
                    inputJson.put("incidentId", in.incidentId());
                    inputJson.put("changeId", in.changeId());
                    inputJson.put("question", in.question());
                    inputJson.put("upstream", upstream.keySet());
                    long stepId = store.insertStep(runId, ++seq, stage + 1, code, agent.version(), inputJson);
                    String refusal = !policy.enabled() ? "Agent " + code + " is disabled by policy"
                            : !policy.roleAllowed(run.requestedByRole()) ? "Role " + run.requestedByRole() + " may not run agent " + code + " (policy)" : null;
                    if (refusal != null) {
                        store.stepDone(stepId, "SKIPPED", null, null, null, null, List.of(), 0, 0, 0, refusal, null, clock.instant());
                        skipped.add(refusal);
                        continue;
                    }
                    Set<String> allowedEvidence = new LinkedHashSet<>(evidence);
                    futures.add(stepThreads.submit(() -> asUser(actor, runId, () -> step(run, stepId, agent, policy, in, actor, allowedEvidence, handle))));
                    stepIds.add(new long[]{stepId});
                    stagePolicies.add(policy);
                    handle.futures.add(futures.get(futures.size() - 1));
                }
                for (int i = 0; i < futures.size(); i++) {
                    Future<StepOutcome> f = futures.get(i);
                    long stepId = stepIds.get(i)[0];
                    Policy policy = stagePolicies.get(i);
                    long remaining = Duration.between(clock.instant(), deadline).toMillis();
                    long wait = Math.max(0, Math.min(policy.timeoutSeconds() * 1000L + 1500, remaining));
                    try {
                        StepOutcome o = f.get(wait, TimeUnit.MILLISECONDS);
                        if (o.output() != null) {
                            outputs.put(o.code().name(), o.output());
                            sources.put(o.code().name(), o.source());
                            confidence.put(o.code().name(), o.confidence());
                            evidence.addAll(o.evidence());
                        }
                    } catch (TimeoutException e) {
                        f.cancel(true);
                        boolean total = remaining <= policy.timeoutSeconds() * 1000L + 1500;
                        stepFinal(stepId, "TIMED_OUT", total ? "The run's total timeout (" + run.timeoutSeconds() + " s) was reached"
                                : "The step exceeded its timeout of " + policy.timeoutSeconds() + " s");
                        if (total) {
                            status = "TIMED_OUT";
                            error = "Total timeout of " + run.timeoutSeconds() + " s reached";
                        }
                    } catch (CancellationException e) {
                        stepFinal(stepId, "CANCELLED", "Cancelled");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        f.cancel(true);
                        stepFinal(stepId, "CANCELLED", "Cancelled");
                    } catch (ExecutionException e) {
                        stepFinal(stepId, "FAILED", "Step failed: " + e.getCause().getClass().getSimpleName());
                    } finally {
                        handle.futures.remove(f);
                    }
                }
                if (handle.cancelled.get() || store.cancelRequested(runId)) {
                    status = "CANCELLED";
                } else if (status == null && !clock.instant().isBefore(deadline)) {
                    status = "TIMED_OUT";
                    error = "Total timeout of " + run.timeoutSeconds() + " s reached";
                }
                if (stage == 0 && status == null && !outputs.containsKey(primary.name())) {
                    status = "FAILED";
                    error = skipped.isEmpty() ? "The " + primary + " agent did not produce an output" : skipped.get(0);
                }
            }
        } catch (RuntimeException e) {
            log.error("Agent run {} failed", runId, e);
            status = "FAILED";
            error = "Run failed: " + e.getClass().getSimpleName();
        }
        if (status != null && !"SUCCEEDED".equals(status)) {
            jdbc.update("update agent_run_steps set status = case when status = 'RUNNING' then ? else 'SKIPPED' end, error = coalesce(error, ?), "
                    + "finished_at = coalesce(finished_at, ?) where run_id = ? and status in ('PENDING', 'RUNNING')", "CANCELLED".equals(status) ? "CANCELLED"
                    : "TIMED_OUT", "CANCELLED".equals(status) ? "Cancelled" : error, AgentRunStore.ts(clock.instant()), runId);
        }
        String finalStatus = status == null ? "SUCCEEDED" : status;
        String finalAgent = finalAgent(run.runType(), outputs);
        JsonNode last = finalAgent == null ? null : outputs.get(finalAgent);
        String summary = last == null ? ("CANCELLED".equals(finalStatus) ? "Cancelled" : error) : text(last);
        Set<String> distinct = new LinkedHashSet<>(sources.values());
        String source = distinct.isEmpty() ? null : distinct.size() == 1 ? distinct.iterator().next() : "MIXED";
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("runType", run.runType());
        output.put("summary", summary);
        output.put("finalAgent", finalAgent);
        output.put("outputs", outputs);
        output.put("sources", sources);
        output.put("skipped", skipped);
        store.finish(runId, finalStatus, output, AgentRunStore.trunc(summary, 4000), source, finalAgent == null ? null : confidence.get(finalAgent),
                "SUCCEEDED".equals(finalStatus) ? null : error, started, clock.instant());
        if (notify && run.requestedById() != null) {
            try {
                tx.executeWithoutResult(s -> notifications.notifyUser(run.requestedById(), "AGENT_RUN", "SA agent run " + finalStatus.toLowerCase()
                        .replace('_', ' ') + ": " + run.runType().name().replace('_', ' ').toLowerCase() + (run.subjectNumber() == null ? ""
                        : " " + run.subjectNumber()), AgentRunStore.trunc(summary == null ? finalStatus : summary, 500), "/agents/runs/" + runId,
                        "SUCCEEDED".equals(finalStatus) ? Severity.INFO : Severity.WARNING));
            } catch (RuntimeException e) {
                log.debug("Could not notify agent run {}: {}", runId, e.getMessage());
            }
        }
        handles.remove(runId);
        return new Result(finalStatus, outputs);
    }

    private static String finalAgent(AgentDtos.RunType type, Map<String, JsonNode> outputs) {
        List<AgentCode> order = switch (type) {
            case INVESTIGATE_INCIDENT -> List.of(AgentCode.RESOLUTION, AgentCode.ROOT_CAUSE, AgentCode.INCIDENT);
            case ASSESS_CHANGE -> List.of(AgentCode.CHANGE_RISK, AgentCode.INFRA_INVESTIGATION);
            case ANSWER_QUESTION -> List.of(AgentCode.KNOWLEDGE);
        };
        return order.stream().map(Enum::name).filter(outputs::containsKey).findFirst().orElse(null);
    }

    private static String text(JsonNode out) {
        for (String f : List.of("summary", "answer")) {
            if (out.hasNonNull(f)) {
                return out.get(f).asText();
            }
        }
        return null;
    }

    private void stepFinal(long stepId, String status, String error) {
        jdbc.update("update agent_run_steps set status = ?, error = coalesce(error, ?), finished_at = coalesce(finished_at, ?) where id = ? "
                + "and status in ('PENDING', 'RUNNING')", status, error, AgentRunStore.ts(clock.instant()), stepId);
    }

    // ------------------------------------------------------------------ step

    record StepOutcome(AgentCode code, JsonNode output, String source, Double confidence, Set<String> evidence) {}

    /** Mutable counters of one step. */
    static final class Counters {
        int turns;
        int tokensIn;
        int tokensOut;
        int toolSeq;
        String detail;
    }

    private StepOutcome step(RunRow run, long stepId, Agent agent, Policy policy, StepInput in, Actor actor, Set<String> upstreamEvidence, Handle handle) {
        Instant started = clock.instant();
        Instant stepDeadline = started.plus(Duration.ofSeconds(policy.timeoutSeconds()));
        store.stepRunning(stepId, started);
        Counters c = new Counters();
        Set<String> seen = Collections.synchronizedSet(new LinkedHashSet<>(upstreamEvidence));
        AgentTools.ToolContext ctx = new AgentTools.ToolContext(actor, run.simulated(), policy.telemetryMinutes(), policy.historyDays(), clock.instant());
        try {
            AgentDefinitions.Toolbox planBox = (tool, args) -> callTool(run.id(), stepId, policy, agent, tool, args, ctx, c, seen, handle, "PLAN").data();
            Map<String, JsonNode> facts = agent.gather(in, planBox);
            checkCancelled(handle);
            Object fallbackOut = agent.fallback(in, facts);
            JsonNode fallbackNode = json.valueToTree(fallbackOut);
            String subject = run.runType() + (run.subjectNumber() == null ? "" : " " + run.subjectNumber()) + " · " + agent.code();
            AiResult<JsonNode> result = ai.runGrounded("agent_" + agent.code().name().toLowerCase(), subject, actor,
                    () -> llmLoop(run, stepId, agent, policy, in, facts, ctx, c, seen, handle, stepDeadline),
                    () -> fallbackNode);
            checkCancelled(handle);
            JsonNode output = result.data();
            String source = result.source().name();
            String reason = result.source() == com.nexus360.ai.AiDtos.Source.FALLBACK ? (c.detail != null ? c.detail : result.fallbackReason()) : null;
            List<String> errors = JsonSchemaLite.validate(agent.schema(), output);
            if (!errors.isEmpty()) {
                log.warn("Deterministic output of agent {} failed its schema: {}", agent.code(), errors);
            }
            List<EvidenceRef> evidence = evidence(output);
            Double confidence = output.hasNonNull("confidence") ? output.get("confidence").asDouble() : null;
            recordActions(run, stepId, agent, policy, output, seen);
            store.stepDone(stepId, "SUCCEEDED", output, source, reason, confidence, evidence, c.turns, c.tokensIn, c.tokensOut, null, started, clock.instant());
            Set<String> keys = new LinkedHashSet<>(seen);
            evidence.forEach(e -> keys.add(e.type() + ":" + e.id()));
            return new StepOutcome(agent.code(), output, source, confidence, keys);
        } catch (CancellationException e) {
            store.stepDone(stepId, "CANCELLED", null, null, null, null, List.of(), c.turns, c.tokensIn, c.tokensOut, "Cancelled", started, clock.instant());
            return new StepOutcome(agent.code(), null, null, null, Set.of());
        } catch (RuntimeException e) {
            log.warn("Agent step {} ({}) failed: {}", stepId, agent.code(), e.toString());
            store.stepDone(stepId, "FAILED", null, null, null, null, List.of(), c.turns, c.tokensIn, c.tokensOut, "Step failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " — " + e.getMessage()), started, clock.instant());
            return new StepOutcome(agent.code(), null, null, null, Set.of());
        }
    }

    private static void checkCancelled(Handle h) {
        if (h.cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Run cancelled");
        }
    }

    /** One tool call (plan or LLM-requested), checked against the policy's allowed tools, recorded with its outcome. */
    private AgentTools.ToolResult callTool(long runId, long stepId, Policy policy, Agent agent, String tool, Map<String, String> args,
                                           AgentTools.ToolContext ctx, Counters c, Set<String> seen, Handle handle, String requestedBy) {
        checkCancelled(handle);
        int seq;
        synchronized (c) {
            seq = ++c.toolSeq;
        }
        AgentTools.ToolResult r;
        if (!agent.tools().contains(tool) || !tools.exists(tool)) {
            r = new AgentTools.ToolResult(ToolStatus.DISALLOWED, null, "Agent " + agent.code() + " does not declare tool " + tool, 0, false, List.of(), 0);
        } else if (!policy.toolAllowed(tool)) {
            r = new AgentTools.ToolResult(ToolStatus.DISALLOWED, null, "Tool " + tool + " is not allowed for agent " + agent.code() + " by policy", 0, false,
                    List.of(), 0);
        } else {
            r = tools.invoke(tool, args, ctx);
        }
        store.toolCall(runId, stepId, seq, tool, args, r.status().name(), r.data(), r.size(), r.truncated(), r.message(), requestedBy, r.durationMs());
        if (r.status() == ToolStatus.OK) {
            r.evidence().forEach(e -> seen.add(e.type() + ":" + e.id()));
        }
        return r.status() == ToolStatus.OK ? r : new AgentTools.ToolResult(r.status(), null, r.message(), 0, false, List.of(), r.durationMs());
    }

    // ------------------------------------------------------------------ LLM tool-use loop

    private JsonNode llmLoop(RunRow run, long stepId, Agent agent, Policy policy, StepInput in, Map<String, JsonNode> facts, AgentTools.ToolContext ctx,
                             Counters c, Set<String> seen, Handle handle, Instant deadline) {
        long remaining = Duration.between(clock.instant(), deadline).toMillis();
        if (remaining <= 0) {
            c.detail = "No time left for the LLM within the step timeout of " + policy.timeoutSeconds() + " s";
            throw new LlmException(LlmException.Reason.UPSTREAM_ERROR, c.detail);
        }
        Future<JsonNode> f = stepThreads.submit(() -> asUser(ctx.actor(), run.id(), () -> loop(run, stepId, agent, policy, in, facts, ctx, c, seen, handle)));
        handle.futures.add(f);
        try {
            return f.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            c.detail = "The LLM did not finish within the step timeout of " + policy.timeoutSeconds() + " s";
            throw new LlmException(LlmException.Reason.UPSTREAM_ERROR, c.detail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            throw new CancellationException("Run cancelled");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof LlmException le) {
                if (c.detail == null) {
                    c.detail = le.getReason() == LlmException.Reason.INVALID_OUTPUT ? "The LLM output failed validation after one retry: " + le.getMessage()
                            : le.getMessage();
                }
                throw le;
            }
            if (e.getCause() instanceof CancellationException ce) {
                throw ce;
            }
            c.detail = "LLM loop failed: " + e.getCause().getClass().getSimpleName();
            throw new LlmException(LlmException.Reason.UPSTREAM_ERROR, c.detail);
        } catch (CancellationException e) {
            throw e;
        } finally {
            handle.futures.remove(f);
        }
    }

    private JsonNode loop(RunRow run, long stepId, Agent agent, Policy policy, StepInput in, Map<String, JsonNode> facts, AgentTools.ToolContext ctx,
                          Counters c, Set<String> seen, Handle handle) {
        List<String> allowed = agent.tools().stream().filter(policy::toolAllowed).toList();
        String task = agent.prompt() + " Output schema (JSON Schema): " + store.write(agent.schema()) + " Tools you may call (read-only, run with the "
                + "requesting user's permissions): " + store.write(tools.catalogue().stream().filter(t -> allowed.contains(t.name())).toList())
                + ". Each turn either calls one tool (action TOOL) or answers (action FINAL with outputJson). At most " + policy.maxSteps() + " turns.";
        StringBuilder transcript = new StringBuilder("<data>\nRun: ").append(run.runType()).append(" ").append(run.subjectType() == null ? ""
                : run.subjectType() + " " + run.subjectNumber()).append(in.question() == null ? "" : "\nQuestion: " + in.question())
                .append("\nTool results of the plan:\n").append(clip(store.write(facts), MAX_PROMPT_DATA)).append("\n</data>");
        ObjectNode[] parsed = new ObjectNode[1];
        for (int turn = 1; turn <= policy.maxSteps(); turn++) {
            checkCancelled(handle);
            String user = transcript + "\nTurn " + turn + " of " + policy.maxSteps() + (turn == policy.maxSteps() ? ": answer now with action FINAL." : ".");
            AgentTurn t = llm.generate(task, user, AgentTurn.class, v -> parsed[0] = validateTurn(v, agent, in, seen));
            c.turns++;
            c.tokensIn += (task.length() + user.length()) / 4;
            c.tokensOut += store.write(t).length() / 4;
            if ("FINAL".equals(t.action)) {
                return parsed[0];
            }
            Map<String, String> args = new LinkedHashMap<>();
            if (t.args != null) {
                t.args.forEach(a -> args.put(a.name, a.value));
            }
            AgentTools.ToolResult r = callTool(run.id(), stepId, policy, agent, t.tool, args, ctx, c, seen, handle, "LLM");
            transcript.append("\n<tool name=\"").append(t.tool).append("\" status=\"").append(r.status()).append("\">\n")
                    .append(r.data() == null ? (r.message() == null ? "" : r.message()) : clip(r.data().toString(), 8000)).append("\n</tool>");
        }
        c.detail = "The LLM gave no FINAL answer within " + policy.maxSteps() + " steps";
        throw new LlmException(LlmException.Reason.INVALID_OUTPUT, c.detail);
    }

    /** Schema and grounding validation of one turn; throws INVALID_OUTPUT (GuardedLlm then retries once, then the step falls back). */
    ObjectNode validateTurn(AgentTurn t, Agent agent, StepInput in, Set<String> seen) {
        if (t == null || t.action == null || !List.of("TOOL", "FINAL").contains(t.action)) {
            throw invalid("action must be TOOL or FINAL");
        }
        if ("TOOL".equals(t.action)) {
            if (t.tool == null || t.tool.isBlank() || t.tool.length() > 40) {
                throw invalid("TOOL turns need a tool name");
            }
            if (t.args != null && (t.args.size() > 10 || t.args.stream().anyMatch(a -> a == null || a.name == null || a.value == null || a.value.length() > 2000))) {
                throw invalid("Invalid tool arguments");
            }
            return null;
        }
        JsonNode node;
        try {
            node = json.readTree(t.outputJson == null ? "" : t.outputJson);
        } catch (Exception e) {
            throw invalid("outputJson is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw invalid("outputJson must be a JSON object");
        }
        List<String> errors = new ArrayList<>(JsonSchemaLite.validate(agent.schema(), node));
        for (EvidenceRef e : evidence(node)) {
            if (!seen.contains(e.type() + ":" + e.id())) {
                errors.add("evidence " + e.type() + " " + e.id() + " did not appear in any tool result");
            }
        }
        for (JsonNode a : node.path("proposedActions")) {
            String problem = actionProblem(a, in, seen);
            if (problem != null) {
                errors.add(problem);
            }
        }
        if (!errors.isEmpty()) {
            throw invalid("Output failed the " + agent.code() + " schema: " + String.join("; ", errors.subList(0, Math.min(5, errors.size()))));
        }
        try {
            json.treeToValue(node, agent.outputType());
        } catch (Exception e) {
            throw invalid("Output does not map to " + agent.outputType().getSimpleName());
        }
        return (ObjectNode) node;
    }

    private static LlmException invalid(String message) {
        return new LlmException(LlmException.Reason.INVALID_OUTPUT, message);
    }

    /** Null when a proposed action is acceptable (valid kind and parameters, grounded references, an incident subject). */
    String actionProblem(JsonNode a, StepInput in, Set<String> seen) {
        ActionKind kind;
        try {
            kind = ActionKind.valueOf(a.path("kind").asText());
        } catch (RuntimeException e) {
            return "unknown action kind " + a.path("kind").asText();
        }
        if (in.incidentId() == null) {
            return "actions need an incident subject";
        }
        JsonNode p = a.path("params");
        return switch (kind) {
            case ASSIGN_TEAM -> {
                Long team = longParam(p, "teamId");
                yield team == null ? "ASSIGN_TEAM needs teamId" : jdbc.queryForObject("select count(*) from teams where id = ? and active", Long.class, team) == 0
                        ? "team " + team + " does not exist or is inactive" : null;
            }
            case CHANGE_PRIORITY -> AgentDefinitions.PRIORITIES.contains(p.path("priority").asText()) ? null : "CHANGE_PRIORITY needs a valid priority";
            case LINK_PROBLEM -> {
                Long problem = longParam(p, "problemId");
                yield problem == null || !seen.contains("PROBLEM:" + problem) ? "LINK_PROBLEM needs a problemId from the tool results" : null;
            }
            case RUN_PLAYBOOK -> {
                Long runbook = longParam(p, "runbookId");
                yield runbook == null || !seen.contains("RUNBOOK:" + runbook) ? "RUN_PLAYBOOK needs a runbookId from the tool results" : null;
            }
            case PUBLISH_KB_DRAFT -> {
                int title = p.path("title").asText("").strip().length();
                int summary = p.path("summary").asText("").strip().length();
                int content = p.path("content").asText("").strip().length();
                yield title < 5 || title > 200 || summary < 10 || summary > 500 || content < 20 || content > 50_000
                        || !AgentDefinitions.CATEGORIES.contains(p.path("category").asText()) ? "PUBLISH_KB_DRAFT needs title (5-200), summary (10-500), "
                        + "content (20-50000) and a category" : null;
            }
            case DECLARE_MAJOR -> null;
        };
    }

    private static Long longParam(JsonNode p, String name) {
        try {
            return p.hasNonNull(name) ? Long.parseLong(p.get(name).asText().trim()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Every evidence reference of an output (top level and nested in hypotheses or findings). */
    static List<EvidenceRef> evidence(JsonNode output) {
        List<EvidenceRef> out = new ArrayList<>();
        collect(output, out, 0);
        Set<String> seen = new LinkedHashSet<>();
        return out.stream().filter(e -> seen.add(e.type() + ":" + e.id())).toList();
    }

    private static void collect(JsonNode node, List<EvidenceRef> out, int depth) {
        if (node == null || depth > 4) {
            return;
        }
        if (node.isObject()) {
            JsonNode ev = node.get("evidence");
            if (ev != null && ev.isArray()) {
                for (JsonNode e : ev) {
                    if (e.hasNonNull("type") && e.hasNonNull("id") && e.get("id").canConvertToLong()) {
                        out.add(new EvidenceRef(e.get("type").asText(), e.get("id").asLong(), e.hasNonNull("label") ? e.get("label").asText() : null));
                    }
                }
            }
            node.fields().forEachRemaining(f -> {
                if (!"evidence".equals(f.getKey())) {
                    collect(f.getValue(), out, depth + 1);
                }
            });
        } else if (node.isArray()) {
            node.forEach(n -> collect(n, out, depth + 1));
        }
    }

    // ------------------------------------------------------------------ proposed actions

    private void recordActions(RunRow run, long stepId, Agent agent, Policy policy, JsonNode output, Set<String> seen) {
        if (!"INCIDENT".equals(run.subjectType()) || run.subjectId() == null) {
            return;
        }
        StepInput in = new StepInput(run.runType(), run.subjectId(), null, null, Map.of(), clock.instant());
        for (JsonNode a : output.path("proposedActions")) {
            String problem = actionProblem(a, in, seen);
            if (problem != null) {
                log.debug("Dropped proposed action of {}: {}", agent.code(), problem);
                continue;
            }
            ActionKind kind = ActionKind.valueOf(a.get("kind").asText());
            Map<String, String> params = new LinkedHashMap<>();
            a.path("params").fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
            boolean exists = jdbc.queryForObject("select count(*) from agent_run_actions where run_id = ? and action_kind = ? and params = ?::jsonb", Long.class,
                    run.id(), kind.name(), store.write(params)) > 0;
            if (exists) {
                continue;
            }
            store.insertAction(run.id(), stepId, agent.code(), kind, "INCIDENT", run.subjectId(), params, title(kind, params, run.subjectNumber()),
                    a.path("rationale").asText("Proposed by " + agent.code()), policy.approvalActionKinds().contains(kind), link(kind, params, run.subjectId()));
        }
    }

    static String title(ActionKind kind, Map<String, String> p, String number) {
        return switch (kind) {
            case ASSIGN_TEAM -> "Assign " + number + " to team " + p.get("teamId");
            case CHANGE_PRIORITY -> "Set " + number + " priority to " + p.get("priority");
            case LINK_PROBLEM -> "Link " + number + " to problem " + p.get("problemId");
            case PUBLISH_KB_DRAFT -> "Create knowledge draft '" + p.get("title") + "' from " + number;
            case RUN_PLAYBOOK -> "Run runbook " + p.get("runbookId") + " for " + number;
            case DECLARE_MAJOR -> "Declare " + number + " a major incident";
        };
    }

    /** The target UI, prefilled, for actions a human applies directly (not routed through an approval). */
    static String link(ActionKind kind, Map<String, String> p, long incidentId) {
        return switch (kind) {
            case PUBLISH_KB_DRAFT -> "/knowledge/new?fromIncident=" + incidentId;
            case LINK_PROBLEM -> "/problems/" + p.get("problemId") + "?linkIncident=" + incidentId;
            default -> "/incidents/" + incidentId + "?agentAction=" + kind.name();
        };
    }

    // ------------------------------------------------------------------ security context

    /** Runs {@code work} with the requester's security context, so tools and services see the requester (CurrentActor, method security). */
    <T> T asUser(Actor actor, long runId, java.util.concurrent.Callable<T> work) throws Exception {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        if (actor.id() != null) {
            Instant now = Instant.now();
            Jwt jwt = Jwt.withTokenValue("agent-run-" + runId).header("alg", "none").subject(String.valueOf(actor.id())).claim("email", actor.email())
                    .claim("name", actor.name()).claim("role", actor.role().name()).issuedAt(now).expiresAt(now.plus(Duration.ofHours(1))).build();
            ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + actor.role().name()))));
        }
        SecurityContextHolder.setContext(ctx);
        try {
            return work.call();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…(truncated)";
    }

    static boolean isAgentRole(Role role) {
        return AgentPolicyService.AGENT_ROLES.contains(role.name());
    }
}
