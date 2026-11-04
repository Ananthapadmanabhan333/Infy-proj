package com.nexus360.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Payload parsers with real-shaped samples of Alertmanager, Grafana (unified and legacy), CloudWatch (SNS message) and the generic format. */
class AlertPayloadsTest {

    private final ObjectMapper json = new ObjectMapper();

    static final String ALERTMANAGER = """
            {"receiver":"sa-webhook","status":"firing","alerts":[
              {"status":"firing","labels":{"alertname":"PayrollHigh5xxRate","instance":"payroll-app-01:8080","job":"payroll","service":"payroll",
                "severity":"critical"},"annotations":{"description":"More than 5% of requests failed with HTTP 503 for 5 minutes.",
                "summary":"Payroll returns HTTP 503 for 7.4% of requests"},"startsAt":"2026-11-04T10:02:11.123Z","endsAt":"0001-01-01T00:00:00Z",
                "generatorURL":"http://prometheus:9090/graph?g0.expr=sum%28rate%28http_requests_total%5B5m%5D%29%29","fingerprint":"c4f3b2a1d0e9f8a1"},
              {"status":"resolved","labels":{"alertname":"PayrollHigh5xxRate","instance":"payroll-app-02:8080","job":"payroll","service":"payroll",
                "severity":"critical"},"annotations":{},"startsAt":"2026-11-04T09:40:00Z","endsAt":"2026-11-04T10:05:00Z",
                "generatorURL":"http://prometheus:9090/graph","fingerprint":"c4f3b2a1d0e9f8a2"}],
             "groupLabels":{"service":"payroll"},"commonLabels":{"job":"payroll","service":"payroll","severity":"critical"},
             "commonAnnotations":{},"externalURL":"http://alertmanager:9093","version":"4","groupKey":"{}:{service=\\"payroll\\"}","truncatedAlerts":0}
            """;

