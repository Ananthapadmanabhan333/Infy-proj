package com.nexus360.oncall;

import com.nexus360.alert.Alert;
import com.nexus360.alert.AlertEscalationPolicy;
import com.nexus360.alert.AlertSeverity;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.oncall.EscalationPolicy.Level;
import com.nexus360.oncall.EscalationPolicy.Target;
import com.nexus360.oncall.Rotation.Assignment;
import com.nexus360.oncall.Rotation.Layer;
import com.nexus360.team.Team;
import com.nexus360.user.Department;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * On-call escalation policy for alerts (order 100, before the built-in {@code team-lead} policy at 1000). It applies to an alert
 * whose routed team has an active on-call schedule with an active escalation policy and whose severity reaches the policy's
 * {@code minSeverity}; every other alert keeps the built-in policy. Level {@code n} is due {@code levels[n-1].delayMinutes} after the
 * previous level (level 1: after the alert became active) and pages its target: the schedule's primary or secondary on call at
 * that moment (overrides, holidays and unavailability applied), the team lead plus the department manager, or a named user. Every
 * page is stored in {@code oncall_pages} (unique per alert, level and person — together with the escalation service's claim on
 * {@code escalation_level} each level pages exactly once) and notified as {@code ONCALL_PAGE} (e-mail on by default).
 */
@Component
@RequiredArgsConstructor
public class OnCallEscalation implements AlertEscalationPolicy {

    public static final String NAME = "on-call";

    private final OnCallService oncall;
    private final UserRepository users;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;

    /** The schedule and policy that handle an alert. */
    public record Binding(OnCallSchedule schedule, EscalationPolicy policy) {}

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public boolean appliesTo(Alert alert) {
        return binding(alert) != null;
    }

    /** First active schedule of the alert's team with an active policy whose minimum severity the alert reaches, else null. */
    public Binding binding(Alert alert) {
        Team team = alert.getTeam();
        if (team == null || alert.getSeverity() == null) {
            return null;
        }
        for (OnCallSchedule s : oncall.activeSchedulesOfTeam(team.getId())) {
            EscalationPolicy p = s.getEscalationPolicy();
            if (p != null && p.isActive() && !p.getLevels().isEmpty()
                    && alert.getSeverity().atLeast(p.getMinSeverity() == null ? AlertSeverity.MAJOR : p.getMinSeverity())) {
                return new Binding(s, p);
            }
        }
        return null;
    }

    @Override
    public Instant nextEscalationAt(Alert alert, Instant reference) {
        Binding b = binding(alert);
        if (b == null) {
            return null;
        }
        int next = alert.getEscalationLevel() + 1;
        List<Level> levels = b.policy().getLevels();
        if (next > levels.size()) {
            return null;
        }
        return reference.plus(Duration.ofMinutes(Math.max(0, levels.get(next - 1).delayMinutes())));
    }

    @Override
    public Outcome escalate(Alert alert, int level, Instant now) {
        Binding b = binding(alert);
        if (b == null || level < 1 || level > b.policy().getLevels().size()) {
            return new Outcome(List.of(), "No on-call level " + level + " for this alert");
        }
        Level l = b.policy().getLevels().get(level - 1);
        Map<Long, String> targets = new LinkedHashMap<>();
        String what = switch (l.target()) {
            case PRIMARY -> onCall(b.schedule(), Layer.PRIMARY, now, targets, "primary on call");
            case SECONDARY -> onCall(b.schedule(), Layer.SECONDARY, now, targets, "secondary on call");
            case MANAGER -> managers(b.schedule().getTeam(), targets);
            case USER -> named(l.userId(), targets);
        };
        if (targets.isEmpty() && l.target() != Target.MANAGER) {
            // Nobody resolvable (empty layer, inactive user): fall back to the team lead and manager so the alert is never silent.
            what = what + " (nobody available) → " + managers(b.schedule().getTeam(), targets);
        }
        Set<Long> paged = new LinkedHashSet<>();
        for (Long userId : targets.keySet()) {
            int inserted = jdbc.update("insert into oncall_pages (alert_id, schedule_id, policy_id, level, target, user_id, paged_at, is_demo) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (alert_id, level, user_id) do nothing", alert.getId(), b.schedule().getId(),
                    b.policy().getId(), level, l.target().name(), userId, Timestamp.from(now), alert.isDemo());
            if (inserted > 0) {
                paged.add(userId);
            }
        }
        if (!paged.isEmpty()) {
            notifications.notifyUsers(paged, "ONCALL_PAGE", "Paged: " + alert.getNumber() + " (" + alert.getSeverity() + ") " + b.schedule().getTeam().getName(),
                    truncate(alert.getTitle(), 200) + " is not acknowledged. Escalation level " + level + " of " + b.policy().getLevels().size()
                            + " (" + b.policy().getName() + "): acknowledge it in SA.", "/alerts/" + alert.getId(),
                    alert.getSeverity() == AlertSeverity.CRITICAL ? Severity.CRITICAL : Severity.WARNING);
        }
        return new Outcome(paged, "Level " + level + " (" + OnCallService.label(l.target(), null).toLowerCase() + ", " + b.schedule().getName() + "): "
                + (paged.isEmpty() ? "nobody new to page" : "paged " + String.join(", ", paged.stream().map(targets::get).toList())) + " — " + what);
    }

    private String onCall(OnCallSchedule s, Layer layer, Instant now, Map<Long, String> targets, String label) {
        Assignment a = oncall.whoAt(s, layer, now);
        if (a == null) {
            return "no " + label;
        }
        users.findById(a.userId()).filter(User::isActive).ifPresent(u -> targets.put(u.getId(), u.getFullName()));
        return label + (a.source() == Rotation.Source.ROTATION ? "" : " (" + a.source().name().toLowerCase().replace('_', ' ') + ")");
    }

    private String managers(Team team, Map<Long, String> targets) {
        List<String> names = new ArrayList<>();
        if (team.getLead() != null && team.getLead().isActive()) {
            targets.put(team.getLead().getId(), team.getLead().getFullName());
            names.add("team lead");
        }
        Department d = team.getDepartment();
        if (d != null && d.getManager() != null && d.getManager().isActive()) {
            targets.putIfAbsent(d.getManager().getId(), d.getManager().getFullName());
            names.add("department manager");
        }
        if (targets.isEmpty()) {
            users.findByActiveTrueAndRoleIn(List.of(Role.IT_MANAGER)).forEach(u -> targets.put(u.getId(), u.getFullName()));
            names.add("IT managers");
        }
        return String.join(" and ", names);
    }

    private String named(Long userId, Map<Long, String> targets) {
        users.findById(userId == null ? -1L : userId).filter(User::isActive).ifPresent(u -> targets.put(u.getId(), u.getFullName()));
        return "named responder";
    }

    private static String truncate(String v, int max) {
        return v == null ? "" : v.length() <= max ? v : v.substring(0, max);
    }
}
