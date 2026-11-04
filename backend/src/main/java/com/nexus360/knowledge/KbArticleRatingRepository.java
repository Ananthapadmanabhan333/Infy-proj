package com.nexus360.knowledge;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KbArticleRatingRepository extends JpaRepository<KbArticleRating, Long> {

    Optional<KbArticleRating> findByArticleIdAndUserId(Long articleId, Long userId);

    /** [count, average, ratings of 4 or 5] for one article. */
    @Query("select count(r), avg(r.rating), sum(case when r.rating >= 4 then 1 else 0 end) from KbArticleRating r where r.articleId = :articleId")
    List<Object[]> aggregate(@Param("articleId") Long articleId);
}
