package com.nexus360.cmdb;

import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.ServiceCatalogDtos.AvailabilityPoint;
import com.nexus360.cmdb.ServiceCatalogDtos.BusinessServiceRequest;
import com.nexus360.cmdb.ServiceCatalogDtos.ServiceDetail;
import com.nexus360.cmdb.ServiceCatalogDtos.ServiceDto;
import com.nexus360.cmdb.ServiceCatalogDtos.ServiceRef;
import com.nexus360.cmdb.ServiceCatalogDtos.ServiceStatus;
import com.nexus360.common.ApiException;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.incident.Priority;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service status and availability derived from incident data: an active P1 means OUTAGE, an active P2 means
 * DEGRADED, and a service whose direct dependency is in OUTAGE is DEGRADED with the dependency named in
 * {@code statusReason}. Availability counts P1 time as full downtime and P2 time at 25%, merging overlaps.
 */
@Service
@RequiredArgsConstructor
public class ServiceHealthService {

    private static final Duration WINDOW = Duration.ofDays(30);
    private static final int HISTORY_DAYS = 90;
    private static final double DEGRADED_WEIGHT = 0.25;

    private final BusinessServiceRepository services;
    private final IncidentRepository incidents;
    private final DepartmentRepository departments;
    private final UserRepository users;
    private final IncidentMapper mapper;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final org.springframework.beans.factory.ObjectProvider<ServiceMaintenanceSource> maintenanceSources;
    private final com.nexus360.team.TeamRepository teams;
    private final ServiceCiSync ciSync;
    private final Clock clock;

    /** Computed state of the whole catalogue (one pass, a handful of queries). */
    private record Health(Map<Long, ServiceDto> byId, Map<Long, Set<Long>> dependsOn) {}

    @Transactional(readOnly = true)
    public List<ServiceDto> list() {
        Health health = health();
        return services.findAllByOrderByTierAscNameAsc().stream().map(s -> health.byId().get(s.getId())).toList();
    }

    @Transactional(readOnly = true)
    public ServiceDetail detail(Long id) {
        BusinessService s = services.findWithDepartmentById(id).orElseThrow(() -> ApiException.notFound("Service", id));
        Instant now = clock.instant();
        Health health = health();
        ServiceDto d = health.byId().get(s.getId());
        Instant from = now.minus(WINDOW);
        Double compliance = jdbc.queryForObject("""
                select case when count(*) = 0 then null
                            else round(100.0 * sum(case when s.outcome = 'MET' then 1 else 0 end) / count(*), 1) end
                from incident_slas s join incidents i on i.id = s.incident_id
                where i.service_id = ? and not i.simulated and s.completed_at >= ? and s.outcome in ('MET', 'MISSED')""",
                Double.class, id, java.sql.Timestamp.from(from));
        Double mttr = jdbc.queryForObject("""
                select round(avg(extract(epoch from (resolved_at - created_at)) / 60)::numeric, 1)
                from incidents where service_id = ? and parent_id is null and not simulated and resolved_at >= ?
                  and coalesce(resolution_code, '') <> 'CANCELLED'""",
                Double.class, id, java.sql.Timestamp.from(from));
        List<ServiceRef> dependencies = health.dependsOn().getOrDefault(id, Set.of()).stream().map(health.byId()::get)
                .filter(Objects::nonNull).sorted(Comparator.comparing(ServiceDto::name)).map(ServiceHealthService::ref).toList();
        List<ServiceRef> dependents = health.dependsOn().entrySet().stream().filter(e -> e.getValue().contains(id))
                .map(e -> health.byId().get(e.getKey())).filter(Objects::nonNull).sorted(Comparator.comparing(ServiceDto::name))
                .map(ServiceHealthService::ref).toList();
        return new ServiceDetail(d.id(), d.code(), d.name(), d.description(), d.departmentId(), d.departmentName(), d.ownerId(),
                d.ownerName(), d.tier(), d.status(), d.availability30d(), d.openIncidents(), d.criticalOpen(), d.maintenance(),
                d.keywords(), d.demo(), d.statusReason(), d.defaultCategory(), d.dependsOnIds(),
                mapper.summaries(incidents.findTop10ByServiceIdOrderByCreatedAtDesc(id)), compliance, mttr,
                dependencies, dependents, availabilityHistory(id, now), d.defaultTeamId(), d.defaultTeamName());
    }

