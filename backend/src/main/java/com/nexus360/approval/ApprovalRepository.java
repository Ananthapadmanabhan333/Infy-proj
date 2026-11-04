package com.nexus360.approval;

import com.nexus360.user.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRepository extends JpaRepository<Approval, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Approval> {

    @Override
    @EntityGraph(attributePaths = {"requestedBy", "decidedBy"})
    Page<Approval> findAll(org.springframework.data.jpa.domain.Specification<Approval> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"requestedBy", "decidedBy"})
    Page<Approval> findByStatusAndApproverRoleInOrderByCreatedAtDesc(Approval.Status status, Collection<Role> roles, Pageable pageable);

    @EntityGraph(attributePaths = {"requestedBy", "decidedBy"})
    Page<Approval> findByApproverRoleInOrderByCreatedAtDesc(Collection<Role> roles, Pageable pageable);

    @EntityGraph(attributePaths = {"requestedBy", "decidedBy"})
    Optional<Approval> findFirstByTypeAndReferenceIdOrderByCreatedAtDesc(Approval.Type type, Long referenceId);

    @EntityGraph(attributePaths = {"requestedBy", "decidedBy"})
    List<Approval> findByTypeAndReferenceIdIn(Approval.Type type, Collection<Long> referenceIds);

    long countByStatusAndApproverRoleIn(Approval.Status status, Collection<Role> roles);

    /** Pending approvals routed to the roles that the user did not request themselves (segregation of duties), oldest first. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"requestedBy", "decidedBy"})
    @org.springframework.data.jpa.repository.Query("select a from Approval a where a.status = com.nexus360.approval.Approval.Status.PENDING "
            + "and a.approverRole in :roles and (a.requestedBy is null or a.requestedBy.id <> :userId) order by a.createdAt asc")
    List<Approval> findDecidableBy(@org.springframework.data.repository.query.Param("roles") Collection<Role> roles,
                                   @org.springframework.data.repository.query.Param("userId") Long userId, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("select count(a) from Approval a where a.status = com.nexus360.approval.Approval.Status.PENDING "
            + "and a.approverRole in :roles and (a.requestedBy is null or a.requestedBy.id <> :userId)")
    long countDecidableBy(@org.springframework.data.repository.query.Param("roles") Collection<Role> roles,
                          @org.springframework.data.repository.query.Param("userId") Long userId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"requestedBy"})
    List<Approval> findByStatusAndRequestedByIdOrderByCreatedAtDesc(Approval.Status status, Long requestedById, Pageable pageable);

    long countByStatusAndRequestedById(Approval.Status status, Long requestedById);

    @EntityGraph(attributePaths = {"requestedBy", "decidedBy"})
    List<Approval> findByReferenceNumberAndTypeInOrderByCreatedAtAsc(String referenceNumber, Collection<Approval.Type> types);

    @EntityGraph(attributePaths = {"requestedBy", "decidedBy"})
    List<Approval> findByTypeAndIdIn(Approval.Type type, Collection<Long> ids);
}
