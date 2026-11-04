package com.nexus360.statuspage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceWindowRepository extends JpaRepository<MaintenanceWindow, Long> {

    @EntityGraph(attributePaths = {"components", "components.service"})
    @Query("select distinct w from MaintenanceWindow w where w.endsAt >= :since order by w.startsAt")
    List<MaintenanceWindow> findEndingAfter(@Param("since") Instant since);

    @EntityGraph(attributePaths = {"components", "components.service"})
    Optional<MaintenanceWindow> findWithComponentsById(Long id);

    /** Windows in progress at {@code now}: not cancelled or completed, not yet ended, and started (or started early by hand). */
    @EntityGraph(attributePaths = {"components", "components.service"})
    @Query("select distinct w from MaintenanceWindow w where w.status in (com.nexus360.statuspage.MaintenanceWindow.Status.SCHEDULED, "
            + "com.nexus360.statuspage.MaintenanceWindow.Status.IN_PROGRESS) and w.endsAt > :now "
            + "and (w.startsAt <= :now or w.status = com.nexus360.statuspage.MaintenanceWindow.Status.IN_PROGRESS)")
    List<MaintenanceWindow> findActive(@Param("now") Instant now);

    long countByDemoTrue();
}
