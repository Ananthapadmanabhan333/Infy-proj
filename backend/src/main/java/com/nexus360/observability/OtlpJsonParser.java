package com.nexus360.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus360.observability.TelemetryModel.LogRecord;
import com.nexus360.observability.TelemetryModel.MetricPoint;
import com.nexus360.observability.TelemetryModel.MetricType;
import com.nexus360.observability.TelemetryModel.Parsed;
import com.nexus360.observability.TelemetryModel.ResourceBatch;
import com.nexus360.observability.TelemetryModel.Span;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parser for the OpenTelemetry protocol's JSON encoding (OTLP/HTTP, {@code Content-Type: application/json}) of
 * {@code ExportMetricsServiceRequest}, {@code ExportLogsServiceRequest} and {@code ExportTraceServiceRequest}. Follows the OTLP JSON
 * rules: lowerCamelCase field names, trace and span ids as hex strings, 64-bit integers as decimal strings or numbers, enums as
 * integers (enum names are accepted too). Invalid items are counted as rejected (partial success), never guessed.
 *
 * <p>Limits: at most {@code maxItems} data points / log records / spans per request (the rest is rejected), 64 resource attributes,
 * 64 attributes per log record or span and 32 per metric point (the rest dropped and counted), keys ≤ 120 and values ≤ 1024
 * characters, log bodies ≤ 8 KB (truncated and flagged).
 */
public final class OtlpJsonParser {

    public static final int MAX_BODY_CHARS = 8192;
    public static final int MAX_ITEM_ATTRIBUTES = 64;
    public static final int MAX_POINT_ATTRIBUTES = 32;
    public static final int MAX_RESOURCE_ATTRIBUTES = 64;
    public static final int MAX_KEY = 120;
    public static final int MAX_VALUE = 1024;
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^[0-9a-fA-F]{16}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final int maxItems;
    private final Instant earliest;
    private final Instant latest;

    /** Items with a timestamp outside [{@code earliest}, {@code latest}] are rejected (retention and clock-skew guard). */
    public OtlpJsonParser(int maxItems, Instant earliest, Instant latest) {
        this.maxItems = maxItems;
        this.earliest = earliest;
        this.latest = latest;
    }

    // ------------------------------------------------------------------ metrics

    public Parsed<MetricPoint> metrics(JsonNode root) {
        requireObject(root);
        Parsed<MetricPoint> out = new Parsed<>();
        int[] budget = {maxItems};
        for (JsonNode rm : array(root, "resourceMetrics")) {
            Map<String, String> resource = attributes(rm.path("resource").path("attributes"), MAX_RESOURCE_ATTRIBUTES, null);
            List<MetricPoint> points = new ArrayList<>();
            for (JsonNode sm : array(rm, "scopeMetrics")) {
                for (JsonNode m : array(sm, "metrics")) {
                    String name = text(m, "name");
                    String unit = text(m, "unit");
                    if (name == null || name.isBlank() || name.length() > 200) {
                        out.reject(countMetricPoints(m), "metric name missing or longer than 200 characters");
                        continue;
                    }
                    name = name.trim();
                    unit = unit == null || unit.isBlank() ? null : truncate(unit.trim(), 40);
                    if (m.has("gauge")) {
                        numberPoints(m.path("gauge"), name, unit, MetricType.GAUGE, points, out, budget);
                    } else if (m.has("sum")) {
                        numberPoints(m.path("sum"), name, unit, MetricType.SUM, points, out, budget);
                    } else if (m.has("histogram")) {
                        distributionPoints(m.path("histogram"), name, unit, MetricType.HISTOGRAM, points, out, budget);
                    } else if (m.has("exponentialHistogram")) {
                        distributionPoints(m.path("exponentialHistogram"), name, unit, MetricType.HISTOGRAM, points, out, budget);
                    } else if (m.has("summary")) {
                        distributionPoints(m.path("summary"), name, unit, MetricType.SUMMARY, points, out, budget);
                    } else {
                        out.reject(1, "metric " + truncate(name, 60) + " has no gauge, sum, histogram or summary data");
                    }
                }
            }
            if (!points.isEmpty()) {
                out.batches().add(new ResourceBatch<>(resource, points));
            }
        }
        return out;
    }

