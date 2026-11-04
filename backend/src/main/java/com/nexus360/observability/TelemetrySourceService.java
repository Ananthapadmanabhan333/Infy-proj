package com.nexus360.observability;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.integration.SecretCipher;
import com.nexus360.observability.ObservabilityDtos.KindInfo;
import com.nexus360.observability.ObservabilityDtos.PullResult;
import com.nexus360.observability.ObservabilityDtos.SourceDto;
import com.nexus360.observability.ObservabilityDtos.SourceRequest;
import com.nexus360.observability.ObservabilityDtos.SourceSaved;
import com.nexus360.observability.ObservabilityDtos.TestResult;
import com.nexus360.observability.TelemetryAdapter.MetricQuery;
import com.nexus360.observability.TelemetryAdapter.OutboundRequest;
import com.nexus360.observability.TelemetryAdapter.Sample;
import com.nexus360.observability.TelemetryAdapter.SeriesData;
import com.nexus360.observability.TelemetryModel.IngestResult;
import com.nexus360.observability.TelemetryModel.MetricPoint;
import com.nexus360.observability.TelemetryModel.MetricType;
import com.nexus360.observability.TelemetryModel.Parsed;
import com.nexus360.observability.TelemetryModel.ResourceBatch;
import com.nexus360.security.Actor;
import com.nexus360.security.Hashing;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Telemetry sources: OTLP push sources (token {@code otsec_…}, only its SHA-256 hash stored, shown once) and pull sources (Prometheus
 * HTTP API; prepared Grafana / CloudWatch / Azure Monitor / GCP adapters). Outbound credentials are encrypted with {@link SecretCipher}
 * and never returned or logged. A source becomes CONNECTED only after a successful test call or (OTLP) the first authenticated
 * delivery; every configuration change, rotation and test is audited.
 */
@Slf4j
@Service
public class TelemetrySourceService {

    static final String TOKEN_PREFIX = "otsec_";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    public static final Map<String, String> OTLP_PATHS = Map.of("metrics", "/api/otel/v1/metrics", "logs", "/api/otel/v1/logs", "traces",
            "/api/otel/v1/traces");

    /** An authenticated OTLP source. */
    public record OtlpSource(long id, String name) {}

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final OutboundHttp http;
    private final TelemetryWriter writer;
    private final AuditService audit;
    private final Clock clock;
    private final Map<TelemetrySourceKind, TelemetryAdapter> adapters = new EnumMap<>(TelemetrySourceKind.class);

