package com.nexus360.problem;

import com.nexus360.admin.SettingsService;
import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.incident.Category;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.Priority;
import com.nexus360.incident.TextSimilarity;
import com.nexus360.knowledge.KnowledgeArticle;
import com.nexus360.knowledge.KnowledgeArticleRepository;
import com.nexus360.knowledge.KnowledgeDtos.ArticleDetail;
import com.nexus360.knowledge.KnowledgeDtos.ArticleRequest;
import com.nexus360.knowledge.KnowledgeService;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.problem.ProblemDtos.ActionDto;
import com.nexus360.problem.ProblemDtos.ActionRequest;
import com.nexus360.problem.ProblemDtos.ChangeRef;
import com.nexus360.problem.ProblemDtos.ClusterIncident;
import com.nexus360.problem.ProblemDtos.FromClusterRequest;
import com.nexus360.problem.ProblemDtos.HistoryDto;
import com.nexus360.problem.ProblemDtos.ProblemAnalytics;
import com.nexus360.problem.ProblemDtos.ProblemDetail;
import com.nexus360.problem.ProblemDtos.ProblemRequest;
import com.nexus360.problem.ProblemDtos.ProblemSummary;
import com.nexus360.problem.ProblemDtos.RecurringCluster;
import com.nexus360.problem.ProblemDtos.ServiceCount;
import com.nexus360.problem.ProblemDtos.ServiceRef;
import com.nexus360.problem.ProblemDtos.TransitionRequest;
import com.nexus360.security.Actor;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRouting;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * Problem management: lifecycle (state machine enforced here), linked incidents, root cause / workaround / permanent fix,
 * known-error records published to the knowledge base, corrective actions, recurring-incident detection and analytics.
 * Staff in the problem-editor group create and work problems; closing is reserved for admins and managers.
 */
@Service
@RequiredArgsConstructor
public class ProblemService {

    public static final Set<ProblemAction.Status> OPEN_ACTIONS = EnumSet.of(ProblemAction.Status.OPEN, ProblemAction.Status.IN_PROGRESS);

    /** List filters ({@code scope}: all, mine = owned by me, team = my teams). */
    public record Filter(String search, List<ProblemStatus> statuses, Priority priority, Long ownerId, Long teamId, Long serviceId,
                         Boolean knownError, String scope) {}

