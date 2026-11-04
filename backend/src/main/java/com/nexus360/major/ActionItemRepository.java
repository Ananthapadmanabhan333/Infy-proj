package com.nexus360.major;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionItemRepository extends JpaRepository<ActionItem, Long> {

    @EntityGraph(attributePaths = "owner")
    List<ActionItem> findByMajorIncidentIdOrderByCreatedAtAscIdAsc(Long majorIncidentId);

    @EntityGraph(attributePaths = "owner")
    List<ActionItem> findByPirIdOrderByCreatedAtAscIdAsc(Long pirId);

    @EntityGraph(attributePaths = "owner")
    Optional<ActionItem> findWithOwnerById(Long id);

    @EntityGraph(attributePaths = "owner")
    List<ActionItem> findByOwnerIdAndStatusInOrderByDueDateAscIdAsc(Long ownerId, Collection<ActionItem.Status> statuses, Pageable page);

    long countByOwnerIdAndStatusIn(Long ownerId, Collection<ActionItem.Status> statuses);

    long countByMajorIncidentIdAndStatusIn(Long majorIncidentId, Collection<ActionItem.Status> statuses);

    long countByPirIdAndStatusIn(Long pirId, Collection<ActionItem.Status> statuses);
}
