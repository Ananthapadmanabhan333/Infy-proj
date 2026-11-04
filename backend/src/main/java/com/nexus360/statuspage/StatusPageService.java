package com.nexus360.statuspage;

import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.cmdb.ServiceCatalogDtos.AvailabilityPoint;
import com.nexus360.cmdb.ServiceCatalogDtos.ServiceDto;
import com.nexus360.cmdb.ServiceCatalogDtos.ServiceStatus;
import com.nexus360.cmdb.ServiceHealthService;
import com.nexus360.common.ApiException;
import com.nexus360.email.EmailService;
import com.nexus360.email.EmailTemplates;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.security.Actor;
import com.nexus360.statuspage.StatusPageDtos.ComponentDto;
import com.nexus360.statuspage.StatusPageDtos.ComponentRef;
import com.nexus360.statuspage.StatusPageDtos.ComponentRequest;
import com.nexus360.statuspage.StatusPageDtos.MaintenanceDto;
import com.nexus360.statuspage.StatusPageDtos.MaintenanceRequest;
import com.nexus360.statuspage.StatusPageDtos.PublicComponent;
import com.nexus360.statuspage.StatusPageDtos.PublicMaintenance;
import com.nexus360.statuspage.StatusPageDtos.PublicStatus;
import com.nexus360.statuspage.StatusPageDtos.PublicUpdate;
import com.nexus360.statuspage.StatusPageDtos.SettingsDto;
import com.nexus360.statuspage.StatusPageDtos.SettingsRequest;
import com.nexus360.statuspage.StatusPageDtos.StatusPageView;
import com.nexus360.statuspage.StatusPageDtos.SubscriberCounts;
import com.nexus360.statuspage.StatusPageDtos.UpdateDto;
import com.nexus360.statuspage.StatusPageDtos.UpdateRequest;
import com.nexus360.statuspage.StatusPageDtos.UptimeDay;
import com.nexus360.user.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Status page: components (published business services) with live status from the service health engine, 90-day uptime
 * from the same incident-based availability, maintenance windows (services in an active window show MAINTENANCE) and
 * incident communications. The public view contains public components and public updates only, never incident details.
 */
@Service
@RequiredArgsConstructor
public class StatusPageService {

    private static final Set<Role> EDITORS = EnumSet.of(Role.SYSTEM_ADMIN, Role.IT_ADMIN, Role.INCIDENT_MANAGER);
    private static final Duration UPDATE_HISTORY = Duration.ofDays(30);
    private static final Duration MAINTENANCE_HISTORY = Duration.ofDays(7);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final StatusPageSettingsRepository settingsRepository;
    private final StatusComponentRepository components;
    private final MaintenanceWindowRepository windows;
    private final StatusUpdateRepository updates;
    private final StatusSubscriberRepository subscribers;
    private final BusinessServiceRepository services;
    private final IncidentRepository incidents;
    private final ServiceHealthService serviceHealth;
    private final StatusSubscriptionService subscriptions;
    private final EmailService email;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    // ------------------------------------------------------------------ views

    @Transactional(readOnly = true)
    public StatusPageView view(Actor actor) {
        Instant now = clock.instant();
        boolean insider = actor.isSystem() || actor.role().isReader();
        StatusPageSettings s = settings();
        List<StatusComponent> all = components.findAllOrdered().stream().filter(c -> insider || c.isPublicVisible()).toList();
        Map<Long, ServiceDto> health = health();
        Map<Long, List<MaintenanceWindow>> maintenanceByComponent = new LinkedHashMap<>();
        List<MaintenanceWindow> recentWindows = windows.findEndingAfter(now.minus(Duration.ofDays(90)));
        recentWindows.forEach(w -> w.getComponents().forEach(c -> maintenanceByComponent.computeIfAbsent(c.getId(), k -> new ArrayList<>()).add(w)));
        List<ComponentDto> dtos = all.stream().map(c -> componentDto(c, health.get(c.getService().getId()),
                maintenanceByComponent.getOrDefault(c.getId(), List.of()), now)).toList();
        List<MaintenanceDto> maintenance = recentWindows.stream()
                .filter(w -> !w.getEndsAt().isBefore(now.minus(MAINTENANCE_HISTORY)))
                .filter(w -> insider || w.getComponents().stream().anyMatch(StatusComponent::isPublicVisible))
                .map(w -> maintenanceDto(w, now, insider)).toList();
        List<UpdateDto> updateDtos = (insider ? updates.findSince(now.minus(UPDATE_HISTORY)) : updates.findPublicSince(now.minus(UPDATE_HISTORY)))
                .stream().map(StatusPageService::updateDto).toList();
        SubscriberCounts counts = !actor.isSystem() && EDITORS.contains(actor.role())
                ? new SubscriberCounts(subscribers.countByConfirmedAtIsNotNull(), subscribers.countByConfirmedAtIsNull()) : null;
        return new StatusPageView(settingsDto(s), overall(dtos.stream().map(ComponentDto::status).toList()), dtos, maintenance, updateDtos,
                counts, email.configured(), now);
    }

