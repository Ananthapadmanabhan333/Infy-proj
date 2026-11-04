package com.nexus360.observability;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.observability.ObservabilityDtos.MappingRuleDto;
import com.nexus360.observability.ObservabilityDtos.MappingRuleRequest;
import com.nexus360.observability.ObservabilityDtos.ReapplyResult;
import com.nexus360.security.Actor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attribute → service / CI mapping rules for telemetry resources. Rules are evaluated in {@code sortOrder} (then id) order against the
 * resource attributes ({@code service.name}, {@code service.namespace}, {@code deployment.environment}, {@code host.name}, {@code k8s.*},
 * {@code cloud.*}, ...); the first enabled rule whose attribute matches (EXACT, case-sensitive, or a full-match REGEX) sets the
 * resource's service and/or CI. Unmatched resources keep {@code serviceId = null} and are listed as unmapped.
 */
@Service
@RequiredArgsConstructor
public class MappingRuleService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_.\\-]{0,119}$");

    /** A compiled rule. */
    record Rule(long id, String name, String attributeKey, String matchType, String matchValue, Pattern regex, Long serviceId, Long ciId) {
        boolean matches(Map<String, String> attributes) {
            String v = attributes.get(attributeKey);
            if (v == null) {
                return false;
            }
            return "REGEX".equals(matchType) ? regex.matcher(v).matches() : matchValue.equals(v);
        }
    }

    /** The mapping decided for a resource. */
    public record Match(Long ruleId, Long serviceId, Long ciId) {
        static final Match NONE = new Match(null, null, null);
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;
    private volatile List<Rule> cache;
    private volatile Instant cachedAt = Instant.EPOCH;

    public Match resolve(Map<String, String> attributes) {
        for (Rule r : rules()) {
            if (r.matches(attributes)) {
                return new Match(r.id(), r.serviceId(), r.ciId());
            }
        }
        return Match.NONE;
    }

    List<Rule> rules() {
        List<Rule> current = cache;
        if (current == null || Duration.between(cachedAt, clock.instant()).compareTo(CACHE_TTL) > 0 || cachedAt.isAfter(clock.instant())) {
            current = jdbc.query("select id, name, attribute_key, match_type, match_value, service_id, ci_id from telemetry_mapping_rules where enabled "
                    + "order by sort_order, id", (rs, n) -> {
                        String type = rs.getString(4);
                        String value = rs.getString(5);
                        Pattern regex = null;
                        if ("REGEX".equals(type)) {
                            try {
                                regex = Pattern.compile(value);
                            } catch (PatternSyntaxException e) {
                                regex = Pattern.compile("(?!)");
                            }
                        }
                        return new Rule(rs.getLong(1), rs.getString(2), rs.getString(3), type, value, regex, nullableLong(rs, 6), nullableLong(rs, 7));
                    });
            cache = current;
            cachedAt = clock.instant();
        }
        return current;
    }

    void invalidate() {
        cache = null;
    }

    /** Counts a (re)mapping of a resource on the rule. */
    void recordMatch(Long ruleId, Instant at) {
        if (ruleId != null) {
            jdbc.update("update telemetry_mapping_rules set match_count = match_count + 1, last_matched_at = ? where id = ?", Timestamp.from(at), ruleId);
        }
    }

    // ------------------------------------------------------------------ CRUD

    @Transactional(readOnly = true)
    public List<MappingRuleDto> list() {
        return jdbc.query(SELECT + " order by r.sort_order, r.id", MappingRuleService::dto);
    }

    @Transactional(readOnly = true)
    public MappingRuleDto get(Long id) {
        return jdbc.query(SELECT + " where r.id = ?", MappingRuleService::dto, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Mapping rule", id));
    }

    @Transactional
    public MappingRuleDto save(Long id, MappingRuleRequest r, Actor actor) {
        validate(r);
        Instant now = clock.instant();
        Long ruleId = id;
        if (id == null) {
            ruleId = jdbc.queryForObject("insert into telemetry_mapping_rules (name, attribute_key, match_type, match_value, service_id, ci_id, sort_order, "
                    + "enabled, created_by_id, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id", Long.class, r.name().trim(),
                    r.attributeKey().trim(), r.matchType(), r.matchValue(), r.serviceId(), r.ciId(), r.sortOrder() == null ? 100 : r.sortOrder(),
                    r.enabled() == null || r.enabled(), actor.id(), Timestamp.from(now), Timestamp.from(now));
        } else {
            get(id);
            jdbc.update("update telemetry_mapping_rules set name = ?, attribute_key = ?, match_type = ?, match_value = ?, service_id = ?, ci_id = ?, "
                    + "sort_order = ?, enabled = ?, updated_at = ? where id = ?", r.name().trim(), r.attributeKey().trim(), r.matchType(), r.matchValue(),
                    r.serviceId(), r.ciId(), r.sortOrder() == null ? 100 : r.sortOrder(), r.enabled() == null || r.enabled(), Timestamp.from(now), id);
        }
        invalidate();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("attributeKey", r.attributeKey());
        details.put("matchType", r.matchType());
        details.put("matchValue", r.matchValue());
        details.put("serviceId", r.serviceId());
        details.put("ciId", r.ciId());
        audit.record(id == null ? "TELEMETRY_MAPPING_CREATED" : "TELEMETRY_MAPPING_UPDATED", "TELEMETRY_MAPPING", ruleId, details);
        return get(ruleId);
    }

    @Transactional
    public void delete(Long id) {
        MappingRuleDto rule = get(id);
        jdbc.update("delete from telemetry_mapping_rules where id = ?", id);
        invalidate();
        audit.record("TELEMETRY_MAPPING_DELETED", "TELEMETRY_MAPPING", id, Map.of("name", rule.name()));
    }

    /** Re-evaluates the mapping of every resource (bounded by {@code limit}, most recently seen first) against the current rules. */
    @Transactional
    public ReapplyResult reapply(int limit) {
        invalidate();
        int max = Math.max(1, Math.min(limit, 50_000));
        record Row(long id, Map<String, String> attributes, Long serviceId, Long ciId, Long ruleId) {}
        List<Row> rows = jdbc.query("select id, attributes::text, service_id, ci_id, mapping_rule_id from telemetry_resources order by last_seen_at desc "
                + "limit ?", (rs, n) -> new Row(rs.getLong(1), TelemetryJson.stringMap(rs.getString(2)), nullableLong(rs, 3), nullableLong(rs, 4),
                nullableLong(rs, 5)), max);
        int changed = 0;
        int mapped = 0;
        Instant now = clock.instant();
        for (Row row : rows) {
            Match m = resolve(row.attributes());
            if (m.ruleId() != null) {
                mapped++;
            }
            if (!java.util.Objects.equals(m.serviceId(), row.serviceId()) || !java.util.Objects.equals(m.ciId(), row.ciId())
                    || !java.util.Objects.equals(m.ruleId(), row.ruleId())) {
                jdbc.update("update telemetry_resources set service_id = ?, ci_id = ?, mapping_rule_id = ?, updated_at = ? where id = ?", m.serviceId(),
                        m.ciId(), m.ruleId(), Timestamp.from(now), row.id());
                recordMatch(m.ruleId(), now);
                changed++;
            }
        }
        audit.record("TELEMETRY_MAPPINGS_REAPPLIED", "TELEMETRY_MAPPING", null, Map.of("resources", rows.size(), "changed", changed));
        return new ReapplyResult(rows.size(), changed, mapped, rows.size() - mapped);
    }

    private void validate(MappingRuleRequest r) {
        if (!KEY.matcher(r.attributeKey().trim()).matches()) {
            throw ApiException.invalidField("attributeKey", "Attribute keys are letters, digits, '.', '_' and '-' (e.g. service.name)");
        }
        if (!List.of("EXACT", "REGEX").contains(r.matchType())) {
            throw ApiException.invalidField("matchType", "matchType must be EXACT or REGEX");
        }
        if ("REGEX".equals(r.matchType())) {
            try {
                Pattern.compile(r.matchValue());
            } catch (PatternSyntaxException e) {
                throw ApiException.invalidField("matchValue", "Not a valid regular expression");
            }
        }
        if (r.serviceId() == null && r.ciId() == null) {
            throw ApiException.badRequest("A mapping rule needs a serviceId, a ciId or both");
        }
        if (r.serviceId() != null && jdbc.queryForObject("select count(*) from business_services where id = ?", Long.class, r.serviceId()) == 0) {
            throw ApiException.invalidField("serviceId", "Service does not exist");
        }
        if (r.ciId() != null && jdbc.queryForObject("select count(*) from configuration_items where id = ?", Long.class, r.ciId()) == 0) {
            throw ApiException.invalidField("ciId", "Configuration item does not exist");
        }
    }

    private static final String SELECT = "select r.id, r.name, r.attribute_key, r.match_type, r.match_value, r.service_id, s.name, r.ci_id, c.name, "
            + "r.sort_order, r.enabled, r.match_count, r.last_matched_at, r.is_demo, r.created_at, r.updated_at from telemetry_mapping_rules r "
            + "left join business_services s on s.id = r.service_id left join configuration_items c on c.id = r.ci_id";

    private static MappingRuleDto dto(ResultSet rs, int n) throws SQLException {
        return new MappingRuleDto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), nullableLong(rs, 6), rs.getString(7),
                nullableLong(rs, 8), rs.getString(9), rs.getInt(10), rs.getBoolean(11), rs.getLong(12), instant(rs, 13), rs.getBoolean(14), instant(rs, 15),
                instant(rs, 16));
    }

    static Long nullableLong(ResultSet rs, int i) throws SQLException {
        long v = rs.getLong(i);
        return rs.wasNull() ? null : v;
    }

    static Double nullableDouble(ResultSet rs, int i) throws SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }

    static Instant instant(ResultSet rs, int i) throws SQLException {
        Timestamp t = rs.getTimestamp(i);
        return t == null ? null : t.toInstant();
    }

    static List<Long> longs(List<Long> in) {
        return in == null ? List.of() : new ArrayList<>(in);
    }
}
