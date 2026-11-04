package com.nexus360.deployment;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ReleaseRepository extends JpaRepository<Release, Long>, JpaSpecificationExecutor<Release> {

    @Override
    @EntityGraph(attributePaths = {"service", "change", "owner"})
    Page<Release> findAll(Specification<Release> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"service", "change", "owner"})
    Optional<Release> findWithDetailsById(Long id);

    @Query(value = "select nextval('release_number_seq')", nativeQuery = true)
    long nextNumber();
}