    /** The public page; {@code 404} with a generic message while the page is not published. */
    @Transactional(readOnly = true)
    public PublicStatus publicView() {
        StatusPageSettings s = settings();
        if (!s.isPublicEnabled()) {
            throw StatusSubscriptionService.notPublished();
        }
        Instant now = clock.instant();
        List<StatusComponent> visible = components.findAllOrdered().stream().filter(StatusComponent::isPublicVisible).toList();
        Set<Long> visibleIds = visible.stream().map(StatusComponent::getId).collect(Collectors.toSet());
        Map<Long, ServiceDto> health = health();
        List<MaintenanceWindow> recentWindows = windows.findEndingAfter(now.minus(Duration.ofDays(90)));
        Map<Long, List<MaintenanceWindow>> byComponent = new LinkedHashMap<>();
        recentWindows.forEach(w -> w.getComponents().forEach(c -> byComponent.computeIfAbsent(c.getId(), k -> new ArrayList<>()).add(w)));
        List<PublicComponent> list = visible.stream().map(c -> {
            ComponentDto d = componentDto(c, health.get(c.getService().getId()), byComponent.getOrDefault(c.getId(), List.of()), now);
            return new PublicComponent(d.id(), d.displayName(), d.groupName(), d.status(), d.uptime90d(), d.history());
        }).toList();
        List<PublicMaintenance> maintenance = recentWindows.stream()
                .filter(w -> w.effectiveStatus(now) != MaintenanceWindow.Status.CANCELLED && !w.getEndsAt().isBefore(now.minus(MAINTENANCE_HISTORY)))
                .filter(w -> w.getComponents().stream().anyMatch(c -> visibleIds.contains(c.getId())))
                .map(w -> new PublicMaintenance(w.getId(), w.getTitle(), w.getDescription(), w.getStartsAt(), w.getEndsAt(), w.effectiveStatus(now),
                        w.getComponents().stream().filter(c -> visibleIds.contains(c.getId())).map(StatusComponent::getDisplayName).sorted().toList()))
                .toList();
        List<PublicUpdate> publicUpdates = updates.findPublicSince(now.minus(UPDATE_HISTORY)).stream()
                .map(u -> new PublicUpdate(u.getId(), u.getTitle(), u.getStatus(), u.getMessage(), u.getPostedAt())).toList();
        return new PublicStatus(s.getTitle(), s.getDescription(), overall(list.stream().map(PublicComponent::status).toList()), now, list,
                maintenance, publicUpdates, subscriptions.available());
    }

    // ------------------------------------------------------------------ editors

    @Transactional
    public SettingsDto updateSettings(SettingsRequest r, Actor actor) {
        StatusPageSettings s = settings();
        boolean wasPublic = s.isPublicEnabled();
        s.setTitle(r.title().trim());
        s.setDescription(blankToNull(r.description()));
        s.setPublicEnabled(r.publicEnabled());
        s.setUpdatedById(actor.id());
        s.setUpdatedAt(clock.instant());
        audit.record("STATUS_PAGE_SETTINGS_UPDATED", "STATUS_PAGE", 1, Map.of("title", s.getTitle(), "publicEnabled", s.isPublicEnabled(),
                "wasPublic", wasPublic));
        return settingsDto(s);
    }

    @Transactional(readOnly = true)
    public List<ComponentDto> components(Actor actor) {
        return view(actor).components();
    }

