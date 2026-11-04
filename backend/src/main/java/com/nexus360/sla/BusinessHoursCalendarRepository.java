package com.nexus360.sla;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessHoursCalendarRepository extends JpaRepository<BusinessHoursCalendar, Long> {

    @EntityGraph(attributePaths = "holidays")
    List<BusinessHoursCalendar> findAllByOrderByNameAsc();

    @EntityGraph(attributePaths = "holidays")
    Optional<BusinessHoursCalendar> findWithHolidaysById(Long id);
}
