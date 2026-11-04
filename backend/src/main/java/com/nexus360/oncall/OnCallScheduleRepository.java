package com.nexus360.oncall;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnCallScheduleRepository extends JpaRepository<OnCallSchedule, Long> {

    @EntityGraph(attributePaths = {"team", "team.department", "team.lead", "escalationPolicy", "holidayCoverUser"})
    Optional<OnCallSchedule> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"team", "team.department", "escalationPolicy", "holidayCoverUser"})
    List<OnCallSchedule> findAllByOrderByNameAsc();

    @EntityGraph(attributePaths = {"team", "team.department", "team.lead", "escalationPolicy", "holidayCoverUser"})
    List<OnCallSchedule> findByTeamIdAndActiveTrueOrderByIdAsc(Long teamId);

    @EntityGraph(attributePaths = {"team", "team.department", "escalationPolicy", "holidayCoverUser"})
    List<OnCallSchedule> findByActiveTrueOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    long countByDemoTrue();

    long countByEscalationPolicyId(Long policyId);
}
