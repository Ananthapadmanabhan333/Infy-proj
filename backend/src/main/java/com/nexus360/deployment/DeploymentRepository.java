package com.nexus360.deployment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeploymentRepository extends JpaRepository<Deployment, Long>, JpaSpecificationExecutor<Deployment> {

    @Override
    @EntityGraph(attributePaths = {"release", "change", "service", "webhookSource", "rollbackOf"})
    Page<Deployment> findAll(Specification<Deployment> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"release", "change", "service", "webhookSource", "rollbackOf"})
    Optional<Deployment> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"release", "change", "service", "webhookSource", "rollbackOf"})
    Optional<Deployment> findByWebhookSourceIdAndExternalId(Long sourceId, String externalId);

    @EntityGraph(attributePaths = {"release", "change", "service", "webhookSource", "rollbackOf"})
    List<Deployment> findByRollbackOfIdOrderByStartedAtDesc(Long id);

    @EntityGraph(attributePaths = {"release", "change", "service", "webhookSource", "rollbackOf"})
    List<Deployment> findByReleaseIdOrderByStartedAtDesc(Long releaseId);

    @EntityGraph(attributePaths = {"release", "change", "service", "webhookSource", "rollbackOf"})
    @Query("select d from Deployment d where d.startedAt >= :from and d.startedAt < :to order by d.startedAt asc")
    List<Deployment> findStartedBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** The last successful deployment of a service to an environment before {@code before} (rollback target). */
    @Query("select d from Deployment d where d.service.id = :serviceId and d.environment = :env and d.status = com.nexus360.deployment.Deployment.Status.SUCCEEDED "
            + "and d.startedAt < :before order by d.startedAt desc")
    List<Deployment> findPreviousSuccessful(@Param("serviceId") Long serviceId, @Param("env") Deployment.Environment env,
                                            @Param("before") Instant before, Pageable pageable);

    @Query(value = "select nextval('deployment_number_seq')", nativeQuery = true)
    long nextNumber();

    long countByDemoTrue();
}
