package com.nexus360.simulation;

import com.nexus360.observability.OtlpIngestService.Signal;
import com.nexus360.observability.OtlpPayloads;
import com.nexus360.observability.OtlpPayloads.Gauge;
import com.nexus360.observability.OtlpPayloads.Histogram;
import com.nexus360.observability.OtlpPayloads.Log;
import com.nexus360.observability.OtlpPayloads.Metric;
import com.nexus360.observability.OtlpPayloads.Span;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates one minute of OpenTelemetry-shaped telemetry for a simulated service (OTLP JSON sent through the real ingestion path):
 * request-duration histograms by status code, CPU utilisation, database connection-pool gauges, access/error logs and traces with a
 * server span and a database client span. All numbers come from the {@link Conditions} passed in plus seeded noise.
 */
final class ScenarioTelemetry {

    private ScenarioTelemetry() {}

    /** An OTLP resource: {@code service.name} plus host, environment and version. */
    record Profile(String serviceName, String host, String route, String version, String dbServiceName) {
        Map<String, String> resource() {
            Map<String, String> r = new LinkedHashMap<>();
            r.put("service.name", serviceName);
            r.put("service.namespace", "sa-demo");
            r.put("deployment.environment", "production");
            r.put("host.name", host);
            r.put("telemetry.sdk.name", "sa-simulation");
            if (version != null) {
                r.put("service.version", version);
            }
            return r;
        }
    }

    /** Request rate per minute, mean latency (ms), error ratio (0–1), CPU (0–1). */
    record Conditions(double ratePerMinute, double latencyMs, double errorRatio, double cpu) {}

    /** Connection pool of a database resource. */
    record Pool(String serviceName, String host, double used, double max, double pending, double queryMs) {}

    /** Telemetry collected per resource and sent as one OTLP request per resource and signal ({@link #flush}). */
    static final class Batch {
        private final Map<Map<String, String>, List<Metric>> metrics = new LinkedHashMap<>();
        private final Map<Map<String, String>, List<Log>> logs = new LinkedHashMap<>();
        private final Map<Map<String, String>, List<Span>> spans = new LinkedHashMap<>();

        void metric(Map<String, String> resource, Metric m) {
            metrics.computeIfAbsent(resource, k -> new ArrayList<>()).add(m);
        }

        void log(Map<String, String> resource, Log l) {
            logs.computeIfAbsent(resource, k -> new ArrayList<>()).add(l);
        }

        void span(Map<String, String> resource, Span s) {
            spans.computeIfAbsent(resource, k -> new ArrayList<>()).add(s);
        }

        void flush(SimulationContext ctx) {
            metrics.forEach((resource, list) -> ctx.otlp(Signal.METRICS, OtlpPayloads.metrics(resource, list)));
            logs.forEach((resource, list) -> ctx.otlp(Signal.LOGS, OtlpPayloads.logs(resource, list)));
            spans.forEach((resource, list) -> ctx.otlp(Signal.TRACES, OtlpPayloads.traces(resource, list)));
            metrics.clear();
            logs.clear();
            spans.clear();
        }
    }

