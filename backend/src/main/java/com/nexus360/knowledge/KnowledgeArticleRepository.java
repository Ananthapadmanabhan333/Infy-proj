package com.nexus360.knowledge;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeArticleRepository extends JpaRepository<KnowledgeArticle, Long>, JpaSpecificationExecutor<KnowledgeArticle> {

    @Override
    @EntityGraph(attributePaths = {"service", "author"})
    Page<KnowledgeArticle> findAll(Specification<KnowledgeArticle> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"service", "author"})
    List<KnowledgeArticle> findByIdIn(Collection<Long> ids);

    @EntityGraph(attributePaths = {"service", "author"})
    Optional<KnowledgeArticle> findWithDetailsById(Long id);

    @Query(value = "select nextval('knowledge_number_seq')", nativeQuery = true)
    long nextNumber();

    long countByStatus(KnowledgeArticle.Status status);

    @Query("select coalesce(sum(a.viewCount), 0) from KnowledgeArticle a")
    long totalViews();

    @Modifying
    @Query("update KnowledgeArticle a set a.viewCount = a.viewCount + 1 where a.id = :id")
    void incrementViews(@Param("id") Long id);

    /** Derived counters: helpful = legacy votes + ratings of 4–5, not helpful = legacy votes + ratings of 1–3. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update KnowledgeArticle a set a.ratingCount = :count, a.ratingAverage = :average, "
            + "a.helpfulCount = a.legacyHelpfulCount + :helpful, a.notHelpfulCount = a.legacyNotHelpfulCount + (:count - :helpful) where a.id = :id")
    void updateRatingStats(@Param("id") Long id, @Param("count") int count, @Param("average") java.math.BigDecimal average,
                           @Param("helpful") int helpful);

    @Query("select a.articleType, count(a) from KnowledgeArticle a where a.status = :status group by a.articleType")
    List<Object[]> countByTypeAndStatus(@Param("status") KnowledgeArticle.Status status);

    @Query("select count(i) from Incident i where i.resolvedAt >= :from and i.parent is null "
            + "and (i.resolutionCode is null or i.resolutionCode <> com.nexus360.incident.ResolutionCode.CANCELLED)")
    long countResolvedSince(@Param("from") Instant from);

    @Query("select count(i) from Incident i where i.resolvedAt >= :from and i.parent is null and i.resolutionArticle is not null "
            + "and (i.resolutionCode is null or i.resolutionCode <> com.nexus360.incident.ResolutionCode.CANCELLED)")
    long countResolvedWithArticleSince(@Param("from") Instant from);
}
