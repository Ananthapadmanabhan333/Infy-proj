package com.nexus360.deployment;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    List<WebhookDelivery> findBySourceIdOrderByReceivedAtDescIdDesc(Long sourceId, Pageable pageable);
}
