package com.nexus360.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    @EntityGraph(attributePaths = "department")
    Optional<User> findByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = "department")
    Optional<User> findWithDepartmentById(Long id);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByRoleAndActiveTrue(Role role);

    long countByDemoTrue();

    @EntityGraph(attributePaths = "department")
    List<User> findByActiveTrueAndRoleIn(Collection<Role> roles);

    @EntityGraph(attributePaths = "department")
    List<User> findByActiveTrueAndRoleInAndDepartmentId(Collection<Role> roles, Long departmentId);

    @Query("select u from User u where u.active = true and u.department.id = :departmentId")
    List<User> findActiveByDepartment(@Param("departmentId") Long departmentId);

    @EntityGraph(attributePaths = "department")
    List<User> findByDemoTrueAndActiveTrueOrderByRoleAscFullNameAsc();

    @Query("select u.department.id, count(u) from User u where u.department is not null group by u.department.id")
    List<Object[]> countMembersByDepartment();
}
