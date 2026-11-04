package com.nexus360.major;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.major.MajorDtos.ActionItemDto;
import com.nexus360.major.MajorDtos.ActionRequest;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.security.Actor;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Action items shared by major incidents (follow-ups) and post-incident reviews (corrective and preventive actions): owner, due
 * date and status {@code OPEN → IN_PROGRESS → DONE | CANCELLED}. Owners must be active staff and are notified on assignment.
 */
@Service
@RequiredArgsConstructor
public class ActionItemService {

    private final ActionItemRepository items;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    /** Where an action item belongs, for notifications and links. */
    public record Parent(Long majorIncidentId, Long pirId, String number, String link, boolean demo) {}

    @Transactional
    public ActionItem add(Parent parent, ActionRequest r, Actor actor) {
        ActionItem a = new ActionItem();
        a.setMajorIncidentId(parent.majorIncidentId());
        a.setPirId(parent.pirId());
        a.setCreatedById(actor.id());
        a.setDemo(parent.demo());
        a.setActionType(r.actionType() == null ? (parent.pirId() != null ? ActionItem.Type.CORRECTIVE : ActionItem.Type.FOLLOW_UP) : r.actionType());
        apply(a, r, parent, actor, true);
        items.save(a);
        audit.record(parent.pirId() != null ? "PIR_ACTION_ADDED" : "MAJOR_INCIDENT_ACTION_ADDED", "ACTION_ITEM", a.getId(),
                Map.of("parent", parent.number(), "title", a.getTitle(), "owner", a.getOwner() == null ? "" : a.getOwner().getEmail()));
        return a;
    }

    @Transactional
    public ActionItem update(ActionItem a, Parent parent, ActionRequest r, Actor actor) {
        if (r.actionType() != null) {
            a.setActionType(r.actionType());
        }
        apply(a, r, parent, actor, false);
        audit.record(parent.pirId() != null ? "PIR_ACTION_UPDATED" : "MAJOR_INCIDENT_ACTION_UPDATED", "ACTION_ITEM", a.getId(),
                Map.of("parent", parent.number(), "status", a.getStatus()));
        return a;
    }

    private void apply(ActionItem a, ActionRequest r, Parent parent, Actor actor, boolean created) {
        a.setTitle(r.title().trim());
        a.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        a.setDueDate(r.dueDate());
        Long previousOwner = a.getOwner() == null ? null : a.getOwner().getId();
        if (r.ownerId() != null) {
            User owner = users.findById(r.ownerId()).filter(u -> u.isActive() && u.getRole().isStaff())
                    .orElseThrow(() -> ApiException.invalidField("ownerId", "User " + r.ownerId() + " is not an active staff member"));
            a.setOwner(owner);
        } else if (created) {
            a.setOwner(null);
        }
        if (r.status() != null && r.status() != a.getStatus()) {
            a.setStatus(r.status());
            a.setCompletedAt(r.status() == ActionItem.Status.DONE ? clock.instant() : null);
        }
        Long owner = a.getOwner() == null ? null : a.getOwner().getId();
        if (owner != null && !Objects.equals(owner, previousOwner)) {
            notifications.notifyUser(owner, parent.pirId() != null ? "PIR_ACTION_ASSIGNED" : "MAJOR_ACTION_ASSIGNED",
                    "Action assigned: " + parent.number(), a.getTitle() + (a.getDueDate() == null ? "" : " (due " + a.getDueDate() + ")"),
                    parent.link(), Severity.INFO);
        }
    }

    @Transactional(readOnly = true)
    public ActionItem find(Long id) {
        return items.findWithOwnerById(id).orElseThrow(() -> ApiException.notFound("Action item", id));
    }

    @Transactional(readOnly = true)
    public List<ActionItemDto> ofMajorIncident(Long id) {
        return items.findByMajorIncidentIdOrderByCreatedAtAscIdAsc(id).stream().map(this::dto).toList();
    }

    @Transactional(readOnly = true)
    public List<ActionItemDto> ofReview(Long id) {
        return items.findByPirIdOrderByCreatedAtAscIdAsc(id).stream().map(this::dto).toList();
    }

    @Transactional(readOnly = true)
    public List<ActionItem> openFor(Long ownerId, int limit) {
        return items.findByOwnerIdAndStatusInOrderByDueDateAscIdAsc(ownerId, ActionItem.Status.OPEN_STATES, PageRequest.of(0, Math.max(1, limit)));
    }

    @Transactional(readOnly = true)
    public long openCountFor(Long ownerId) {
        return items.countByOwnerIdAndStatusIn(ownerId, ActionItem.Status.OPEN_STATES);
    }

    public ActionItemDto dto(ActionItem a) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Instant updated = a.getUpdatedAt();
        return new ActionItemDto(a.getId(), a.getPirId() != null ? "PIR" : "MAJOR_INCIDENT", a.getMajorIncidentId(), a.getPirId(), a.getTitle(),
                a.getDescription(), a.getActionType(), a.getOwner() == null ? null : a.getOwner().getId(),
                a.getOwner() == null ? null : a.getOwner().getFullName(), a.getDueDate(), a.getStatus(),
                a.getDueDate() != null && ActionItem.Status.OPEN_STATES.contains(a.getStatus()) && a.getDueDate().isBefore(today), a.getCompletedAt(),
                a.isDemo(), a.getCreatedAt(), updated);
    }
}