    private void numberPoints(JsonNode data, String name, String unit, MetricType type, List<MetricPoint> points, Parsed<MetricPoint> out,
                              int[] budget) {
        for (JsonNode p : array(data, "dataPoints")) {
            if (budget[0] <= 0) {
                out.reject(1, "more than " + maxItems + " data points in one request");
                continue;
            }
            Instant ts = time(p, "timeUnixNano");
            if (ts == null) {
                out.reject(1, "data point without a valid timeUnixNano");
                continue;
            }
            if (outOfRange(ts)) {
                out.reject(1, "data point timestamp outside the accepted range");
                continue;
            }
            Double value = p.has("asDouble") ? number(p.get("asDouble")) : p.has("asInt") ? number(p.get("asInt")) : null;
            if (value == null || value.isNaN() || value.isInfinite()) {
                out.reject(1, "data point without a finite asDouble/asInt value");
                continue;
            }
            budget[0]--;
            points.add(new MetricPoint(name, unit, type, attributes(p.path("attributes"), MAX_POINT_ATTRIBUTES, null), ts, value, null, null, null,
                    null));
        }
    }

    private void distributionPoints(JsonNode data, String name, String unit, MetricType type, List<MetricPoint> points, Parsed<MetricPoint> out,
                                    int[] budget) {
        for (JsonNode p : array(data, "dataPoints")) {
            if (budget[0] <= 0) {
                out.reject(1, "more than " + maxItems + " data points in one request");
                continue;
            }
            Instant ts = time(p, "timeUnixNano");
            if (ts == null) {
                out.reject(1, "data point without a valid timeUnixNano");
                continue;
            }
            if (outOfRange(ts)) {
                out.reject(1, "data point timestamp outside the accepted range");
                continue;
            }
            Double countValue = number(p.get("count"));
            if (countValue == null || countValue < 0 || countValue.isNaN()) {
                out.reject(1, "histogram/summary point without a valid count");
                continue;
            }
            long count = countValue.longValue();
            Double sum = number(p.get("sum"));
            if (count > 0 && (sum == null || sum.isNaN() || sum.isInfinite())) {
                out.reject(1, "histogram/summary point without a finite sum (the mean cannot be computed)");
                continue;
            }
            budget[0]--;
            if (count == 0) {
                // A valid point without observations: accepted, nothing to store (a mean of no observations is undefined).
                continue;
            }
            Double min = number(p.get("min"));
            Double max = number(p.get("max"));
            points.add(new MetricPoint(name, unit, type, attributes(p.path("attributes"), MAX_POINT_ATTRIBUTES, null), ts, sum / count, count, sum,
                    finite(min), finite(max)));
        }
    }

    private static int countMetricPoints(JsonNode m) {
        int n = 0;
        for (String k : List.of("gauge", "sum", "histogram", "exponentialHistogram", "summary")) {
            n += array(m.path(k), "dataPoints").size();
        }
        return Math.max(1, n);
    }

    // ------------------------------------------------------------------ logs

