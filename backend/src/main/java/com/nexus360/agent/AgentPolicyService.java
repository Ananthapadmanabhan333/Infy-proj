package com.nexus360.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.agent.AgentDtos.ActionKind;
import com.nexus360.agent.AgentDtos.AgentCode;
import com.nexus360.agent.AgentDtos.PolicyDto;
import com.nexus360.agent.AgentDtos.PolicyRequest;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.security.Actor;
import com.nexus360.security.Roles;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Governance policies per agent (AI governance): enabled, the subset of the agent's declared tools it may call, maximum LLM tool-use
 * steps, per-step timeout, the roles that may run it (within AGENT_USERS), the action kinds that need an approval (others are offered as
 * a prefilled link to the target UI) and data scopes ({@code telemetryMinutes}, {@code historyDays}). Changes are audited.
 */
@Service
@RequiredArgsConstructor
public class AgentPolicyService {

    static final Set<String> AGENT_ROLES = Set.of(Roles.AGENT_USER_ROLES);
    public static final int DEFAULT_TELEMETRY_MINUTES = 60;
    public static final int DEFAULT_HISTORY_DAYS = 180;

    private final JdbcTemplate jdbc;
    private final AgentDefinitions definitions;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    /** Effective policy of one agent. */
    public record Policy(AgentCode code, boolean enabled, List<String> allowedTools, int maxSteps, int timeoutSeconds, List<String> allowedRoles,
                         List<ActionKind> approvalActionKinds, int telemetryMinutes, int historyDays, Map<String, Object> dataScopes, String updatedByName,
                         java.time.Instant updatedAt) {

        public boolean roleAllowed(String role) {
            return allowedRoles.contains(role);
        }

        public boolean toolAllowed(String tool) {
            return allowedTools.contains(tool);
        }

        public PolicyDto dto() {
            return new PolicyDto(code, enabled, allowedTools, maxSteps, timeoutSeconds, allowedRoles, approvalActionKinds, dataScopes, updatedByName, updatedAt);
        }
    }

    public List<Policy> all() {
        return Arrays.stream(AgentCode.values()).map(this::get).toList();
    }

    public Policy get(AgentCode code) {
        return jdbc.query("select * from agent_policies where agent_code = ?", (rs, n) -> {
            Map<String, Object> scopes = read(rs.getString("data_scopes"), new TypeReference<Map<String, Object>>() { });
            Timestamp updated = rs.getTimestamp("updated_at");
            return new Policy(code, rs.getBoolean("enabled"), read(rs.getString("allowed_tools"), new TypeReference<List<String>>() { }), rs.getInt("max_steps"),
                    rs.getInt("timeout_seconds"), read(rs.getString("allowed_roles"), new TypeReference<List<String>>() { }),
                    read(rs.getString("approval_action_kinds"), new TypeReference<List<ActionKind>>() { }), intScope(scopes, "telemetryMinutes",
                    DEFAULT_TELEMETRY_MINUTES), intScope(scopes, "historyDays", DEFAULT_HISTORY_DAYS), scopes, rs.getString("updated_by_name"),
                    updated == null ? null : updated.toInstant());
        }, code.name()).stream().findFirst().orElseThrow(() -> ApiException.notFound("Agent policy", code));
    }

    @Transactional
    public PolicyDto update(AgentCode code, PolicyRequest r, Actor actor) {
        List<String> declared = definitions.get(code).tools();
        List<String> tools = r.allowedTools().stream().map(String::trim).distinct().toList();
        for (String t : tools) {
            if (!declared.contains(t)) {
                throw ApiException.invalidField("allowedTools", "Agent " + code + " does not declare tool '" + t + "' (declared: " + declared + ")");
            }
        }
        List<String> roles = r.allowedRoles().stream().map(String::trim).distinct().toList();
        for (String role : roles) {
            if (!AGENT_ROLES.contains(role)) {
                throw ApiException.invalidField("allowedRoles", "'" + role + "' is not an agent user role " + AGENT_ROLES);
            }
        }
        Map<String, Object> scopes = new LinkedHashMap<>();
        if (r.dataScopes() != null) {
            if (r.dataScopes().telemetryMinutes() != null) {
                scopes.put("telemetryMinutes", r.dataScopes().telemetryMinutes());
            }
            if (r.dataScopes().historyDays() != null) {
                scopes.put("historyDays", r.dataScopes().historyDays());
            }
        }
        Policy before = get(code);
        jdbc.update("update agent_policies set enabled = ?, allowed_tools = ?::jsonb, max_steps = ?, timeout_seconds = ?, allowed_roles = ?::jsonb, "
                + "approval_action_kinds = ?::jsonb, data_scopes = ?::jsonb, updated_by_name = ?, updated_at = ?, version = version + 1 where agent_code = ?",
                r.enabled(), write(tools), r.maxSteps(), r.timeoutSeconds(), write(roles), write(r.approvalActionKinds().stream().distinct().toList()),
                write(scopes), actor.name(), Timestamp.from(clock.instant()), code.name());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("agent", code.name());
        details.put("enabled", r.enabled());
        details.put("previouslyEnabled", before.enabled());
        details.put("allowedTools", tools);
        details.put("maxSteps", r.maxSteps());
        details.put("timeoutSeconds", r.timeoutSeconds());
        details.put("allowedRoles", roles);
        details.put("approvalActionKinds", r.approvalActionKinds());
        audit.record("AGENT_POLICY_UPDATED", "AGENT_POLICY", code.name(), details);
        return get(code).dto();
    }

    private static int intScope(Map<String, Object> scopes, String key, int fallback) {
        return scopes != null && scopes.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return text == null ? null : json.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
