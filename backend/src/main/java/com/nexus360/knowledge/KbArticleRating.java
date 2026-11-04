package com.nexus360.knowledge;

import com.nexus360.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One user's 1–5 star rating of an article (one per user per article; re-rating updates it). */
@Getter
@Setter
@Entity
@Table(name = "kb_article_ratings")
public class KbArticleRating extends BaseEntity {

    @Column(name = "article_id", nullable = false)
    private Long articleId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int rating;

    @Column(length = 1000)
    private String comment;
}
