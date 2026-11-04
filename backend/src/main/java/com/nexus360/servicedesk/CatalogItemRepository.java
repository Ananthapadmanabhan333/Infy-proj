package com.nexus360.servicedesk;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogItemRepository extends JpaRepository<CatalogItem, Long> {

    @EntityGraph(attributePaths = {"category", "fulfillmentDepartment", "formDefinition"})
    List<CatalogItem> findAllByOrderByNameAsc();

    @EntityGraph(attributePaths = {"category", "fulfillmentDepartment", "formDefinition"})
    Optional<CatalogItem> findWithDetailsById(Long id);

    @Query("select i.category.id, count(i) from CatalogItem i where i.active = true group by i.category.id")
    List<Object[]> countActiveByCategory();

    /** Items using a form definition. */
    List<CatalogItem> findByFormDefinitionIdOrderByNameAsc(Long formDefinitionId);

    @Query("select i.formDefinition.id, count(i) from CatalogItem i where i.formDefinition.id in :ids group by i.formDefinition.id")
    List<Object[]> countByForms(@Param("ids") Collection<Long> ids);

    /** Items created by Release 2 code paths (e.g. the demo seeder) that have no form definition yet. */
    List<CatalogItem> findByFormDefinitionIsNull();
}
