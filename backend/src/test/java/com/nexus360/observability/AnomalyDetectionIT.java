package com.nexus360.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Anomaly detection on constructed series: Gaussian noise is not flagged, an injected spike is (with its expected range), the evaluation
 * computes precision and recall against labelled windows, and an open anomaly raises an OBSERVABILITY alert through the alert pipeline,
 * which converts into an incident and is resolved when the anomaly ends.
 */
class AnomalyDetectionIT extends ObservabilityTestSupport {

    private static double[] noise(int n, long seed) {
        Random r = new Random(seed);
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = 100 + 2 * r.nextGaussian();
        }
        return v;
    }

    private long detector(String metric, Map<String, Object> extra) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", "Detector " + metric);
        req.put("metricName", metric);
        req.put("algorithm", "EWMA");
        req.put("windowPoints", 30);
        req.put("threshold", 4.0);
        req.put("consecutivePoints", 3);
        req.put("minSamples", 30);
        req.put("direction", "BOTH");
        req.put("seasonality", "NONE");
        req.putAll(extra);
        return body(mvc.perform(jsonBody(as("admin", post("/api/observability/anomaly-detectors")), req)).andExpect(status().isCreated()).andReturn())
                .get("id").asLong();
    }

    @Test
    void spikeIsDetectedNoiseIsNotAndEvaluationComputesPrecisionAndRecall() throws Exception {
        String token = otlpSource("Anomaly spike " + uid());
        String metric = "test.anomaly.latency." + uid();
        double[] values = noise(150, 42);
        for (int i = 100; i < 105; i++) {
            values[i] = 160;
        }
        Instant end = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        assertThat(export("metrics", token, OtlpPayloads.metrics(resource("anomaly-" + uid()), gauges(metric, Map.of(), end, values))).getResponse()
                .getStatus()).isEqualTo(200);
        long id = detector(metric, Map.of("raiseAlert", false));

        JsonNode run = body(mvc.perform(as("admin", post("/api/observability/anomaly-detectors/" + id + "/run"))).andExpect(status().isOk()).andReturn());
        assertThat(run.get("status").asText()).isEqualTo("OK");
        assertThat(run.get("seriesEvaluated").asInt()).isEqualTo(1);
        assertThat(run.get("anomaliesOpened").asInt()).isEqualTo(1);
        JsonNode anomalies = getJson("riya.varma", "/api/observability/anomalies?detectorId=" + id);
        assertThat(anomalies.get("totalElements").asLong()).isEqualTo(1);
        JsonNode a = anomalies.get("content").get(0);
        Instant spikeStart = end.minusSeconds(60L * 150).plusSeconds(60L * 100 + 10);
        assertThat(Instant.parse(a.get("startedAt").asText()).truncatedTo(ChronoUnit.MINUTES)).isEqualTo(spikeStart.truncatedTo(ChronoUnit.MINUTES));
        assertThat(a.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(a.get("direction").asText()).isEqualTo("UP");
        assertThat(a.get("pointCount").asInt()).isEqualTo(5);
        assertThat(a.get("observedValue").asDouble()).isEqualTo(160.0);
        assertThat(a.get("expectedValue").asDouble()).isBetween(97.0, 103.0);
        assertThat(a.get("expectedUpper").asDouble()).isLessThan(160.0);
        assertThat(a.get("peakScore").asDouble()).isGreaterThan(4.0);
        assertThat(a.get("modelParams").get("algorithm").asText()).isEqualTo("EWMA");
        // Running again does not duplicate the anomaly.
        mvc.perform(as("admin", post("/api/observability/anomaly-detectors/" + id + "/run"))).andExpect(status().isOk());
        assertThat(getJson("riya.varma", "/api/observability/anomalies?detectorId=" + id).get("totalElements").asLong()).isEqualTo(1);

        Instant from = end.minus(Duration.ofMinutes(160));
        Map<String, Object> label = Map.of("from", spikeStart.minusSeconds(60).toString(), "to", spikeStart.plusSeconds(300).toString(), "label", "injected spike");
        JsonNode eval = body(mvc.perform(jsonBody(as("admin", post("/api/observability/anomaly-detectors/" + id + "/evaluate")), Map.of("from", from.toString(),
                "to", end.plusSeconds(60).toString(), "labels", List.of(label), "useAlerts", false, "toleranceMinutes", 0))).andExpect(status().isOk()).andReturn());
        assertThat(eval.get("status").asText()).isEqualTo("EVALUATED");
        assertThat(eval.get("detections")).hasSize(1);
        assertThat(eval.get("truePositives").asInt()).isEqualTo(1);
        assertThat(eval.get("falsePositives").asInt()).isZero();
        assertThat(eval.get("precision").asDouble()).isEqualTo(1.0);
        assertThat(eval.get("recall").asDouble()).isEqualTo(1.0);
        assertThat(eval.get("f1").asDouble()).isEqualTo(1.0);
        assertThat(getJson("admin", "/api/observability/anomaly-detectors/" + id).get("lastEvaluation").get("recall").asDouble()).isEqualTo(1.0);

        // A label where nothing happened: the detection is a false positive and the label is missed.
        Map<String, Object> wrong = Map.of("from", end.minus(Duration.ofMinutes(140)).toString(), "to", end.minus(Duration.ofMinutes(135)).toString());
        JsonNode miss = body(mvc.perform(jsonBody(as("admin", post("/api/observability/anomaly-detectors/" + id + "/evaluate")), Map.of("from", from.toString(),
                "to", end.plusSeconds(60).toString(), "labels", List.of(wrong), "useAlerts", false, "toleranceMinutes", 0))).andExpect(status().isOk()).andReturn());
        assertThat(miss.get("precision").asDouble()).isZero();
        assertThat(miss.get("recall").asDouble()).isZero();
        assertThat(miss.get("falseNegatives").asInt()).isEqualTo(1);

        // Without labels precision/recall are not computed.
        JsonNode none = body(mvc.perform(jsonBody(as("admin", post("/api/observability/anomaly-detectors/" + id + "/evaluate")), Map.of("from", from.toString(),
                "to", end.plusSeconds(60).toString(), "useAlerts", false))).andExpect(status().isOk()).andReturn());
        assertThat(none.get("status").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(none.get("precision").isNull()).isTrue();
    }

    @Test
    void pureNoiseIsNotFlaggedAndShortSeriesReportInsufficientData() throws Exception {
        String token = otlpSource("Anomaly noise " + uid());
        String metric = "test.anomaly.noise." + uid();
        Instant end = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        export("metrics", token, OtlpPayloads.metrics(resource("noise-" + uid()), gauges(metric, Map.of(), end, noise(180, 7))));
        long id = detector(metric, Map.of("raiseAlert", false));
        JsonNode run = body(mvc.perform(as("admin", post("/api/observability/anomaly-detectors/" + id + "/run"))).andExpect(status().isOk()).andReturn());
        assertThat(run.get("anomaliesOpened").asInt()).isZero();
        assertThat(getJson("admin", "/api/observability/anomalies?detectorId=" + id).get("totalElements").asLong()).isZero();

        String shortMetric = "test.anomaly.short." + uid();
        export("metrics", token, OtlpPayloads.metrics(resource("short-" + uid()), gauges(shortMetric, Map.of(), end, noise(12, 3))));
        long shortId = detector(shortMetric, Map.of("raiseAlert", false));
        JsonNode shortRun = body(mvc.perform(as("admin", post("/api/observability/anomaly-detectors/" + shortId + "/run"))).andExpect(status().isOk())
                .andReturn());
        assertThat(shortRun.get("status").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(shortRun.get("message").asText()).contains("12 of 31 samples");
        JsonNode noSeries = body(mvc.perform(as("admin", post("/api/observability/anomaly-detectors/" + detector("test.none." + uid(), Map.of()) + "/run")))
                .andExpect(status().isOk()).andReturn());
        assertThat(noSeries.get("status").asText()).isEqualTo("NO_SERIES");

        // Validation of detector parameters.
        mvc.perform(jsonBody(as("admin", post("/api/observability/anomaly-detectors")), Map.of("name", "bad " + uid(), "metricName", "m", "threshold", 0.5)))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonBody(as("admin", post("/api/observability/anomaly-detectors")), Map.of("name", "bad " + uid(), "metricName", "m", "algorithm", "ML")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void openAnomalyRaisesAnAlertThatBecomesAnIncidentAndResolvesWithTheAnomaly() throws Exception {
        String token = otlpSource("Anomaly alert " + uid());
        String metric = "test.anomaly.alert." + uid();
        String code = ("ANO" + uid()).toUpperCase();
        long[] svc = service(code);
        String serviceName = "anomaly-alert-" + uid();
        mvc.perform(jsonBody(as("admin", post("/api/observability/mappings")), Map.of("name", "map " + serviceName, "attributeKey", "service.name",
                "matchType", "EXACT", "matchValue", serviceName, "serviceId", svc[0]))).andExpect(status().isCreated());
        double[] values = noise(120, 11);
        for (int i = 115; i < 120; i++) {
            values[i] = 175;
        }
        Instant end = clock.instant().truncatedTo(ChronoUnit.MINUTES).plusSeconds(60);
        export("metrics", token, OtlpPayloads.metrics(resource(serviceName), gauges(metric, Map.of(), end, values)));
        long id = detector(metric, Map.of("raiseAlert", true, "createIncident", true, "alertSeverity", "CRITICAL", "direction", "UP"));

        JsonNode run = body(mvc.perform(as("admin", post("/api/observability/anomaly-detectors/" + id + "/run"))).andExpect(status().isOk()).andReturn());
        assertThat(run.get("anomaliesOpened").asInt()).isEqualTo(1);
        assertThat(run.get("alertsRaised").asInt()).isEqualTo(1);
        JsonNode anomaly = getJson("riya.varma", "/api/observability/anomalies?status=OPEN&detectorId=" + id).get("content").get(0);
        assertThat(anomaly.get("serviceId").asLong()).isEqualTo(svc[0]);
        long alertId = anomaly.get("alertId").asLong();
        JsonNode alert = getJson("riya.varma", "/api/alerts/" + alertId);
        assertThat(alert.at("/alert/sourceKind").asText()).isEqualTo("OBSERVABILITY");
        String alertJson = alert.toString();
        assertThat(alertJson).contains("expectedRange").contains("\"severity\":\"CRITICAL\"");
        Long incidentId = jdbc.queryForObject("select incident_id from alerts where id = ?", Long.class, alertId);
        assertThat(incidentId).isNotNull();
        assertThat(jdbc.queryForObject("select service_id from incidents where id = ?", Long.class, incidentId)).isEqualTo(svc[0]);
        assertThat(jdbc.queryForObject("select simulated from incidents where id = ?", Boolean.class, incidentId)).isFalse();

        // The anomaly ends: normal points for the next minutes → anomaly RESOLVED, alert resolved by the source.
        testClock().advance(Duration.ofMinutes(4));
        Instant later = clock.instant().truncatedTo(ChronoUnit.MINUTES).plusSeconds(60);
        export("metrics", token, OtlpPayloads.metrics(resource(serviceName), gauges(metric, Map.of(), later, noise(5, 99))));
        JsonNode second = body(mvc.perform(as("admin", post("/api/observability/anomaly-detectors/" + id + "/run"))).andExpect(status().isOk()).andReturn());
        assertThat(second.get("anomaliesResolved").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from telemetry_anomalies where id = ?", String.class, anomaly.get("id").asLong())).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("select status from alerts where id = ?", String.class, alertId)).isEqualTo("RESOLVED");
    }
}