    private final ProblemRepository problems;
    private final ProblemActionRepository actions;
    private final ProblemHistoryRepository history;
    private final ProblemIncidentRepository links;
    private final IncidentRepository incidents;
    private final IncidentMapper incidentMapper;
    private final BusinessServiceRepository services;
    private final UserRepository users;
    private final TeamRouting teamRouting;
    private final KnowledgeService knowledge;
    private final KnowledgeArticleRepository articles;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final com.nexus360.cmdb.CiLinkService ciLinks;
    private final Clock clock;

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public PageResponse<ProblemSummary> list(Filter f, Integer page, Integer size, String sort, Actor actor) {
        Set<Long> myTeams = "team".equals(f.scope()) ? teamRouting.teamIdsOf(actor.id()) : Set.of();
        Specification<Problem> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.statuses() != null && !f.statuses().isEmpty()) {
                p.add(root.get("status").in(f.statuses()));
            }
            if (f.priority() != null) {
                p.add(cb.equal(root.get("priority"), f.priority()));
            }
            if (f.ownerId() != null) {
                p.add(cb.equal(root.get("owner").get("id"), f.ownerId()));
            }
            if (f.teamId() != null) {
                p.add(cb.equal(root.get("team").get("id"), f.teamId()));
            }
            if (f.knownError() != null) {
                p.add(cb.equal(root.get("knownError"), f.knownError()));
            }
            if (f.serviceId() != null) {
                Join<Object, Object> s = root.join("services", JoinType.INNER);
                p.add(cb.equal(s.get("id"), f.serviceId()));
                query.distinct(true);
            }
            if ("mine".equals(f.scope())) {
                p.add(cb.equal(root.get("owner").get("id"), actor.id()));
            } else if ("team".equals(f.scope())) {
                p.add(myTeams.isEmpty() ? cb.disjunction() : root.get("team").get("id").in(myTeams));
            }
            if (f.search() != null && !f.search().isBlank()) {
                String like = "%" + f.search().trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'), cb.like(cb.lower(root.get("number")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<Problem> result = problems.findAll(spec, PageResponse.pageable(page, size,
                sortOf(sort, Set.of("createdAt", "updatedAt", "priority", "status", "number", "title"), Sort.by(Sort.Direction.DESC, "createdAt"))));
        return PageResponse.of(result, summaries(result.getContent()));
    }

    @Transactional(readOnly = true)
    public List<ProblemSummary> summaries(List<Problem> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(Problem::getId).toList();
        Map<Long, Long> incidentCounts = counts(links.countByProblems(ids));
        Map<Long, Long> actionCounts = counts(actions.countOpenByProblems(ids, OPEN_ACTIONS));
        Map<Long, List<ServiceRef>> serviceRefs = serviceRefs(ids);
        return list.stream().map(p -> new ProblemSummary(p.getId(), p.getNumber(), p.getTitle(), p.getStatus(), p.getPriority(), p.getCategory(),
                p.getOwner() == null ? null : p.getOwner().getId(), p.getOwner() == null ? null : p.getOwner().getFullName(),
                p.getTeam() == null ? null : p.getTeam().getId(), p.getTeam() == null ? null : p.getTeam().getName(), p.isKnownError(),
                incidentCounts.getOrDefault(p.getId(), 0L), actionCounts.getOrDefault(p.getId(), 0L), serviceRefs.getOrDefault(p.getId(), List.of()),
                p.getCreatedAt(), p.getUpdatedAt(), p.isDemo())).toList();
    }

    @Transactional(readOnly = true)
    public ProblemDetail get(Long id) {
        return detail(load(id));
    }

    @Transactional(readOnly = true)
    public List<HistoryDto> history(Long id) {
        load(id);
        return history.findByProblemIdOrderByCreatedAtAscIdAsc(id).stream()
                .map(h -> new HistoryDto(h.getCreatedAt(), h.getActorName(), h.getAction(), h.getFromValue(), h.getToValue(), h.getNote())).toList();
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public ProblemDetail create(ProblemRequest r, Actor actor) {
        Problem p = new Problem();
        p.setNumber("PRB-" + problems.nextNumber());
        p.setCreatedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        p.setCreatedAt(clock.instant());
        apply(p, r, actor, true);
        problems.save(p);
        problems.flush();
        p.setAffectedCis(new java.util.ArrayList<>(ciLinks.sync(com.nexus360.cmdb.CiLinkService.Subject.PROBLEM, p.getId(), r.ciIds(), p.getAffectedCis(),
                true)));
        record(p, actor, "CREATED", null, p.getStatus().name(), p.getTitle());
        if (r.incidentIds() != null && !r.incidentIds().isEmpty()) {
            linkInternal(p, r.incidentIds(), actor);
        }
        if (p.getOwner() != null) {
            notifyOwner(p, "Problem assigned: " + p.getNumber(), p.getTitle());
        }
        audit.record("PROBLEM_CREATED", "PROBLEM", p.getNumber(), Map.of("title", p.getTitle(), "priority", p.getPriority(),
                "incidents", r.incidentIds() == null ? 0 : r.incidentIds().size()));
        return detail(p);
    }

    @Transactional
    public ProblemDetail update(Long id, ProblemRequest r, Actor actor) {
        Problem p = load(id);
        if (p.getStatus() == ProblemStatus.CLOSED) {
            throw ApiException.conflict(p.getNumber() + " is closed");
        }
        Long previousOwner = p.getOwner() == null ? null : p.getOwner().getId();
        apply(p, r, actor, false);
        p.setAffectedCis(new java.util.ArrayList<>(ciLinks.sync(com.nexus360.cmdb.CiLinkService.Subject.PROBLEM, p.getId(), r.ciIds(), p.getAffectedCis(),
                r.affectedCis() != null)));
        p.setUpdatedAt(clock.instant());
        Long owner = p.getOwner() == null ? null : p.getOwner().getId();
        if (!Objects.equals(previousOwner, owner) && owner != null) {
            notifyOwner(p, "Problem assigned: " + p.getNumber(), p.getTitle());
        }
        audit.record("PROBLEM_UPDATED", "PROBLEM", p.getNumber(), Map.of("title", p.getTitle()));
        return detail(p);
    }

    private void apply(Problem p, ProblemRequest r, Actor actor, boolean creating) {
        String title = r.title().trim();
        if (!title.equals(p.getTitle()) && !creating) {
            record(p, actor, "UPDATED", "title", null, title);
        }
        p.setTitle(title);
        if (creating || r.description() != null) {
            p.setDescription(r.description());
        }
        if (r.priority() != null && r.priority() != p.getPriority()) {
            if (!creating) {
                record(p, actor, "PRIORITY_CHANGED", p.getPriority().name(), r.priority().name(), null);
            }
            p.setPriority(r.priority());
        }
        if (r.category() != null || creating) {
            p.setCategory(r.category());
        }
        if (r.teamId() != null) {
            Team team = teamRouting.activeById(r.teamId()).orElseThrow(() -> ApiException.invalidField("teamId", "Team does not exist or is inactive"));
            if (!creating && (p.getTeam() == null || !p.getTeam().getId().equals(team.getId()))) {
                record(p, actor, "TEAM_CHANGED", p.getTeam() == null ? null : p.getTeam().getName(), team.getName(), null);
            }
            p.setTeam(team);
            p.setDepartment(team.getDepartment());
        }
        if (r.ownerId() != null) {
            User owner = users.findWithDepartmentById(r.ownerId()).filter(u -> u.isActive() && u.getRole().isStaff())
                    .orElseThrow(() -> ApiException.invalidField("ownerId", "The owner must be active support staff"));
            if (!creating && (p.getOwner() == null || !p.getOwner().getId().equals(owner.getId()))) {
                record(p, actor, "OWNER_CHANGED", p.getOwner() == null ? null : p.getOwner().getFullName(), owner.getFullName(), null);
            }
            p.setOwner(owner);
            if (p.getDepartment() == null) {
                p.setDepartment(owner.getDepartment());
            }
        }
        if (r.serviceIds() != null) {
            Set<BusinessService> chosen = new LinkedHashSet<>();
            for (Long sid : r.serviceIds()) {
                chosen.add(services.findById(sid).orElseThrow(() -> ApiException.invalidField("serviceIds", "Service " + sid + " does not exist")));
            }
            p.getServices().clear();
            p.getServices().addAll(chosen);
            if (p.getDepartment() == null && !chosen.isEmpty()) {
                p.setDepartment(chosen.iterator().next().getDepartment());
            }
        }
        if (r.affectedCis() != null) {
            p.setAffectedCis(new ArrayList<>(r.affectedCis().stream().map(String::trim).distinct().toList()));
        }
        if (r.rootCause() != null) {
            p.setRootCause(blankToNull(r.rootCause()));
        }
        if (r.workaround() != null) {
            p.setWorkaround(blankToNull(r.workaround()));
        }
        if (r.permanentFix() != null) {
            p.setPermanentFix(blankToNull(r.permanentFix()));
        }
    }

    /** Enforces the state machine and the data each state needs; closing needs an admin or manager. */
    @Transactional
    public ProblemDetail transition(Long id, TransitionRequest r, Actor actor) {
        Problem p = load(id);
        ProblemStatus from = p.getStatus();
        ProblemStatus to = r.status();
        if (from == to) {
            throw ApiException.conflict(p.getNumber() + " is already " + to);
        }
        if (!from.canMoveTo(to)) {
            throw ApiException.conflict("A problem cannot move from " + from + " to " + to + " (allowed: " + from.next() + ")");
        }
        if (r.rootCause() != null && !r.rootCause().isBlank()) {
            p.setRootCause(r.rootCause().trim());
        }
        if (r.workaround() != null && !r.workaround().isBlank()) {
            p.setWorkaround(r.workaround().trim());
        }
        if (r.permanentFix() != null && !r.permanentFix().isBlank()) {
            p.setPermanentFix(r.permanentFix().trim());
        }
        Instant now = clock.instant();
        switch (to) {
            case ROOT_CAUSE_IDENTIFIED -> {
                require(p.getRootCause(), "rootCause", "Record the root cause before marking it identified");
                p.setRootCauseAt(now);
            }
            case KNOWN_ERROR -> {
                require(p.getRootCause(), "rootCause", "A known error needs a root cause");
                require(p.getWorkaround(), "workaround", "A known error needs a workaround");
                p.setKnownError(true);
                p.setKnownErrorAt(p.getKnownErrorAt() == null ? now : p.getKnownErrorAt());
            }
            case RESOLVED -> {
                require(p.getPermanentFix(), "permanentFix", "Describe the permanent fix before resolving the problem");
                p.setResolvedAt(now);
            }
            case CLOSED -> {
                if (!actor.isAdminOrManager()) {
                    throw ApiException.forbidden("Only administrators and managers can close problems");
                }
                if (from == ProblemStatus.NEW || from == ProblemStatus.INVESTIGATING) {
                    require(r.note(), "note", "Give a reason for closing a problem that was not resolved");
                    p.setClosureNote("Cancelled: " + r.note().trim());
                } else if (actions.countByProblemIdAndStatusIn(p.getId(), OPEN_ACTIONS) > 0) {
                    throw ApiException.conflict("Finish or cancel the open corrective actions before closing " + p.getNumber());
                } else if (r.note() != null && !r.note().isBlank()) {
                    p.setClosureNote(r.note().trim());
                }
                p.setClosedAt(now);
            }
            case INVESTIGATING -> {
                if (from == ProblemStatus.RESOLVED) {
                    p.setResolvedAt(null);
                }
            }
            default -> { }
        }
        p.setStatus(to);
        p.setUpdatedAt(now);
        record(p, actor, "STATUS_CHANGED", from.name(), to.name(), r.note());
        if (to == ProblemStatus.KNOWN_ERROR && Boolean.TRUE.equals(r.publishKnownError()) && p.getKbArticle() == null) {
            publishKnownError(p, true, actor);
        }
        notifyOwner(p, p.getNumber() + " is now " + to.name().replace('_', ' ').toLowerCase(Locale.ROOT), p.getTitle());
        audit.record("PROBLEM_STATUS_CHANGED", "PROBLEM", p.getNumber(), Map.of("from", from, "to", to));
        return detail(p);
    }

    @Transactional
    public ProblemDetail link(Long id, List<Long> incidentIds, Actor actor) {
        Problem p = load(id);
        if (p.getStatus() == ProblemStatus.CLOSED) {
            throw ApiException.conflict(p.getNumber() + " is closed");
        }
        linkInternal(p, incidentIds, actor);
        audit.record("PROBLEM_INCIDENTS_LINKED", "PROBLEM", p.getNumber(), Map.of("incidents", incidentIds.size()));
        return detail(p);
    }

    private void linkInternal(Problem p, List<Long> incidentIds, Actor actor) {
        for (Long incidentId : new LinkedHashSet<>(incidentIds)) {
            Incident i = incidents.findById(incidentId).orElseThrow(() -> ApiException.invalidField("incidentIds", "Incident " + incidentId + " does not exist"));
            links.findByIncident(incidentId).ifPresent(existing -> {
                if (!existing.getId().getProblemId().equals(p.getId())) {
                    throw ApiException.conflict(i.getNumber() + " is already linked to another problem");
                }
            });
            if (links.existsById(new ProblemIncident.Key(p.getId(), incidentId))) {
                continue;
            }
            ProblemIncident link = new ProblemIncident();
            link.setId(new ProblemIncident.Key(p.getId(), incidentId));
            link.setLinkedById(actor.id());
            link.setCreatedAt(clock.instant());
            links.save(link);
            if (i.getService() != null && p.getServices().stream().noneMatch(s -> s.getId().equals(i.getService().getId()))) {
                p.getServices().add(i.getService());
            }
            if (p.getCategory() == null) {
                p.setCategory(i.getCategory());
            }
            record(p, actor, "INCIDENT_LINKED", null, i.getNumber(), i.getTitle());
        }
    }

    @Transactional
    public ProblemDetail unlink(Long id, Long incidentId, Actor actor) {
        Problem p = load(id);
        ProblemIncident.Key key = new ProblemIncident.Key(id, incidentId);
        if (!links.existsById(key)) {
            throw ApiException.notFound("Linked incident", incidentId);
        }
        links.deleteById(key);
        String number = incidents.findById(incidentId).map(Incident::getNumber).orElse("#" + incidentId);
        record(p, actor, "INCIDENT_UNLINKED", number, null, null);
        audit.record("PROBLEM_INCIDENT_UNLINKED", "PROBLEM", p.getNumber(), Map.of("incident", number));
        return detail(p);
    }

    @Transactional
    public ActionDto addAction(Long id, ActionRequest r, Actor actor) {
        Problem p = load(id);
        if (p.getStatus() == ProblemStatus.CLOSED) {
            throw ApiException.conflict(p.getNumber() + " is closed");
        }
        ProblemAction a = new ProblemAction();
        a.setProblem(p);
        a.setDemo(p.isDemo());
        applyAction(a, r);
        actions.save(a);
        record(p, actor, "ACTION_ADDED", null, a.getTitle(), a.getOwner() == null ? null : "Owner " + a.getOwner().getFullName());
        if (a.getOwner() != null) {
            notifications.notifyUser(a.getOwner().getId(), "PROBLEM_ACTION_ASSIGNED", "Corrective action for " + p.getNumber(), a.getTitle()
                    + (a.getDueDate() == null ? "" : " (due " + a.getDueDate() + ")"), "/problems/" + p.getId(), Severity.INFO);
        }
        audit.record("PROBLEM_ACTION_ADDED", "PROBLEM", p.getNumber(), Map.of("action", a.getTitle()));
        return actionDto(a);
    }

    @Transactional
    public ActionDto updateAction(Long id, Long actionId, ActionRequest r, Actor actor) {
        Problem p = load(id);
        ProblemAction a = actions.findById(actionId).filter(x -> x.getProblem().getId().equals(id))
                .orElseThrow(() -> ApiException.notFound("Corrective action", actionId));
        ProblemAction.Status before = a.getStatus();
        applyAction(a, r);
        if (before != a.getStatus()) {
            record(p, actor, "ACTION_" + a.getStatus().name(), before.name(), a.getStatus().name(), a.getTitle());
        }
        audit.record("PROBLEM_ACTION_UPDATED", "PROBLEM", p.getNumber(), Map.of("action", a.getTitle(), "status", a.getStatus()));
        return actionDto(a);
    }

    private void applyAction(ProblemAction a, ActionRequest r) {
        a.setTitle(r.title().trim());
        a.setDescription(r.description());
        if (r.actionType() != null) {
            a.setActionType(r.actionType());
        }
        a.setDueDate(r.dueDate());
        if (r.ownerId() != null) {
            a.setOwner(users.findById(r.ownerId()).filter(u -> u.isActive() && u.getRole().isStaff())
                    .orElseThrow(() -> ApiException.invalidField("ownerId", "The action owner must be active support staff")));
        }
        if (r.status() != null && r.status() != a.getStatus()) {
            a.setStatus(r.status());
            a.setCompletedAt(r.status() == ProblemAction.Status.DONE ? clock.instant() : null);
        }
    }

    /** Creates (or re-links) the KNOWN_ERROR knowledge article of a known error. */
    @Transactional
    public ProblemDetail knownErrorArticle(Long id, boolean publish, Actor actor) {
        Problem p = load(id);
        if (!p.isKnownError()) {
            throw ApiException.conflict(p.getNumber() + " is not a known error yet");
        }
        if (p.getKbArticle() != null) {
            throw ApiException.conflict(p.getNumber() + " already has the knowledge article " + p.getKbArticle().getNumber());
        }
        publishKnownError(p, publish, actor);
        return detail(p);
    }

    private void publishKnownError(Problem p, boolean publish, Actor actor) {
        boolean publisher = actor.isSystem() || actor.isAdminOrManager();
        KnowledgeArticle.Status status = publish ? (publisher ? KnowledgeArticle.Status.PUBLISHED : KnowledgeArticle.Status.IN_REVIEW)
                : KnowledgeArticle.Status.DRAFT;
        String symptoms = p.getDescription() == null || p.getDescription().isBlank() ? p.getTitle() : p.getDescription();
        String content = "## Known error " + p.getNumber() + "\n\n### Symptoms\n" + symptoms + "\n\n### Root cause\n" + p.getRootCause()
                + "\n\n### Workaround\n" + p.getWorkaround() + "\n\n### Permanent fix\n"
                + (p.getPermanentFix() == null ? "Not available yet; follow " + p.getNumber() + " for progress." : p.getPermanentFix());
        String summary = "Workaround: " + p.getWorkaround();
        summary = summary.length() > 500 ? summary.substring(0, 499) : summary;
        if (summary.length() < 10) {
            summary = summary + " (see article)";
        }
        String title = ("Known error: " + p.getTitle());
        title = title.length() > 200 ? title.substring(0, 200) : title;
        Long serviceId = p.getServices().stream().map(BusinessService::getId).findFirst().orElse(null);
        ArticleDetail article = knowledge.create(new ArticleRequest(title, summary, content, KnowledgeArticle.ArticleType.KNOWN_ERROR,
                p.getCategory() == null ? Category.OTHER : p.getCategory(), serviceId, List.of("known-error", p.getNumber().toLowerCase(Locale.ROOT)),
                status, "Known error record of " + p.getNumber()), actor);
        p.setKbArticle(articles.getReferenceById(article.id()));
        record(p, actor, "KNOWN_ERROR_ARTICLE", null, article.number(), status.name());
        audit.record("PROBLEM_KNOWN_ERROR_PUBLISHED", "PROBLEM", p.getNumber(), Map.of("article", article.number(), "status", status));
    }

    // ------------------------------------------------------------------ recurring incidents

    /**
     * Clusters of similar incidents without a problem: top-level, non-cancelled incidents of the last {@code days} days are
     * grouped per service (or category without service) and clustered greedily by text similarity
     * ({@link TextSimilarity#score}); clusters of at least {@code minSize} incidents are suggested, largest first.
     */
    @Transactional(readOnly = true)
    public List<RecurringCluster> recurring(Integer days, Integer minSize) {
        int window = days == null ? settings.getInt("problem.recurring.window_days", 30) : Math.max(1, Math.min(days, 365));
        int min = minSize == null ? settings.getInt("problem.recurring.min_incidents", 3) : Math.max(2, Math.min(minSize, 50));
        double threshold = settings.getDouble("problem.recurring.similarity", 0.42);
        List<Incident> candidates = problems.findUnlinkedIncidentsSince(clock.instant().minus(Duration.ofDays(window)), PageRequest.of(0, 1500));
        Map<String, List<Incident>> groups = new LinkedHashMap<>();
        for (Incident i : candidates) {
            String key = i.getService() != null ? "S" + i.getService().getId() : "C" + i.getCategory();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        List<RecurringCluster> out = new ArrayList<>();
        for (Map.Entry<String, List<Incident>> g : groups.entrySet()) {
            List<Incident> sorted = g.getValue().stream().sorted(Comparator.comparing(Incident::getCreatedAt)).toList();
            List<List<Incident>> clusters = new ArrayList<>();
            for (Incident i : sorted) {
                List<Incident> best = null;
                double bestScore = 0;
                for (List<Incident> c : clusters) {
                    double s = c.stream().mapToDouble(m -> TextSimilarity.score(i.getTitle(), i.getDescription(), m.getTitle(), m.getDescription()))
                            .max().orElse(0);
                    if (s >= threshold && s > bestScore) {
                        best = c;
                        bestScore = s;
                    }
                }
                if (best == null) {
                    clusters.add(new ArrayList<>(List.of(i)));
                } else {
                    best.add(i);
                }
            }
            for (List<Incident> c : clusters) {
                if (c.size() < min) {
                    continue;
                }
                Incident seed = c.get(0);
                List<ClusterIncident> members = c.stream().map(m -> new ClusterIncident(m.getId(), m.getNumber(), m.getTitle(), m.getStatus().name(),
                        m.getCreatedAt(), m == seed ? 1.0 : round(TextSimilarity.score(seed.getTitle(), seed.getDescription(), m.getTitle(), m.getDescription()))))
                        .sorted(Comparator.comparing(ClusterIncident::createdAt).reversed()).toList();
                double cohesion = round(members.stream().mapToDouble(ClusterIncident::similarity).average().orElse(0));
                String title = mostCommonTitle(c);
                out.add(new RecurringCluster(g.getKey() + ":" + seed.getId(), seed.getService() == null ? null : seed.getService().getId(),
                        seed.getService() == null ? null : seed.getService().getName(), seed.getCategory(), c.size(), cohesion,
                        c.get(0).getCreatedAt(), c.get(c.size() - 1).getCreatedAt(), "Recurring: " + title, members));
            }
        }
        out.sort(Comparator.comparingInt(RecurringCluster::size).reversed().thenComparing(RecurringCluster::lastSeen, Comparator.reverseOrder()));
        return out;
    }

    @Transactional
    public ProblemDetail fromCluster(FromClusterRequest r, Actor actor) {
        List<Incident> list = r.incidentIds().stream().map(id -> incidents.findById(id)
                .orElseThrow(() -> ApiException.invalidField("incidentIds", "Incident " + id + " does not exist"))).toList();
        String title = r.title() != null && !r.title().isBlank() ? r.title().trim() : "Recurring: " + mostCommonTitle(list);
        title = title.length() > 200 ? title.substring(0, 200) : title;
        Priority priority = r.priority() != null ? r.priority()
                : list.stream().map(Incident::getPriority).min(Comparator.naturalOrder()).orElse(Priority.MEDIUM);
        List<Long> serviceIds = list.stream().map(Incident::getService).filter(Objects::nonNull).map(BusinessService::getId).distinct().toList();
        Long teamId = list.stream().map(Incident::getTeam).filter(Objects::nonNull).map(Team::getId).findFirst().orElse(null);
        String description = "Recurring incidents detected by SA (" + list.size() + " similar reports): "
                + list.stream().map(Incident::getNumber).collect(Collectors.joining(", ")) + ".";
        return create(new ProblemRequest(title, description, priority, list.get(0).getCategory(), r.ownerId(), teamId, serviceIds, null, null, null,
                null, r.incidentIds()), actor);
    }

    // ------------------------------------------------------------------ analytics

    @Transactional(readOnly = true)
    public ProblemAnalytics analytics(Integer days) {
        int d = days == null ? 90 : Math.max(1, Math.min(days, 730));
        Instant to = clock.instant();
        Instant from = to.minus(Duration.ofDays(d));
        Timestamp f = Timestamp.from(from);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (ProblemStatus s : ProblemStatus.values()) {
            byStatus.put(s.name(), 0L);
        }
        jdbc.query("select status, count(*) from problems group by status", rs -> {
            byStatus.put(rs.getString(1), rs.getLong(2));
        });
        Map<String, Long> byPriority = new LinkedHashMap<>();
        for (Priority pr : Priority.values()) {
            byPriority.put(pr.name(), 0L);
        }
        jdbc.query("select priority, count(*) from problems where status <> 'CLOSED' group by priority", rs -> {
            byPriority.put(rs.getString(1), rs.getLong(2));
        });
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long open = ProblemStatus.OPEN.stream().mapToLong(s -> byStatus.getOrDefault(s.name(), 0L)).sum();
        long knownErrors = num("select count(*) from problems where known_error and status in ('KNOWN_ERROR', 'ROOT_CAUSE_IDENTIFIED')");
        long created = num("select count(*) from problems where created_at >= ?", f);
        long resolved = num("select count(*) from problems where resolved_at >= ?", f);
        Double toRootCause = jdbc.queryForObject("select avg(extract(epoch from (root_cause_at - created_at)) / 3600.0) from problems "
                + "where root_cause_at is not null and created_at >= ?", Double.class, f);
        Double toResolve = jdbc.queryForObject("select avg(extract(epoch from (resolved_at - created_at)) / 3600.0) from problems "
                + "where resolved_at is not null and created_at >= ?", Double.class, f);
        long linked = num("select count(*) from problem_incidents where created_at >= ?", f);
        long recurrences = num("select count(*) from problem_incidents pi join problems p on p.id = pi.problem_id join incidents i on i.id = pi.incident_id "
                + "where p.known_error_at is not null and i.created_at > p.known_error_at and not i.simulated");
        long overdue = num("select count(*) from problem_actions where status in ('OPEN', 'IN_PROGRESS') and due_date < ?",
                java.sql.Date.valueOf(LocalDate.ofInstant(to, ZoneOffset.UTC)));
        List<ServiceCount> top = jdbc.query("select s.id, s.name, count(distinct p.id) as problems, count(distinct pi.incident_id) as incidents "
                + "from problem_services ps join problems p on p.id = ps.problem_id join business_services s on s.id = ps.service_id "
                + "left join problem_incidents pi on pi.problem_id = p.id group by s.id, s.name order by problems desc, incidents desc limit 5",
                (rs, n) -> new ServiceCount(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4)));
        return new ProblemAnalytics(LocalDate.ofInstant(from, ZoneOffset.UTC), LocalDate.ofInstant(to, ZoneOffset.UTC), total, open, byStatus, byPriority,
                knownErrors, created, resolved, toRootCause == null ? null : round(toRootCause), toResolve == null ? null : round(toResolve), linked,
                recurrences, overdue, recurring(null, null).size(), top);
    }

    // ------------------------------------------------------------------ helpers

    Problem load(Long id) {
        return problems.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Problem", id));
    }

    private ProblemDetail detail(Problem p) {
        List<Long> incidentIds = p.getId() == null ? List.of() : links.findIncidentIds(p.getId());
        List<Incident> linked = incidentIds.isEmpty() ? List.of() : incidents.findAllById(incidentIds);
        List<ChangeRef> changes = jdbc.query("select id, number, title, status, change_type from changes where problem_id = ? order by created_at",
                (rs, n) -> new ChangeRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), p.getId());
        List<ActionDto> actionDtos = actions.findByProblemIdOrderByCreatedAtAscIdAsc(p.getId()).stream().map(this::actionDto).toList();
        KnowledgeArticle kb = p.getKbArticle();
        return new ProblemDetail(p.getId(), p.getNumber(), p.getTitle(), p.getDescription(), p.getStatus(), p.getPriority(), p.getCategory(),
                p.getOwner() == null ? null : p.getOwner().getId(), p.getOwner() == null ? null : p.getOwner().getFullName(),
                p.getTeam() == null ? null : p.getTeam().getId(), p.getTeam() == null ? null : p.getTeam().getName(),
                p.getDepartment() == null ? null : p.getDepartment().getId(), p.getDepartment() == null ? null : p.getDepartment().getName(),
                p.getRootCause(), p.getWorkaround(), p.getPermanentFix(), p.isKnownError(), p.getKnownErrorAt(), kb == null ? null : kb.getId(),
                kb == null ? null : kb.getNumber(), kb == null ? null : kb.getStatus().name(), p.getAffectedCis(), p.getClosureNote(), p.getRootCauseAt(),
                p.getResolvedAt(), p.getClosedAt(), p.getCreatedBy() == null ? null : p.getCreatedBy().getFullName(),
                p.getServices().stream().map(s -> new ServiceRef(s.getId(), s.getCode(), s.getName())).toList(),
                incidentMapper.summaries(linked.stream().sorted(Comparator.comparing(Incident::getCreatedAt).reversed()).toList()), actionDtos, changes,
                List.copyOf(p.getStatus().next()), p.getCreatedAt(), p.getUpdatedAt(), p.isDemo(),
                ciLinks.refs(com.nexus360.cmdb.CiLinkService.Subject.PROBLEM, p.getId()));
    }

    private ActionDto actionDto(ProblemAction a) {
        boolean overdue = a.getDueDate() != null && OPEN_ACTIONS.contains(a.getStatus()) && a.getDueDate().isBefore(LocalDate.now(clock));
        return new ActionDto(a.getId(), a.getTitle(), a.getDescription(), a.getActionType(), a.getOwner() == null ? null : a.getOwner().getId(),
                a.getOwner() == null ? null : a.getOwner().getFullName(), a.getDueDate(), a.getStatus(), overdue, a.getCompletedAt(), a.getCreatedAt());
    }

    private Map<Long, List<ServiceRef>> serviceRefs(List<Long> problemIds) {
        Map<Long, List<ServiceRef>> out = new HashMap<>();
        org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        named.query("select ps.problem_id, s.id, s.code, s.name from problem_services ps join business_services s on s.id = ps.service_id "
                + "where ps.problem_id in (:ids) order by s.name", Map.of("ids", problemIds), rs -> {
            out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new ServiceRef(rs.getLong(2), rs.getString(3), rs.getString(4)));
        });
        return out;
    }

    private void notifyOwner(Problem p, String title, String message) {
        if (p.getOwner() != null) {
            notifications.notifyUser(p.getOwner().getId(), "PROBLEM_UPDATED", title, message, "/problems/" + p.getId(), Severity.INFO);
        }
    }

    private void record(Problem p, Actor actor, String action, String from, String to, String note) {
        ProblemHistory h = new ProblemHistory();
        h.setProblemId(p.getId());
        h.setActorId(actor.id());
        h.setActorName(actor.name());
        h.setAction(action);
        h.setFromValue(truncate(from, 200));
        h.setToValue(truncate(to, 200));
        h.setNote(truncate(note, 1000));
        h.setCreatedAt(clock.instant());
        history.save(h);
    }

    private long num(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    private static Map<Long, Long> counts(List<Object[]> rows) {
        Map<Long, Long> m = new HashMap<>();
        rows.forEach(r -> m.put((Long) r[0], (Long) r[1]));
        return m;
    }

    private static String mostCommonTitle(List<Incident> list) {
        return list.stream().collect(Collectors.groupingBy(i -> i.getTitle().trim(), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(list.get(0).getTitle());
    }

    private static void require(String value, String field, String message) {
        if (value == null || value.isBlank()) {
            throw ApiException.invalidField(field, message);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
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

    /** For My Work: active problems I own or that wait unowned in my teams. */
    @Transactional(readOnly = true)
    public List<ProblemSummary> workFor(Long userId, Set<Long> teamIds, int limit) {
        Set<Long> teams = teamIds.isEmpty() ? Set.of(-1L) : teamIds;
        return summaries(problems.findWorkFor(userId, teams, ProblemStatus.OPEN, PageRequest.of(0, limit)));
    }

    @Transactional(readOnly = true)
    public long workCount(Long userId, Set<Long> teamIds) {
        return problems.countWorkFor(userId, teamIds.isEmpty() ? Set.of(-1L) : teamIds, ProblemStatus.OPEN);
    }
}
