package com.nexus360.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/** Scheduled reports (docs/RELEASE3_SPEC.md §4): schedules, permissions, the advisory-locked scheduler, stored runs, downloads. */
class ScheduledReportsIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    ReportScheduleService service;

    private Map<String, Object> schedule(String name, String type, String format, String frequency) {
        Map<String, Object> s = new HashMap<>(Map.of("name", name, "reportType", type, "format", format, "frequency", frequency,
                "recipients", List.of(email("priya.raman"))));
        s.put("runHour", 6);
        return s;
    }

    private long create(String user, Map<String, Object> body) throws Exception {
        return body(mvc.perform(jsonBody(as(user, post("/api/reports/schedules")), body)).andExpect(status().isCreated()).andReturn())
                .get("id").asLong();
    }

    @Test
    void reportTypesListTheRegisteredGenerators() throws Exception {
        JsonNode types = getJson("priya.raman", "/api/reports/types");
        assertThat(types).hasSize(7);
        Map<String, Boolean> available = new HashMap<>();
        types.forEach(t -> available.put(t.get("type").asText(), t.get("available").asBoolean()));
        assertThat(available).containsEntry("ANALYTICS_OVERVIEW", true).containsEntry("SLA", true).containsEntry("INCIDENTS", true)
                // CHANGES and PROBLEMS are registered by the change and problem modules (Release 3, BE-2).
                .containsEntry("CHANGES", true).containsEntry("PROBLEMS", true)
                // ASSETS is registered by the asset module (Release 3, BE-3), ONCALL by the on-call module (BE-4): every type is available.
                .containsEntry("ASSETS", true).containsEntry("ONCALL", true);
    }

    @Test
    void schedulesAreValidatedAndOwnedWithRoleChecks() throws Exception {
        mvc.perform(jsonBody(as("riya.varma", post("/api/reports/schedules")), schedule("Agent report", "SLA", "PDF", "DAILY")))
                .andExpect(status().isForbidden());
        // ONCALL is available since BE-4: it can be scheduled and run (PDF and CSV).
        long oncall = create("priya.raman", schedule("On-call", "ONCALL", "PDF", "DAILY"));
        JsonNode oncallRun = body(mvc.perform(as("priya.raman", post("/api/reports/schedules/" + oncall + "/run"))).andExpect(status().isOk()).andReturn());
        assertThat(oncallRun.get("status").asText()).isEqualTo("SUCCEEDED");
        long oncallCsv = create("priya.raman", schedule("On-call CSV", "ONCALL", "CSV", "WEEKLY"));
        assertThat(body(mvc.perform(as("priya.raman", post("/api/reports/schedules/" + oncallCsv + "/run"))).andExpect(status().isOk()).andReturn())
                .get("status").asText()).isEqualTo("SUCCEEDED");
        Map<String, Object> outsider = schedule("Outsider", "SLA", "PDF", "DAILY");
        outsider.put("recipients", List.of("someone@example.org"));
        mvc.perform(jsonBody(as("priya.raman", post("/api/reports/schedules")), outsider)).andExpect(status().isBadRequest());
        Map<String, Object> employeeRecipient = schedule("Employee", "SLA", "PDF", "DAILY");
        employeeRecipient.put("recipients", List.of(email("kavya.reddy")));
        mvc.perform(jsonBody(as("priya.raman", post("/api/reports/schedules")), employeeRecipient)).andExpect(status().isBadRequest());
        Map<String, Object> badFilter = schedule("Filter", "SLA", "PDF", "DAILY");
        badFilter.put("filters", Map.of("colour", "red"));
        mvc.perform(jsonBody(as("priya.raman", post("/api/reports/schedules")), badFilter)).andExpect(status().isBadRequest());

        Map<String, Object> weekly = schedule("Weekly incidents for Apps", "INCIDENTS", "CSV", "WEEKLY");
        weekly.put("dayOfWeek", 3);
        weekly.put("runHour", 7);
        weekly.put("filters", Map.of("departmentId", jdbc.queryForObject("select id from departments where code = 'IT-APPS'", Long.class)));
        long id = create("priya.raman", weekly);
        JsonNode dto = getJson("priya.raman", "/api/reports/schedules/" + id);
        assertThat(dto.get("ownerName").asText()).isEqualTo("Priya Raman");
        assertThat(dto.get("dayOfWeek").asInt()).isEqualTo(3);
        assertThat(dto.get("recipients").get(0).asText()).isEqualTo(email("priya.raman"));
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        Instant next = Instant.parse(dto.get("nextRunAt").asText());
        assertThat(next.atZone(zone).getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(next.atZone(zone).getHour()).isEqualTo(7);
        assertThat(next).isAfter(clock.instant());

        // Exporters see every schedule; the owner or an administrator changes it; others get 404.
        assertThat(getJson("ananya.bose", "/api/reports/schedules").findValuesAsText("id")).contains(String.valueOf(id));
        mvc.perform(as("riya.varma", get("/api/reports/schedules/" + id))).andExpect(status().isNotFound());
        assertThat(getJson("riya.varma", "/api/reports/schedules").findValuesAsText("id")).doesNotContain(String.valueOf(id));
        mvc.perform(jsonBody(as("sunita.joshi", put("/api/reports/schedules/" + id)), weekly)).andExpect(status().isForbidden());
        weekly.put("name", "Weekly incidents (renamed)");
        mvc.perform(jsonBody(as("rajesh.iyer", put("/api/reports/schedules/" + id)), weekly)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Weekly incidents (renamed)"));
        mvc.perform(as("kavya.reddy", post("/api/reports/schedules/" + id + "/run"))).andExpect(status().isNotFound());
        mvc.perform(as("priya.raman", delete("/api/reports/schedules/" + id))).andExpect(status().isNoContent());
        mvc.perform(as("priya.raman", get("/api/reports/schedules/" + id))).andExpect(status().isNotFound());
    }

    @Test
    void dueSchedulesRunOncePerPeriodUnderTheSchedulerLock() throws Exception {
        long csv = create("priya.raman", schedule("Daily SLA log", "SLA", "CSV", "DAILY"));
        long pdf = create("sunita.joshi", schedule("Daily overview", "ANALYTICS_OVERVIEW", "PDF", "DAILY"));
        jdbc.update("update report_schedules set next_run_at = now() - interval '5 minutes' where id in (?, ?)", csv, pdf);

        // Another instance holds the scheduler lock: nothing runs here.
        try (Connection other = dataSource.getConnection()) {
            try (PreparedStatement lock = other.prepareStatement("select pg_advisory_lock(?)")) {
                lock.setLong(1, ReportScheduleService.LOCK_KEY);
                lock.execute();
            }
            assertThat(service.runDue()).isZero();
            assertThat(jdbc.queryForObject("select count(*) from report_runs where schedule_id in (?, ?)", Long.class, csv, pdf)).isZero();
            try (PreparedStatement unlock = other.prepareStatement("select pg_advisory_unlock(?)")) {
                unlock.setLong(1, ReportScheduleService.LOCK_KEY);
                unlock.execute();
            }
        }
        assertThat(service.runDue()).isGreaterThanOrEqualTo(2);
        assertThat(service.runDue()).isZero(); // next_run_at moved to the next period
        for (long id : new long[]{csv, pdf}) {
            assertThat(jdbc.queryForObject("select count(*) from report_runs where schedule_id = ? and trigger_type = 'SCHEDULE'", Long.class, id))
                    .isEqualTo(1);
            Instant next = jdbc.queryForObject("select next_run_at from report_schedules where id = ?", java.sql.Timestamp.class, id).toInstant();
            assertThat(next).isAfter(Instant.now());
        }

        JsonNode runs = getJson("priya.raman", "/api/reports/schedules/" + csv + "/runs");
        assertThat(runs).hasSize(1);
        JsonNode run = runs.get(0);
        assertThat(run.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.get("trigger").asText()).isEqualTo("SCHEDULE");
        assertThat(run.get("contentType").asText()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(run.get("fileName").asText()).startsWith("sa-sla-").endsWith(".csv");
        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1);
        assertThat(run.get("periodFrom").asText()).isEqualTo(run.get("periodTo").asText());
        assertThat(LocalDate.parse(run.get("periodTo").asText())).isBetween(yesterday.minusDays(1), yesterday);
        assertThat(getJson("priya.raman", "/api/reports/schedules/" + csv).at("/lastRun/id").asLong()).isEqualTo(run.get("id").asLong());

        MvcResult file = mvc.perform(as("priya.raman", get("/api/reports/runs/" + run.get("id").asLong() + "/download")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".csv"))).andReturn();
        String text = new String(file.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("number,title,priority,policy,outcome,response_breached");

        long pdfRun = getJson("sunita.joshi", "/api/reports/schedules/" + pdf + "/runs").get(0).get("id").asLong();
        MvcResult document = mvc.perform(as("ananya.bose", get("/api/reports/runs/" + pdfRun + "/download")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf")).andReturn();
        assertThat(new String(document.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        // Neither owner nor exporter: no access.
        mvc.perform(as("riya.varma", get("/api/reports/runs/" + pdfRun + "/download"))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'REPORT_DOWNLOADED' and entity_id in (?, ?)", Long.class,
                String.valueOf(run.get("id").asLong()), String.valueOf(pdfRun))).isEqualTo(2);
        // The owner is told; recipients' e-mails are recorded (SKIPPED without SMTP), never claimed as sent.
        assertThat(jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id where u.email = ? "
                + "and n.type = 'REPORT_READY'", Long.class, email("sunita.joshi"))).isPositive();
        assertThat(run.get("emailedTo").asInt()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where category = 'REPORT' and status = 'SKIPPED' and to_address = ?",
                Long.class, email("priya.raman"))).isPositive();
    }

    @Test
    void manualRunsCoverEveryCoreTypeAndFormatAndFormulaInjectionIsNeutralised() throws Exception {
        String evil = "=HYPERLINK(\"http://evil.example\")";
        long incident = createIncident("amit.shah", Map.of("title", evil + " report cell", "description", "Formula injection check"))
                .at("/incident/id").asLong();
        assertThat(incident).isPositive();
        for (String type : List.of("ANALYTICS_OVERVIEW", "SLA", "INCIDENTS")) {
            for (String format : List.of("PDF", "CSV")) {
                long id = create("priya.raman", schedule("Manual " + type + " " + format, type, format, "MONTHLY"));
                JsonNode run = body(mvc.perform(as("priya.raman", post("/api/reports/schedules/" + id + "/run"))).andExpect(status().isOk()).andReturn());
                assertThat(run.get("status").asText()).as(type + " " + format).isEqualTo("SUCCEEDED");
                assertThat(run.get("trigger").asText()).isEqualTo("MANUAL");
                assertThat(LocalDate.parse(run.get("periodFrom").asText()).getDayOfMonth()).isEqualTo(1);
                assertThat(run.get("sizeBytes").asLong()).isPositive();
                MvcResult file = mvc.perform(as("priya.raman", get("/api/reports/runs/" + run.get("id").asLong() + "/download")))
                        .andExpect(status().isOk()).andReturn();
                byte[] bytes = file.getResponse().getContentAsByteArray();
                if ("PDF".equals(format)) {
                    assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
                } else {
                    assertThat(file.getResponse().getContentType()).startsWith("text/csv");
                }
            }
        }
        // The current month's incident log contains the formula-like title only as quoted text.
        long current = create("priya.raman", schedule("Daily incidents", "INCIDENTS", "CSV", "DAILY"));
        jdbc.update("update incidents set created_at = now() - interval '1 day' where id = ?", incident);
        JsonNode run = body(mvc.perform(as("priya.raman", post("/api/reports/schedules/" + current + "/run"))).andExpect(status().isOk()).andReturn());
        String csv = new String(mvc.perform(as("priya.raman", get("/api/reports/runs/" + run.get("id").asLong() + "/download")))
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'=HYPERLINK(\"\"http://evil.example\"\") report cell");
        assertThat(csv).doesNotContain(",=HYPERLINK");

        // A generator failure is recorded on the run, not thrown at the caller.
        Map<String, Object> missingDept = schedule("Broken filter", "SLA", "PDF", "DAILY");
        missingDept.put("filters", Map.of("departmentId", 987654321));
        long broken = create("priya.raman", missingDept);
        JsonNode failed = body(mvc.perform(as("priya.raman", post("/api/reports/schedules/" + broken + "/run"))).andExpect(status().isOk()).andReturn());
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("error").asText()).contains("Department");
        mvc.perform(as("priya.raman", get("/api/reports/runs/" + failed.get("id").asLong() + "/download"))).andExpect(status().isConflict());
    }

    @Test
    void nextRunAndPeriodsFollowTheFrequency() {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        Instant base = LocalDate.of(2026, 11, 4).atTime(10, 0).atZone(zone).toInstant(); // a Wednesday
        assertThat(ReportScheduleService.nextRun(ReportSchedule.Frequency.DAILY, 6, null, null, base, zone))
                .isEqualTo(LocalDate.of(2026, 11, 5).atTime(6, 0).atZone(zone).toInstant());
        assertThat(ReportScheduleService.nextRun(ReportSchedule.Frequency.DAILY, 11, null, null, base, zone))
                .isEqualTo(LocalDate.of(2026, 11, 4).atTime(11, 0).atZone(zone).toInstant());
        assertThat(ReportScheduleService.nextRun(ReportSchedule.Frequency.WEEKLY, 7, (short) 1, null, base, zone))
                .isEqualTo(LocalDate.of(2026, 11, 9).atTime(7, 0).atZone(zone).toInstant());
        assertThat(ReportScheduleService.nextRun(ReportSchedule.Frequency.MONTHLY, 6, null, (short) 1, base, zone))
                .isEqualTo(LocalDate.of(2026, 12, 1).atTime(6, 0).atZone(zone).toInstant());
        assertThat(ReportScheduleService.period(ReportSchedule.Frequency.DAILY, LocalDate.of(2026, 11, 4)))
                .containsExactly(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 3));
        assertThat(ReportScheduleService.period(ReportSchedule.Frequency.WEEKLY, LocalDate.of(2026, 11, 4)))
                .containsExactly(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 3));
        assertThat(ReportScheduleService.period(ReportSchedule.Frequency.MONTHLY, LocalDate.of(2026, 11, 4)))
                .containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
    }
}
