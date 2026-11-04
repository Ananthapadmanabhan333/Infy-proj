package com.nexus360.cmdb;

import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.CmdbDtos.AssetRef;
import com.nexus360.cmdb.CmdbDtos.CiDetail;
import com.nexus360.cmdb.CmdbDtos.CiRef;
import com.nexus360.cmdb.CmdbDtos.CiRequest;
import com.nexus360.cmdb.CmdbDtos.CiSummary;
import com.nexus360.cmdb.CmdbDtos.CmdbMeta;
import com.nexus360.cmdb.CmdbDtos.HistoryDto;
import com.nexus360.cmdb.CmdbDtos.RelationshipDto;
import com.nexus360.cmdb.CmdbDtos.RelationshipRequest;
import com.nexus360.cmdb.CmdbDtos.RelationshipType;
import com.nexus360.cmdb.CmdbDtos.RelationshipTypeSpec;
import com.nexus360.cmdb.CmdbDtos.WorkRef;
import com.nexus360.cmdb.ConfigurationItem.CiType;
import com.nexus360.cmdb.ConfigurationItem.Criticality;
import com.nexus360.cmdb.ConfigurationItem.Environment;
import com.nexus360.cmdb.ConfigurationItem.Status;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.security.Actor;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRepository;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CMDB configuration items: CRUD with validation, typed relationships and the CI history. BUSINESS_SERVICE CIs that mirror a
 * business service are maintained by {@link ServiceCiSync} (their name follows the service; they cannot be deleted while the
 * service exists). Traversal (impact analysis, graph) lives in {@link CmdbGraphService}.
 */
@Service
@RequiredArgsConstructor
public class CmdbService {

