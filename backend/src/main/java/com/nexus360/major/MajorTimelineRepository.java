package com.nexus360.major;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorTimelineRepository extends JpaRepository<MajorTimelineEntry, Long> {

    List<MajorTimelineEntry> findByMajorIncidentIdOrderByOccurredAtAscIdAsc(Long majorIncidentId);
}
