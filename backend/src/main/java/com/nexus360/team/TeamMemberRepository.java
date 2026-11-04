package com.nexus360.team;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamMemberRepository extends JpaRepository<TeamMember, TeamMember.Key> {

    @EntityGraph(attributePaths = {"user", "user.department"})
    @Query("select m from TeamMember m where m.team.id in :teamIds")
    List<TeamMember> findByTeamIds(@Param("teamIds") Collection<Long> teamIds);

    @EntityGraph(attributePaths = {"team", "team.department"})
    @Query("select m from TeamMember m where m.user.id = :userId and m.team.active = true order by m.team.name")
    List<TeamMember> findActiveTeamsOfUser(@Param("userId") Long userId);

    @Query("select m.id.teamId from TeamMember m where m.user.id = :userId and m.team.active = true")
    List<Long> findActiveTeamIdsOfUser(@Param("userId") Long userId);

    boolean existsByIdTeamIdAndIdUserId(Long teamId, Long userId);
}
