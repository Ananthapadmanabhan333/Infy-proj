package com.nexus360.announcement;

import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.notification.Notification;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.Actor;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import com.nexus360.user.Role;
import com.nexus360.user.UserRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Announcements")
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    public record AnnouncementDto(Long id, String title, String body, Announcement.Severity severity, Long serviceId, String serviceName,
                                  Instant startsAt, Instant endsAt, boolean published, String authorName, Instant createdAt, boolean demo) {}

    public record AnnouncementRequest(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 10_000) String body,
                                      @NotNull Announcement.Severity severity, Long serviceId, Instant startsAt, Instant endsAt,
                                      boolean published) {}

    private final AnnouncementRepository repository;
    private final BusinessServiceRepository services;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    @GetMapping
    @Transactional(readOnly = true)
    public List<AnnouncementDto> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        Actor actor = CurrentActor.require();
        boolean editor = actor.isAdminOrManager();
        Instant now = clock.instant();
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .filter(a -> editor || a.isPublished())
                .filter(a -> !activeOnly || (a.isPublished() && (a.getStartsAt() == null || !a.getStartsAt().isAfter(now))
                        && (a.getEndsAt() == null || a.getEndsAt().isAfter(now))))
                .map(AnnouncementController::dto).toList();
    }

    @PreAuthorize(Roles.ADMIN_OR_MANAGER)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AnnouncementDto create(@Valid @RequestBody AnnouncementRequest request) {
        Actor actor = CurrentActor.require();
        Announcement a = new Announcement();
        a.setAuthor(users.getReferenceById(actor.id()));
        apply(a, request);
        repository.save(a);
        if (a.isPublished()) {
            notifyPublished(a);
        }
        audit.record("ANNOUNCEMENT_CREATED", "ANNOUNCEMENT", a.getId(), Map.of("title", a.getTitle(), "published", a.isPublished()));
        return dto(a);
    }

    @PreAuthorize(Roles.ADMIN_OR_MANAGER)
    @PutMapping("/{id}")
    @Transactional
    public AnnouncementDto update(@PathVariable Long id, @Valid @RequestBody AnnouncementRequest request) {
        Announcement a = repository.findById(id).orElseThrow(() -> ApiException.notFound("Announcement", id));
        boolean wasPublished = a.isPublished();
        apply(a, request);
        if (a.isPublished() && !wasPublished) {
            notifyPublished(a);
        }
        audit.record("ANNOUNCEMENT_UPDATED", "ANNOUNCEMENT", a.getId(), Map.of("published", a.isPublished()));
        return dto(a);
    }

    @PreAuthorize(Roles.ADMIN_OR_MANAGER)
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Announcement a = repository.findById(id).orElseThrow(() -> ApiException.notFound("Announcement", id));
        repository.delete(a);
        audit.record("ANNOUNCEMENT_DELETED", "ANNOUNCEMENT", id, Map.of("title", a.getTitle()));
        return ResponseEntity.noContent().build();
    }

    private void apply(Announcement a, AnnouncementRequest r) {
        if (r.startsAt() != null && r.endsAt() != null && !r.endsAt().isAfter(r.startsAt())) {
            throw ApiException.badRequest("End time must be after start time");
        }
        a.setTitle(r.title().trim());
        a.setBody(r.body().trim());
        a.setSeverity(r.severity());
        a.setService(r.serviceId() == null ? null : services.findById(r.serviceId()).orElseThrow(() -> ApiException.badRequest("Service does not exist")));
        a.setStartsAt(r.startsAt());
        a.setEndsAt(r.endsAt());
        a.setPublished(r.published());
    }

    private void notifyPublished(Announcement a) {
        if (a.getSeverity() == Announcement.Severity.CRITICAL || a.getSeverity() == Announcement.Severity.MAINTENANCE) {
            notifications.notifyRoles(List.of(Role.values()), "ANNOUNCEMENT", a.getTitle(),
                    a.getBody().length() > 300 ? a.getBody().substring(0, 300) + "…" : a.getBody(), "/announcements",
                    a.getSeverity() == Announcement.Severity.CRITICAL ? Notification.Severity.CRITICAL : Notification.Severity.WARNING);
        }
    }

    private static AnnouncementDto dto(Announcement a) {
        return new AnnouncementDto(a.getId(), a.getTitle(), a.getBody(), a.getSeverity(), a.getService() == null ? null : a.getService().getId(),
                a.getService() == null ? null : a.getService().getName(), a.getStartsAt(), a.getEndsAt(), a.isPublished(),
                a.getAuthor() == null ? null : a.getAuthor().getFullName(), a.getCreatedAt(), a.isDemo());
    }
}