    /**
     * Creates or updates a service. {@code dependsOnIds}: null keeps the current dependencies, an empty list clears them;
     * a service cannot depend on itself and dependencies must not form a cycle.
     */
    @Transactional
    public ServiceDto save(Long id, BusinessServiceRequest request) {
        BusinessService s = id == null ? new BusinessService() : services.findById(id).orElseThrow(() -> ApiException.notFound("Service", id));
        if (id == null && services.existsByCodeIgnoreCase(request.code())) {
            throw ApiException.conflict("Service code already in use");
        }
        s.setCode(request.code());
        s.setName(request.name().trim());
        s.setDescription(request.description());
        s.setDepartment(departments.findById(request.departmentId()).orElseThrow(() -> ApiException.badRequest("Department does not exist")));
        s.setOwner(request.ownerId() == null ? null : users.findById(request.ownerId()).orElseThrow(() -> ApiException.badRequest("Owner does not exist")));
        s.setTier(request.tier());
        s.setKeywords(request.keywords() == null ? null : String.join(",", request.keywords()));
        s.setDefaultCategory(request.defaultCategory());
        s.setMaintenance(request.maintenance());
        if (request.defaultTeamId() != null) {
            if (request.defaultTeamId() == 0L) {
                s.setDefaultTeam(null);
            } else {
                com.nexus360.team.Team team = teams.findWithDetailsById(request.defaultTeamId())
                        .orElseThrow(() -> ApiException.badRequest("Team " + request.defaultTeamId() + " does not exist"));
                if (!team.getDepartment().getId().equals(s.getDepartment().getId())) {
                    throw ApiException.badRequest("The default team must belong to the service's department");
                }
                s.setDefaultTeam(team);
            }
        }
        services.save(s);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("maintenance", s.isMaintenance());
        if (request.dependsOnIds() != null) {
            Set<Long> wanted = request.dependsOnIds().stream().filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
            if (wanted.contains(s.getId())) {
                throw ApiException.badRequest("A service cannot depend on itself");
            }
            List<BusinessService> targets = services.findAllById(wanted);
            if (targets.size() != wanted.size()) {
                throw ApiException.badRequest("Unknown service in dependsOnIds");
            }
            Map<Long, Set<Long>> graph = dependencyGraph();
            graph.put(s.getId(), wanted);
            String cycle = findCycle(graph, s.getId());
            if (cycle != null) {
                throw ApiException.badRequest("Dependencies would create a cycle: " + cycle);
            }
            s.getDependsOn().clear();
            s.getDependsOn().addAll(targets);
            details.put("dependsOn", targets.stream().map(BusinessService::getCode).sorted().toList());
        }
        services.flush();
        // Release 3 CMDB: the service's BUSINESS_SERVICE CI and its dependency relationships follow the service.
        ciSync.syncService(s.getId());
        audit.record(id == null ? "SERVICE_CREATED" : "SERVICE_UPDATED", "SERVICE", s.getCode(), details);
        return health().byId().get(s.getId());
    }

    // ------------------------------------------------------------------ status

    private Health health() {
        Instant now = clock.instant();
        Map<Long, Map<Priority, Long>> active = activeCounts();
        Map<Long, Double> availability = availability(now);
        Map<Long, Set<Long>> graph = dependencyGraph();
        List<BusinessService> all = services.findAllByOrderByTierAscNameAsc();
        Map<Long, String> maintenance = activeMaintenance(now);
        Map<Long, ServiceStatus> own = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (BusinessService s : all) {
            names.put(s.getId(), s.getName());
            own.put(s.getId(), maintenance.containsKey(s.getId()) ? ServiceStatus.MAINTENANCE : ownStatus(s, active.getOrDefault(s.getId(), Map.of())));
        }
        Map<Long, ServiceDto> byId = new LinkedHashMap<>();
        for (BusinessService s : all) {
            Map<Priority, Long> counts = active.getOrDefault(s.getId(), Map.of());
            ServiceStatus status = own.get(s.getId());
            String reason = maintenance.containsKey(s.getId()) && !s.isMaintenance()
                    ? "Planned maintenance: " + maintenance.get(s.getId()) : ownReason(status, counts);
            if (status == ServiceStatus.OPERATIONAL) {
                List<String> down = graph.getOrDefault(s.getId(), Set.of()).stream()
                        .filter(dep -> own.get(dep) == ServiceStatus.OUTAGE).map(names::get).filter(Objects::nonNull).sorted().toList();
                if (!down.isEmpty()) {
                    status = ServiceStatus.DEGRADED;
                    reason = down.size() == 1 ? "Dependency " + down.get(0) + " is in outage"
                            : "Dependencies " + String.join(", ", down) + " are in outage";
                }
            }
            long critical = counts.getOrDefault(Priority.CRITICAL, 0L);
            long open = counts.values().stream().mapToLong(Long::longValue).sum();
            byId.put(s.getId(), new ServiceDto(s.getId(), s.getCode(), s.getName(), s.getDescription(), s.getDepartment().getId(),
                    s.getDepartment().getName(), s.getOwner() == null ? null : s.getOwner().getId(),
                    s.getOwner() == null ? null : s.getOwner().getFullName(), s.getTier(), status,
                    availability.getOrDefault(s.getId(), 100.0), open, critical, s.isMaintenance(), s.keywordList(), s.isDemo(), reason,
                    s.getDefaultCategory(), graph.getOrDefault(s.getId(), Set.of()).stream().sorted().toList(),
                    s.getDefaultTeam() == null ? null : s.getDefaultTeam().getId(), s.getDefaultTeam() == null ? null : s.getDefaultTeam().getName()));
        }
        return new Health(byId, graph);
    }

