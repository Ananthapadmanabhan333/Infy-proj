package com.nexus360.servicedesk;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceRequestCommentRepository extends JpaRepository<ServiceRequestComment, Long> {

    @EntityGraph(attributePaths = "author")
    List<ServiceRequestComment> findByRequestIdOrderByCreatedAtAscIdAsc(Long requestId);

    @EntityGraph(attributePaths = "author")
    List<ServiceRequestComment> findByRequestIdAndInternalFalseOrderByCreatedAtAscIdAsc(Long requestId);

    @Query("select c.request.id, count(c) from ServiceRequestComment c where c.request.id in :ids group by c.request.id")
    List<Object[]> countAll(@Param("ids") Collection<Long> ids);

    @Query("select c.request.id, count(c) from ServiceRequestComment c where c.request.id in :ids and c.internal = false group by c.request.id")
    List<Object[]> countPublic(@Param("ids") Collection<Long> ids);
}
