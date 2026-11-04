package com.nexus360.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.security.Hashing;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsers that turn monitoring payloads into {@link AlertSignal}s (pure functions, unit-tested with real-shaped samples):
 * Prometheus Alertmanager webhook (version 4), Grafana alerting webhook (unified alerting, plus the legacy rule format),
 * AWS CloudWatch alarm state-change messages (the {@code Message} of an SNS notification) and the generic SA JSON format.
 * Malformed payloads raise {@link IllegalArgumentException} with a safe message.
 */
public final class AlertPayloads {

    public static final int MAX_ALERTS = 200;
    private static final DateTimeFormatter CLOUDWATCH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final Pattern TAG = Pattern.compile("(?i)\\b(severity|service|ci)\\s*[:=]\\s*([A-Za-z0-9_.-]+)");
    private static final Pattern ARN_REGION = Pattern.compile("^arn:aws[a-z-]*:[a-z0-9]+:([a-z0-9-]+):(\\d{12}):.*$");

    private AlertPayloads() {}

    // ------------------------------------------------------------------ Prometheus Alertmanager

    /** Alertmanager webhook: {@code {version, groupKey, status, receiver, groupLabels, commonLabels, commonAnnotations, externalURL, alerts[]}}. */
    public static List<AlertSignal> alertmanager(JsonNode root) {
        JsonNode alerts = root.path("alerts");
        if (!alerts.isArray()) {
            throw new IllegalArgumentException("Alertmanager payload without an alerts array");
        }
        if (alerts.size() > MAX_ALERTS) {
            throw new IllegalArgumentException("At most " + MAX_ALERTS + " alerts per notification");
        }
        String groupKey = text(root, "groupKey");
        List<AlertSignal> out = new ArrayList<>();
        for (JsonNode a : alerts) {
            Map<String, String> labels = strings(a.path("labels"), 500);
            Map<String, String> annotations = strings(a.path("annotations"), 2000);
            String status = text(a, "status") == null ? text(root, "status") : text(a, "status");
            if (status == null || !(status.equals("firing") || status.equals("resolved"))) {
                throw new IllegalArgumentException("Alert status must be firing or resolved");
            }
            String alertname = labels.get("alertname");
            String instance = labels.get("instance");
            String title = firstNonBlank(annotations.get("summary"), alertname == null ? null : alertname + (instance == null ? "" : " on " + instance),
                    "Prometheus alert");
            String fingerprint = firstNonBlank(text(a, "fingerprint"), labelFingerprint(labels), null);
            out.add(new AlertSignal(fingerprint, groupKey, title, firstNonBlank(annotations.get("description"), annotations.get("message"), null),
                    AlertSeverity.parse(firstNonBlank(labels.get("severity"), labels.get("priority"), null)), status.equals("firing"), labels, annotations,
                    url(text(a, "generatorURL")), firstNonBlank(labels.get("service"), labels.get("service_code"), null),
                    firstNonBlank(labels.get("ci"), host(instance), null), time(text(a, "startsAt")), time(text(a, "endsAt"))));
        }
        return out;
    }

    // ------------------------------------------------------------------ Grafana

    /**
     * Grafana alerting webhook. Unified alerting: {@code {receiver, status, orgId, alerts[{status, labels, annotations, startsAt, endsAt,
     * generatorURL, fingerprint, silenceURL, dashboardURL, panelURL, values, valueString}], groupKey, title, state, message, ...}}.
     * Legacy alerting: {@code {ruleId, ruleName, ruleUrl, state (alerting|ok|no_data|paused|pending), message, evalMatches, tags}}.
     */
    public static List<AlertSignal> grafana(JsonNode root) {
        if (root.path("alerts").isArray()) {
            JsonNode alerts = root.path("alerts");
            if (alerts.size() > MAX_ALERTS) {
                throw new IllegalArgumentException("At most " + MAX_ALERTS + " alerts per notification");
            }
            String groupKey = text(root, "groupKey");
            List<AlertSignal> out = new ArrayList<>();
            for (JsonNode a : alerts) {
                Map<String, String> labels = strings(a.path("labels"), 500);
                Map<String, String> annotations = strings(a.path("annotations"), 2000);
                String status = text(a, "status") == null ? text(root, "status") : text(a, "status");
                if (status == null || !(status.equals("firing") || status.equals("resolved"))) {
                    throw new IllegalArgumentException("Alert status must be firing or resolved");
                }
                String valueString = text(a, "valueString");
                if (valueString != null) {
                    annotations.putIfAbsent("valueString", truncate(valueString, 2000));
                }
                String alertname = labels.get("alertname");
                String title = firstNonBlank(annotations.get("summary"), alertname, "Grafana alert");
                out.add(new AlertSignal(firstNonBlank(text(a, "fingerprint"), labelFingerprint(labels), null), groupKey, title,
                        firstNonBlank(annotations.get("description"), annotations.get("message"), null),
                        AlertSeverity.parse(firstNonBlank(labels.get("severity"), labels.get("priority"), null)), status.equals("firing"), labels,
                        annotations, url(firstNonBlank(text(a, "panelURL"), text(a, "dashboardURL"), text(a, "generatorURL"))),
                        firstNonBlank(labels.get("service"), labels.get("service_code"), null),
                        firstNonBlank(labels.get("ci"), host(labels.get("instance")), null), time(text(a, "startsAt")), time(text(a, "endsAt"))));
            }
            return out;
        }
        if (root.hasNonNull("ruleId") || root.hasNonNull("ruleName")) {
            String state = text(root, "state");
            if (state == null) {
                throw new IllegalArgumentException("Grafana legacy alert without a state");
            }
            if (!state.equals("alerting") && !state.equals("ok")) {
                return List.of(); // no_data, paused, pending: nothing to record
            }
            Map<String, String> labels = strings(root.path("tags"), 500);
            labels.putIfAbsent("alertname", truncate(firstNonBlank(text(root, "ruleName"), "Grafana alert", null), 500));
            String title = firstNonBlank(text(root, "title"), text(root, "ruleName"), "Grafana alert");
            return List.of(new AlertSignal("grafana-rule-" + firstNonBlank(text(root, "ruleId"), Hashing.sha256Hex(title), null), null,
                    title.replaceFirst("^\\[(Alerting|OK)\\]\\s*", ""), text(root, "message"), AlertSeverity.parse(labels.get("severity")),
                    state.equals("alerting"), labels, new LinkedHashMap<>(), url(text(root, "ruleUrl")), labels.get("service"), labels.get("ci"), null,
                    null));
        }
        throw new IllegalArgumentException("Grafana payload without alerts");
    }

