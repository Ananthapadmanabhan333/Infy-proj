package com.nexus360.major;

import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.events.DomainEvents.MajorIncidentEvent;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentService;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.major.MajorDtos.ActionItemDto;
import com.nexus360.major.MajorDtos.ActionRequest;
import com.nexus360.major.MajorDtos.AlertRef;
import com.nexus360.major.MajorDtos.DeclareRequest;
import com.nexus360.major.MajorDtos.MajorDetail;
import com.nexus360.major.MajorDtos.MajorSummary;
import com.nexus360.major.MajorDtos.PersonRef;
import com.nexus360.major.MajorDtos.PirRef;
import com.nexus360.major.MajorDtos.RolesRequest;
import com.nexus360.major.MajorDtos.ServiceRef;
import com.nexus360.major.MajorDtos.StakeholderUpdateDto;
import com.nexus360.major.MajorDtos.StakeholderUpdateRequest;
import com.nexus360.major.MajorDtos.StatusRequest;
import com.nexus360.major.MajorDtos.TimelineDto;
import com.nexus360.major.MajorDtos.TimelineRequest;
import com.nexus360.major.MajorDtos.UpdateRequest;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.Actor;
import com.nexus360.statuspage.StatusPageDtos;
import com.nexus360.statuspage.StatusPageService;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Major incident command (docs/RELEASE3_SPEC.md §5 BE-4). Declaring a major incident — through {@code POST /api/major-incidents},
 * the existing {@code POST /api/incidents/{id}/major}, the automatic declaration at the duplicate-report threshold, a workflow or an
 * approved AI action — publishes {@link MajorIncidentEvent} inside the incident transaction, and {@link #onMajorIncident} creates the
 * command record in the same transaction. Commanders (admins and managers, or the assigned incident commander) assign the roles and
 * move the status; responders (staff) write the war-room timeline, stakeholder updates and action items. A stakeholder update can be
 * published to the status page (status page editors, the commander or the communications lead). Closing requires the incident to be
 * resolved and opens the post-incident review.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MajorIncidentService {

    static final Set<Role> STATUS_PAGE_EDITORS = Set.of(Role.SYSTEM_ADMIN, Role.IT_ADMIN, Role.INCIDENT_MANAGER);
    private static final Set<Role> STAKEHOLDERS = Set.of(Role.SYSTEM_ADMIN, Role.IT_MANAGER, Role.INCIDENT_MANAGER);

    private final MajorIncidentRepository majors;
    private final MajorTimelineRepository timeline;
    private final StakeholderUpdateRepository updates;
    private final ActionItemService actions;
    private final ActionItemRepository actionItems;
    private final PirRepository reviews;
    private final PirService pirs;
    private final IncidentRepository incidents;
    private final IncidentService incidentService;
    private final IncidentMapper mapper;
    private final BusinessServiceRepository services;
    private final UserRepository users;
    private final StatusPageService statusPage;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ------------------------------------------------------------------ declaration

    /** Creates the command record when an incident becomes major (synchronously, in the incident's transaction). */
    @EventListener
    public void onMajorIncident(MajorIncidentEvent e) {
        Incident incident = incidents.findById(e.incidentId()).orElse(null);
        if (incident == null) {
            return;
        }
        MajorIncident existing = majors.findByIncidentId(incident.getId()).orElse(null);
        Actor actor = e.actorId() == null ? Actor.SYSTEM : users.findById(e.actorId()).map(Actor::of).orElse(Actor.SYSTEM);
        if (!e.major()) {
            if (existing != null) {
                entry(existing, MajorTimelineEntry.Type.SYSTEM, "The incident's major flag was cleared by " + actor.name(), actor, clock.instant());
            }
            return;
        }
        if (existing != null) {
            if (existing.getStatus() == MajorIncident.Status.CLOSED || existing.getStatus() == MajorIncident.Status.RESOLVED) {
                Instant now = clock.instant();
                existing.setStatus(MajorIncident.Status.ACTIVE);
                existing.setResolvedAt(null);
                existing.setClosedAt(null);
                entry(existing, MajorTimelineEntry.Type.STATUS, "Declared major again: status ACTIVE", actor, now);
            }
            return;
        }
        if (incident.getParent() != null) {
            return; // linked reports are handled on their parent
        }
        open(incident, actor, e.automatic());
    }

    private MajorIncident open(Incident incident, Actor actor, boolean automatic) {
        Instant now = clock.instant();
        MajorIncident m = new MajorIncident();
        m.setNumber("MI-" + majors.nextNumber());
        m.setIncident(incident);
        m.setStatus(MajorIncident.Status.ACTIVE);
        m.setDeclaredAt(now);
        m.setDeclaredBy(actor.isSystem() ? null : users.findById(actor.id()).orElse(null));
        if (!actor.isSystem() && actor.isAdminOrManager()) {
            m.setCommander(users.findById(actor.id()).orElse(null)); // whoever declares takes command until it is handed over
        }
        m.setSummary(truncate(incident.getTitle(), 2000));
        m.setDemo(incident.isDemo());
        if (incident.getService() != null) {
            m.getServices().add(incident.getService());
        }
        majors.save(m);
        entry(m, MajorTimelineEntry.Type.SYSTEM, (automatic ? "Declared a major incident automatically (duplicate-report threshold)"
                : "Declared a major incident by " + actor.name()) + (m.getCommander() == null ? "" : "; incident commander " + m.getCommander().getFullName()),
                actor, now);
        audit.record("MAJOR_INCIDENT_COMMAND_OPENED", "MAJOR_INCIDENT", m.getId(), Map.of("number", m.getNumber(), "incident", incident.getNumber(),
                "automatic", automatic));
        return m;
    }

    /** Declares the incident major (sets the existing flag) and fills the command record. */
    @Transactional
    public MajorDetail declare(DeclareRequest r, Actor actor) {
        Incident incident = incidents.findDetailedById(r.incidentId())
                .orElseThrow(() -> ApiException.invalidField("incidentId", "Incident " + r.incidentId() + " does not exist"));
        if (incident.getParent() != null) {
            throw ApiException.badRequest(incident.getNumber() + " is a linked report; declare its parent " + incident.getParent().getNumber());
        }
        if (incident.getStatus() == IncidentStatus.CLOSED) {
            throw ApiException.conflict(incident.getNumber() + " is closed");
        }
        MajorIncident existing = majors.findByIncidentId(incident.getId()).orElse(null);
        if (existing != null && incident.isMajor() && existing.getStatus() != MajorIncident.Status.CLOSED) {
            throw ApiException.conflict(incident.getNumber() + " is already major incident " + existing.getNumber());
        }
        incidentService.setMajorInternal(incident, true, actor);
        MajorIncident m = majors.findByIncidentId(incident.getId()).orElseGet(() -> open(incident, actor, false));
        if (m.getStatus() == MajorIncident.Status.CLOSED) {
            m.setStatus(MajorIncident.Status.ACTIVE);
            m.setResolvedAt(null);
            m.setClosedAt(null);
            entry(m, MajorTimelineEntry.Type.STATUS, "Declared major again by " + actor.name() + ": status ACTIVE", actor, clock.instant());
        }
        if (r.commanderId() != null || r.techLeadId() != null || r.commsLeadId() != null) {
            applyRoles(m, new RolesRequest(r.commanderId(), r.techLeadId(), r.commsLeadId()), actor);
        }
        if (r.summary() != null && !r.summary().isBlank()) {
            m.setSummary(r.summary().trim());
        }
        if (r.bridgeUrl() != null) {
            m.setBridgeUrl(r.bridgeUrl().isBlank() ? null : r.bridgeUrl().trim());
        }
        if (r.serviceIds() != null) {
            m.setServices(resolveServices(r.serviceIds()));
        }
        return detail(m, actor);
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<MajorSummary> list(List<MajorIncident.Status> statuses, String search, Integer page, Integer size) {
        List<MajorIncident.Status> s = statuses == null || statuses.isEmpty() ? List.of(MajorIncident.Status.values()) : statuses;
        String q = search == null || search.isBlank() ? "" : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        Page<MajorIncident> result = majors.search(s, q, PageResponse.pageable(page, size, Sort.by(Sort.Direction.DESC, "declaredAt")));
        return PageResponse.of(result, this::summary);
    }

    MajorSummary summary(MajorIncident m) {
        Incident i = m.getIncident();
        PirRef pir = reviews.findByIncidentId(i.getId()).map(p -> new PirRef(p.getId(), p.getNumber(), p.getStatus())).orElse(null);
        return new MajorSummary(m.getId(), m.getNumber(), m.getStatus(), i.getId(), i.getNumber(), i.getTitle(), i.getPriority(), i.getStatus(),
                PirService.person(m.getCommander()), PirService.person(m.getTechLead()), PirService.person(m.getCommsLead()), m.getDeclaredAt(),
                m.getResolvedAt(), m.getClosedAt(), duration(m), actionItems.countByMajorIncidentIdAndStatusIn(m.getId(), ActionItem.Status.OPEN_STATES),
                pir, m.isDemo());
    }

    @Transactional(readOnly = true)
    public MajorDetail get(Long id, Actor actor) {
        return detail(load(id), actor);
    }

    @Transactional(readOnly = true)
    public MajorDetail byIncident(Long incidentId, Actor actor) {
        MajorIncident m = majors.findByIncidentId(incidentId)
                .orElseThrow(() -> ApiException.notFound("Major incident record for incident", incidentId));
        return detail(load(m.getId()), actor);
    }

    MajorDetail detail(MajorIncident m, Actor actor) {
        Incident i = incidents.findDetailedById(m.getIncident().getId()).orElse(m.getIncident());
        List<TimelineDto> entries = timeline.findByMajorIncidentIdOrderByOccurredAtAscIdAsc(m.getId()).stream()
                .map(t -> new TimelineDto(t.getId(), t.getEntryType(), t.getBody(), t.getActorId(), t.getActorName(), t.getOccurredAt(), t.isDemo())).toList();
        List<StakeholderUpdateDto> ups = updates.findByMajorIncidentIdOrderByPostedAtDescIdDesc(m.getId()).stream().map(MajorIncidentService::updateDto)
                .toList();
        List<AlertRef> alerts = jdbc.query("select id, number, title, severity, status from alerts where incident_id = ? order by first_seen_at limit 50",
                (rs, n) -> new AlertRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), i.getId());
        PirRef pir = reviews.findByIncidentId(i.getId()).map(p -> new PirRef(p.getId(), p.getNumber(), p.getStatus())).orElse(null);
        List<ServiceRef> svc = m.getServices().stream().sorted(Comparator.comparing(BusinessService::getName))
                .map(s -> new ServiceRef(s.getId(), s.getCode(), s.getName())).toList();
        boolean command = canCommand(m, actor);
        return new MajorDetail(m.getId(), m.getNumber(), m.getStatus(), mapper.summary(i), PirService.person(m.getCommander()),
                PirService.person(m.getTechLead()), PirService.person(m.getCommsLead()), m.getSummary(), m.getImpactSummary(), m.getInvestigationNotes(),
                m.getResolutionSummary(), m.getBridgeUrl(), m.getDeclaredAt(), m.getDeclaredBy() == null ? "SA Automation" : m.getDeclaredBy().getFullName(),
                m.getResolvedAt(), m.getClosedAt(), duration(m), svc, entries, ups, actions.ofMajorIncident(m.getId()), alerts, pir,
                command ? List.copyOf(m.getStatus().next()) : List.of(), command, canPublish(m, actor), m.isDemo(), m.getCreatedAt(), m.getUpdatedAt());
    }

    // ------------------------------------------------------------------ command

    @Transactional
    public MajorDetail assignRoles(Long id, RolesRequest r, Actor actor) {
        MajorIncident m = load(id);
        requireCommand(m, actor);
        applyRoles(m, r, actor);
        return detail(m, actor);
    }

    private void applyRoles(MajorIncident m, RolesRequest r, Actor actor) {
        Instant now = clock.instant();
        List<String> changes = new ArrayList<>();
        assign(m, r.commanderId(), "commanderId", m.getCommander(), m::setCommander, "Incident commander", changes, actor, now);
        assign(m, r.techLeadId(), "techLeadId", m.getTechLead(), m::setTechLead, "Technical lead", changes, actor, now);
        assign(m, r.commsLeadId(), "commsLeadId", m.getCommsLead(), m::setCommsLead, "Communications lead", changes, actor, now);
        if (!changes.isEmpty()) {
            audit.record("MAJOR_INCIDENT_ROLES_ASSIGNED", "MAJOR_INCIDENT", m.getId(), Map.of("number", m.getNumber(), "changes", String.join("; ", changes)));
        }
    }

    private void assign(MajorIncident m, Long requested, String field, User current, Consumer<User> setter, String role, List<String> changes, Actor actor,
                        Instant now) {
        if (requested == null) {
            return;
        }
        User next = requested == 0 ? null : users.findById(requested).filter(u -> u.isActive() && u.getRole().isStaff())
                .orElseThrow(() -> ApiException.invalidField(field, "User " + requested + " is not an active staff member"));
        if (Objects.equals(current == null ? null : current.getId(), next == null ? null : next.getId())) {
            return;
        }
        setter.accept(next);
        String text = role + ": " + (next == null ? "unassigned" : next.getFullName());
        changes.add(text);
        entry(m, MajorTimelineEntry.Type.ROLE, text, actor, now);
        if (next != null) {
            notifications.notifyUser(next.getId(), "MAJOR_INCIDENT_ROLE", role + " for " + m.getNumber(), "You are the " + role.toLowerCase(Locale.ROOT)
                    + " of major incident " + m.getNumber() + " (" + m.getIncident().getNumber() + ": " + truncate(m.getIncident().getTitle(), 150) + ").",
                    link(m), Severity.CRITICAL);
        }
    }

    @Transactional
    public MajorDetail update(Long id, UpdateRequest r, Actor actor) {
        MajorIncident m = load(id);
        requireResponder(actor);
        requireOpen(m);
        if (r.summary() != null) {
            m.setSummary(blankToNull(r.summary()));
        }
        if (r.impactSummary() != null) {
            m.setImpactSummary(blankToNull(r.impactSummary()));
        }
        if (r.investigationNotes() != null) {
            m.setInvestigationNotes(blankToNull(r.investigationNotes()));
        }
        if (r.resolutionSummary() != null) {
            m.setResolutionSummary(blankToNull(r.resolutionSummary()));
        }
        if (r.bridgeUrl() != null) {
            m.setBridgeUrl(blankToNull(r.bridgeUrl()));
        }
        if (r.serviceIds() != null) {
            Set<BusinessService> next = resolveServices(r.serviceIds());
            if (!next.equals(m.getServices())) {
                m.setServices(next);
                entry(m, MajorTimelineEntry.Type.SYSTEM, "Impacted services: " + (next.isEmpty() ? "none" : String.join(", ",
                        next.stream().map(BusinessService::getName).sorted().toList())), actor, clock.instant());
            }
        }
        audit.record("MAJOR_INCIDENT_UPDATED", "MAJOR_INCIDENT", m.getId(), Map.of("number", m.getNumber()));
        return detail(m, actor);
    }

    @Transactional
    public MajorDetail transition(Long id, StatusRequest r, Actor actor) {
        MajorIncident m = load(id);
        requireCommand(m, actor);
        MajorIncident.Status from = m.getStatus();
        if (!from.next().contains(r.status())) {
            throw ApiException.conflict("A major incident cannot move from " + from + " to " + r.status());
        }
        Instant now = clock.instant();
        String note = blankToNull(r.note());
        switch (r.status()) {
            case RESOLVED -> {
                if ((m.getResolutionSummary() == null || m.getResolutionSummary().isBlank()) && note == null) {
                    throw ApiException.invalidField("note", "Describe how service was restored (note or resolutionSummary)");
                }
                if (m.getResolutionSummary() == null || m.getResolutionSummary().isBlank()) {
                    m.setResolutionSummary(note);
                }
                m.setResolvedAt(now);
            }
            case CLOSED -> {
                IncidentStatus s = m.getIncident().getStatus();
                if (s != IncidentStatus.RESOLVED && s != IncidentStatus.CLOSED) {
                    throw ApiException.conflict("Resolve " + m.getIncident().getNumber() + " before closing " + m.getNumber());
                }
                m.setClosedAt(now);
            }
            case ACTIVE -> {
                m.setResolvedAt(null);
                m.setClosedAt(null);
            }
            default -> { }
        }
        m.setStatus(r.status());
        entry(m, MajorTimelineEntry.Type.STATUS, "Status " + from + " → " + r.status() + (note == null ? "" : ": " + note), actor, now);
        audit.record("MAJOR_INCIDENT_STATUS_CHANGED", "MAJOR_INCIDENT", m.getId(), Map.of("number", m.getNumber(), "from", from, "to", r.status()));
        if (r.status() == MajorIncident.Status.CLOSED && !reviews.existsByIncidentId(m.getIncident().getId())) {
            Incident i = incidents.findDetailedById(m.getIncident().getId()).orElseThrow();
            PostIncidentReview p = pirs.createInternal(i, m, m.getCommander(), null, actor);
            entry(m, MajorTimelineEntry.Type.SYSTEM, "Post-incident review " + p.getNumber() + " opened", actor, now);
        }
        return detail(m, actor);
    }

    @Transactional
    public TimelineDto addTimeline(Long id, TimelineRequest r, Actor actor) {
        MajorIncident m = load(id);
        requireResponder(actor);
        if (!Set.of(MajorTimelineEntry.Type.NOTE, MajorTimelineEntry.Type.DECISION, MajorTimelineEntry.Type.ACTION,
                MajorTimelineEntry.Type.INVESTIGATION).contains(r.entryType())) {
            throw ApiException.invalidField("entryType", "Responders write NOTE, DECISION, ACTION or INVESTIGATION entries");
        }
        Instant now = clock.instant();
        Instant at = r.occurredAt() == null ? now : r.occurredAt();
        if (at.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw ApiException.invalidField("occurredAt", "occurredAt cannot be in the future");
        }
        MajorTimelineEntry e = entry(m, r.entryType(), r.body().trim(), actor, at);
        audit.record("MAJOR_INCIDENT_TIMELINE_ADDED", "MAJOR_INCIDENT", m.getId(), Map.of("number", m.getNumber(), "type", r.entryType()));
        return new TimelineDto(e.getId(), e.getEntryType(), e.getBody(), e.getActorId(), e.getActorName(), e.getOccurredAt(), e.isDemo());
    }

    @Transactional
    public StakeholderUpdateDto postUpdate(Long id, StakeholderUpdateRequest r, Actor actor) {
        MajorIncident m = load(id);
        requireResponder(actor);
        boolean publish = Boolean.TRUE.equals(r.publishToStatusPage());
        if (publish && !canPublish(m, actor)) {
            throw ApiException.forbidden("Only status page editors, the incident commander or the communications lead publish to the status page");
        }
        Instant now = clock.instant();
        StakeholderUpdate u = new StakeholderUpdate();
        u.setMajorIncidentId(m.getId());
        u.setStatus(r.status());
        u.setTitle(r.title() == null || r.title().isBlank() ? defaultTitle(m) : r.title().trim());
        u.setMessage(r.message().trim());
        u.setPostedById(actor.id());
        u.setPostedByName(actor.name());
        u.setPostedAt(now);
        u.setDemo(m.isDemo());
        updates.save(u);
        if (publish) {
            publish(m, u, actor);
        }
        entry(m, MajorTimelineEntry.Type.UPDATE, "Stakeholder update (" + u.getStatus() + "): " + u.getTitle() + (publish ? " — published on the status page" : ""),
                actor, now);
        Set<Long> to = new LinkedHashSet<>();
        users.findByActiveTrueAndRoleIn(STAKEHOLDERS).forEach(x -> to.add(x.getId()));
        roleHolders(m).forEach(to::add);
        if (m.getIncident().getAssignee() != null) {
            to.add(m.getIncident().getAssignee().getId());
        }
        notifications.notifyUsers(to, "STAKEHOLDER_UPDATE", m.getNumber() + " " + u.getStatus().name().toLowerCase(Locale.ROOT) + ": " + u.getTitle(),
                truncate(u.getMessage(), 500), link(m), Severity.WARNING);
        audit.record("MAJOR_INCIDENT_UPDATE_POSTED", "MAJOR_INCIDENT", m.getId(), Map.of("number", m.getNumber(), "status", u.getStatus(),
                "published", publish));
        return updateDto(u);
    }

    @Transactional
    public StakeholderUpdateDto publishUpdate(Long id, Long updateId, Actor actor) {
        MajorIncident m = load(id);
        if (!canPublish(m, actor)) {
            throw ApiException.forbidden("Only status page editors, the incident commander or the communications lead publish to the status page");
        }
        StakeholderUpdate u = updates.findById(updateId).filter(x -> x.getMajorIncidentId().equals(m.getId()))
                .orElseThrow(() -> ApiException.notFound("Stakeholder update", updateId));
        if (u.getStatusUpdateId() != null) {
            throw ApiException.conflict("This update is already on the status page");
        }
        publish(m, u, actor);
        entry(m, MajorTimelineEntry.Type.UPDATE, "Stakeholder update published on the status page: " + u.getTitle(), actor, clock.instant());
        return updateDto(u);
    }

    private void publish(MajorIncident m, StakeholderUpdate u, Actor actor) {
        StatusPageDtos.UpdateDto posted = statusPage.postUpdate(new StatusPageDtos.UpdateRequest(u.getTitle(), u.getStatus(), u.getMessage(),
                m.getIncident().getId(), true), actor);
        u.setStatusUpdateId(posted.id());
        u.setPublishedAt(clock.instant());
        if (m.isDemo()) {
            jdbc.update("update status_updates set is_demo = true where id = ?", posted.id());
        }
        audit.record("MAJOR_INCIDENT_UPDATE_PUBLISHED", "MAJOR_INCIDENT", m.getId(), Map.of("number", m.getNumber(), "statusUpdate", posted.id()));
    }

    // ------------------------------------------------------------------ actions

    @Transactional
    public ActionItemDto addAction(Long id, ActionRequest r, Actor actor) {
        MajorIncident m = load(id);
        requireResponder(actor);
        return actions.dto(actions.add(parent(m), r, actor));
    }

    @Transactional
    public ActionItemDto updateAction(ActionItem item, ActionRequest r, Actor actor) {
        MajorIncident m = load(item.getMajorIncidentId());
        requireResponder(actor);
        return actions.dto(actions.update(item, parent(m), r, actor));
    }

    ActionItemService.Parent parent(MajorIncident m) {
        return new ActionItemService.Parent(m.getId(), null, m.getNumber(), link(m), m.isDemo());
    }

    // ------------------------------------------------------------------ helpers

    MajorIncident load(Long id) {
        return majors.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Major incident", id));
    }

    MajorTimelineEntry entry(MajorIncident m, MajorTimelineEntry.Type type, String body, Actor actor, Instant at) {
        MajorTimelineEntry e = new MajorTimelineEntry();
        e.setMajorIncidentId(m.getId());
        e.setEntryType(type);
        e.setBody(truncate(body, 4000));
        e.setActorId(actor.id());
        e.setActorName(actor.name());
        e.setOccurredAt(at);
        e.setDemo(m.isDemo());
        return timeline.save(e);
    }

    static boolean canCommand(MajorIncident m, Actor actor) {
        return actor.isSystem() || actor.isAdminOrManager()
                || (m.getCommander() != null && Objects.equals(m.getCommander().getId(), actor.id()));
    }

    static boolean canPublish(MajorIncident m, Actor actor) {
        return actor.isSystem() || STATUS_PAGE_EDITORS.contains(actor.role())
                || (actor.isStaff() && ((m.getCommander() != null && Objects.equals(m.getCommander().getId(), actor.id()))
                || (m.getCommsLead() != null && Objects.equals(m.getCommsLead().getId(), actor.id()))));
    }

    private static void requireCommand(MajorIncident m, Actor actor) {
        if (!canCommand(m, actor)) {
            throw ApiException.forbidden("Only managers, administrators and the incident commander run the command of " + m.getNumber());
        }
    }

    private static void requireResponder(Actor actor) {
        if (!actor.isSystem() && !actor.isStaff()) {
            throw ApiException.forbidden("Only staff respond to major incidents");
        }
    }

    private static void requireOpen(MajorIncident m) {
        if (m.getStatus() == MajorIncident.Status.CLOSED) {
            throw ApiException.conflict(m.getNumber() + " is closed");
        }
    }

    private Set<BusinessService> resolveServices(List<Long> ids) {
        Set<BusinessService> result = new LinkedHashSet<>();
        for (Long sid : ids) {
            result.add(services.findById(sid).orElseThrow(() -> ApiException.invalidField("serviceIds", "Service " + sid + " does not exist")));
        }
        return result;
    }

    private static List<Long> roleHolders(MajorIncident m) {
        List<Long> ids = new ArrayList<>();
        for (User u : new User[]{m.getCommander(), m.getTechLead(), m.getCommsLead()}) {
            if (u != null) {
                ids.add(u.getId());
            }
        }
        return ids;
    }

    private static String defaultTitle(MajorIncident m) {
        String service = m.getServices().stream().map(BusinessService::getName).sorted().findFirst().orElse(null);
        return service == null ? "Service disruption" : "Service disruption: " + service;
    }

    private long duration(MajorIncident m) {
        Instant end = m.getResolvedAt() != null ? m.getResolvedAt() : m.getClosedAt() != null ? m.getClosedAt() : clock.instant();
        return Math.max(0, Duration.between(m.getDeclaredAt(), end).toMinutes());
    }

    static StakeholderUpdateDto updateDto(StakeholderUpdate u) {
        return new StakeholderUpdateDto(u.getId(), u.getStatus(), u.getTitle(), u.getMessage(), u.getStatusUpdateId() != null, u.getStatusUpdateId(),
                u.getPublishedAt(), u.getPostedByName(), u.getPostedAt(), u.isDemo());
    }

    static String link(MajorIncident m) {
        return "/major-incidents/" + m.getId();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String truncate(String v, int max) {
        return v == null ? null : v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }
}
