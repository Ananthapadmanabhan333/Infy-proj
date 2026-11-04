package com.nexus360.servicedesk;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceRequestHistoryRepository extends JpaRepository<ServiceRequestHistory, Long> {

    List<ServiceRequestHistory> findByRequestIdOrderByCreatedAtAscIdAsc(Long requestId);
}