    private static ServiceStatus ownStatus(BusinessService s, Map<Priority, Long> active) {
        if (s.isMaintenance()) {
            return ServiceStatus.MAINTENANCE;
        }
        if (active.getOrDefault(Priority.CRITICAL, 0L) > 0) {
            return ServiceStatus.OUTAGE;
        }
        return active.getOrDefault(Priority.HIGH, 0L) > 0 ? ServiceStatus.DEGRADED : ServiceStatus.OPERATIONAL;
    }

    private static String ownReason(ServiceStatus status, Map<Priority, Long> active) {
        return switch (status) {
            case MAINTENANCE -> "Under planned maintenance";
            case OUTAGE -> active.getOrDefault(Priority.CRITICAL, 0L) + " critical incident(s) open";
            case DEGRADED -> active.getOrDefault(Priority.HIGH, 0L) + " high-priority incident(s) open";
            case OPERATIONAL -> null;
        };
    }

    private static ServiceRef ref(ServiceDto d) {
        return new ServiceRef(d.id(), d.code(), d.name(), d.status());
    }

    /** Edges service → services it depends on. */
    private Map<Long, Set<Long>> dependencyGraph() {
        Map<Long, Set<Long>> graph = new HashMap<>();
        jdbc.query("select service_id, depends_on_id from service_dependencies", rs -> {
            graph.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getLong(2));
        });
        return graph;
    }

    /** A dependency path from {@code start} back to itself, or null. */
    static String findCycle(Map<Long, Set<Long>> graph, Long start) {
        Deque<List<Long>> stack = new ArrayDeque<>();
        for (Long next : graph.getOrDefault(start, Set.of())) {
            stack.push(List.of(start, next));
        }
        Set<Long> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            List<Long> path = stack.pop();
            Long last = path.get(path.size() - 1);
            if (last.equals(start)) {
                return path.stream().map(String::valueOf).collect(Collectors.joining(" -> "));
            }
            if (!seen.add(last)) {
                continue;
            }
            for (Long next : graph.getOrDefault(last, Set.of())) {
                List<Long> extended = new ArrayList<>(path);
                extended.add(next);
                stack.push(extended);
            }
        }
        return null;
    }

    /** Active top-level incidents per service and priority (children are represented by their parent). */
    private Map<Long, Map<Priority, Long>> activeCounts() {
        Map<Long, Map<Priority, Long>> result = new HashMap<>();
        for (Object[] row : incidents.countActiveByServiceAndPriority(IncidentStatus.ACTIVE)) {
            result.computeIfAbsent((Long) row[0], k -> new HashMap<>()).put((Priority) row[1], (Long) row[2]);
        }
        return result;
    }

    // ------------------------------------------------------------------ availability

    Map<Long, Double> availability(Instant now) {
        Instant from = now.minus(WINDOW);
        List<Incident> contributors = incidents.findDowntimeContributors(List.of(Priority.CRITICAL, Priority.HIGH), from, now).stream()
                .filter(i -> !i.isSimulated()).toList();
        Map<Long, List<Incident>> byService = contributors.stream().collect(Collectors.groupingBy(i -> i.getService().getId()));
        Map<Long, Double> result = new HashMap<>();
        double window = WINDOW.getSeconds();
        byService.forEach((serviceId, list) -> result.put(serviceId, availabilityPercent(list, from, now, window)));
        return result;
    }

    /**
     * Downtime per service in [from, to) with the same rules as the availability figures (Release 3 analytics): merged P1 time
     * ({@code outageSeconds}, full downtime) and merged P2 time ({@code degradedSeconds}, weighted {@link #DEGRADED_WEIGHT});
     * {@code incidents} = contributing top-level, non-cancelled P1/P2 incidents.
     */
    public record Downtime(double outageSeconds, double degradedSeconds, int incidents) {

        /** Availability % over a window of {@code windowSeconds} (two decimals, ≥ 0). */
        public double availability(double windowSeconds) {
            double downtime = outageSeconds + DEGRADED_WEIGHT * degradedSeconds;
            return windowSeconds <= 0 ? 100.0 : Math.max(0, Math.round((100 - downtime * 100 / windowSeconds) * 100) / 100.0);
        }
    }

    public Map<Long, Downtime> downtime(Instant from, Instant to) {
        return downtime(from, to, false);
    }

    /** Simulated incidents (simulation mode) never count as downtime unless {@code includeSimulated}. */
    public Map<Long, Downtime> downtime(Instant from, Instant to, boolean includeSimulated) {
        List<Incident> contributors = incidents.findDowntimeContributors(List.of(Priority.CRITICAL, Priority.HIGH), from, to).stream()
                .filter(i -> includeSimulated || !i.isSimulated()).toList();
        Map<Long, Downtime> result = new HashMap<>();
        contributors.stream().collect(Collectors.groupingBy(i -> i.getService().getId())).forEach((serviceId, list) -> result.put(serviceId,
                new Downtime(mergedSeconds(list.stream().filter(i -> i.getPriority() == Priority.CRITICAL).toList(), from, to),
                        mergedSeconds(list.stream().filter(i -> i.getPriority() == Priority.HIGH).toList(), from, to), list.size())));
        return result;
    }

    /** Business services in an active maintenance window right now, with the window title. */
    public Map<Long, String> activeMaintenance(Instant now) {
        Map<Long, String> result = new HashMap<>();
        maintenanceSources.orderedStream().forEach(source -> source.activeMaintenance(now).forEach(result::putIfAbsent));
        return result;
    }

    /** Daily availability for the last 90 days (days in the default business calendar's time zone; today up to now). */
    public List<AvailabilityPoint> availabilityHistory(Long serviceId, Instant now) {
        ZoneId zone = zone();
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate first = today.minusDays(HISTORY_DAYS - 1L);
        Instant windowStart = first.atStartOfDay(zone).toInstant();
        List<Incident> list = incidents.findDowntimeContributorsForService(serviceId, List.of(Priority.CRITICAL, Priority.HIGH), windowStart, now).stream()
                .filter(i -> !i.isSimulated()).toList();
        List<AvailabilityPoint> points = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            Instant start = day.atStartOfDay(zone).toInstant();
            Instant end = day.equals(today) ? now : day.plusDays(1).atStartOfDay(zone).toInstant();
            double seconds = Math.max(1, Duration.between(start, end).getSeconds());
            points.add(new AvailabilityPoint(day.toString(), list.isEmpty() ? 100.0 : availabilityPercent(list, start, end, seconds)));
        }
        return points;
    }

    private static double availabilityPercent(List<Incident> list, Instant from, Instant to, double windowSeconds) {
        double outage = mergedSeconds(list.stream().filter(i -> i.getPriority() == Priority.CRITICAL).toList(), from, to);
        double degraded = mergedSeconds(list.stream().filter(i -> i.getPriority() == Priority.HIGH).toList(), from, to);
        double downtime = outage + DEGRADED_WEIGHT * degraded;
        return Math.max(0, Math.round((100 - downtime * 100 / windowSeconds) * 100) / 100.0);
    }

    private ZoneId zone() {
        List<String> zones = jdbc.queryForList("select timezone from business_hours_calendars where is_default limit 1", String.class);
        try {
            return zones.isEmpty() ? ZoneOffset.UTC : ZoneId.of(zones.get(0));
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static double mergedSeconds(List<Incident> list, Instant from, Instant to) {
        List<Instant[]> intervals = new ArrayList<>();
        for (Incident i : list) {
            Instant start = i.getCreatedAt().isBefore(from) ? from : i.getCreatedAt();
            Instant end = i.getResolvedAt() == null || i.getResolvedAt().isAfter(to) ? to : i.getResolvedAt();
            if (end.isAfter(start)) {
                intervals.add(new Instant[]{start, end});
            }
        }
        intervals.sort(Comparator.comparing(a -> a[0]));
        double total = 0;
        Instant curStart = null;
        Instant curEnd = null;
        for (Instant[] iv : intervals) {
            if (curEnd == null || iv[0].isAfter(curEnd)) {
                if (curEnd != null) {
                    total += Duration.between(curStart, curEnd).getSeconds();
                }
                curStart = iv[0];
                curEnd = iv[1];
            } else if (iv[1].isAfter(curEnd)) {
                curEnd = iv[1];
            }
        }
        if (curEnd != null) {
            total += Duration.between(curStart, curEnd).getSeconds();
        }
        return total;
    }
}