    public Parsed<LogRecord> logs(JsonNode root) {
        requireObject(root);
        Parsed<LogRecord> out = new Parsed<>();
        int budget = maxItems;
        for (JsonNode rl : array(root, "resourceLogs")) {
            Map<String, String> resource = attributes(rl.path("resource").path("attributes"), MAX_RESOURCE_ATTRIBUTES, null);
            List<LogRecord> records = new ArrayList<>();
            for (JsonNode sl : array(rl, "scopeLogs")) {
                for (JsonNode r : array(sl, "logRecords")) {
                    if (budget <= 0) {
                        out.reject(1, "more than " + maxItems + " log records in one request");
                        continue;
                    }
                    Instant ts = time(r, "timeUnixNano");
                    Instant observed = time(r, "observedTimeUnixNano");
                    Instant at = ts != null ? ts : observed;
                    if (at == null) {
                        out.reject(1, "log record without timeUnixNano or observedTimeUnixNano");
                        continue;
                    }
                    if (outOfRange(at)) {
                        out.reject(1, "log record timestamp outside the accepted range");
                        continue;
                    }
                    String traceId = optionalId(text(r, "traceId"), TRACE_ID);
                    String spanId = optionalId(text(r, "spanId"), SPAN_ID);
                    if ("!".equals(traceId) || "!".equals(spanId)) {
                        out.reject(1, "log record with an invalid traceId/spanId (hex expected)");
                        continue;
                    }
                    String body = anyValue(r.get("body"));
                    body = body == null ? "" : body;
                    boolean truncated = body.length() > MAX_BODY_CHARS;
                    if (truncated) {
                        body = body.substring(0, MAX_BODY_CHARS);
                    }
                    int severityNumber = r.path("severityNumber").isNumber() ? r.path("severityNumber").asInt() : severityNumberFromName(text(r,
                            "severityNumber"));
                    String severityText = text(r, "severityText");
                    int[] dropped = {r.path("droppedAttributesCount").asInt(0)};
                    Map<String, String> attrs = attributes(r.path("attributes"), MAX_ITEM_ATTRIBUTES, dropped);
                    budget--;
                    records.add(new LogRecord(at, observed, severity(severityNumber, severityText), Math.max(0, Math.min(24, severityNumber)),
                            severityText == null ? null : truncate(severityText, 40), body, truncated, attrs, dropped[0], traceId, spanId));
                }
            }
            if (!records.isEmpty()) {
                out.batches().add(new ResourceBatch<>(resource, records));
            }
        }
        return out;
    }

    /** OTLP severity numbers: 1-4 TRACE, 5-8 DEBUG, 9-12 INFO, 13-16 WARN, 17-20 ERROR, 21-24 FATAL; else the text; else INFO. */
    public static String severity(int number, String text) {
        if (number >= 1 && number <= 24) {
            return switch ((number - 1) / 4) {
                case 0 -> "TRACE";
                case 1 -> "DEBUG";
                case 2 -> "INFO";
                case 3 -> "WARN";
                case 4 -> "ERROR";
                default -> "FATAL";
            };
        }
        String t = text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
        if (t.startsWith("TRACE")) {
            return "TRACE";
        }
        if (t.startsWith("DEBUG")) {
            return "DEBUG";
        }
        if (t.startsWith("WARN")) {
            return "WARN";
        }
        if (t.startsWith("ERR")) {
            return "ERROR";
        }
        if (t.startsWith("FATAL") || t.startsWith("CRIT") || t.startsWith("EMERG") || t.startsWith("ALERT")) {
            return "FATAL";
        }
        return "INFO";
    }

    private static int severityNumberFromName(String name) {
        if (name == null || !name.startsWith("SEVERITY_NUMBER_")) {
            return 0;
        }
        String s = name.substring("SEVERITY_NUMBER_".length());
        String base = s.replaceAll("[0-9]", "");
        String digits = s.replaceAll("[^0-9]", "");
        int offset = digits.isEmpty() ? 0 : Math.max(0, Math.min(3, Integer.parseInt(digits) - 1));
        return switch (base) {
            case "TRACE" -> 1 + offset;
            case "DEBUG" -> 5 + offset;
            case "INFO" -> 9 + offset;
            case "WARN" -> 13 + offset;
            case "ERROR" -> 17 + offset;
            case "FATAL" -> 21 + offset;
            default -> 0;
        };
    }

    // ------------------------------------------------------------------ traces

