package com.nexus360.alert;

import com.nexus360.admin.SettingsService;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.team.Team;
import com.nexus360.user.Department;
import com.nexus360.user.Role;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Built-in escalation (order 1000, the fallback for every alert of at least {@code alert.escalation.min_severity}): when nobody
 * acknowledges an alert within {@code alert.escalation.ack_minutes} (15) it notifies the routed team's lead and the manager of the
 * team's department (level 1; without a team: the managers of the service's department, else the IT managers); after the same
 * time again the incident and IT managers (level 2). Notifications use type {@code ALERT_ESCALATED}.
 */
@Component
@RequiredArgsConstructor
public class TeamLeadEscalationPolicy implements AlertEscalationPolicy {

    public static final int MAX_LEVEL = 2;

    private final SettingsService settings;
    private final UserRepository users;
    private final NotificationService notifications;

    @Override
    public String name() {
        return "team-lead";
    }

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public boolean appliesTo(Alert alert) {
        AlertSeverity min = AlertSeverity.parse(settings.getString("alert.escalation.min_severity", "MAJOR"));
        return alert.getSeverity().atLeast(min == null ? AlertSeverity.MAJOR : min);
    }

    @Override
    public Instant nextEscalationAt(Alert alert, Instant reference) {
        if (alert.getEscalationLevel() >= MAX_LEVEL) {
            return null;
        }
        return reference.plus(Duration.ofMinutes(Math.max(1, settings.getInt("alert.escalation.ack_minutes", 15))));
    }

    @Override
    public Outcome escalate(Alert alert, int level, Instant now) {
        Set<Long> to = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        if (level == 1) {
            Team team = alert.getTeam();
            if (team != null && team.getLead() != null && team.getLead().isActive()) {
                to.add(team.getLead().getId());
                names.add("team lead " + team.getLead().getFullName());
            }
            Department dept = team != null ? team.getDepartment() : alert.getService() == null ? null : alert.getService().getDepartment();
            if (dept != null && dept.getManager() != null && dept.getManager().isActive() && to.add(dept.getManager().getId())) {
                names.add("manager " + dept.getManager().getFullName());
            }
            if (to.isEmpty()) {
                users.findByActiveTrueAndRoleIn(List.of(Role.IT_MANAGER)).stream().map(User::getId).forEach(to::add);
                names.add("the IT managers");
            }
        } else {
            users.findByActiveTrueAndRoleIn(List.of(Role.INCIDENT_MANAGER, Role.IT_MANAGER)).stream().map(User::getId).forEach(to::add);
            names.add("the incident and IT managers");
        }
        notifications.notifyUsers(to, "ALERT_ESCALATED", "Unacknowledged alert " + alert.getNumber() + " (" + alert.getSeverity() + ")",
                truncate(alert.getTitle(), 200) + " has not been acknowledged" + (alert.getTeam() == null ? "" : " by " + alert.getTeam().getName())
                        + ". Escalation level " + level + ".", "/alerts/" + alert.getId(),
                alert.getSeverity() == AlertSeverity.CRITICAL ? Severity.CRITICAL : Severity.WARNING);
        return new Outcome(to, "Level " + level + ": notified " + String.join(", ", names));
    }

    private static String truncate(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max);
    }
}
