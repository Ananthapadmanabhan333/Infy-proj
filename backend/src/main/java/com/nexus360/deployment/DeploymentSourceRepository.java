package com.nexus360.deployment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeploymentSourceRepository extends JpaRepository<DeploymentSource, Long> {

    @EntityGraph(attributePaths = {"service"})
    List<DeploymentSource> findAllByOrderByNameAsc();

    @EntityGraph(attributePaths = {"service"})
    Optional<DeploymentSource> findWithServiceById(Long id);

    boolean existsByNameIgnoreCase(String name);
}
