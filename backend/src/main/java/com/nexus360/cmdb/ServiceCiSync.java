package com.nexus360.cmdb;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps one BUSINESS_SERVICE CI per business service and mirrors service dependencies as {@code DEPENDS_ON} relationships
 * with origin {@code SERVICE_MAP}. Runs at start-up (after the demo services are seeded, before the CMDB demo data) and
 * whenever a service is saved ({@link ServiceHealthService#save}). Manual relationships are never touched.
 */
@Slf4j
@Component
@Order(24)
@RequiredArgsConstructor
public class ServiceCiSync implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final CiLinkService ciLinks;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int created = syncAll();
        if (created > 0) {
            log.info("CMDB: created {} business-service CI(s)", created);
        }
    }

    /** Syncs every service; returns the number of CIs created. */
    @Transactional
    public int syncAll() {
        List<Long> ids = jdbc.queryForList("select id from business_services order by id", Long.class);
        int created = 0;
        for (Long id : ids) {
            created += ensureCi(id) ? 1 : 0;
        }
        for (Long id : ids) {
            syncRelationships(id);
        }
        return created;
    }

    /** Creates or updates the service's CI and its dependency relationships. */
    @Transactional
    public void syncService(Long serviceId) {
        ensureCi(serviceId);
        jdbc.queryForList("select depends_on_id from service_dependencies where service_id = ?", Long.class, serviceId).forEach(this::ensureCi);
        syncRelationships(serviceId);
    }

    /** Returns true when a CI was created. */
    private boolean ensureCi(Long serviceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select code, name, description, tier, owner_id, default_team_id, is_demo "
                + "from business_services where id = ?", serviceId);
        if (rows.isEmpty()) {
            return false;
        }
        Map<String, Object> s = rows.get(0);
        String name = (String) s.get("name");
        int tier = ((Number) s.get("tier")).intValue();
        String criticality = tier == 1 ? "CRITICAL" : tier == 2 ? "HIGH" : "MEDIUM";
        Timestamp now = Timestamp.from(clock.instant());
        List<Map<String, Object>> existing = jdbc.queryForList("select id, name from configuration_items where service_id = ?", serviceId);
        if (existing.isEmpty()) {
            String ciName = freeName(name, (String) s.get("code"), null);
            Long id = jdbc.queryForObject("insert into configuration_items (name, ci_type, status, environment, criticality, description, owner_id, "
                    + "team_id, service_id, attributes, is_demo, created_at, updated_at) values (?, 'BUSINESS_SERVICE', 'ACTIVE', 'PRODUCTION', ?, ?, ?, ?, ?, "
                    + "jsonb_build_object('serviceCode', ?::text, 'tier', ?::int), ?, ?, ?) returning id", Long.class, ciName, criticality,
                    truncate((String) s.get("description"), 2000), s.get("owner_id"), s.get("default_team_id"), serviceId, s.get("code"), tier,
                    s.get("is_demo"), now, now);
            history(id, "CREATED", null, null, "Created from business service " + s.get("code"));
            ciLinks.linkByName(id);
            return true;
        }
        Long id = ((Number) existing.get(0).get("id")).longValue();
        String current = (String) existing.get(0).get("name");
        if (!current.equals(name)) {
            String wanted = freeName(name, (String) s.get("code"), id);
            if (!wanted.equals(current)) {
                jdbc.update("update configuration_items set name = ?, updated_at = ? where id = ?", wanted, now, id);
                history(id, "UPDATED", current, wanted, "Renamed with its business service");
                ciLinks.linkByName(id);
            }
        }
        jdbc.update("update configuration_items set criticality = ?, attributes = attributes || jsonb_build_object('serviceCode', ?::text, 'tier', ?::int), "
                + "updated_at = ? where id = ? and (criticality <> ? or attributes ->> 'serviceCode' is distinct from ? or attributes ->> 'tier' is distinct from ?)",
                criticality, s.get("code"), tier, now, id, criticality, s.get("code"), String.valueOf(tier));
        return false;
    }

    private void syncRelationships(Long serviceId) {
        Long source = ciOf(serviceId);
        if (source == null) {
            return;
        }
        Set<Long> wanted = new HashSet<>();
        for (Long dep : jdbc.queryForList("select depends_on_id from service_dependencies where service_id = ?", Long.class, serviceId)) {
            Long target = ciOf(dep);
            if (target != null) {
                wanted.add(target);
            }
        }
        List<Map<String, Object>> current = jdbc.queryForList("select id, target_id from ci_relationships where source_id = ? and origin = 'SERVICE_MAP' "
                + "and rel_type = 'DEPENDS_ON'", source);
        Set<Long> have = new HashSet<>();
        for (Map<String, Object> r : current) {
            Long target = ((Number) r.get("target_id")).longValue();
            if (!wanted.contains(target)) {
                jdbc.update("delete from ci_relationships where id = ?", r.get("id"));
                history(source, "RELATIONSHIP_REMOVED", "depends on #" + target, null, "Business service dependency removed");
            } else {
                have.add(target);
            }
        }
        for (Long target : wanted) {
            if (!have.contains(target)) {
                int n = jdbc.update("insert into ci_relationships (source_id, target_id, rel_type, description, origin, is_demo, created_at) "
                        + "select ?, ?, 'DEPENDS_ON', 'Business service dependency', 'SERVICE_MAP', a.is_demo and b.is_demo, ? from configuration_items a, "
                        + "configuration_items b where a.id = ? and b.id = ? on conflict do nothing", source, target, Timestamp.from(clock.instant()), source, target);
                if (n > 0) {
                    history(source, "RELATIONSHIP_ADDED", null, "depends on #" + target, "Business service dependency");
                }
            }
        }
    }

    private Long ciOf(Long serviceId) {
        List<Long> ids = jdbc.queryForList("select id from configuration_items where service_id = ?", Long.class, serviceId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** The service name, or a variant that no other CI uses (CI names are unique ignoring case). */
    private String freeName(String name, String code, Long selfId) {
        for (String candidate : List.of(name, name + " (service)", name + " (service " + code + ")")) {
            String c = truncate(candidate, 120);
            Long taken = jdbc.queryForObject("select count(*) from configuration_items where lower(name) = lower(?) and (cast(? as bigint) is null or id <> ?)",
                    Long.class, c, selfId, selfId);
            if (taken == null || taken == 0) {
                return c;
            }
        }
        return truncate(name + " " + java.util.UUID.randomUUID().toString().substring(0, 6), 120);
    }

    private void history(Long ciId, String action, String from, String to, String note) {
        jdbc.update("insert into ci_history (ci_id, actor_name, action, from_value, to_value, note, created_at) values (?, 'SA Automation', ?, ?, ?, ?, ?)",
                ciId, action, from, to, note, Timestamp.from(clock.instant()));
    }

    private static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
