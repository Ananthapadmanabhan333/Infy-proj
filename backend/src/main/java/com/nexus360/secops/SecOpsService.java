package com.nexus360.secops;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalHandler;
import com.nexus360.approval.ApprovalService;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.observability.TelemetryJson;
import com.nexus360.secops.SecOpsDtos.AcceptRiskRequest;
import com.nexus360.secops.SecOpsDtos.AddSecurityNoteRequest;
import com.nexus360.secops.SecOpsDtos.CreateSecurityIncidentRequest;
import com.nexus360.secops.SecOpsDtos.CreateVulnerabilitySourceRequest;
import com.nexus360.secops.SecOpsDtos.SecurityCorrelationGroupDto;
import com.nexus360.secops.SecOpsDtos.SecurityIncidentDetailDto;
import com.nexus360.secops.SecOpsDtos.SecurityIncidentDto;
import com.nexus360.secops.SecOpsDtos.SecurityIncidentTaskDto;
import com.nexus360.secops.SecOpsDtos.SecurityOverviewDto;
import com.nexus360.secops.SecOpsDtos.SecurityResponseTemplateDto;
import com.nexus360.secops.SecOpsDtos.TransitionSecurityIncidentRequest;
import com.nexus360.secops.SecOpsDtos.VulnerabilityFindingDto;
import com.nexus360.secops.SecOpsDtos.VulnerabilityIngestRequest;
import com.nexus360.secops.SecOpsDtos.VulnerabilityIngestResultDto;
import com.nexus360.secops.SecOpsDtos.VulnerabilitySourceCreatedDto;
import com.nexus360.secops.SecOpsDtos.VulnerabilitySourceDto;
import com.nexus360.security.Actor;
import com.nexus360.security.Hashing;
import com.nexus360.user.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecOpsService implements ApprovalHandler {

    public static final String RISK_FORMULA =
            "round1(clamp((cvssBase/10*100) * critFactor * exposureFactor * exploitFactor * ageFactor / 1.5, 0, 100))";

    public static final List<String> CATEGORIES = List.of(
            "MALWARE", "PHISHING", "UNAUTHORISED_ACCESS", "DATA_EXPOSURE",
            "VULNERABILITY_EXPLOITATION", "DOS", "POLICY_VIOLATION");
    public static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    public static final List<String> INCIDENT_STATUSES = List.of(
            "NEW", "TRIAGE", "CONTAINMENT", "ERADICATION", "RECOVERY", "CLOSED");
    public static final List<String> SOURCE_FORMATS = List.of("GENERIC_JSON", "SARIF", "TRIVY_JSON", "CSV");

    private static final Set<Role> SECOPS_WRITE_ROLES = EnumSet.of(
            Role.SYSTEM_ADMIN, Role.IT_ADMIN, Role.SECURITY_ANALYST, Role.INCIDENT_MANAGER);
    private static final Set<Role> SECOPS_READ_ROLES = EnumSet.of(
            Role.SYSTEM_ADMIN, Role.IT_ADMIN, Role.SECURITY_ANALYST, Role.INCIDENT_MANAGER, Role.AUDITOR, Role.IT_MANAGER);

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final Clock clock;

    // ------------------------------------------------------------------ Overview

    @Transactional(readOnly = true)
    public SecurityOverviewDto overview(boolean includeSimulated) {
        String simClause = includeSimulated ? "" : " AND NOT simulated";
        long openIncidents = count("SELECT count(*) FROM security_incidents WHERE status <> 'CLOSED'" + simClause);
        long criticalFindings = count("SELECT count(*) FROM vulnerability_findings WHERE status = 'OPEN' AND risk_band = 'CRITICAL'" + simClause);
        long highFindings = count("SELECT count(*) FROM vulnerability_findings WHERE status = 'OPEN' AND risk_band = 'HIGH'" + simClause);
        long openFindings = count("SELECT count(*) FROM vulnerability_findings WHERE status = 'OPEN'" + simClause);
        long openGroups = count("SELECT count(*) FROM security_correlation_groups WHERE status = 'OPEN'" + simClause);
        Instant now = clock.instant();
        Long breached = jdbc.queryForObject(
                "SELECT count(*) FROM vulnerability_findings WHERE status = 'OPEN' AND (first_seen_at + (sla_days * interval '1 day')) < ?" + simClause,
                Long.class, Timestamp.from(now));
        boolean hasSim = includeSimulated && (
                count("SELECT count(*) FROM vulnerability_findings WHERE simulated") > 0
                        || count("SELECT count(*) FROM security_incidents WHERE simulated") > 0
                        || count("SELECT count(*) FROM security_correlation_groups WHERE simulated") > 0);
        return new SecurityOverviewDto(
                openIncidents, criticalFindings, highFindings, openFindings, openGroups,
                breached == null ? 0 : breached, RISK_FORMULA, hasSim);
    }

    // ------------------------------------------------------------------ Security Incidents

    @Transactional(readOnly = true)
    public List<SecurityIncidentDto> listIncidents(String status, String severity, boolean includeSimulated, Actor actor) {
        StringBuilder sql = new StringBuilder("SELECT * FROM security_incidents WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND severity = ?");
            args.add(severity.trim().toUpperCase(Locale.ROOT));
        }
        if (!includeSimulated) {
            sql.append(" AND NOT simulated");
        }
        if (actor != null && actor.role() != null && !SECOPS_READ_ROLES.contains(actor.role())) {
            sql.append(" AND assigned_to_id = ?");
            args.add(actor.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC");
        return jdbc.query(sql.toString(), this::mapIncident, args.toArray());
    }

    @Transactional(readOnly = true)
    public SecurityIncidentDetailDto getIncidentDetail(long id, Actor actor) {
        SecurityIncidentDto inc = jdbc.query("SELECT * FROM security_incidents WHERE id = ?", this::mapIncident, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("SecurityIncident", id));
        if (actor != null && actor.role() != null && !SECOPS_READ_ROLES.contains(actor.role())
                && !Objects.equals(inc.assignedToId(), actor.id())) {
            throw ApiException.forbidden("Restricted security incident visibility");
        }
        List<SecurityIncidentTaskDto> tasks = jdbc.query(
                "SELECT * FROM security_incident_tasks WHERE security_incident_id = ? ORDER BY task_order ASC, id ASC",
                this::mapTask, id);
        return new SecurityIncidentDetailDto(inc, tasks);
    }

    @Transactional
    public SecurityIncidentDetailDto createIncident(CreateSecurityIncidentRequest req, Actor actor) {
        String category = req.category() == null || req.category().isBlank()
                ? "VULNERABILITY_EXPLOITATION"
                : normalizeEnum(req.category(), CATEGORIES, "category");
        String severity = req.severity() == null || req.severity().isBlank()
                ? "HIGH"
                : normalizeEnum(req.severity(), SEVERITIES, "severity");
        Instant now = clock.instant();
        Long seq = jdbc.queryForObject("SELECT nextval('security_incident_number_seq')", Long.class);
        String number = "SEC-" + seq;

        String assignedName = null;
        if (req.assignedToId() != null) {
            List<String> names = jdbc.queryForList("SELECT full_name FROM users WHERE id = ?", String.class, req.assignedToId());
            if (!names.isEmpty()) {
                assignedName = names.get(0);
            }
        }

        boolean simulated = Boolean.TRUE.equals(req.simulated());
        Long id = jdbc.queryForObject("""
                INSERT INTO security_incidents (
                    number, title, description, category, severity, status,
                    linked_incident_id, affected_ci_id, affected_service_id, affected_user_email,
                    assigned_to_id, assigned_to_name, indicators, evidence_notes,
                    simulated, is_demo, created_by_id, created_by_name, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'NEW', ?, ?, ?, ?, ?, ?, ?::jsonb, '[]'::jsonb, ?, false, ?, ?, ?, ?) RETURNING id
                """, Long.class,
                number, req.title().trim(), req.description(), category, severity,
                req.linkedIncidentId(), req.affectedCiId(), req.affectedServiceId(), req.affectedUserEmail(),
                req.assignedToId(), assignedName,
                TelemetryJson.write(req.indicators() == null ? List.of() : req.indicators()),
                simulated, actor.id(), actor.name(), Timestamp.from(now), Timestamp.from(now));

        populateIncidentTasks(id, req.templateCode(), category);
        audit.record("SECURITY_INCIDENT_CREATED", "SECURITY_INCIDENT", id, Map.of("number", number, "category", category, "severity", severity));
        return getIncidentDetail(id, actor);
    }

    private void populateIncidentTasks(long incidentId, String templateCode, String category) {
        List<Map<String, Object>> templateTasks = null;
        String playbookCode = null;
        if (templateCode != null && !templateCode.isBlank()) {
            List<SecurityResponseTemplateDto> tmpls = jdbc.query(
                    "SELECT * FROM security_response_templates WHERE lower(code) = lower(?)",
                    this::mapTemplate, templateCode.trim());
            if (!tmpls.isEmpty()) {
                templateTasks = tmpls.get(0).tasks();
                playbookCode = tmpls.get(0).recommendedPlaybookCode();
            }
        }
        if (templateTasks == null) {
            List<SecurityResponseTemplateDto> tmpls = jdbc.query(
                    "SELECT * FROM security_response_templates WHERE category = ? ORDER BY id ASC LIMIT 1",
                    this::mapTemplate, category);
            if (!tmpls.isEmpty()) {
                templateTasks = tmpls.get(0).tasks();
                playbookCode = tmpls.get(0).recommendedPlaybookCode();
            }
        }
        if (templateTasks == null || templateTasks.isEmpty()) {
            templateTasks = List.of(
                    Map.of("title", "Verify alert signals and scope affected assets", "phase", "TRIAGE"),
                    Map.of("title", "Isolate affected endpoint or revoke compromised credentials", "phase", "CONTAINMENT"),
                    Map.of("title", "Patch vulnerability or remove malicious artefacts", "phase", "ERADICATION"),
                    Map.of("title", "Restore normal operations and verify monitoring", "phase", "RECOVERY"));
        }
        int order = 1;
        for (Map<String, Object> t : templateTasks) {
            String title = String.valueOf(t.getOrDefault("title", "Response task " + order));
            String phase = String.valueOf(t.getOrDefault("phase", "TRIAGE")).toUpperCase(Locale.ROOT);
            if (!Set.of("TRIAGE", "CONTAINMENT", "ERADICATION", "RECOVERY").contains(phase)) {
                phase = "TRIAGE";
            }
            String pb = t.get("remediationPlaybookCode") != null ? String.valueOf(t.get("remediationPlaybookCode")) : playbookCode;
            jdbc.update("""
                    INSERT INTO security_incident_tasks (
                        security_incident_id, task_order, title, phase, status, remediation_playbook_code
                    ) VALUES (?, ?, ?, ?, 'OPEN', ?)
                    """, incidentId, order++, title, phase, pb);
        }
    }

    @Transactional
    public SecurityIncidentDetailDto transitionIncident(long id, TransitionSecurityIncidentRequest req, Actor actor) {
        SecurityIncidentDetailDto detail = getIncidentDetail(id, actor);
        String target = normalizeEnum(req.status(), INCIDENT_STATUSES, "status");
        if ("CLOSED".equals(target)) {
            String lessons = req.lessonsLearned() != null ? req.lessonsLearned().trim() : detail.incident().lessonsLearned();
            if (lessons == null || lessons.isBlank()) {
                throw ApiException.invalidField("lessonsLearned", "Closing a security incident requires non-blank lessonsLearned");
            }
            Instant now = clock.instant();
            jdbc.update("""
                    UPDATE security_incidents
                       SET status = 'CLOSED', lessons_learned = ?, closed_at = ?, updated_at = ?
                     WHERE id = ?
                    """, lessons, Timestamp.from(now), Timestamp.from(now), id);
        } else {
            Instant now = clock.instant();
            String lessons = req.lessonsLearned() != null && !req.lessonsLearned().isBlank()
                    ? req.lessonsLearned().trim()
                    : detail.incident().lessonsLearned();
            jdbc.update("UPDATE security_incidents SET status = ?, lessons_learned = ?, updated_at = ? WHERE id = ?",
                    target, lessons, Timestamp.from(now), id);
        }
        audit.record("SECURITY_INCIDENT_TRANSITIONED", "SECURITY_INCIDENT", id, Map.of("to", target));
        return getIncidentDetail(id, actor);
    }

    @Transactional
    public SecurityIncidentDetailDto addNoteOrIndicator(long id, AddSecurityNoteRequest req, Actor actor) {
        SecurityIncidentDetailDto detail = getIncidentDetail(id, actor);
        Instant now = clock.instant();
        List<Map<String, Object>> notes = new ArrayList<>(detail.incident().evidenceNotes());
        List<Map<String, Object>> iocs = new ArrayList<>(detail.incident().indicators());

        if (req.note() != null && !req.note().isBlank()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("author", actor.name() == null ? "SecOps Analyst" : actor.name());
            entry.put("note", req.note().trim());
            entry.put("createdAt", now.toString());
            notes.add(entry);
        }
        if (req.indicator() != null && !req.indicator().isEmpty()) {
            iocs.add(req.indicator());
        } else if (req.indicatorValue() != null && !req.indicatorValue().isBlank()) {
            Map<String, Object> ioc = new LinkedHashMap<>();
            ioc.put("type", req.indicatorType() != null && !req.indicatorType().isBlank() ? req.indicatorType().trim() : "HASH");
            ioc.put("value", req.indicatorValue().trim());
            ioc.put("confidence", req.indicatorConfidence() != null && !req.indicatorConfidence().isBlank() ? req.indicatorConfidence().trim() : "MEDIUM");
            iocs.add(ioc);
        }
        jdbc.update("UPDATE security_incidents SET evidence_notes = ?::jsonb, indicators = ?::jsonb, updated_at = ? WHERE id = ?",
                TelemetryJson.write(notes), TelemetryJson.write(iocs), Timestamp.from(now), id);
        return getIncidentDetail(id, actor);
    }

    @Transactional
    public SecurityIncidentDetailDto completeTask(long incidentId, long taskId, Actor actor) {
        getIncidentDetail(incidentId, actor);
        Instant now = clock.instant();
        int updated = jdbc.update("""
                UPDATE security_incident_tasks
                   SET status = 'COMPLETED', completed_by_name = ?, completed_at = ?
                 WHERE id = ? AND security_incident_id = ?
                """, actor.name(), Timestamp.from(now), taskId, incidentId);
        if (updated == 0) {
            throw ApiException.notFound("SecurityIncidentTask", taskId);
        }
        jdbc.update("UPDATE security_incidents SET updated_at = ? WHERE id = ?", Timestamp.from(now), incidentId);
        return getIncidentDetail(incidentId, actor);
    }

    // ------------------------------------------------------------------ Vulnerability Sources & Ingestion

    @Transactional(readOnly = true)
    public List<VulnerabilitySourceDto> listSources() {
        return jdbc.query("SELECT * FROM vulnerability_sources ORDER BY id ASC", this::mapSource);
    }

    @Transactional
    public VulnerabilitySourceCreatedDto createSource(CreateVulnerabilitySourceRequest req, Actor actor) {
        String format = normalizeEnum(req.format(), SOURCE_FORMATS, "format");
        String rawToken = "vsrc_" + Hashing.randomToken(24);
        String tokenHash = Hashing.sha256Hex(rawToken);
        String prefix = rawToken.substring(0, Math.min(12, rawToken.length()));
        Instant now = clock.instant();
        Long id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO vulnerability_sources (
                        name, format, scanner_name, token_hash, token_prefix, enabled, is_demo, created_at
                    ) VALUES (?, ?, ?, ?, ?, true, false, ?) RETURNING id
                    """, Long.class, req.name().trim(), format, req.scannerName().trim(), tokenHash, prefix, Timestamp.from(now));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("Vulnerability source '" + req.name().trim() + "' already exists");
        }
        audit.record("VULNERABILITY_SOURCE_CREATED", "VULNERABILITY_SOURCE", id, Map.of("name", req.name().trim(), "format", format));
        VulnerabilitySourceDto source = jdbc.query("SELECT * FROM vulnerability_sources WHERE id = ?", this::mapSource, id).get(0);
        return new VulnerabilitySourceCreatedDto(source, rawToken);
    }

    @Transactional
    public VulnerabilityIngestResultDto ingest(VulnerabilityIngestRequest req, String sourceTokenHeader, Actor actor) {
        Long sourceId = authenticateIngest(req.sourceId(), sourceTokenHeader, actor);
        VulnerabilitySourceDto source = jdbc.query("SELECT * FROM vulnerability_sources WHERE id = ?", this::mapSource, sourceId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("VulnerabilitySource", sourceId));

        String format = req.format() == null || req.format().isBlank()
                ? source.format()
                : normalizeEnum(req.format(), SOURCE_FORMATS, "format");
        boolean fullScan = Boolean.TRUE.equals(req.fullScan());
        boolean simulated = Boolean.TRUE.equals(req.simulated());

        List<ParsedFinding> parsed = parsePayload(format, source.scannerName(), req.payload());
        Instant now = clock.instant();

        int ingestedCount = 0;
        int updatedCount = 0;
        Set<String> seenDedupeKeys = new HashSet<>();
        List<Long> touchedIds = new ArrayList<>();

        for (ParsedFinding pf : parsed) {
            String dedupeKey = (pf.cveId().toUpperCase(Locale.ROOT) + ":"
                    + pf.assetName().toLowerCase(Locale.ROOT) + ":"
                    + pf.packageName().toLowerCase(Locale.ROOT));
            if (dedupeKey.length() > 240) {
                dedupeKey = dedupeKey.substring(0, 240);
            }
            seenDedupeKeys.add(dedupeKey);

            AssetMatch match = matchAsset(pf.assetName(), pf.assetCriticality());
            Instant firstSeen = pf.firstSeenAt() != null ? pf.firstSeenAt() : now;

            List<Map<String, Object>> existing = jdbc.queryForList(
                    "SELECT id, first_seen_at, status FROM vulnerability_findings WHERE source_id = ? AND dedupe_key = ?",
                    sourceId, dedupeKey);

            if (!existing.isEmpty()) {
                Map<String, Object> row = existing.get(0);
                long existingId = ((Number) row.get("id")).longValue();
                Timestamp fsTs = (Timestamp) row.get("first_seen_at");
                if (fsTs != null) {
                    firstSeen = fsTs.toInstant();
                }
                RiskCalculation risk = calculateRiskScore(
                        pf.cvssBase(), match.criticalityFactor(), pf.internetExposed(), pf.exploitKnown(), firstSeen, now);
                String prevStatus = String.valueOf(row.get("status"));
                String newStatus = "FIXED".equals(prevStatus) ? "OPEN" : prevStatus;

                jdbc.update("""
                        UPDATE vulnerability_findings
                           SET title = ?, cwe_id = ?, installed_version = ?, fixed_version = ?,
                               cvss_base = ?, cvss_vector = ?, ci_id = ?, service_id = ?,
                               asset_criticality = ?, internet_exposed = ?, exploit_known = ?,
                               risk_score = ?, risk_band = ?, sla_days = ?, status = ?, last_seen_at = ?, simulated = ?
                         WHERE id = ?
                        """,
                        pf.title(), pf.cweId(), pf.installedVersion(), pf.fixedVersion(),
                        pf.cvssBase(), pf.cvssVector(), match.ciId(), match.serviceId(),
                        match.criticalityFactor(), pf.internetExposed(), pf.exploitKnown(),
                        risk.score(), risk.band(), risk.slaDays(), newStatus, Timestamp.from(now), simulated, existingId);
                updatedCount++;
                touchedIds.add(existingId);
            } else {
                RiskCalculation risk = calculateRiskScore(
                        pf.cvssBase(), match.criticalityFactor(), pf.internetExposed(), pf.exploitKnown(), firstSeen, now);
                Long newId = jdbc.queryForObject("""
                        INSERT INTO vulnerability_findings (
                            source_id, dedupe_key, cve_id, cwe_id, title, scanner,
                            asset_name, ci_id, service_id, package_name, installed_version, fixed_version,
                            cvss_base, cvss_vector, asset_criticality, internet_exposed, exploit_known,
                            risk_score, risk_band, sla_days, status, first_seen_at, last_seen_at, simulated
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?) RETURNING id
                        """, Long.class,
                        sourceId, dedupeKey, pf.cveId().toUpperCase(Locale.ROOT), pf.cweId(), pf.title(), pf.scanner(),
                        pf.assetName(), match.ciId(), match.serviceId(), pf.packageName(), pf.installedVersion(), pf.fixedVersion(),
                        pf.cvssBase(), pf.cvssVector(), match.criticalityFactor(), pf.internetExposed(), pf.exploitKnown(),
                        risk.score(), risk.band(), risk.slaDays(), Timestamp.from(firstSeen), Timestamp.from(now), simulated);
                ingestedCount++;
                touchedIds.add(newId);
            }
        }

        int autoClosed = 0;
        if (fullScan) {
            List<Map<String, Object>> openRows = jdbc.queryForList(
                    "SELECT id, dedupe_key FROM vulnerability_findings WHERE source_id = ? AND status = 'OPEN'", sourceId);
            for (Map<String, Object> r : openRows) {
                String dk = String.valueOf(r.get("dedupe_key"));
                if (!seenDedupeKeys.contains(dk)) {
                    long fid = ((Number) r.get("id")).longValue();
                    jdbc.update("UPDATE vulnerability_findings SET status = 'FIXED', last_seen_at = ? WHERE id = ?",
                            Timestamp.from(now), fid);
                    autoClosed++;
                }
            }
        }

        jdbc.update("UPDATE vulnerability_sources SET last_ingested_at = ? WHERE id = ?", Timestamp.from(now), sourceId);

        List<VulnerabilityFindingDto> outFindings = touchedIds.isEmpty()
                ? List.of()
                : jdbc.query("SELECT * FROM vulnerability_findings WHERE source_id = ? ORDER BY risk_score DESC, id DESC",
                this::mapFinding, sourceId);
        return new VulnerabilityIngestResultDto(ingestedCount, updatedCount, autoClosed, outFindings);
    }

    private Long authenticateIngest(Long requestedSourceId, String sourceTokenHeader, Actor actor) {
        if (sourceTokenHeader != null && !sourceTokenHeader.isBlank()) {
            String hash = Hashing.sha256Hex(sourceTokenHeader.trim());
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM vulnerability_sources WHERE token_hash = ? AND enabled = true", Long.class, hash);
            if (ids.isEmpty()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid or disabled X-SA-Source-Token");
            }
            return requestedSourceId != null ? requestedSourceId : ids.get(0);
        }
        if (actor == null || (actor.id() == null && !actor.isSystem())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!actor.isSystem() && (actor.role() == null || !SECOPS_WRITE_ROLES.contains(actor.role()))) {
            throw ApiException.forbidden("You do not have permission to ingest vulnerability findings");
        }
        if (requestedSourceId != null) {
            return requestedSourceId;
        }
        List<Long> existing = jdbc.queryForList("SELECT id FROM vulnerability_sources ORDER BY id ASC LIMIT 1", Long.class);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        VulnerabilitySourceCreatedDto created = createSource(
                new CreateVulnerabilitySourceRequest("Default Vulnerability Scanner", "GENERIC_JSON", "SA-Scanner"), actor);
        return created.source().id();
    }

    // ------------------------------------------------------------------ Risk Score Formula (§4.6)

    public record RiskCalculation(double score, String band, int slaDays) {}

    public static RiskCalculation calculateRiskScore(
            double cvssBase, double critFactor, boolean internetExposed, boolean exploitKnown,
            Instant firstSeenAt, Instant now) {
        double base = Math.max(0.0, Math.min(1.0, cvssBase / 10.0)) * 100.0;
        double exposureFactor = internetExposed ? 1.25 : 0.90;
        double exploitFactor = exploitKnown ? 1.30 : 0.95;
        double ageDays = firstSeenAt != null && now != null && now.isAfter(firstSeenAt)
                ? Duration.between(firstSeenAt, now).toHours() / 24.0
                : 0.0;
        double ageFactor = 1.0 + Math.min(0.25, ageDays / 120.0);
        double raw = base * critFactor * exposureFactor * exploitFactor * ageFactor / 1.5;
        double clamped = Math.max(0.0, Math.min(100.0, raw));
        double score = Math.round(clamped * 10.0) / 10.0;

        String band;
        int slaDays;
        if (score >= 80.0) {
            band = "CRITICAL";
            slaDays = 3;
        } else if (score >= 60.0) {
            band = "HIGH";
            slaDays = 14;
        } else if (score >= 35.0) {
            band = "MEDIUM";
            slaDays = 30;
        } else {
            band = "LOW";
            slaDays = 90;
        }
        return new RiskCalculation(score, band, slaDays);
    }

    private record AssetMatch(Long ciId, Long serviceId, double criticalityFactor) {}

    private AssetMatch matchAsset(String assetName, Double explicitCrit) {
        List<Map<String, Object>> cis = jdbc.queryForList(
                "SELECT id, service_id, criticality FROM configuration_items WHERE lower(name) = lower(?)",
                assetName.trim());
        if (!cis.isEmpty()) {
            Map<String, Object> ci = cis.get(0);
            Long ciId = ((Number) ci.get("id")).longValue();
            Long serviceId = ci.get("service_id") instanceof Number n ? n.longValue() : null;
            String crit = String.valueOf(ci.getOrDefault("criticality", "HIGH")).toUpperCase(Locale.ROOT);
            double factor = switch (crit) {
                case "CRITICAL" -> 1.25;
                case "HIGH" -> 1.0;
                case "MEDIUM" -> 0.8;
                case "LOW" -> 0.65;
                default -> 1.0;
            };
            return new AssetMatch(ciId, serviceId, explicitCrit != null ? explicitCrit : factor);
        }
        return new AssetMatch(null, null, explicitCrit != null ? explicitCrit : 1.0);
    }

    // ------------------------------------------------------------------ Findings & Risk Acceptance

    @Transactional(readOnly = true)
    public List<VulnerabilityFindingDto> listFindings(String status, String riskBand, boolean includeSimulated) {
        StringBuilder sql = new StringBuilder("SELECT * FROM vulnerability_findings WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (riskBand != null && !riskBand.isBlank()) {
            sql.append(" AND risk_band = ?");
            args.add(riskBand.trim().toUpperCase(Locale.ROOT));
        }
        if (!includeSimulated) {
            sql.append(" AND NOT simulated");
        }
        sql.append(" ORDER BY risk_score DESC, id DESC");
        return jdbc.query(sql.toString(), this::mapFinding, args.toArray());
    }

    @Transactional
    public VulnerabilityFindingDto acceptRisk(long id, AcceptRiskRequest req, Actor actor) {
        VulnerabilityFindingDto finding = jdbc.query("SELECT * FROM vulnerability_findings WHERE id = ?", this::mapFinding, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("VulnerabilityFinding", id));
        String reason = req.acceptanceReason() != null && !req.acceptanceReason().isBlank()
                ? req.acceptanceReason().trim()
                : (req.reason() != null && !req.reason().isBlank() ? req.reason().trim() : null);
        if (reason == null || reason.isBlank()) {
            throw ApiException.invalidField("acceptanceReason", "A justification reason is required to accept vulnerability risk");
        }
        Instant now = clock.instant();
        Instant until = req.acceptedUntil() != null
                ? req.acceptedUntil()
                : now.plus(Duration.ofDays(req.days() != null && req.days() > 0 ? req.days() : 30));

        Approval approval = approvals.request(
                Approval.Type.VULNERABILITY_ACCEPTANCE,
                id,
                finding.cveId(),
                "Accept vulnerability risk " + finding.cveId() + " on " + finding.assetName(),
                reason,
                Map.of("findingId", id, "cveId", finding.cveId(), "assetName", finding.assetName(), "acceptedUntil", until.toString()),
                Role.IT_MANAGER,
                actor,
                false,
                "/security");

        jdbc.update("""
                UPDATE vulnerability_findings
                   SET status = 'ACCEPTED', accepted_until = ?, acceptance_reason = ?, approval_id = ?
                 WHERE id = ?
                """, Timestamp.from(until), reason, approval.getId(), id);

        audit.record("VULNERABILITY_RISK_ACCEPTED", "VULNERABILITY_FINDING", id,
                Map.of("cveId", finding.cveId(), "reason", reason, "approvalId", approval.getId()));
        return jdbc.query("SELECT * FROM vulnerability_findings WHERE id = ?", this::mapFinding, id).get(0);
    }

    @Override
    public Approval.Type type() {
        return Approval.Type.VULNERABILITY_ACCEPTANCE;
    }

    @Override
    @Transactional
    public void onDecision(Approval approval, boolean approved, Actor approver) {
        if (!approved) {
            jdbc.update("UPDATE vulnerability_findings SET status = 'OPEN', accepted_until = NULL WHERE id = ?",
                    approval.getReferenceId());
        }
    }

    // ------------------------------------------------------------------ Security Alert Correlation

    @Transactional(readOnly = true)
    public List<SecurityCorrelationGroupDto> listCorrelationGroups(boolean includeSimulated) {
        String sql = "SELECT * FROM security_correlation_groups WHERE 1=1"
                + (includeSimulated ? "" : " AND NOT simulated")
                + " ORDER BY created_at DESC, id DESC";
        return jdbc.query(sql, this::mapCorrelationGroup);
    }

    @Transactional
    public List<SecurityCorrelationGroupDto> correlate(boolean includeSimulated, Actor actor) {
        String simFilter = includeSimulated ? "" : " AND NOT simulated";
        Instant now = clock.instant();

        // Group open CRITICAL/HIGH or exploit-known findings by asset_name
        List<VulnerabilityFindingDto> findings = jdbc.query(
                "SELECT * FROM vulnerability_findings WHERE status = 'OPEN' AND (risk_band IN ('CRITICAL', 'HIGH') OR exploit_known = true)" + simFilter,
                this::mapFinding);

        Map<String, List<VulnerabilityFindingDto>> byAsset = new LinkedHashMap<>();
        for (VulnerabilityFindingDto f : findings) {
            byAsset.computeIfAbsent(f.assetName(), k -> new ArrayList<>()).add(f);
        }

        for (Map.Entry<String, List<VulnerabilityFindingDto>> entry : byAsset.entrySet()) {
            String asset = entry.getKey();
            List<VulnerabilityFindingDto> list = entry.getValue();
            boolean anySim = list.stream().anyMatch(VulnerabilityFindingDto::simulated);
            boolean anyCrit = list.stream().anyMatch(f -> "CRITICAL".equals(f.riskBand()) || f.exploitKnown());
            String severity = anyCrit ? "CRITICAL" : "HIGH";
            String groupKey = "SEC-ASSET:" + asset.toLowerCase(Locale.ROOT);

            Long alertCount = jdbc.queryForObject(
                    "SELECT count(*) FROM alerts a LEFT JOIN configuration_items c ON c.id = a.ci_id WHERE lower(c.name) = lower(?)",
                    Long.class, asset);
            Long failedLogins = jdbc.queryForObject(
                    "SELECT count(*) FROM audit_logs WHERE action LIKE '%LOGIN%' AND outcome = 'FAILURE'",
                    Long.class);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("findingCount", list.size());
            summary.put("cves", list.stream().map(VulnerabilityFindingDto::cveId).distinct().toList());
            summary.put("maxRiskScore", list.stream().mapToDouble(VulnerabilityFindingDto::riskScore).max().orElse(0.0));
            summary.put("correlatedAlerts", alertCount == null ? 0 : alertCount);
            summary.put("failedLoginSignals", failedLogins == null ? 0 : failedLogins);

            List<Long> existing = jdbc.queryForList(
                    "SELECT id FROM security_correlation_groups WHERE group_key = ? AND status = 'OPEN'",
                    Long.class, groupKey);
            if (!existing.isEmpty()) {
                jdbc.update("""
                        UPDATE security_correlation_groups
                           SET severity = ?, signal_summary = ?::jsonb, simulated = ?, updated_at = ?
                         WHERE id = ?
                        """, severity, TelemetryJson.write(summary), anySim, Timestamp.from(now), existing.get(0));
            } else {
                String title = "Correlated security signals on " + asset + " (" + list.size() + " high-risk finding(s))";
                jdbc.update("""
                        INSERT INTO security_correlation_groups (
                            group_key, title, target_asset, severity, status,
                            signal_summary, simulated, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 'OPEN', ?::jsonb, ?, ?, ?)
                        """, groupKey, title, asset, severity, TelemetryJson.write(summary), anySim, Timestamp.from(now), Timestamp.from(now));
            }
        }

        return listCorrelationGroups(includeSimulated);
    }

    @Transactional
    public SecurityIncidentDetailDto raiseIncidentFromGroup(long groupId, Actor actor) {
        SecurityCorrelationGroupDto group = jdbc.query("SELECT * FROM security_correlation_groups WHERE id = ?", this::mapCorrelationGroup, groupId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("SecurityCorrelationGroup", groupId));
        if ("ESCALATED".equals(group.status()) && group.securityIncidentId() != null) {
            return getIncidentDetail(group.securityIncidentId(), actor);
        }

        Long ciId = null;
        Long serviceId = null;
        if (group.targetAsset() != null) {
            AssetMatch m = matchAsset(group.targetAsset(), null);
            ciId = m.ciId();
            serviceId = m.serviceId();
        }

        SecurityIncidentDetailDto created = createIncident(new CreateSecurityIncidentRequest(
                group.title(),
                "Security incident escalated from correlation group " + group.groupKey() + ". Signal summary: " + TelemetryJson.write(group.signalSummary()),
                "VULNERABILITY_EXPLOITATION",
                group.severity(),
                null,
                ciId,
                serviceId,
                group.targetUser(),
                actor.id(),
                List.of(Map.of("type", "ASSET", "value", Objects.toString(group.targetAsset(), "unknown"), "confidence", "HIGH")),
                null,
                group.simulated()
        ), actor);

        Instant now = clock.instant();
        jdbc.update("UPDATE security_correlation_groups SET status = 'ESCALATED', security_incident_id = ?, updated_at = ? WHERE id = ?",
                created.incident().id(), Timestamp.from(now), groupId);
        return created;
    }

    @Transactional(readOnly = true)
    public List<SecurityResponseTemplateDto> listTemplates() {
        return jdbc.query("SELECT * FROM security_response_templates ORDER BY id ASC", this::mapTemplate);
    }

    // ------------------------------------------------------------------ Multi-format Parser

    private record ParsedFinding(
            String cveId,
            String cweId,
            String title,
            String scanner,
            String assetName,
            String packageName,
            String installedVersion,
            String fixedVersion,
            double cvssBase,
            String cvssVector,
            Double assetCriticality,
            boolean internetExposed,
            boolean exploitKnown,
            Instant firstSeenAt
    ) {}

    private List<ParsedFinding> parsePayload(String format, String defaultScanner, JsonNode payload) {
        if (payload == null || payload.isNull()) {
            throw ApiException.badRequest("Vulnerability ingestion payload is required");
        }
        return switch (format) {
            case "GENERIC_JSON" -> parseGenericJson(defaultScanner, payload);
            case "SARIF" -> parseSarif(defaultScanner, payload);
            case "TRIVY_JSON" -> parseTrivyJson(defaultScanner, payload);
            case "CSV" -> parseCsv(defaultScanner, payload);
            default -> throw ApiException.badRequest("Unsupported vulnerability format: " + format);
        };
    }

    private List<ParsedFinding> parseGenericJson(String defaultScanner, JsonNode payload) {
        JsonNode root = unwrapJsonString(payload);
        JsonNode arr = root.isArray() ? root : (root.has("findings") ? root.get("findings") : root);
        List<JsonNode> nodes = new ArrayList<>();
        if (arr.isArray()) {
            arr.forEach(nodes::add);
        } else if (arr.isObject()) {
            nodes.add(arr);
        }
        List<ParsedFinding> out = new ArrayList<>();
        for (JsonNode n : nodes) {
            String cve = text(n, "cveId", "cve_id", "cve", "id");
            if (cve == null || cve.isBlank()) {
                continue;
            }
            String title = Objects.requireNonNullElse(text(n, "title", "summary", "description"), cve);
            String asset = Objects.requireNonNullElse(text(n, "assetName", "asset", "host", "target"), "unknown-asset");
            String pkg = Objects.requireNonNullElse(text(n, "packageName", "package", "component"), "core-pkg");
            double cvss = number(n, 7.0, "cvssBase", "cvss", "score");
            Double crit = n.has("assetCriticality") && n.get("assetCriticality").isNumber() ? n.get("assetCriticality").asDouble() : null;
            out.add(new ParsedFinding(
                    cve.trim(),
                    text(n, "cweId", "cwe"),
                    title.trim(),
                    Objects.requireNonNullElse(text(n, "scanner"), defaultScanner),
                    asset.trim(),
                    pkg.trim(),
                    text(n, "installedVersion", "version"),
                    text(n, "fixedVersion", "fixVersion"),
                    cvss,
                    text(n, "cvssVector", "vector"),
                    crit,
                    bool(n, "internetExposed", "exposed"),
                    bool(n, "exploitKnown", "exploited"),
                    null
            ));
        }
        if (out.isEmpty()) {
            throw ApiException.badRequest("No valid vulnerability findings found in GENERIC_JSON payload");
        }
        return out;
    }

    private List<ParsedFinding> parseSarif(String defaultScanner, JsonNode payload) {
        JsonNode root = unwrapJsonString(payload);
        JsonNode runs = root.get("runs");
        if (runs == null || !runs.isArray()) {
            throw ApiException.badRequest("Invalid SARIF 2.1.0 payload: missing 'runs' array");
        }
        List<ParsedFinding> out = new ArrayList<>();
        for (JsonNode run : runs) {
            String scanner = run.at("/tool/driver/name").asText(defaultScanner);
            JsonNode results = run.get("results");
            if (results == null || !results.isArray()) {
                continue;
            }
            for (JsonNode r : results) {
                JsonNode props = r.has("properties") ? r.get("properties") : r;
                String cve = Objects.requireNonNullElse(text(r, "ruleId"), text(props, "cveId", "id"));
                if (cve == null || cve.isBlank()) {
                    continue;
                }
                String msg = r.at("/message/text").asText(null);
                String title = msg != null && !msg.isBlank() ? msg : Objects.requireNonNullElse(text(props, "title"), cve);
                String uri = r.at("/locations/0/physicalLocation/artifactLocation/uri").asText(null);
                String asset = Objects.requireNonNullElse(text(props, "assetName", "asset", "host"),
                        uri != null && !uri.isBlank() ? uri : "sarif-target");
                String pkg = Objects.requireNonNullElse(text(props, "packageName", "package"), "sarif-package");
                String level = text(r, "level");
                double defaultScore = "error".equalsIgnoreCase(level) ? 8.8 : ("warning".equalsIgnoreCase(level) ? 6.5 : 4.0);
                double cvss = number(props, defaultScore, "cvssBase", "security-severity", "score");
                out.add(new ParsedFinding(
                        cve.trim(),
                        text(props, "cweId", "cwe"),
                        title.trim(),
                        scanner,
                        asset.trim(),
                        pkg.trim(),
                        text(props, "installedVersion"),
                        text(props, "fixedVersion"),
                        cvss,
                        text(props, "cvssVector"),
                        null,
                        bool(props, "internetExposed"),
                        bool(props, "exploitKnown"),
                        null
                ));
            }
        }
        if (out.isEmpty()) {
            throw ApiException.badRequest("No valid findings found in SARIF payload");
        }
        return out;
    }

    private List<ParsedFinding> parseTrivyJson(String defaultScanner, JsonNode payload) {
        JsonNode root = unwrapJsonString(payload);
        JsonNode results = root.has("Results") ? root.get("Results") : root.get("results");
        if (results == null || !results.isArray()) {
            throw ApiException.badRequest("Invalid TRIVY_JSON payload: missing 'Results' array");
        }
        List<ParsedFinding> out = new ArrayList<>();
        for (JsonNode res : results) {
            String target = Objects.requireNonNullElse(text(res, "Target", "target", "assetName"), "trivy-target");
            JsonNode vulns = res.has("Vulnerabilities") ? res.get("Vulnerabilities") : res.get("vulnerabilities");
            if (vulns == null || !vulns.isArray()) {
                continue;
            }
            for (JsonNode v : vulns) {
                String cve = text(v, "VulnerabilityID", "vulnerabilityId", "cveId");
                if (cve == null || cve.isBlank()) {
                    continue;
                }
                String title = Objects.requireNonNullElse(text(v, "Title", "Description", "title"), cve);
                String pkg = Objects.requireNonNullElse(text(v, "PkgName", "packageName", "package"), "trivy-pkg");
                String sev = Objects.requireNonNullElse(text(v, "Severity", "severity"), "HIGH").toUpperCase(Locale.ROOT);
                double defaultCvss = switch (sev) {
                    case "CRITICAL" -> 9.4;
                    case "HIGH" -> 7.8;
                    case "MEDIUM" -> 5.5;
                    default -> 3.0;
                };
                double cvss = v.at("/CVSS/nvd/V3Score").isNumber()
                        ? v.at("/CVSS/nvd/V3Score").asDouble()
                        : number(v, defaultCvss, "cvssBase", "Score", "score");
                String cwe = v.has("CweIDs") && v.get("CweIDs").isArray() && !v.get("CweIDs").isEmpty()
                        ? v.get("CweIDs").get(0).asText()
                        : text(v, "cweId");
                out.add(new ParsedFinding(
                        cve.trim(),
                        cwe,
                        title.trim(),
                        defaultScanner,
                        Objects.requireNonNullElse(text(v, "assetName"), target).trim(),
                        pkg.trim(),
                        text(v, "InstalledVersion", "installedVersion"),
                        text(v, "FixedVersion", "fixedVersion"),
                        cvss,
                        text(v, "cvssVector"),
                        null,
                        bool(v, "internetExposed"),
                        bool(v, "exploitKnown"),
                        null
                ));
            }
        }
        if (out.isEmpty()) {
            throw ApiException.badRequest("No valid findings found in TRIVY_JSON payload");
        }
        return out;
    }

    private List<ParsedFinding> parseCsv(String defaultScanner, JsonNode payload) {
        String csv = payload.isTextual() ? payload.asText() : payload.toString();
        String[] lines = csv.split("\\r?\\n");
        List<ParsedFinding> out = new ArrayList<>();
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cols = line.split(",", -1);
            if (cols[0].trim().equalsIgnoreCase("cveId") || cols[0].trim().equalsIgnoreCase("cve_id")) {
                continue;
            }
            if (cols.length < 7) {
                throw ApiException.badRequest("CSV row has fewer than 7 columns: " + line);
            }
            String cveId = cols[0].trim();
            String title = cols[1].trim();
            String assetName = cols[2].trim();
            String pkg = cols[3].trim();
            String installed = cols[4].trim();
            String fixed = cols[5].trim();
            double cvss;
            try {
                cvss = Double.parseDouble(cols[6].trim());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("Invalid cvssBase in CSV row: " + cols[6]);
            }
            boolean exposed = cols.length > 7 && Boolean.parseBoolean(cols[7].trim());
            boolean exploit = cols.length > 8 && Boolean.parseBoolean(cols[8].trim());
            out.add(new ParsedFinding(
                    cveId, null, title.isEmpty() ? cveId : title, defaultScanner,
                    assetName, pkg, installed.isEmpty() ? null : installed, fixed.isEmpty() ? null : fixed,
                    cvss, null, null, exposed, exploit, null));
        }
        if (out.isEmpty()) {
            throw ApiException.badRequest("CSV payload contained no vulnerability rows");
        }
        return out;
    }

    private static JsonNode unwrapJsonString(JsonNode node) {
        if (node.isTextual()) {
            try {
                return TelemetryJson.MAPPER.readTree(node.asText());
            } catch (Exception e) {
                throw ApiException.badRequest("Invalid JSON string in payload");
            }
        }
        return node;
    }

    private static String text(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    private static double number(JsonNode node, double fallback, String... keys) {
        if (node == null || !node.isObject()) {
            return fallback;
        }
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                if (v.isNumber()) {
                    return v.asDouble();
                }
                try {
                    return Double.parseDouble(v.asText());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return fallback;
    }

    private static boolean bool(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) {
            return false;
        }
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                if (v.isBoolean()) {
                    return v.asBoolean();
                }
                return "true".equalsIgnoreCase(v.asText());
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Mappers & Helpers

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
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

    private SecurityIncidentDto mapIncident(ResultSet rs, int rowNum) throws SQLException {
        return new SecurityIncidentDto(
                rs.getLong("id"),
                rs.getString("number"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("category"),
                rs.getString("severity"),
                rs.getString("status"),
                nullableLong(rs, "linked_incident_id"),
                nullableLong(rs, "affected_ci_id"),
                nullableLong(rs, "affected_service_id"),
                rs.getString("affected_user_email"),
                nullableLong(rs, "assigned_to_id"),
                rs.getString("assigned_to_name"),
                TelemetryJson.objectList(rs.getString("indicators")),
                TelemetryJson.objectList(rs.getString("evidence_notes")),
                rs.getString("lessons_learned"),
                rs.getBoolean("simulated"),
                rs.getBoolean("is_demo"),
                nullableLong(rs, "created_by_id"),
                rs.getString("created_by_name"),
                ts(rs, "created_at"),
                ts(rs, "updated_at"),
                ts(rs, "closed_at")
        );
    }

    private SecurityIncidentTaskDto mapTask(ResultSet rs, int rowNum) throws SQLException {
        return new SecurityIncidentTaskDto(
                rs.getLong("id"),
                rs.getLong("security_incident_id"),
                rs.getInt("task_order"),
                rs.getString("title"),
                rs.getString("phase"),
                rs.getString("status"),
                rs.getString("remediation_playbook_code"),
                rs.getString("completed_by_name"),
                ts(rs, "completed_at")
        );
    }

    private VulnerabilitySourceDto mapSource(ResultSet rs, int rowNum) throws SQLException {
        return new VulnerabilitySourceDto(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("format"),
                rs.getString("scanner_name"),
                rs.getString("token_prefix"),
                rs.getBoolean("enabled"),
                ts(rs, "last_ingested_at"),
                rs.getBoolean("is_demo"),
                ts(rs, "created_at")
        );
    }

    private VulnerabilityFindingDto mapFinding(ResultSet rs, int rowNum) throws SQLException {
        return new VulnerabilityFindingDto(
                rs.getLong("id"),
                rs.getLong("source_id"),
                rs.getString("dedupe_key"),
                rs.getString("cve_id"),
                rs.getString("cwe_id"),
                rs.getString("title"),
                rs.getString("scanner"),
                rs.getString("asset_name"),
                nullableLong(rs, "ci_id"),
                nullableLong(rs, "service_id"),
                rs.getString("package_name"),
                rs.getString("installed_version"),
                rs.getString("fixed_version"),
                rs.getDouble("cvss_base"),
                rs.getString("cvss_vector"),
                rs.getDouble("asset_criticality"),
                rs.getBoolean("internet_exposed"),
                rs.getBoolean("exploit_known"),
                rs.getDouble("risk_score"),
                rs.getString("risk_band"),
                rs.getInt("sla_days"),
                rs.getString("status"),
                ts(rs, "accepted_until"),
                rs.getString("acceptance_reason"),
                nullableLong(rs, "approval_id"),
                ts(rs, "first_seen_at"),
                ts(rs, "last_seen_at"),
                rs.getBoolean("simulated")
        );
    }

    private SecurityCorrelationGroupDto mapCorrelationGroup(ResultSet rs, int rowNum) throws SQLException {
        return new SecurityCorrelationGroupDto(
                rs.getLong("id"),
                rs.getString("group_key"),
                rs.getString("title"),
                rs.getString("target_asset"),
                rs.getString("target_user"),
                rs.getString("severity"),
                rs.getString("status"),
                TelemetryJson.objectMap(rs.getString("signal_summary")),
                nullableLong(rs, "security_incident_id"),
                rs.getBoolean("simulated"),
                ts(rs, "created_at"),
                ts(rs, "updated_at")
        );
    }

    private SecurityResponseTemplateDto mapTemplate(ResultSet rs, int rowNum) throws SQLException {
        return new SecurityResponseTemplateDto(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("description"),
                TelemetryJson.objectList(rs.getString("tasks")),
                rs.getString("recommended_playbook_code"),
                rs.getBoolean("is_demo")
        );
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