    static void emitService(Batch batch, Random r, Profile p, Conditions c, Instant t, boolean withLogs, boolean withTraces) {
        long total = Math.max(1, Math.round(c.ratePerMinute() * (1 + 0.05 * r.nextGaussian())));
        long errors = Math.min(total, Math.round(total * Math.max(0, c.errorRatio())));
        long ok = total - errors;
        double latency = Math.max(1, c.latencyMs() * (1 + 0.04 * r.nextGaussian()));
        List<Metric> metrics = new ArrayList<>();
        if (ok > 0) {
            metrics.add(new Histogram("http.server.request.duration", "ms", attrs("http.route", p.route(), "http.request.method", "GET",
                    "http.response.status_code", "200"), t, ok, ok * latency, latency * 0.4, latency * 2.5));
        }
        if (errors > 0) {
            double errLatency = latency * 1.6;
            metrics.add(new Histogram("http.server.request.duration", "ms", attrs("http.route", p.route(), "http.request.method", "GET",
                    "http.response.status_code", "500"), t, errors, errors * errLatency, errLatency * 0.5, errLatency * 3));
        }
        metrics.add(new Gauge("process.cpu.utilization", "1", Map.of(), t, clamp(c.cpu() + 0.02 * r.nextGaussian(), 0, 1)));
        Map<String, String> resource = p.resource();
        metrics.forEach(m -> batch.metric(resource, m));
        if (withLogs) {
            batch.log(resource, new Log(t, 9, "INFO", "GET " + p.route() + " served " + ok + " requests, mean " + Math.round(latency) + " ms",
                    attrs("http.route", p.route()), null, null));
        }
        if (errors > 0) {
            batch.log(resource, new Log(t.plusSeconds(1), 17, "ERROR", "GET " + p.route() + " failed for " + errors + " requests: HTTP 500 (upstream "
                    + "database timeout)", attrs("http.route", p.route(), "error.type", "DatabaseTimeout"), null, null));
        }
        if (withTraces) {
            List<Span> api = new ArrayList<>();
            List<Span> db = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String traceId = hex(r, 32);
                String serverSpan = hex(r, 16);
                String clientSpan = hex(r, 16);
                boolean failed = errors > 0 && i == 0;
                Instant start = t.plusMillis(5_000L + i * 20_000L);
                long duration = Math.max(2, Math.round(latency * (failed ? 1.6 : 1.0)));
                api.add(new Span(traceId, serverSpan, null, "GET " + p.route(), 2, start, start.plusMillis(duration), failed ? 2 : 0,
                        failed ? "HTTP 500" : null, attrs("http.route", p.route(), "http.response.status_code", failed ? "500" : "200")));
                if (p.dbServiceName() != null) {
                    long dbDuration = Math.max(1, Math.round(duration * 0.6));
                    api.add(new Span(traceId, clientSpan, serverSpan, "SELECT payroll", 3, start.plusMillis(2), start.plusMillis(2 + dbDuration),
                            failed ? 2 : 0, failed ? "connection acquisition timed out" : null, attrs("db.system", "postgresql", "db.operation.name", "SELECT")));
                    db.add(new Span(traceId, hex(r, 16), clientSpan, "postgres query", 2, start.plusMillis(3), start.plusMillis(1 + dbDuration),
                            failed ? 2 : 0, failed ? "remaining connection slots are reserved" : null, attrs("db.system", "postgresql")));
                }
            }
            api.forEach(sp -> batch.span(resource, sp));
            Map<String, String> dbRes = dbResource(p.dbServiceName() == null ? "db" : p.dbServiceName(), "payroll-db-primary");
            db.forEach(sp -> batch.span(dbRes, sp));
        }
    }

    static void emitPool(Batch batch, Random r, Pool pool, Instant t) {
        double used = clamp(pool.used() + 0.8 * r.nextGaussian(), 0, pool.max());
        List<Metric> metrics = List.of(
                new Gauge("db.client.connections.usage", "{connection}", attrs("pool.name", "payroll-pool", "state", "used"), t, Math.round(used)),
                new Gauge("db.client.connections.usage", "{connection}", attrs("pool.name", "payroll-pool", "state", "idle"), t,
                        Math.max(0, Math.round(pool.max() - used))),
                new Gauge("db.client.connections.max", "{connection}", attrs("pool.name", "payroll-pool"), t, pool.max()),
                new Gauge("db.client.connections.pending_requests", "{request}", attrs("pool.name", "payroll-pool"), t, Math.max(0,
                        Math.round(pool.pending() + (pool.pending() > 0 ? r.nextGaussian() : 0)))),
                new Gauge("db.client.operation.duration", "ms", attrs("db.operation.name", "SELECT"), t, Math.max(0.5, pool.queryMs()
                        * (1 + 0.05 * r.nextGaussian()))));
        Map<String, String> resource = dbResource(pool.serviceName(), pool.host());
        metrics.forEach(m -> batch.metric(resource, m));
        if (pool.pending() > 0) {
            batch.log(resource, new Log(t.plusSeconds(2), 21, "FATAL", "FATAL: remaining connection slots are reserved for non-replication superuser "
                    + "connections (" + Math.round(used) + " of " + Math.round(pool.max()) + " in use)", attrs("db.system", "postgresql"), null, null));
            batch.log(resource, new Log(t.plusSeconds(3), 17, "ERROR", "HikariPool-1 - Connection is not available, request timed out after 30000ms",
                    attrs("pool.name", "payroll-pool"), null, null));
        }
    }

    static Map<String, String> dbResource(String serviceName, String host) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("service.name", serviceName);
        r.put("service.namespace", "sa-demo");
        r.put("deployment.environment", "production");
        r.put("host.name", host);
        r.put("db.system", "postgresql");
        r.put("telemetry.sdk.name", "sa-simulation");
        return r;
    }

    static Map<String, String> attrs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    static String hex(Random r, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(Character.forDigit(i == 0 ? 1 + r.nextInt(15) : r.nextInt(16), 16));
        }
        return sb.toString();
    }

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