    public Parsed<Span> traces(JsonNode root) {
        requireObject(root);
        Parsed<Span> out = new Parsed<>();
        int budget = maxItems;
        for (JsonNode rs : array(root, "resourceSpans")) {
            Map<String, String> resource = attributes(rs.path("resource").path("attributes"), MAX_RESOURCE_ATTRIBUTES, null);
            List<Span> spans = new ArrayList<>();
            for (JsonNode ss : array(rs, "scopeSpans")) {
                for (JsonNode s : array(ss, "spans")) {
                    if (budget <= 0) {
                        out.reject(1, "more than " + maxItems + " spans in one request");
                        continue;
                    }
                    String traceId = text(s, "traceId");
                    String spanId = text(s, "spanId");
                    if (traceId == null || !TRACE_ID.matcher(traceId).matches() || traceId.chars().allMatch(c -> c == '0')) {
                        out.reject(1, "span with a missing or invalid traceId (32 hex characters, not all zero)");
                        continue;
                    }
                    if (spanId == null || !SPAN_ID.matcher(spanId).matches() || spanId.chars().allMatch(c -> c == '0')) {
                        out.reject(1, "span with a missing or invalid spanId (16 hex characters, not all zero)");
                        continue;
                    }
                    String parent = optionalId(text(s, "parentSpanId"), SPAN_ID);
                    if ("!".equals(parent)) {
                        out.reject(1, "span with an invalid parentSpanId");
                        continue;
                    }
                    String name = text(s, "name");
                    if (name == null || name.isBlank()) {
                        out.reject(1, "span without a name");
                        continue;
                    }
                    Instant start = time(s, "startTimeUnixNano");
                    Instant end = time(s, "endTimeUnixNano");
                    if (start == null || end == null || end.isBefore(start)) {
                        out.reject(1, "span without valid start/end times (end must not precede start)");
                        continue;
                    }
                    if (outOfRange(start)) {
                        out.reject(1, "span start time outside the accepted range");
                        continue;
                    }
                    JsonNode status = s.path("status");
                    int[] dropped = {s.path("droppedAttributesCount").asInt(0)};
                    budget--;
                    spans.add(new Span(traceId.toLowerCase(Locale.ROOT), spanId.toLowerCase(Locale.ROOT), parent, truncate(name.trim(), 300),
                            spanKind(s.get("kind")), start, end, statusCode(status.get("code")), truncate(text(status, "message"), 500),
                            attributes(s.path("attributes"), MAX_ITEM_ATTRIBUTES, dropped), dropped[0]));
                }
            }
            if (!spans.isEmpty()) {
                out.batches().add(new ResourceBatch<>(resource, spans));
            }
        }
        return out;
    }

    static String spanKind(JsonNode kind) {
        if (kind == null || kind.isNull()) {
            return "UNSPECIFIED";
        }
        if (kind.isNumber()) {
            return switch (kind.asInt()) {
                case 1 -> "INTERNAL";
                case 2 -> "SERVER";
                case 3 -> "CLIENT";
                case 4 -> "PRODUCER";
                case 5 -> "CONSUMER";
                default -> "UNSPECIFIED";
            };
        }
        String k = kind.asText("").toUpperCase(Locale.ROOT).replace("SPAN_KIND_", "");
        return List.of("INTERNAL", "SERVER", "CLIENT", "PRODUCER", "CONSUMER").contains(k) ? k : "UNSPECIFIED";
    }

    static String statusCode(JsonNode code) {
        if (code == null || code.isNull()) {
            return "UNSET";
        }
        if (code.isNumber()) {
            return switch (code.asInt()) {
                case 1 -> "OK";
                case 2 -> "ERROR";
                default -> "UNSET";
            };
        }
        String c = code.asText("").toUpperCase(Locale.ROOT).replace("STATUS_CODE_", "");
        return c.equals("OK") || c.equals("ERROR") ? c : "UNSET";
    }

    // ------------------------------------------------------------------ helpers

    private boolean outOfRange(Instant ts) {
        return (earliest != null && ts.isBefore(earliest)) || (latest != null && ts.isAfter(latest));
    }

