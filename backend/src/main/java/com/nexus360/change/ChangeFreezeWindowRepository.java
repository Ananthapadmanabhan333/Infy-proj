package com.nexus360.change;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChangeFreezeWindowRepository extends JpaRepository<ChangeFreezeWindow, Long> {

    @Query("select f from ChangeFreezeWindow f where f.active = true and f.startsAt < :to and f.endsAt > :from order by f.startsAt")
    List<ChangeFreezeWindow> findOverlapping(@Param("from") Instant from, @Param("to") Instant to);

    @Query("select f from ChangeFreezeWindow f where f.endsAt > :since order by f.startsAt")
    List<ChangeFreezeWindow> findEndingAfter(@Param("since") Instant since);
}
