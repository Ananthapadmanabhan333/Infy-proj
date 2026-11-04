package com.nexus360.deployment;

import com.nexus360.admin.SettingsService;
import com.nexus360.audit.AuditService;
import com.nexus360.change.Change;
import com.nexus360.change.ChangeRepository;
import com.nexus360.change.ChangeStatus;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.deployment.DeploymentDtos.CalendarDay;
import com.nexus360.deployment.DeploymentDtos.CorrelatedIncident;
import com.nexus360.deployment.DeploymentDtos.DeliveryDto;
import com.nexus360.deployment.DeploymentDtos.DeploymentCalendar;
import com.nexus360.deployment.DeploymentDtos.DeploymentDetail;
import com.nexus360.deployment.DeploymentDtos.DeploymentDto;
import com.nexus360.deployment.DeploymentDtos.DeploymentMetrics;
import com.nexus360.deployment.DeploymentDtos.DeploymentRequest;
import com.nexus360.deployment.DeploymentDtos.DeploymentRisk;
import com.nexus360.deployment.DeploymentDtos.ReleaseDetail;
import com.nexus360.deployment.DeploymentDtos.ReleaseDto;
import com.nexus360.deployment.DeploymentDtos.ReleaseRequest;
import com.nexus360.deployment.DeploymentDtos.RiskFactor;
import com.nexus360.deployment.DeploymentDtos.RollbackRequest;
import com.nexus360.deployment.DeploymentDtos.SourceDto;
import com.nexus360.deployment.DeploymentDtos.SourceRequest;
import com.nexus360.deployment.DeploymentDtos.SourceSaveResult;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.integration.SecretCipher;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.Actor;
import com.nexus360.security.Hashing;
import com.nexus360.user.Role;
import com.nexus360.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases and deployment records: manual records and webhook-reported ones (see {@link DeploymentWebhookService}),
 * status updates, rollback tracking, the deployment calendar, incident correlation, deterministic risk analysis and
 * metrics. Webhook sources keep their shared secret encrypted with {@link SecretCipher}; it is shown once.
 */
@Service
@RequiredArgsConstructor
public class DeploymentService {

    private static final Set<ChangeStatus> AUTHORISED = EnumSet.of(ChangeStatus.APPROVED, ChangeStatus.SCHEDULED, ChangeStatus.IMPLEMENTING,
            ChangeStatus.COMPLETED);

    public record Filter(String search, Long serviceId, Deployment.Environment environment, Deployment.Status status, Long releaseId, Long changeId,
                         Deployment.Source source, Instant from, Instant to) {}

    private final DeploymentRepository deployments;
    private final ReleaseRepository releases;
    private final DeploymentSourceRepository sources;
    private final WebhookDeliveryRepository deliveries;
    private final ChangeRepository changes;
    private final BusinessServiceRepository services;
    private final IncidentRepository incidents;
    private final IncidentMapper incidentMapper;
    private final UserRepository users;
    private final SecretCipher cipher;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ------------------------------------------------------------------ deployments

