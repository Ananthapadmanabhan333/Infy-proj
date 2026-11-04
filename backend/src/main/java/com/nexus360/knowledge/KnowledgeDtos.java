package com.nexus360.knowledge;

import com.nexus360.incident.Category;
import com.nexus360.knowledge.KnowledgeArticle.ArticleType;
import com.nexus360.knowledge.KnowledgeArticle.Status;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class KnowledgeDtos {

    private KnowledgeDtos() {}

    /** {@code averageRating} is null until the article has been rated; {@code helpfulCount} counts ratings of 4–5 stars. */
    public record ArticleSummary(Long id, String number, String title, String summary, ArticleType articleType, Category category,
                                 Long serviceId, String serviceName, List<String> tags, Status status, String authorName, int viewCount,
                                 int helpfulCount, int notHelpfulCount, int readMinutes, Instant publishedAt, Instant updatedAt,
                                 Double score, boolean demo, Double averageRating, int ratingCount, int currentVersion) {

        public static ArticleSummary of(KnowledgeArticle a, Double score) {
            return new ArticleSummary(a.getId(), a.getNumber(), a.getTitle(), a.getSummary(), a.getArticleType(), a.getCategory(),
                    a.getService() == null ? null : a.getService().getId(), a.getService() == null ? null : a.getService().getName(),
                    a.tagList(), a.getStatus(), a.getAuthor() == null ? null : a.getAuthor().getFullName(), a.getViewCount(),
                    a.getHelpfulCount(), a.getNotHelpfulCount(), a.getReadMinutes(), a.getPublishedAt(), a.getUpdatedAt(),
                    score == null ? null : Math.round(score * 1000) / 1000.0, a.isDemo(),
                    a.getRatingCount() == 0 || a.getRatingAverage() == null ? null : a.getRatingAverage().doubleValue(), a.getRatingCount(),
                    a.getCurrentVersion());
        }
    }

    /** {@code myRating}: the caller's own rating (1–5) or null. */
    public record ArticleDetail(Long id, String number, String title, String summary, ArticleType articleType, Category category,
                                Long serviceId, String serviceName, List<String> tags, Status status, String authorName, int viewCount,
                                int helpfulCount, int notHelpfulCount, int readMinutes, Instant publishedAt, Instant updatedAt,
                                Double score, boolean demo, Double averageRating, int ratingCount, int currentVersion, String content,
                                long linkedIncidentCount, Integer myRating) {}

    public record ArticleRequest(
            @NotBlank @Size(min = 5, max = 200) String title,
            @NotBlank @Size(min = 10, max = 500) String summary,
            @NotBlank @Size(min = 20, max = 50_000) String content,
            @NotNull ArticleType articleType,
            @NotNull Category category,
            Long serviceId,
            @Size(max = 15) List<@Size(max = 30) String> tags,
            @NotNull Status status,
            @Size(max = 500) String changeNote) {}

    /** {@code rating} 1–5 (one per user, updatable). The Release 1 form {@code {helpful}} is still accepted (true = 5, false = 2). */
    public record FeedbackRequest(@Min(1) @Max(5) Integer rating, Boolean helpful, @Size(max = 1000) String comment) {}

    public record VersionDto(int version, String title, String summary, String editorName, String changeNote, Instant createdAt) {}

    /** {@code countsByType}: published articles per type; {@code runbookCount}: active runbooks. */
    public record KnowledgeStats(long published, long drafts, long totalViews, Double resolutionRate, long inReview,
                                 Map<ArticleType, Long> countsByType, long runbookCount) {}
}
