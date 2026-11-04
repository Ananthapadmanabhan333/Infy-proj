package com.nexus360.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.agent.AgentDtos.ActionDto;
import com.nexus360.agent.AgentDtos.ActionKind;
import com.nexus360.agent.AgentDtos.AgentCode;
import com.nexus360.agent.AgentDtos.FeedbackDto;
import com.nexus360.agent.AgentDtos.FeedbackSummary;
import com.nexus360.agent.AgentDtos.RunDto;
import com.nexus360.agent.AgentDtos.RunType;
import com.nexus360.agent.AgentDtos.StepDto;
import com.nexus360.agent.AgentDtos.ToolCallDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * JDBC persistence of agent runs, steps, tool calls, proposed actions and feedback. Each write is a short auto-committed statement so a
 * running agent never holds a database connection while it waits for the LLM or a parallel step.
 */
@Component
@RequiredArgsConstructor
public class AgentRunStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    // ------------------------------------------------------------------ runs

    public record RunRow(long id, RunType runType, String status, String subjectType, Long subjectId, String subjectNumber, String question,
                         Map<String, Object> input, int timeoutSeconds, boolean cancelRequested, Long requestedById, String requestedByName,
                         String requestedByRole, Long evalRunId, boolean simulated, boolean demo) {}

    public RunRow run(long id) {
        return jdbc.query("select * from agent_runs where id = ?", (rs, n) -> new RunRow(rs.getLong("id"), RunType.valueOf(rs.getString("run_type")),
                rs.getString("status"), rs.getString("subject_type"), (Long) rs.getObject("subject_id"), rs.getString("subject_number"),
                rs.getString("question"), read(rs.getString("input"), new TypeReference<Map<String, Object>>() { }), rs.getInt("timeout_seconds"),
                rs.getBoolean("cancel_requested"), (Long) rs.getObject("requested_by_id"), rs.getString("requested_by_name"),
                rs.getString("requested_by_role"), (Long) rs.getObject("eval_run_id"), rs.getBoolean("simulated"), rs.getBoolean("is_demo")), id)
                .stream().findFirst().orElse(null);
    }

    public RunDto runDto(long id, Long viewerId) {
        return jdbc.query("select * from agent_runs where id = ?", (rs, n) -> runDto(rs, viewerId), id).stream().findFirst().orElse(null);
    }

    RunDto runDto(ResultSet rs, Long viewerId) throws SQLException {
        long id = rs.getLong("id");
        Map<String, Object> fb = jdbc.queryForMap("select count(*) filter (where helpful) as h, count(*) filter (where not helpful) as n, "
                + "bool_or(helpful) filter (where user_id = ?) as mine from agent_feedback where run_id = ?", viewerId == null ? -1 : viewerId, id);
        return new RunDto(id, RunType.valueOf(rs.getString("run_type")), rs.getString("status"), rs.getString("subject_type"), (Long) rs.getObject("subject_id"),
                rs.getString("subject_number"), rs.getString("question"), rs.getString("source"), (Double) rs.getObject("confidence"), rs.getString("summary"),
                read(rs.getString("agent_versions"), new TypeReference<Map<String, Object>>() { }), rs.getInt("timeout_seconds"), rs.getInt("step_count"),
                rs.getInt("tool_call_count"), rs.getInt("tokens_in"), rs.getInt("tokens_out"), rs.getString("error"), rs.getBoolean("cancel_requested"),
                rs.getString("cancelled_by_name"), (Long) rs.getObject("requested_by_id"), rs.getString("requested_by_name"), (Long) rs.getObject("eval_run_id"),
                rs.getBoolean("simulated"), rs.getBoolean("is_demo"), inst(rs, "queued_at"), inst(rs, "started_at"), inst(rs, "finished_at"),
                (Long) rs.getObject("duration_ms"), inst(rs, "created_at"), new FeedbackSummary(((Number) fb.get("h")).longValue(),
                ((Number) fb.get("n")).longValue(), (Boolean) fb.get("mine")));
    }

    public JsonNode runJson(long id, String column) {
        String text = jdbc.queryForObject("select " + column + "::text from agent_runs where id = ?", String.class, id);
        return tree(text);
    }

    public void markRunning(long id, Instant at) {
        jdbc.update("update agent_runs set status = 'RUNNING', started_at = ?, updated_at = ? where id = ? and status = 'QUEUED'", ts(at), ts(at), id);
    }

    public void finish(long id, String status, Object output, String summary, String source, Double confidence, String error, Instant started, Instant at) {
        Map<String, Object> totals = jdbc.queryForMap("select count(*) as steps, coalesce(sum(tokens_in), 0) as tin, coalesce(sum(tokens_out), 0) as tout, "
                + "(select count(*) from agent_tool_calls where run_id = ?) as calls from agent_run_steps where run_id = ? and status <> 'PENDING'", id, id);
        jdbc.update("update agent_runs set status = ?, output = ?::jsonb, summary = ?, source = ?, confidence = ?, error = ?, finished_at = ?, duration_ms = ?, "
                + "step_count = ?, tool_call_count = ?, tokens_in = ?, tokens_out = ?, updated_at = ? where id = ?", status, output == null ? null : write(output),
                summary, source, confidence, error == null ? null : trunc(error, 1000), ts(at), started == null ? null : Math.max(0, at.toEpochMilli()
                - started.toEpochMilli()), ((Number) totals.get("steps")).intValue(), ((Number) totals.get("calls")).intValue(),
                ((Number) totals.get("tin")).intValue(), ((Number) totals.get("tout")).intValue(), ts(at), id);
    }

    public boolean cancelRequested(long id) {
        Boolean b = jdbc.queryForObject("select cancel_requested from agent_runs where id = ?", Boolean.class, id);
        return Boolean.TRUE.equals(b);
    }

    // ------------------------------------------------------------------ steps and tool calls

    public long insertStep(long runId, int seq, int stage, AgentCode code, String version, Object input) {
        return jdbc.queryForObject("insert into agent_run_steps (run_id, seq, stage, agent_code, agent_version, status, input) values (?, ?, ?, ?, ?, 'PENDING', "
                + "?::jsonb) returning id", Long.class, runId, seq, stage, code.name(), version, write(input));
    }

    public void stepRunning(long stepId, Instant at) {
        jdbc.update("update agent_run_steps set status = 'RUNNING', started_at = ? where id = ?", ts(at), stepId);
    }

    public void stepDone(long stepId, String status, Object output, String source, String fallbackReason, Double confidence, Object evidence, int turns,
                         int tokensIn, int tokensOut, String error, Instant started, Instant at) {
        jdbc.update("update agent_run_steps set status = ?, output = ?::jsonb, source = ?, fallback_reason = ?, confidence = ?, evidence = ?::jsonb, llm_turns = ?, "
                + "tokens_in = ?, tokens_out = ?, error = ?, started_at = coalesce(started_at, ?), finished_at = ?, duration_ms = ? where id = ?", status,
                output == null ? null : write(output), source, fallbackReason == null ? null : trunc(fallbackReason, 500), confidence,
                write(evidence == null ? List.of() : evidence), turns, tokensIn, tokensOut, error == null ? null : trunc(error, 1000), ts(started == null ? at
                : started), ts(at), started == null ? 0 : Math.max(0, at.toEpochMilli() - started.toEpochMilli()), stepId);
    }

    public void toolCall(long runId, long stepId, int seq, String tool, Map<String, String> args, String status, JsonNode result, int size, boolean truncated,
                         String message, String requestedBy, long durationMs) {
        jdbc.update("insert into agent_tool_calls (run_id, step_id, seq, tool, args, status, result, result_size, truncated, message, requested_by, duration_ms) "
                + "values (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?)", runId, stepId, seq, tool, write(args == null ? Map.of() : args), status,
                result == null ? null : result.toString(), size, truncated, message == null ? null : trunc(message, 500), requestedBy, durationMs);
    }

    public List<StepDto> steps(long runId) {
        Map<Long, List<ToolCallDto>> byStep = new java.util.HashMap<>();
        jdbc.query("select * from agent_tool_calls where run_id = ? order by step_id, seq", rs -> {
            byStep.computeIfAbsent(rs.getLong("step_id"), k -> new java.util.ArrayList<>()).add(new ToolCallDto(rs.getLong("id"), rs.getInt("seq"),
                    rs.getString("tool"), read(rs.getString("args"), new TypeReference<Map<String, Object>>() { }), rs.getString("status"),
                    tree(rs.getString("result")), rs.getInt("result_size"), rs.getBoolean("truncated"), rs.getString("message"), rs.getString("requested_by"),
                    rs.getLong("duration_ms"), inst(rs, "created_at")));
        }, runId);
        return jdbc.query("select * from agent_run_steps where run_id = ? order by seq", (rs, n) -> new StepDto(rs.getLong("id"), rs.getInt("seq"),
                rs.getInt("stage"), AgentCode.valueOf(rs.getString("agent_code")), rs.getString("agent_version"), rs.getString("status"),
                tree(rs.getString("input")), tree(rs.getString("output")), rs.getString("source"), rs.getString("fallback_reason"),
                (Double) rs.getObject("confidence"), tree(rs.getString("evidence")), rs.getInt("llm_turns"), rs.getInt("tokens_in"), rs.getInt("tokens_out"),
                rs.getString("error"), inst(rs, "started_at"), inst(rs, "finished_at"), (Long) rs.getObject("duration_ms"),
                byStep.getOrDefault(rs.getLong("id"), List.of())), runId);
    }

    // ------------------------------------------------------------------ actions and feedback

    public long insertAction(long runId, Long stepId, AgentCode code, ActionKind kind, String targetType, long targetId, Map<String, String> params,
                             String title, String rationale, boolean requiresApproval, String link) {
        return jdbc.queryForObject("insert into agent_run_actions (run_id, step_id, agent_code, action_kind, target_type, target_id, params, title, rationale, "
                + "requires_approval, link) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) returning id", Long.class, runId, stepId, code.name(), kind.name(),
                targetType, targetId, write(params), trunc(title, 250), trunc(rationale, 1000), requiresApproval, link);
    }

    public List<ActionDto> actions(long runId) {
        return jdbc.query("select * from agent_run_actions where run_id = ? order by id", (rs, n) -> action(rs), runId);
    }

    public ActionDto action(long runId, long actionId) {
        return jdbc.query("select * from agent_run_actions where run_id = ? and id = ?", (rs, n) -> action(rs), runId, actionId).stream().findFirst().orElse(null);
    }

    public ActionDto actionById(long actionId) {
        return jdbc.query("select * from agent_run_actions where id = ?", (rs, n) -> action(rs), actionId).stream().findFirst().orElse(null);
    }

    private ActionDto action(ResultSet rs) throws SQLException {
        return new ActionDto(rs.getLong("id"), rs.getLong("run_id"), (Long) rs.getObject("step_id"), AgentCode.valueOf(rs.getString("agent_code")),
                ActionKind.valueOf(rs.getString("action_kind")), rs.getString("target_type"), rs.getLong("target_id"),
                read(rs.getString("params"), new TypeReference<Map<String, Object>>() { }), rs.getString("title"), rs.getString("rationale"),
                rs.getBoolean("requires_approval"), rs.getString("link"), rs.getString("status"), (Long) rs.getObject("approval_id"),
                rs.getString("decided_by_name"), inst(rs, "decided_at"), rs.getString("decision_comment"), rs.getString("result"), inst(rs, "created_at"));
    }

    public List<FeedbackDto> feedback(long runId) {
        return jdbc.query("select * from agent_feedback where run_id = ? order by updated_at desc", (rs, n) -> new FeedbackDto(rs.getLong("id"),
                rs.getLong("user_id"), rs.getString("user_name"), rs.getBoolean("helpful"), rs.getString("comment"), inst(rs, "created_at"),
                inst(rs, "updated_at")), runId);
    }

    // ------------------------------------------------------------------ helpers

    String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise agent data", e);
        }
    }

    <T> T read(String text, TypeReference<T> type) {
        try {
            return text == null ? null : json.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read agent data", e);
        }
    }

    JsonNode tree(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    static Instant inst(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    static String trunc(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
