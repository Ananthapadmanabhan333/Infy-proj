package com.nexus360.cmdb;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessServiceRepository extends JpaRepository<BusinessService, Long> {

    @EntityGraph(attributePaths = {"department", "owner", "defaultTeam"})
    List<BusinessService> findAllByOrderByTierAscNameAsc();

    @EntityGraph(attributePaths = {"department", "owner", "defaultTeam"})
    Optional<BusinessService> findWithDepartmentById(Long id);

    Optional<BusinessService> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);
}
