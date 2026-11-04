package com.nexus360.integration;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationDeliveryRepository extends JpaRepository<IntegrationDelivery, Long> {

    List<IntegrationDelivery> findTop50ByIntegrationIdOrderByCreatedAtDesc(Long integrationId);
}
