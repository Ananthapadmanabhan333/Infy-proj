package com.nexus360.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.observability.AnomalyService;
import com.nexus360.observability.ObservabilityTestSupport;
import com.nexus360.observability.OtlpPayloads;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Simulation mode: the setting, scenario lifecycle, scenarios generating labelled data through the real pipelines (OTLP ingestion →
 * anomalies → alerts → correlation → incident; simulated deployment → change timeline), simulated data excluded by default, and the
 * purge deleting only simulated rows.
 */
class SimulationIT extends ObservabilityTestSupport {

    @Autowired
    SimulationService simulation;

    @Autowired
    AnomalyService anomalies;

    @AfterEach
    void off() throws Exception {
        mvc.perform(jsonBody(as("admin", put("/api/simulation/mode")), Map.of("enabled", false))).andExpect(status().isOk());
    }

    private void mode(boolean on) throws Exception {
        mvc.perform(jsonBody(as("admin", put("/api/simulation/mode")), Map.of("enabled", on))).andExpect(status().isOk());
    }

    @Test
    void modeAndScenarioLifecycleArePermissionedAndAudited() throws Exception {
        JsonNode status = getJson("rohan.kulkarni", "/api/simulation/status");
        assertThat(status.get("allowed").asBoolean()).isTrue();
        assertThat(status.get("enabled").asBoolean()).isFalse();
        List<String> codes = new java.util.ArrayList<>();
        status.get("scenarios").forEach(s -> codes.add(s.get("code").asText()));
        assertThat(codes).contains("BASELINE", "PAYROLL_DB_SATURATION", "DEPLOY_REGRESSION");

        mvc.perform(as("admin", post("/api/simulation/scenarios/BASELINE/start"))).andExpect(status().isConflict());
        mvc.perform(jsonBody(as("ananya.bose", put("/api/simulation/mode")), Map.of("enabled", true))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("rajesh.iyer", put("/api/simulation/mode")), Map.of("enabled", true))).andExpect(status().isForbidden());
        mode(true);
        assertThat(getJson("rohan.kulkarni", "/api/simulation/status").get("enabled").asBoolean()).isTrue();
        mvc.perform(as("ananya.bose", post("/api/simulation/scenarios/BASELINE/start"))).andExpect(status().isForbidden());
        mvc.perform(as("admin", post("/api/simulation/scenarios/NOPE/start"))).andExpect(status().isNotFound());

        JsonNode run = body(mvc.perform(as("admin", post("/api/simulation/scenarios/BASELINE/start"))).andExpect(status().isOk()).andReturn());
        assertThat(run.get("status").asText()).isEqualTo("RUNNING");
        assertThat(run.get("tickCount").asInt()).isEqualTo(1);
        assertThat(run.get("summary").get("metricPoints").asLong()).isPositive();
        mvc.perform(as("admin", post("/api/simulation/scenarios/BASELINE/start"))).andExpect(status().isConflict());
        testClock().advance(Duration.ofMinutes(1));
        assertThat(simulation.tickDue()).isEqualTo(1);
        assertThat(getJson("ananya.bose", "/api/simulation/runs").get(0).get("tickCount").asInt()).isEqualTo(2);
        // Every generated record is simulated; queries exclude it unless asked.
        assertThat(jdbc.queryForObject("select count(*) from telemetry_resources where service_name = 'erp-finance' and not simulated", Long.class)).isZero();
        assertThat(getJson("riya.varma", "/api/observability/metrics/series?name=process.cpu.utilization").get("status").asText()).isEqualTo("NO_DATA");
        JsonNode sim = getJson("riya.varma", "/api/observability/metrics/series?name=process.cpu.utilization&includeSimulated=true");
        assertThat(sim.get("simulated").asBoolean()).isTrue();
        assertThat(sim.get("series").get(0).get("simulated").asBoolean()).isTrue();

