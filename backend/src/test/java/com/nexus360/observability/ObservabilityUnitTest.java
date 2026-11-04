package com.nexus360.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.common.ApiException;
import com.nexus360.config.AppProperties;
import com.nexus360.observability.AnomalyEngine.Algorithm;
import com.nexus360.observability.AnomalyEngine.Direction;
import com.nexus360.observability.AnomalyEngine.Params;
import com.nexus360.observability.AnomalyEngine.Point;
import com.nexus360.observability.TelemetryAdapter.MetricQuery;
import com.nexus360.observability.TelemetryAdapter.OutboundRequest;
import com.nexus360.observability.TelemetryAdapter.SeriesData;
import com.nexus360.observability.TelemetryModel.LogRecord;
import com.nexus360.observability.TelemetryModel.MetricPoint;
import com.nexus360.observability.TelemetryModel.Parsed;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Pure unit tests: anomaly engine, OTLP JSON parser, prepared adapters (request builders and fixture responses), outbound SSRF guard. */
class ObservabilityUnitTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    private static List<Point> series(int n, long seed, double level, double sd) {
        Random r = new Random(seed);
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Point(T0.plusSeconds(60L * i), level + sd * r.nextGaussian()));
        }
        return out;
    }

    // ------------------------------------------------------------------ anomaly engine

    @Test
    void ewmaFlagsAnInjectedSpikeButNotNoise() {
        Params p = new Params(Algorithm.EWMA, 30, 4.0, 3, 30, Direction.BOTH, false);
        List<Point> noise = series(300, 1, 50, 1.5);
        assertThat(AnomalyEngine.detect(noise, p).windows()).isEmpty();
        List<Point> spiked = new ArrayList<>(noise);
        for (int i = 200; i < 206; i++) {
            spiked.set(i, new Point(spiked.get(i).t(), 80));
        }
        AnomalyEngine.Result r = AnomalyEngine.detect(spiked, p);
        assertThat(r.windows()).hasSize(1);
        AnomalyEngine.Window w = r.windows().get(0);
        assertThat(w.start()).isEqualTo(T0.plusSeconds(60L * 200));
        assertThat(w.end()).isEqualTo(T0.plusSeconds(60L * 205));
        assertThat(w.points()).isEqualTo(6);
        assertThat(w.direction()).isEqualTo("UP");
        assertThat(w.expected()).isBetween(48.0, 52.0);
        assertThat(w.upper()).isLessThan(80.0);
        assertThat(w.open()).isFalse();
    }

    @Test
    void consecutiveRuleIgnoresSinglePointBlipsAndMadHandlesDips() {
        List<Point> s = series(200, 2, 100, 2);
        s.set(150, new Point(s.get(150).t(), 200));
        s.set(151, new Point(s.get(151).t(), 200));
        assertThat(AnomalyEngine.detect(s, new Params(Algorithm.EWMA, 30, 4.0, 3, 30, Direction.BOTH, false)).windows()).isEmpty();
        assertThat(AnomalyEngine.detect(s, new Params(Algorithm.EWMA, 30, 4.0, 2, 30, Direction.BOTH, false)).windows()).hasSize(1);
        List<Point> dip = series(200, 3, 100, 2);
        for (int i = 190; i < 200; i++) {
            dip.set(i, new Point(dip.get(i).t(), 40));
        }
        AnomalyEngine.Result mad = AnomalyEngine.detect(dip, new Params(Algorithm.MEDIAN_MAD, 60, 4.0, 3, 30, Direction.BOTH, false));
        assertThat(mad.windows()).hasSize(1);
        assertThat(mad.windows().get(0).direction()).isEqualTo("DOWN");
        assertThat(mad.windows().get(0).open()).isTrue();
        assertThat(AnomalyEngine.detect(dip, new Params(Algorithm.MEDIAN_MAD, 60, 4.0, 3, 30, Direction.UP, false)).windows()).isEmpty();
    }

    @Test
    void insufficientDataIsReported() {
        AnomalyEngine.Result r = AnomalyEngine.detect(series(20, 4, 10, 1), new Params(Algorithm.EWMA, 30, 3, 3, 30, Direction.BOTH, false));
        assertThat(r.sufficient()).isFalse();
        assertThat(r.scoredPoints()).isZero();
        assertThat(r.windows()).isEmpty();
    }

    @Test
    void hourOfWeekSeasonalityUsesEarlierWeeks() {
        // Hourly data over three weeks: a daily peak at 12:00 is normal with seasonality, anomalous without it.
        List<Point> pts = new ArrayList<>();
        Random r = new Random(5);
        for (int h = 0; h < 24 * 21; h++) {
            int hour = h % 24;
            double v = (hour >= 12 && hour < 15 ? 200 : 100) + r.nextGaussian();
            pts.add(new Point(T0.plus(Duration.ofHours(h)), v));
        }
        Params seasonal = new Params(Algorithm.MEDIAN_MAD, 24, 4.0, 3, 2, Direction.BOTH, true);
        List<AnomalyEngine.Window> withSeason = AnomalyEngine.detect(pts, seasonal).windows().stream()
                .filter(w -> w.start().isAfter(T0.plus(Duration.ofDays(14)))).toList();
        assertThat(withSeason).isEmpty();
    }

    // ------------------------------------------------------------------ OTLP parser

    @Test
    void parserNormalisesSeverityIdsAttributesAndLimits() throws Exception {
        assertThat(OtlpJsonParser.severity(17, null)).isEqualTo("ERROR");
        assertThat(OtlpJsonParser.severity(1, null)).isEqualTo("TRACE");
        assertThat(OtlpJsonParser.severity(24, null)).isEqualTo("FATAL");
        assertThat(OtlpJsonParser.severity(0, "Warning")).isEqualTo("WARN");
        assertThat(OtlpJsonParser.severity(0, null)).isEqualTo("INFO");
        assertThat(OtlpJsonParser.spanKind(JSON.readTree("\"SPAN_KIND_CLIENT\""))).isEqualTo("CLIENT");
        assertThat(OtlpJsonParser.statusCode(JSON.readTree("2"))).isEqualTo("ERROR");
        JsonNode attrs = JSON.readTree("[{\"key\":\"a\",\"value\":{\"intValue\":\"5\"}},{\"key\":\"b\",\"value\":{\"arrayValue\":{\"values\":[{\"stringValue\":\"x\"}]}}},"
                + "{\"key\":\"c\",\"value\":{\"boolValue\":true}}]");
        assertThat(OtlpJsonParser.attributes(attrs, 64, null)).containsEntry("a", "5").containsEntry("b", "[\"x\"]").containsEntry("c", "true");
        int[] dropped = {0};
        assertThat(OtlpJsonParser.attributes(attrs, 2, dropped)).hasSize(2);
        assertThat(dropped[0]).isEqualTo(1);
        assertThat(OtlpJsonParser.time(JSON.readTree("{\"t\":\"1700000000123456789\"}"), "t")).isEqualTo(Instant.ofEpochSecond(1_700_000_000L, 123_456_789));

        Instant now = Instant.now();
        OtlpJsonParser parser = new OtlpJsonParser(2, now.minus(Duration.ofDays(1)), now.plusSeconds(600));
        List<OtlpPayloads.Gauge> three = List.of(new OtlpPayloads.Gauge("g", null, Map.of(), now, 1), new OtlpPayloads.Gauge("g", null, Map.of(), now, 2),
                new OtlpPayloads.Gauge("g", null, Map.of(), now, 3));
        Parsed<MetricPoint> parsed = parser.metrics(OtlpPayloads.metrics(Map.of("service.name", "s"), three));
        assertThat(parsed.accepted()).isEqualTo(2);
        assertThat(parsed.rejected()).isEqualTo(1);
        assertThat(parsed.errorMessage()).contains("more than 2");
        Parsed<LogRecord> logs = parser.logs(OtlpPayloads.logs(Map.of(), List.of(new OtlpPayloads.Log(now, 13, null, "y".repeat(9000), Map.of(), null, null))));
        assertThat(logs.batches().get(0).items().get(0).bodyTruncated()).isTrue();
        assertThat(logs.batches().get(0).items().get(0).severity()).isEqualTo("WARN");
        assertThatThrownBy(() -> parser.traces(JSON.readTree("[]"))).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ prepared adapters (fixtures)

    private static final Instant FROM = Instant.parse("2026-11-04T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-04T10:05:00Z");

    @Test
    void cloudWatchBuildsGetMetricDataAndParsesResults() throws Exception {
        CloudWatchAdapter a = new CloudWatchAdapter();
        Map<String, Object> config = Map.of("region", "ap-south-1", "queries", List.of(Map.of("name", "aws.cpu", "namespace", "AWS/EC2", "metricName",
                "CPUUtilization", "dimensions", Map.of("InstanceId", "i-0abc"), "stat", "Average")));
        a.validate(config);
        assertThatThrownBy(() -> a.validate(Map.of("region", "mars", "queries", List.of()))).isInstanceOf(ApiException.class);
        MetricQuery q = a.queries(config).get(0);
        OutboundRequest req = a.buildQuery(a.defaultBaseUrl(config), config, q, FROM, TO, Duration.ofMinutes(1));
        assertThat(req.uri()).isEqualTo(URI.create("https://monitoring.ap-south-1.amazonaws.com/"));
        assertThat(req.headers()).containsEntry("X-Amz-Target", "GraniteServiceVersion20100801.GetMetricData");
        JsonNode body = JSON.readTree(req.body());
        assertThat(body.at("/MetricDataQueries/0/MetricStat/Metric/MetricName").asText()).isEqualTo("CPUUtilization");
        assertThat(body.at("/MetricDataQueries/0/MetricStat/Metric/Dimensions/0/Value").asText()).isEqualTo("i-0abc");
        assertThat(body.at("/MetricDataQueries/0/MetricStat/Period").asInt()).isEqualTo(60);
        assertThat(body.get("StartTime").asLong()).isEqualTo(FROM.getEpochSecond());
        String fixture = "{\"MetricDataResults\":[{\"Id\":\"m1\",\"Label\":\"CPUUtilization\",\"Timestamps\":[1762250400,1762250460],\"Values\":[12.5,14.0],"
                + "\"StatusCode\":\"Complete\"}],\"Messages\":[]}";
        List<SeriesData> data = a.parse(fixture, q);
        assertThat(data).hasSize(1);
        assertThat(data.get(0).samples()).hasSize(2);
        assertThat(data.get(0).samples().get(1).value()).isEqualTo(14.0);
        assertThat(data.get(0).resource()).containsEntry("cloud.provider", "aws").containsEntry("host.id", "i-0abc");
        assertThat(a.buildTest(null, config)).isNull();
        assertThat(a.testUnsupportedReason()).contains("Signature Version 4");
        assertThatThrownBy(() -> a.parse("{}", q)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void azureMonitorBuildsMetricsRequestAndParsesTimeseries() {
        AzureMonitorAdapter a = new AzureMonitorAdapter();
        String rid = "/subscriptions/1111/resourceGroups/rg-sa/providers/Microsoft.Compute/virtualMachines/vm-payroll";
        Map<String, Object> config = Map.of("resourceId", rid, "queries", List.of(Map.of("name", "azure.cpu", "metricName", "Percentage CPU",
                "aggregation", "Average")));
        a.validate(config);
        assertThatThrownBy(() -> a.validate(Map.of("resourceId", "vm1", "queries", List.of(Map.of("name", "x", "metricName", "y")))))
                .isInstanceOf(ApiException.class);
        MetricQuery q = a.queries(config).get(0);
        OutboundRequest req = a.buildQuery(a.defaultBaseUrl(config), config, q, FROM, TO, Duration.ofMinutes(1));
        assertThat(req.uri().toString()).startsWith("https://management.azure.com" + rid + "/providers/Microsoft.Insights/metrics?api-version=2023-10-01")
                .contains("metricnames=Percentage%20CPU").contains("interval=PT1M").contains("aggregation=Average")
                .contains("timespan=2026-11-04T10%3A00%3A00Z%2F2026-11-04T10%3A05%3A00Z");
        String fixture = "{\"cost\":0,\"timespan\":\"x\",\"interval\":\"PT1M\",\"resourceregion\":\"centralindia\",\"value\":[{\"id\":\"" + rid
                + "/providers/Microsoft.Insights/metrics/Percentage CPU\",\"type\":\"Microsoft.Insights/metrics\",\"name\":{\"value\":\"Percentage CPU\"},"
                + "\"unit\":\"Percent\",\"timeseries\":[{\"metadatavalues\":[],\"data\":[{\"timeStamp\":\"2026-11-04T10:00:00Z\",\"average\":21.5},"
                + "{\"timeStamp\":\"2026-11-04T10:01:00Z\"}]}]}]}";
        List<SeriesData> data = a.parse(fixture, q);
        assertThat(data.get(0).samples()).hasSize(1);
        assertThat(data.get(0).samples().get(0).value()).isEqualTo(21.5);
        assertThat(data.get(0).resource()).containsEntry("cloud.region", "centralindia").containsEntry("cloud.resource_id", rid);
        assertThat(a.buildTest(a.defaultBaseUrl(config), config).uri().toString()).contains("metricDefinitions");
    }

    @Test
    void gcpBuildsTimeSeriesListAndParsesPoints() {
        GcpMonitoringAdapter a = new GcpMonitoringAdapter();
        Map<String, Object> config = Map.of("projectId", "sa-demo-project", "queries", List.of(Map.of("name", "gcp.cpu", "filter",
                "metric.type=\"compute.googleapis.com/instance/cpu/utilization\"")));
        a.validate(config);
        MetricQuery q = a.queries(config).get(0);
        OutboundRequest req = a.buildQuery(a.defaultBaseUrl(config), config, q, FROM, TO, Duration.ofMinutes(1));
        assertThat(req.uri().toString()).startsWith("https://monitoring.googleapis.com/v3/projects/sa-demo-project/timeSeries?filter=metric.type%3D%22")
                .contains("aggregation.alignmentPeriod=60s").contains("perSeriesAligner=ALIGN_MEAN");
        String fixture = "{\"timeSeries\":[{\"metric\":{\"type\":\"compute.googleapis.com/instance/cpu/utilization\",\"labels\":{\"instance_name\":\"vm1\"}},"
                + "\"resource\":{\"type\":\"gce_instance\",\"labels\":{\"instance_id\":\"123\",\"zone\":\"asia-south1-a\",\"project_id\":\"sa-demo-project\"}},"
                + "\"points\":[{\"interval\":{\"startTime\":\"2026-11-04T10:01:00Z\",\"endTime\":\"2026-11-04T10:02:00Z\"},\"value\":{\"doubleValue\":0.42}},"
                + "{\"interval\":{\"startTime\":\"2026-11-04T10:00:00Z\",\"endTime\":\"2026-11-04T10:01:00Z\"},\"value\":{\"int64Value\":\"1\"}}]}]}";
        List<SeriesData> data = a.parse(fixture, q);
        assertThat(data.get(0).samples()).hasSize(2);
        assertThat(data.get(0).samples().get(0).timestamp()).isEqualTo(Instant.parse("2026-11-04T10:01:00Z"));
        assertThat(data.get(0).resource()).containsEntry("cloud.provider", "gcp").containsEntry("host.id", "123").containsEntry("cloud.region",
                "asia-south1-a");
        assertThat(data.get(0).attributes()).containsEntry("instance_name", "vm1");
        assertThatThrownBy(() -> a.parse("{\"error\":{\"message\":\"denied\"}}", q)).hasMessageContaining("denied");
    }

    @Test
    void grafanaBuildsDsQueryAndParsesFrames() throws Exception {
        GrafanaAdapter a = new GrafanaAdapter();
        Map<String, Object> config = Map.of("datasourceUid", "prom-main", "queries", List.of(Map.of("name", "grafana.up", "expr", "up")));
        a.validate(config);
        MetricQuery q = a.queries(config).get(0);
        OutboundRequest req = a.buildQuery("https://grafana.example.com/", config, q, FROM, TO, Duration.ofMinutes(1));
        assertThat(req.uri()).isEqualTo(URI.create("https://grafana.example.com/api/ds/query"));
        JsonNode body = JSON.readTree(req.body());
        assertThat(body.at("/queries/0/datasource/uid").asText()).isEqualTo("prom-main");
        assertThat(body.get("from").asText()).isEqualTo(String.valueOf(FROM.toEpochMilli()));
        String fixture = "{\"results\":{\"A\":{\"status\":200,\"frames\":[{\"schema\":{\"fields\":[{\"name\":\"Time\",\"type\":\"time\"},{\"name\":\"Value\","
                + "\"type\":\"number\",\"labels\":{\"job\":\"payroll\",\"instance\":\"10.0.0.1:9100\"}}]},\"data\":{\"values\":[[1762250400000,1762250460000],"
                + "[1,0]]}}]}}}";
        List<SeriesData> data = a.parse(fixture, q);
        assertThat(data.get(0).samples()).hasSize(2);
        assertThat(data.get(0).resource()).containsEntry("service.name", "payroll").containsEntry("host.name", "10.0.0.1");
        assertThatThrownBy(() -> a.parse("{\"results\":{\"A\":{\"error\":\"bad expr\"}}}", q)).hasMessageContaining("bad expr");
    }

    @Test
    void prometheusParsesMatrixAndRejectsErrors() {
        PrometheusAdapter a = new PrometheusAdapter();
        MetricQuery q = new MetricQuery("p.up", "up", Map.of());
        List<SeriesData> data = a.parse("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"job\":\"api\",\"instance\":"
                + "\"[::1]:9090\"},\"value\":[1762250400.5,\"3\"]}]}}", q);
        assertThat(data.get(0).resource()).containsEntry("service.name", "api").containsEntry("host.name", "::1");
        assertThat(data.get(0).samples().get(0).timestamp()).isEqualTo(Instant.ofEpochMilli(1_762_250_400_500L));
        assertThatThrownBy(() -> a.parse("{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"parse error\"}", q)).hasMessageContaining("parse error");
    }

    // ------------------------------------------------------------------ SSRF guard

    @Test
    void outboundCallsToPrivateAddressesAreBlocked() {
        AppProperties props = new AppProperties(null, null, null, null, null, null, null, null, new AppProperties.Integrations(null, false, 5), null, null,
                null, null);
        OutboundHttp http = new OutboundHttp(props);
        for (String url : List.of("http://127.0.0.1:9090/api/v1/status/buildinfo", "https://169.254.169.254/latest/meta-data", "https://10.0.0.5/",
                "https://localhost/")) {
            OutboundHttp.Result r = http.execute(new OutboundRequest("GET", URI.create(url), Map.of(), null), OutboundHttp.Credentials.NONE);
            assertThat(r.ok()).isFalse();
            assertThat(r.error()).startsWith("Blocked by the outbound URL policy");
        }
        assertThatThrownBy(() -> http.validate("https://user:pw@example.com/")).isInstanceOf(ApiException.class);
    }
}
