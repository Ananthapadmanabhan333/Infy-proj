package com.nexus360.security;

import com.nexus360.user.Role;
import com.nexus360.user.User;

/** Who is performing an action. {@link #SYSTEM} is used by schedulers, workflows and the demo scenario engine. */
public record Actor(Long id, String email, String name, Role role, int depth) {

    public static final Actor SYSTEM = new Actor(null, "system@nexus360", "SA Automation", Role.SYSTEM_ADMIN, 0);

    public static final String WORKFLOW_EMAIL = "workflow@nexus360";

    public static Actor of(User user) {
        return new Actor(user.getId(), user.getEmail(), user.getFullName(), user.getRole(), 0);
    }

    public static Actor workflow(String workflowName, int depth) {
        return new Actor(null, WORKFLOW_EMAIL, "Workflow: " + workflowName, Role.SYSTEM_ADMIN, depth);
    }

    public boolean isSystem() {
        return id == null;
    }

    public boolean isStaff() {
        return role.isStaff();
    }

    /** Automation acting through a workflow (see {@link #workflow}). */
    public boolean isWorkflow() {
        return id == null && WORKFLOW_EMAIL.equals(email);
    }

    public boolean isAdminOrManager() {
        return role.isAdminOrManager();
    }

    public boolean hasRole(Role... roles) {
        for (Role r : roles) {
            if (role == r) {
                return true;
            }
        }
        return false;
    }
}
