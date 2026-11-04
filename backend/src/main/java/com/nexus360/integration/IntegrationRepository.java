package com.nexus360.integration;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationRepository extends JpaRepository<Integration, Long> {

    @EntityGraph(attributePaths = "defaultService")
    List<Integration> findAllByOrderByNameAsc();

    @EntityGraph(attributePaths = "defaultService")
    Optional<Integration> findWithServiceById(Long id);

    List<Integration> findByEnabledTrueAndTypeIn(List<Integration.Type> types);

    boolean existsByNameIgnoreCase(String name);
}