    @Test
    void alertmanagerWebhookIsParsedPerAlert() throws Exception {
        List<AlertSignal> signals = AlertPayloads.alertmanager(json.readTree(ALERTMANAGER));
        assertThat(signals).hasSize(2);
        AlertSignal firing = signals.get(0);
        assertThat(firing.fingerprint()).isEqualTo("c4f3b2a1d0e9f8a1");
        assertThat(firing.groupKey()).isEqualTo("{}:{service=\"payroll\"}");
        assertThat(firing.title()).isEqualTo("Payroll returns HTTP 503 for 7.4% of requests");
        assertThat(firing.severity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(firing.firing()).isTrue();
        assertThat(firing.serviceCode()).isEqualTo("payroll");
        assertThat(firing.ciName()).isEqualTo("payroll-app-01");
        assertThat(firing.startsAt()).isEqualTo(Instant.parse("2026-11-04T10:02:11.123Z"));
        assertThat(firing.endsAt()).isNull(); // Go zero time
        assertThat(firing.externalUrl()).startsWith("http://prometheus:9090/graph");
        assertThat(firing.labels()).containsEntry("alertname", "PayrollHigh5xxRate");
        AlertSignal resolved = signals.get(1);
        assertThat(resolved.firing()).isFalse();
        assertThat(resolved.title()).isEqualTo("PayrollHigh5xxRate on payroll-app-02:8080"); // no summary annotation
        assertThat(resolved.endsAt()).isEqualTo(Instant.parse("2026-11-04T10:05:00Z"));

        assertThatThrownBy(() -> AlertPayloads.alertmanager(json.readTree("{\"version\":\"4\"}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlertPayloads.alertmanager(json.readTree("{\"alerts\":[{\"status\":\"weird\",\"labels\":{}}]}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprintFallsBackToTheLabelSet() throws Exception {
        String body = "{\"alerts\":[{\"status\":\"firing\",\"labels\":{\"b\":\"2\",\"alertname\":\"X\",\"a\":\"1\"}}]}";
        String other = "{\"alerts\":[{\"status\":\"firing\",\"labels\":{\"a\":\"1\",\"alertname\":\"X\",\"b\":\"2\"}}]}";
        assertThat(AlertPayloads.alertmanager(json.readTree(body)).get(0).fingerprint())
                .isEqualTo(AlertPayloads.alertmanager(json.readTree(other)).get(0).fingerprint()).hasSize(64);
        assertThat(AlertPayloads.alertmanager(json.readTree(body)).get(0).severity()).isNull(); // source default applies
    }

    static final String GRAFANA = """
            {"receiver":"SA","status":"firing","orgId":1,"alerts":[{"status":"firing","labels":{"alertname":"VpnTunnelFlapping",
              "grafana_folder":"Network","instance":"vpn-gw-02","severity":"warning"},"annotations":{"summary":"VPN tunnel flapping on vpn-gw-02",
              "description":"IPsec tunnel went down 4 times in 15 minutes"},"startsAt":"2026-11-04T09:30:00+05:30","endsAt":"0001-01-01T00:00:00Z",
              "generatorURL":"https://grafana.example.com/alerting/grafana/abc/view","fingerprint":"6d0f1e2a3b4c5d6e",
              "silenceURL":"https://grafana.example.com/alerting/silence/new","dashboardURL":"https://grafana.example.com/d/vpn",
              "panelURL":"https://grafana.example.com/d/vpn?viewPanel=4","values":{"B":4},"valueString":"[ var='B' labels={} value=4 ]"}],
             "groupLabels":{"alertname":"VpnTunnelFlapping"},"commonLabels":{"alertname":"VpnTunnelFlapping"},"commonAnnotations":{},
             "externalURL":"https://grafana.example.com/","version":"1","groupKey":"{}/{}:{alertname=\\"VpnTunnelFlapping\\"}","truncatedAlerts":0,
             "title":"[FIRING:1] VpnTunnelFlapping Network","state":"alerting","message":"**Firing**"}
            """;

    @Test
    void grafanaUnifiedAndLegacyPayloads() throws Exception {
        AlertSignal s = AlertPayloads.grafana(json.readTree(GRAFANA)).get(0);
        assertThat(s.fingerprint()).isEqualTo("6d0f1e2a3b4c5d6e");
        assertThat(s.title()).isEqualTo("VPN tunnel flapping on vpn-gw-02");
        assertThat(s.severity()).isEqualTo(AlertSeverity.WARNING);
        assertThat(s.externalUrl()).isEqualTo("https://grafana.example.com/d/vpn?viewPanel=4");
        assertThat(s.ciName()).isEqualTo("vpn-gw-02");
        assertThat(s.annotations()).containsKey("valueString");
        assertThat(s.startsAt()).isEqualTo(Instant.parse("2026-11-04T04:00:00Z"));

        String legacy = """
                {"dashboardId":1,"evalMatches":[{"value":100,"metric":"High value","tags":null}],"message":"Notification Message",
                 "orgId":1,"panelId":2,"ruleId":17,"ruleName":"Panel Title alert","ruleUrl":"https://grafana.example.com/d/abc?panelId=2",
                 "state":"alerting","tags":{"severity":"critical","service":"ERP"},"title":"[Alerting] Panel Title alert"}
                """;
        AlertSignal l = AlertPayloads.grafana(json.readTree(legacy)).get(0);
        assertThat(l.fingerprint()).isEqualTo("grafana-rule-17");
        assertThat(l.title()).isEqualTo("Panel Title alert");
        assertThat(l.firing()).isTrue();
        assertThat(l.severity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(l.serviceCode()).isEqualTo("ERP");
        assertThat(AlertPayloads.grafana(json.readTree(legacy.replace("\"alerting\"", "\"ok\""))).get(0).firing()).isFalse();
        assertThat(AlertPayloads.grafana(json.readTree(legacy.replace("\"alerting\"", "\"no_data\"")))).isEmpty();
        assertThatThrownBy(() -> AlertPayloads.grafana(json.readTree("{\"foo\":1}"))).isInstanceOf(IllegalArgumentException.class);
    }

    static String cloudwatchAlarm(String state) {
        return """
                {"AlarmName":"payroll-alb-5xx","AlarmDescription":"Payroll ALB 5XX above 50 per minute severity: critical service: PAYROLL",
                 "AWSAccountId":"123456789012","AlarmConfigurationUpdatedTimestamp":"2026-10-01T08:00:00.000+0000","NewStateValue":"%s",
                 "NewStateReason":"Threshold Crossed: 1 datapoint [212.0 (04/11/26 10:02:00)] was greater than the threshold (50.0).",
                 "StateChangeTime":"2026-11-04T10:03:00.215+0000","Region":"Asia Pacific (Mumbai)",
                 "AlarmArn":"arn:aws:cloudwatch:ap-south-1:123456789012:alarm:payroll-alb-5xx","OldStateValue":"OK","OKActions":[],
                 "AlarmActions":["arn:aws:sns:ap-south-1:123456789012:payroll-alarms"],"InsufficientDataActions":[],
                 "Trigger":{"MetricName":"HTTPCode_Target_5XX_Count","Namespace":"AWS/ApplicationELB","StatisticType":"Statistic","Statistic":"SUM",
                  "Unit":null,"Dimensions":[{"value":"app/payroll-alb/50dc6c495c0c9188","name":"LoadBalancer"}],"Period":60,"EvaluationPeriods":1,
                  "DatapointsToAlarm":1,"ComparisonOperator":"GreaterThanThreshold","Threshold":50.0,"TreatMissingData":"missing",
                  "EvaluateLowSampleCountPercentile":""}}
                """.formatted(state);
    }

    @Test
    void cloudwatchAlarmStateChanges() throws Exception {
        AlertSignal s = AlertPayloads.cloudwatch(json.readTree(cloudwatchAlarm("ALARM")));
        assertThat(s.fingerprint()).isEqualTo("arn:aws:cloudwatch:ap-south-1:123456789012:alarm:payroll-alb-5xx");
        assertThat(s.firing()).isTrue();
        assertThat(s.severity()).isEqualTo(AlertSeverity.CRITICAL); // "severity: critical" tag in the description
        assertThat(s.serviceCode()).isEqualTo("PAYROLL");
        assertThat(s.title()).startsWith("payroll-alb-5xx: Payroll ALB 5XX above 50 per minute");
        assertThat(s.labels()).containsEntry("region", "ap-south-1").containsEntry("dimension.LoadBalancer", "app/payroll-alb/50dc6c495c0c9188")
                .containsEntry("metricName", "HTTPCode_Target_5XX_Count");
        assertThat(s.startsAt()).isEqualTo(Instant.parse("2026-11-04T10:03:00.215Z"));
        assertThat(s.externalUrl()).startsWith("https://console.aws.amazon.com/cloudwatch/home?region=ap-south-1#alarmsV2:alarm/payroll-alb-5xx");
        AlertSignal ok = AlertPayloads.cloudwatch(json.readTree(cloudwatchAlarm("OK")));
        assertThat(ok.firing()).isFalse();
        assertThat(ok.endsAt()).isNotNull();
        assertThat(AlertPayloads.cloudwatch(json.readTree(cloudwatchAlarm("INSUFFICIENT_DATA")))).isNull();
        assertThatThrownBy(() -> AlertPayloads.cloudwatch(json.readTree("{\"foo\":1}"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void genericFormatSingleAndBatch() throws Exception {
        AlertSignal one = AlertPayloads.generic(json.readTree("{\"title\":\"Disk full on erp-app-01\",\"severity\":\"error\",\"service\":\"ERP\","
                + "\"ci\":\"erp-app-01\",\"labels\":{\"mount\":\"/var\"}}")).get(0);
        assertThat(one.severity()).isEqualTo(AlertSeverity.MAJOR);
        assertThat(one.firing()).isTrue();
        assertThat(one.fingerprint()).hasSize(64);
        List<AlertSignal> batch = AlertPayloads.generic(json.readTree("{\"alerts\":[{\"title\":\"A check\",\"fingerprint\":\"x1\",\"status\":\"resolved\"},"
                + "{\"title\":\"B check\"}]}"));
        assertThat(batch).extracting(AlertSignal::fingerprint).first().isEqualTo("x1");
        assertThat(batch.get(0).firing()).isFalse();
        JsonNode bad = json.readTree("{\"title\":\"x\"}");
        assertThatThrownBy(() -> AlertPayloads.generic(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlertPayloads.generic(json.readTree("{\"title\":\"Valid title\",\"severity\":\"apocalyptic\"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void helpers() {
        assertThat(AlertPayloads.host("payroll-app-01:8080")).isEqualTo("payroll-app-01");
        assertThat(AlertPayloads.host("https://payroll.example.com/health")).isEqualTo("payroll.example.com");
        assertThat(AlertPayloads.host("[2001:db8::1]:9100")).isEqualTo("2001:db8::1");
        assertThat(AlertPayloads.host("vpn-gw-02")).isEqualTo("vpn-gw-02");
        assertThat(AlertSeverity.parse("warn")).isEqualTo(AlertSeverity.WARNING);
        assertThat(AlertSeverity.parse("P1")).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(AlertSeverity.parse("nonsense")).isNull();
        assertThat(AlertSeverity.CRITICAL.atLeast(AlertSeverity.MAJOR)).isTrue();
        assertThat(AlertSeverity.WARNING.atLeast(AlertSeverity.MAJOR)).isFalse();
        assertThat(SnsGateway.topicRegion("arn:aws:sns:ap-south-1:123456789012:payroll-alarms")).isEqualTo("ap-south-1");
        assertThat(SnsGateway.topicRegion("arn:aws:sqs:ap-south-1:123456789012:q")).isNull();
        assertThat(SnsGateway.allowedUrl("https://sns.ap-south-1.amazonaws.com/SimpleNotificationService-abc.pem", "ap-south-1", true)).isTrue();
        assertThat(SnsGateway.allowedUrl("http://sns.ap-south-1.amazonaws.com/x.pem", "ap-south-1", true)).isFalse();
        assertThat(SnsGateway.allowedUrl("https://sns.us-east-1.amazonaws.com/x.pem", "ap-south-1", true)).isFalse();
        assertThat(SnsGateway.allowedUrl("https://sns.ap-south-1.amazonaws.com.evil.example/x.pem", "ap-south-1", true)).isFalse();
        assertThat(SnsGateway.allowedUrl("https://user@sns.ap-south-1.amazonaws.com/x.pem", "ap-south-1", true)).isFalse();
        assertThat(SnsGateway.allowedUrl("https://sns.ap-south-1.amazonaws.com:8443/x.pem", "ap-south-1", true)).isFalse();
        assertThat(SnsGateway.allowedUrl("https://sns.ap-south-1.amazonaws.com/x.txt", "ap-south-1", true)).isFalse();
        MatchCriteria m = new MatchCriteria(Map.of("job", "backup|snapshot", "env", "*"), null, List.of(AlertSeverity.WARNING), null, null, null, null);
        Alert a = new Alert();
        a.setTitle("x");
        a.setSeverity(AlertSeverity.WARNING);
        a.setSourceKind(Alert.SourceKind.PROMETHEUS);
        a.setLabels(new java.util.HashMap<>(Map.of("job", "Snapshot", "env", "prod")));
        assertThat(m.matches(a)).isTrue();
        a.getLabels().remove("env");
        assertThat(m.matches(a)).isFalse();
        a.getLabels().put("env", "prod");
        a.setSeverity(AlertSeverity.CRITICAL);
        assertThat(m.matches(a)).isFalse();
        assertThat(MatchCriteria.ANY.matches(a)).isTrue();
    }
}
