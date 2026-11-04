package com.nexus360.change;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChangeIncidentRepository extends JpaRepository<ChangeIncident, ChangeIncident.Key> {

    @Query("select ci from ChangeIncident ci where ci.id.changeId = :changeId order by ci.createdAt")
    List<ChangeIncident> findByChange(@Param("changeId") Long changeId);
}
