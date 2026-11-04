package com.nexus360.alert;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertSourceRepository extends JpaRepository<AlertSource, Long> {

    @EntityGraph(attributePaths = {"defaultService", "defaultCi"})
    Optional<AlertSource> findWithDefaultsById(Long id);

    @EntityGraph(attributePaths = {"defaultService", "defaultCi"})
    List<AlertSource> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    Optional<AlertSource> findByNameIgnoreCase(String name);
}
