package com.nexus360.servicedesk;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceCategoryRepository extends JpaRepository<ServiceCategory, Long> {

    List<ServiceCategory> findAllByOrderBySortOrderAscNameAsc();

    boolean existsByCodeIgnoreCase(String code);

    java.util.Optional<ServiceCategory> findByCodeIgnoreCase(String code);
}
