package com.nexus360.knowledge;

import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.incident.Category;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.knowledge.KnowledgeArticle.ArticleType;
import com.nexus360.knowledge.KnowledgeArticle.Status;
import com.nexus360.knowledge.KnowledgeDtos.ArticleDetail;
import com.nexus360.knowledge.KnowledgeDtos.ArticleRequest;
import com.nexus360.knowledge.KnowledgeDtos.ArticleSummary;
import com.nexus360.knowledge.KnowledgeDtos.KnowledgeStats;
import com.nexus360.knowledge.KnowledgeDtos.VersionDto;
import com.nexus360.runbook.RunbookRepository;
import com.nexus360.security.Actor;
import com.nexus360.user.Role;
import com.nexus360.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knowledge base: articles with a review workflow (DRAFT → IN_REVIEW → PUBLISHED → ARCHIVED), content versions with
 * restore, one 1–5 rating per user and article, and relevance search/recommendation.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeArticleRepository articles;
    private final KbArticleVersionRepository versions;
    private final KbArticleRatingRepository ratings;
    private final KnowledgeSearch search;
    private final BusinessServiceRepository services;
    private final UserRepository users;
    private final IncidentRepository incidents;
    private final RunbookRepository runbooks;
    private final AuditService audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<ArticleSummary> list(String q, ArticleType type, List<ArticleType> types, Category category, Long serviceId,
                                             Status status, Integer page, Integer size, Actor actor) {
        return list(q, type, types, category, serviceId, status, null, page, size, actor);
    }

    /** {@code sort}: popular (views), recent (publication) or rating (average stars); relevance when {@code q} is given. */
    @Transactional(readOnly = true)
    public PageResponse<ArticleSummary> list(String q, ArticleType type, List<ArticleType> types, Category category, Long serviceId,
                                             Status status, String sort, Integer page, Integer size, Actor actor) {
        Status effectiveStatus = canSeeDrafts(actor) ? status : Status.PUBLISHED;
        String order = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        var pageable = PageResponse.pageable(page, size, order.isEmpty() ? Sort.by(Sort.Direction.DESC, "updatedAt") : Sort.unsorted());
        if (q != null && !q.isBlank()) {
            KnowledgeSearch.Result result = search.search(q.trim(), type, types, category, serviceId, effectiveStatus,
                    (int) pageable.getOffset(), pageable.getPageSize());
            Map<Long, KnowledgeArticle> byId = articles.findByIdIn(result.hits().stream().map(KnowledgeSearch.Hit::id).toList())
                    .stream().collect(Collectors.toMap(KnowledgeArticle::getId, Function.identity()));
            List<ArticleSummary> content = result.hits().stream().filter(h -> byId.containsKey(h.id()))
                    .map(h -> ArticleSummary.of(byId.get(h.id()), h.score())).toList();
            int totalPages = (int) Math.ceil(result.total() / (double) pageable.getPageSize());
            return new PageResponse<>(content, pageable.getPageNumber(), pageable.getPageSize(), result.total(), totalPages);
        }
        Specification<KnowledgeArticle> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (type != null) {
                p.add(cb.equal(root.get("articleType"), type));
            } else if (types != null && !types.isEmpty()) {
                p.add(root.get("articleType").in(types));
            }
            if (category != null) {
                p.add(cb.equal(root.get("category"), category));
            }
            if (serviceId != null) {
                p.add(cb.equal(root.get("service").get("id"), serviceId));
            }
            if (effectiveStatus != null) {
                p.add(cb.equal(root.get("status"), effectiveStatus));
            }
            boolean counting = query.getResultType() == Long.class || query.getResultType() == long.class;
            if (!counting) {
                switch (order) {
                    case "popular" -> query.orderBy(cb.desc(root.get("viewCount")), cb.desc(root.get("updatedAt")));
                    case "recent" -> query.orderBy(cb.desc(cb.coalesce(root.get("publishedAt"), root.get("updatedAt"))));
                    case "rating" -> query.orderBy(cb.desc(cb.coalesce(root.get("ratingAverage"), BigDecimal.ZERO)),
                            cb.desc(root.get("ratingCount")), cb.desc(root.get("viewCount")));
                    default -> { }
                }
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<KnowledgeArticle> result = articles.findAll(spec, pageable);
        return PageResponse.of(result, a -> ArticleSummary.of(a, null));
    }

    @Transactional
    public ArticleDetail get(Long id, Actor actor) {
        KnowledgeArticle a = load(id, actor);
        articles.incrementViews(id);
        return detail(a, a.getViewCount() + 1, actor);
    }

    @Transactional
    public ArticleDetail create(ArticleRequest request, Actor actor) {
        requireAuthor(actor);
        requireMaySetStatus(actor, request.status());
        KnowledgeArticle a = new KnowledgeArticle();
        a.setNumber("KB-" + articles.nextNumber());
        a.setAuthor(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        apply(a, request);
        a.setStatus(request.status());
        if (request.status() == Status.PUBLISHED) {
            a.setPublishedAt(clock.instant());
        }
        a.setCurrentVersion(1);
        articles.save(a);
        saveVersion(a, 1, request.changeNote() == null || request.changeNote().isBlank() ? "Initial version" : request.changeNote(), actor);
        audit.record("KB_ARTICLE_CREATED", "KNOWLEDGE_ARTICLE", a.getNumber(), Map.of("title", a.getTitle(), "status", a.getStatus()));
        return detail(a, a.getViewCount(), actor);
    }

    /** Content edits create a new version. Published and archived articles can only be edited by knowledge publishers. */
    @Transactional
    public ArticleDetail update(Long id, ArticleRequest request, Actor actor) {
        requireAuthor(actor);
        KnowledgeArticle a = articles.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Article", id));
        if ((a.getStatus() == Status.PUBLISHED || a.getStatus() == Status.ARCHIVED) && !isPublisher(actor)) {
            throw ApiException.forbidden("Only knowledge publishers can edit published or archived articles");
        }
        requireMaySetStatus(actor, request.status());
        Status before = a.getStatus();
        String fingerprint = fingerprint(a);
        apply(a, request);
        changeStatus(a, request.status());
        if (!fingerprint.equals(fingerprint(a))) {
            int next = a.getCurrentVersion() + 1;
            a.setCurrentVersion(next);
            saveVersion(a, next, request.changeNote(), actor);
        }
        audit.record("KB_ARTICLE_UPDATED", "KNOWLEDGE_ARTICLE", a.getNumber(), Map.of("from", before, "to", a.getStatus(),
                "version", a.getCurrentVersion()));
        return detail(a, a.getViewCount(), actor);
    }

    @Transactional
    public ArticleDetail submit(Long id, Actor actor) {
        requireAuthor(actor);
        KnowledgeArticle a = articles.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Article", id));
        if (a.getStatus() != Status.DRAFT) {
            throw ApiException.conflict(a.getNumber() + " is " + a.getStatus() + "; only drafts can be submitted for review");
        }
        a.setStatus(Status.IN_REVIEW);
        audit.record("KB_ARTICLE_SUBMITTED", "KNOWLEDGE_ARTICLE", a.getNumber(), Map.of("title", a.getTitle()));
        return detail(a, a.getViewCount(), actor);
    }

    @Transactional
    public ArticleDetail publish(Long id, Actor actor) {
        requirePublisher(actor);
        KnowledgeArticle a = articles.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Article", id));
        if (a.getStatus() == Status.PUBLISHED) {
            throw ApiException.conflict(a.getNumber() + " is already published");
        }
        Status before = a.getStatus();
        changeStatus(a, Status.PUBLISHED);
        audit.record("KB_ARTICLE_PUBLISHED", "KNOWLEDGE_ARTICLE", a.getNumber(), Map.of("from", before, "version", a.getCurrentVersion()));
        return detail(a, a.getViewCount(), actor);
    }

    @Transactional
    public ArticleDetail archive(Long id, Actor actor) {
        requirePublisher(actor);
        KnowledgeArticle a = articles.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Article", id));
        if (a.getStatus() == Status.ARCHIVED) {
            throw ApiException.conflict(a.getNumber() + " is already archived");
        }
        Status before = a.getStatus();
        changeStatus(a, Status.ARCHIVED);
        audit.record("KB_ARTICLE_ARCHIVED", "KNOWLEDGE_ARTICLE", a.getNumber(), Map.of("from", before));
        return detail(a, a.getViewCount(), actor);
    }

    /** Permanently removes an article with its versions and ratings; incidents citing it keep their resolution without the link. */
    @Transactional
    public void delete(Long id, Actor actor) {
        if (!actor.isSystem() && !actor.role().isAdmin()) {
            throw ApiException.forbidden("Only administrators can delete knowledge articles");
        }
        KnowledgeArticle a = articles.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Article", id));
        int unlinked = incidents.clearResolutionArticle(id);
        audit.record("KB_ARTICLE_DELETED", "KNOWLEDGE_ARTICLE", a.getNumber(), Map.of("title", a.getTitle(), "status", a.getStatus(),
                "incidentsUnlinked", unlinked));
        articles.delete(a);
    }

    @Transactional(readOnly = true)
    public List<VersionDto> versions(Long id, Actor actor) {
        load(id, actor);
        return versions.findByArticleIdOrderByVersionNumberDesc(id).stream()
                .map(v -> new VersionDto(v.getVersionNumber(), v.getTitle(), v.getSummary(), v.getEditorName(), v.getChangeNote(), v.getCreatedAt()))
                .toList();
    }

    /** Restoring copies an old version's content into a new version, so the history stays append-only. */
    @Transactional
    public ArticleDetail restore(Long id, int versionNumber, Actor actor) {
        requirePublisher(actor);
        KnowledgeArticle a = articles.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Article", id));
        KbArticleVersion v = versions.findByArticleIdAndVersionNumber(id, versionNumber)
                .orElseThrow(() -> ApiException.notFound("Version", versionNumber));
        a.setTitle(v.getTitle());
        a.setSummary(v.getSummary());
        a.setContent(v.getContent());
        a.setArticleType(v.getArticleType());
        a.setCategory(v.getCategory());
        a.setService(v.getServiceId() == null ? null : services.findById(v.getServiceId()).orElse(null));
        a.setTags(v.getTags());
        a.setReadMinutes(readMinutes(v.getContent()));
        int next = a.getCurrentVersion() + 1;
        a.setCurrentVersion(next);
        saveVersion(a, next, "Restored version " + versionNumber, actor);
        audit.record("KB_ARTICLE_VERSION_RESTORED", "KNOWLEDGE_ARTICLE", a.getNumber(), Map.of("restored", versionNumber, "newVersion", next));
        return detail(a, a.getViewCount(), actor);
    }

    /** One rating per user and article (re-rating replaces it); the article's aggregates are recomputed from the rows. */
    @Transactional
    public ArticleDetail feedback(Long id, Integer rating, Boolean helpful, String comment, Actor actor) {
        if (actor.isSystem()) {
            throw ApiException.forbidden("Ratings need a signed-in user");
        }
        int value = rating != null ? rating : helpful == null ? 0 : (helpful ? 5 : 2);
        if (value < 1 || value > 5) {
            throw ApiException.badRequest("rating must be between 1 and 5");
        }
        KnowledgeArticle a = articles.findWithDetailsById(id).filter(x -> x.getStatus() == Status.PUBLISHED)
                .orElseThrow(() -> ApiException.notFound("Article", id));
        KbArticleRating r = ratings.findByArticleIdAndUserId(id, actor.id()).orElseGet(() -> {
            KbArticleRating fresh = new KbArticleRating();
            fresh.setArticleId(id);
            fresh.setUserId(actor.id());
            return fresh;
        });
        r.setRating(value);
        r.setComment(comment == null || comment.isBlank() ? null : comment.trim());
        ratings.saveAndFlush(r);
        refreshRatingStats(id);
        KnowledgeArticle reloaded = articles.findWithDetailsById(id).orElse(a);
        return detail(reloaded, reloaded.getViewCount(), actor);
    }

    @Transactional
    public void refreshRatingStats(Long id) {
        Object[] row = ratings.aggregate(id).get(0);
        int count = row[0] == null ? 0 : ((Number) row[0]).intValue();
        BigDecimal average = count == 0 || row[1] == null ? null : BigDecimal.valueOf(((Number) row[1]).doubleValue()).setScale(2, RoundingMode.HALF_UP);
        int helpfulRatings = row[2] == null ? 0 : ((Number) row[2]).intValue();
        articles.updateRatingStats(id, count, average, helpfulRatings);
    }

    @Transactional(readOnly = true)
    public KnowledgeStats stats() {
        var since = clock.instant().minus(Duration.ofDays(30));
        long resolved = articles.countResolvedSince(since);
        long withArticle = articles.countResolvedWithArticleSince(since);
        Map<ArticleType, Long> byType = new EnumMap<>(ArticleType.class);
        for (ArticleType t : ArticleType.values()) {
            byType.put(t, 0L);
        }
        for (Object[] row : articles.countByTypeAndStatus(Status.PUBLISHED)) {
            byType.put((ArticleType) row[0], (Long) row[1]);
        }
        return new KnowledgeStats(articles.countByStatus(Status.PUBLISHED), articles.countByStatus(Status.DRAFT), articles.totalViews(),
                resolved == 0 ? null : Math.round(withArticle * 1000.0 / resolved) / 10.0, articles.countByStatus(Status.IN_REVIEW),
                byType, runbooks.countByActiveTrue());
    }

    /**
     * Articles relevant to free text, used by SA AI and the incident page. Full-text search retrieves candidates;
     * they are then re-ranked by how many of the article's key terms (title, summary, tags) appear in the text, with a
     * boost for the same service, because an OR-query over long incident text matches too loosely on its own.
     */
    @Transactional(readOnly = true)
    public List<ArticleSummary> recommend(String text, Long serviceId, int limit) {
        List<KnowledgeSearch.Hit> candidates = search.recommend(text, serviceId, Math.max(limit, 15));
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, KnowledgeArticle> byId = articles.findByIdIn(candidates.stream().map(KnowledgeSearch.Hit::id).toList()).stream()
                .collect(Collectors.toMap(KnowledgeArticle::getId, Function.identity()));
        double maxRank = candidates.get(0).score() <= 0 ? 1 : candidates.get(0).score();
        Set<String> incidentTerms = com.nexus360.incident.TextSimilarity.tokens(text);
        record Scored(KnowledgeArticle article, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (KnowledgeSearch.Hit hit : candidates) {
            KnowledgeArticle a = byId.get(hit.id());
            if (a == null) {
                continue;
            }
            Set<String> articleTerms = com.nexus360.incident.TextSimilarity.tokens(
                    a.getTitle() + " " + a.getSummary() + " " + (a.getTags() == null ? "" : a.getTags().replace(',', ' ')));
            Set<String> shared = new HashSet<>(articleTerms);
            shared.retainAll(incidentTerms);
            double overlap = articleTerms.isEmpty() ? 0 : (double) shared.size() / Math.min(articleTerms.size(), Math.max(1, incidentTerms.size()));
            boolean sameService = serviceId != null && a.getService() != null && serviceId.equals(a.getService().getId());
            scored.add(new Scored(a, 0.6 * overlap + (sameService ? 0.25 : 0) + 0.15 * Math.min(1, hit.score() / maxRank)));
        }
        scored.sort((x, y) -> Double.compare(y.score(), x.score()));
        double top = scored.isEmpty() ? 0 : scored.get(0).score();
        return scored.stream()
                .filter(s -> s.score() >= 0.18 && s.score() >= top * 0.45)
                .limit(limit)
                .map(s -> ArticleSummary.of(s.article(), s.score()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private KnowledgeArticle load(Long id, Actor actor) {
        KnowledgeArticle a = articles.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Article", id));
        if (a.getStatus() != Status.PUBLISHED && !canSeeDrafts(actor)) {
            throw ApiException.notFound("Article", id);
        }
        return a;
    }

    private void apply(KnowledgeArticle a, ArticleRequest r) {
        BusinessService service = r.serviceId() == null ? null
                : services.findById(r.serviceId()).orElseThrow(() -> ApiException.badRequest("Service does not exist"));
        a.setTitle(r.title().trim());
        a.setSummary(r.summary().trim());
        a.setContent(r.content());
        a.setArticleType(r.articleType());
        a.setCategory(r.category());
        a.setService(service);
        a.setTags(r.tags() == null ? null : r.tags().stream().map(String::trim).filter(t -> !t.isEmpty()).distinct()
                .collect(Collectors.joining(",")));
        a.setReadMinutes(readMinutes(r.content()));
    }

    private void changeStatus(KnowledgeArticle a, Status status) {
        if (status == Status.PUBLISHED && a.getPublishedAt() == null) {
            a.setPublishedAt(clock.instant());
        }
        a.setStatus(status);
    }

    private void saveVersion(KnowledgeArticle a, int number, String note, Actor actor) {
        KbArticleVersion v = new KbArticleVersion();
        v.setArticleId(a.getId());
        v.setVersionNumber(number);
        v.setTitle(a.getTitle());
        v.setSummary(a.getSummary());
        v.setContent(a.getContent());
        v.setArticleType(a.getArticleType());
        v.setCategory(a.getCategory());
        v.setServiceId(a.getService() == null ? null : a.getService().getId());
        v.setTags(a.getTags());
        v.setEditorId(actor.id());
        v.setEditorName(actor.name());
        v.setChangeNote(note == null || note.isBlank() ? null : note.trim());
        versions.save(v);
    }

    private static String fingerprint(KnowledgeArticle a) {
        return String.join("\u0001", Objects.toString(a.getTitle(), ""), Objects.toString(a.getSummary(), ""),
                Objects.toString(a.getContent(), ""), String.valueOf(a.getArticleType()), String.valueOf(a.getCategory()),
                a.getService() == null ? "" : String.valueOf(a.getService().getId()), Objects.toString(a.getTags(), ""));
    }

    private static int readMinutes(String content) {
        return Math.max(1, content.split("\\s+").length / 200);
    }

    private ArticleDetail detail(KnowledgeArticle a, int views, Actor actor) {
        ArticleSummary s = ArticleSummary.of(a, null);
        Integer mine = actor == null || actor.isSystem() || a.getId() == null ? null
                : ratings.findByArticleIdAndUserId(a.getId(), actor.id()).map(KbArticleRating::getRating).orElse(null);
        return new ArticleDetail(s.id(), s.number(), s.title(), s.summary(), s.articleType(), s.category(), s.serviceId(),
                s.serviceName(), s.tags(), s.status(), s.authorName(), views, s.helpfulCount(), s.notHelpfulCount(), s.readMinutes(),
                s.publishedAt(), s.updatedAt(), null, s.demo(), s.averageRating(), s.ratingCount(), s.currentVersion(), a.getContent(),
                a.getId() == null ? 0 : incidents.countByResolutionArticle(a.getId()), mine);
    }

    private static boolean canSeeDrafts(Actor actor) {
        return actor.isSystem() || actor.isStaff() || actor.role() == Role.AUDITOR;
    }

    private static boolean isPublisher(Actor actor) {
        return actor.isSystem() || actor.isAdminOrManager();
    }

    private static void requireAuthor(Actor actor) {
        if (!actor.isSystem() && !actor.isStaff()) {
            throw ApiException.forbidden("Only support staff can author knowledge articles");
        }
    }

    private static void requirePublisher(Actor actor) {
        if (!isPublisher(actor)) {
            throw ApiException.forbidden("Only knowledge publishers (managers and administrators) can do this");
        }
    }

    private static void requireMaySetStatus(Actor actor, Status status) {
        if ((status == Status.PUBLISHED || status == Status.ARCHIVED) && !isPublisher(actor)) {
            throw ApiException.forbidden("Only knowledge publishers can publish or archive articles; submit the draft for review instead");
        }
    }
}
