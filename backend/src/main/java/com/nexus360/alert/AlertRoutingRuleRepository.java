package com.nexus360.alert;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRoutingRuleRepository extends JpaRepository<AlertRoutingRule, Long> {

    @EntityGraph(attributePaths = {"team", "service", "ci"})
    List<AlertRoutingRule> findByEnabledTrueOrderBySortOrderAscIdAsc();

    @EntityGraph(attributePaths = {"team", "service", "ci"})
    List<AlertRoutingRule> findAllByOrderBySortOrderAscIdAsc();
}
