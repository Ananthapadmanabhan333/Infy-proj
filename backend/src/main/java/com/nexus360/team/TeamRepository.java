package com.nexus360.team;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamRepository extends JpaRepository<Team, Long> {

    @EntityGraph(attributePaths = {"department", "lead"})
    Optional<Team> findByCodeIgnoreCase(String code);

    @EntityGraph(attributePaths = {"department", "lead"})
    Optional<Team> findWithDetailsById(Long id);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsByNameIgnoreCase(String name);

    @EntityGraph(attributePaths = {"department", "lead"})
    List<Team> findAllByOrderByNameAsc();

    /** The department's default team (at most one, enforced by a partial unique index). */
    @EntityGraph(attributePaths = {"department"})
    Optional<Team> findFirstByDepartmentIdAndDepartmentDefaultTrueAndActiveTrue(Long departmentId);

    long countByDemoTrue();

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update Team t set t.departmentDefault = false where t.department.id = :departmentId and t.id <> :keepId and t.departmentDefault = true")
    int clearDepartmentDefault(@Param("departmentId") Long departmentId, @Param("keepId") Long keepId);
}
