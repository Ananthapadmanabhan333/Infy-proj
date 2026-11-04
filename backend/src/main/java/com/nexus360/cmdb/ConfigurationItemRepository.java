package com.nexus360.cmdb;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConfigurationItemRepository extends JpaRepository<ConfigurationItem, Long>, JpaSpecificationExecutor<ConfigurationItem> {

    @Override
    @EntityGraph(attributePaths = {"owner", "team", "service"})
    Page<ConfigurationItem> findAll(Specification<ConfigurationItem> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "team", "service", "createdBy"})
    Optional<ConfigurationItem> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"owner", "team", "service"})
    @Query("select c from ConfigurationItem c where c.id in :ids")
    List<ConfigurationItem> findAllWithDetails(@Param("ids") Collection<Long> ids);

    @Query("select c from ConfigurationItem c where lower(c.name) = lower(:name)")
    Optional<ConfigurationItem> findByNameIgnoreCase(@Param("name") String name);

    Optional<ConfigurationItem> findByServiceId(Long serviceId);

    long countByDemoTrue();
}
