package com.nexus360.statuspage;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StatusComponentRepository extends JpaRepository<StatusComponent, Long> {

    @EntityGraph(attributePaths = {"service"})
    @Query("select c from StatusComponent c order by c.sortOrder, c.displayName")
    List<StatusComponent> findAllOrdered();

    boolean existsByServiceId(Long serviceId);

    long countByDemoTrue();
}
