package com.nexus360.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every Release 3 metric against a hand-built data set: a fresh department (service, team, schedule) with incidents, alerts, pages,
 * changes, problems, assets and SLA clocks in March 2019 — a period no other test writes to — so each expected value is computed by hand
 * in the comments below.
 */
class OperationsMetricsIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private long dept;
    private long service;
    private String query;

    @BeforeEach
    void fixture() {
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        dept = id("insert into departments (code, name) values (?, ?) returning id", "M" + tag, "Metrics dept " + tag);
        service = id("insert into business_services (code, name, department_id, tier) values (?, ?, ?, 2) returning id", "SVC" + tag,
                "Metrics service " + tag, dept);
        long team = id("insert into teams (code, name, department_id) values (?, ?, ?) returning id", "T" + tag, "Metrics team " + tag, dept);
        long riya = jdbc.queryForObject("select id from users where email = ?", Long.class, email("riya.varma"));
        // Incidents (UTC). I1: P1, response 10 min, resolved after 120 min. I2: P2, response 30 min, resolved after 240 min, two assignees.
        // I3: open, no response. I4: cancelled.
        long i1 = incident(tag + "1", "CRITICAL", "RESOLVED", "2019-03-10T10:00:00Z", "2019-03-10T10:10:00Z", "2019-03-10T12:00:00Z", "FIXED");
        long i2 = incident(tag + "2", "HIGH", "RESOLVED", "2019-03-11T10:00:00Z", "2019-03-11T10:30:00Z", "2019-03-11T14:00:00Z", "FIXED");
        long i3 = incident(tag + "3", "MEDIUM", "IN_PROGRESS", "2019-03-12T10:00:00Z", null, null, null);
        incident(tag + "4", "LOW", "CLOSED", "2019-03-13T10:00:00Z", null, "2019-03-13T10:05:00Z", "CANCELLED");
        history(i1, "riya");
        history(i2, "riya");
        history(i2, "dev");
        // SLA clocks: I1 MET, I2 MISSED (breached at 12:00).
        sla(i1, "MET", "2019-03-10T12:00:00Z", null);
        sla(i2, "MISSED", "2019-03-11T14:00:00Z", "2019-03-11T12:00:00Z");
        // Alerts: A1 → I1 fired 10 min before it, A2 → I2 20 min before, A3 → I3 but after it was recorded, A4 suppressed.
        long a1 = alert(tag, 1, "CRITICAL", "PROMETHEUS", "ACKNOWLEDGED", team, i1, 3, "2019-03-10T09:50:00Z", "2019-03-10T10:15:00Z");
        long a2 = alert(tag, 2, "MAJOR", "GRAFANA", "ACKNOWLEDGED", team, i2, 1, "2019-03-11T09:40:00Z", "2019-03-11T09:51:00Z");
        alert(tag, 3, "MAJOR", "MANUAL", "OPEN", team, i3, 2, "2019-03-12T10:30:00Z", null);
        long a4 = alert(tag, 4, "WARNING", "PROMETHEUS", "SUPPRESSED", team, null, 1, "2019-03-15T10:00:00Z", null);
        // On-call pages: A1 level 1 at 09:55 and level 2 at 10:05 (ack 10:15 → 20 min); A2 level 1 at 09:45 (ack 09:51 → 6 min); A4 unanswered.
        long schedule = id("insert into oncall_schedules (team_id, name, time_zone, rotation_type, rotation_start) values (?, ?, 'UTC', 'DAILY', "
                + "'2019-01-01') returning id", team, "Metrics schedule " + tag);
        page(a1, schedule, 1, riya, "2019-03-10T09:55:00Z");
        page(a1, schedule, 2, riya, "2019-03-10T10:05:00Z");
        page(a2, schedule, 1, riya, "2019-03-11T09:45:00Z");
        page(a4, schedule, 1, riya, "2019-03-15T10:05:00Z");
        // Changes of the team: 2 completed, 1 failed, 1 rolled back in March; one completed in April (outside).
        change(tag + "1", "COMPLETED", team, "2019-03-05T10:00:00Z");
        change(tag + "2", "COMPLETED", team, "2019-03-06T10:00:00Z");
        change(tag + "3", "FAILED", team, "2019-03-07T10:00:00Z");
        change(tag + "4", "ROLLED_BACK", team, "2019-03-08T10:00:00Z");
        change(tag + "5", "COMPLETED", team, "2019-04-10T10:00:00Z");
        // Problems: P1 known error since February with I1 and I2 linked; P2 known error without new incidents; P3 known error only in May.
        long p1 = problem(tag + "1", "2019-02-01T00:00:00Z");
        problem(tag + "2", "2019-02-01T00:00:00Z");
        problem(tag + "3", "2019-05-01T00:00:00Z");
        jdbc.update("insert into problem_incidents (problem_id, incident_id) values (?, ?), (?, ?)", p1, i1, p1, i2);
        // Assets: AS1 laptop in use (bought 31 Jan, warranty ends in 10 days), AS2 server in stock (bought 15 Mar), AS3 retired 20 Mar,
        // AS4 retired 1 Mar and disposed 25 Mar, AS5 licence bought 1 Mar.
        asset(tag + "-1", "LAPTOP", "IN_USE", "2019-01-31", LocalDate.now().plusDays(10).toString(), null, null, null);
        asset(tag + "-2", "SERVER", "IN_STOCK", "2019-03-15", null, null, null, null);
        asset(tag + "-3", "LAPTOP", "RETIRED", "2017-01-01", null, null, "2019-03-20T10:00:00Z", null);
        asset(tag + "-4", "DESKTOP", "DISPOSED", null, null, null, "2019-03-01T10:00:00Z", "2019-03-25T10:00:00Z");
        asset(tag + "-5", "SOFTWARE_LICENSE", "IN_USE", "2019-03-01", null, 5, null, null);
        query = "from=2019-03-01&to=2019-03-31&departmentId=" + dept;
    }

    @Test
    void everyOperationalMetricMatchesTheHandComputedValue() throws Exception {
        JsonNode m = getJson("priya.raman", "/api/analytics/operations?" + query);
        assertThat(m.get("from").asText()).isEqualTo("2019-03-01");
        assertThat(m.get("days").asInt()).isEqualTo(31);
        // MTTA = (10 + 30) / 2 incidents with a first response.
        assertThat(m.at("/mtta/minutes").asDouble()).isEqualTo(20.0);
        assertThat(m.at("/mtta/samples").asLong()).isEqualTo(2);
        // MTTD = (10 + 20) / 2; I3's alert fired after the incident; I4 is cancelled.
        assertThat(m.at("/mttd/minutes").asDouble()).isEqualTo(15.0);
        assertThat(m.at("/mttd/samples").asLong()).isEqualTo(2);
        assertThat(m.at("/mttd/incidentsWithoutAlert").asLong()).isEqualTo(1);
        // FCR: I1 (one assignee) of I1, I2 resolved.
        assertThat(m.at("/firstContactResolution/rate").asDouble()).isEqualTo(50.0);
        assertThat(m.at("/firstContactResolution/resolved").asLong()).isEqualTo(2);
        assertThat(m.at("/firstContactResolution/resolvedAtFirstContact").asLong()).isEqualTo(1);
        // Changes: 2 completed / 4 finished.
        assertThat(m.at("/changes/finished").asLong()).isEqualTo(4);
        assertThat(m.at("/changes/completed").asLong()).isEqualTo(2);
        assertThat(m.at("/changes/failed").asLong()).isEqualTo(1);
        assertThat(m.at("/changes/rolledBack").asLong()).isEqualTo(1);
        assertThat(m.at("/changes/successRate").asDouble()).isEqualTo(50.0);
        assertThat(m.at("/changes/failureRate").asDouble()).isEqualTo(50.0);
        // Problem recurrence: P1 recurred (I1, I2) of P1, P2 known errors.
        assertThat(m.at("/problemRecurrence/knownErrors").asLong()).isEqualTo(2);
        assertThat(m.at("/problemRecurrence/problemsWithRecurrence").asLong()).isEqualTo(1);
        assertThat(m.at("/problemRecurrence/recurringIncidents").asLong()).isEqualTo(2);
        assertThat(m.at("/problemRecurrence/rate").asDouble()).isEqualTo(50.0);
        // Alert volume: 4 alerts, 3 + 1 + 2 + 1 occurrences.
        assertThat(m.at("/alertVolume/total").asLong()).isEqualTo(4);
        assertThat(m.at("/alertVolume/occurrences").asLong()).isEqualTo(7);
        assertThat(m.at("/alertVolume/bySeverity/CRITICAL").asLong()).isEqualTo(1);
        assertThat(m.at("/alertVolume/bySeverity/MAJOR").asLong()).isEqualTo(2);
        assertThat(m.at("/alertVolume/bySeverity/WARNING").asLong()).isEqualTo(1);
        assertThat(m.at("/alertVolume/bySeverity/INFO").asLong()).isZero();
        assertThat(m.at("/alertVolume/bySource/PROMETHEUS").asLong()).isEqualTo(2);
        assertThat(m.at("/alertVolume/bySource/GRAFANA").asLong()).isEqualTo(1);
        assertThat(m.at("/alertVolume/bySource/MANUAL").asLong()).isEqualTo(1);
        assertThat(m.at("/alertVolume/suppressed").asLong()).isEqualTo(1);
        assertThat(m.at("/alertVolume/withIncident").asLong()).isEqualTo(3);
        assertThat(m.at("/alertVolume/perDay")).hasSize(31);
        long perDayTotal = 0;
        for (JsonNode d : m.at("/alertVolume/perDay")) {
            perDayTotal += d.get("count").asLong();
        }
        assertThat(perDayTotal).isEqualTo(4);
        // Assets: acquired AS2 + AS5; age of active hardware = (59 + 16) / 2.
        assertThat(m.at("/assetLifecycle/byStatus/IN_USE").asLong()).isEqualTo(2);
        assertThat(m.at("/assetLifecycle/byStatus/IN_STOCK").asLong()).isEqualTo(1);
        assertThat(m.at("/assetLifecycle/byStatus/IN_REPAIR").asLong()).isZero();
        assertThat(m.at("/assetLifecycle/byStatus/RETIRED").asLong()).isEqualTo(1);
        assertThat(m.at("/assetLifecycle/byStatus/DISPOSED").asLong()).isEqualTo(1);
        assertThat(m.at("/assetLifecycle/acquired").asLong()).isEqualTo(2);
        assertThat(m.at("/assetLifecycle/retired").asLong()).isEqualTo(2); // AS3, and AS4 (retired 1 Mar before its disposal)
        assertThat(m.at("/assetLifecycle/disposed").asLong()).isEqualTo(1);
        assertThat(m.at("/assetLifecycle/averageAgeDays").asDouble()).isEqualTo(37.5);
        assertThat(m.at("/assetLifecycle/warrantyExpiringNext30Days").asLong()).isEqualTo(1);
        // On-call: 3 alerts paged with 4 pages, 2 acknowledged after (20 + 6) / 2 minutes, A1 reached level 2.
        assertThat(m.at("/onCall/alertsPaged").asLong()).isEqualTo(3);
        assertThat(m.at("/onCall/pages").asLong()).isEqualTo(4);
        assertThat(m.at("/onCall/acknowledged").asLong()).isEqualTo(2);
        assertThat(m.at("/onCall/ackRate").asDouble()).isEqualTo(66.7);
        assertThat(m.at("/onCall/avgAckMinutes").asDouble()).isEqualTo(13.0);
        assertThat(m.at("/onCall/escalated").asLong()).isEqualTo(1);
        assertThat(m.at("/onCall/bySchedule")).hasSize(1);
        // Department performance: one row for the department.
        JsonNode d = m.at("/departmentPerformance/0");
        assertThat(m.get("departmentPerformance")).hasSize(1);
        assertThat(d.get("departmentId").asLong()).isEqualTo(dept);
        assertThat(d.get("created").asLong()).isEqualTo(4);
        assertThat(d.get("resolved").asLong()).isEqualTo(2);
        assertThat(d.get("open").asLong()).isEqualTo(1);
        assertThat(d.get("mttaMinutes").asDouble()).isEqualTo(20.0);
        assertThat(d.get("mttrMinutes").asDouble()).isEqualTo(180.0);
        assertThat(d.get("slaCompliance").asDouble()).isEqualTo(50.0);
        assertThat(d.get("firstContactResolution").asDouble()).isEqualTo(50.0);
        // Availability: 120 min P1 outage + 25 % of 240 min P2 = 180 min over the 31-day window.
        ZoneId zone = ZoneId.of(jdbc.queryForObject("select timezone from business_hours_calendars where is_default limit 1", String.class));
        double window = java.time.Duration.between(LocalDate.of(2019, 3, 1).atStartOfDay(zone), LocalDate.of(2019, 4, 1).atStartOfDay(zone)).getSeconds();
        double expected = Math.round((100 - (120 * 60 + 0.25 * 240 * 60) * 100 / window) * 100) / 100.0;
        JsonNode svc = m.at("/serviceAvailability/0");
        assertThat(svc.get("serviceId").asLong()).isEqualTo(service);
        assertThat(svc.get("availability").asDouble()).isEqualTo(expected).isEqualTo(99.6);
        assertThat(svc.get("outageMinutes").asDouble()).isEqualTo(120.0);
        assertThat(svc.get("degradedMinutes").asDouble()).isEqualTo(240.0);
        assertThat(svc.get("incidents").asInt()).isEqualTo(2);

        // Employees cannot read operations metrics.
        mvc.perform(as("kavya.reddy", get("/api/analytics/operations?" + query))).andExpect(status().isForbidden());
    }

    @Test
    void commandCenterFieldsComeFromTheDatabase() throws Exception {
        JsonNode cc = getJson("sunita.joshi", "/api/analytics/dashboard?" + query).get("commandCenter");
        assertThat(cc.at("/incidents/createdInPeriod").asLong()).isEqualTo(4);
        assertThat(cc.at("/incidents/resolvedInPeriod").asLong()).isEqualTo(2);
        assertThat(cc.at("/incidents/open").asLong()).isEqualTo(1);
        assertThat(cc.at("/incidents/criticalOpen").asLong()).isZero();
        assertThat(cc.at("/incidents/slaBreachesInPeriod").asLong()).isEqualTo(1);
        assertThat(cc.at("/incidents/mttaMinutes").asDouble()).isEqualTo(20.0);
        assertThat(cc.at("/alerts/active").asLong()).isEqualTo(3);
        assertThat(cc.at("/alerts/open").asLong()).isEqualTo(1);
        assertThat(cc.at("/alerts/acknowledged").asLong()).isEqualTo(2);
        assertThat(cc.at("/alerts/critical").asLong()).isEqualTo(1);
        assertThat(cc.at("/changes/completedInPeriod").asLong()).isEqualTo(2);
        assertThat(cc.at("/changes/failedInPeriod").asLong()).isEqualTo(2);
        assertThat(cc.at("/departmentPerformance/0/departmentId").asLong()).isEqualTo(dept);
        assertThat(cc.get("onCallNow").isArray()).isTrue();
        assertThat(cc.get("recentActivity").isArray()).isTrue();
        assertThat(cc.at("/workflows/executions24h").asLong()).isEqualTo(
                jdbc.queryForObject("select count(*) from workflow_executions where started_at >= now() - interval '24 hours'", Long.class));

        // Organisation-wide: live figures match the tables; demo on-call schedules show who is on call; activity is newest first.
        JsonNode org = getJson("sunita.joshi", "/api/analytics/dashboard?days=7").get("commandCenter");
        assertThat(org.at("/alerts/active").asLong()).isEqualTo(jdbc.queryForObject("select count(*) from alerts where status in ('OPEN', 'ACKNOWLEDGED')",
                Long.class));
        assertThat(org.at("/incidents/open").asLong()).isEqualTo(jdbc.queryForObject("select count(*) from incidents where parent_id is null and status in "
                + "('NEW', 'ASSIGNED', 'IN_PROGRESS', 'PENDING', 'REOPENED')", Long.class));
        assertThat(org.get("onCallNow").size()).isGreaterThanOrEqualTo(7);
        assertThat(org.at("/onCallNow/0/primary/name").asText()).isNotBlank();
        assertThat(org.get("activeMajorIncidents").isArray()).isTrue();
        List<Instant> times = new ArrayList<>();
        org.get("recentActivity").forEach(a -> times.add(Instant.parse(a.get("at").asText())));
        assertThat(times).isNotEmpty().isSortedAccordingTo(java.util.Comparator.reverseOrder());

        // Employees get the self-service dashboard without the Command Center block.
        assertThat(getJson("kavya.reddy", "/api/analytics/dashboard?days=7").get("commandCenter").isNull()).isTrue();
    }

    @Test
    void definitionsDocumentEveryMetric() throws Exception {
        JsonNode defs = getJson("kavya.reddy", "/api/analytics/definitions");
        List<String> keys = new ArrayList<>();
        defs.forEach(x -> keys.add(x.get("key").asText()));
        assertThat(keys).contains("mtta", "mttd", "firstContactResolution", "changes.successRate", "changes.failureRate", "problemRecurrence",
                "alertVolume", "assetLifecycle", "onCall", "departmentPerformance", "serviceAvailability");
        defs.forEach(x -> assertThat(x.get("formula").asText()).isNotBlank());
    }

    // ------------------------------------------------------------------ fixtures

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static Timestamp ts(String iso) {
        return iso == null ? null : Timestamp.from(Instant.parse(iso));
    }

    private long incident(String suffix, String priority, String status, String created, String response, String resolved, String code) {
        return id("insert into incidents (number, title, category, impact, urgency, priority, status, service_id, department_id, created_at, updated_at, "
                        + "first_response_at, resolved_at, resolution_code) values (?, ?, 'APPLICATION', 'HIGH', 'HIGH', ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id",
                "INC-M" + suffix, "Metrics incident " + suffix, priority, status, service, dept, ts(created), ts(created), ts(response), ts(resolved), code);
    }

    private void history(long incident, String assignee) {
        jdbc.update("insert into incident_history (incident_id, actor_name, event_type, field, new_value, created_at) values (?, 'test', 'ASSIGNED', "
                + "'assignee', ?, ?)", incident, assignee, ts("2019-03-10T10:01:00Z"));
    }

    private void sla(long incident, String outcome, String completed, String breached) {
        long policy = jdbc.queryForObject("select id from sla_policies order by id limit 1", Long.class);
        jdbc.update("insert into incident_slas (incident_id, policy_id, policy_name, priority, business_hours_only, response_target_minutes, "
                + "resolution_target_minutes, at_risk_percent, started_at, response_due_at, clock_status, health, outcome, completed_at, breached_at) "
                + "values (?, ?, 'Test', 'HIGH', false, 30, 120, 80, ?, ?, 'STOPPED', ?, ?, ?, ?)", incident, policy, ts(completed), ts(completed),
                breached == null ? "ON_TRACK" : "BREACHED", outcome, ts(completed), ts(breached));
    }

    private long alert(String tag, int n, String severity, String source, String status, long team, Long incident, int occurrences, String first,
                       String acknowledged) {
        return id("insert into alerts (number, source_kind, scope_key, fingerprint, title, severity, status, service_id, team_id, incident_id, "
                        + "occurrence_count, first_seen_at, last_seen_at, acknowledged_at) values (?, ?, 'manual', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id",
                "ALR-M" + tag + n, source, "fp-" + tag + n, "Metrics alert " + n, severity, status, service, team, incident, occurrences, ts(first), ts(first),
                ts(acknowledged));
    }

    private void page(long alert, long schedule, int level, long user, String at) {
        jdbc.update("insert into oncall_pages (alert_id, schedule_id, level, target, user_id, paged_at) values (?, ?, ?, ?, ?, ?)", alert, schedule, level,
                level == 1 ? "PRIMARY" : "SECONDARY", user, ts(at));
    }

    private void change(String suffix, String status, long team, String closed) {
        jdbc.update("insert into changes (number, title, change_type, status, team_id, closed_at) values (?, ?, 'NORMAL', ?, ?, ?)", "CHG-M" + suffix,
                "Metrics change " + suffix, status, team, ts(closed));
    }

    private long problem(String suffix, String knownError) {
        return id("insert into problems (number, title, status, department_id, known_error, known_error_at) values (?, ?, 'KNOWN_ERROR', ?, true, ?) "
                + "returning id", "PRB-M" + suffix, "Metrics problem " + suffix, dept, ts(knownError));
    }

    private void asset(String suffix, String type, String status, String purchased, String warranty, Integer seats, String retired, String disposed) {
        jdbc.update("insert into assets (asset_tag, name, asset_type, status, department_id, purchase_date, warranty_expires, license_seats, retired_at, "
                + "disposed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "M" + suffix, "Metrics asset " + suffix, type, status, dept,
                purchased == null ? null : java.sql.Date.valueOf(purchased), warranty == null ? null : java.sql.Date.valueOf(warranty), seats, ts(retired),
                ts(disposed));
    }
}
