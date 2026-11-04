package com.nexus360.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.observability.ObservabilityTestSupport;
import com.nexus360.observability.OtlpPayloads;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for SA Predictive Intelligence (Release 4 §4.3): minimum sample gates, time-based split training and
 * offline metrics, single ACTIVE model per type, Release 3 statistical fallback when no ACTIVE model exists, served
 * prediction persistence and "how this was computed", simulated exclusion/inclusion, outcome reconciliation, drift
 * warnings, service health and capacity risk forecasts, and RBAC.
 */
class PredictionIT extends ObservabilityTestSupport {

    private final List<Long> createdIncidentIds = new ArrayList<>();
    private final List<Long> createdModelIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from predictions where model_id in (select id from prediction_models where status = 'ACTIVE')");
        jdbc.update("update prediction_models set status = 'RETIRED' where status = 'ACTIVE'");
        jdbc.update("delete from predictions where subject_type = 'METRIC_SERIES'");
        jdbc.update("delete from telemetry_metric_points where series_id in (select id from telemetry_metric_series where name = 'system.cpu.utilization' and attributes = '{\"cpu\":\"total\"}'::jsonb)");
        jdbc.update("delete from telemetry_metric_rollups where series_id in (select id from telemetry_metric_series where name = 'system.cpu.utilization' and attributes = '{\"cpu\":\"total\"}'::jsonb)");
        jdbc.update("delete from telemetry_metric_series where name = 'system.cpu.utilization' and attributes = '{\"cpu\":\"total\"}'::jsonb");
        if (!createdIncidentIds.isEmpty()) {
            for (Long id : createdIncidentIds) {
                jdbc.update("delete from predictions where subject_type = 'INCIDENT' and subject_id = ?", id);
                jdbc.update("delete from incident_slas where incident_id = ?", id);
                jdbc.update("delete from incidents where id = ?", id);
            }
            createdIncidentIds.clear();
        }
        if (!createdModelIds.isEmpty()) {
            for (Long id : createdModelIds) {
                jdbc.update("delete from predictions where model_id = ?", id);
                jdbc.update("delete from prediction_jobs where model_id = ?", id);
                jdbc.update("delete from prediction_models where id = ?", id);
            }
            createdModelIds.clear();
        }
    }

    @Test
    void rbacAndMinimumSampleSizeGateAndStatisticalFallback() throws Exception {
        // RBAC: readers (incl. auditor, security analyst, finops analyst) can read; employee cannot; only prediction admins can write.
        assertThat(getJson("ananya.bose", "/api/predictions/meta").get("minSamples").asInt()).isEqualTo(200);
        assertThat(getJson("vikram.sec", "/api/predictions/models").isArray()).isTrue();
        assertThat(getJson("neha.finops", "/api/predictions/jobs").isArray()).isTrue();
        mvc.perform(as("rohan.kulkarni", get("/api/predictions/meta"))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("rohan.kulkarni", post("/api/predictions/models/train")), Map.of("type", "SLA_BREACH")))
                .andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("ananya.bose", post("/api/predictions/models/train")), Map.of("type", "SLA_BREACH")))
                .andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("riya.varma", post("/api/predictions/models/train")), Map.of("type", "SLA_BREACH")))
                .andExpect(status().isForbidden());
        mvc.perform(as("rohan.kulkarni", post("/api/predictions/outcomes/reconcile"))).andExpect(status().isForbidden());

        // Minimum sample size gate -> INSUFFICIENT_DATA on an empty historical window.
        Instant now = clock.instant();
        JsonNode insufficient = body(mvc.perform(jsonBody(as("admin", post("/api/predictions/models/train")), Map.of(
                "type", "SLA_BREACH",
                "from", now.minus(Duration.ofDays(900)).toString(),
                "to", now.minus(Duration.ofDays(890)).toString())))
                .andExpect(status().isCreated()).andReturn());
        createdModelIds.add(insufficient.get("id").asLong());
        assertThat(insufficient.get("status").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(insufficient.get("statusMessage").asText()).startsWith("Insufficient data —");
        // Cannot activate an INSUFFICIENT_DATA model.
        mvc.perform(as("admin", post("/api/predictions/models/" + insufficient.get("id").asLong() + "/activate")))
                .andExpect(status().isConflict());

        // Without an ACTIVE SLA_BREACH model, /api/predictions/incidents/{id} returns the labelled Release 3 statistical fallback.
        long openIncident = jdbc.queryForObject("""
                select i.id from incidents i
                join incident_slas s on s.incident_id = i.id
                where i.parent_id is null and s.outcome is null and not i.simulated
                order by i.id desc limit 1
                """, Long.class);
        JsonNode pred = getJson("ananya.bose", "/api/predictions/incidents/" + openIncident);
        JsonNode slaPred = pred.get("slaBreach");
        assertThat(slaPred.get("status").asText()).isEqualTo("NO_ACTIVE_MODEL");
        assertThat(slaPred.get("basis").asText()).isEqualTo("STATISTICAL_FALLBACK");
        assertThat(slaPred.get("modelId").isNull()).isTrue();
        assertThat(slaPred.get("fallback").isNull()).isFalse();
        assertThat(slaPred.get("method").asText()).contains("Release 3 statistic");
    }

    @Test
    void slaBreachAndRecurrenceTrainingActivationServingOutcomeReconciliationAndDrift() throws Exception {
        Long serviceId = jdbc.queryForObject("select id from business_services where code = 'PAYROLL'", Long.class);
        List<Long> allServices = jdbc.queryForList("select id from business_services order by id", Long.class);
        String[] negCategories = {"APPLICATION", "NETWORK", "HARDWARE", "ACCESS", "EMAIL", "SECURITY", "FACILITIES"};
        Long deptId = jdbc.queryForObject("select department_id from business_services where id = ?", Long.class, serviceId);
        Long policyId = jdbc.queryForObject("select id from sla_policies order by id limit 1", Long.class);
        Instant base = clock.instant().minus(Duration.ofDays(400));

        // Construct 220 deterministic historical incidents in [base, base + 110d] so they do not touch the 30d demo window.
        // Critical DATABASE incidents on PAYROLL breach and recur within 36h; Medium incidents cycle across (service, category)
        // pairs so the same pair only repeats after > 40 days (> 14d recurrence window) and therefore never recurs.
        int negIdx = 0;
        for (int i = 0; i < 220; i++) {
            boolean positive = (i % 3 == 0); // ~74 positives, ~146 negatives (chronological across train and test)
            Instant createdAt = base.plus(Duration.ofHours(i * 12L));
            Instant resolvedAt = createdAt.plus(Duration.ofHours(positive ? 6 : 1));
            String priority = positive ? "CRITICAL" : "MEDIUM";
            String category = positive ? "DATABASE" : negCategories[negIdx % negCategories.length];
            Long incServiceId = positive ? serviceId : allServices.get((negIdx / negCategories.length) % allServices.size());
            if (!positive) {
                negIdx++;
            }
            String number = "INC-P2-" + (1000 + i);
            Long incId = jdbc.queryForObject("""
                    insert into incidents (
                        number, title, description, category, classification_source, service_id, department_id,
                        impact, urgency, priority, status, affected_users, source, resolution_code, resolution_notes,
                        resolved_at, simulated, is_demo, created_at, updated_at
                    ) values (?, ?, 'Constructed training row', ?, 'MANUAL', ?, ?, ?, ?, ?, 'RESOLVED', 5, 'PORTAL',
                              ?, 'Resolved in test', ?, false, true, ?, ?)
                    returning id
                    """, Long.class, number, "Constructed incident " + i, category, incServiceId, deptId,
                    positive ? "HIGH" : "MEDIUM", positive ? "HIGH" : "MEDIUM", priority,
                    positive ? "WORKAROUND" : "FIXED", Timestamp.from(resolvedAt), Timestamp.from(createdAt), Timestamp.from(resolvedAt));
            createdIncidentIds.add(incId);

            long targetMin = positive ? 60 : 240;
            long consumedSec = positive ? 5400 : 1800; // 1.5x target (breached) vs 0.125x target (met)
            jdbc.update("""
                    insert into incident_slas (
                        incident_id, policy_id, policy_name, priority, business_hours_only,
                        response_target_minutes, resolution_target_minutes, at_risk_percent,
                        response_due_at, resolution_due_at, clock_status, health, outcome, started_at, completed_at,
                        consumed_seconds, created_at, updated_at
                    ) values (?, ?, 'Standard SLA', ?, false, 15, ?, 80, ?, ?, 'STOPPED', ?, ?, ?, ?, ?, ?, ?)
                    """, incId, policyId, priority, targetMin, Timestamp.from(createdAt.plus(Duration.ofMinutes(15))),
                    Timestamp.from(createdAt.plus(Duration.ofMinutes(targetMin))),
                    positive ? "BREACHED" : "ON_TRACK", positive ? "MISSED" : "MET",
                    Timestamp.from(createdAt), Timestamp.from(resolvedAt), consumedSec,
                    Timestamp.from(createdAt), Timestamp.from(resolvedAt));
        }

        Instant windowFrom = base.minus(Duration.ofDays(1));
        Instant windowTo = base.plus(Duration.ofDays(130));

        // 1. Train SLA_BREACH v1
        JsonNode slaV1 = body(mvc.perform(jsonBody(as("priya.raman", post("/api/predictions/models/train")), Map.of(
                "type", "SLA_BREACH",
                "from", windowFrom.toString(),
                "to", windowTo.toString(),
                "testFraction", 0.25)))
                .andExpect(status().isCreated()).andReturn());
        long slaV1Id = slaV1.get("id").asLong();
        createdModelIds.add(slaV1Id);
        assertThat(slaV1.get("status").asText()).isEqualTo("TRAINED");
        assertThat(slaV1.get("simulated").asBoolean()).isFalse();
        assertThat(slaV1.get("sampleCount").asInt()).isEqualTo(220);
        assertThat(slaV1.get("positiveCount").asInt()).isGreaterThanOrEqualTo(70);
        JsonNode testMetrics = slaV1.get("metrics").get("test");
        assertThat(testMetrics.get("auc").asDouble()).isGreaterThan(0.85);
        assertThat(testMetrics.get("brier").asDouble()).isLessThan(0.20);
        assertThat(testMetrics.get("calibration").size()).isEqualTo(10);

        // Out-of-time evaluation of stored model without refitting
        JsonNode evalJob = body(mvc.perform(jsonBody(as("priya.raman", post("/api/predictions/models/" + slaV1Id + "/evaluate")), Map.of(
                "from", windowFrom.toString(),
                "to", windowTo.toString())))
                .andExpect(status().isOk()).andReturn());
        assertThat(evalJob.get("jobType").asText()).isEqualTo("EVALUATE");
        assertThat(evalJob.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(evalJob.get("result").get("auc").asDouble()).isGreaterThan(0.85);

        // 2. Activate SLA_BREACH v1; then train and activate v2 -> v1 is automatically retired (single ACTIVE model per type).
        JsonNode activeV1 = body(mvc.perform(as("priya.raman", post("/api/predictions/models/" + slaV1Id + "/activate")))
                .andExpect(status().isOk()).andReturn());
        assertThat(activeV1.get("status").asText()).isEqualTo("ACTIVE");

        JsonNode slaV2 = body(mvc.perform(jsonBody(as("admin", post("/api/predictions/models/train")), Map.of(
                "type", "SLA_BREACH",
                "from", windowFrom.toString(),
                "to", windowTo.toString(),
                "includeSimulated", true)))
                .andExpect(status().isCreated()).andReturn());
        long slaV2Id = slaV2.get("id").asLong();
        createdModelIds.add(slaV2Id);
        assertThat(slaV2.get("simulated").asBoolean()).isTrue();

        body(mvc.perform(as("admin", post("/api/predictions/models/" + slaV2Id + "/activate")))
                .andExpect(status().isOk()).andReturn());
        assertThat(getJson("admin", "/api/predictions/models/" + slaV1Id).get("status").asText()).isEqualTo("RETIRED");
        // Re-activate v1 (non-simulated) so v2 is retired.
        body(mvc.perform(as("admin", post("/api/predictions/models/" + slaV1Id + "/activate")))
                .andExpect(status().isOk()).andReturn());
        assertThat(getJson("admin", "/api/predictions/models/" + slaV2Id).get("status").asText()).isEqualTo("RETIRED");

        // 3. Serve SLA_BREACH prediction from the ACTIVE model -> stored in predictions with modelVersion, features, and howComputed.
        long openIncident = jdbc.queryForObject("""
                select i.id from incidents i
                join incident_slas s on s.incident_id = i.id
                where i.parent_id is null and s.outcome is null and not i.simulated
                order by i.id desc limit 1
                """, Long.class);
        JsonNode served = getJson("ananya.bose", "/api/predictions/incidents/" + openIncident + "/sla-breach");
        assertThat(served.get("status").asText()).isEqualTo("OK");
        assertThat(served.get("basis").asText()).isEqualTo("MODEL");
        assertThat(served.get("modelId").asLong()).isEqualTo(slaV1Id);
        assertThat(served.get("predictionId").asLong()).isPositive();
        assertThat(served.get("howComputed").asText()).contains("sigmoid(intercept + Σ weight × standardised feature)");
        assertThat(served.get("contributions").isArray()).isTrue();
        assertThat(served.get("contributions").size()).isPositive();

        JsonNode storedRecords = getJson("ananya.bose", "/api/predictions/records?modelId=" + slaV1Id + "&subjectId=" + openIncident);
        assertThat(storedRecords.get("totalElements").asLong()).isEqualTo(1L);
        assertThat(storedRecords.get("content").get(0).get("outcomeStatus").asText()).isEqualTo("PENDING");

        // 4. Insert 35 inverted live predictions against resolved incidents to trigger live accuracy calculation and DRIFT warning.
        for (int i = 0; i < 35; i++) {
            Long incId = createdIncidentIds.get(i);
            boolean breached = (i % 3 == 0);
            // Deliberately invert score (high score for MET, low score for MISSED) to trigger drift vs offline AUC/Brier!
            double invertedScore = breached ? 0.05 : 0.95;
            jdbc.update("""
                    insert into predictions (
                        model_id, model_type, model_version, subject_type, subject_id, subject_label,
                        target, score, threshold, risk_level, target_at, features, explanation,
                        outcome_status, simulated, created_at
                    ) values (?, 'SLA_BREACH', ?, 'INCIDENT', ?, 'INC-DRIFT', 'breach', ?, 0.5, 'HIGH', now(),
                              '{}'::jsonb, '{}'::jsonb, 'PENDING', false, ?)
                    """, slaV1Id, activeV1.get("version").asInt(), incId, invertedScore, Timestamp.from(clock.instant()));
        }

        JsonNode reconciled = body(mvc.perform(as("admin", post("/api/predictions/outcomes/reconcile")))
                .andExpect(status().isOk()).andReturn());
        assertThat(reconciled.get("known").asInt()).isGreaterThanOrEqualTo(35);
        assertThat(reconciled.get("driftWarnings").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode perf = getJson("ananya.bose", "/api/predictions/models/" + slaV1Id + "/performance");
        assertThat(perf.get("outcomes").asInt()).isGreaterThanOrEqualTo(35);
        assertThat(perf.get("driftStatus").asText()).isEqualTo("DRIFT");
        assertThat(perf.get("driftReasons").size()).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from notifications where type = 'PREDICTION_DRIFT'", Long.class)).isPositive();

        // 5. Train INCIDENT_RECURRENCE on the same constructed dataset (every positive DATABASE incident has a later DATABASE incident within 14d).
        JsonNode recModel = body(mvc.perform(jsonBody(as("admin", post("/api/predictions/models/train")), Map.of(
                "type", "INCIDENT_RECURRENCE",
                "from", windowFrom.toString(),
                "to", windowTo.toString())))
                .andExpect(status().isCreated()).andReturn());
        createdModelIds.add(recModel.get("id").asLong());
        assertThat(recModel.get("status").asText()).isEqualTo("TRAINED");
        assertThat(recModel.get("metrics").get("test").get("auc").asDouble()).isGreaterThan(0.70);
    }

    @Test
    void serviceHealthAndCapacityRiskForecastsIncludeIntervalsAndHowComputed() throws Exception {
        long payrollId = jdbc.queryForObject("select id from business_services where code = 'PAYROLL'", Long.class);

        // Without active SERVICE_HEALTH or CAPACITY_RISK models, both endpoints explicitly report NO_ACTIVE_MODEL.
        assertThat(getJson("ananya.bose", "/api/predictions/services/" + payrollId + "/health?horizonDays=7").get("status").asText())
                .isEqualTo("NO_ACTIVE_MODEL");
        assertThat(getJson("ananya.bose", "/api/predictions/capacity?serviceId=" + payrollId + "&threshold=85&horizonHours=48").get("status").asText())
                .isEqualTo("NO_ACTIVE_MODEL");

        // Ensure PAYROLL has daily incident points spanning >= 110 days so SERVICE_HEALTH has >= 100 daily service points.
        Instant now = clock.instant();
        Long deptId = jdbc.queryForObject("select department_id from business_services where id = ?", Long.class, payrollId);
        for (int d = 110; d >= 10; d -= 15) {
            Instant ts = now.minus(Duration.ofDays(d));
            Long incId = jdbc.queryForObject("""
                    insert into incidents (
                        number, title, description, category, classification_source, service_id, department_id,
                        impact, urgency, priority, status, affected_users, source, simulated, is_demo, created_at, updated_at
                    ) values (?, 'Historical health point', 'Health series anchor', 'APPLICATION', 'MANUAL', ?, ?,
                              'MEDIUM', 'MEDIUM', 'MEDIUM', 'NEW', 1, 'PORTAL', false, true, ?, ?)
                    returning id
                    """, Long.class, "INC-SH-" + d, payrollId, deptId, Timestamp.from(ts), Timestamp.from(ts));
            createdIncidentIds.add(incId);
        }

        // Train and activate SERVICE_HEALTH over the last 115 days of service history (>= 100 daily service points).
        JsonNode healthModel = body(mvc.perform(jsonBody(as("admin", post("/api/predictions/models/train")), Map.of(
                "type", "SERVICE_HEALTH",
                "from", now.minus(Duration.ofDays(115)).toString(),
                "to", now.toString(),
                "testFraction", 0.25)))
                .andExpect(status().isCreated()).andReturn());
        long healthModelId = healthModel.get("id").asLong();
        createdModelIds.add(healthModelId);
        assertThat(healthModel.get("status").asText()).isEqualTo("TRAINED");
        body(mvc.perform(as("admin", post("/api/predictions/models/" + healthModelId + "/activate")))
                .andExpect(status().isOk()).andReturn());

        JsonNode health = getJson("ananya.bose", "/api/predictions/services/" + payrollId + "/health?horizonDays=7");
        assertThat(health.get("status").asText()).isEqualTo("OK");
        assertThat(health.get("serviceCode").asText()).isEqualTo("PAYROLL");
        assertThat(health.get("horizonDays").asInt()).isEqualTo(7);
        assertThat(health.get("howComputed").asText()).contains("fitted per metric");
        assertThat(health.get("metrics").isArray()).isTrue();
        assertThat(health.get("metrics").get(0).get("forecast").size()).isEqualTo(7);

        // Ingest 110 hourly CPU utilisation points rising from 45% toward 83.5% (>= 100 points required for CAPACITY_RISK training).
        String token = otlpSource("Capacity exporter " + uid());
        List<OtlpPayloads.Metric> gauges = new ArrayList<>();
        for (int h = 110; h >= 1; h--) {
            double val = 45.0 + (110 - h) * 0.35;
            gauges.add(new OtlpPayloads.Gauge("system.cpu.utilization", "%", Map.of("cpu", "total"), now.minus(Duration.ofHours(h)), val));
        }
        export("metrics", token, OtlpPayloads.metrics(resource("payroll-api"), gauges));

        JsonNode capModel = body(mvc.perform(jsonBody(as("admin", post("/api/predictions/models/train")), Map.of(
                "type", "CAPACITY_RISK",
                "from", now.minus(Duration.ofHours(115)).toString(),
                "to", now.toString(),
                "testFraction", 0.25)))
                .andExpect(status().isCreated()).andReturn());
        long capModelId = capModel.get("id").asLong();
        createdModelIds.add(capModelId);
        assertThat(capModel.get("status").asText()).isEqualTo("TRAINED");
        body(mvc.perform(as("admin", post("/api/predictions/models/" + capModelId + "/activate")))
                .andExpect(status().isOk()).andReturn());

        JsonNode cap = getJson("ananya.bose", "/api/predictions/capacity?serviceId=" + payrollId + "&threshold=85&horizonHours=48");
        assertThat(cap.get("status").asText()).isEqualTo("OK");
        assertThat(cap.get("horizonHours").asInt()).isEqualTo(48);
        assertThat(cap.get("howComputed").asText()).contains("Holt");
        assertThat(cap.get("series").size()).isPositive();
        JsonNode firstSeries = cap.get("series").get(0);
        assertThat(firstSeries.get("status").asText()).isEqualTo("OK");
        assertThat(firstSeries.get("atRisk").asBoolean()).isTrue();
        assertThat(firstSeries.get("hoursToThreshold").asDouble()).isPositive();
        assertThat(firstSeries.get("forecast").size()).isEqualTo(48);
    }
}