    @Transactional(readOnly = true)
    public PageResponse<DeploymentDto> list(Filter f, Integer page, Integer size, String sort) {
        Specification<Deployment> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.serviceId() != null) {
                p.add(cb.equal(root.get("service").get("id"), f.serviceId()));
            }
            if (f.environment() != null) {
                p.add(cb.equal(root.get("environment"), f.environment()));
            }
            if (f.status() != null) {
                p.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.releaseId() != null) {
                p.add(cb.equal(root.get("release").get("id"), f.releaseId()));
            }
            if (f.changeId() != null) {
                p.add(cb.equal(root.get("change").get("id"), f.changeId()));
            }
            if (f.source() != null) {
                p.add(cb.equal(root.get("source"), f.source()));
            }
            if (f.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("startedAt"), f.from()));
            }
            if (f.to() != null) {
                p.add(cb.lessThan(root.get("startedAt"), f.to()));
            }
            if (f.search() != null && !f.search().isBlank()) {
                String like = "%" + f.search().trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("number")), like, '\\'), cb.like(cb.lower(root.get("version")), like, '\\'),
                        cb.like(cb.lower(root.get("commitSha")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<Deployment> result = deployments.findAll(spec, PageResponse.pageable(page, size, sortOf(sort,
                Set.of("startedAt", "finishedAt", "number", "status", "environment", "version"), Sort.by(Sort.Direction.DESC, "startedAt"))));
        return PageResponse.of(result, DeploymentService::dto);
    }

    @Transactional(readOnly = true)
    public DeploymentDetail get(Long id) {
        Deployment d = load(id);
        int hours = settings.getInt("deployment.correlation.hours", 12);
        return new DeploymentDetail(dto(d), risk(d), correlated(d, hours), hours,
                deployments.findByRollbackOfIdOrderByStartedAtDesc(id).stream().map(DeploymentService::dto).toList());
    }

    @Transactional
    public DeploymentDto create(DeploymentRequest r, Actor actor) {
        return create(r, actor, false);
    }

    /** {@code simulated}: recorded by a simulation scenario (Release 4) through the same validation and side effects. */
    @Transactional
    public DeploymentDto create(DeploymentRequest r, Actor actor, boolean simulated) {
        Deployment d = new Deployment();
        d.setSimulated(simulated);
        d.setNumber("DEP-" + deployments.nextNumber());
        d.setService(services.findById(r.serviceId()).orElseThrow(() -> ApiException.invalidField("serviceId", "Service does not exist")));
        d.setEnvironment(r.environment());
        d.setVersion(r.version().trim());
        d.setCommitSha(r.commitSha() == null ? null : r.commitSha().toLowerCase(Locale.ROOT));
        d.setPipelineUrl(r.pipelineUrl());
        d.setSource(Deployment.Source.MANUAL);
        d.setActor(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        d.setActorName(actor.name());
        d.setNotes(r.notes());
        Instant now = clock.instant();
        d.setStartedAt(r.startedAt() == null ? now : r.startedAt());
        if (d.getStartedAt().isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw ApiException.invalidField("startedAt", "A deployment record cannot start in the future");
        }
        if (r.releaseId() != null) {
            d.setRelease(releases.findById(r.releaseId()).orElseThrow(() -> ApiException.invalidField("releaseId", "Release does not exist")));
        }
        if (r.changeId() != null) {
            d.setChange(changes.findById(r.changeId()).orElseThrow(() -> ApiException.invalidField("changeId", "Change does not exist")));
        }
        Deployment.Status status = r.status() == null ? Deployment.Status.IN_PROGRESS : r.status();
        if (status == Deployment.Status.ROLLED_BACK) {
            throw ApiException.invalidField("status", "Record a rollback with POST /deployments/{id}/rollback");
        }
        d.setStatus(status);
        if (status != Deployment.Status.IN_PROGRESS) {
            d.setFinishedAt(now);
        }
        deployments.save(d);
        afterStatus(d, null);
        audit.record("DEPLOYMENT_RECORDED", "DEPLOYMENT", d.getNumber(), Map.of("service", d.getService().getCode(), "environment", d.getEnvironment(),
                "version", d.getVersion(), "status", d.getStatus(), "simulated", simulated));
        return dto(d);
    }

    @Transactional
    public DeploymentDto updateStatus(Long id, Deployment.Status status, String notes, Actor actor) {
        Deployment d = load(id);
        Deployment.Status from = d.getStatus();
        if (status == Deployment.Status.ROLLED_BACK) {
            throw ApiException.invalidField("status", "Record a rollback with POST /deployments/{id}/rollback");
        }
        if (from != Deployment.Status.IN_PROGRESS) {
            throw ApiException.conflict(d.getNumber() + " is already " + from);
        }
        if (status == Deployment.Status.IN_PROGRESS) {
            throw ApiException.conflict(d.getNumber() + " is already in progress");
        }
        d.setStatus(status);
        d.setFinishedAt(clock.instant());
        if (notes != null && !notes.isBlank()) {
            d.setNotes(notes.trim());
        }
        afterStatus(d, from);
        audit.record("DEPLOYMENT_STATUS_CHANGED", "DEPLOYMENT", d.getNumber(), Map.of("from", from, "to", status));
        return dto(d);
    }

    /** Marks a deployment ROLLED_BACK and records the rollback deployment (previous successful version unless given). */
    @Transactional
    public DeploymentDto rollback(Long id, RollbackRequest r, Actor actor) {
        Deployment d = load(id);
        if (d.getStatus() == Deployment.Status.ROLLED_BACK) {
            throw ApiException.conflict(d.getNumber() + " was already rolled back");
        }
        if (d.getStatus() == Deployment.Status.IN_PROGRESS) {
            throw ApiException.conflict("Finish " + d.getNumber() + " (SUCCEEDED or FAILED) before rolling it back");
        }
        String target = r.targetVersion() != null && !r.targetVersion().isBlank() ? r.targetVersion().trim()
                : deployments.findPreviousSuccessful(d.getService().getId(), d.getEnvironment(), d.getStartedAt(), PageRequest.of(0, 1)).stream()
                .findFirst().map(Deployment::getVersion)
                .orElseThrow(() -> ApiException.invalidField("targetVersion", "No earlier successful deployment found; give the target version"));
        Instant now = clock.instant();
        Deployment.Status from = d.getStatus();
        d.setStatus(Deployment.Status.ROLLED_BACK);
        d.setRolledBackAt(now);
        d.setRollbackReason(r.reason().trim());
        Deployment back = new Deployment();
        back.setNumber("DEP-" + deployments.nextNumber());
        back.setService(d.getService());
        back.setEnvironment(d.getEnvironment());
        back.setVersion(target);
        back.setRelease(d.getRelease());
        back.setChange(d.getChange());
        back.setRollbackOf(d);
        back.setSource(Deployment.Source.MANUAL);
        back.setActor(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        back.setActorName(actor.name());
        back.setStartedAt(now);
        back.setStatus(Deployment.Status.IN_PROGRESS);
        back.setNotes("Rollback of " + d.getNumber() + " (" + d.getVersion() + " to " + target + "): " + r.reason().trim());
        back.setDemo(d.isDemo());
        deployments.save(back);
        afterStatus(d, from);
        audit.record("DEPLOYMENT_ROLLED_BACK", "DEPLOYMENT", d.getNumber(), Map.of("rollback", back.getNumber(), "targetVersion", target));
        return dto(back);
    }

    /** Release status follows its production deployments; failed production deployments are announced. */
    void afterStatus(Deployment d, Deployment.Status from) {
        Release release = d.getRelease();
        if (release != null && release.getStatus() != Release.Status.CANCELLED) {
            if (d.getStatus() == Deployment.Status.IN_PROGRESS && release.getStatus() == Release.Status.PLANNED) {
                release.setStatus(Release.Status.IN_PROGRESS);
            } else if (d.getEnvironment() == Deployment.Environment.PRODUCTION && d.getRollbackOf() == null) {
                if (d.getStatus() == Deployment.Status.SUCCEEDED) {
                    release.setStatus(Release.Status.DEPLOYED);
                } else if (d.getStatus() == Deployment.Status.FAILED || d.getStatus() == Deployment.Status.ROLLED_BACK) {
                    release.setStatus(Release.Status.FAILED);
                }
            }
        }
        if (d.getStatus() == Deployment.Status.FAILED || d.getStatus() == Deployment.Status.ROLLED_BACK) {
            Set<Long> to = new LinkedHashSet<>();
            users.findByActiveTrueAndRoleIn(List.of(Role.CHANGE_MANAGER)).forEach(u -> to.add(u.getId()));
            if (d.getChange() != null && d.getChange().getAssignee() != null) {
                to.add(d.getChange().getAssignee().getId());
            }
            notifications.notifyUsers(to, "DEPLOYMENT_FAILED", (d.getStatus() == Deployment.Status.FAILED ? "Deployment failed: " : "Deployment rolled back: ")
                    + d.getNumber(), d.getService().getName() + " " + d.getVersion() + " (" + d.getEnvironment() + ")"
                    + (d.getChange() == null ? "" : " for " + d.getChange().getNumber()), "/deployments/" + d.getId(),
                    d.getEnvironment() == Deployment.Environment.PRODUCTION ? Severity.CRITICAL : Severity.WARNING);
        }
    }

    // ------------------------------------------------------------------ risk, correlation, calendar, metrics

    /** Deterministic deployment risk: environment, service tier, change linkage and state, recent failure rate, freeze. */
    DeploymentRisk risk(Deployment d) {
        List<RiskFactor> factors = new ArrayList<>();
        if (d.getEnvironment() == Deployment.Environment.PRODUCTION) {
            factors.add(new RiskFactor("production", "Production deployment", 3));
        }
        int tier = d.getService().getTier();
        if (tier == 1) {
            factors.add(new RiskFactor("tier", "Tier-1 (business-critical) service", 3));
        } else if (tier == 2) {
            factors.add(new RiskFactor("tier", "Tier-2 service", 1));
        }
        Change c = d.getChange();
        if (c == null) {
            if (d.getEnvironment() == Deployment.Environment.PRODUCTION) {
                factors.add(new RiskFactor("noChange", "Production deployment without a linked change", 3));
            }
        } else {
            if (c.getRisk() == Change.Risk.HIGH) {
                factors.add(new RiskFactor("changeRisk", "Linked change " + c.getNumber() + " is high risk", 2));
            } else if (c.getRisk() == Change.Risk.MEDIUM) {
                factors.add(new RiskFactor("changeRisk", "Linked change " + c.getNumber() + " is medium risk", 1));
            }
            if (!AUTHORISED.contains(c.getStatus()) && d.getEnvironment() == Deployment.Environment.PRODUCTION) {
                factors.add(new RiskFactor("changeState", "Linked change " + c.getNumber() + " is " + c.getStatus() + " (not approved)", 2));
            }
        }
        Long total = jdbc.queryForObject("select count(*) from deployments where service_id = ? and started_at >= ? and id <> ? and not simulated", Long.class,
                d.getService().getId(), Timestamp.from(d.getStartedAt().minus(Duration.ofDays(30))), d.getId());
        Long failed = jdbc.queryForObject("select count(*) from deployments where service_id = ? and started_at >= ? and id <> ? and not simulated "
                + "and status in ('FAILED', 'ROLLED_BACK')", Long.class, d.getService().getId(), Timestamp.from(d.getStartedAt().minus(Duration.ofDays(30))), d.getId());
        if (total != null && total >= 3 && failed != null && failed * 100 / total >= 30) {
            factors.add(new RiskFactor("failureRate", failed + " of " + total + " recent deployments of this service failed or were rolled back", 2));
        }
        Long frozen = jdbc.queryForObject("select count(*) from change_freeze_windows where active and starts_at <= ? and ends_at > ? "
                + "and (jsonb_array_length(service_ids) = 0 or service_ids @> to_jsonb(?::bigint))", Long.class,
                Timestamp.from(d.getStartedAt()), Timestamp.from(d.getStartedAt()), d.getService().getId());
        if (frozen != null && frozen > 0) {
            factors.add(new RiskFactor("freeze", "Started during a change freeze", 3));
        }
        int score = factors.stream().mapToInt(RiskFactor::points).sum();
        String level = score >= 10 ? "HIGH" : score >= 5 ? "MEDIUM" : "LOW";
        return new DeploymentRisk(level, score, factors, "Deterministic SA rules (no LLM): environment, tier, change, failure history, freeze");
    }

    private List<CorrelatedIncident> correlated(Deployment d, int hours) {
        List<Long> ids = jdbc.queryForList("select id from incidents where service_id = ? and parent_id is null and created_at >= ? and created_at < ? "
                + "order by created_at", Long.class, d.getService().getId(), Timestamp.from(d.getStartedAt()),
                Timestamp.from(d.getStartedAt().plus(Duration.ofHours(hours))));
        if (ids.isEmpty()) {
            return List.of();
        }
        return incidents.findAllById(ids).stream().sorted(Comparator.comparing(Incident::getCreatedAt))
                .map(i -> new CorrelatedIncident(incidentMapper.summary(i), Duration.between(d.getStartedAt(), i.getCreatedAt()).toMinutes())).toList();
    }

    @Transactional(readOnly = true)
    public DeploymentCalendar calendar(Instant from, Instant to, Long serviceId, Deployment.Environment environment) {
        Instant now = clock.instant();
        Instant f = from == null ? now.minus(Duration.ofDays(30)) : from;
        Instant t = to == null ? now.plus(Duration.ofDays(1)) : to;
        if (!t.isAfter(f) || Duration.between(f, t).toDays() > 92) {
            throw ApiException.badRequest("The calendar range must be positive and at most 92 days");
        }
        ZoneId zone = zone();
        Map<LocalDate, List<Deployment>> byDay = new TreeMap<>();
        for (Deployment d : deployments.findStartedBetween(f, t)) {
            if ((serviceId == null || d.getService().getId().equals(serviceId)) && (environment == null || d.getEnvironment() == environment)) {
                byDay.computeIfAbsent(LocalDate.ofInstant(d.getStartedAt(), zone), k -> new ArrayList<>()).add(d);
            }
        }
        List<CalendarDay> days = byDay.entrySet().stream().map(e -> new CalendarDay(e.getKey(),
                count(e.getValue(), Deployment.Status.SUCCEEDED), count(e.getValue(), Deployment.Status.FAILED),
                count(e.getValue(), Deployment.Status.IN_PROGRESS), count(e.getValue(), Deployment.Status.ROLLED_BACK),
                e.getValue().stream().map(DeploymentService::dto).toList())).toList();
        return new DeploymentCalendar(f, t, days);
    }

    @Transactional(readOnly = true)
    public DeploymentMetrics metrics(Integer days) {
        int d = days == null ? 30 : Math.max(1, Math.min(days, 730));
        Instant to = clock.instant();
        Instant from = to.minus(Duration.ofDays(d));
        Timestamp f = Timestamp.from(from);
        Map<String, Long> byEnv = new LinkedHashMap<>();
        for (Deployment.Environment e : Deployment.Environment.values()) {
            byEnv.put(e.name(), 0L);
        }
        jdbc.query("select environment, count(*) from deployments where started_at >= ? and not simulated group by environment", rs -> {
            byEnv.put(rs.getString(1), rs.getLong(2));
        }, f);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Deployment.Status s : Deployment.Status.values()) {
            byStatus.put(s.name(), 0L);
        }
        jdbc.query("select status, count(*) from deployments where started_at >= ? and not simulated group by status", rs -> {
            byStatus.put(rs.getString(1), rs.getLong(2));
        }, f);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long finished = total - byStatus.get("IN_PROGRESS");
        Double avg = jdbc.queryForObject("select avg(extract(epoch from (finished_at - started_at)) / 60.0) from deployments "
                + "where finished_at is not null and started_at >= ? and not simulated", Double.class, f);
        Long withoutChange = jdbc.queryForObject("select count(*) from deployments where environment = 'PRODUCTION' and change_id is null "
                + "and rollback_of_id is null and started_at >= ? and not simulated", Long.class, f);
        int hours = settings.getInt("deployment.correlation.hours", 12);
        Long withIncidents = jdbc.queryForObject("select count(distinct d.id) from deployments d join incidents i on i.service_id = d.service_id "
                + "and i.parent_id is null and not i.simulated and i.created_at >= d.started_at and i.created_at < d.started_at + make_interval(hours => ?) "
                + "where d.started_at >= ? and not d.simulated", Long.class, hours, f);
        return new DeploymentMetrics(LocalDate.ofInstant(from, ZoneOffset.UTC), LocalDate.ofInstant(to, ZoneOffset.UTC), total, byEnv, byStatus,
                rate(byStatus.get("SUCCEEDED"), finished), rate(byStatus.get("FAILED"), finished), rate(byStatus.get("ROLLED_BACK"), finished),
                avg == null ? null : Math.round(avg * 10.0) / 10.0, withoutChange == null ? 0 : withoutChange, withIncidents == null ? 0 : withIncidents);
    }

    // ------------------------------------------------------------------ releases

    @Transactional(readOnly = true)
    public PageResponse<ReleaseDto> releases(String search, Release.Status status, Long serviceId, Integer page, Integer size, String sort) {
        Specification<Release> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (serviceId != null) {
                p.add(cb.equal(root.get("service").get("id"), serviceId));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("name")), like, '\\'), cb.like(cb.lower(root.get("number")), like, '\\'),
                        cb.like(cb.lower(root.get("version")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<Release> result = releases.findAll(spec, PageResponse.pageable(page, size, sortOf(sort, Set.of("createdAt", "targetAt", "number", "name", "status"),
                Sort.by(Sort.Direction.DESC, "createdAt"))));
        return PageResponse.of(result, result.getContent().stream().map(this::releaseDto).toList());
    }

    @Transactional(readOnly = true)
    public ReleaseDetail release(Long id) {
        Release r = releases.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Release", id));
        return new ReleaseDetail(releaseDto(r), deployments.findByReleaseIdOrderByStartedAtDesc(id).stream().map(DeploymentService::dto).toList());
    }

    @Transactional
    public ReleaseDto saveRelease(Long id, ReleaseRequest r, Actor actor) {
        Release rel = id == null ? new Release() : releases.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Release", id));
        if (id == null) {
            rel.setNumber("REL-" + releases.nextNumber());
            rel.setOwner(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        }
        rel.setName(r.name().trim());
        rel.setVersion(r.version().trim());
        rel.setDescription(r.description());
        if (r.status() != null) {
            rel.setStatus(r.status());
        }
        rel.setService(r.serviceId() == null ? null : services.findById(r.serviceId())
                .orElseThrow(() -> ApiException.invalidField("serviceId", "Service does not exist")));
        rel.setChange(r.changeId() == null ? null : changes.findById(r.changeId())
                .orElseThrow(() -> ApiException.invalidField("changeId", "Change does not exist")));
        rel.setTargetAt(r.targetAt());
        releases.save(rel);
        audit.record(id == null ? "RELEASE_CREATED" : "RELEASE_UPDATED", "RELEASE", rel.getNumber(), Map.of("name", rel.getName(),
                "version", rel.getVersion(), "status", rel.getStatus()));
        return releaseDto(rel);
    }

    // ------------------------------------------------------------------ webhook sources

    @Transactional(readOnly = true)
    public List<SourceDto> sources() {
        return sources.findAllByOrderByNameAsc().stream().map(DeploymentService::sourceDto).toList();
    }

    @Transactional
    public SourceSaveResult saveSource(Long id, SourceRequest r, Actor actor) {
        DeploymentSource s = id == null ? new DeploymentSource() : sources.findWithServiceById(id).orElseThrow(() -> ApiException.notFound("Webhook source", id));
        if ((id == null || !s.getName().equalsIgnoreCase(r.name().trim())) && sources.existsByNameIgnoreCase(r.name().trim())) {
            throw ApiException.conflict("A webhook source named " + r.name().trim() + " exists");
        }
        if (id != null && s.getProvider() != r.provider()) {
            throw ApiException.invalidField("provider", "The provider of a source cannot change; create a new source");
        }
        s.setName(r.name().trim());
        s.setProvider(r.provider());
        s.setService(services.findById(r.serviceId()).orElseThrow(() -> ApiException.invalidField("serviceId", "Service does not exist")));
        s.setDefaultEnvironment(r.defaultEnvironment() == null ? Deployment.Environment.PRODUCTION : r.defaultEnvironment());
        s.setActive(r.active() == null || r.active());
        String secret = null;
        if (id == null) {
            secret = newSecret();
            s.setSecretEncrypted(cipher.encrypt(secret));
            s.setCreatedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        }
        sources.save(s);
        audit.record(id == null ? "DEPLOYMENT_SOURCE_CREATED" : "DEPLOYMENT_SOURCE_UPDATED", "DEPLOYMENT_SOURCE", s.getId(),
                Map.of("name", s.getName(), "provider", s.getProvider(), "active", s.isActive()));
        return new SourceSaveResult(sourceDto(s), secret);
    }

    @Transactional
    public SourceSaveResult rotateSecret(Long id) {
        DeploymentSource s = sources.findWithServiceById(id).orElseThrow(() -> ApiException.notFound("Webhook source", id));
        String secret = newSecret();
        s.setSecretEncrypted(cipher.encrypt(secret));
        audit.record("DEPLOYMENT_SOURCE_SECRET_ROTATED", "DEPLOYMENT_SOURCE", s.getId(), Map.of("name", s.getName()));
        return new SourceSaveResult(sourceDto(s), secret);
    }

    @Transactional(readOnly = true)
    public List<DeliveryDto> deliveries(Long id, Integer size) {
        sources.findById(id).orElseThrow(() -> ApiException.notFound("Webhook source", id));
        return deliveries.findBySourceIdOrderByReceivedAtDescIdDesc(id, PageRequest.of(0, size == null ? 50 : Math.max(1, Math.min(size, 200)))).stream()
                .map(x -> new DeliveryDto(x.getId(), x.getDeliveryId(), x.getEventType(), x.getOutcome(), x.getMessage(), x.getDeploymentId(),
                        x.getReceivedAt())).toList();
    }

    private static String newSecret() {
        return "whsec_" + Hashing.randomToken(32);
    }

    // ------------------------------------------------------------------ helpers

    Deployment load(Long id) {
        return deployments.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Deployment", id));
    }

    private ReleaseDto releaseDto(Release r) {
        Long count = jdbc.queryForObject("select count(*) from deployments where release_id = ?", Long.class, r.getId());
        return new ReleaseDto(r.getId(), r.getNumber(), r.getName(), r.getVersion(), r.getDescription(), r.getStatus(),
                r.getService() == null ? null : r.getService().getId(), r.getService() == null ? null : r.getService().getName(),
                r.getChange() == null ? null : r.getChange().getId(), r.getChange() == null ? null : r.getChange().getNumber(),
                r.getOwner() == null ? null : r.getOwner().getId(), r.getOwner() == null ? null : r.getOwner().getFullName(), r.getTargetAt(),
                count == null ? 0 : count, r.isDemo(), r.getCreatedAt(), r.getUpdatedAt());
    }

    static DeploymentDto dto(Deployment d) {
        BusinessService s = d.getService();
        return new DeploymentDto(d.getId(), d.getNumber(), d.getRelease() == null ? null : d.getRelease().getId(),
                d.getRelease() == null ? null : d.getRelease().getNumber(), d.getRelease() == null ? null : d.getRelease().getName(),
                d.getChange() == null ? null : d.getChange().getId(), d.getChange() == null ? null : d.getChange().getNumber(), s.getId(), s.getName(),
                d.getEnvironment(), d.getVersion(), d.getCommitSha(), d.getPipelineUrl(), d.getStatus(), d.getSource(),
                d.getWebhookSource() == null ? null : d.getWebhookSource().getId(), d.getWebhookSource() == null ? null : d.getWebhookSource().getName(),
                d.getExternalId(), d.getActorName(), d.getStartedAt(), d.getFinishedAt(),
                d.getFinishedAt() == null ? null : Duration.between(d.getStartedAt(), d.getFinishedAt()).toSeconds(),
                d.getRollbackOf() == null ? null : d.getRollbackOf().getId(), d.getRollbackOf() == null ? null : d.getRollbackOf().getNumber(),
                d.getRolledBackAt(), d.getRollbackReason(), d.getNotes(), d.isDemo(), d.getCreatedAt(), d.isSimulated());
    }

    static SourceDto sourceDto(DeploymentSource s) {
        return new SourceDto(s.getId(), s.getName(), s.getProvider(), s.getService().getId(), s.getService().getName(), s.getDefaultEnvironment(),
                s.isActive(), "/api/deployments/webhooks/" + s.getId(), s.getLastDeliveryAt(), s.isDemo(), s.getCreatedAt());
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(settings.getString(SettingsService.ORG_TIMEZONE, "Asia/Kolkata"));
        } catch (DateTimeException e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }

    private static long count(List<Deployment> list, Deployment.Status status) {
        return list.stream().filter(d -> d.getStatus() == status).count();
    }

    private static Double rate(Long part, long whole) {
        return whole == 0 || part == null ? null : Math.round(1000.0 * part / whole) / 10.0;
    }

    static Sort sortOf(String sort, Set<String> allowed, Sort fallback) {
        if (sort == null || sort.isBlank()) {
            return fallback;
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!allowed.contains(field)) {
            throw ApiException.badRequest("Cannot sort by '" + field + "'");
        }
        return Sort.by(parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.ASC : Sort.Direction.DESC, field);
    }
}
