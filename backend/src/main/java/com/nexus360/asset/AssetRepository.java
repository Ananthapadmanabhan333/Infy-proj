package com.nexus360.asset;

import java.util.Collection;
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

public interface AssetRepository extends JpaRepository<Asset, Long>, JpaSpecificationExecutor<Asset> {

    @Override
    @EntityGraph(attributePaths = {"assignedTo", "department", "ci"})
    Page<Asset> findAll(Specification<Asset> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"assignedTo", "department", "ci"})
    List<Asset> findAll(Specification<Asset> spec);

    @EntityGraph(attributePaths = {"assignedTo", "department", "ci", "createdBy"})
    Optional<Asset> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"assignedTo", "department", "ci"})
    Optional<Asset> findByAssetTagIgnoreCase(String assetTag);

    @Query("select a from Asset a where lower(a.serialNumber) = lower(:serial)")
    Optional<Asset> findBySerialIgnoreCase(@Param("serial") String serial);

    @EntityGraph(attributePaths = {"assignedTo", "department", "ci"})
    @Query("select a from Asset a where a.id in :ids")
    List<Asset> findAllWithDetails(@Param("ids") Collection<Long> ids);

    @Query(value = "select nextval('asset_tag_seq')", nativeQuery = true)
    long nextTag();

    long countByDemoTrue();
}