    private static final Pattern ATTRIBUTE_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,59}$");
    private static final Set<String> SORTS = Set.of("name", "ciType", "status", "criticality", "environment", "createdAt", "updatedAt");

    public record Filter(String search, List<CiType> types, List<Status> statuses, Environment environment, Criticality criticality, Long teamId,
                         Long ownerId, Long serviceId) {}

    private final ConfigurationItemRepository cis;
    private final BusinessServiceRepository services;
    private final UserRepository users;
    private final TeamRepository teams;
    private final CiLinkService ciLinks;
    private final CmdbGraphService graph;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public PageResponse<CiSummary> list(Filter f, Integer page, Integer size, String sort) {
        Specification<ConfigurationItem> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.types() != null && !f.types().isEmpty()) {
                p.add(root.get("ciType").in(f.types()));
            }
            if (f.statuses() != null && !f.statuses().isEmpty()) {
                p.add(root.get("status").in(f.statuses()));
            }
            if (f.environment() != null) {
                p.add(cb.equal(root.get("environment"), f.environment()));
            }
            if (f.criticality() != null) {
                p.add(cb.equal(root.get("criticality"), f.criticality()));
            }
            if (f.teamId() != null) {
                p.add(cb.equal(root.get("team").get("id"), f.teamId()));
            }
            if (f.ownerId() != null) {
                p.add(cb.equal(root.get("owner").get("id"), f.ownerId()));
            }
            if (f.serviceId() != null) {
                p.add(cb.equal(root.get("service").get("id"), f.serviceId()));
            }
            if (f.search() != null && !f.search().isBlank()) {
                String like = "%" + f.search().trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("name")), like, '\\'), cb.like(cb.lower(root.get("description")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<ConfigurationItem> result = cis.findAll(spec, PageResponse.pageable(page, size, sortOf(sort)));
        List<Long> ids = result.getContent().stream().map(ConfigurationItem::getId).toList();
        Map<Long, Long> rels = new HashMap<>();
        if (!ids.isEmpty()) {
            String in = CmdbGraphService.ids(ids);
            jdbc.query("select ci, count(*) from (select source_id as ci from ci_relationships where source_id in (" + in + ") union all "
                    + "select target_id from ci_relationships where target_id in (" + in + ")) x group by ci", rs -> {
                        rels.put(rs.getLong(1), rs.getLong(2));
                    });
        }
        Map<Long, Long> alerts = graph.alertCounts(ids);
        return PageResponse.of(result, c -> summary(c, rels.getOrDefault(c.getId(), 0L), alerts.getOrDefault(c.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public CiDetail get(Long id) {
        return detail(load(id));
    }

    @Transactional(readOnly = true)
    public List<RelationshipDto> relationships(Long id) {
        load(id);
        List<RelationshipDto> out = new ArrayList<>(relationships(id, true));
        out.addAll(relationships(id, false));
        return out;
    }

    @Transactional(readOnly = true)
    public List<HistoryDto> history(Long id) {
        load(id);
        return jdbc.query("select id, created_at, actor_name, action, from_value, to_value, note from ci_history where ci_id = ? order by created_at desc, id desc "
                + "limit 500", (rs, n) -> new HistoryDto(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7)), id);
    }

    public CmdbMeta meta() {
        return new CmdbMeta(List.of(CiType.values()), List.of(Status.values()), List.of(Environment.values()), List.of(Criticality.values()),
                java.util.Arrays.stream(RelationshipType.values()).map(t -> new RelationshipTypeSpec(t, t.outgoingLabel(), t.incomingLabel(),
                        t.sourceDependsOnTarget())).toList());
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public CiDetail create(CiRequest r, Actor actor) {
        ConfigurationItem c = new ConfigurationItem();
        c.setCreatedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        c.setCreatedAt(clock.instant());
        apply(c, r, true);
        cis.saveAndFlush(c);
        record(c.getId(), actor, "CREATED", null, c.getCiType() + " " + c.getName(), null);
        ciLinks.linkByName(c.getId());
        audit.record("CI_CREATED", "CONFIGURATION_ITEM", c.getId(), Map.of("name", c.getName(), "type", c.getCiType()));
        return detail(c);
    }

    @Transactional
    public CiDetail update(Long id, CiRequest r, Actor actor) {
        ConfigurationItem c = load(id);
        Map<String, String> before = snapshot(c);
        Map<String, Object> attributesBefore = new LinkedHashMap<>(c.getAttributes());
        apply(c, r, false);
        c.setUpdatedAt(clock.instant());
        cis.saveAndFlush(c);
        Map<String, String> after = snapshot(c);
        List<String> changed = new ArrayList<>();
        for (String field : before.keySet()) {
            if (!Objects.equals(before.get(field), after.get(field))) {
                changed.add(field);
                if ("description".equals(field)) {
                    record(id, actor, "UPDATED", null, null, "Description updated");
                } else {
                    record(id, actor, "status".equals(field) ? "STATUS_CHANGED" : "UPDATED", field + ": " + nz(before.get(field)), field + ": "
                            + nz(after.get(field)), null);
                }
            }
        }
        if (!attributesBefore.equals(c.getAttributes())) {
            Set<String> keys = new TreeSet<>(attributesBefore.keySet());
            keys.addAll(c.getAttributes().keySet());
            List<String> diff = keys.stream().filter(k -> !Objects.equals(attributesBefore.get(k), c.getAttributes().get(k))).toList();
            changed.add("attributes");
            record(id, actor, "ATTRIBUTES_CHANGED", null, null, "Changed: " + String.join(", ", diff));
        }
        if (changed.contains("name")) {
            ciLinks.linkByName(id);
        }
        audit.record("CI_UPDATED", "CONFIGURATION_ITEM", id, Map.of("name", c.getName(), "changed", changed));
        return detail(c);
    }

    @Transactional
    public void delete(Long id, Actor actor) {
        ConfigurationItem c = load(id);
        if (c.getService() != null) {
            throw ApiException.conflict("This CI represents the business service " + c.getService().getName()
                    + "; retire it or delete the service instead");
        }
        cis.delete(c);
        audit.record("CI_DELETED", "CONFIGURATION_ITEM", id, Map.of("name", c.getName(), "type", c.getCiType()));
    }

    @Transactional
    public RelationshipDto addRelationship(RelationshipRequest r, Actor actor) {
        if (r.sourceId().equals(r.targetId())) {
            throw ApiException.invalidField("targetId", "A configuration item cannot relate to itself");
        }
        ConfigurationItem source = cis.findById(r.sourceId()).orElseThrow(() -> ApiException.invalidField("sourceId", "Configuration item does not exist"));
        ConfigurationItem target = cis.findById(r.targetId()).orElseThrow(() -> ApiException.invalidField("targetId", "Configuration item does not exist"));
        Long exists = jdbc.queryForObject("select count(*) from ci_relationships where source_id = ? and target_id = ? and rel_type = ?", Long.class,
                source.getId(), target.getId(), r.type().name());
        if (exists != null && exists > 0) {
            throw ApiException.conflict(source.getName() + " already " + r.type().outgoingLabel() + " " + target.getName());
        }
        Long relId = jdbc.queryForObject("insert into ci_relationships (source_id, target_id, rel_type, description, origin, is_demo, created_by_id, created_at) "
                + "values (?, ?, ?, ?, 'MANUAL', false, ?, ?) returning id", Long.class, source.getId(), target.getId(), r.type().name(),
                blankToNull(r.description()), actor.isSystem() ? null : actor.id(), Timestamp.from(clock.instant()));
        record(source.getId(), actor, "RELATIONSHIP_ADDED", null, r.type().outgoingLabel() + " " + target.getName(), r.description());
        record(target.getId(), actor, "RELATIONSHIP_ADDED", null, r.type().incomingLabel() + " " + source.getName(), r.description());
        audit.record("CI_RELATIONSHIP_ADDED", "CONFIGURATION_ITEM", source.getId(), Map.of("relationshipId", relId, "type", r.type(),
                "target", target.getName()));
        return relationships(source.getId(), true).stream().filter(x -> x.id().equals(relId)).findFirst().orElseThrow();
    }

    @Transactional
    public void removeRelationship(Long relationshipId, Actor actor) {
        List<Map<String, Object>> rows = jdbc.queryForList("select r.source_id, r.target_id, r.rel_type, r.origin, s.name as source_name, t.name as target_name "
                + "from ci_relationships r join configuration_items s on s.id = r.source_id join configuration_items t on t.id = r.target_id where r.id = ?",
                relationshipId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Relationship", relationshipId);
        }
        Map<String, Object> rel = rows.get(0);
        if ("SERVICE_MAP".equals(rel.get("origin"))) {
            throw ApiException.conflict("This relationship mirrors a business-service dependency; change the service's dependencies instead");
        }
        RelationshipType type = RelationshipType.valueOf((String) rel.get("rel_type"));
        jdbc.update("delete from ci_relationships where id = ?", relationshipId);
        Long source = ((Number) rel.get("source_id")).longValue();
        Long target = ((Number) rel.get("target_id")).longValue();
        record(source, actor, "RELATIONSHIP_REMOVED", type.outgoingLabel() + " " + rel.get("target_name"), null, null);
        record(target, actor, "RELATIONSHIP_REMOVED", type.incomingLabel() + " " + rel.get("source_name"), null, null);
        audit.record("CI_RELATIONSHIP_REMOVED", "CONFIGURATION_ITEM", source, Map.of("relationshipId", relationshipId, "type", type,
                "target", rel.get("target_name")));
    }

    /** History entry (also used by the asset module when an asset is linked to or unlinked from a CI). */
    @Transactional
    public void record(Long ciId, Actor actor, String action, String from, String to, String note) {
        jdbc.update("insert into ci_history (ci_id, actor_id, actor_name, action, from_value, to_value, note, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                ciId, actor.isSystem() ? null : actor.id(), actor.name(), action, truncate(from, 500), truncate(to, 500), truncate(note, 1000),
                Timestamp.from(clock.instant()));
    }

    // ------------------------------------------------------------------ helpers

    private void apply(ConfigurationItem c, CiRequest r, boolean creating) {
        String name = r.name().trim();
        cis.findByNameIgnoreCase(name).filter(other -> !other.getId().equals(c.getId())).ifPresent(other -> {
            throw ApiException.conflict("A configuration item named '" + other.getName() + "' already exists");
        });
        if (!creating && c.getService() != null && !name.equals(c.getName())) {
            throw ApiException.invalidField("name", "The name of a business-service CI follows its service; rename the service instead");
        }
        if (!creating && c.getService() != null && r.ciType() != CiType.BUSINESS_SERVICE) {
            throw ApiException.invalidField("ciType", "A CI that represents a business service must stay BUSINESS_SERVICE");
        }
        c.setName(name);
        c.setCiType(r.ciType());
        if (r.status() != null || creating) {
            c.setStatus(r.status() == null ? Status.ACTIVE : r.status());
        }
        if (r.environment() != null || creating) {
            c.setEnvironment(r.environment());
        }
        if (r.criticality() != null || creating) {
            c.setCriticality(r.criticality() == null ? Criticality.MEDIUM : r.criticality());
        }
        if (r.description() != null || creating) {
            c.setDescription(blankToNull(r.description()));
        }
        if (r.ownerId() != null) {
            c.setOwner(r.ownerId() == 0L ? null : users.findById(r.ownerId()).filter(User::isActive)
                    .orElseThrow(() -> ApiException.invalidField("ownerId", "The owner must be an active user")));
        }
        if (r.teamId() != null) {
            c.setTeam(r.teamId() == 0L ? null : teams.findById(r.teamId()).filter(Team::isActive)
                    .orElseThrow(() -> ApiException.invalidField("teamId", "Team does not exist or is inactive")));
        }
        if (r.serviceId() != null && r.serviceId() != 0L) {
            if (r.ciType() != CiType.BUSINESS_SERVICE) {
                throw ApiException.invalidField("serviceId", "Only BUSINESS_SERVICE CIs represent a business service");
            }
            if (c.getService() == null || !c.getService().getId().equals(r.serviceId())) {
                if (c.getService() != null) {
                    throw ApiException.invalidField("serviceId", "The service of a business-service CI cannot change");
                }
                BusinessService s = services.findById(r.serviceId()).orElseThrow(() -> ApiException.invalidField("serviceId", "Service does not exist"));
                cis.findByServiceId(s.getId()).ifPresent(other -> {
                    throw ApiException.conflict("Service " + s.getName() + " is already represented by CI '" + other.getName() + "'");
                });
                c.setService(s);
            }
        }
        if (r.attributes() != null) {
            c.setAttributes(validAttributes(r.attributes()));
        }
    }

    static Map<String, Object> validAttributes(Map<String, Object> in) {
        if (in.size() > 50) {
            throw ApiException.invalidField("attributes", "At most 50 attributes");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim();
            if (!ATTRIBUTE_KEY.matcher(key).matches()) {
                throw ApiException.invalidField("attributes", "Attribute names start with a letter and use letters, digits, '_', '.' or '-' (max 60): '"
                        + e.getKey() + "'");
            }
            Object v = e.getValue();
            if (v instanceof String s) {
                if (s.length() > 500) {
                    throw ApiException.invalidField("attributes." + key, "Attribute values are at most 500 characters");
                }
                out.put(key, s);
            } else if (v == null || v instanceof Number || v instanceof Boolean) {
                out.put(key, v);
            } else {
                throw ApiException.invalidField("attributes." + key, "Attribute values must be text, numbers, booleans or null");
            }
        }
        return out;
    }

    private CiDetail detail(ConfigurationItem c) {
        Long id = c.getId();
        List<AssetRef> assets = jdbc.query("select id, asset_tag, name, asset_type, status from assets where ci_id = ? order by asset_tag", (rs, n) ->
                new AssetRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), id);
        List<WorkRef> alerts = jdbc.query("select id, number, title, status from alerts where ci_id = ? and status in ('OPEN', 'ACKNOWLEDGED') "
                + "order by last_seen_at desc limit 10", (rs, n) -> new WorkRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), id);
        Long alertCount = jdbc.queryForObject("select count(*) from alerts where ci_id = ? and status in ('OPEN', 'ACKNOWLEDGED')", Long.class, id);
        List<WorkRef> problems = jdbc.query("select p.id, p.number, p.title, p.status from problem_cis l join problems p on p.id = l.problem_id where l.ci_id = ? "
                + "order by p.created_at desc limit 20", (rs, n) -> new WorkRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), id);
        List<WorkRef> changes = jdbc.query("select x.id, x.number, x.title, x.status from change_cis l join changes x on x.id = l.change_id where l.ci_id = ? "
                + "order by x.created_at desc limit 20", (rs, n) -> new WorkRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), id);
        return new CiDetail(id, c.getName(), c.getCiType(), c.getStatus(), c.getEnvironment(), c.getCriticality(), c.getDescription(),
                c.getOwner() == null ? null : c.getOwner().getId(), c.getOwner() == null ? null : c.getOwner().getFullName(),
                c.getTeam() == null ? null : c.getTeam().getId(), c.getTeam() == null ? null : c.getTeam().getName(),
                c.getService() == null ? null : c.getService().getId(), c.getService() == null ? null : c.getService().getCode(),
                c.getService() == null ? null : c.getService().getName(), c.getAttributes(), relationships(id, true), relationships(id, false), assets, alerts,
                alertCount == null ? 0 : alertCount, problems, changes, c.getCreatedBy() == null ? null : c.getCreatedBy().getFullName(), c.isDemo(),
                c.getCreatedAt(), c.getUpdatedAt());
    }

    private List<RelationshipDto> relationships(Long id, boolean outgoing) {
        String other = outgoing ? "target_id" : "source_id";
        String self = outgoing ? "source_id" : "target_id";
        return jdbc.query("select r.id, r.rel_type, r.description, r.origin, r.is_demo, r.created_at, c.id, c.name, c.ci_type, c.status, c.environment "
                + "from ci_relationships r join configuration_items c on c.id = r." + other + " where r." + self + " = ? order by r.rel_type, lower(c.name)",
                (rs, n) -> {
                    RelationshipType type = RelationshipType.valueOf(rs.getString(2));
                    return new RelationshipDto(rs.getLong(1), type, outgoing ? "OUTGOING" : "INCOMING", outgoing ? type.outgoingLabel() : type.incomingLabel(),
                            new CiRef(rs.getLong(7), rs.getString(8), CiType.valueOf(rs.getString(9)), Status.valueOf(rs.getString(10)),
                                    rs.getString(11) == null ? null : Environment.valueOf(rs.getString(11))), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                            rs.getTimestamp(6).toInstant());
                }, id);
    }

    static CiSummary summary(ConfigurationItem c, long relationships, long alerts) {
        return new CiSummary(c.getId(), c.getName(), c.getCiType(), c.getStatus(), c.getEnvironment(), c.getCriticality(), c.getDescription(),
                c.getOwner() == null ? null : c.getOwner().getId(), c.getOwner() == null ? null : c.getOwner().getFullName(),
                c.getTeam() == null ? null : c.getTeam().getId(), c.getTeam() == null ? null : c.getTeam().getName(),
                c.getService() == null ? null : c.getService().getId(), c.getService() == null ? null : c.getService().getCode(), relationships, alerts,
                c.isDemo(), c.getCreatedAt(), c.getUpdatedAt());
    }

    private static Map<String, String> snapshot(ConfigurationItem c) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", c.getName());
        m.put("ciType", c.getCiType().name());
        m.put("status", c.getStatus().name());
        m.put("environment", c.getEnvironment() == null ? null : c.getEnvironment().name());
        m.put("criticality", c.getCriticality().name());
        m.put("owner", c.getOwner() == null ? null : c.getOwner().getFullName());
        m.put("team", c.getTeam() == null ? null : c.getTeam().getName());
        m.put("description", c.getDescription() == null ? null : Integer.toHexString(c.getDescription().hashCode()));
        return m;
    }

    ConfigurationItem load(Long id) {
        return cis.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Configuration item", id));
    }

    private static Sort sortOf(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.ASC, "name");
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORTS.contains(field)) {
            throw ApiException.badRequest("Cannot sort by '" + field + "'");
        }
        return Sort.by(parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.DESC : Sort.Direction.ASC, field);
    }

    private static String nz(String v) {
        return v == null ? "-" : v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
