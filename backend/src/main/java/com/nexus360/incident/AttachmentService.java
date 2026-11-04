package com.nexus360.incident;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.config.AppProperties;
import com.nexus360.incident.IncidentDtos.AttachmentDto;
import com.nexus360.security.Actor;
import com.nexus360.security.Hashing;
import com.nexus360.user.Role;
import com.nexus360.user.UserRepository;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload validation: size limit, extension allow-list, and a content signature check so the stored
 * content type is derived from the bytes rather than trusted from the client.
 */
@Service
@RequiredArgsConstructor
public class AttachmentService {

    public static final int MAX_ATTACHMENTS_PER_INCIDENT = 20;

    private static final Map<String, String> TEXT_TYPES = Map.of(
            "txt", "text/plain", "log", "text/plain", "csv", "text/csv", "json", "application/json");

    private final IncidentAttachmentRepository attachments;
    private final IncidentQueryService queries;
    private final IncidentTimeline timeline;
    private final UserRepository users;
    private final AuditService audit;
    private final AppProperties properties;

    public record Download(String fileName, String contentType, byte[] data) {}

    @Transactional(readOnly = true)
    public List<AttachmentDto> list(Long incidentId, Actor actor) {
        queries.load(incidentId, actor);
        return attachments.findMetaByIncident(incidentId).stream()
                .map(m -> new AttachmentDto(m.id(), m.fileName(), m.contentType(), m.sizeBytes(),
                        m.uploadedByName() == null ? "Unknown" : m.uploadedByName(), m.createdAt()))
                .toList();
    }

    @Transactional
    public AttachmentDto upload(Long incidentId, MultipartFile file, Actor actor) {
        Incident incident = queries.load(incidentId, actor);
        if (actor.role() == Role.AUDITOR) {
            throw ApiException.forbidden("Auditors have read-only access");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("The file is empty");
        }
        if (file.getSize() > properties.attachments().maxBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the " + properties.attachments().maxBytes() / (1024 * 1024) + " MB limit");
        }
        if (attachments.countByIncidentId(incidentId) >= MAX_ATTACHMENTS_PER_INCIDENT) {
            throw ApiException.conflict("An incident can have at most " + MAX_ATTACHMENTS_PER_INCIDENT + " attachments");
        }
        String name = sanitizeFileName(file.getOriginalFilename());
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read the uploaded file");
        }
        String contentType = detectContentType(name, data);
        IncidentAttachment attachment = new IncidentAttachment();
        attachment.setIncident(incident);
        attachment.setUploadedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        attachment.setFileName(name);
        attachment.setContentType(contentType);
        attachment.setSizeBytes(data.length);
        attachment.setSha256(Hashing.sha256Hex(data));
        attachment.setData(data);
        attachments.save(attachment);
        timeline.record(incident, actor, "ATTACHMENT", "Attached " + name);
        audit.record("ATTACHMENT_UPLOADED", "INCIDENT", incident.getNumber(), Map.of("file", name, "bytes", data.length, "type", contentType));
        return new AttachmentDto(attachment.getId(), name, contentType, data.length, actor.name(), attachment.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public Download download(Long incidentId, Long attachmentId, Actor actor) {
        queries.load(incidentId, actor);
        IncidentAttachment a = attachments.findByIdAndIncidentId(attachmentId, incidentId)
                .orElseThrow(() -> ApiException.notFound("Attachment", attachmentId));
        return new Download(a.getFileName(), a.getContentType(), a.getData());
    }

    @Transactional
    public void delete(Long incidentId, Long attachmentId, Actor actor) {
        Incident incident = queries.load(incidentId, actor);
        IncidentAttachment a = attachments.findByIdAndIncidentId(attachmentId, incidentId)
                .orElseThrow(() -> ApiException.notFound("Attachment", attachmentId));
        boolean uploader = a.getUploadedBy() != null && a.getUploadedBy().getId().equals(actor.id());
        if (!uploader && !actor.isAdminOrManager()) {
            throw ApiException.forbidden("Only the uploader or a manager can delete this attachment");
        }
        attachments.delete(a);
        timeline.record(incident, actor, "ATTACHMENT", "Removed attachment " + a.getFileName());
        audit.record("ATTACHMENT_DELETED", "INCIDENT", incident.getNumber(), Map.of("file", a.getFileName()));
    }

    public static String sanitizeFileName(String original) {
        String name = original == null ? "" : original;
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}\"<>|:*?]", "_").trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.isBlank()) {
            name = "attachment";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    public static String detectContentType(String fileName, byte[] data) {
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        switch (ext) {
            case "png" -> {
                return requireSignature(data, new byte[]{(byte) 0x89, 'P', 'N', 'G'}, "image/png");
            }
            case "jpg", "jpeg" -> {
                return requireSignature(data, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, "image/jpeg");
            }
            case "gif" -> {
                return requireSignature(data, new byte[]{'G', 'I', 'F', '8'}, "image/gif");
            }
            case "pdf" -> {
                return requireSignature(data, new byte[]{'%', 'P', 'D', 'F'}, "application/pdf");
            }
            case "docx" -> {
                return requireSignature(data, new byte[]{'P', 'K', 3, 4},
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            }
            case "xlsx" -> {
                return requireSignature(data, new byte[]{'P', 'K', 3, 4},
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            }
            default -> {
                String textType = TEXT_TYPES.get(ext);
                if (textType == null) {
                    throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            "File type not allowed. Allowed: png, jpg, gif, pdf, docx, xlsx, txt, log, csv, json");
                }
                if (!isUtf8Text(data)) {
                    throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The file content does not match its ." + ext + " extension");
                }
                return textType;
            }
        }
    }

    private static String requireSignature(byte[] data, byte[] signature, String type) {
        if (data.length < signature.length) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The file content does not match its extension");
        }
        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The file content does not match its extension");
            }
        }
        return type;
    }

    private static boolean isUtf8Text(byte[] data) {
        for (byte b : data) {
            if (b == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
