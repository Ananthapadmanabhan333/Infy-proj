package com.nexus360.incident;

import com.nexus360.admin.SettingsService;
import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.events.DomainEvents.IncidentEvent;
import com.nexus360.events.DomainEvents.IncidentEventType;
import com.nexus360.incident.IncidentDtos.AssignRequest;
import com.nexus360.incident.IncidentDtos.AssignmentOutcome;
import com.nexus360.incident.IncidentDtos.Classification;
import com.nexus360.incident.IncidentDtos.CommentDto;
import com.nexus360.incident.IncidentDtos.CreateIncidentRequest;
import com.nexus360.incident.IncidentDtos.CreateIncidentResult;
import com.nexus360.incident.IncidentDtos.DuplicateOutcome;
import com.nexus360.incident.IncidentDtos.IncidentDetail;
import com.nexus360.incident.IncidentDtos.TransitionRequest;
import com.nexus360.incident.IncidentDtos.UpdateIncidentRequest;
import com.nexus360.knowledge.KnowledgeArticle;
import com.nexus360.knowledge.KnowledgeArticleRepository;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.Actor;
import com.nexus360.sla.SlaEngine;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRouting;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Incident commands. Creation runs the full intake pipeline: rule-based classification, impact × urgency
 * priority, duplicate detection (grouping reports under an automatically created parent incident),
 * routing to the owning department, and SLA start.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentService {

    private final IncidentRepository incidents;
    private final IncidentCommentRepository comments;
    private final IncidentLinkRepository links;
    private final BusinessServiceRepository services;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final KnowledgeArticleRepository articles;
    private final IncidentClassifier classifier;
    private final IncidentCategoryService categoryConfig;
    private final IncidentEscalationRepository escalationRecords;
    private final DuplicateDetector duplicates;
    private final AssignmentService assignment;
    private final TeamRouting teamRouting;
    private final SlaEngine sla;
    private final IncidentTimeline timeline;
    private final IncidentQueryService queries;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    /** Operations leadership: notified of major incidents and level-3 escalations. */
    static final Set<Role> LEADERSHIP = EnumSet.of(Role.SYSTEM_ADMIN, Role.IT_MANAGER, Role.INCIDENT_MANAGER);

    // ------------------------------------------------------------------ create

    @Transactional
    public CreateIncidentResult create(CreateIncidentRequest request, Actor actor) {
        Instant now = clock.instant();
        User creator = actor.isSystem() ? null : users.findById(actor.id()).orElseThrow(() -> ApiException.badRequest("Unknown user"));
        User requester = creator;
        if (request.requesterId() != null && !Objects.equals(request.requesterId(), actor.id())) {
            IncidentAccess.requireStaff(actor);
            requester = users.findById(request.requesterId()).orElseThrow(() -> ApiException.badRequest("Requester not found"));
        }
        List<BusinessService> catalogue = services.findAllByOrderByTierAscNameAsc();
        BusinessService service = null;
        if (request.serviceId() != null) {
            service = catalogue.stream().filter(s -> s.getId().equals(request.serviceId())).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("Service " + request.serviceId() + " does not exist"));
        }
        categoryConfig.requireActive(request.category());
        String title = request.title().trim();
        String description = request.description() == null ? null : request.description().trim();
        IncidentClassifier.Result rules = classifier.classify(title, description, catalogue, categoryConfig.rules());

        IncidentSource source = request.source() == null || !(actor.isStaff() || actor.isSystem()) ? IncidentSource.PORTAL : request.source();
        Category category;
        ClassificationSource classificationSource;
        Double confidence = null;
        if (request.category() != null) {
            category = request.category();
            classificationSource = ClassificationSource.MANUAL;
        } else {
            category = rules.category();
            classificationSource = source == IncidentSource.MONITORING ? ClassificationSource.MONITORING : ClassificationSource.RULES;
            confidence = rules.confidence();
        }
        if (service == null && rules.service() != null) {
            service = rules.service();
        }
        int affected = request.affectedUsers() == null ? 1 : request.affectedUsers();
        Level impact = request.impact() != null ? request.impact()
                : PriorityCalculator.deriveImpact(affected, service == null ? null : service.getTier(), 1);
        Level urgency = request.urgency() != null ? request.urgency() : PriorityCalculator.deriveUrgency(title + " " + description);
        Priority priority = PriorityCalculator.calculate(impact, urgency);

        Incident incident = new Incident();
        incident.setNumber("INC-" + incidents.nextNumber());
        incident.setTitle(title);
        incident.setDescription(description);
        incident.setCategory(category);
        incident.setClassificationSource(classificationSource);
        incident.setClassificationConfidence(confidence == null ? null : BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP));
        incident.setService(service);
        incident.setImpact(impact);
        incident.setUrgency(urgency);
        incident.setPriority(priority);
        incident.setStatus(IncidentStatus.NEW);
        incident.setAffectedUsers(affected);
        incident.setSource(source);
        incident.setRequester(requester);
        incident.setCreatedBy(creator);
        incident.setDemo(source == IncidentSource.SCENARIO || (creator != null && creator.isDemo()) || (requester != null && requester.isDemo()));
        incidents.save(incident);
        timeline.record(incident, actor, "CREATED", "Reported via " + source.name().toLowerCase() + ". Classified as " + category
                + " (" + classificationSource.name().toLowerCase() + (confidence == null ? "" : ", " + Math.round(confidence * 100) + "% confidence")
                + "). Priority " + priority.label() + " " + priority + " from impact " + impact + " × urgency " + urgency + ".");

        DuplicateOutcome duplicate = null;
        Incident parent = null;
        if (settings.getBoolean(SettingsService.DUPLICATE_AUTO_LINK, true)) {
            List<DuplicateDetector.Match> matches = duplicates.candidates(title, description, service, category, incident.getId(),
                    duplicates.autoLinkThreshold(), 1);
            if (!matches.isEmpty()) {
                DuplicateDetector.Match best = matches.get(0);
                Incident target = best.incident().getParent() != null ? best.incident().getParent() : best.incident();
                boolean parentCreated = false;
                if (isParent(target)) {
                    parent = target;
                } else {
                    parent = createParent(target, now);
                    attachChild(target, parent, null, Actor.SYSTEM, now);
                    parentCreated = true;
                }
                attachChild(incident, parent, best.score(), Actor.SYSTEM, now);
                refreshParent(parent, now);
                duplicate = new DuplicateOutcome(true, best.incident().getNumber(), parent.getId(), parent.getNumber(), parentCreated, best.score());
                audit.record("INCIDENT_DUPLICATE_LINKED", "INCIDENT", incident.getNumber(),
                        Map.of("parent", parent.getNumber(), "score", best.score(), "parentCreated", parentCreated));
            }
        }

        AssignmentOutcome assignmentOutcome = null;
        if (parent == null) {
            sla.start(incident, now);
            if (settings.getBoolean(SettingsService.AUTO_ASSIGN, true)) {
                assignmentOutcome = autoAssignInternal(incident, now);
            }
        } else {
            assignmentOutcome = new AssignmentOutcome(parent.getDepartment() == null ? null : parent.getDepartment().getName(),
                    parent.getAssignee() == null ? null : parent.getAssignee().getFullName(),
                    parent.getTeam() == null ? null : parent.getTeam().getName());
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("priority", priority);
        details.put("category", category);
        details.put("service", service == null ? null : service.getCode());
        details.put("parent", parent == null ? null : parent.getNumber());
        audit.record("INCIDENT_CREATED", "INCIDENT", incident.getNumber(), details);
        publisher.publishEvent(new IncidentEvent(IncidentEventType.CREATED, incident.getId(), actor.id(), actor.depth(), Map.of()));

        Classification classification = new Classification(category, service == null ? null : service.getId(),
                service == null ? null : service.getName(), classificationSource, confidence);
        return new CreateIncidentResult(queries.detailOf(incident, actor), classification, duplicate, assignmentOutcome);
    }

    private boolean isParent(Incident incident) {
        return incident.isMajor() || incidents.countByParentId(incident.getId()) > 0;
    }

    private Incident createParent(Incident original, Instant now) {
        Incident parent = new Incident();
        parent.setNumber("INC-" + incidents.nextNumber());
        String title = "Multiple reports: " + original.getTitle();
        parent.setTitle(title.length() > 200 ? title.substring(0, 199) + "…" : title);
        parent.setDescription("Parent incident created automatically by SA duplicate detection after more than one user "
                + "reported the same problem. Work this incident; linked reports are resolved with it.\n\nFirst report "
                + original.getNumber() + ": " + (original.getDescription() == null ? original.getTitle() : original.getDescription()));
        parent.setCategory(original.getCategory());
        parent.setClassificationSource(original.getClassificationSource());
        parent.setClassificationConfidence(original.getClassificationConfidence());
        parent.setService(original.getService());
        parent.setImpact(original.getImpact());
        parent.setUrgency(original.getUrgency());
        parent.setPriority(original.getPriority());
        parent.setStatus(original.getAssignee() != null ? IncidentStatus.ASSIGNED : IncidentStatus.NEW);
        parent.setDepartment(original.getDepartment());
        parent.setTeam(original.getTeam());
        parent.setAssignee(original.getAssignee());
        parent.setAffectedUsers(original.getAffectedUsers());
        parent.setSource(original.getSource());
        parent.setDemo(original.isDemo());
        incidents.save(parent);
        timeline.record(parent, Actor.SYSTEM, "CREATED", "Parent incident created by duplicate detection from " + original.getNumber());
        // The outage started with the first report, so the parent's SLA clock does too.
        sla.start(parent, original.getCreatedAt() == null ? now : original.getCreatedAt());
        if (parent.getAssignee() == null && settings.getBoolean(SettingsService.AUTO_ASSIGN, true)) {
            autoAssignInternal(parent, now);
        }
        return parent;
    }

    private void attachChild(Incident child, Incident parent, Double score, Actor actor, Instant now) {
        child.setParent(parent);
        child.setDuplicateScore(score == null ? null : BigDecimal.valueOf(score).setScale(3, RoundingMode.HALF_UP));
        child.setDepartment(parent.getDepartment());
        child.setTeam(parent.getTeam());
        sla.cancel(child, now, "Tracked under parent incident " + parent.getNumber());
        timeline.record(child, actor, "LINKED", "Linked to parent " + parent.getNumber()
                + (score == null ? "" : " (similarity " + Math.round(score * 100) + "%)"));
        timeline.record(parent, actor, "LINKED", child.getNumber() + " linked as a child report");
        IncidentComment note = new IncidentComment();
        note.setIncident(child);
        note.setBody("This problem is already being worked on under " + parent.getNumber()
                + ". Your report has been linked to it and you will be notified when it is resolved.");
        note.setSystemGenerated(true);
        comments.save(note);
        if (child.getRequester() != null) {
            notifications.notifyUser(child.getRequester().getId(), "INCIDENT_LINKED", child.getNumber() + " linked to " + parent.getNumber(),
                    "Your report is being handled as part of " + parent.getNumber() + ".", "/incidents/" + child.getId(), Severity.INFO);
        }
    }

    /** Re-derives the parent's impact and priority from its reports and declares a major incident at the threshold. */
    private void refreshParent(Incident parent, Instant now) {
        List<Incident> children = incidents.findByParentIdOrderByCreatedAtAsc(parent.getId());
        int reports = children.size();
        int affected = Math.max(parent.getAffectedUsers(), children.stream().mapToInt(Incident::getAffectedUsers).sum());
        Integer tier = parent.getService() == null ? null : parent.getService().getTier();
        Level impact = PriorityCalculator.higher(parent.getImpact(), PriorityCalculator.deriveImpact(affected, tier, reports));
        Level urgency = children.stream().map(Incident::getUrgency).reduce(parent.getUrgency(), PriorityCalculator::higher);
        Priority priority = PriorityCalculator.calculate(impact, urgency);
        timeline.change(parent, Actor.SYSTEM, "affectedUsers", parent.getAffectedUsers(), affected);
        timeline.change(parent, Actor.SYSTEM, "impact", parent.getImpact(), impact);
        timeline.change(parent, Actor.SYSTEM, "urgency", parent.getUrgency(), urgency);
        Priority previous = parent.getPriority();
        parent.setAffectedUsers(affected);
        parent.setImpact(impact);
        parent.setUrgency(urgency);
        if (priority != previous) {
            parent.setPriority(priority);
            timeline.change(parent, Actor.SYSTEM, "priority", previous, priority);
            timeline.record(parent, Actor.SYSTEM, "FIELD_CHANGED", "Priority recalculated to " + priority.label() + " " + priority
                    + " from " + reports + " linked reports (impact " + impact + " × urgency " + urgency + ")");
            sla.recalculate(parent, now);
            publisher.publishEvent(new IncidentEvent(IncidentEventType.UPDATED, parent.getId(), null, 0,
                    Map.of("priority", priority.name(), "reports", String.valueOf(reports))));
        }
        int threshold = settings.getInt(SettingsService.MAJOR_REPORT_THRESHOLD, 3);
        if (!parent.isMajor() && reports >= threshold) {
            parent.setMajor(true);
            timeline.record(parent, Actor.SYSTEM, "ESCALATED", "Declared a major incident: " + reports + " users reported the same problem");
            notifications.notifyRoles(majorIncidentRecipients(), "MAJOR_INCIDENT", "Major incident: " + parent.getNumber(),
                    parent.getTitle() + " — " + reports + " linked reports.", "/incidents/" + parent.getId(), Severity.CRITICAL);
            audit.record("MAJOR_INCIDENT_DECLARED", "INCIDENT", parent.getNumber(), Map.of("reports", reports, "automatic", true));
            publisher.publishEvent(new com.nexus360.events.DomainEvents.MajorIncidentEvent(parent.getId(), true, null, Actor.SYSTEM.name(), true));
        }
    }

    // ------------------------------------------------------------------ update & transitions

    @Transactional
    public IncidentDetail update(Long id, UpdateIncidentRequest request, Actor actor) {
        IncidentAccess.requireStaff(actor);
        Incident incident = queries.load(id, actor);
        requireNotTerminal(incident);
        if (incident.getCategory() != request.category()) {
            categoryConfig.requireActive(request.category());
        }
        Instant now = clock.instant();
        BusinessService service = request.serviceId() == null ? null
                : services.findById(request.serviceId()).orElseThrow(() -> ApiException.badRequest("Service does not exist"));
        Map<String, String> changes = new HashMap<>();
        track(changes, incident, actor, "title", incident.getTitle(), request.title().trim());
        track(changes, incident, actor, "description", incident.getDescription(), request.description());
        track(changes, incident, actor, "category", incident.getCategory(), request.category());
        track(changes, incident, actor, "impact", incident.getImpact(), request.impact());
        track(changes, incident, actor, "urgency", incident.getUrgency(), request.urgency());
        track(changes, incident, actor, "service", incident.getService() == null ? null : incident.getService().getName(),
                service == null ? null : service.getName());
        track(changes, incident, actor, "affectedUsers", incident.getAffectedUsers(), request.affectedUsers());
        if (incident.getCategory() != request.category()) {
            incident.setClassificationSource(ClassificationSource.MANUAL);
            incident.setClassificationConfidence(null);
        }
        incident.setTitle(request.title().trim());
        incident.setDescription(request.description());
        incident.setCategory(request.category());
        incident.setImpact(request.impact());
        incident.setUrgency(request.urgency());
        incident.setService(service);
        incident.setAffectedUsers(request.affectedUsers());
        Priority previous = incident.getPriority();
        Priority priority = PriorityCalculator.calculate(request.impact(), request.urgency());
        if (priority != previous) {
            incident.setPriority(priority);
            timeline.change(incident, actor, "priority", previous, priority);
            changes.put("priority", priority.name());
            sla.recalculate(incident, now);
        }
        if (!changes.isEmpty()) {
            audit.record("INCIDENT_UPDATED", "INCIDENT", incident.getNumber(), changes);
            publisher.publishEvent(new IncidentEvent(IncidentEventType.UPDATED, incident.getId(), actor.id(), actor.depth(), changes));
        }
        return queries.detailOf(incident, actor);
    }

    @Transactional
    public IncidentDetail transition(Long id, TransitionRequest request, Actor actor) {
        Incident incident = queries.load(id, actor);
        applyTransition(incident, request, actor);
        return queries.detailOf(incident, actor);
    }

    /**
     * Performs one status transition (used by the API, workflows and approved AI actions). CLOSED from NEW or ASSIGNED is a
     * cancellation: it needs a reason and is recorded with resolution code CANCELLED.
     */
    @Transactional
    public void applyTransition(Incident incident, TransitionRequest request, Actor actor) {
        IncidentStatus from = incident.getStatus();
        IncidentStatus to = request.status();
        if (!IncidentStateMachine.allowedFor(incident, actor).contains(to)) {
            throw ApiException.conflict("Cannot move " + incident.getNumber() + " from " + from + " to " + to);
        }
        Instant now = clock.instant();
        String reason = request.comment() == null ? null : request.comment().trim();
        boolean cancellation = IncidentStateMachine.isCancellation(from, to);
        switch (to) {
            case RESOLVED -> {
                if (request.resolutionCode() == null) {
                    throw ApiException.badRequest("A resolution code is required");
                }
                if (request.resolutionCode() == ResolutionCode.CANCELLED) {
                    throw ApiException.badRequest("Use status CLOSED with a reason to cancel an incident");
                }
                if (request.resolutionNotes() == null || request.resolutionNotes().trim().length() < 10) {
                    throw ApiException.badRequest("Resolution notes of at least 10 characters are required");
                }
                KnowledgeArticle article = null;
                if (request.resolutionArticleId() != null) {
                    article = articles.findById(request.resolutionArticleId())
                            .filter(a -> a.getStatus() == KnowledgeArticle.Status.PUBLISHED)
                            .orElseThrow(() -> ApiException.badRequest("Resolution article must be a published knowledge article"));
                }
                incident.setResolutionCode(request.resolutionCode());
                incident.setResolutionNotes(request.resolutionNotes().trim());
                if (request.rootCause() != null && !request.rootCause().isBlank()) {
                    incident.setRootCause(request.rootCause().trim());
                }
                incident.setResolutionArticle(article);
                incident.setResolvedAt(now);
                incident.setResolvedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
                markResponded(incident, actor, now);
                sla.complete(incident, now);
                resolveChildren(incident, actor, now);
                notifyRequester(incident, "INCIDENT_RESOLVED", incident.getNumber() + " resolved",
                        "Your incident has been resolved: " + incident.getResolutionNotes(), Severity.SUCCESS);
            }
            case PENDING -> {
                requireReason(reason);
                sla.pause(incident, now, reason);
            }
            case REOPENED -> {
                // Status first: the SLA queries below auto-flush, and a RESOLVED row must keep its resolved_at (ck_incident_resolved).
                incident.setStatus(IncidentStatus.REOPENED);
                incident.setResolvedAt(null);
                incident.setResolvedBy(null);
                incident.setResolutionCode(null);
                incident.setResolutionNotes(null);
                incident.setClosedAt(null);
                sla.reopen(incident, now, "Reopened" + (reason == null || reason.isEmpty() ? "" : ": " + reason));
                if (incident.getAssignee() != null) {
                    notifications.notifyUser(incident.getAssignee().getId(), "INCIDENT_REOPENED", incident.getNumber() + " reopened",
                            incident.getTitle() + " was reopened" + (reason == null || reason.isEmpty() ? "." : ": " + reason),
                            "/incidents/" + incident.getId(), Severity.WARNING);
                }
            }
            case IN_PROGRESS -> {
                if (from == IncidentStatus.PENDING) {
                    sla.resume(incident, now);
                }
                markResponded(incident, actor, now);
            }
            case CLOSED -> {
                if (cancellation) {
                    requireReason(reason);
                    incident.setResolutionCode(ResolutionCode.CANCELLED);
                    incident.setResolutionNotes("Cancelled: " + reason);
                    incident.setResolvedAt(now);
                    incident.setResolvedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
                    sla.cancel(incident, now, "Cancelled: " + reason);
                }
                incident.setClosedAt(now);
                incidents.findByParentIdOrderByCreatedAtAsc(incident.getId()).stream()
                        .filter(c -> c.getStatus() == IncidentStatus.RESOLVED)
                        .forEach(c -> {
                            c.setStatus(IncidentStatus.CLOSED);
                            c.setClosedAt(now);
                            timeline.change(c, actor, "status", IncidentStatus.RESOLVED, IncidentStatus.CLOSED);
                        });
            }
            default -> { }
        }
        incident.setStatus(to);
        timeline.change(incident, actor, "status", from, to);
        if (reason != null && !reason.isEmpty()) {
            addCommentInternal(incident, actor, statusLabel(to, cancellation) + ": " + reason, false);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", from);
        details.put("to", to);
        if (cancellation) {
            details.put("cancelled", true);
        }
        audit.record("INCIDENT_STATUS_CHANGED", "INCIDENT", incident.getNumber(), details);
        publisher.publishEvent(new IncidentEvent(IncidentEventType.STATUS_CHANGED, incident.getId(), actor.id(), actor.depth(),
                cancellation ? Map.of("from", from.name(), "to", to.name(), "resolutionCode", ResolutionCode.CANCELLED.name())
                        : Map.of("from", from.name(), "to", to.name())));
    }

    private void resolveChildren(Incident parent, Actor actor, Instant now) {
        for (Incident child : incidents.findByParentIdOrderByCreatedAtAsc(parent.getId())) {
            if (!child.getStatus().isActive()) {
                continue;
            }
            IncidentStatus previous = child.getStatus();
            child.setStatus(IncidentStatus.RESOLVED);
            child.setResolvedAt(now);
            child.setResolvedBy(parent.getResolvedBy());
            child.setResolutionCode(parent.getResolutionCode());
            child.setResolutionNotes("Resolved with parent " + parent.getNumber() + ": " + parent.getResolutionNotes());
            child.setRootCause(parent.getRootCause());
            child.setResolutionArticle(parent.getResolutionArticle());
            timeline.change(child, actor, "status", previous, IncidentStatus.RESOLVED);
            notifyRequester(child, "INCIDENT_RESOLVED", child.getNumber() + " resolved",
                    "Resolved together with " + parent.getNumber() + ": " + parent.getResolutionNotes(), Severity.SUCCESS);
        }
    }

    // ------------------------------------------------------------------ assignment & escalation

    @Transactional
    public IncidentDetail assign(Long id, AssignRequest request, Actor actor) {
        IncidentAccess.requireStaff(actor);
        Incident incident = queries.load(id, actor);
        requireNotTerminal(incident);
        Instant now = clock.instant();
        User assignee = null;
        if (request.assigneeId() != null) {
            assignee = users.findWithDepartmentById(request.assigneeId())
                    .filter(u -> u.isActive() && u.getRole().isStaff())
                    .orElseThrow(() -> ApiException.badRequest("Assignee must be an active support staff member"));
        }
        Team team = null;
        if (request.teamId() != null) {
            team = teamRouting.activeById(request.teamId()).orElseThrow(() -> ApiException.badRequest("Team does not exist or is inactive"));
            if (assignee != null && !teamRouting.isMember(team, assignee)) {
                throw ApiException.badRequest(assignee.getFullName() + " is not a member of " + team.getName());
            }
        }
        Department department;
        if (request.departmentId() != null) {
            department = departments.findById(request.departmentId()).orElseThrow(() -> ApiException.badRequest("Department does not exist"));
            if (team != null && !team.getDepartment().getId().equals(department.getId())) {
                throw ApiException.badRequest(team.getName() + " belongs to " + team.getDepartment().getName());
            }
        } else if (team != null) {
            department = team.getDepartment();
        } else {
            department = assignee == null ? incident.getDepartment() : assignee.getDepartment();
        }
        if (team == null) {
            team = teamRouting.resolve(incident.getTeam(), department, assignee);
        }
        applyAssignment(incident, department, team, assignee, actor);
        if (assignee != null) {
            markResponded(incident, actor, now);
        }
        Map<String, Object> details = mapOf("department", department == null ? null : department.getCode(),
                "assignee", assignee == null ? null : assignee.getEmail());
        details.put("team", team == null ? null : team.getCode());
        audit.record("INCIDENT_ASSIGNED", "INCIDENT", incident.getNumber(), details);
        return queries.detailOf(incident, actor);
    }

    @Transactional
    public IncidentDetail autoAssign(Long id, Actor actor) {
        IncidentAccess.requireStaff(actor);
        Incident incident = queries.load(id, actor);
        requireNotTerminal(incident);
        AssignmentOutcome outcome = autoAssignInternal(incident, clock.instant());
        if (outcome.assigneeName() == null) {
            throw ApiException.conflict("No available agent in " + (outcome.departmentName() == null ? "any department" : outcome.departmentName()));
        }
        return queries.detailOf(incident, actor);
    }

    /**
     * Team routing (Release 3): an incident that already has an active team goes to that team's least-loaded member;
     * otherwise the service / department routing of {@link AssignmentService#route} picks the team and member.
     */
    private AssignmentOutcome autoAssignInternal(Incident incident, Instant now) {
        AssignmentService.Assignment routed = null;
        Team current = incident.getTeam();
        if (current != null && current.isActive()) {
            User member = teamRouting.leastLoadedMember(current).orElse(null);
            if (member != null) {
                routed = new AssignmentService.Assignment(current.getDepartment(), current, member);
            }
        }
        if (routed == null) {
            routed = assignment.route(incident.getService(), incident.getCategory());
        }
        applyAssignment(incident, routed.department(), routed.team(), routed.assignee(), Actor.SYSTEM);
        return new AssignmentOutcome(routed.department() == null ? null : routed.department().getName(),
                routed.assignee() == null ? null : routed.assignee().getFullName(), routed.team() == null ? null : routed.team().getName());
    }

    private void applyAssignment(Incident incident, Department department, Team team, User assignee, Actor actor) {
        String oldDepartment = incident.getDepartment() == null ? null : incident.getDepartment().getName();
        String oldAssignee = incident.getAssignee() == null ? null : incident.getAssignee().getFullName();
        String oldTeam = incident.getTeam() == null ? null : incident.getTeam().getName();
        Long previousTeamId = incident.getTeam() == null ? null : incident.getTeam().getId();
        Long previousAssigneeId = incident.getAssignee() == null ? null : incident.getAssignee().getId();
        incident.setDepartment(department);
        incident.setTeam(team);
        incident.setAssignee(assignee);
        timeline.change(incident, actor, "department", oldDepartment, department == null ? null : department.getName());
        timeline.change(incident, actor, "team", oldTeam, team == null ? null : team.getName());
        timeline.change(incident, actor, "assignee", oldAssignee, assignee == null ? null : assignee.getFullName());
        IncidentStatus status = incident.getStatus();
        if (assignee != null && (status == IncidentStatus.NEW || status == IncidentStatus.REOPENED)) {
            incident.setStatus(IncidentStatus.ASSIGNED);
            timeline.change(incident, actor, "status", status, IncidentStatus.ASSIGNED);
        } else if (assignee == null && status == IncidentStatus.ASSIGNED) {
            incident.setStatus(IncidentStatus.NEW);
            timeline.change(incident, actor, "status", IncidentStatus.ASSIGNED, IncidentStatus.NEW);
        }
        if (assignee != null && !assignee.getId().equals(previousAssigneeId)) {
            notifications.notifyUser(assignee.getId(), "INCIDENT_ASSIGNED", "Assigned to you: " + incident.getNumber(),
                    incident.getPriority().label() + " · " + incident.getTitle(), "/incidents/" + incident.getId(),
                    incident.getPriority() == Priority.CRITICAL ? Severity.CRITICAL : Severity.INFO);
        }
        if (assignee == null && team != null && incident.getParent() == null && !team.getId().equals(previousTeamId)) {
            notifications.notifyUsers(teamRouting.activeMembers(team).stream().map(User::getId).toList(), "TEAM_QUEUE",
                    "New in " + team.getName() + ": " + incident.getNumber(), incident.getPriority().label() + " · " + incident.getTitle()
                            + " is waiting in the team queue.", "/incidents/" + incident.getId(),
                    incident.getPriority() == Priority.CRITICAL ? Severity.CRITICAL : Severity.INFO);
        }
        if (!Objects.equals(oldAssignee, assignee == null ? null : assignee.getFullName()) || !Objects.equals(oldDepartment,
                department == null ? null : department.getName()) || !Objects.equals(oldTeam, team == null ? null : team.getName())) {
            Map<String, String> changes = mapOfStrings("department", department == null ? null : department.getCode(),
                    "assignee", assignee == null ? null : assignee.getEmail());
            changes.put("team", team == null ? "" : team.getCode());
            publisher.publishEvent(new IncidentEvent(IncidentEventType.ASSIGNED, incident.getId(), actor.id(), actor.depth(), changes));
        }
    }

    @Transactional
    public IncidentDetail escalate(Long id, String reason, Actor actor) {
        IncidentAccess.requireStaff(actor);
        Incident incident = queries.load(id, actor);
        requireNotTerminal(incident);
        escalateInternal(incident, reason, actor);
        return queries.detailOf(incident, actor);
    }

    /** Raises the escalation level by one and notifies the next tier. Used by agents and workflows. */
    @Transactional
    public void escalateInternal(Incident incident, String reason, Actor actor) {
        escalateInternal(incident, reason, actor, actor.isWorkflow() ? IncidentEscalation.Source.WORKFLOW : IncidentEscalation.Source.MANUAL);
    }

    @Transactional
    public void escalateInternal(Incident incident, String reason, Actor actor, IncidentEscalation.Source source) {
        int level = Math.min(3, incident.getEscalationLevel() + 1);
        int previous = incident.getEscalationLevel();
        incident.setEscalationLevel(level);
        IncidentEscalation record = new IncidentEscalation();
        record.setIncidentId(incident.getId());
        record.setLevel(level);
        record.setSource(source);
        record.setReason(reason.length() > 1000 ? reason.substring(0, 999) + "…" : reason);
        record.setActorId(actor.id());
        record.setActorName(actor.name());
        record.setCreatedAt(clock.instant());
        escalationRecords.save(record);
        timeline.change(incident, actor, "escalationLevel", previous, level);
        timeline.record(incident, actor, "ESCALATED", "Escalated to level " + level + ": " + reason);
        addCommentInternal(incident, actor, "Escalated to level " + level + ": " + reason, true);
        String link = "/incidents/" + incident.getId();
        Long departmentId = incident.getDepartment() == null ? null : incident.getDepartment().getId();
        if (level >= 3) {
            notifications.notifyRoles(LEADERSHIP, "INCIDENT_ESCALATED", "Level 3 escalation: " + incident.getNumber(),
                    reason, link, Severity.CRITICAL);
        } else {
            notifications.notifyDepartmentManager(departmentId, "INCIDENT_ESCALATED", "Escalated: " + incident.getNumber(), reason, link, Severity.WARNING);
        }
        audit.record("INCIDENT_ESCALATED", "INCIDENT", incident.getNumber(), Map.of("level", level, "reason", reason, "source", source));
        publisher.publishEvent(new IncidentEvent(IncidentEventType.ESCALATED, incident.getId(), actor.id(), actor.depth(),
                Map.of("level", String.valueOf(level))));
    }

    @Transactional
    public IncidentDetail setMajor(Long id, boolean major, Actor actor) {
        if (!actor.isSystem() && !actor.isAdminOrManager()) {
            throw ApiException.forbidden("Only managers and administrators can declare major incidents");
        }
        Incident incident = queries.load(id, actor);
        setMajorInternal(incident, major, actor);
        return queries.detailOf(incident, actor);
    }

    @Transactional
    public void setMajorInternal(Incident incident, boolean major, Actor actor) {
        if (incident.isMajor() == major) {
            return;
        }
        incident.setMajor(major);
        timeline.change(incident, actor, "major", !major, major);
        if (major) {
            notifications.notifyRoles(majorIncidentRecipients(), "MAJOR_INCIDENT", "Major incident: " + incident.getNumber(),
                    incident.getTitle(), "/incidents/" + incident.getId(), Severity.CRITICAL);
        }
        audit.record(major ? "MAJOR_INCIDENT_DECLARED" : "MAJOR_INCIDENT_CLEARED", "INCIDENT", incident.getNumber(), Map.of("automatic", false));
        publisher.publishEvent(new com.nexus360.events.DomainEvents.MajorIncidentEvent(incident.getId(), major, actor.id(), actor.name(), false));
    }

    /** Workflow / AI action helper: set priority directly (impact and urgency are left as recorded). */
    @Transactional
    public void setPriorityInternal(Incident incident, Priority priority, Actor actor, String reason) {
        Priority previous = incident.getPriority();
        if (previous == priority) {
            return;
        }
        incident.setPriority(priority);
        timeline.change(incident, actor, "priority", previous, priority);
        timeline.record(incident, actor, "FIELD_CHANGED", "Priority set to " + priority.label() + ": " + reason);
        sla.recalculate(incident, clock.instant());
        audit.record("INCIDENT_PRIORITY_SET", "INCIDENT", incident.getNumber(), Map.of("from", previous, "to", priority, "reason", reason));
        publisher.publishEvent(new IncidentEvent(IncidentEventType.UPDATED, incident.getId(), actor.id(), actor.depth(),
                Map.of("priority", priority.name())));
    }

    /** Workflow / AI helper: assigns to a department and agent; the team follows from the assignee (see {@link TeamRouting#resolve}). */
    @Transactional
    public void assignInternal(Incident incident, Department department, User assignee, Actor actor) {
        applyAssignment(incident, department, teamRouting.resolve(incident.getTeam(), department, assignee), assignee, actor);
    }

    /** Workflow helper (ASSIGN_TEAM): assigns to a team and, when given, one of its members (null = the team queue). */
    @Transactional
    public void assignToTeamInternal(Incident incident, Team team, User member, Actor actor) {
        applyAssignment(incident, team.getDepartment(), team, member, actor);
    }

    // ------------------------------------------------------------------ relationships

    @Transactional
    public IncidentDetail linkToParent(Long id, Long parentId, Actor actor) {
        IncidentAccess.requireStaff(actor);
        Incident incident = queries.load(id, actor);
        Incident parent = queries.load(parentId, actor);
        linkToParentInternal(incident, parent, null, actor);
        return queries.detailOf(incident, actor);
    }

    @Transactional
    public void linkToParentInternal(Incident incident, Incident parent, Double score, Actor actor) {
        if (incident.getId().equals(parent.getId())) {
            throw ApiException.badRequest("An incident cannot be its own parent");
        }
        if (parent.getParent() != null) {
            parent = parent.getParent();
        }
        if (incidents.countByParentId(incident.getId()) > 0) {
            throw ApiException.conflict(incident.getNumber() + " already has child incidents and cannot become a child");
        }
        if (!parent.getStatus().isActive()) {
            throw ApiException.conflict("Parent incident " + parent.getNumber() + " is not active");
        }
        if (incident.getParent() != null && incident.getParent().getId().equals(parent.getId())) {
            return;
        }
        Instant now = clock.instant();
        attachChild(incident, parent, score, actor, now);
        refreshParent(parent, now);
        audit.record("INCIDENT_PARENT_LINKED", "INCIDENT", incident.getNumber(), Map.of("parent", parent.getNumber()));
    }

    @Transactional
    public IncidentDetail unlinkFromParent(Long id, Actor actor) {
        IncidentAccess.requireStaff(actor);
        Incident incident = queries.load(id, actor);
        Incident parent = incident.getParent();
        if (parent == null) {
            throw ApiException.conflict(incident.getNumber() + " has no parent incident");
        }
        incident.setParent(null);
        incident.setDuplicateScore(null);
        timeline.record(incident, actor, "UNLINKED", "Unlinked from parent " + parent.getNumber());
        timeline.record(parent, actor, "UNLINKED", incident.getNumber() + " unlinked");
        if (incident.getStatus().isActive()) {
            sla.reopen(incident, clock.instant(), "Unlinked from parent " + parent.getNumber());
        }
        audit.record("INCIDENT_PARENT_UNLINKED", "INCIDENT", incident.getNumber(), Map.of("parent", parent.getNumber()));
        return queries.detailOf(incident, actor);
    }

    @Transactional
    public IncidentDetail addLink(Long id, Long targetId, LinkType type, Actor actor) {
        IncidentAccess.requireStaff(actor);
        Incident source = queries.load(id, actor);
        Incident target = queries.load(targetId, actor);
        if (source.getId().equals(target.getId())) {
            throw ApiException.badRequest("An incident cannot be linked to itself");
        }
        if (links.existsBySourceIdAndTargetIdAndLinkType(source.getId(), target.getId(), type)) {
            throw ApiException.conflict("These incidents are already linked");
        }
        IncidentLink link = new IncidentLink();
        link.setSource(source);
        link.setTarget(target);
        link.setLinkType(type);
        link.setCreatedById(actor.id());
        links.save(link);
        timeline.record(source, actor, "LINKED", type + " " + target.getNumber());
        timeline.record(target, actor, "LINKED", source.getNumber() + " " + type);
        return queries.detailOf(source, actor);
    }

    @Transactional
    public IncidentDetail removeLink(Long id, Long linkId, Actor actor) {
        IncidentAccess.requireStaff(actor);
        Incident incident = queries.load(id, actor);
        IncidentLink link = links.findById(linkId)
                .filter(l -> l.getSource().getId().equals(id) || l.getTarget().getId().equals(id))
                .orElseThrow(() -> ApiException.notFound("Link", linkId));
        links.delete(link);
        timeline.record(incident, actor, "UNLINKED", "Removed " + link.getLinkType() + " link");
        return queries.detailOf(incident, actor);
    }

    // ------------------------------------------------------------------ comments & deletion

    @Transactional
    public CommentDto addComment(Long id, String body, boolean internal, Actor actor) {
        Incident incident = queries.load(id, actor);
        if (actor.role() == Role.AUDITOR) {
            throw ApiException.forbidden("Auditors have read-only access");
        }
        if (internal && !actor.isStaff()) {
            throw ApiException.forbidden("Only support staff can add internal work notes");
        }
        IncidentComment comment = addCommentInternal(incident, actor, body.trim(), internal);
        timeline.record(incident, actor, "COMMENTED", internal ? "Added a work note" : "Added a comment");
        Instant now = clock.instant();
        String link = "/incidents/" + incident.getId();
        if (actor.isStaff() && !internal) {
            markResponded(incident, actor, now);
            notifyRequester(incident, "INCIDENT_COMMENT", "Update on " + incident.getNumber(), body, Severity.INFO);
        } else if (!actor.isStaff() && incident.getAssignee() != null) {
            notifications.notifyUser(incident.getAssignee().getId(), "INCIDENT_COMMENT", "Requester replied on " + incident.getNumber(),
                    body, link, Severity.INFO);
        }
        publisher.publishEvent(new IncidentEvent(IncidentEventType.COMMENTED, incident.getId(), actor.id(), actor.depth(), Map.of()));
        return new CommentDto(comment.getId(), comment.getBody(), comment.isInternal(), false, actor.id(), actor.name(), comment.getCreatedAt());
    }

    @Transactional
    public IncidentComment addCommentInternal(Incident incident, Actor actor, String body, boolean internal) {
        IncidentComment comment = new IncidentComment();
        comment.setIncident(incident);
        comment.setAuthor(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        comment.setBody(body.length() > 10000 ? body.substring(0, 10000) : body);
        comment.setInternal(internal);
        comment.setSystemGenerated(actor.isSystem());
        comment.setCreatedAt(clock.instant());
        return comments.save(comment);
    }

    @Transactional
    public void delete(Long id, Actor actor) {
        if (!actor.hasRole(Role.SYSTEM_ADMIN)) {
            throw ApiException.forbidden("Only system administrators can delete incidents");
        }
        Incident incident = queries.load(id, actor);
        audit.record("INCIDENT_DELETED", "INCIDENT", incident.getNumber(), Map.of("title", incident.getTitle()));
        incidents.delete(incident);
    }

    // ------------------------------------------------------------------ helpers

    private void markResponded(Incident incident, Actor actor, Instant now) {
        if (actor.isSystem() || !actor.isStaff()) {
            return;
        }
        if (incident.getFirstResponseAt() == null) {
            incident.setFirstResponseAt(now);
        }
        sla.recordResponse(incident, now);
    }

    private void notifyRequester(Incident incident, String type, String title, String message, Severity severity) {
        if (incident.getRequester() != null) {
            notifications.notifyUser(incident.getRequester().getId(), type, title, message, "/incidents/" + incident.getId(), severity);
        }
    }

    private void track(Map<String, String> changes, Incident incident, Actor actor, String field, Object before, Object after) {
        String a = before == null ? null : before.toString();
        String b = after == null ? null : after.toString();
        if (!Objects.equals(a, b)) {
            changes.put(field, b == null ? "" : b.length() > 200 ? b.substring(0, 200) : b);
            timeline.change(incident, actor, field, "description".equals(field) ? "(previous text)" : a, "description".equals(field) ? "(updated)" : b);
        }
    }

    private static void requireNotTerminal(Incident incident) {
        if (!incident.getStatus().isActive()) {
            throw ApiException.conflict(incident.getNumber() + " is " + incident.getStatus() + " and can no longer be changed");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.length() < 5) {
            throw ApiException.badRequest("Please give a reason of at least 5 characters");
        }
    }

    private Set<Role> majorIncidentRecipients() {
        return settings.getBoolean(SettingsService.MAJOR_NOTIFY_ALL_STAFF, false) ? Role.STAFF : LEADERSHIP;
    }

    private static String statusLabel(IncidentStatus status, boolean cancellation) {
        if (cancellation) {
            return "Cancelled";
        }
        return switch (status) {
            case PENDING -> "Set to pending";
            case REOPENED -> "Reopened";
            case IN_PROGRESS -> "Work resumed";
            case RESOLVED -> "Resolved";
            case CLOSED -> "Closed";
            case ASSIGNED -> "Assigned";
            default -> status.name();
        };
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static Map<String, String> mapOfStrings(String k1, String v1, String k2, String v2) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(k1, v1 == null ? "" : v1);
        map.put(k2, v2 == null ? "" : v2);
        return map;
    }
}
