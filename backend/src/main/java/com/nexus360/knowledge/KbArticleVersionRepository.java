package com.nexus360.knowledge;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbArticleVersionRepository extends JpaRepository<KbArticleVersion, Long> {

    List<KbArticleVersion> findByArticleIdOrderByVersionNumberDesc(Long articleId);

    Optional<KbArticleVersion> findByArticleIdAndVersionNumber(Long articleId, int versionNumber);
}
