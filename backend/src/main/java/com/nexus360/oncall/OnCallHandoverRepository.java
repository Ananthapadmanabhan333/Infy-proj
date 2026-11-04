package com.nexus360.oncall;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnCallHandoverRepository extends JpaRepository<OnCallHandover, Long> {

    @EntityGraph(attributePaths = "toUser")
    List<OnCallHandover> findByScheduleIdOrderByCreatedAtDescIdDesc(Long scheduleId, Pageable page);
}
