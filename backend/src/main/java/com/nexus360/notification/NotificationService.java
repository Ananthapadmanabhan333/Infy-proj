package com.nexus360.notification;

import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.security.CurrentActor;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * In-app notifications. Recipients are resolved here so callers can address users, roles or departments. Each user's
 * notification preferences are honoured when the notification is created, and new notifications are pushed to open
 * Server-Sent-Event streams once the creating transaction commits.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    public record NotificationDto(Long id, String type, String title, String message, String link, Severity severity,
                                  boolean read, Instant createdAt) {

        static NotificationDto of(Notification n) {
            return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getLink(), n.getSeverity(),
                    n.getReadAt() != null, n.getCreatedAt());
        }
    }

    /** {@code email}: effective e-mail channel; {@code emailDefault}: its value for users who never changed it. */
    public record PreferenceDto(String type, String label, String description, boolean inApp, boolean email, boolean emailDefault) {}

    private static final List<Role> DEPARTMENT_STAFF = List.of(Role.SUPPORT_AGENT, Role.IT_MANAGER, Role.INCIDENT_MANAGER, Role.CHANGE_MANAGER,
            Role.ASSET_MANAGER);

    private final NotificationRepository repository;
    private final NotificationPreferenceRepository preferences;
    private final NotificationStreamHub hub;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final com.nexus360.email.EmailService email;
    private final Clock clock;

    @Transactional
    public void notifyUsers(Collection<Long> userIds, String type, String title, String message, String link, Severity severity) {
        Set<Long> unique = new LinkedHashSet<>();
        userIds.stream().filter(Objects::nonNull).forEach(unique::add);
        Long self = CurrentActor.orSystem().id();
        if (self != null && severity != Severity.CRITICAL) {
            unique.remove(self); // do not notify people about their own actions
        }
        String category = NotificationTypes.categoryOf(type);
        if (category != null && !unique.isEmpty()) {
            queueEmails(new LinkedHashSet<>(unique), category, title, message, link);
            unique.removeAll(new HashSet<>(preferences.findDisabledUsers(category, unique)));
        }
        List<Notification> created = new ArrayList<>();
        for (Long userId : unique) {
            Notification n = new Notification();
            n.setUserId(userId);
            n.setType(type);
            n.setTitle(truncate(title, 200));
            n.setMessage(truncate(message, 1000));
            n.setLink(link);
            n.setSeverity(severity);
            created.add(repository.save(n));
        }
        if (!created.isEmpty()) {
            publishAfterCommit(created);
        }
    }

    @Transactional
    public void notifyUser(Long userId, String type, String title, String message, String link, Severity severity) {
        if (userId != null) {
            notifyUsers(List.of(userId), type, title, message, link, severity);
        }
    }

    @Transactional
    public void notifyRoles(Collection<Role> roles, String type, String title, String message, String link, Severity severity) {
        notifyUsers(users.findByActiveTrueAndRoleIn(roles).stream().map(User::getId).toList(), type, title, message, link, severity);
    }

    /** Agents and managers of the department; all managers when the department is unknown. */
    @Transactional
    public void notifyDepartment(Long departmentId, String type, String title, String message, String link, Severity severity) {
        if (departmentId == null) {
            notifyRoles(Role.MANAGERS, type, title, message, link, severity);
            return;
        }
        notifyUsers(users.findByActiveTrueAndRoleInAndDepartmentId(DEPARTMENT_STAFF, departmentId).stream().map(User::getId).toList(),
                type, title, message, link, severity);
    }

    /** The department's manager, falling back to all IT and incident managers when none is set. */
    @Transactional
    public void notifyDepartmentManager(Long departmentId, String type, String title, String message, String link, Severity severity) {
        Long managerId = departmentId == null ? null
                : departments.findById(departmentId).map(Department::getManager).map(User::getId).orElse(null);
        if (managerId != null) {
            notifyUser(managerId, type, title, message, link, severity);
        } else {
            notifyRoles(Role.MANAGERS, type, title, message, link, severity);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDto> list(boolean unreadOnly, Integer page, Integer size) {
        Long userId = CurrentActor.require().id();
        int s = size == null ? 20 : Math.min(Math.max(size, 1), PageResponse.MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(page == null ? 0 : Math.max(page, 0), s);
        return PageResponse.of(unreadOnly
                ? repository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, pageable)
                : repository.findByUserIdOrderByCreatedAtDesc(userId, pageable), NotificationDto::of);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return repository.countByUserIdAndReadAtIsNull(CurrentActor.require().id());
    }

    @Transactional
    public void markRead(Long id) {
        Long userId = CurrentActor.require().id();
        Notification n = repository.findById(id).filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Notification", id));
        if (n.getReadAt() == null) {
            n.setReadAt(clock.instant());
        }
    }

    @Transactional
    public int markAllRead() {
        return repository.markAllRead(CurrentActor.require().id(), clock.instant());
    }

    // ------------------------------------------------------------------ preferences

    @Transactional(readOnly = true)
    public List<PreferenceDto> preferences(Long userId) {
        Map<String, NotificationPreference> stored = new java.util.HashMap<>();
        preferences.findByUserId(userId).forEach(p -> stored.put(p.getId().getType(), p));
        return NotificationTypes.PREFERENCES.stream()
                .map(t -> {
                    NotificationPreference p = stored.get(t.type());
                    boolean inApp = p == null || p.isInApp();
                    boolean mail = p == null || p.getEmail() == null ? t.emailDefault() : p.getEmail();
                    return new PreferenceDto(t.type(), t.label(), t.description(), inApp, mail, t.emailDefault());
                })
                .toList();
    }

    /**
     * Partial update. Each value is either a boolean (the in-app channel, Release 2 format) or an object
     * {@code {inApp?: boolean, email?: boolean}} (Release 3).
     */
    @Transactional
    public List<PreferenceDto> updatePreferences(Long userId, Map<String, ?> changes) {
        if (changes == null || changes.isEmpty()) {
            throw ApiException.badRequest("No preferences given");
        }
        Map<String, Boolean[]> parsed = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ?> e : changes.entrySet()) {
            if (!NotificationTypes.isPreferenceType(e.getKey())) {
                throw ApiException.badRequest("Unknown notification type '" + e.getKey() + "'");
            }
            Object v = e.getValue();
            if (v instanceof Boolean b) {
                parsed.put(e.getKey(), new Boolean[]{b, null});
            } else if (v instanceof Map<?, ?> m && !m.isEmpty() && m.keySet().stream().allMatch(k -> "inApp".equals(k) || "email".equals(k))
                    && m.values().stream().allMatch(x -> x instanceof Boolean)) {
                parsed.put(e.getKey(), new Boolean[]{(Boolean) m.get("inApp"), (Boolean) m.get("email")});
            } else {
                throw ApiException.badRequest("Preference for " + e.getKey() + " must be true, false or {\"inApp\": boolean, \"email\": boolean}");
            }
        }
        Instant now = clock.instant();
        parsed.forEach((type, values) -> {
            NotificationPreference.Key key = new NotificationPreference.Key(userId, type);
            NotificationPreference p = preferences.findById(key).orElseGet(() -> {
                NotificationPreference fresh = new NotificationPreference();
                fresh.setId(key);
                return fresh;
            });
            if (values[0] != null) {
                p.setInApp(values[0]);
            }
            if (values[1] != null) {
                p.setEmail(values[1]);
            }
            p.setUpdatedAt(now);
            preferences.save(p);
        });
        return preferences(userId);
    }

    /**
     * E-mail channel: recipients whose choice (or the category default) is on get the notification by e-mail as well. The
     * outbox records the message as SKIPPED when SMTP is not configured, so nothing claims a delivery that cannot happen.
     */
    private void queueEmails(Set<Long> recipients, String category, String title, String message, String link) {
        Set<Long> mail = new LinkedHashSet<>();
        if (NotificationTypes.emailDefault(category)) {
            mail.addAll(recipients);
            preferences.findEmailChoice(category, false, recipients).forEach(mail::remove);
        } else {
            mail.addAll(preferences.findEmailChoice(category, true, recipients));
        }
        if (mail.isEmpty()) {
            return;
        }
        String label = NotificationTypes.PREFERENCES.stream().filter(p -> p.type().equals(category))
                .map(NotificationTypes.PreferenceType::label).findFirst().orElse(category);
        for (User u : users.findAllById(mail)) {
            if (!u.isActive()) {
                continue;
            }
            email.queue(com.nexus360.email.EmailService.Request.of(com.nexus360.email.EmailService.NOTIFICATION, u.getEmail(), u.getId(),
                    truncate(title, 200), new com.nexus360.email.EmailTemplates.Content(title,
                            message == null || message.isBlank() ? List.of() : List.of(message), List.of(), "Open in SA", email.link(link),
                            "You receive this because e-mail notifications for \u201c" + label + "\u201d are on. "
                                    + "You can switch them off under Profile \u2192 Notifications."), false));
        }
    }

    // ------------------------------------------------------------------ helpers

    private void publishAfterCommit(List<Notification> created) {
        List<Map.Entry<Long, NotificationDto>> pushes = created.stream()
                .map(n -> Map.entry(n.getUserId(), NotificationDto.of(n))).toList();
        Runnable push = () -> pushes.forEach(p -> hub.publish(p.getKey(), p.getValue()));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    push.run();
                }
            });
        } else {
            push.run();
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