    // ------------------------------------------------------------------ AWS CloudWatch (SNS message body)

    /**
     * CloudWatch alarm state change: {@code {AlarmName, AlarmDescription, AWSAccountId, NewStateValue (ALARM|OK|INSUFFICIENT_DATA),
     * NewStateReason, StateChangeTime, Region, AlarmArn, OldStateValue, Trigger {MetricName, Namespace, Dimensions[{name, value}], ...}}}.
     * {@code null} for INSUFFICIENT_DATA. {@code severity:}/{@code service:}/{@code ci:} tags in the alarm description are honoured.
     */
    public static AlertSignal cloudwatch(JsonNode alarm) {
        String name = text(alarm, "AlarmName");
        String state = text(alarm, "NewStateValue");
        if (name == null || state == null) {
            throw new IllegalArgumentException("CloudWatch message without AlarmName and NewStateValue");
        }
        if (!state.equals("ALARM") && !state.equals("OK")) {
            return null;
        }
        String description = text(alarm, "AlarmDescription");
        Map<String, String> tags = new LinkedHashMap<>();
        if (description != null) {
            Matcher m = TAG.matcher(description);
            while (m.find()) {
                tags.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
            }
        }
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("alarmName", truncate(name, 500));
        String arn = text(alarm, "AlarmArn");
        String regionCode = null;
        if (arn != null) {
            Matcher m = ARN_REGION.matcher(arn);
            if (m.matches()) {
                regionCode = m.group(1);
                labels.put("region", regionCode);
            }
        }
        putIfText(labels, "account", text(alarm, "AWSAccountId"));
        JsonNode trigger = alarm.path("Trigger");
        putIfText(labels, "namespace", text(trigger, "Namespace"));
        putIfText(labels, "metricName", text(trigger, "MetricName"));
        for (JsonNode d : trigger.path("Dimensions")) {
            String dn = firstNonBlank(text(d, "name"), text(d, "Name"), null);
            String dv = firstNonBlank(text(d, "value"), text(d, "Value"), null);
            if (dn != null && dv != null && labels.size() < 40) {
                labels.put("dimension." + truncate(dn, 80), truncate(dv, 500));
            }
        }
        tags.forEach(labels::putIfAbsent);
        Map<String, String> annotations = new LinkedHashMap<>();
        putIfText(annotations, "newStateReason", truncate(text(alarm, "NewStateReason"), 2000));
        putIfText(annotations, "oldState", text(alarm, "OldStateValue"));
        putIfText(annotations, "regionName", text(alarm, "Region"));
        String metric = text(trigger, "MetricName") == null ? "" : text(trigger, "Namespace") + " " + text(trigger, "MetricName") + " "
                + firstNonBlank(text(trigger, "ComparisonOperator"), "", null) + " " + firstNonBlank(text(trigger, "Threshold"), "", null);
        String body = firstNonBlank(text(alarm, "NewStateReason"), "", null) + (metric.isBlank() ? "" : "\nMetric: " + metric.trim());
        String console = regionCode == null ? null : "https://console.aws.amazon.com/cloudwatch/home?region=" + regionCode + "#alarmsV2:alarm/"
                + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        Instant at = cloudwatchTime(text(alarm, "StateChangeTime"));
        String title = description == null || description.isBlank() ? name : name + ": " + firstLine(TAG.matcher(description).replaceAll("").trim(), name);
        return new AlertSignal(firstNonBlank(arn, "cloudwatch:" + name, null), arn == null ? null : "cloudwatch:" + arn, truncate(title, 300), body.trim(),
                AlertSeverity.parse(tags.get("severity")), state.equals("ALARM"), labels, annotations, console, tags.get("service"), tags.get("ci"),
                state.equals("ALARM") ? at : null, state.equals("OK") ? at : null);
    }

