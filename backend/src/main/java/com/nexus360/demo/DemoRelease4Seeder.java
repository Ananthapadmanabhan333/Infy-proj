package com.nexus360.demo;

import com.nexus360.config.AppProperties;
import com.nexus360.security.Hashing;
import com.nexus360.user.UserRepository;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release 4 (BE-5, BE-6, BE-7, BE-8) demo configuration (all {@code is_demo = true}, seeded once per part, also on upgraded databases):
 * telemetry mapping rules, OTLP source, anomaly detectors, CMDB redundancy, agent eval cases, remediation execution systems & playbooks,
 * security response templates & scanner source, FinOps cost source/rules/budget, and DEX proactive rule.
 * <b>No live telemetry, cost items, vulnerability findings, or DEX endpoints are seeded</b>: live data comes only from real ingestion
 * or from simulation scenarios.
 */
@Slf4j
@Component
@Order(29)
@RequiredArgsConstructor
public class DemoRelease4Seeder implements ApplicationRunner {

    private final AppProperties properties;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Demo demo = properties.demo();
        if (demo == null || !demo.seed() || users.countByDemoTrue() == 0 || id("select id from business_services where code = 'PAYROLL'") == null) {
            return;
        }
        int rules = seedMappings();
        boolean source = seedSource();
        int detectors = seedDetectors();
        int redundant = seedRedundancy();
        int evalCases = seedEvalCases();
        int remediation = seedRemediation();
        int secops = seedSecOps();
        int finops = seedFinOps();
        int dex = seedDex();
        if (rules + detectors + redundant + evalCases + remediation + secops + finops + dex > 0 || source) {
            log.info("Release 4 demo configuration: {} mapping rules, OTLP source {}, {} anomaly detectors, {} redundant relationships, {} eval cases, {} remediation, {} secops, {} finops, {} dex",
                    rules, source ? "seeded" : "present", detectors, redundant, evalCases, remediation, secops, finops, dex);
        }
    }

    private int seedMappings() {
        if (count("select count(*) from telemetry_mapping_rules where is_demo") > 0) {
            return 0;
        }
        Instant now = clock.instant();
        Object[][] rules = {
                {"Payroll API", "payroll-api", "PAYROLL", "Payroll Application", 10},
                {"Payroll database", "payroll-db", null, "payroll-db-primary", 20},
                {"HR portal", "hr-portal", "HR-PORTAL", "HR Portal Application", 30},
                {"ERP finance", "erp-finance", "ERP", "ERP Finance Application", 40}};
        int n = 0;
        for (Object[] r : rules) {
            Long service = r[2] == null ? null : id("select id from business_services where code = ?", r[2]);
            Long ci = id("select id from configuration_items where lower(name) = lower(?)", r[3]);
            if (service == null && ci == null) {
                continue;
            }
            jdbc.update("insert into telemetry_mapping_rules (name, attribute_key, match_type, match_value, service_id, ci_id, sort_order, is_demo, created_at, "
                    + "updated_at) values (?, 'service.name', 'EXACT', ?, ?, ?, ?, true, ?, ?)", r[0], r[1], service, ci, r[4], Timestamp.from(now),
                    Timestamp.from(now));
            n++;
        }
        return n;
    }

    private boolean seedSource() {
        if (count("select count(*) from telemetry_sources where is_demo") > 0) {
            return false;
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = "otsec_" + Hashing.sha256Hex(java.util.Base64.getEncoder().encodeToString(random)).substring(0, 40);
        Instant now = clock.instant();
        jdbc.update("insert into telemetry_sources (name, kind, auth_type, token_hash, token_hint, config, is_demo, created_at, updated_at) values "
                + "('Demo OTLP collector', 'OTLP', 'NONE', ?, ?, '{}'::jsonb, true, ?, ?) on conflict (name) do nothing", Hashing.sha256Hex(token),
                token.substring(0, 10), Timestamp.from(now), Timestamp.from(now));
        return true;
    }

    private int seedDetectors() {
        if (count("select count(*) from anomaly_detectors where is_demo") > 0) {
            return 0;
        }
        Instant now = clock.instant();
        Long payroll = id("select id from business_services where code = 'PAYROLL'");
        Long hr = id("select id from business_services where code = 'HR-PORTAL'");
        Long db = id("select id from configuration_items where lower(name) = 'payroll-db-primary'");
        record D(String name, String description, String metric, Long service, Long ci, String filters, String algorithm, int window, double threshold,
                 String severity, boolean incident) {}
        List<D> list = List.of(
                new D("Payroll DB connection pool (simulation)", "Used connections of the payroll database pool (simulated series of PAYROLL_DB_SATURATION).",
                        "db.client.connections.usage", null, db, "{\"state\": \"used\"}", "EWMA", 30, 4.0, "CRITICAL", true),
                new D("Payroll API latency (simulation)", "Mean request duration of successful payroll API requests (simulated series).",
                        "http.server.request.duration", payroll, null, "{\"http.response.status_code\": \"200\"}", "EWMA", 30, 4.0, "MAJOR", false),
                new D("HR portal latency (simulation)", "Mean request duration of the HR portal (simulated series of DEPLOY_REGRESSION).",
                        "http.server.request.duration", hr, null, "{\"http.response.status_code\": \"200\"}", "MEDIAN_MAD", 60, 4.0, "MAJOR", false));
        int n = 0;
        for (D d : list) {
            if (d.service() == null && d.ci() == null) {
                continue;
            }
            jdbc.update("insert into anomaly_detectors (name, description, metric_name, service_id, ci_id, attribute_filters, value_aggregation, algorithm, "
                    + "window_points, threshold, consecutive_points, min_samples, direction, seasonality, include_simulated, raise_alert, alert_severity, "
                    + "create_incident, enabled, is_demo, created_at, updated_at) values (?, ?, ?, ?, ?, ?::jsonb, 'avg', ?, ?, ?, 3, 30, 'UP', 'NONE', true, "
                    + "true, ?, ?, true, true, ?, ?) on conflict (name) do nothing", d.name(), d.description(), d.metric(), d.service(), d.ci(), d.filters(),
                    d.algorithm(), d.window(), d.threshold(), d.severity(), d.incident(), Timestamp.from(now), Timestamp.from(now));
            n++;
        }
        return n;
    }

    private int seedRedundancy() {
        if (count("select count(*) from ci_relationships where is_demo and attributes ? 'redundant'") > 0) {
            return 0;
        }
        String[][] pairs = {{"payroll-alb", "payroll-app-01"}, {"payroll-alb", "payroll-app-02"}, {"Payroll Application", "payroll-app-01"},
                {"Payroll Application", "payroll-app-02"}, {"Identity Provider (ADFS)", "adfs-01"}, {"Identity Provider (ADFS)", "adfs-02"},
                {"VPN & Remote Access", "vpn-gw-01"}, {"VPN & Remote Access", "vpn-gw-02"}};
        int n = 0;
        for (String[] p : pairs) {
            n += jdbc.update("update ci_relationships r set attributes = r.attributes || '{\"redundant\": true}'::jsonb, updated_at = ? from configuration_items s, "
                    + "configuration_items t where s.id = r.source_id and t.id = r.target_id and lower(s.name) = lower(?) and lower(t.name) = lower(?) and r.is_demo",
                    Timestamp.from(clock.instant()), p[0], p[1]);
        }
        return n;
    }

    private int seedEvalCases() {
        if (count("select count(*) from agent_eval_cases where is_demo") > 0) {
            return 0;
        }
        Instant now = clock.instant();
        Long incId = id("select id from incidents where is_demo and not simulated order by id limit 1");
        Long chgId = id("select id from changes where is_demo order by id limit 1");
        int n = 0;
        if (incId != null) {
            String cat = jdbc.queryForObject("select category from incidents where id = ?", String.class, incId);
            jdbc.update("""
                    insert into agent_eval_cases (
                        name, agent_code, run_type, subject_type, subject_id, expected,
                        enabled, is_demo, created_by_name, created_at, updated_at
                    ) values (?, 'INCIDENT', 'INVESTIGATE_INCIDENT', 'INCIDENT', ?, ?::jsonb, true, true, 'System Seed', ?, ?)
                    on conflict (name) do nothing
                    """, "Incident triage classification check", incId, "{\"category\": \"" + cat + "\"}",
                    Timestamp.from(now), Timestamp.from(now));
            n++;
            jdbc.update("""
                    insert into agent_eval_cases (
                        name, agent_code, run_type, subject_type, subject_id, expected,
                        enabled, is_demo, created_by_name, created_at, updated_at
                    ) values (?, 'RESOLUTION', 'INVESTIGATE_INCIDENT', 'INCIDENT', ?, ?::jsonb, true, true, 'System Seed', ?, ?)
                    on conflict (name) do nothing
                    """, "Incident resolution plan check", incId, "{\"keywords\": [\"resolution\", \"incident\"]}",
                    Timestamp.from(now), Timestamp.from(now));
            n++;
        }
        if (chgId != null) {
            jdbc.update("""
                    insert into agent_eval_cases (
                        name, agent_code, run_type, subject_type, subject_id, expected,
                        enabled, is_demo, created_by_name, created_at, updated_at
                    ) values (?, 'CHANGE_RISK', 'ASSESS_CHANGE', 'CHANGE', ?, ?::jsonb, true, true, 'System Seed', ?, ?)
                    on conflict (name) do nothing
                    """, "Change risk blast-radius check", chgId, "{\"keywords\": [\"risk\", \"change\"]}",
                    Timestamp.from(now), Timestamp.from(now));
            n++;
        }
        jdbc.update("""
                insert into agent_eval_cases (
                    name, agent_code, run_type, question, expected,
                    enabled, is_demo, created_by_name, created_at, updated_at
                ) values (?, 'KNOWLEDGE', 'ANSWER_QUESTION', ?, ?::jsonb, true, true, 'System Seed', ?, ?)
                on conflict (name) do nothing
                """, "Knowledge retrieval for VPN connectivity", "How do I troubleshoot VPN connectivity issues?",
                "{\"keywords\": [\"vpn\"]}", Timestamp.from(now), Timestamp.from(now));
        n++;
        return n;
    }

    private int seedRemediation() {
        if (count("select count(*) from execution_systems where is_demo") > 0) {
            return 0;
        }
        Instant now = clock.instant();
        String simTemplates = """
                [
                  {"id":"drain-idle-connections","name":"Drain Idle DB Connections","description":"Drains idle pool sessions above watermark","parametersSchema":{"maxIdleSeconds":"integer"},"targetScope":"DATABASE"},
                  {"id":"restore-pool-defaults","name":"Restore Pool Defaults","description":"Reverts pool sizing to baseline","parametersSchema":{},"targetScope":"DATABASE"},
                  {"id":"revoke-user-sessions","name":"Revoke Active SSO Sessions","description":"Revokes active tokens for user","parametersSchema":{"userEmail":"string"},"targetScope":"IDENTITY"}
                ]
                """;
        jdbc.update("""
                insert into execution_systems (
                    name, kind, endpoint_url, credential_scope, action_templates, status, enabled, is_demo, created_at, updated_at
                ) values ('SA Simulation Sandbox Runner', 'SIMULATED', null, 'SIMULATION_SANDBOX', ?::jsonb, 'SIMULATED', true, true, ?, ?)
                on conflict (name) do nothing
                """, simTemplates, Timestamp.from(now), Timestamp.from(now));

        jdbc.update("""
                insert into execution_systems (
                    name, kind, endpoint_url, credential_scope, action_templates, status, enabled, is_demo, created_at, updated_at
                ) values ('Kubernetes Production Controller (Prepared)', 'KUBERNETES', 'https://k8s-api.prod.nexus360.example.com', 'namespace:payroll-prod', '[]'::jsonb, 'NOT_CONNECTED', true, true, ?, ?)
                on conflict (name) do nothing
                """, Timestamp.from(now), Timestamp.from(now));

        Long simSysId = id("select id from execution_systems where name = 'SA Simulation Sandbox Runner'");
        Long adminId = id("select id from users where role = 'SYSTEM_ADMIN' order by id limit 1");

        jdbc.update("""
                insert into remediation_playbooks (
                    code, name, description, target_environment, pre_approved_alert_types,
                    max_blast_radius, rate_limit_per_hour, require_linked_record, active_version, enabled, is_demo, created_at, updated_at
                ) values (
                    'PB-PAYROLL-POOL-RESET',
                    'Payroll DB Connection Pool Recovery',
                    'Drains leaked idle connections on payroll-db-primary and verifies connection usage drops below threshold.',
                    'PRODUCTION',
                    '["DB_POOL_SATURATION","OBSERVABILITY"]'::jsonb,
                    5, 4, true, 1, true, true, ?, ?
                ) on conflict (code) do nothing
                """, Timestamp.from(now), Timestamp.from(now));

        Long pbId = id("select id from remediation_playbooks where code = 'PB-PAYROLL-POOL-RESET'");
        if (pbId != null && count("select count(*) from remediation_playbook_versions where playbook_id = " + pbId) == 0) {
            String steps = String.format("""
                    [
                      {"stepOrder":1,"name":"Drain idle connections","kind":"ACTION","systemId":%d,"templateId":"drain-idle-connections","parameters":{"maxIdleSeconds":60},"rollbackTemplateId":"restore-pool-defaults","rollbackParameters":{},"timeoutSeconds":30,"maxRetries":1},
                      {"stepOrder":2,"name":"Verify pool saturation cleared","kind":"VERIFY","systemId":%d,"templateId":"verify-pool","parameters":{"metric":"db.client.connections.usage","below":35},"timeoutSeconds":30,"maxRetries":1}
                    ]
                    """, simSysId != null ? simSysId : 1L, simSysId != null ? simSysId : 1L);
            jdbc.update("""
                    insert into remediation_playbook_versions (
                        playbook_id, version, status, trigger_kinds, parameters_schema, steps, recovery_criteria,
                        created_by_id, created_by_name, approved_by_id, approved_by_name, approved_at, created_at
                    ) values (?, 1, 'APPROVED', '["MANUAL","ALERT_RULE","AGENT"]'::jsonb, '{}'::jsonb, ?::jsonb, '{"metric":"db.client.connections.usage","target":35}'::jsonb,
                              ?, 'System Seed', ?, 'System Seed', ?, ?)
                    """, pbId, steps, adminId, adminId, Timestamp.from(now), Timestamp.from(now));
        }
        return 2;
    }

    private int seedSecOps() {
        if (count("select count(*) from security_response_templates where is_demo") > 0) {
            return 0;
        }
        Instant now = clock.instant();
        jdbc.update("""
                insert into security_response_templates (code, name, category, description, tasks, recommended_playbook_code, is_demo)
                values
                  ('PHISHING_RESPONSE', 'Credential Phishing Response', 'PHISHING',
                   'Standard NIST 800-61 playbook for reported phishing and credential harvesting.',
                   '[{"title":"Quarantine phishing email across mailboxes","phase":"CONTAINMENT"},{"title":"Revoke active SSO sessions for targeted user","phase":"CONTAINMENT"},{"title":"Reset credentials & enforce WebAuthn MFA","phase":"ERADICATION"},{"title":"Verify no unauthorized OAuth grants","phase":"RECOVERY"}]'::jsonb,
                   'PB-COMPROMISED-ACCOUNT', true),
                  ('MALWARE_CONTAINMENT', 'Endpoint Malware Containment', 'MALWARE',
                   'Isolate infected host, collect forensic memory dump, and eradicate persistence.',
                   '[{"title":"Isolate host at EDR/switch port","phase":"CONTAINMENT"},{"title":"Capture volatile memory & process tree","phase":"TRIAGE"},{"title":"Remove malicious binaries and scheduled tasks","phase":"ERADICATION"},{"title":"Re-image or verify clean baseline","phase":"RECOVERY"}]'::jsonb,
                   null, true),
                  ('COMPROMISED_ACCOUNT', 'Compromised Identity Containment', 'UNAUTHORISED_ACCESS',
                   'Contain anomalous login activity and rotate user/service credentials.',
                   '[{"title":"Suspend active tokens and sessions","phase":"CONTAINMENT"},{"title":"Audit recent API & privilege actions","phase":"TRIAGE"},{"title":"Rotate secrets and enforce MFA","phase":"ERADICATION"},{"title":"Restore standard access and monitor","phase":"RECOVERY"}]'::jsonb,
                   'PB-COMPROMISED-ACCOUNT', true)
                on conflict (code) do nothing
                """);

        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        String rawToken = "vsrc_" + Hashing.sha256Hex(java.util.Base64.getEncoder().encodeToString(random)).substring(0, 32);
        jdbc.update("""
                insert into vulnerability_sources (name, format, scanner_name, token_hash, token_prefix, enabled, is_demo, created_at)
                values ('Trivy Container Scanner (Demo)', 'TRIVY_JSON', 'Trivy', ?, ?, true, true, ?)
                on conflict (name) do nothing
                """, Hashing.sha256Hex(rawToken), rawToken.substring(0, 10), Timestamp.from(now));
        return 4;
    }

    private int seedFinOps() {
        if (count("select count(*) from finops_cost_sources where is_demo") > 0) {
            return 0;
        }
        Instant now = clock.instant();
        jdbc.update("""
                insert into finops_cost_sources (name, kind, provider_name, column_mapping, status, is_demo, created_at)
                values ('AWS FOCUS Billing Export (Demo)', 'FOCUS_CSV', 'AWS', '{}'::jsonb, 'READY', true, ?)
                on conflict (name) do nothing
                """, Timestamp.from(now));

        Long payrollId = id("select id from business_services where code = 'PAYROLL'");
        Long erpId = id("select id from business_services where code = 'ERP'");

        jdbc.update("""
                insert into finops_allocation_rules (
                    name, priority, tag_key, tag_value, resource_pattern, target_service_id, target_cost_center, split_percent, enabled, is_demo, created_at
                ) values
                  ('Payroll Service Cost Allocation', 10, 'service', 'PAYROLL', 'payroll', ?, 'CC-FIN-100', 100.0, true, true, ?),
                  ('ERP & Analytics Allocation', 20, 'service', 'ERP', 'analytics', ?, 'CC-ENG-200', 100.0, true, true, ?)
                """, payrollId, Timestamp.from(now), erpId, Timestamp.from(now));

        jdbc.update("""
                insert into finops_budgets (
                    name, target_type, target_key, period_month, amount, thresholds, last_notified_threshold, is_demo, created_at
                ) values ('Enterprise Cloud Monthly Budget', 'GLOBAL', 'ALL', ?, ?, '[50, 80, 100]'::jsonb, 0, true, ?)
                """, YearMonth.now().toString(), BigDecimal.valueOf(5000.00), Timestamp.from(now));
        return 4;
    }

    private int seedDex() {
        if (count("select count(*) from dex_proactive_rules where is_demo") > 0) {
            return 0;
        }
        Instant now = clock.instant();
        jdbc.update("""
                insert into dex_proactive_rules (name, department_name, threshold_score, enabled, is_demo, created_at)
                values ('Engineering Fleet Experience Guardrail', 'Engineering', 60.0, true, true, ?)
                """, Timestamp.from(now));
        return 1;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private Long id(String sql, Object... args) {
        List<Long> ids = jdbc.queryForList(sql, Long.class, args);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
