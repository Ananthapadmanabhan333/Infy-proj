package com.nexus360.incident;

import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalRepository;
import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.common.Csv;
import com.nexus360.common.PageResponse;
import com.nexus360.incident.IncidentDtos.ActivityItem;
import com.nexus360.incident.IncidentDtos.CommentDto;
import com.nexus360.incident.IncidentDtos.DuplicateCandidate;
import com.nexus360.incident.IncidentDtos.EscalationDto;
import com.nexus360.incident.IncidentDtos.HistoryDto;
import com.nexus360.incident.IncidentDtos.IncidentCounts;
import com.nexus360.incident.IncidentDtos.IncidentDetail;
import com.nexus360.incident.IncidentDtos.IncidentLinkDto;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.incident.IncidentDtos.LinkedIncident;
import com.nexus360.incident.IncidentDtos.SlaDetail;
import com.nexus360.incident.IncidentDtos.SlaEventDto;
import com.nexus360.incident.IncidentDtos.SlaSnapshot;
import com.nexus360.security.Actor;
import com.nexus360.sla.IncidentSla;
import com.nexus360.sla.IncidentSlaRepository;
import com.nexus360.sla.SlaEventRepository;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IncidentQueryService {

    /** {@code scope}: all, mine (assigned to me), reported, department (my department) or team (my teams, Release 3). */
    public record Filter(String search, List<IncidentStatus> statuses, List<Priority> priorities, Category category, Long serviceId,
                         Long departmentId, Long assigneeId, String scope, Boolean major, Boolean parentOnly, String slaHealth,
                         Long teamId) {

        public Filter(String search, List<IncidentStatus> statuses, List<Priority> priorities, Category category, Long serviceId,
                      Long departmentId, Long assigneeId, String scope, Boolean major, Boolean parentOnly, String slaHealth) {
            this(search, statuses, priorities, category, serviceId, departmentId, assigneeId, scope, major, parentOnly, slaHealth, null);
        }
    }

    private final IncidentRepository incidents;
    private final IncidentCommentRepository comments;
    private final IncidentHistoryRepository history;
    private final IncidentLinkRepository links;
    private final IncidentSlaRepository slas;
    private final SlaEventRepository slaEvents;
    private final BusinessServiceRepository services;
    private final UserRepository users;
    private final IncidentMapper mapper;
    private final com.nexus360.team.TeamRouting teamRouting;
    private final IncidentClassifier classifier;
    private final IncidentCategoryService categoryConfig;
    private final DuplicateDetector duplicates;
    private final IncidentEscalationRepository escalations;
    private final ApprovalRepository approvals;
    private final EntityManager entityManager;
    private final AuditService audit;
    private final Clock clock;

    private static final Set<String> SLA_EVENTS_IN_ACTIVITY = Set.of("STARTED", "PAUSED", "RESUMED", "RESPONDED", "MET", "MISSED",
            "RECALCULATED", "CANCELLED");

    public PageResponse<IncidentSummary> search(Filter filter, Integer page, Integer size, String sort, Actor actor) {
        boolean byPriority = sort != null && sort.startsWith("priority");
        Sort order = byPriority ? Sort.unsorted() : parseSort(sort);
        Pageable pageable = PageResponse.pageable(page, size, order);
        Page<Incident> result = incidents.findAll(specification(filter, actor, byPriority), pageable);
        return PageResponse.of(result, mapper.summaries(result.getContent()));
    }

    public Incident load(Long id, Actor actor) {
        Incident incident = incidents.findDetailedById(id).orElseThrow(() -> ApiException.notFound("Incident", id));
        IncidentAccess.requireView(incident, actor);
        return incident;
    }

    public IncidentDetail detail(Long id, Actor actor) {
        return detailOf(load(id, actor), actor);
    }

    public IncidentDetail detailOf(Incident incident, Actor actor) {
        List<Incident> children = incidents.findByParentIdOrderByCreatedAtAsc(incident.getId());
        boolean insider = insider(actor);
        List<EscalationDto> escalationList = insider ? escalations(incident.getId()) : List.of();
        List<IncidentSummary> related = List.of();
        if (insider && incident.getService() != null) {
            List<Long> excluded = new ArrayList<>();
            excluded.add(incident.getId());
            if (incident.getParent() != null) {
                excluded.add(incident.getParent().getId());
            }
            related = mapper.summaries(incidents.findRelatedActive(incident.getService().getId(), excluded, IncidentStatus.ACTIVE,
                    PageRequest.of(0, 5)));
        }
        return mapper.detail(incident, children, linkDtos(incident.getId()), IncidentStateMachine.allowedFor(incident, actor),
                escalationList, related);
    }

    public List<EscalationDto> escalations(Long incidentId) {
        return escalations.findByIncidentIdOrderByCreatedAtAscIdAsc(incidentId).stream()
                .map(e -> new EscalationDto(e.getLevel(), e.getReason(), e.getActorName(), e.getCreatedAt(), e.getSource().name()))
                .toList();
    }

    /** Status-tab counts for the same filters as {@link #search} (one aggregate query). */
    public IncidentCounts counts(Filter filter, Actor actor) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Incident> root = query.from(Incident.class);
        Predicate where = specification(filter, actor, false).toPredicate(root, query, cb);
        Expression<Long> one = cb.literal(1L);
        Expression<Long> zero = cb.literal(0L);
        query.multiselect(
                cb.count(root),
                cb.sum(cb.<Long>selectCase().when(root.get("status").in(IncidentStatus.ACTIVE), one).otherwise(zero)),
                cb.sum(cb.<Long>selectCase().when(cb.equal(root.get("status"), IncidentStatus.IN_PROGRESS), one).otherwise(zero)),
                cb.sum(cb.<Long>selectCase().when(root.get("status").in(EnumSet.of(IncidentStatus.RESOLVED, IncidentStatus.CLOSED)), one)
                        .otherwise(zero)));
        if (where != null) {
            query.where(where);
        }
        Tuple t = entityManager.createQuery(query).getSingleResult();
        return new IncidentCounts(value(t.get(0)), value(t.get(1)), value(t.get(2)), value(t.get(3)));
    }

    /**
     * Merged timeline, newest first: field and status history, comments (visibility rules apply), SLA clock events,
     * escalations, approvals and runbook activity.
     */
    public List<ActivityItem> activity(Long id, Actor actor) {
        Incident incident = load(id, actor);
        boolean insider = insider(actor);
        List<ActivityItem> items = new ArrayList<>();
        for (IncidentHistory h : history.findByIncidentIdOrderByCreatedAtAscIdAsc(id)) {
            if ("COMMENTED".equals(h.getEventType()) || "escalationLevel".equals(h.getField())) {
                continue; // comments are listed from the comment table; levels from the escalation entries
            }
            String kind = switch (h.getEventType()) {
                case "SLA" -> "SLA";
                case "ESCALATED" -> "ESCALATION";
                case "RUNBOOK" -> "RUNBOOK";
                default -> "HISTORY";
            };
            if ("ESCALATION".equals(kind) && !insider) {
                continue; // escalation reasons are internal
            }
            items.add(new ActivityItem(kind, h.getCreatedAt(), h.getActorName(), historyTitle(h), h.getMessage()));
        }
        List<IncidentComment> commentList = insider ? comments.findByIncidentIdOrderByCreatedAtAscIdAsc(id)
                : comments.findByIncidentIdAndInternalFalseOrderByCreatedAtAscIdAsc(id);
        for (IncidentComment c : commentList) {
            items.add(new ActivityItem("COMMENT", c.getCreatedAt(), c.getAuthor() == null ? "SA Automation" : c.getAuthor().getFullName(),
                    c.isInternal() ? "Work note" : "Comment", c.getBody()));
        }
        slas.findByIncidentId(id).ifPresent(sla -> slaEvents.findByIncidentSlaIdOrderByCreatedAtAscIdAsc(sla.getId()).stream()
                .filter(e -> SLA_EVENTS_IN_ACTIVITY.contains(e.getEventType()))
                .forEach(e -> items.add(new ActivityItem("SLA", e.getCreatedAt(), "SLA engine", "SLA " + humanize(e.getEventType()).toLowerCase(Locale.ROOT),
                        e.getDetails()))));
        if (insider) {
            for (Approval a : approvals.findByReferenceNumberAndTypeInOrderByCreatedAtAsc(incident.getNumber(),
                    List.of(Approval.Type.AI_ACTION, Approval.Type.RUNBOOK_EXECUTION, Approval.Type.RUNBOOK_STEP))) {
                items.add(new ActivityItem("APPROVAL", a.getCreatedAt(), a.getRequestedBy() == null ? "SA Automation" : a.getRequestedBy().getFullName(),
                        "Approval requested: " + a.getTitle(), "Routed to " + a.getApproverRole()));
                if (a.getDecidedAt() != null) {
                    items.add(new ActivityItem("APPROVAL", a.getDecidedAt(), a.getDecidedBy() == null ? null : a.getDecidedBy().getFullName(),
                            (a.getStatus() == Approval.Status.APPROVED ? "Approved: " : "Rejected: ") + a.getTitle(), a.getDecisionComment()));
                }
            }
        }
        items.sort(Comparator.comparing(ActivityItem::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    public List<CommentDto> comments(Long id, Actor actor) {
        load(id, actor);
        List<IncidentComment> list = actor.isStaff() || actor.role() == Role.AUDITOR
                ? comments.findByIncidentIdOrderByCreatedAtAscIdAsc(id)
                : comments.findByIncidentIdAndInternalFalseOrderByCreatedAtAscIdAsc(id);
        return list.stream().map(c -> new CommentDto(c.getId(), c.getBody(), c.isInternal(), c.isSystemGenerated(),
                c.getAuthor() == null ? null : c.getAuthor().getId(),
                c.getAuthor() == null ? "SA Automation" : c.getAuthor().getFullName(), c.getCreatedAt())).toList();
    }

    public List<HistoryDto> history(Long id, Actor actor) {
        load(id, actor);
        return history.findByIncidentIdOrderByCreatedAtAscIdAsc(id).stream()
                .map(h -> new HistoryDto(h.getId(), h.getEventType(), h.getField(), h.getOldValue(), h.getNewValue(), h.getMessage(),
                        h.getActorName(), h.getCreatedAt()))
                .toList();
    }

    public SlaDetail sla(Long id, Actor actor) {
        load(id, actor);
        IncidentSla sla = slas.findByIncidentId(id).orElseThrow(() -> ApiException.notFound("SLA for incident", id));
        SlaSnapshot s = mapper.snapshot(sla, clock.instant());
        List<SlaEventDto> events = slaEvents.findByIncidentSlaIdOrderByCreatedAtAscIdAsc(sla.getId()).stream()
                .map(e -> new SlaEventDto(e.getId(), e.getEventType(), e.getDetails(), e.getCreatedAt())).toList();
        return new SlaDetail(s.policyName(), s.businessHoursOnly(), s.clockStatus(), s.health(), s.outcome(), s.responseDueAt(),
                s.respondedAt(), s.responseBreached(), s.resolutionDueAt(), s.remainingSeconds(), s.percentElapsed(), s.escalationLevel(),
                sla.getStartedAt(), sla.getResponseTargetMinutes(), sla.getResolutionTargetMinutes(), sla.getAtRiskPercent(),
                sla.getTotalPausedSeconds(), events);
    }

    /** Rule-based duplicate suggestions for an existing incident (excludes itself and its own family). */
    public List<DuplicateCandidate> duplicatesFor(Long id, Actor actor) {
        Incident incident = load(id, actor);
        Set<Long> family = new HashSet<>();
        family.add(incident.getId());
        if (incident.getParent() != null) {
            family.add(incident.getParent().getId());
        }
        incidents.findByParentIdOrderByCreatedAtAsc(incident.getId()).forEach(c -> family.add(c.getId()));
        return duplicates.candidates(incident.getTitle(), incident.getDescription(), incident.getService(), incident.getCategory(),
                        incident.getId(), duplicates.suggestionThreshold(), 12).stream()
                .filter(m -> !family.contains(m.incident().getId())
                        && (m.incident().getParent() == null || !family.contains(m.incident().getParent().getId())))
                .limit(8)
                .map(m -> candidate(m.incident(), m.score()))
                .toList();
    }

    /** Live "possible duplicates" while a user types a new report. Employees only see numbers and titles. */
    public List<DuplicateCandidate> preview(String title, String description, Long serviceId) {
        if ((title == null || title.trim().length() < 5) && (description == null || description.trim().length() < 10)) {
            return List.of();
        }
        List<BusinessService> all = services.findAllByOrderByTierAscNameAsc();
        IncidentClassifier.Result classified = classifier.classify(title, description, all, categoryConfig.rules());
        BusinessService service = serviceId == null ? classified.service()
                : all.stream().filter(s -> s.getId().equals(serviceId)).findFirst().orElse(null);
        Category category = classified.category();
        return duplicates.candidates(title, description, service, category, null, duplicates.suggestionThreshold(), 5).stream()
                .map(m -> candidate(m.incident(), m.score())).toList();
    }

    public List<IncidentLinkDto> linkDtos(Long incidentId) {
        return links.findAllInvolving(incidentId).stream().map(l -> {
            boolean outgoing = l.getSource().getId().equals(incidentId);
            Incident other = outgoing ? l.getTarget() : l.getSource();
            return new IncidentLinkDto(l.getId(), l.getLinkType(), outgoing ? "OUTGOING" : "INCOMING",
                    new LinkedIncident(other.getId(), other.getNumber(), other.getTitle(), other.getStatus(), other.getPriority()));
        }).toList();
    }

    public ResponseEntity<byte[]> export(Filter filter, Actor actor) {
        List<Incident> rows = incidents.findAll(specification(filter, actor, false),
                PageRequest.of(0, 5000, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        List<IncidentSummary> summaries = mapper.summaries(rows);
        List<List<Object>> data = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Incident inc = rows.get(i);
            IncidentSummary s = summaries.get(i);
            data.add(java.util.Arrays.asList(inc.getNumber(), inc.getTitle(), inc.getPriority(), inc.getStatus(), inc.getCategory(),
                    s.serviceName(), s.departmentName(), s.assigneeName(), s.requesterName(), s.parentNumber(), inc.isMajor(),
                    inc.getCreatedAt(), inc.getResolvedAt(), s.sla() == null ? null : s.sla().health(),
                    s.sla() == null ? null : s.sla().outcome(), inc.isDemo()));
        }
        audit.record("INCIDENTS_EXPORTED", "INCIDENT", null, java.util.Map.of("rows", rows.size()));
        return Csv.response("nexus360-incidents.csv", List.of("number", "title", "priority", "status", "category", "service",
                "department", "assignee", "requester", "parent", "major", "created_at", "resolved_at", "sla_health", "sla_outcome",
                "demo"), data);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean insider(Actor actor) {
        return actor.isSystem() || actor.isStaff() || actor.role() == Role.AUDITOR;
    }

    private static long value(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static String historyTitle(IncidentHistory h) {
        if (h.getField() != null) {
            return switch (h.getField()) {
                case "status" -> "Status changed from " + humanize(h.getOldValue()) + " to " + humanize(h.getNewValue());
                case "assignee" -> h.getNewValue() == null ? "Unassigned" : "Assigned to " + h.getNewValue();
                case "department" -> h.getNewValue() == null ? "Assignment group cleared" : "Assignment group set to " + h.getNewValue();
                default -> humanize(h.getField()) + " changed" + (h.getNewValue() == null ? "" : " to " + h.getNewValue());
            };
        }
        return switch (h.getEventType()) {
            case "CREATED" -> "Incident created";
            case "LINKED" -> "Linked";
            case "UNLINKED" -> "Unlinked";
            case "SLA" -> "SLA update";
            case "ESCALATED" -> "Escalated";
            case "RUNBOOK" -> "Runbook";
            case "AI" -> "SA AI";
            case "ATTACHMENT" -> "Attachment";
            default -> humanize(h.getEventType());
        };
    }

    /** "IN_PROGRESS" to "In progress", "affectedUsers" to "Affected users". */
    static String humanize(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String spaced = value.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private DuplicateCandidate candidate(Incident i, double score) {
        return new DuplicateCandidate(i.getId(), i.getNumber(), i.getTitle(), i.getStatus(), i.getPriority(),
                i.getService() == null ? null : i.getService().getName(), i.getCreatedAt(), score,
                i.getParent() == null ? null : i.getParent().getId());
    }

    private Specification<Incident> specification(Filter f, Actor actor, boolean orderByPriority) {
        Long departmentOfActor = "department".equals(f.scope()) && actor.id() != null
                ? users.findWithDepartmentById(actor.id()).map(User::getDepartment).map(d -> d.getId()).orElse(-1L) : null;
        Set<Long> teamsOfActor = "team".equals(f.scope()) ? teamRouting.teamIdsOf(actor.id()) : Set.of();
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            boolean employee = !actor.isSystem() && !actor.isStaff() && actor.role() != Role.AUDITOR;
            String scope = employee ? "reported" : f.scope() == null ? "all" : f.scope();
            switch (scope) {
                case "mine" -> predicates.add(cb.equal(root.get("assignee").get("id"), actor.id()));
                case "reported" -> predicates.add(cb.or(
                        cb.equal(root.join("requester", JoinType.LEFT).get("id"), actor.id()),
                        cb.equal(root.join("createdBy", JoinType.LEFT).get("id"), actor.id())));
                case "department" -> predicates.add(cb.equal(root.get("department").get("id"), departmentOfActor));
                case "team" -> predicates.add(teamsOfActor.isEmpty() ? cb.disjunction() : root.get("team").get("id").in(teamsOfActor));
                default -> { }
            }
            if (f.search() != null && !f.search().isBlank()) {
                String like = "%" + f.search().trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'),
                        cb.like(cb.lower(root.get("number")), like, '\\'),
                        cb.like(cb.lower(root.get("description")), like, '\\')));
            }
            if (f.statuses() != null && !f.statuses().isEmpty()) {
                predicates.add(root.get("status").in(f.statuses()));
            }
            if (f.priorities() != null && !f.priorities().isEmpty()) {
                predicates.add(root.get("priority").in(f.priorities()));
            }
            if (f.category() != null) {
                predicates.add(cb.equal(root.get("category"), f.category()));
            }
            if (f.serviceId() != null) {
                predicates.add(cb.equal(root.get("service").get("id"), f.serviceId()));
            }
            if (f.departmentId() != null) {
                predicates.add(cb.equal(root.get("department").get("id"), f.departmentId()));
            }
            if (f.assigneeId() != null) {
                predicates.add(cb.equal(root.get("assignee").get("id"), f.assigneeId()));
            }
            if (f.teamId() != null) {
                predicates.add(cb.equal(root.get("team").get("id"), f.teamId()));
            }
            if (Boolean.TRUE.equals(f.major())) {
                predicates.add(cb.isTrue(root.get("major")));
            }
            if (Boolean.TRUE.equals(f.parentOnly())) {
                predicates.add(cb.isNull(root.get("parent")));
            }
            if (f.slaHealth() != null && !f.slaHealth().isBlank()) {
                Subquery<Long> sub = query.subquery(Long.class);
                var sla = sub.from(IncidentSla.class);
                Predicate match = "PAUSED".equalsIgnoreCase(f.slaHealth())
                        ? cb.equal(sla.get("clockStatus"), IncidentSla.ClockStatus.PAUSED)
                        : cb.and(cb.equal(sla.get("health"), IncidentSla.Health.valueOf(f.slaHealth().toUpperCase(Locale.ROOT))),
                        cb.notEqual(sla.get("clockStatus"), IncidentSla.ClockStatus.STOPPED));
                sub.select(sla.get("id")).where(cb.equal(sla.get("incident"), root), match);
                predicates.add(cb.exists(sub));
            }
            if (orderByPriority && query.getResultType() != Long.class && query.getResultType() != long.class) {
                Expression<Integer> rank = cb.<Integer>selectCase()
                        .when(cb.equal(root.get("priority"), Priority.CRITICAL), 0)
                        .when(cb.equal(root.get("priority"), Priority.HIGH), 1)
                        .when(cb.equal(root.get("priority"), Priority.MEDIUM), 2)
                        .otherwise(3);
                query.orderBy(cb.asc(rank), cb.desc(root.get("createdAt")));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        String[] parts = sort.split(",");
        String field = switch (parts[0]) {
            case "updatedAt", "number", "status", "title", "resolvedAt" -> parts[0];
            default -> "createdAt";
        };
        Sort.Direction direction = parts.length > 1 && parts[1].equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, field);
    }
}
