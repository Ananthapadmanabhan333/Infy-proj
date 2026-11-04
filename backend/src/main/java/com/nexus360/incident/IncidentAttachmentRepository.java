package com.nexus360.incident;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentAttachmentRepository extends JpaRepository<IncidentAttachment, Long> {

    /** Metadata only — the binary column is not loaded for listings. */
    record AttachmentMeta(Long id, String fileName, String contentType, long sizeBytes, Long uploadedById, String uploadedByName,
                          Instant createdAt) {}

    @Query("select new com.nexus360.incident.IncidentAttachmentRepository$AttachmentMeta(a.id, a.fileName, a.contentType, "
            + "a.sizeBytes, u.id, u.fullName, a.createdAt) from IncidentAttachment a left join a.uploadedBy u "
            + "where a.incident.id = :incidentId order by a.createdAt asc")
    List<AttachmentMeta> findMetaByIncident(@Param("incidentId") Long incidentId);

    Optional<IncidentAttachment> findByIdAndIncidentId(Long id, Long incidentId);

    long countByIncidentId(Long incidentId);
}
