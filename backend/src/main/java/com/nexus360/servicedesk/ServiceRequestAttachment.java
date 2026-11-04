package com.nexus360.servicedesk;

import com.nexus360.common.CreatedEntity;
import com.nexus360.user.User;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A file submitted through a file-upload form field. Uploaded first (staged, {@code requestId} null, only usable by the
 * uploader) and linked to the request when it is created. Validated with the incident attachment rules (size, extension
 * allow-list, content signature).
 */
@Getter
@Setter
@Entity
@Table(name = "service_request_attachments")
public class ServiceRequestAttachment extends CreatedEntity {

    @Column(name = "request_id")
    private Long requestId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_id")
    private User uploadedBy;

    @Column(name = "field_key", length = 40)
    private String fieldKey;

    @Column(name = "file_name", nullable = false, length = 200)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] data;
}