    public TelemetrySourceService(JdbcTemplate jdbc, SecretCipher cipher, OutboundHttp http, TelemetryWriter writer, AuditService audit, Clock clock,
                                  List<TelemetryAdapter> adapterBeans) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.http = http;
        this.writer = writer;
        this.audit = audit;
        this.clock = clock;
        for (TelemetryAdapter a : adapterBeans) {
            if (adapters.put(a.kind(), a) != null) {
                throw new IllegalStateException("Duplicate telemetry adapter for " + a.kind());
            }
        }
    }

    public TelemetryAdapter adapter(TelemetrySourceKind kind) {
        return adapters.get(kind);
    }

    // ------------------------------------------------------------------ reading

    public List<KindInfo> kinds() {
        List<KindInfo> out = new ArrayList<>();
        out.add(new KindInfo(TelemetrySourceKind.OTLP, false, false, false, null, List.of(),
                "OTLP/HTTP JSON push to /api/otel/v1/metrics|logs|traces with Authorization: Bearer <source token>; protobuf is not supported (415)."));
        out.add(info(TelemetrySourceKind.PROMETHEUS_API, List.of("queries[].name", "queries[].query"),
                "Prometheus HTTP API (query_range, PromQL), bearer or basic auth; scheduled pull when pullEnabled."));
        out.add(info(TelemetrySourceKind.GRAFANA, List.of("datasourceUid", "queries[].name", "queries[].expr"),
                "Prepared: Grafana /api/ds/query with a service-account token; test call checks the data source."));
        out.add(info(TelemetrySourceKind.CLOUDWATCH, List.of("region", "queries[].name", "queries[].namespace", "queries[].metricName",
                "queries[].dimensions", "queries[].stat", "queries[].period"), "Prepared: CloudWatch GetMetricData (SigV4 signing not implemented)."));
        out.add(info(TelemetrySourceKind.AZURE_MONITOR, List.of("resourceId", "queries[].name", "queries[].metricName", "queries[].aggregation"),
                "Prepared: Azure Monitor metrics REST API with an Entra ID access token as secret."));
        out.add(info(TelemetrySourceKind.GCP_MONITORING, List.of("projectId", "queries[].name", "queries[].filter", "queries[].aligner"),
                "Prepared: Cloud Monitoring timeSeries.list with an OAuth access token as secret."));
        return out;
    }

    private KindInfo info(TelemetrySourceKind kind, List<String> keys, String description) {
        TelemetryAdapter a = adapters.get(kind);
        return new KindInfo(kind, a.prepared(), !a.prepared(), a.testUnsupportedReason() == null, a.defaultBaseUrl(Map.of()), keys, description);
    }

    @Transactional(readOnly = true)
    public List<SourceDto> list() {
        return jdbc.query(SELECT + " order by lower(name)", this::dto);
    }

    @Transactional(readOnly = true)
    public SourceDto get(Long id) {
        return find(id).orElseThrow(() -> ApiException.notFound("Telemetry source", id));
    }

    private Optional<SourceDto> find(Long id) {
        return jdbc.query(SELECT + " where id = ?", this::dto, id).stream().findFirst();
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public SourceSaved create(SourceRequest r, Actor actor) {
        Map<String, Object> config = r.config() == null ? Map.of() : r.config();
        String authType = r.authType() == null ? "NONE" : r.authType();
        String baseUrl = validateCommon(r.kind(), r.baseUrl(), authType, config, r.pullEnabled());
        if (jdbc.queryForObject("select count(*) from telemetry_sources where lower(name) = lower(?)", Long.class, r.name().trim()) > 0) {
            throw ApiException.invalidField("name", "A telemetry source with this name already exists");
        }
        String token = r.kind() == TelemetrySourceKind.OTLP ? newToken() : null;
        Instant now = clock.instant();
        Long id = jdbc.queryForObject("insert into telemetry_sources (name, kind, base_url, auth_type, username, secret_encrypted, token_hash, token_hint, config, "
                + "pull_enabled, pull_interval_seconds, active, created_by_id, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?) "
                + "returning id", Long.class, r.name().trim(), r.kind().name(), baseUrl, authType, blankToNull(r.username()),
                r.secret() == null || r.secret().isBlank() ? null : cipher.encrypt(r.secret()), token == null ? null : Hashing.sha256Hex(token),
                token == null ? null : token.substring(0, 10), TelemetryJson.write(config), Boolean.TRUE.equals(r.pullEnabled()),
                r.pullIntervalSeconds() == null ? 60 : r.pullIntervalSeconds(), r.active() == null || r.active(), actor.id(), Timestamp.from(now),
                Timestamp.from(now));
        audit.record("TELEMETRY_SOURCE_CREATED", "TELEMETRY_SOURCE", id, Map.of("name", r.name().trim(), "kind", r.kind().name()));
        return new SourceSaved(get(id), token);
    }

    @Transactional
    public SourceDto update(Long id, SourceRequest r) {
        SourceDto current = get(id);
        if (current.kind() != r.kind()) {
            throw ApiException.invalidField("kind", "The kind of a source cannot change");
        }
        Map<String, Object> config = r.config() == null ? current.config() : r.config();
        String authType = r.authType() == null ? current.authType() : r.authType();
        String baseUrl = validateCommon(r.kind(), r.baseUrl(), authType, config, r.pullEnabled());
        if (jdbc.queryForObject("select count(*) from telemetry_sources where lower(name) = lower(?) and id <> ?", Long.class, r.name().trim(), id) > 0) {
            throw ApiException.invalidField("name", "A telemetry source with this name already exists");
        }
        boolean endpointChanged = !java.util.Objects.equals(baseUrl, current.baseUrl()) || !java.util.Objects.equals(config, current.config())
                || r.secret() != null || !java.util.Objects.equals(authType, current.authType());
        jdbc.update("update telemetry_sources set name = ?, base_url = ?, auth_type = ?, username = ?, config = ?::jsonb, pull_enabled = ?, "
                + "pull_interval_seconds = ?, active = ?, updated_at = ?, version = version + 1" + (r.secret() != null ? ", secret_encrypted = ?" : "")
                + (endpointChanged && r.kind() != TelemetrySourceKind.OTLP ? ", status = 'NOT_CONNECTED', last_verified_at = null" : "") + " where id = ?",
                updateArgs(r, baseUrl, authType, config, current, id));
        audit.record("TELEMETRY_SOURCE_UPDATED", "TELEMETRY_SOURCE", id, Map.of("name", r.name().trim(), "secretChanged", r.secret() != null,
                "reverification", endpointChanged && r.kind() != TelemetrySourceKind.OTLP));
        return get(id);
    }

    private Object[] updateArgs(SourceRequest r, String baseUrl, String authType, Map<String, Object> config, SourceDto current, Long id) {
        List<Object> args = new ArrayList<>(List.of(r.name().trim()));
        args.add(baseUrl);
        args.add(authType);
        args.add(blankToNull(r.username()));
        args.add(TelemetryJson.write(config));
        args.add(r.pullEnabled() == null ? current.pullEnabled() : r.pullEnabled());
        args.add(r.pullIntervalSeconds() == null ? current.pullIntervalSeconds() : r.pullIntervalSeconds());
        args.add(r.active() == null ? current.active() : r.active());
        args.add(Timestamp.from(clock.instant()));
        if (r.secret() != null) {
            args.add(r.secret().isBlank() ? null : cipher.encrypt(r.secret()));
        }
        args.add(id);
        return args.toArray();
    }

    @Transactional
    public void delete(Long id) {
        SourceDto s = get(id);
        jdbc.update("delete from telemetry_sources where id = ?", id);
        audit.record("TELEMETRY_SOURCE_DELETED", "TELEMETRY_SOURCE", id, Map.of("name", s.name(), "kind", s.kind().name()));
    }

    @Transactional
    public SourceSaved rotateToken(Long id) {
        SourceDto s = get(id);
        if (s.kind() != TelemetrySourceKind.OTLP) {
            throw ApiException.badRequest("Only OTLP sources have an ingestion token");
        }
        String token = newToken();
        jdbc.update("update telemetry_sources set token_hash = ?, token_hint = ?, updated_at = ? where id = ?", Hashing.sha256Hex(token),
                token.substring(0, 10), Timestamp.from(clock.instant()), id);
        audit.record("TELEMETRY_SOURCE_TOKEN_ROTATED", "TELEMETRY_SOURCE", id, Map.of("name", s.name()));
        return new SourceSaved(get(id), token);
    }

    /** Test call against the external system; CONNECTED (with lastVerifiedAt) only when it succeeds. Prepared kinds without auth support stay NOT_CONNECTED. */
    @Transactional
    public TestResult test(Long id) {
        SourceDto s = get(id);
        Instant now = clock.instant();
        if (s.kind() == TelemetrySourceKind.OTLP) {
            String msg = s.lastVerifiedAt() == null ? "OTLP is push-based: the source becomes CONNECTED with the first authenticated delivery"
                    : "Last authenticated delivery at " + s.lastReceivedAt();
            return new TestResult(id, s.status(), s.lastVerifiedAt() != null, null, msg, now, s.lastVerifiedAt());
        }
        TelemetryAdapter adapter = adapters.get(s.kind());
        String base = baseUrl(s, adapter);
        OutboundRequest request = adapter.buildTest(base, s.config());
        if (request == null) {
            jdbc.update("update telemetry_sources set last_tested_at = ?, last_error = ? where id = ?", Timestamp.from(now),
                    OtlpJsonParser.truncate(adapter.testUnsupportedReason(), 1000), id);
            audit.record("TELEMETRY_SOURCE_TESTED", "TELEMETRY_SOURCE", id, Map.of("success", false, "reason", "not supported"));
            return new TestResult(id, s.status(), false, null, adapter.testUnsupportedReason(), now, s.lastVerifiedAt());
        }
        OutboundHttp.Result result = http.execute(request, credentials(id));
        boolean success = result.error() == null && adapter.testSucceeded(result.status(), result.body());
        String message = success ? "Connected: the system answered HTTP " + result.status()
                : result.error() != null ? result.error() : "The system answered HTTP " + result.status();
        if (success) {
            jdbc.update("update telemetry_sources set status = 'CONNECTED', last_verified_at = ?, last_tested_at = ?, last_error = null where id = ?",
                    Timestamp.from(now), Timestamp.from(now), id);
        } else {
            jdbc.update("update telemetry_sources set status = case when last_verified_at is null then 'NOT_CONNECTED' else 'ERROR' end, last_tested_at = ?, "
                    + "last_error = ? where id = ?", Timestamp.from(now), OtlpJsonParser.truncate(message, 1000), id);
        }
        audit.record("TELEMETRY_SOURCE_TESTED", "TELEMETRY_SOURCE", id, Map.of("success", success, "httpStatus", result.status()));
        SourceDto after = get(id);
        return new TestResult(id, after.status(), success, result.status() == 0 ? null : result.status(), message, now, after.lastVerifiedAt());
    }

    /**
     * Pulls the configured queries of a Prometheus source for the last {@code lookbackMinutes} (default: the pull interval plus one
     * minute) at a 60 s step and stores the samples through {@link TelemetryWriter}. Prepared adapters cannot pull.
     */
    public PullResult pull(Long id, Integer lookbackMinutes) {
        SourceDto s = get(id);
        TelemetryAdapter adapter = adapters.get(s.kind());
        if (adapter == null || s.kind() == TelemetrySourceKind.OTLP) {
            throw ApiException.badRequest("OTLP sources are push-based");
        }
        if (adapter.prepared()) {
            throw ApiException.conflict("Prepared adapter (" + s.kind() + "): scheduled and manual pulls are not enabled in this release");
        }
        if (!s.active()) {
            throw ApiException.conflict("The source is inactive");
        }
        Instant to = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        int minutes = lookbackMinutes == null ? Math.max(2, s.pullIntervalSeconds() / 60 + 1) : Math.max(1, Math.min(lookbackMinutes, 1440));
        Instant from = to.minus(Duration.ofMinutes(minutes));
        int series = 0;
        int points = 0;
        int rejected = 0;
        List<String> errors = new ArrayList<>();
        List<MetricQuery> queries = adapter.queries(s.config());
        OutboundHttp.Credentials credentials = credentials(id);
        for (MetricQuery q : queries) {
            OutboundHttp.Result result = http.execute(adapter.buildQuery(baseUrl(s, adapter), s.config(), q, from, to, Duration.ofSeconds(60)), credentials);
            if (!result.ok()) {
                errors.add(q.name() + ": " + (result.error() != null ? result.error() : "HTTP " + result.status()));
                continue;
            }
            List<SeriesData> data;
            try {
                data = adapter.parse(result.body(), q);
            } catch (IllegalArgumentException e) {
                errors.add(q.name() + ": " + e.getMessage());
                continue;
            }
            Parsed<MetricPoint> parsed = new Parsed<>();
            for (SeriesData d : data) {
                List<MetricPoint> mp = new ArrayList<>();
                for (Sample sample : d.samples()) {
                    mp.add(new MetricPoint(d.name(), null, MetricType.GAUGE, d.attributes(), sample.timestamp(), sample.value(), null, null, null, null));
                }
                if (!mp.isEmpty()) {
                    parsed.batches().add(new ResourceBatch<>(d.resource(), mp));
                }
            }
            IngestResult written = writer.writeMetrics(new TelemetryWriter.Context(id, false), parsed);
            series += data.size();
            points += written.accepted();
            rejected += written.rejected();
        }
        Instant now = clock.instant();
        boolean anySuccess = errors.size() < queries.size();
        String message = errors.isEmpty() ? "Pulled " + points + " samples from " + series + " series" : String.join("; ", errors);
        jdbc.update("update telemetry_sources set last_pulled_at = ?, last_error = ?, received_count = received_count + ?"
                + (anySuccess ? ", status = 'CONNECTED', last_verified_at = ?" : ", status = case when last_verified_at is null then 'NOT_CONNECTED' else 'ERROR' end")
                + " where id = ?", anySuccess ? new Object[] {Timestamp.from(now), errors.isEmpty() ? null : OtlpJsonParser.truncate(message, 1000), points,
                        Timestamp.from(now), id} : new Object[] {Timestamp.from(now), OtlpJsonParser.truncate(message, 1000), points, id});
        return new PullResult(id, queries.size(), series, points, rejected, message, from, to);
    }

    /** Active Prometheus sources whose next pull is due. */
    public List<Long> duePulls() {
        return jdbc.queryForList("select id from telemetry_sources where active and pull_enabled and kind = 'PROMETHEUS_API' and (last_pulled_at is null "
                + "or last_pulled_at + make_interval(secs => pull_interval_seconds) <= ?) order by id", Long.class, Timestamp.from(clock.instant()));
    }

    // ------------------------------------------------------------------ OTLP authentication

    /** The active OTLP source of a bearer token, or empty (unknown, inactive or not an OTLP token). */
    public Optional<OtlpSource> authenticateOtlp(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX) || token.length() > 200) {
            return Optional.empty();
        }
        return jdbc.query("select id, name from telemetry_sources where token_hash = ? and kind = 'OTLP' and active", rs -> rs.next()
                ? Optional.of(new OtlpSource(rs.getLong(1), rs.getString(2))) : Optional.<OtlpSource>empty(), Hashing.sha256Hex(token));
    }

    /** Records an authenticated delivery: the first one verifies (CONNECTED) the source. */
    public void markDelivery(long sourceId, int accepted) {
        Instant now = clock.instant();
        jdbc.update("update telemetry_sources set last_received_at = ?, received_count = received_count + ?, status = 'CONNECTED', "
                + "last_verified_at = ?, last_error = null where id = ?", Timestamp.from(now), accepted, Timestamp.from(now), sourceId);
    }

    // ------------------------------------------------------------------ helpers

    private String validateCommon(TelemetrySourceKind kind, String baseUrl, String authType, Map<String, Object> config, Boolean pullEnabled) {
        if (kind == TelemetrySourceKind.OTLP) {
            if (baseUrl != null && !baseUrl.isBlank()) {
                throw ApiException.invalidField("baseUrl", "OTLP sources push to SA and have no base URL");
            }
            if (Boolean.TRUE.equals(pullEnabled)) {
                throw ApiException.invalidField("pullEnabled", "OTLP sources are push-based");
            }
            if (!"NONE".equals(authType)) {
                throw ApiException.invalidField("authType", "OTLP sources authenticate with their generated token (authType NONE)");
            }
            return null;
        }
        TelemetryAdapter adapter = adapters.get(kind);
        adapter.validate(config);
        if (Boolean.TRUE.equals(pullEnabled) && adapter.prepared()) {
            throw ApiException.invalidField("pullEnabled", "Prepared adapters cannot pull in this release");
        }
        String url = baseUrl == null || baseUrl.isBlank() ? adapter.defaultBaseUrl(config) : baseUrl.trim();
        if (url == null) {
            throw ApiException.invalidField("baseUrl", "A base URL is required for " + kind);
        }
        http.validate(url);
        return baseUrl == null || baseUrl.isBlank() ? null : url;
    }

    private String baseUrl(SourceDto s, TelemetryAdapter adapter) {
        return s.baseUrl() != null ? s.baseUrl() : adapter.defaultBaseUrl(s.config());
    }

    private OutboundHttp.Credentials credentials(Long id) {
        return jdbc.query("select auth_type, username, secret_encrypted from telemetry_sources where id = ?", rs -> rs.next()
                ? new OutboundHttp.Credentials(rs.getString(1), rs.getString(2), rs.getString(3) == null ? null : cipher.decrypt(rs.getString(3)))
                : OutboundHttp.Credentials.NONE, id);
    }

    static String newToken() {
        StringBuilder sb = new StringBuilder(TOKEN_PREFIX);
        for (int i = 0; i < 40; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static final String SELECT = "select id, name, kind, status, base_url, auth_type, username, secret_encrypted is not null, token_hint, config::text, "
            + "pull_enabled, pull_interval_seconds, active, last_verified_at, last_tested_at, last_error, last_received_at, last_pulled_at, received_count, "
            + "is_demo, created_at, updated_at from telemetry_sources";

    private SourceDto dto(ResultSet rs, int n) throws SQLException {
        TelemetrySourceKind kind = TelemetrySourceKind.valueOf(rs.getString(3));
        TelemetryAdapter adapter = adapters.get(kind);
        return new SourceDto(rs.getLong(1), rs.getString(2), kind, rs.getString(4), adapter != null && adapter.prepared(), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getBoolean(8), rs.getString(9), TelemetryJson.objectMap(rs.getString(10)), rs.getBoolean(11), rs.getInt(12),
                rs.getBoolean(13), MappingRuleService.instant(rs, 14), MappingRuleService.instant(rs, 15), rs.getString(16), MappingRuleService.instant(rs, 17),
                MappingRuleService.instant(rs, 18), rs.getLong(19), kind == TelemetrySourceKind.OTLP ? new LinkedHashMap<>(OTLP_PATHS) : Map.of(),
                rs.getBoolean(20), MappingRuleService.instant(rs, 21), MappingRuleService.instant(rs, 22));
    }
}
