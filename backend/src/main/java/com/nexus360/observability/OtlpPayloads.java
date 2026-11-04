package com.nexus360.observability;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Builds OTLP/HTTP JSON export requests (the exact wire format of {@code /api/otel/v1/*}). Used by the simulation scenarios, which
 * send their data through the same ingestion path as real exporters, and by tests.
 */
public final class OtlpPayloads {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private OtlpPayloads() {}

    public sealed interface Metric permits Gauge, Sum, Histogram {}

    public record Gauge(String name, String unit, Map<String, String> attributes, Instant time, double value) implements Metric {}

    public record Sum(String name, String unit, Map<String, String> attributes, Instant time, double value, boolean monotonic) implements Metric {}

    public record Histogram(String name, String unit, Map<String, String> attributes, Instant time, long count, double sum, double min, double max)
            implements Metric {}

    /** {@code severityNumber}: OTLP 1–24 (9 INFO, 13 WARN, 17 ERROR, 21 FATAL). */
    public record Log(Instant time, int severityNumber, String severityText, String body, Map<String, String> attributes, String traceId, String spanId) {}

    /** {@code kind}: 1 INTERNAL, 2 SERVER, 3 CLIENT; {@code statusCode}: 0 UNSET, 1 OK, 2 ERROR. */
    public record Span(String traceId, String spanId, String parentSpanId, String name, int kind, Instant start, Instant end, int statusCode,
                       String statusMessage, Map<String, String> attributes) {}

    public static ObjectNode metrics(Map<String, String> resource, List<? extends Metric> metrics) {
        ObjectNode root = F.objectNode();
        ObjectNode rm = root.putArray("resourceMetrics").addObject();
        rm.putObject("resource").set("attributes", attributes(resource));
        ObjectNode sm = rm.putArray("scopeMetrics").addObject();
        sm.putObject("scope").put("name", "sa-simulation").put("version", "1.0");
        ArrayNode out = sm.putArray("metrics");
        for (Metric m : metrics) {
            ObjectNode metric = out.addObject();
            switch (m) {
                case Gauge g -> {
                    metric.put("name", g.name());
                    if (g.unit() != null) {
                        metric.put("unit", g.unit());
                    }
                    ObjectNode p = metric.putObject("gauge").putArray("dataPoints").addObject();
                    p.set("attributes", attributes(g.attributes()));
                    p.put("timeUnixNano", nanos(g.time()));
                    p.put("asDouble", g.value());
                }
                case Sum s -> {
                    metric.put("name", s.name());
                    if (s.unit() != null) {
                        metric.put("unit", s.unit());
                    }
                    ObjectNode sum = metric.putObject("sum");
                    sum.put("aggregationTemporality", 1);
                    sum.put("isMonotonic", s.monotonic());
                    ObjectNode p = sum.putArray("dataPoints").addObject();
                    p.set("attributes", attributes(s.attributes()));
                    p.put("startTimeUnixNano", nanos(s.time().minusSeconds(60)));
                    p.put("timeUnixNano", nanos(s.time()));
                    p.put("asDouble", s.value());
                }
                case Histogram h -> {
                    metric.put("name", h.name());
                    if (h.unit() != null) {
                        metric.put("unit", h.unit());
                    }
                    ObjectNode hist = metric.putObject("histogram");
                    hist.put("aggregationTemporality", 1);
                    ObjectNode p = hist.putArray("dataPoints").addObject();
                    p.set("attributes", attributes(h.attributes()));
                    p.put("startTimeUnixNano", nanos(h.time().minusSeconds(60)));
                    p.put("timeUnixNano", nanos(h.time()));
                    p.put("count", String.valueOf(h.count()));
                    p.put("sum", h.sum());
                    p.put("min", h.min());
                    p.put("max", h.max());
                }
            }
        }
        return root;
    }

    public static ObjectNode logs(Map<String, String> resource, List<Log> logs) {
        ObjectNode root = F.objectNode();
        ObjectNode rl = root.putArray("resourceLogs").addObject();
        rl.putObject("resource").set("attributes", attributes(resource));
        ObjectNode sl = rl.putArray("scopeLogs").addObject();
        sl.putObject("scope").put("name", "sa-simulation");
        ArrayNode out = sl.putArray("logRecords");
        for (Log l : logs) {
            ObjectNode r = out.addObject();
            r.put("timeUnixNano", nanos(l.time()));
            r.put("observedTimeUnixNano", nanos(l.time()));
            r.put("severityNumber", l.severityNumber());
            if (l.severityText() != null) {
                r.put("severityText", l.severityText());
            }
            r.putObject("body").put("stringValue", l.body());
            r.set("attributes", attributes(l.attributes()));
            if (l.traceId() != null) {
                r.put("traceId", l.traceId());
            }
            if (l.spanId() != null) {
                r.put("spanId", l.spanId());
            }
        }
        return root;
    }

    public static ObjectNode traces(Map<String, String> resource, List<Span> spans) {
        ObjectNode root = F.objectNode();
        ObjectNode rs = root.putArray("resourceSpans").addObject();
        rs.putObject("resource").set("attributes", attributes(resource));
        ObjectNode ss = rs.putArray("scopeSpans").addObject();
        ss.putObject("scope").put("name", "sa-simulation");
        ArrayNode out = ss.putArray("spans");
        for (Span s : spans) {
            ObjectNode n = out.addObject();
            n.put("traceId", s.traceId());
            n.put("spanId", s.spanId());
            if (s.parentSpanId() != null) {
                n.put("parentSpanId", s.parentSpanId());
            }
            n.put("name", s.name());
            n.put("kind", s.kind());
            n.put("startTimeUnixNano", nanos(s.start()));
            n.put("endTimeUnixNano", nanos(s.end()));
            n.set("attributes", attributes(s.attributes()));
            ObjectNode status = n.putObject("status");
            status.put("code", s.statusCode());
            if (s.statusMessage() != null) {
                status.put("message", s.statusMessage());
            }
        }
        return root;
    }

    public static ArrayNode attributes(Map<String, String> attributes) {
        ArrayNode a = F.arrayNode();
        if (attributes != null) {
            attributes.forEach((k, v) -> {
                ObjectNode kv = a.addObject();
                kv.put("key", k);
                kv.putObject("value").put("stringValue", v);
            });
        }
        return a;
    }

    /** Nanoseconds since the epoch as a decimal string (OTLP JSON int64 encoding). */
    public static String nanos(Instant t) {
        return java.math.BigInteger.valueOf(t.getEpochSecond()).multiply(java.math.BigInteger.valueOf(1_000_000_000L))
                .add(java.math.BigInteger.valueOf(t.getNano())).toString();
    }
}