    // ------------------------------------------------------------------ generic SA JSON

    /**
     * Generic format: one alert object or {@code {"alerts": [...]}}; each {@code {title (required), description?, severity?,
     * status? firing|resolved, fingerprint?, groupKey?, service? (code), ci? (name), labels? {}, annotations? {}, externalUrl?, startsAt?,
     * endsAt?}}. Without a fingerprint the title, service and CI identify the alert.
     */
    public static List<AlertSignal> generic(JsonNode root) {
        JsonNode list = root.has("alerts") ? root.path("alerts") : null;
        if (list != null && !list.isArray()) {
            throw new IllegalArgumentException("alerts must be an array");
        }
        List<JsonNode> items = new ArrayList<>();
        if (list == null) {
            items.add(root);
        } else {
            list.forEach(items::add);
        }
        if (items.isEmpty() || items.size() > MAX_ALERTS) {
            throw new IllegalArgumentException("Send between 1 and " + MAX_ALERTS + " alerts");
        }
        List<AlertSignal> out = new ArrayList<>();
        for (JsonNode a : items) {
            if (!a.isObject()) {
                throw new IllegalArgumentException("Each alert must be a JSON object");
            }
            String title = text(a, "title");
            if (title == null || title.trim().length() < 3) {
                throw new IllegalArgumentException("Each alert needs a title of at least 3 characters");
            }
            String status = text(a, "status") == null ? "firing" : text(a, "status").toLowerCase(Locale.ROOT);
            if (!status.equals("firing") && !status.equals("resolved")) {
                throw new IllegalArgumentException("status must be firing or resolved");
            }
            String severityText = text(a, "severity");
            AlertSeverity severity = AlertSeverity.parse(severityText);
            if (severityText != null && severity == null) {
                throw new IllegalArgumentException("Unknown severity '" + truncate(severityText, 20) + "'");
            }
            String service = text(a, "service");
            String ci = text(a, "ci");
            String fingerprint = firstNonBlank(text(a, "fingerprint"), Hashing.sha256Hex(title.trim().toLowerCase(Locale.ROOT) + "|"
                    + (service == null ? "" : service.toLowerCase(Locale.ROOT)) + "|" + (ci == null ? "" : ci.toLowerCase(Locale.ROOT))), null);
            out.add(new AlertSignal(truncate(fingerprint, 128), truncate(text(a, "groupKey"), 300), truncate(title.trim(), 300),
                    truncate(text(a, "description"), 10_000), severity, status.equals("firing"), strings(a.path("labels"), 500),
                    strings(a.path("annotations"), 2000), url(text(a, "externalUrl")), service, ci, time(text(a, "startsAt")), time(text(a, "endsAt"))));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** Stable fingerprint of a label set (sorted name=value pairs). */
    public static String labelFingerprint(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(labels).forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return Hashing.sha256Hex(sb.toString());
    }

    /** Host part of a Prometheus {@code instance} label ("payroll-app-01:8080" → "payroll-app-01"). */
    static String host(String instance) {
        if (instance == null || instance.isBlank()) {
            return null;
        }
        String v = instance.trim();
        if (v.contains("://")) {
            try {
                return URI.create(v).getHost();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            return end > 0 ? v.substring(1, end) : v;
        }
        int colon = v.lastIndexOf(':');
        return colon > 0 && v.indexOf(':') == colon ? v.substring(0, colon) : v;
    }

    static Map<String, String> strings(JsonNode node, int maxValue) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext() && out.size() < 50) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue() == null || e.getValue().isNull() || e.getValue().isContainerNode()) {
                continue;
            }
            out.put(truncate(e.getKey(), 100), truncate(e.getValue().asText(), maxValue));
        }
        return out;
    }

    static Instant time(String value) {
        if (value == null || value.isBlank() || value.startsWith("0001-01-01")) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException e) {
            return cloudwatchTime(value);
        }
    }

    static Instant cloudwatchTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim(), CLOUDWATCH_TIME).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(value.trim()).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static String url(String v) {
        return v != null && v.matches("^https?://.{1,490}$") ? v : null;
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || v.isContainerNode() || v.asText().isBlank() ? null : v.asText();
    }

    private static void putIfText(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }

    private static String firstLine(String text, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String line = text.lines().findFirst().orElse(text).trim();
        return line.isEmpty() ? fallback : line;
    }

    static String firstNonBlank(String a, String b, String c) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return c;
    }

    static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
