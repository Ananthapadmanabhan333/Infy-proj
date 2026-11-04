package com.nexus360.observability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Normalised telemetry, independent of the wire format (OTLP/HTTP JSON, Prometheus HTTP API, prepared cloud adapters). Attribute
 * maps are flat {@code key → string} maps (arrays and key/value lists are rendered as JSON text).
 */
public final class TelemetryModel {

    private TelemetryModel() {}

    public enum MetricType { GAUGE, SUM, HISTOGRAM, SUMMARY }

    /**
     * One metric data point. Gauges and sums: {@code value}; histograms and summaries: {@code value} = mean ({@code sum / count}),
     * plus {@code count}, {@code sum} and, when sent, {@code min} / {@code max}.
     */
    public record MetricPoint(String name, String unit, MetricType type, Map<String, String> attributes, Instant timestamp, double value,
                              Long count, Double sum, Double min, Double max) {}

    /** {@code severity} is already normalised to TRACE, DEBUG, INFO, WARN, ERROR or FATAL. */
    public record LogRecord(Instant timestamp, Instant observedAt, String severity, int severityNumber, String severityText, String body,
                            boolean bodyTruncated, Map<String, String> attributes, int droppedAttributes, String traceId, String spanId) {}

    public record Span(String traceId, String spanId, String parentSpanId, String name, String kind, Instant start, Instant end,
                       String statusCode, String statusMessage, Map<String, String> attributes, int droppedAttributes) {}

    /** Items that share one resource (OTLP {@code resourceMetrics} / {@code resourceLogs} / {@code resourceSpans} entry). */
    public record ResourceBatch<T>(Map<String, String> resource, List<T> items) {}

    /** Parse result: valid items per resource, the number of rejected items and the first error messages. */
    public static final class Parsed<T> {
        private final List<ResourceBatch<T>> batches = new ArrayList<>();
        private int rejected;
        private final List<String> errors = new ArrayList<>();

        public List<ResourceBatch<T>> batches() {
            return batches;
        }

        public int rejected() {
            return rejected;
        }

        public List<String> errors() {
            return errors;
        }

        public int accepted() {
            return batches.stream().mapToInt(b -> b.items().size()).sum();
        }

        public void reject(int n, String message) {
            if (n <= 0) {
                return;
            }
            rejected += n;
            if (message != null && errors.size() < 5 && !errors.contains(message)) {
                errors.add(message);
            }
        }

        public String errorMessage() {
            return String.join("; ", errors);
        }
    }

    /** Outcome of writing a parsed batch. */
    public record IngestResult(int accepted, int rejected, String errorMessage) {}
}