    @Transactional
    public ComponentDto createComponent(ComponentRequest r) {
        BusinessService service = services.findById(r.serviceId()).orElseThrow(() -> ApiException.invalidField("serviceId", "Service does not exist"));
        if (components.existsByServiceId(service.getId())) {
            throw ApiException.conflict(service.getName() + " is already on the status page");
        }
        StatusComponent c = new StatusComponent();
        c.setService(service);
        apply(c, r);
        components.save(c);
        audit.record("STATUS_COMPONENT_CREATED", "STATUS_COMPONENT", c.getId(), Map.of("service", service.getCode(), "public", c.isPublicVisible()));
        return single(c);
    }

    @Transactional
    public ComponentDto updateComponent(Long id, ComponentRequest r) {
        StatusComponent c = components.findById(id).orElseThrow(() -> ApiException.notFound("Status component", id));
        if (!c.getService().getId().equals(r.serviceId())) {
            throw ApiException.badRequest("A component's service cannot be changed; create a new component instead");
        }
        apply(c, r);
        audit.record("STATUS_COMPONENT_UPDATED", "STATUS_COMPONENT", c.getId(), Map.of("public", c.isPublicVisible()));
        return single(c);
    }

    @Transactional
    public void deleteComponent(Long id) {
        StatusComponent c = components.findById(id).orElseThrow(() -> ApiException.notFound("Status component", id));
        audit.record("STATUS_COMPONENT_DELETED", "STATUS_COMPONENT", c.getId(), Map.of("service", c.getService().getCode()));
        components.delete(c);
    }

    @Transactional
    public MaintenanceDto createMaintenance(MaintenanceRequest r, Actor actor) {
        MaintenanceWindow w = new MaintenanceWindow();
        w.setCreatedById(actor.id());
        applyMaintenance(w, r);
        windows.save(w);
        int notified = notifyMaintenance(w, r.notifySubscribers(), "Planned maintenance: ");
        audit.record("MAINTENANCE_SCHEDULED", "MAINTENANCE_WINDOW", w.getId(), Map.of("title", w.getTitle(), "startsAt", w.getStartsAt(),
                "endsAt", w.getEndsAt(), "components", w.getComponents().size(), "subscribersNotified", notified));
        return maintenanceDto(w, clock.instant(), true);
    }

    @Transactional
    public MaintenanceDto updateMaintenance(Long id, MaintenanceRequest r) {
        MaintenanceWindow w = windows.findWithComponentsById(id).orElseThrow(() -> ApiException.notFound("Maintenance window", id));
        if (w.getStatus() == MaintenanceWindow.Status.CANCELLED || w.getStatus() == MaintenanceWindow.Status.COMPLETED) {
            throw ApiException.conflict("A " + w.getStatus().name().toLowerCase() + " maintenance window cannot be edited");
        }
        applyMaintenance(w, r);
        int notified = notifyMaintenance(w, r.notifySubscribers() != null && r.notifySubscribers(), "Maintenance updated: ");
        audit.record("MAINTENANCE_UPDATED", "MAINTENANCE_WINDOW", w.getId(), Map.of("title", w.getTitle(), "startsAt", w.getStartsAt(),
                "endsAt", w.getEndsAt(), "subscribersNotified", notified));
        return maintenanceDto(w, clock.instant(), true);
    }

    /** SCHEDULED → IN_PROGRESS | COMPLETED | CANCELLED; IN_PROGRESS → COMPLETED | CANCELLED; finished windows are final. */
    @Transactional
    public MaintenanceDto setMaintenanceStatus(Long id, MaintenanceWindow.Status target) {
        MaintenanceWindow w = windows.findWithComponentsById(id).orElseThrow(() -> ApiException.notFound("Maintenance window", id));
        MaintenanceWindow.Status from = w.getStatus();
        boolean allowed = switch (from) {
            case SCHEDULED -> target != MaintenanceWindow.Status.SCHEDULED;
            case IN_PROGRESS -> target == MaintenanceWindow.Status.COMPLETED || target == MaintenanceWindow.Status.CANCELLED;
            default -> false;
        };
        if (!allowed) {
            throw ApiException.conflict("Cannot move a maintenance window from " + from + " to " + target);
        }
        w.setStatus(target);
        Instant now = clock.instant();
        if (target == MaintenanceWindow.Status.COMPLETED && w.getEndsAt().isAfter(now)) {
            w.setEndsAt(now.isAfter(w.getStartsAt()) ? now : w.getStartsAt().plusSeconds(1));
        }
        audit.record("MAINTENANCE_STATUS_CHANGED", "MAINTENANCE_WINDOW", w.getId(), Map.of("from", from, "to", target));
        return maintenanceDto(w, now, true);
    }

