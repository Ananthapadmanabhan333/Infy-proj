package com.nexus360.oncall;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscalationPolicyRepository extends JpaRepository<EscalationPolicy, Long> {

    @EntityGraph(attributePaths = "team")
    List<EscalationPolicy> findAllByOrderByNameAsc();

    @EntityGraph(attributePaths = "team")
    Optional<EscalationPolicy> findWithTeamById(Long id);

    Optional<EscalationPolicy> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