    private static void requireObject(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("The request body must be an OTLP JSON object");
        }
    }

    private static List<JsonNode> array(JsonNode node, String field) {
        JsonNode a = node == null ? null : node.get(field);
        if (a == null || !a.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>(a.size());
        a.forEach(out::add);
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** {@code null} when absent or empty, {@code "!"} when present but invalid, else the lower-case id. */
    private static String optionalId(String id, Pattern pattern) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        if (!pattern.matcher(id).matches()) {
            return "!";
        }
        return id.chars().allMatch(c -> c == '0') ? null : id.toLowerCase(Locale.ROOT);
    }

    /** Nanoseconds since the epoch, as a decimal string or a number; {@code 0}/absent = unknown. */
    static Instant time(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            BigInteger nanos = v.isNumber() ? v.bigIntegerValue() : new BigInteger(v.asText().trim());
            if (nanos.signum() <= 0) {
                return null;
            }
            BigInteger[] parts = nanos.divideAndRemainder(BigInteger.valueOf(1_000_000_000L));
            return Instant.ofEpochSecond(parts[0].longValueExact(), parts[1].longValue());
        } catch (NumberFormatException | ArithmeticException | java.time.DateTimeException e) {
            return null;
        }
    }

    private static Double number(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        String t = v.asText().trim();
        try {
            return switch (t) {
                case "NaN" -> Double.NaN;
                case "Infinity" -> Double.POSITIVE_INFINITY;
                case "-Infinity" -> Double.NEGATIVE_INFINITY;
                default -> Double.parseDouble(t);
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double finite(Double d) {
        return d == null || d.isNaN() || d.isInfinite() ? null : d;
    }

    /** OTLP {@code KeyValue[]} → flat map; {@code dropped[0]} is incremented for attributes beyond {@code max}. */
    static Map<String, String> attributes(JsonNode list, int max, int[] dropped) {
        Map<String, String> out = new LinkedHashMap<>();
        if (list == null || !list.isArray()) {
            return out;
        }
        for (JsonNode kv : list) {
            String key = text(kv, "key");
            if (key == null || key.isBlank()) {
                continue;
            }
            if (out.size() >= max) {
                if (dropped != null) {
                    dropped[0]++;
                }
                continue;
            }
            String value = anyValue(kv.get("value"));
            out.put(truncate(key.trim(), MAX_KEY), value == null ? "" : truncate(value, MAX_VALUE));
        }
        return out;
    }

    /** OTLP {@code AnyValue} rendered as text (arrays and key/value lists as JSON). */
    static String anyValue(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.has("stringValue")) {
            return v.get("stringValue").asText();
        }
        if (v.has("boolValue")) {
            return String.valueOf(v.get("boolValue").asBoolean());
        }
        if (v.has("intValue")) {
            return v.get("intValue").asText();
        }
        if (v.has("doubleValue")) {
            return v.get("doubleValue").asText();
        }
        if (v.has("bytesValue")) {
            return v.get("bytesValue").asText();
        }
        if (v.has("arrayValue")) {
            ArrayNode a = JSON.createArrayNode();
            for (JsonNode item : v.path("arrayValue").path("values")) {
                a.add(anyValue(item));
            }
            return a.toString();
        }
        if (v.has("kvlistValue")) {
            ObjectNode o = JSON.createObjectNode();
            for (JsonNode kv : v.path("kvlistValue").path("values")) {
                String key = text(kv, "key");
                if (key != null) {
                    o.put(key, anyValue(kv.get("value")));
                }
            }
            return o.toString();
        }
        return v.isObject() && v.isEmpty() ? null : v.toString();
    }

    static String truncate(String v, int max) {
        if (v == null || v.length() <= max) {
            return v;
        }
        return v.substring(0, max);
    }

    /** Number of bytes of a string in UTF-8 (for size checks in tests and builders). */
    static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
