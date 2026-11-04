package com.nexus360.incident;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentCategorySettingRepository extends JpaRepository<IncidentCategorySetting, Category> {

    @EntityGraph(attributePaths = "defaultDepartment")
    List<IncidentCategorySetting> findAllByOrderBySortOrderAsc();
}
