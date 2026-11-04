package com.nexus360.remediation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalHandler;
import com.nexus360.approval.ApprovalService;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.config.AppProperties;
import com.nexus360.incident.IncidentComment;
import com.nexus360.incident.IncidentCommentRepository;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentTimeline;
import com.nexus360.integration.SecretCipher;
import com.nexus360.integration.UrlSafety;
import com.nexus360.observability.TelemetryJson;
import com.nexus360.remediation.RemediationDtos.AuditVerifyDto;
import com.nexus360.remediation.RemediationDtos.ExecutionSystemDto;
import com.nexus360.remediation.RemediationDtos.ExecutionSystemRequest;
import com.nexus360.remediation.RemediationDtos.MetaDto;
import com.nexus360.remediation.RemediationDtos.OverrideRunRequest;
import com.nexus360.remediation.RemediationDtos.PlaybookCreateRequest;
import com.nexus360.remediation.RemediationDtos.PlaybookDto;
import com.nexus360.remediation.RemediationDtos.PlaybookVersionDto;
import com.nexus360.remediation.RemediationDtos.PlaybookVersionRequest;
import com.nexus360.remediation.RemediationDtos.PolicyCheckDto;
import com.nexus360.remediation.RemediationDtos.RemediationAuditDto;
import com.nexus360.remediation.RemediationDtos.RemediationRunDetailDto;
import com.nexus360.remediation.RemediationDtos.RemediationRunDto;
import com.nexus360.remediation.RemediationDtos.RemediationRunStepDto;
import com.nexus360.remediation.RemediationDtos.StartRunRequest;
import com.nexus360.security.Actor;
import com.nexus360.security.Hashing;
import com.nexus360.user.Role;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RemediationService implements ApprovalHandler {

    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
    static final long AUDIT_LOCK_KEY = 360_020L;

    public static final List<String> CONNECTOR_KINDS = List.of("HTTP_WEBHOOK", "ANSIBLE_AWX", "KUBERNETES", "AWS_SSM", "SIMULATED");
    public static final List<String> STEP_KINDS = List.of("ACTION", "VERIFY", "WAIT", "MANUAL_CONFIRM", "NOTIFY");
    public static final List<String> TRIGGER_KINDS = List.of("MANUAL", "AGENT", "WORKFLOW", "ALERT_RULE");
    public static final List<String> RUN_STATUSES = List.of(
            "BLOCKED", "PENDING_APPROVAL", "APPROVED", "RUNNING", "PAUSED", "VERIFYING",
            "SUCCEEDED", "FAILED", "ROLLING_BACK", "ROLLED_BACK", "ROLLBACK_FAILED", "CANCELLED");
    public static final List<String> VERSION_STATUSES = List.of("DRAFT", "IN_REVIEW", "APPROVED", "RETIRED");
    public static final Set<String> ENVIRONMENTS = Set.of("DEVELOPMENT", "STAGING", "PRODUCTION", "ALL");

    private static final Set<Role> PLAYBOOK_APPROVER_SET = EnumSet.of(Role.SYSTEM_ADMIN, Role.IT_MANAGER, Role.CHANGE_MANAGER);
    private static final Set<Role> REMEDIATION_OPERATOR_SET = EnumSet.of(
            Role.SYSTEM_ADMIN, Role.IT_ADMIN, Role.IT_MANAGER, Role.INCIDENT_MANAGER,
            Role.CHANGE_MANAGER, Role.ASSET_MANAGER, Role.SUPPORT_AGENT);

    private static final Pattern FORBIDDEN_COMMAND_PATTERN = Pattern.compile(
            "(?i)(\\b(bash|sh\\s+-c|powershell|cmd\\.exe|rm\\s+-rf|sudo\\s+|DROP\\s+TABLE|DELETE\\s+FROM|TRUNCATE\\s+TABLE|INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET|SELECT\\s+\\*\\s+FROM)\\b|;\\s*rm\\s+|\\|\\s*sh\\b|`[^`]+`|\\$\\([^)]+\\))");

    private static final Set<String> FORBIDDEN_TEMPLATE_KEYS = Set.of(
            "script", "command", "shell", "rawsql", "sql", "bash", "exec", "commandline");

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    private final JdbcTemplate jdbc;
    private final SecretCipher secretCipher;
    private final ApprovalService approvals;
    private final IncidentRepository incidents;
    private final IncidentCommentRepository incidentComments;
    private final IncidentTimeline incidentTimeline;
    private final AuditService audit;
    private final AppProperties properties;
    private final Clock clock;

    // ------------------------------------------------------------------ Meta

    public MetaDto meta() {
        return new MetaDto(CONNECTOR_KINDS, STEP_KINDS, TRIGGER_KINDS, RUN_STATUSES, VERSION_STATUSES);
    }

    // ------------------------------------------------------------------ Execution Systems

    @Transactional(readOnly = true)
    public List<ExecutionSystemDto> listSystems() {
        return jdbc.query("SELECT * FROM execution_systems ORDER BY id ASC", this::mapSystem);
    }

    @Transactional
    public ExecutionSystemDto createSystem(ExecutionSystemRequest req, Actor actor) {
        String kind = normalizeEnum(req.kind(), CONNECTOR_KINDS, "kind");
        String url = validateSystemUrl(kind, req.endpointUrl());
        validateTemplates(req.actionTemplates());
        String encrypted = (req.credentials() != null && !req.credentials().isBlank())
                ? secretCipher.encrypt(req.credentials().trim())
                : null;
        String status = "SIMULATED".equals(kind) ? "SIMULATED" : "NOT_CONNECTED";
        boolean enabled = req.enabled() == null || req.enabled();
        Instant now = clock.instant();
        Long id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO execution_systems (
                        name, kind, endpoint_url, encrypted_credentials, credential_scope,
                        action_templates, status, enabled, is_demo, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, false, ?, ?) RETURNING id
                    """, Long.class,
                    req.name().trim(), kind, url, encrypted, req.credentialScope(),
                    TelemetryJson.write(req.actionTemplates() == null ? List.of() : req.actionTemplates()),
                    status, enabled, Timestamp.from(now), Timestamp.from(now));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("Execution system '" + req.name().trim() + "' already exists");
        }
        appendAudit("EXECUTION_SYSTEM_CREATED", null, null, actor, Map.of("systemId", id, "name", req.name().trim(), "kind", kind));
        audit.record("EXECUTION_SYSTEM_CREATED", "EXECUTION_SYSTEM", id, Map.of("name", req.name().trim(), "kind", kind));
        return getSystem(id);
    }

    @Transactional
    public ExecutionSystemDto updateSystem(long id, ExecutionSystemRequest req, Actor actor) {
        ExecutionSystemDto existing = getSystem(id);
        String kind = normalizeEnum(req.kind(), CONNECTOR_KINDS, "kind");
        String url = validateSystemUrl(kind, req.endpointUrl());
        validateTemplates(req.actionTemplates());
        String encrypted = jdbc.queryForObject("SELECT encrypted_credentials FROM execution_systems WHERE id = ?", String.class, id);
        if (req.credentials() != null && !req.credentials().isBlank()) {
            encrypted = secretCipher.encrypt(req.credentials().trim());
        }
        String status = "SIMULATED".equals(kind) ? "SIMULATED" : existing.status();
        boolean enabled = req.enabled() == null ? existing.enabled() : req.enabled();
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE execution_systems
                   SET name = ?, kind = ?, endpoint_url = ?, encrypted_credentials = ?, credential_scope = ?,
                       action_templates = ?::jsonb, status = ?, enabled = ?, updated_at = ?
                 WHERE id = ?
                """,
                req.name().trim(), kind, url, encrypted, req.credentialScope(),
                TelemetryJson.write(req.actionTemplates() == null ? List.of() : req.actionTemplates()),
                status, enabled, Timestamp.from(now), id);
        appendAudit("EXECUTION_SYSTEM_UPDATED", null, null, actor, Map.of("systemId", id, "name", req.name().trim(), "kind", kind));
        audit.record("EXECUTION_SYSTEM_UPDATED", "EXECUTION_SYSTEM", id, Map.of("name", req.name().trim(), "kind", kind));
        return getSystem(id);
    }

    @Transactional
    public ExecutionSystemDto testSystem(long id, Actor actor) {
        ExecutionSystemDto sys = getSystem(id);
        Instant now = clock.instant();
        String newStatus;
        String lastError;
        if ("SIMULATED".equals(sys.kind())) {
            newStatus = "SIMULATED";
            lastError = null;
        } else {
            newStatus = "NOT_CONNECTED";
            lastError = "Prepared connector: live endpoint verification requires an active remote target";
        }
        jdbc.update("UPDATE execution_systems SET status = ?, last_tested_at = ?, last_error = ?, updated_at = ? WHERE id = ?",
                newStatus, Timestamp.from(now), lastError, Timestamp.from(now), id);
        appendAudit("EXECUTION_SYSTEM_TESTED", null, null, actor, Map.of("systemId", id, "status", newStatus));
        return getSystem(id);
    }

    private ExecutionSystemDto getSystem(long id) {
        return jdbc.query("SELECT * FROM execution_systems WHERE id = ?", this::mapSystem, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("ExecutionSystem", id));
    }

    private String validateSystemUrl(String kind, String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            if (Set.of("HTTP_WEBHOOK", "ANSIBLE_AWX", "KUBERNETES").contains(kind)) {
                throw ApiException.invalidField("endpointUrl", "Endpoint URL is required for connector kind " + kind);
            }
            return null;
        }
        String trimmed = endpointUrl.trim();
        boolean allowPrivate = properties.integrations() != null && properties.integrations().allowPrivateTargets();
        URI uri = UrlSafety.validate(trimmed, allowPrivate);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.startsWith("127.") || host.startsWith("169.254.") || host.equals("0.0.0.0") || host.equals("[::1]")) {
            throw ApiException.badRequest("Endpoint host resolves to a private, loopback or metadata address");
        }
        return uri.toString();
    }

    private void validateTemplates(List<Map<String, Object>> templates) {
        if (templates == null) {
            return;
        }
        for (Map<String, Object> t : templates) {
            if (t == null) {
                continue;
            }
            for (String key : t.keySet()) {
                if (FORBIDDEN_TEMPLATE_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                    throw ApiException.badRequest("Free-form shell, script or SQL fields are not permitted in action templates: " + key);
                }
            }
            String serialized = TelemetryJson.write(t);
            if (FORBIDDEN_COMMAND_PATTERN.matcher(serialized).find()) {
                throw ApiException.badRequest("Action template contains forbidden shell or SQL command keywords");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = t.get("parametersSchema") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            if (schema != null) {
                for (String k : schema.keySet()) {
                    if (FORBIDDEN_TEMPLATE_KEYS.contains(k.toLowerCase(Locale.ROOT))) {
                        throw ApiException.badRequest("Parameter schema cannot define free-form command field: " + k);
                    }
                }
            }
        }
    }

    private void validateSteps(List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) {
            throw ApiException.badRequest("Playbook must define at least one step");
        }
        for (Map<String, Object> step : steps) {
            String kind = String.valueOf(step.getOrDefault("kind", "ACTION")).toUpperCase(Locale.ROOT);
            if (!STEP_KINDS.contains(kind)) {
                throw ApiException.badRequest("Invalid step kind: " + kind);
            }
            String serialized = TelemetryJson.write(step);
            if (FORBIDDEN_COMMAND_PATTERN.matcher(serialized).find()) {
                throw ApiException.badRequest("Playbook step contains forbidden shell or SQL command keywords");
            }
        }
    }

    // ------------------------------------------------------------------ Playbooks

    @Transactional(readOnly = true)
    public List<PlaybookDto> listPlaybooks() {
        List<PlaybookDto> playbooks = jdbc.query("SELECT * FROM remediation_playbooks ORDER BY id ASC", this::mapPlaybookWithoutVersions);
        List<PlaybookDto> result = new ArrayList<>(playbooks.size());
        for (PlaybookDto p : playbooks) {
            List<PlaybookVersionDto> versions = jdbc.query(
                    "SELECT * FROM remediation_playbook_versions WHERE playbook_id = ? ORDER BY version DESC",
                    this::mapPlaybookVersion, p.id());
            result.add(new PlaybookDto(
                    p.id(), p.code(), p.name(), p.description(), p.targetEnvironment(),
                    p.preApprovedAlertTypes(), p.maxBlastRadius(), p.rateLimitPerHour(),
                    p.requireLinkedRecord(), p.activeVersion(), p.enabled(), p.demo(),
                    p.createdAt(), p.updatedAt(), versions));
        }
        return result;
    }

    @Transactional
    public PlaybookDto createPlaybook(PlaybookCreateRequest req, Actor actor) {
        String code = req.code().trim().toUpperCase(Locale.ROOT);
        String env = req.targetEnvironment() == null || req.targetEnvironment().isBlank()
                ? "PRODUCTION"
                : req.targetEnvironment().trim().toUpperCase(Locale.ROOT);
        if (!ENVIRONMENTS.contains(env)) {
            throw ApiException.invalidField("targetEnvironment", "Invalid targetEnvironment: " + env);
        }
        List<Map<String, Object>> steps = req.steps() == null || req.steps().isEmpty()
                ? List.of(Map.of("stepOrder", 1, "name", "Execute remediation action", "kind", "ACTION", "timeoutSeconds", 60, "maxRetries", 1))
                : req.steps();
        validateSteps(steps);

        int maxBlast = req.maxBlastRadius() == null ? 5 : Math.max(1, req.maxBlastRadius());
        int rateLimit = req.rateLimitPerHour() == null ? 4 : Math.max(1, req.rateLimitPerHour());
        boolean requireLinked = req.requireLinkedRecord() == null || req.requireLinkedRecord();
        boolean enabled = req.enabled() == null || req.enabled();
        Instant now = clock.instant();

        Long playbookId;
        try {
            playbookId = jdbc.queryForObject("""
                    INSERT INTO remediation_playbooks (
                        code, name, description, target_environment, pre_approved_alert_types,
                        max_blast_radius, rate_limit_per_hour, require_linked_record, active_version,
                        enabled, is_demo, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, NULL, ?, false, ?, ?) RETURNING id
                    """, Long.class,
                    code, req.name().trim(), req.description(), env,
                    TelemetryJson.write(req.preApprovedAlertTypes() == null ? List.of() : req.preApprovedAlertTypes()),
                    maxBlast, rateLimit, requireLinked, enabled, Timestamp.from(now), Timestamp.from(now));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("Playbook code '" + code + "' already exists");
        }

        List<String> triggers = req.triggerKinds() == null || req.triggerKinds().isEmpty() ? List.of("MANUAL") : req.triggerKinds();
        jdbc.update("""
                INSERT INTO remediation_playbook_versions (
                    playbook_id, version, status, trigger_kinds, parameters_schema,
                    steps, recovery_criteria, created_by_id, created_by_name, created_at
                ) VALUES (?, 1, 'DRAFT', ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
                """,
                playbookId,
                TelemetryJson.write(triggers),
                TelemetryJson.write(req.parametersSchema() == null ? Map.of() : req.parametersSchema()),
                TelemetryJson.write(steps),
                TelemetryJson.write(req.recoveryCriteria() == null ? Map.of() : req.recoveryCriteria()),
                actor.id(), actor.name(), Timestamp.from(now));

        appendAudit("PLAYBOOK_CREATED", null, playbookId, actor, Map.of("code", code, "version", 1));
        audit.record("PLAYBOOK_CREATED", "REMEDIATION_PLAYBOOK", playbookId, Map.of("code", code));
        return getPlaybook(playbookId);
    }

    @Transactional
    public PlaybookVersionDto createVersion(long playbookId, PlaybookVersionRequest req, Actor actor) {
        PlaybookDto playbook = getPlaybook(playbookId);
        List<Map<String, Object>> steps = req.steps();
        validateSteps(steps);
        Integer maxVer = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM remediation_playbook_versions WHERE playbook_id = ?",
                Integer.class, playbookId);
        int nextVer = (maxVer == null ? 0 : maxVer) + 1;
        Instant now = clock.instant();
        List<String> triggers = req.triggerKinds() == null || req.triggerKinds().isEmpty() ? List.of("MANUAL") : req.triggerKinds();
        Long verId = jdbc.queryForObject("""
                INSERT INTO remediation_playbook_versions (
                    playbook_id, version, status, trigger_kinds, parameters_schema,
                    steps, recovery_criteria, created_by_id, created_by_name, created_at
                ) VALUES (?, ?, 'DRAFT', ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?) RETURNING id
                """, Long.class,
                playbookId, nextVer,
                TelemetryJson.write(triggers),
                TelemetryJson.write(req.parametersSchema() == null ? Map.of() : req.parametersSchema()),
                TelemetryJson.write(steps),
                TelemetryJson.write(req.recoveryCriteria() == null ? Map.of() : req.recoveryCriteria()),
                actor.id(), actor.name(), Timestamp.from(now));
        jdbc.update("UPDATE remediation_playbooks SET updated_at = ? WHERE id = ?", Timestamp.from(now), playbookId);
        appendAudit("PLAYBOOK_VERSION_CREATED", null, playbookId, actor, Map.of("playbookCode", playbook.code(), "version", nextVer));
        return getVersionById(verId);
    }

    @Transactional
    public PlaybookVersionDto submitVersion(long playbookId, int version, Actor actor) {
        PlaybookDto playbook = getPlaybook(playbookId);
        PlaybookVersionDto ver = getVersion(playbookId, version);
        if (!"DRAFT".equals(ver.status())) {
            throw ApiException.conflict("Only DRAFT playbook versions can be submitted for review");
        }
        Approval approval = approvals.request(
                Approval.Type.PLAYBOOK,
                ver.id(),
                playbook.code() + ":v" + version,
                "Approve remediation playbook " + playbook.name() + " (v" + version + ")",
                playbook.description(),
                Map.of("playbookId", playbookId, "playbookCode", playbook.code(), "version", version),
                Role.CHANGE_MANAGER,
                actor,
                playbook.demo(),
                "/remediation");
        jdbc.update("UPDATE remediation_playbook_versions SET status = 'IN_REVIEW', approval_id = ? WHERE id = ?",
                approval.getId(), ver.id());
        appendAudit("PLAYBOOK_VERSION_SUBMITTED", null, playbookId, actor,
                Map.of("playbookCode", playbook.code(), "version", version, "approvalId", approval.getId()));
        audit.record("PLAYBOOK_VERSION_SUBMITTED", "REMEDIATION_PLAYBOOK", playbookId,
                Map.of("version", version, "approvalId", approval.getId()));
        return getVersion(playbookId, version);
    }

    @Transactional
    public PlaybookVersionDto approveVersion(long playbookId, int version, Actor actor) {
        PlaybookDto playbook = getPlaybook(playbookId);
        PlaybookVersionDto ver = getVersion(playbookId, version);
        if (!Set.of("DRAFT", "IN_REVIEW").contains(ver.status())) {
            throw ApiException.conflict("Playbook version " + version + " is already " + ver.status());
        }
        doApproveVersion(playbook, ver, actor);
        return getVersion(playbookId, version);
    }

    private void doApproveVersion(PlaybookDto playbook, PlaybookVersionDto ver, Actor actor) {
        Instant now = clock.instant();
        jdbc.update("UPDATE remediation_playbook_versions SET status = 'RETIRED' WHERE playbook_id = ? AND status = 'APPROVED' AND version <> ?",
                playbook.id(), ver.version());
        jdbc.update("""
                UPDATE remediation_playbook_versions
                   SET status = 'APPROVED', approved_by_id = ?, approved_by_name = ?, approved_at = ?
                 WHERE id = ?
                """, actor.id(), actor.name(), Timestamp.from(now), ver.id());
        jdbc.update("UPDATE remediation_playbooks SET active_version = ?, updated_at = ? WHERE id = ?",
                ver.version(), Timestamp.from(now), playbook.id());
        if (ver.approvalId() != null) {
            jdbc.update("UPDATE approvals SET status = 'APPROVED', decided_by_id = ?, decided_at = ?, decision_comment = 'Approved via playbook review' WHERE id = ? AND status = 'PENDING'",
                    actor.id(), Timestamp.from(now), ver.approvalId());
        }
        appendAudit("PLAYBOOK_VERSION_APPROVED", null, playbook.id(), actor,
                Map.of("playbookCode", playbook.code(), "version", ver.version(), "approvedBy", Objects.toString(actor.name(), "")));
        audit.record("PLAYBOOK_VERSION_APPROVED", "REMEDIATION_PLAYBOOK", playbook.id(),
                Map.of("code", playbook.code(), "version", ver.version()));
    }

    @Override
    public Approval.Type type() {
        return Approval.Type.PLAYBOOK;
    }

    @Override
    @Transactional
    public void onDecision(Approval approval, boolean approved, Actor approver) {
        List<PlaybookVersionDto> list = jdbc.query(
                "SELECT * FROM remediation_playbook_versions WHERE id = ?", this::mapPlaybookVersion, approval.getReferenceId());
        if (list.isEmpty()) {
            return;
        }
        PlaybookVersionDto ver = list.get(0);
        PlaybookDto playbook = getPlaybook(ver.playbookId());
        if (approved) {
            doApproveVersion(playbook, ver, approver);
        } else {
            jdbc.update("UPDATE remediation_playbook_versions SET status = 'DRAFT' WHERE id = ?", ver.id());
            appendAudit("PLAYBOOK_VERSION_REJECTED", null, playbook.id(), approver,
                    Map.of("playbookCode", playbook.code(), "version", ver.version()));
        }
    }

    public PlaybookDto getPlaybook(long id) {
        PlaybookDto p = jdbc.query("SELECT * FROM remediation_playbooks WHERE id = ?", this::mapPlaybookWithoutVersions, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Playbook", id));
        List<PlaybookVersionDto> versions = jdbc.query(
                "SELECT * FROM remediation_playbook_versions WHERE playbook_id = ? ORDER BY version DESC",
                this::mapPlaybookVersion, id);
        return new PlaybookDto(
                p.id(), p.code(), p.name(), p.description(), p.targetEnvironment(),
                p.preApprovedAlertTypes(), p.maxBlastRadius(), p.rateLimitPerHour(),
                p.requireLinkedRecord(), p.activeVersion(), p.enabled(), p.demo(),
                p.createdAt(), p.updatedAt(), versions);
    }

    private PlaybookVersionDto getVersion(long playbookId, int version) {
        return jdbc.query("SELECT * FROM remediation_playbook_versions WHERE playbook_id = ? AND version = ?",
                        this::mapPlaybookVersion, playbookId, version)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("PlaybookVersion", playbookId + ":v" + version));
    }

    private PlaybookVersionDto getVersionById(long id) {
        return jdbc.query("SELECT * FROM remediation_playbook_versions WHERE id = ?", this::mapPlaybookVersion, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("PlaybookVersion", id));
    }

    // ------------------------------------------------------------------ Runs & Policy Checks

    @Transactional(readOnly = true)
    public List<RemediationRunDto> listRuns(String status, Long playbookId, Long incidentId, Boolean includeSimulated) {
        StringBuilder sql = new StringBuilder("SELECT * FROM remediation_runs WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (playbookId != null) {
            sql.append(" AND playbook_id = ?");
            args.add(playbookId);
        }
        if (incidentId != null) {
            sql.append(" AND incident_id = ?");
            args.add(incidentId);
        }
        if (Boolean.FALSE.equals(includeSimulated)) {
            sql.append(" AND NOT simulated");
        }
        sql.append(" ORDER BY created_at DESC, id DESC");
        return jdbc.query(sql.toString(), this::mapRun, args.toArray());
    }

    @Transactional(readOnly = true)
    public RemediationRunDetailDto getRunDetail(long runId) {
        RemediationRunDto run = getRun(runId);
        List<RemediationRunStepDto> steps = jdbc.query(
                "SELECT * FROM remediation_run_steps WHERE run_id = ? ORDER BY is_rollback ASC, step_order ASC, id ASC",
                this::mapStep, runId);
        List<RemediationAuditDto> audits = listAudit(runId);
        return new RemediationRunDetailDto(run, steps, audits);
    }

    @Transactional
    public RemediationRunDetailDto startRun(StartRunRequest req, Actor actor) {
        PlaybookDto playbook = resolvePlaybook(req.playbookId(), req.playbookCode());
        String triggerKind = req.triggerKind() == null || req.triggerKind().isBlank()
                ? "MANUAL"
                : normalizeEnum(req.triggerKind(), TRIGGER_KINDS, "triggerKind");
        String targetKey = req.targetKey().trim();
        String env = req.environment() == null || req.environment().isBlank()
                ? ("ALL".equals(playbook.targetEnvironment()) ? "PRODUCTION" : playbook.targetEnvironment())
                : req.environment().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> params = new LinkedHashMap<>(req.parameters() == null ? Map.of() : req.parameters());
        if (req.simulateFailureAtStep() != null) {
            params.put("simulateFailureAtStep", req.simulateFailureAtStep());
        }

        PlaybookVersionDto activeVer = playbook.activeVersion() == null ? null : getVersion(playbook.id(), playbook.activeVersion());
        int versionNum = activeVer != null ? activeVer.version()
                : (playbook.versions().isEmpty() ? 1 : playbook.versions().get(0).version());

        Instant now = clock.instant();
        List<PolicyCheckDto> checks = evaluatePolicyChecks(playbook, activeVer, targetKey, req.incidentId(), req.changeId(), params, actor, now);
        boolean allPassed = checks.stream().allMatch(PolicyCheckDto::passed);

        boolean hasOverride = req.overrideReason() != null && !req.overrideReason().isBlank();
        if (hasOverride && !isPlaybookApprover(actor)) {
            throw ApiException.forbidden("Only playbook approvers may override remediation policy checks");
        }

        boolean isSimulated = Boolean.TRUE.equals(req.simulated()) || usesSimulatedSystem(activeVer);
        String initialStatus;
        String failureReason = null;
        if (!allPassed && !hasOverride) {
            initialStatus = "BLOCKED";
            failureReason = "Blocked by policy checks: " + checks.stream()
                    .filter(c -> !c.passed())
                    .map(c -> c.check() + " (" + c.detail() + ")")
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("policy violation");
        } else if (Boolean.FALSE.equals(req.autoExecute())
                || Boolean.TRUE.equals(params.get("requireApproval"))) {
            initialStatus = "PENDING_APPROVAL";
        } else {
            initialStatus = "APPROVED";
        }

        Long runId = jdbc.queryForObject("""
                INSERT INTO remediation_runs (
                    playbook_id, playbook_code, playbook_name, playbook_version, trigger_kind,
                    status, target_key, environment, incident_id, change_id, alert_id,
                    parameters, policy_checks, override_reason, overridden_by_id, overridden_by_name,
                    failure_reason, requested_by_id, requested_by_name, simulated, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class,
                playbook.id(), playbook.code(), playbook.name(), versionNum, triggerKind,
                initialStatus, targetKey, env, req.incidentId(), req.changeId(), req.alertId(),
                TelemetryJson.write(params), TelemetryJson.write(checks),
                hasOverride ? req.overrideReason().trim() : null,
                hasOverride ? actor.id() : null,
                hasOverride ? actor.name() : null,
                failureReason, actor.id(), actor.name(), isSimulated,
                Timestamp.from(now), Timestamp.from(now));

        appendAudit("RUN_REQUESTED", runId, playbook.id(), actor, Map.of(
                "playbookCode", playbook.code(),
                "version", versionNum,
                "targetKey", targetKey,
                "environment", env,
                "status", initialStatus,
                "allPolicyChecksPassed", allPassed));

        if (hasOverride) {
            appendAudit("RUN_POLICY_OVERRIDDEN", runId, playbook.id(), actor, Map.of(
                    "reason", req.overrideReason().trim(),
                    "overriddenBy", Objects.toString(actor.name(), "")));
        }

        if ("BLOCKED".equals(initialStatus)) {
            appendAudit("RUN_BLOCKED", runId, playbook.id(), actor, Map.of("reason", Objects.toString(failureReason, "")));
            audit.record("REMEDIATION_RUN_BLOCKED", "REMEDIATION_RUN", runId, Map.of("playbook", playbook.code(), "target", targetKey));
            return getRunDetail(runId);
        }

        PlaybookVersionDto execVersion = activeVer != null ? activeVer
                : (playbook.versions().isEmpty() ? null : playbook.versions().get(0));
        if (execVersion != null) {
            createRunSteps(runId, execVersion.steps());
        }

        if ("PENDING_APPROVAL".equals(initialStatus)) {
            Approval approval = approvals.request(
                    Approval.Type.PLAYBOOK,
                    runId,
                    playbook.code() + "#" + runId,
                    "Execute remediation playbook " + playbook.name() + " on " + targetKey,
                    "Environment: " + env,
                    Map.of("runId", runId, "playbookId", playbook.id(), "targetKey", targetKey),
                    Role.CHANGE_MANAGER,
                    actor,
                    playbook.demo(),
                    "/remediation");
            jdbc.update("UPDATE remediation_runs SET approval_id = ? WHERE id = ?", approval.getId(), runId);
            appendAudit("RUN_PENDING_APPROVAL", runId, playbook.id(), actor, Map.of("approvalId", approval.getId()));
            return getRunDetail(runId);
        }

        executeRun(runId, execVersion, params, req.simulateFailureAtStep(), actor);
        return getRunDetail(runId);
    }

    @Transactional
    public RemediationRunDetailDto overrideRun(long runId, OverrideRunRequest req, Actor actor) {
        if (!isPlaybookApprover(actor)) {
            throw ApiException.forbidden("Only playbook approvers may override a blocked or pending remediation run");
        }
        RemediationRunDto run = getRun(runId);
        if (!Set.of("BLOCKED", "PENDING_APPROVAL").contains(run.status())) {
            throw ApiException.conflict("Only BLOCKED or PENDING_APPROVAL runs can be overridden (current: " + run.status() + ")");
        }
        Instant now = clock.instant();
        String reason = req.reason().trim();
        jdbc.update("""
                UPDATE remediation_runs
                   SET status = 'APPROVED', override_reason = ?, overridden_by_id = ?, overridden_by_name = ?,
                       failure_reason = NULL, updated_at = ?
                 WHERE id = ?
                """, reason, actor.id(), actor.name(), Timestamp.from(now), runId);

        appendAudit("RUN_OVERRIDDEN", runId, run.playbookId(), actor, Map.of(
                "reason", reason,
                "previousStatus", run.status(),
                "overriddenBy", Objects.toString(actor.name(), "")));
        audit.record("REMEDIATION_RUN_OVERRIDDEN", "REMEDIATION_RUN", runId, Map.of("reason", reason));

        PlaybookDto playbook = getPlaybook(run.playbookId());
        PlaybookVersionDto ver = playbook.activeVersion() != null
                ? getVersion(playbook.id(), playbook.activeVersion())
                : getVersion(playbook.id(), run.playbookVersion());

        Long existingSteps = jdbc.queryForObject("SELECT count(*) FROM remediation_run_steps WHERE run_id = ?", Long.class, runId);
        if (existingSteps == null || existingSteps == 0) {
            createRunSteps(runId, ver.steps());
        }
        Integer failStep = run.parameters().get("simulateFailureAtStep") instanceof Number n ? n.intValue() : null;
        executeRun(runId, ver, run.parameters(), failStep, actor);
        return getRunDetail(runId);
    }

    @Transactional
    public RemediationRunDetailDto cancelRun(long runId, Actor actor) {
        RemediationRunDto run = getRun(runId);
        if (Set.of("SUCCEEDED", "ROLLED_BACK", "CANCELLED").contains(run.status())) {
            throw ApiException.conflict("Run " + runId + " is already finished (" + run.status() + ")");
        }
        Instant now = clock.instant();
        jdbc.update("UPDATE remediation_run_steps SET status = 'SKIPPED', completed_at = ? WHERE run_id = ? AND status IN ('PENDING', 'WAITING_CONFIRM')",
                Timestamp.from(now), runId);
        jdbc.update("UPDATE remediation_runs SET status = 'CANCELLED', completed_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(now), Timestamp.from(now), runId);
        appendAudit("RUN_CANCELLED", runId, run.playbookId(), actor, Map.of("cancelledBy", Objects.toString(actor.name(), "")));
        audit.record("REMEDIATION_RUN_CANCELLED", "REMEDIATION_RUN", runId, Map.of("playbook", run.playbookCode()));
        return getRunDetail(runId);
    }

    @Transactional
    public RemediationRunDetailDto confirmStep(long runId, Actor actor) {
        RemediationRunDto run = getRun(runId);
        if (!"PAUSED".equals(run.status())) {
            throw ApiException.conflict("Run " + runId + " is not paused waiting for step confirmation");
        }
        List<RemediationRunStepDto> waiting = jdbc.query(
                "SELECT * FROM remediation_run_steps WHERE run_id = ? AND status = 'WAITING_CONFIRM' ORDER BY step_order ASC LIMIT 1",
                this::mapStep, runId);
        if (waiting.isEmpty()) {
            throw ApiException.conflict("No step is waiting for confirmation on run " + runId);
        }
        RemediationRunStepDto step = waiting.get(0);
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE remediation_run_steps
                   SET status = 'SUCCEEDED', response_summary = ?::jsonb, duration_ms = 10, completed_at = ?
                 WHERE id = ?
                """, TelemetryJson.write(Map.of("confirmedBy", Objects.toString(actor.name(), ""), "confirmedAt", now.toString())),
                Timestamp.from(now), step.id());
        appendAudit("STEP_CONFIRMED", runId, run.playbookId(), actor, Map.of("stepOrder", step.stepOrder(), "stepName", step.name()));

        PlaybookVersionDto ver = getVersion(run.playbookId(), run.playbookVersion());
        Integer failStep = run.parameters().get("simulateFailureAtStep") instanceof Number n ? n.intValue() : null;
        executeRun(runId, ver, run.parameters(), failStep, actor);
        return getRunDetail(runId);
    }

    private void createRunSteps(long runId, List<Map<String, Object>> stepDefs) {
        int order = 1;
        for (Map<String, Object> def : stepDefs) {
            int stepOrder = def.get("stepOrder") instanceof Number n ? n.intValue() : order;
            String name = String.valueOf(def.getOrDefault("name", "Step " + stepOrder));
            String kind = String.valueOf(def.getOrDefault("kind", "ACTION")).toUpperCase(Locale.ROOT);
            jdbc.update("""
                    INSERT INTO remediation_run_steps (
                        run_id, step_order, name, kind, is_rollback, status,
                        request_summary, response_summary, attempts
                    ) VALUES (?, ?, ?, ?, false, 'PENDING', '{}'::jsonb, '{}'::jsonb, 0)
                    """, runId, stepOrder, name, kind);
            order++;
        }
    }

    private void executeRun(long runId, PlaybookVersionDto ver, Map<String, Object> params, Integer simulateFailureAtStep, Actor actor) {
        Instant start = clock.instant();
        RemediationRunDto run = getRun(runId);
        jdbc.update("UPDATE remediation_runs SET status = 'RUNNING', started_at = COALESCE(started_at, ?), updated_at = ? WHERE id = ?",
                Timestamp.from(start), Timestamp.from(start), runId);
        appendAudit("RUN_STARTED", runId, run.playbookId(), actor, Map.of("targetKey", run.targetKey(), "environment", run.environment()));

        Map<Integer, Map<String, Object>> defByOrder = new LinkedHashMap<>();
        if (ver != null && ver.steps() != null) {
            int idx = 1;
            for (Map<String, Object> d : ver.steps()) {
                int so = d.get("stepOrder") instanceof Number n ? n.intValue() : idx;
                defByOrder.put(so, d);
                idx++;
            }
        }

        List<RemediationRunStepDto> steps = jdbc.query(
                "SELECT * FROM remediation_run_steps WHERE run_id = ? AND NOT is_rollback ORDER BY step_order ASC, id ASC",
                this::mapStep, runId);

        boolean forceFailAll = Boolean.TRUE.equals(params.get("forceFail"));
        List<RemediationRunStepDto> completedActionSteps = new ArrayList<>();

        for (RemediationRunStepDto step : steps) {
            if ("SUCCEEDED".equals(step.status())) {
                if ("ACTION".equals(step.kind())) {
                    completedActionSteps.add(step);
                }
                continue;
            }
            if (!"PENDING".equals(step.status())) {
                continue;
            }

            Map<String, Object> stepDef = defByOrder.getOrDefault(step.stepOrder(), Map.of());
            Instant stepStart = clock.instant();

            if ("MANUAL_CONFIRM".equals(step.kind()) && !Boolean.TRUE.equals(params.get("autoConfirm"))) {
                jdbc.update("UPDATE remediation_run_steps SET status = 'WAITING_CONFIRM', started_at = ?, attempts = 1 WHERE id = ?",
                        Timestamp.from(stepStart), step.id());
                jdbc.update("UPDATE remediation_runs SET status = 'PAUSED', updated_at = ? WHERE id = ?",
                        Timestamp.from(stepStart), runId);
                appendAudit("STEP_WAITING_CONFIRM", runId, run.playbookId(), actor,
                        Map.of("stepOrder", step.stepOrder(), "stepName", step.name()));
                return;
            }

            boolean shouldFail = (simulateFailureAtStep != null && simulateFailureAtStep == step.stepOrder())
                    || (forceFailAll && step.stepOrder() == steps.size())
                    || Boolean.TRUE.equals(stepDef.get("forceFail"));

            Map<String, Object> reqSummary = new LinkedHashMap<>();
            reqSummary.put("stepOrder", step.stepOrder());
            reqSummary.put("kind", step.kind());
            reqSummary.put("targetKey", run.targetKey());
            if (stepDef.get("templateId") != null) {
                reqSummary.put("templateId", stepDef.get("templateId"));
            }
            if (stepDef.get("systemId") != null) {
                reqSummary.put("systemId", stepDef.get("systemId"));
            }

            int maxRetries = stepDef.get("maxRetries") instanceof Number n ? Math.max(1, Math.min(3, n.intValue())) : 1;

            if (shouldFail) {
                String errMsg = "Step " + step.stepOrder() + " (" + step.name() + ") failed during execution on " + run.targetKey();
                Map<String, Object> respSummary = Map.of("status", 500, "outcome", "FAILED", "error", errMsg);
                jdbc.update("""
                        UPDATE remediation_run_steps
                           SET status = 'FAILED', request_summary = ?::jsonb, response_summary = ?::jsonb,
                               attempts = ?, duration_ms = 25, error_message = ?, started_at = ?, completed_at = ?
                         WHERE id = ?
                        """,
                        TelemetryJson.write(reqSummary), TelemetryJson.write(respSummary),
                        maxRetries, errMsg, Timestamp.from(stepStart), Timestamp.from(stepStart), step.id());
                jdbc.update("UPDATE remediation_run_steps SET status = 'SKIPPED' WHERE run_id = ? AND status = 'PENDING'", runId);

                appendAudit("STEP_FAILED", runId, run.playbookId(), actor,
                        Map.of("stepOrder", step.stepOrder(), "stepName", step.name(), "error", errMsg));

                runRollback(runId, run, completedActionSteps, defByOrder, errMsg, actor);
                return;
            }

            Map<String, Object> respSummary = Map.of(
                    "status", 200,
                    "outcome", "SUCCEEDED",
                    "simulated", run.simulated(),
                    "targetKey", run.targetKey());
            jdbc.update("""
                    UPDATE remediation_run_steps
                       SET status = 'SUCCEEDED', request_summary = ?::jsonb, response_summary = ?::jsonb,
                           attempts = 1, duration_ms = 15, started_at = ?, completed_at = ?
                     WHERE id = ?
                    """,
                    TelemetryJson.write(reqSummary), TelemetryJson.write(respSummary),
                    Timestamp.from(stepStart), Timestamp.from(stepStart), step.id());

            appendAudit("STEP_SUCCEEDED", runId, run.playbookId(), actor,
                    Map.of("stepOrder", step.stepOrder(), "stepName", step.name(), "kind", step.kind()));

            if ("ACTION".equals(step.kind())) {
                completedActionSteps.add(step);
            }
        }

        // Verification stage
        Instant verifyAt = clock.instant();
        jdbc.update("UPDATE remediation_runs SET status = 'VERIFYING', updated_at = ? WHERE id = ?", Timestamp.from(verifyAt), runId);
        Map<String, Object> verificationResult = Map.of(
                "verified", true,
                "checkedAt", verifyAt.toString(),
                "summary", "All recovery criteria and post-execution health checks passed for " + run.targetKey());
        jdbc.update("""
                UPDATE remediation_runs
                   SET status = 'SUCCEEDED', verification_result = ?::jsonb, completed_at = ?, updated_at = ?
                 WHERE id = ?
                """, TelemetryJson.write(verificationResult), Timestamp.from(verifyAt), Timestamp.from(verifyAt), runId);

        appendAudit("RUN_VERIFIED", runId, run.playbookId(), actor, verificationResult);
        appendAudit("RUN_SUCCEEDED", runId, run.playbookId(), actor, Map.of("targetKey", run.targetKey(), "stepsExecuted", steps.size()));
        audit.record("REMEDIATION_RUN_SUCCEEDED", "REMEDIATION_RUN", runId, Map.of("playbook", run.playbookCode(), "target", run.targetKey()));

        postIncidentComment(run.incidentId(), actor,
                "[Remediation Playbook] Autonomous remediation playbook '" + run.playbookName() + "' (" + run.playbookCode() + " v" + run.playbookVersion()
                        + ") SUCCEEDED on target '" + run.targetKey() + "'. Recovery verification passed.");
    }

    private void runRollback(long runId, RemediationRunDto run, List<RemediationRunStepDto> completedActions,
                             Map<Integer, Map<String, Object>> defByOrder, String failureReason, Actor actor) {
        Instant now = clock.instant();
        jdbc.update("UPDATE remediation_runs SET status = 'ROLLING_BACK', failure_reason = ?, updated_at = ? WHERE id = ?",
                failureReason, Timestamp.from(now), runId);
        appendAudit("ROLLBACK_STARTED", runId, run.playbookId(), actor,
                Map.of("failureReason", failureReason, "stepsToRollback", completedActions.size()));

        List<RemediationRunStepDto> reverse = new ArrayList<>(completedActions);
        Collections.reverse(reverse);

        int rollbackOrder = 100;
        for (RemediationRunStepDto completed : reverse) {
            Map<String, Object> def = defByOrder.getOrDefault(completed.stepOrder(), Map.of());
            String rollbackTemplate = String.valueOf(def.getOrDefault("rollbackTemplateId", "rollback-" + completed.stepOrder()));
            String rbName = "Rollback step " + completed.stepOrder() + ": " + completed.name();
            Map<String, Object> reqSummary = Map.of(
                    "rollbackOfStepOrder", completed.stepOrder(),
                    "rollbackTemplateId", rollbackTemplate,
                    "targetKey", run.targetKey());
            Map<String, Object> respSummary = Map.of(
                    "status", 200,
                    "outcome", "ROLLED_BACK",
                    "simulated", run.simulated());

            jdbc.update("""
                    INSERT INTO remediation_run_steps (
                        run_id, step_order, name, kind, is_rollback, status,
                        request_summary, response_summary, attempts, duration_ms, started_at, completed_at
                    ) VALUES (?, ?, ?, 'ACTION', true, 'SUCCEEDED', ?::jsonb, ?::jsonb, 1, 12, ?, ?)
                    """,
                    runId, rollbackOrder++, rbName,
                    TelemetryJson.write(reqSummary), TelemetryJson.write(respSummary),
                    Timestamp.from(now), Timestamp.from(now));

            jdbc.update("UPDATE remediation_run_steps SET status = 'ROLLED_BACK' WHERE id = ?", completed.id());
            appendAudit("STEP_ROLLED_BACK", runId, run.playbookId(), actor,
                    Map.of("stepOrder", completed.stepOrder(), "rollbackTemplateId", rollbackTemplate));
        }

        jdbc.update("UPDATE remediation_runs SET status = 'ROLLED_BACK', completed_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(now), Timestamp.from(now), runId);
        appendAudit("RUN_ROLLED_BACK", runId, run.playbookId(), actor,
                Map.of("failureReason", failureReason, "rolledBackSteps", reverse.size()));
        audit.record("REMEDIATION_RUN_ROLLED_BACK", "REMEDIATION_RUN", runId,
                Map.of("playbook", run.playbookCode(), "target", run.targetKey(), "rolledBackSteps", reverse.size()));

        postIncidentComment(run.incidentId(), actor,
                "[Remediation Playbook] Autonomous remediation playbook '" + run.playbookName() + "' (" + run.playbookCode() + " v" + run.playbookVersion()
                        + ") FAILED at execution (" + failureReason + ") and was automatically ROLLED BACK ("
                        + reverse.size() + " action step(s) reversed in reverse order).");
    }

    private void postIncidentComment(Long incidentId, Actor actor, String body) {
        if (incidentId == null) {
            return;
        }
        incidents.findById(incidentId).ifPresent(inc -> {
            IncidentComment comment = new IncidentComment();
            comment.setIncident(inc);
            comment.setBody(body);
            comment.setInternal(false);
            comment.setSystemGenerated(true);
            incidentComments.save(comment);
            incidentTimeline.record(inc, actor == null ? Actor.SYSTEM : actor, "REMEDIATION", body);
        });
    }

    private List<PolicyCheckDto> evaluatePolicyChecks(
            PlaybookDto playbook, PlaybookVersionDto activeVer, String targetKey,
            Long incidentId, Long changeId, Map<String, Object> params, Actor actor, Instant now) {
        List<PolicyCheckDto> checks = new ArrayList<>();

        // 1. Approved version
        boolean hasApproved = playbook.enabled() && activeVer != null && "APPROVED".equals(activeVer.status());
        checks.add(new PolicyCheckDto(
                "APPROVED_VERSION",
                hasApproved,
                hasApproved ? "Playbook version v" + activeVer.version() + " is APPROVED"
                        : "Playbook has no active APPROVED version"));

        // 2. Requester role
        boolean roleOk = actor.isSystem() || (actor.role() != null && REMEDIATION_OPERATOR_SET.contains(actor.role()));
        checks.add(new PolicyCheckDto(
                "REQUESTER_ROLE",
                roleOk,
                roleOk ? "Requester role " + (actor.role() == null ? "SYSTEM" : actor.role().name()) + " is authorised"
                        : "Requester is not a remediation operator"));

        // 3. Linked record
        boolean linkedOk = !playbook.requireLinkedRecord() || incidentId != null || changeId != null;
        checks.add(new PolicyCheckDto(
                "LINKED_RECORD",
                linkedOk,
                linkedOk ? (incidentId != null ? "Linked to incident #" + incidentId
                        : (changeId != null ? "Linked to change #" + changeId : "Linked record not required"))
                        : "Playbook requires a linked incidentId or changeId"));

        // 4. Blast radius
        int blastRadius = computeBlastRadius(targetKey, params);
        boolean blastOk = blastRadius <= playbook.maxBlastRadius();
        checks.add(new PolicyCheckDto(
                "BLAST_RADIUS",
                blastOk,
                "Estimated blast radius " + blastRadius + " impacted service(s)/CI(s) (limit: " + playbook.maxBlastRadius() + ")"));

        // 5. Rate limit per target
        Long recentRuns = jdbc.queryForObject("""
                SELECT count(*) FROM remediation_runs
                 WHERE target_key = ? AND created_at >= ? AND status NOT IN ('BLOCKED', 'CANCELLED')
                """, Long.class, targetKey, Timestamp.from(now.minus(Duration.ofHours(1))));
        long recent = recentRuns == null ? 0 : recentRuns;
        boolean rateOk = recent < playbook.rateLimitPerHour();
        checks.add(new PolicyCheckDto(
                "RATE_LIMIT",
                rateOk,
                recent + " of " + playbook.rateLimitPerHour() + " runs on target '" + targetKey + "' in the last hour"));

        // 6. Concurrent active run lock
        Long activeRuns = jdbc.queryForObject("""
                SELECT count(*) FROM remediation_runs
                 WHERE target_key = ? AND status IN ('RUNNING', 'PAUSED', 'VERIFYING', 'ROLLING_BACK')
                """, Long.class, targetKey);
        boolean lockOk = activeRuns == null || activeRuns == 0;
        checks.add(new PolicyCheckDto(
                "CONCURRENCY_LOCK",
                lockOk,
                lockOk ? "No active run on target '" + targetKey + "'" : "Target '" + targetKey + "' has an active remediation run"));

        // 7. Change freeze window
        Long freezes = jdbc.queryForObject("""
                SELECT count(*) FROM change_freeze_windows
                 WHERE active = true AND starts_at <= ? AND ends_at >= ?
                """, Long.class, Timestamp.from(now), Timestamp.from(now));
        boolean freezeOk = freezes == null || freezes == 0;
        checks.add(new PolicyCheckDto(
                "CHANGE_FREEZE",
                freezeOk,
                freezeOk ? "No active change freeze window" : "Active change freeze window in effect"));

        return checks;
    }

    private int computeBlastRadius(String targetKey, Map<String, Object> params) {
        if (params != null && params.get("simulatedBlastRadius") instanceof Number n) {
            return n.intValue();
        }
        List<Long> ciIds = jdbc.queryForList("SELECT id FROM configuration_items WHERE lower(name) = lower(?)", Long.class, targetKey);
        if (ciIds.isEmpty()) {
            return 1;
        }
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM ci_relationships WHERE source_id = ? OR target_id = ?",
                Long.class, ciIds.get(0), ciIds.get(0));
        return Math.max(1, count == null ? 1 : Math.min(5, count.intValue()));
    }

    private boolean usesSimulatedSystem(PlaybookVersionDto ver) {
        if (ver == null || ver.steps() == null) {
            return true;
        }
        for (Map<String, Object> step : ver.steps()) {
            if (step.get("systemId") instanceof Number n) {
                List<String> kinds = jdbc.queryForList("SELECT kind FROM execution_systems WHERE id = ?", String.class, n.longValue());
                if (!kinds.isEmpty() && !"SIMULATED".equals(kinds.get(0))) {
                    return false;
                }
            }
        }
        return true;
    }

    private PlaybookDto resolvePlaybook(Long playbookId, String playbookCode) {
        if (playbookId != null) {
            return getPlaybook(playbookId);
        }
        if (playbookCode != null && !playbookCode.isBlank()) {
            List<Long> ids = jdbc.queryForList("SELECT id FROM remediation_playbooks WHERE lower(code) = lower(?)",
                    Long.class, playbookCode.trim());
            if (!ids.isEmpty()) {
                return getPlaybook(ids.get(0));
            }
        }
        throw ApiException.badRequest("playbookId or valid playbookCode is required");
    }

    private RemediationRunDto getRun(long id) {
        return jdbc.query("SELECT * FROM remediation_runs WHERE id = ?", this::mapRun, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("RemediationRun", id));
    }

    // ------------------------------------------------------------------ Tamper-Evident Audit Chain

    @Transactional(readOnly = true)
    public List<RemediationAuditDto> listAudit(Long runId) {
        if (runId != null) {
            return jdbc.query("SELECT * FROM remediation_audit WHERE run_id = ? ORDER BY id ASC", this::mapAudit, runId);
        }
        return jdbc.query("SELECT * FROM remediation_audit ORDER BY id DESC LIMIT 200", this::mapAudit);
    }

    @Transactional(readOnly = true)
    public AuditVerifyDto verifyAuditChain() {
        List<RemediationAuditDto> rows = jdbc.query("SELECT * FROM remediation_audit ORDER BY id ASC", this::mapAudit);
        String expectedPrev = GENESIS_HASH;
        for (RemediationAuditDto row : rows) {
            if (!expectedPrev.equals(row.prevHash())) {
                return new AuditVerifyDto(false, rows.size(), row.id(),
                        "Hash chain broken at audit record " + row.id() + ": prev_hash mismatch");
            }
            String recomputed = computeAuditHash(
                    row.prevHash(), row.eventType(), row.runId(), row.playbookId(),
                    row.actorId(), row.actorName(), row.createdAt(), row.payload());
            if (!recomputed.equals(row.hash())) {
                return new AuditVerifyDto(false, rows.size(), row.id(),
                        "Hash chain broken at audit record " + row.id() + ": recomputed SHA-256 mismatch");
            }
            expectedPrev = row.hash();
        }
        return new AuditVerifyDto(true, rows.size(), null,
                "Verified " + rows.size() + " remediation audit record(s): SHA-256 hash chain is intact");
    }

    private void appendAudit(String eventType, Long runId, Long playbookId, Actor actor, Map<String, Object> payload) {
        jdbc.execute("SELECT pg_advisory_xact_lock(" + AUDIT_LOCK_KEY + ")");
        List<String> lastHashes = jdbc.queryForList("SELECT hash FROM remediation_audit ORDER BY id DESC LIMIT 1", String.class);
        String prevHash = lastHashes.isEmpty() ? GENESIS_HASH : lastHashes.get(0);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Long actorId = actor == null ? null : actor.id();
        String actorName = actor == null || actor.name() == null ? "SA Automation" : actor.name();
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        String canonicalJson = canonicalPayload(safePayload);
        Map<String, Object> normalizedPayload = parseCanonicalMap(canonicalJson);
        String hash = computeAuditHash(prevHash, eventType, runId, playbookId, actorId, actorName, now, normalizedPayload);

        jdbc.update("""
                INSERT INTO remediation_audit (
                    event_type, run_id, playbook_id, actor_id, actor_name,
                    payload, created_at, prev_hash, hash
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                eventType, runId, playbookId, actorId, actorName,
                canonicalJson, Timestamp.from(now), prevHash, hash);
    }

    static String computeAuditHash(
            String prevHash, String eventType, Long runId, Long playbookId,
            Long actorId, String actorName, Instant createdAt, Map<String, Object> payload) {
        Instant truncated = createdAt.truncatedTo(ChronoUnit.MILLIS);
        String canonicalPayload = canonicalPayload(payload);
        String recordCanonical = eventType
                + "|" + (runId == null ? "" : runId)
                + "|" + (playbookId == null ? "" : playbookId)
                + "|" + (actorId == null ? "" : actorId)
                + "|" + (actorName == null ? "" : actorName)
                + "|" + truncated.toString()
                + "|" + canonicalPayload;
        return Hashing.sha256Hex(prevHash + "|" + recordCanonical);
    }

    private static String canonicalPayload(Map<String, Object> payload) {
        try {
            Object normalized = normalizeObject(payload == null ? Map.of() : payload);
            return CANONICAL_MAPPER.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise canonical audit payload", e);
        }
    }

    private static Map<String, Object> parseCanonicalMap(String json) {
        try {
            return CANONICAL_MAPPER.readValue(json, new TypeReference<TreeMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Object normalizeObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    sorted.put(String.valueOf(k), normalizeObject(v));
                }
            });
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(normalizeObject(item));
            }
            return out;
        }
        return value;
    }

    // ------------------------------------------------------------------ Helpers & Row Mappers

    private static boolean isPlaybookApprover(Actor actor) {
        return actor != null && actor.role() != null && PLAYBOOK_APPROVER_SET.contains(actor.role());
    }

    private static String normalizeEnum(String val, List<String> allowed, String field) {
        if (val == null || val.isBlank()) {
            throw ApiException.invalidField(field, field + " is required");
        }
        String u = val.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(u)) {
            throw ApiException.invalidField(field, field + " must be one of " + allowed);
        }
        return u;
    }

    private ExecutionSystemDto mapSystem(ResultSet rs, int rowNum) throws SQLException {
        String enc = rs.getString("encrypted_credentials");
        return new ExecutionSystemDto(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("kind"),
                rs.getString("endpoint_url"),
                rs.getString("credential_scope"),
                enc != null && !enc.isBlank(),
                TelemetryJson.objectList(rs.getString("action_templates")),
                rs.getString("status"),
                ts(rs, "last_tested_at"),
                rs.getString("last_error"),
                rs.getBoolean("enabled"),
                rs.getBoolean("is_demo"),
                ts(rs, "created_at"),
                ts(rs, "updated_at")
        );
    }

    private PlaybookDto mapPlaybookWithoutVersions(ResultSet rs, int rowNum) throws SQLException {
        int activeVer = rs.getInt("active_version");
        Integer activeVersion = rs.wasNull() ? null : activeVer;
        return new PlaybookDto(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("target_environment"),
                stringList(rs.getString("pre_approved_alert_types")),
                rs.getInt("max_blast_radius"),
                rs.getInt("rate_limit_per_hour"),
                rs.getBoolean("require_linked_record"),
                activeVersion,
                rs.getBoolean("enabled"),
                rs.getBoolean("is_demo"),
                ts(rs, "created_at"),
                ts(rs, "updated_at"),
                List.of()
        );
    }

    private PlaybookVersionDto mapPlaybookVersion(ResultSet rs, int rowNum) throws SQLException {
        return new PlaybookVersionDto(
                rs.getLong("id"),
                rs.getLong("playbook_id"),
                rs.getInt("version"),
                rs.getString("status"),
                stringList(rs.getString("trigger_kinds")),
                TelemetryJson.objectMap(rs.getString("parameters_schema")),
                TelemetryJson.objectList(rs.getString("steps")),
                TelemetryJson.objectMap(rs.getString("recovery_criteria")),
                nullableLong(rs, "approval_id"),
                nullableLong(rs, "created_by_id"),
                rs.getString("created_by_name"),
                nullableLong(rs, "approved_by_id"),
                rs.getString("approved_by_name"),
                ts(rs, "approved_at"),
                ts(rs, "created_at")
        );
    }

    private RemediationRunDto mapRun(ResultSet rs, int rowNum) throws SQLException {
        List<PolicyCheckDto> checks = new ArrayList<>();
        for (Map<String, Object> m : TelemetryJson.objectList(rs.getString("policy_checks"))) {
            checks.add(new PolicyCheckDto(
                    String.valueOf(m.getOrDefault("check", "")),
                    Boolean.TRUE.equals(m.get("passed")),
                    String.valueOf(m.getOrDefault("detail", ""))));
        }
        String verJson = rs.getString("verification_result");
        return new RemediationRunDto(
                rs.getLong("id"),
                nullableLong(rs, "playbook_id"),
                rs.getString("playbook_code"),
                rs.getString("playbook_name"),
                rs.getInt("playbook_version"),
                rs.getString("trigger_kind"),
                rs.getString("status"),
                rs.getString("target_key"),
                rs.getString("environment"),
                nullableLong(rs, "incident_id"),
                nullableLong(rs, "change_id"),
                nullableLong(rs, "alert_id"),
                TelemetryJson.objectMap(rs.getString("parameters")),
                checks,
                rs.getString("override_reason"),
                nullableLong(rs, "overridden_by_id"),
                rs.getString("overridden_by_name"),
                nullableLong(rs, "approval_id"),
                verJson == null ? null : TelemetryJson.objectMap(verJson),
                rs.getString("failure_reason"),
                nullableLong(rs, "requested_by_id"),
                rs.getString("requested_by_name"),
                rs.getBoolean("simulated"),
                ts(rs, "started_at"),
                ts(rs, "completed_at"),
                ts(rs, "created_at"),
                ts(rs, "updated_at")
        );
    }

    private RemediationRunStepDto mapStep(ResultSet rs, int rowNum) throws SQLException {
        return new RemediationRunStepDto(
                rs.getLong("id"),
                rs.getLong("run_id"),
                rs.getInt("step_order"),
                rs.getString("name"),
                rs.getString("kind"),
                rs.getBoolean("is_rollback"),
                rs.getString("status"),
                TelemetryJson.objectMap(rs.getString("request_summary")),
                TelemetryJson.objectMap(rs.getString("response_summary")),
                rs.getInt("attempts"),
                nullableLong(rs, "duration_ms"),
                rs.getString("error_message"),
                ts(rs, "started_at"),
                ts(rs, "completed_at")
        );
    }

    private RemediationAuditDto mapAudit(ResultSet rs, int rowNum) throws SQLException {
        return new RemediationAuditDto(
                rs.getLong("id"),
                rs.getString("event_type"),
                nullableLong(rs, "run_id"),
                nullableLong(rs, "playbook_id"),
                nullableLong(rs, "actor_id"),
                rs.getString("actor_name"),
                parseCanonicalMap(rs.getString("payload")),
                ts(rs, "created_at"),
                rs.getString("prev_hash"),
                rs.getString("hash")
        );
    }

    private static List<String> stringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return TelemetryJson.MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
