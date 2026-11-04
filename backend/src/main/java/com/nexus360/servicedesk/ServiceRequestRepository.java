package com.nexus360.servicedesk;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, Long>, JpaSpecificationExecutor<ServiceRequest> {

    @Override
    @EntityGraph(attributePaths = {"catalogItem", "catalogItem.category", "requester", "department", "assignee", "team"})
    Page<ServiceRequest> findAll(Specification<ServiceRequest> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"catalogItem", "catalogItem.category", "catalogItem.fulfillmentDepartment", "requester",
            "department", "assignee", "team"})
    Optional<ServiceRequest> findWithDetailsById(Long id);

    @Query(value = "select nextval('service_request_number_seq')", nativeQuery = true)
    long nextNumber();

    long countByStatusIn(Collection<ServiceRequest.Status> statuses);

    long countByRequesterIdAndStatusIn(Long requesterId, Collection<ServiceRequest.Status> statuses);

    long countByRequesterId(Long requesterId);

    @EntityGraph(attributePaths = {"catalogItem", "catalogItem.category", "requester", "department", "assignee", "team"})
    java.util.List<ServiceRequest> findByAssigneeIdAndStatusInOrderByCreatedAtAsc(Long assigneeId, Collection<ServiceRequest.Status> statuses,
                                                                                  Pageable pageable);

    long countByAssigneeIdAndStatusIn(Long assigneeId, Collection<ServiceRequest.Status> statuses);

    @EntityGraph(attributePaths = {"catalogItem", "catalogItem.category", "requester", "department", "assignee", "team"})
    @Query("select r from ServiceRequest r where r.team.id in :teamIds and r.assignee is null and r.status in :statuses order by r.createdAt asc")
    java.util.List<ServiceRequest> findTeamQueue(@org.springframework.data.repository.query.Param("teamIds") Collection<Long> teamIds,
                                                 @org.springframework.data.repository.query.Param("statuses") Collection<ServiceRequest.Status> statuses,
                                                 Pageable pageable);

    @Query("select count(r) from ServiceRequest r where r.team.id in :teamIds and r.assignee is null and r.status in :statuses")
    long countTeamQueue(@org.springframework.data.repository.query.Param("teamIds") Collection<Long> teamIds,
                        @org.springframework.data.repository.query.Param("statuses") Collection<ServiceRequest.Status> statuses);

    @EntityGraph(attributePaths = {"catalogItem", "catalogItem.category", "requester", "department", "assignee", "team"})
    java.util.List<ServiceRequest> findByRequesterIdAndStatusInOrderByCreatedAtDesc(Long requesterId, Collection<ServiceRequest.Status> statuses,
                                                                                    Pageable pageable);
}