    @Transactional
    public UpdateDto postUpdate(UpdateRequest r, Actor actor) {
        StatusUpdate u = new StatusUpdate();
        u.setPostedById(actor.id());
        u.setPostedAt(clock.instant());
        applyUpdate(u, r);
        updates.save(u);
        int notified = 0;
        if (u.isPublicVisible()) {
            notified = subscriptions.notifySubscribers(u.getStatus().name().charAt(0) + u.getStatus().name().substring(1).toLowerCase()
                            + ": " + u.getTitle(), u.getTitle(), List.of(u.getMessage()),
                    List.of(new EmailTemplates.Fact("Status", u.getStatus().name()), new EmailTemplates.Fact("Posted", WHEN.format(u.getPostedAt()))));
        }
        audit.record("STATUS_UPDATE_POSTED", "STATUS_UPDATE", u.getId(), Map.of("status", u.getStatus(), "public", u.isPublicVisible(),
                "incident", u.getIncident() == null ? "" : u.getIncident().getNumber(), "subscribersNotified", notified));
        return updateDto(u);
    }

    @Transactional
    public UpdateDto editUpdate(Long id, UpdateRequest r) {
        StatusUpdate u = updates.findById(id).orElseThrow(() -> ApiException.notFound("Status update", id));
        applyUpdate(u, r);
        audit.record("STATUS_UPDATE_EDITED", "STATUS_UPDATE", u.getId(), Map.of("status", u.getStatus(), "public", u.isPublicVisible()));
        return updateDto(u);
    }

    // ------------------------------------------------------------------ helpers

    private StatusPageSettings settings() {
        return settingsRepository.findById(StatusPageSettings.ID).orElseThrow(() -> new IllegalStateException("status_page_settings row missing (V6)"));
    }

    private Map<Long, ServiceDto> health() {
        return serviceHealth.list().stream().collect(Collectors.toMap(ServiceDto::id, Function.identity()));
    }

    private ComponentDto single(StatusComponent c) {
        Instant now = clock.instant();
        return componentDto(c, health().get(c.getService().getId()), windows.findEndingAfter(now.minus(Duration.ofDays(90))).stream()
                .filter(w -> w.getComponents().stream().anyMatch(x -> x.getId().equals(c.getId()))).toList(), now);
    }

    private ComponentDto componentDto(StatusComponent c, ServiceDto health, List<MaintenanceWindow> maintenance, Instant now) {
        List<AvailabilityPoint> points = serviceHealth.availabilityHistory(c.getService().getId(), now);
        ZoneId zone = zone();
        List<UptimeDay> history = points.stream().map(p -> new UptimeDay(p.date(), p.availability(), touches(maintenance, LocalDate.parse(p.date()), zone)))
                .toList();
        Double uptime = points.isEmpty() ? null : Math.round(points.stream().mapToDouble(AvailabilityPoint::availability).average().orElse(100) * 100) / 100.0;
        ServiceStatus status = health == null ? ServiceStatus.OPERATIONAL : health.status();
        return new ComponentDto(c.getId(), c.getService().getId(), c.getService().getCode(), c.getService().getName(), c.getDisplayName(),
                c.getGroupName(), c.getDescription(), c.getSortOrder(), c.isPublicVisible(), status, health == null ? null : health.statusReason(),
                uptime, history, c.isDemo());
    }

    private static boolean touches(List<MaintenanceWindow> maintenance, LocalDate day, ZoneId zone) {
        Instant start = day.atStartOfDay(zone).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
        return maintenance.stream().anyMatch(w -> w.getStatus() != MaintenanceWindow.Status.CANCELLED
                && w.getStartsAt().isBefore(end) && w.getEndsAt().isAfter(start));
    }

