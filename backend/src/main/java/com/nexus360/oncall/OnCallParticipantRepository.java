package com.nexus360.oncall;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OnCallParticipantRepository extends JpaRepository<OnCallParticipant, Long> {

    @EntityGraph(attributePaths = "user")
    List<OnCallParticipant> findByScheduleIdOrderByLayerAscPositionAsc(Long scheduleId);

    @EntityGraph(attributePaths = "user")
    List<OnCallParticipant> findByScheduleIdInOrderByScheduleIdAscLayerAscPositionAsc(Collection<Long> scheduleIds);

    @Query("select distinct p.scheduleId from OnCallParticipant p where p.user.id = :userId")
    List<Long> findScheduleIdsOfUser(@Param("userId") Long userId);

    boolean existsByScheduleIdAndUserId(Long scheduleId, Long userId);

    @Modifying
    @Query("delete from OnCallParticipant p where p.scheduleId = :scheduleId")
    int deleteBySchedule(@Param("scheduleId") Long scheduleId);
}
