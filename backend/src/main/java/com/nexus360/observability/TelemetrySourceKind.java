package com.nexus360.observability;

/** Telemetry source kinds (RELEASE4_SPEC §4.1): OTLP push and pull adapters. */
public enum TelemetrySourceKind {
    /** OTLP/HTTP JSON push to {@code /api/otel/v1/*} with the source token. */
    OTLP,
    /** Prometheus HTTP API (PromQL over HTTP, bearer/basic): real client and scheduled pull. */
    PROMETHEUS_API,
    /** Prepared: Grafana data source proxy query ({@code /api/ds/query}). */
    GRAFANA,
    /** Prepared: CloudWatch GetMetricData. */
    CLOUDWATCH,
    /** Prepared: Azure Monitor metrics REST API. */
    AZURE_MONITOR,
    /** Prepared: Google Cloud Monitoring timeSeries.list. */
    GCP_MONITORING
}
