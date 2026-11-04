package com.nexus360.incident;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentCommentRepository extends JpaRepository<IncidentComment, Long> {

    @EntityGraph(attributePaths = "author")
    List<IncidentComment> findByIncidentIdOrderByCreatedAtAscIdAsc(Long incidentId);

    @EntityGraph(attributePaths = "author")
    List<IncidentComment> findByIncidentIdAndInternalFalseOrderByCreatedAtAscIdAsc(Long incidentId);
}
