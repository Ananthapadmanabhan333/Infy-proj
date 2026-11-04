package com.nexus360.alert;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertSuppressionRuleRepository extends JpaRepository<AlertSuppressionRule, Long> {

    List<AlertSuppressionRule> findByEnabledTrueOrderByIdAsc();

    List<AlertSuppressionRule> findAllByOrderByNameAsc();
}
