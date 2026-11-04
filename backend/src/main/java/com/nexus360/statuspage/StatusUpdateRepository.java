package com.nexus360.statuspage;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StatusUpdateRepository extends JpaRepository<StatusUpdate, Long> {

    @EntityGraph(attributePaths = {"incident"})
    @Query("select u from StatusUpdate u where u.postedAt >= :since order by u.postedAt desc, u.id desc")
    List<StatusUpdate> findSince(@Param("since") Instant since);

    @EntityGraph(attributePaths = {"incident"})
    @Query("select u from StatusUpdate u where u.publicVisible = true and u.postedAt >= :since order by u.postedAt desc, u.id desc")
    List<StatusUpdate> findPublicSince(@Param("since") Instant since);
}
