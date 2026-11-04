package com.nexus360.form;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FormDefinitionVersionRepository extends JpaRepository<FormDefinitionVersion, Long> {

    Optional<FormDefinitionVersion> findByFormIdAndVersionNumber(Long formId, int versionNumber);

    @EntityGraph(attributePaths = {"createdBy"})
    List<FormDefinitionVersion> findByFormIdOrderByVersionNumberDesc(Long formId);

    /** Current versions of several forms. */
    @Query("select v from FormDefinitionVersion v where v.form.id in :ids and v.versionNumber = v.form.currentVersion")
    List<FormDefinitionVersion> findCurrent(@Param("ids") Collection<Long> ids);
}