        JsonNode stopped = body(mvc.perform(as("admin", post("/api/simulation/scenarios/BASELINE/stop"))).andExpect(status().isOk()).andReturn());
        assertThat(stopped.get("status").asText()).isEqualTo("STOPPED");
        mvc.perform(as("admin", post("/api/simulation/scenarios/BASELINE/stop"))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action in ('SIMULATION_MODE_ENABLED', 'SIMULATION_SCENARIO_STARTED', "
                + "'SIMULATION_SCENARIO_STOPPED')", Long.class)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void payrollSaturationFlowsThroughAnomaliesAlertsCorrelationAndAnIncident() throws Exception {
        anomalies.runAll();
        JsonNode dashboardBefore = getJson("riya.varma", "/api/analytics/dashboard?days=30");
        JsonNode overviewBefore = getJson("riya.varma", "/api/analytics/overview?days=30");
        JsonNode opsBefore = getJson("riya.varma", "/api/analytics/operations?days=30");
        JsonNode slaBefore = getJson("riya.varma", "/api/sla/overview?days=30");
        String csvBefore = mvc.perform(as("ananya.bose", get("/api/analytics/export?format=csv&days=30")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mode(true);
        mvc.perform(as("admin", post("/api/simulation/scenarios/PAYROLL_DB_SATURATION/start"))).andExpect(status().isOk());
        for (int i = 0; i < 9; i++) {
            testClock().advance(Duration.ofMinutes(1));
            assertThat(simulation.tickNow("PAYROLL_DB_SATURATION")).isTrue();
        }
        anomalies.runAll();
        JsonNode open = getJson("riya.varma", "/api/observability/anomalies?status=OPEN&includeSimulated=true");
        List<String> metrics = new java.util.ArrayList<>();
        open.get("content").forEach(a -> {
            assertThat(a.get("simulated").asBoolean()).isTrue();
            metrics.add(a.get("metricName").asText());
        });
        assertThat(metrics).contains("db.client.connections.usage", "http.server.request.duration");
        assertThat(getJson("riya.varma", "/api/observability/anomalies?status=OPEN").get("totalElements").asLong())
                .as("simulated anomalies are excluded by default").isLessThan(open.get("totalElements").asLong());

        Long dbAlert = jdbc.queryForObject("select a.alert_id from telemetry_anomalies a where a.simulated and a.status = 'OPEN' and a.metric_name = "
                + "'db.client.connections.usage' order by a.id desc limit 1", Long.class);
        Map<String, Object> alert = jdbc.queryForMap("select source_kind, simulated, incident_id from alerts where id = ?", dbAlert);
        assertThat(alert.get("source_kind")).isEqualTo("OBSERVABILITY");
        assertThat(alert.get("simulated")).isEqualTo(true);
        long incident = ((Number) alert.get("incident_id")).longValue();
        assertThat(jdbc.queryForObject("select simulated from incidents where id = ?", Boolean.class, incident)).isTrue();
        JsonNode inc = getJson("riya.varma", "/api/incidents/" + incident);
        assertThat(inc.get("simulated").asBoolean()).isTrue();

        Long group = jdbc.queryForObject("select group_id from correlation_group_members where alert_id = ?", Long.class, dbAlert);
        JsonNode g = getJson("riya.varma", "/api/observability/correlation-groups/" + group);
        assertThat(g.get("simulated").asBoolean()).isTrue();
        assertThat(g.get("memberCount").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(g.get("incidentId").asLong()).isEqualTo(incident);
        assertThat(g.get("rootCiName").asText()).isEqualTo("payroll-db-primary");
        assertThat(getJson("riya.varma", "/api/observability/correlation-groups").toString()).doesNotContain("\"id\":" + group + ",");

        // Additive correlationGroupId on incident detail, alert detail, and alert summary list.
        assertThat(inc.get("correlationGroupId").asLong()).isEqualTo(group);
        JsonNode alertDetail = getJson("riya.varma", "/api/alerts/" + dbAlert);
        assertThat(alertDetail.get("alert").get("simulated").asBoolean()).isTrue();
        assertThat(alertDetail.get("alert").get("correlationGroupId").asLong()).isEqualTo(group);
        JsonNode alertList = getJson("riya.varma", "/api/alerts?incidentId=" + incident);
        assertThat(alertList.get("content").get(0).get("simulated").asBoolean()).isTrue();
        assertThat(alertList.get("content").get(0).get("correlationGroupId").asLong()).isEqualTo(group);

        // Simulated incidents and alerts do not change Release 1–3 aggregates (KPIs, SLA compliance, MTTA/MTTR, alert volume, exports).
        JsonNode dashboardAfter = getJson("riya.varma", "/api/analytics/dashboard?days=30");
        JsonNode overviewAfter = getJson("riya.varma", "/api/analytics/overview?days=30");
        JsonNode opsAfter = getJson("riya.varma", "/api/analytics/operations?days=30");
        JsonNode slaAfter = getJson("riya.varma", "/api/sla/overview?days=30");
        String csvAfter = mvc.perform(as("ananya.bose", get("/api/analytics/export?format=csv&days=30")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(dashboardAfter.get("kpis")).isEqualTo(dashboardBefore.get("kpis"));
        assertThat(overviewAfter.get("kpis")).isEqualTo(overviewBefore.get("kpis"));
        assertThat(slaBefore.has("compliancePercent")).isTrue();
        assertThat(slaAfter.get("compliancePercent")).isEqualTo(slaBefore.get("compliancePercent"));
        assertThat(slaAfter.get("activeCount")).isEqualTo(slaBefore.get("activeCount"));
        assertThat(opsAfter.get("mtta")).isEqualTo(opsBefore.get("mtta"));
        assertThat(overviewAfter.get("kpis").get("mttrMinutes")).isEqualTo(overviewBefore.get("kpis").get("mttrMinutes"));
        assertThat(opsAfter.get("alertVolume").get("total")).isEqualTo(opsBefore.get("alertVolume").get("total"));
        assertThat(csvAfter).doesNotContain(inc.get("number").asText());
        assertThat(csvAfter.lines().count()).isEqualTo(csvBefore.lines().count());

        // When includeSimulated=true is explicitly passed, aggregates include the simulated records.
        JsonNode overviewWithSim = getJson("riya.varma", "/api/analytics/overview?days=30&includeSimulated=true");
        assertThat(overviewWithSim.get("kpis").get("totalIncidents").asLong())
                .isGreaterThan(overviewBefore.get("kpis").get("totalIncidents").asLong());
        JsonNode opsWithSim = getJson("riya.varma", "/api/analytics/operations?days=30&includeSimulated=true");
        assertThat(opsWithSim.get("alertVolume").get("total").asLong())
                .isGreaterThan(opsBefore.get("alertVolume").get("total").asLong());

        // Labelled fault window → detector evaluation finds it.
        long detector = jdbc.queryForObject("select id from anomaly_detectors where name = 'Payroll DB connection pool (simulation)'", Long.class);
        JsonNode eval = body(mvc.perform(jsonBody(as("admin", post("/api/observability/anomaly-detectors/" + detector + "/evaluate")), Map.of("from",
                clock.instant().minus(Duration.ofHours(2)).toString(), "to", clock.instant().plusSeconds(120).toString(), "useAlerts", false)))
                .andExpect(status().isOk()).andReturn());
        assertThat(eval.get("status").asText()).isEqualTo("EVALUATED");
        assertThat(eval.get("labels").get(0).get("source").asText()).isEqualTo("FAULT_WINDOW");
        assertThat(eval.get("recall").asDouble()).isEqualTo(1.0);
        assertThat(eval.get("precision").asDouble()).isEqualTo(1.0);

        // Logs and traces of the scenario exist and are flagged.
        assertThat(getJson("riya.varma", "/api/observability/logs?severity=FATAL&includeSimulated=true").get("content").get(0).get("simulated").asBoolean()).isTrue();
        assertThat(getJson("riya.varma", "/api/observability/traces?error=true&includeSimulated=true").get(0).get("simulated").asBoolean()).isTrue();

        // Purge: only simulated rows are deleted.
        String token = otlpSource("Real exporter " + uid());
        String real = "real-service-" + uid();
        export("metrics", token, OtlpPayloads.metrics(resource(real), List.of(new OtlpPayloads.Gauge("real.metric", null, Map.of(), clock.instant(), 1))));
        long realAlerts = jdbc.queryForObject("select count(*) from alerts where not simulated", Long.class);
        long realIncidents = jdbc.queryForObject("select count(*) from incidents where not simulated", Long.class);
        mvc.perform(as("ananya.bose", delete("/api/simulation/data"))).andExpect(status().isForbidden());
        JsonNode purge = body(mvc.perform(as("admin", delete("/api/simulation/data"))).andExpect(status().isOk()).andReturn());
        assertThat(purge.get("telemetry_resources").asLong()).isPositive();
        assertThat(purge.get("alerts").asLong()).isPositive();
        assertThat(purge.get("incidents").asLong()).isPositive();
        JsonNode counts = getJson("admin", "/api/simulation/status").get("simulatedRecords");
        counts.fields().forEachRemaining(e -> assertThat(e.getValue().asLong()).as(e.getKey()).isZero());
        assertThat(jdbc.queryForObject("select count(*) from telemetry_resources where service_name = ?", Long.class, real)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from alerts where not simulated", Long.class)).isEqualTo(realAlerts);
        assertThat(jdbc.queryForObject("select count(*) from incidents where not simulated", Long.class)).isEqualTo(realIncidents);
        assertThat(jdbc.queryForObject("select count(*) from simulation_runs where status = 'RUNNING'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'SIMULATION_DATA_PURGED'", Long.class)).isPositive();
    }

    @Test
    void deployRegressionRecordsASimulatedDeploymentAndAVersionChange() throws Exception {
        mode(true);
        mvc.perform(as("admin", post("/api/simulation/scenarios/DEPLOY_REGRESSION/start"))).andExpect(status().isOk());
        testClock().advance(Duration.ofMinutes(1));
        simulation.tickNow("DEPLOY_REGRESSION");
        long hr = jdbc.queryForObject("select id from business_services where code = 'HR-PORTAL'", Long.class);
        JsonNode deployments = getJson("anil.kumar", "/api/deployments?serviceId=" + hr);
        assertThat(deployments.toString()).contains("\"simulated\":true");
        JsonNode timeline = getJson("ananya.bose", "/api/twin/changes?serviceId=" + hr + "&includeSimulated=true");
        List<String> kinds = new java.util.ArrayList<>();
        timeline.get("entries").forEach(e -> kinds.add(e.get("kind").asText() + ":" + e.get("simulated").asBoolean()));
        assertThat(kinds).contains("DEPLOYMENT:true", "TELEMETRY:true");
        JsonNode real = getJson("ananya.bose", "/api/twin/changes?serviceId=" + hr);
        real.get("entries").forEach(e -> assertThat(e.get("simulated").asBoolean()).isFalse());
        // Switching the mode off stops the scenario.
        mode(false);
        assertThat(jdbc.queryForObject("select status from simulation_runs where scenario = 'DEPLOY_REGRESSION' order by id desc limit 1", String.class))
                .isEqualTo("STOPPED");
        mvc.perform(as("admin", delete("/api/simulation/data"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from deployments where simulated", Long.class)).isZero();
        mvc.perform(as("rohan.kulkarni", get("/api/simulation/runs"))).andExpect(status().isForbidden());
    }
}
