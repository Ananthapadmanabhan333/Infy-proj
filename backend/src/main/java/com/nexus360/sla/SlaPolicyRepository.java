package com.nexus360.sla;

import com.nexus360.incident.Priority;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, Long> {

    /** Holidays load lazily (batch fetch); fetching the collection here would force in-memory paging for "first". */
    @EntityGraph(attributePaths = "calendar")
    Optional<SlaPolicy> findFirstByPriorityAndActiveTrue(Priority priority);

    @EntityGraph(attributePaths = "calendar")
    List<SlaPolicy> findAllByOrderByPriorityAscNameAsc();
}
