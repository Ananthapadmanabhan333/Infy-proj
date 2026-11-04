package com.nexus360.servicedesk;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceRequestAttachmentRepository extends JpaRepository<ServiceRequestAttachment, Long> {

    record Meta(Long id, String fileName, String contentType, int sizeBytes, String fieldKey, String uploadedByName, Instant createdAt) {}

    @Query("select new com.nexus360.servicedesk.ServiceRequestAttachmentRepository$Meta(a.id, a.fileName, a.contentType, a.sizeBytes, "
            + "a.fieldKey, u.fullName, a.createdAt) from ServiceRequestAttachment a join a.uploadedBy u where a.requestId = :requestId order by a.id")
    List<Meta> findMetaByRequest(@Param("requestId") Long requestId);

    @Query("select new com.nexus360.servicedesk.ServiceRequestAttachmentRepository$Meta(a.id, a.fileName, a.contentType, a.sizeBytes, "
            + "a.fieldKey, u.fullName, a.createdAt) from ServiceRequestAttachment a join a.uploadedBy u "
            + "where a.id = :id and a.uploadedBy.id = :userId and a.requestId is null")
    Optional<Meta> findStaged(@Param("id") Long id, @Param("userId") Long userId);

    Optional<ServiceRequestAttachment> findByIdAndRequestId(Long id, Long requestId);

    long countByUploadedByIdAndRequestIdIsNull(Long userId);

    @Modifying
    @Query("delete from ServiceRequestAttachment a where a.uploadedBy.id = :userId and a.requestId is null and a.createdAt < :before")
    int purgeStaged(@Param("userId") Long userId, @Param("before") Instant before);

    @Modifying
    @Query("update ServiceRequestAttachment a set a.requestId = :requestId, a.fieldKey = :fieldKey where a.id in :ids "
            + "and a.uploadedBy.id = :userId and a.requestId is null")
    int link(@Param("ids") List<Long> ids, @Param("requestId") Long requestId, @Param("fieldKey") String fieldKey, @Param("userId") Long userId);
}