    /** Same day boundaries as the availability history (default business calendar time zone). */
    private ZoneId zone() {
        List<String> zones = jdbc.queryForList("select timezone from business_hours_calendars where is_default limit 1", String.class);
        try {
            return zones.isEmpty() ? ZoneOffset.UTC : ZoneId.of(zones.get(0));
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static ServiceStatus overall(List<ServiceStatus> statuses) {
        if (statuses.contains(ServiceStatus.OUTAGE)) {
            return ServiceStatus.OUTAGE;
        }
        if (statuses.contains(ServiceStatus.DEGRADED)) {
            return ServiceStatus.DEGRADED;
        }
        if (statuses.contains(ServiceStatus.MAINTENANCE)) {
            return ServiceStatus.MAINTENANCE;
        }
        return ServiceStatus.OPERATIONAL;
    }

    private void apply(StatusComponent c, ComponentRequest r) {
        c.setDisplayName(r.displayName().trim());
        c.setGroupName(blankToNull(r.groupName()));
        c.setDescription(blankToNull(r.description()));
        c.setSortOrder(r.sortOrder() == null ? 0 : r.sortOrder());
        c.setPublicVisible(r.publicVisible() == null || r.publicVisible());
    }

    private void applyMaintenance(MaintenanceWindow w, MaintenanceRequest r) {
        if (!r.endsAt().isAfter(r.startsAt())) {
            throw ApiException.invalidField("endsAt", "The maintenance window must end after it starts");
        }
        if (Duration.between(r.startsAt(), r.endsAt()).toDays() > 31) {
            throw ApiException.invalidField("endsAt", "A maintenance window may last at most 31 days");
        }
        Set<Long> ids = new HashSet<>(r.componentIds());
        List<StatusComponent> found = components.findAllById(ids);
        if (found.size() != ids.size()) {
            throw ApiException.invalidField("componentIds", "Unknown status component");
        }
        w.setTitle(r.title().trim());
        w.setDescription(blankToNull(r.description()));
        w.setStartsAt(r.startsAt());
        w.setEndsAt(r.endsAt());
        w.getComponents().clear();
        w.getComponents().addAll(found);
    }

    private int notifyMaintenance(MaintenanceWindow w, Boolean notify, String subjectPrefix) {
        if (Boolean.FALSE.equals(notify) || w.getComponents().stream().noneMatch(StatusComponent::isPublicVisible)) {
            return 0;
        }
        String affected = w.getComponents().stream().filter(StatusComponent::isPublicVisible).map(StatusComponent::getDisplayName).sorted()
                .collect(Collectors.joining(", "));
        List<String> paragraphs = new ArrayList<>();
        if (w.getDescription() != null) {
            paragraphs.add(w.getDescription());
        }
        return subscriptions.notifySubscribers(subjectPrefix + w.getTitle(), w.getTitle(), paragraphs,
                List.of(new EmailTemplates.Fact("Starts", WHEN.format(w.getStartsAt())), new EmailTemplates.Fact("Ends", WHEN.format(w.getEndsAt())),
                        new EmailTemplates.Fact("Affected", affected)));
    }

    private void applyUpdate(StatusUpdate u, UpdateRequest r) {
        Incident incident = null;
        if (r.incidentId() != null) {
            incident = incidents.findById(r.incidentId()).orElseThrow(() -> ApiException.invalidField("incidentId", "Incident does not exist"));
        }
        u.setIncident(incident);
        u.setTitle(r.title().trim());
        u.setStatus(r.status());
        u.setMessage(r.message().trim());
        u.setPublicVisible(Boolean.TRUE.equals(r.publicVisible()));
    }

    private static SettingsDto settingsDto(StatusPageSettings s) {
        return new SettingsDto(s.getTitle(), s.getDescription(), s.isPublicEnabled(), s.getUpdatedAt());
    }

    private static MaintenanceDto maintenanceDto(MaintenanceWindow w, Instant now, boolean insider) {
        List<ComponentRef> refs = w.getComponents().stream().filter(c -> insider || c.isPublicVisible())
                .map(c -> new ComponentRef(c.getId(), c.getDisplayName(), c.getService().getId(), c.isPublicVisible()))
                .sorted(java.util.Comparator.comparing(ComponentRef::displayName)).toList();
        return new MaintenanceDto(w.getId(), w.getTitle(), w.getDescription(), w.getStartsAt(), w.getEndsAt(), w.effectiveStatus(now), w.getStatus(),
                refs, w.isDemo());
    }

    private static UpdateDto updateDto(StatusUpdate u) {
        return new UpdateDto(u.getId(), u.getTitle(), u.getStatus(), u.getMessage(), u.isPublicVisible(),
                u.getIncident() == null ? null : u.getIncident().getId(), u.getIncident() == null ? null : u.getIncident().getNumber(),
                u.getPostedAt(), u.isDemo());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
