package com.nexus360.access;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRequestRepository extends JpaRepository<AccountRequest, Long>, JpaSpecificationExecutor<AccountRequest> {

    @Override
    @EntityGraph(attributePaths = "decidedBy")
    Page<AccountRequest> findAll(Specification<AccountRequest> spec, Pageable pageable);

    /** Row lock so two administrators cannot decide the same request concurrently. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AccountRequest r where r.id = :id")
    Optional<AccountRequest> findForUpdate(@Param("id") Long id);

    @Query("select case when count(r) > 0 then true else false end from AccountRequest r "
            + "where lower(r.email) = lower(:email) and r.type = :type and r.status = com.nexus360.access.AccountRequestStatus.PENDING")
    boolean existsPending(@Param("email") String email, @Param("type") AccountRequestType type);

    long countByTypeAndStatus(AccountRequestType type, AccountRequestStatus status);
}
