package com.nexus360.knowledge;

import com.nexus360.common.CreatedEntity;
import com.nexus360.incident.Category;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Snapshot of an article's content; every content change appends one. */
@Getter
@Setter
@Entity
@Table(name = "kb_article_versions")
public class KbArticleVersion extends CreatedEntity {

    @Column(name = "article_id", nullable = false)
    private Long articleId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "article_type", nullable = false, length = 20)
    private KnowledgeArticle.ArticleType articleType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category category;

    @Column(name = "service_id")
    private Long serviceId;

    @Column(length = 400)
    private String tags;

    @Column(name = "editor_id")
    private Long editorId;

    @Column(name = "editor_name", nullable = false, length = 120)
    private String editorName;

    @Column(name = "change_note", length = 500)
    private String changeNote;
}
