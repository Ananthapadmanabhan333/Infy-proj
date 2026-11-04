package com.nexus360.major;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StakeholderUpdateRepository extends JpaRepository<StakeholderUpdate, Long> {

    List<StakeholderUpdate> findByMajorIncidentIdOrderByPostedAtDescIdDesc(Long majorIncidentId);

    long countByMajorIncidentId(Long majorIncidentId);
}
