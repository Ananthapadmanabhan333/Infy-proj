package com.nexus360.ai;

import com.nexus360.ai.AiDtos.WorkflowSuggestion;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.common.ApiException;
import com.nexus360.incident.Category;
import com.nexus360.incident.Priority;
import com.nexus360.runbook.Runbook;
import com.nexus360.runbook.RunbookRepository;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.Role;
import com.nexus360.workflow.WorkflowModel.ActionDef;
import com.nexus360.workflow.WorkflowModel.ActionType;
import com.nexus360.workflow.WorkflowModel.ConditionRule;
import com.nexus360.workflow.WorkflowModel.Conditions;
import com.nexus360.workflow.WorkflowModel.Definition;
import com.nexus360.workflow.WorkflowModel.Operator;
import com.nexus360.workflow.WorkflowModel.Schedule;
import com.nexus360.workflow.WorkflowModel.Trigger;
import com.nexus360.workflow.WorkflowService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns a plain-language goal into a workflow proposal. {@link #deterministic} recognises triggers, conditions and actions
 * by keywords and the live catalogue (services, departments, runbooks); {@link #validate} checks any proposal — LLM or
 * deterministic — against the workflow model before it is returned. Proposals are never saved automatically.
 */
@Component
@RequiredArgsConstructor
public class WorkflowSuggester {

    private static final Pattern EVERY_MINUTES = Pattern.compile("every\\s+(\\d{1,4})\\s*(min|minute|minutes|h|hour|hours)\\b");

    private final WorkflowService workflows;
    private final BusinessServiceRepository services;
    private final DepartmentRepository departments;
    private final RunbookRepository runbooks;

    /** Throws {@link LlmException} (INVALID_OUTPUT) when the proposal does not fit the workflow model. */
    public WorkflowSuggestion validate(WorkflowSuggestion s) {
        if (s.triggerEvent() == null || s.definition() == null) {
            throw new LlmException(LlmException.Reason.INVALID_OUTPUT, "Workflow proposal without trigger or definition");
        }
        try {
            workflows.validate(s.definition(), s.triggerEvent());
        } catch (ApiException e) {
            throw new LlmException(LlmException.Reason.INVALID_OUTPUT, "Workflow proposal is invalid: " + e.getMessage());
        }
        return s;
    }

    /** Model reference for the LLM prompt: the workflow vocabulary plus the live codes it may reference. */
    public String meta() {
        WorkflowService.Meta m = workflows.meta();
        StringBuilder sb = new StringBuilder();
        sb.append("Triggers: ").append(m.triggers()).append('\n');
        sb.append("Condition fields: ").append(m.fields().stream().map(f -> f.field() + (f.values() == null ? "" : " " + f.values())).toList()).append('\n');
        sb.append("Operators: ").append(m.operators()).append('\n');
        sb.append("Actions: ").append(m.actions().stream().map(a -> a.type() + " params " + a.params().stream()
                .map(p -> p.key() + (p.required() ? "*" : "") + (p.options() == null ? "" : " " + p.options())).toList()).toList()).append('\n');
        sb.append("Department codes: ").append(departments.findAllByOrderByNameAsc().stream().map(d -> d.getCode() + " (" + d.getName() + ")").toList()).append('\n');
        sb.append("Service codes: ").append(services.findAllByOrderByTierAscNameAsc().stream().map(s -> s.getCode() + " (" + s.getName() + ")").toList()).append('\n');
        sb.append("Runbooks (runbookId): ").append(runbooks.findAllByOrderByTitleAsc().stream().filter(Runbook::isActive)
                .map(r -> r.getId() + " = " + r.getTitle()).toList()).append('\n');
        sb.append("Message templates may use {{number}}, {{title}}, {{priority}}, {{status}}, {{service}}. SCHEDULED needs intervalMinutes >= 15.");
        return sb.toString();
    }

    /** Converts the LLM's structured output; any unknown enum value is a validation failure (the caller falls back). */
    public WorkflowSuggestion fromLlm(com.nexus360.ai.LlmSchemas.WorkflowSuggestion out) {
        if (out == null || out.triggerEvent == null) {
            throw new LlmException(LlmException.Reason.INVALID_OUTPUT, "No trigger in workflow proposal");
        }
        Trigger trigger = parse(Trigger.class, out.triggerEvent, "trigger");
        List<ConditionRule> rules = new ArrayList<>();
        for (var r : out.rules == null ? List.<com.nexus360.ai.LlmSchemas.WorkflowRule>of() : out.rules) {
            rules.add(new ConditionRule(r.field == null ? "" : r.field.trim(), parse(Operator.class, r.operator, "operator"),
                    r.value == null ? "" : r.value.trim()));
        }
        String match = out.match == null || out.match.isBlank() ? "ALL" : out.match.trim().toUpperCase(Locale.ROOT);
        Definition definition = new Definition(new Conditions(match, rules), actions(out.actions), actions(out.elseActions),
                trigger == Trigger.SCHEDULED ? new Schedule(Math.max(15, out.intervalMinutes)) : null, null);
        String name = out.name == null || out.name.isBlank() ? "Suggested workflow" : truncate(out.name.trim(), 150);
        return new WorkflowSuggestion(name, out.description == null ? null : truncate(out.description.trim(), 1000), trigger, definition,
                out.explanation == null || out.explanation.isBlank() ? "Proposed by the LLM; review before saving." : out.explanation.trim());
    }

    private static List<ActionDef> actions(List<com.nexus360.ai.LlmSchemas.WorkflowAction> list) {
        List<ActionDef> actions = new ArrayList<>();
        for (var a : list == null ? List.<com.nexus360.ai.LlmSchemas.WorkflowAction>of() : list) {
            Map<String, String> params = new LinkedHashMap<>();
            for (var p : a.params == null ? List.<com.nexus360.ai.LlmSchemas.WorkflowParam>of() : a.params) {
                if (p.key != null && p.value != null) {
                    params.put(p.key.trim(), p.value.trim());
                }
            }
            actions.add(new ActionDef(parse(ActionType.class, a.type, "action type"), params));
        }
        return actions;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String what) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.Reason.INVALID_OUTPUT, "Invalid " + what + " '" + value + "'");
        }
    }

    public WorkflowSuggestion deterministic(String goal) {
        String g = " " + goal.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + " ";
        List<String> notes = new ArrayList<>();

        Trigger trigger;
        Schedule schedule = null;
        Matcher every = EVERY_MINUTES.matcher(g);
        if (every.find() || g.contains(" hourly ") || g.contains(" daily ") || g.contains(" stale ") || g.contains(" pending for ")
                || g.contains(" periodically ") || g.contains(" reminder")) {
            trigger = Trigger.SCHEDULED;
            int minutes = 60;
            if (every.find(0)) {
                int n = Integer.parseInt(every.group(1));
                minutes = every.group(2).startsWith("h") ? n * 60 : n;
            } else if (g.contains(" daily ")) {
                minutes = 1440;
            }
            schedule = new Schedule(Math.max(15, Math.min(10_080, minutes)));
            notes.add("runs every " + schedule.intervalMinutes() + " minutes against active incidents");
        } else if (g.contains("breach")) {
            trigger = Trigger.SLA_BREACHED;
        } else if (g.contains("at risk") || g.contains("at-risk")) {
            trigger = Trigger.SLA_AT_RISK;
        } else if (g.contains("priority") && (g.contains("change") || g.contains("raised") || g.contains("increase") || g.contains("upgrade"))) {
            trigger = Trigger.PRIORITY_CHANGED;
        } else if (g.contains("service request") || g.contains(" request ") || g.contains("requests")) {
            trigger = Trigger.SERVICE_REQUEST_CREATED;
        } else if (g.contains(" assigned ")) {
            trigger = Trigger.INCIDENT_ASSIGNED;
        } else if (g.contains("status") || g.contains(" resolved ") || g.contains(" reopened ")) {
            trigger = Trigger.INCIDENT_STATUS_CHANGED;
        } else if (g.contains(" updated ") || g.contains(" changes ")) {
            trigger = Trigger.INCIDENT_UPDATED;
        } else {
            trigger = Trigger.INCIDENT_CREATED;
        }
        notes.add("trigger " + trigger);

        List<ConditionRule> rules = new ArrayList<>();
        priority(g).ifPresent(p -> {
            rules.add(new ConditionRule("priority", Operator.EQUALS, p.name()));
            notes.add("only " + p.name().toLowerCase(Locale.ROOT) + " priority");
        });
        Optional<BusinessService> service = services.findAllByOrderByTierAscNameAsc().stream()
                .filter(s -> g.contains(" " + s.getName().toLowerCase(Locale.ROOT)) || g.contains(" " + s.getCode().toLowerCase(Locale.ROOT) + " "))
                .findFirst();
        service.ifPresent(s -> {
            rules.add(new ConditionRule("serviceCode", Operator.EQUALS, s.getCode()));
            notes.add("service " + s.getName());
        });
        if (service.isEmpty() && trigger != Trigger.SERVICE_REQUEST_CREATED) {
            category(g).ifPresent(c -> {
                rules.add(new ConditionRule("category", Operator.EQUALS, c.name()));
                notes.add("category " + c.name().toLowerCase(Locale.ROOT));
            });
        }
        if (trigger == Trigger.SCHEDULED && (g.contains("pending") || g.contains("stale") || g.contains("waiting"))) {
            rules.add(new ConditionRule("status", Operator.EQUALS, "PENDING"));
            notes.add("incidents in Pending");
        }
        if (g.contains("major")) {
            rules.add(new ConditionRule("major", Operator.EQUALS, "true"));
        }

        List<ActionDef> actions = new ArrayList<>();
        Optional<Department> department = departments.findAllByOrderByNameAsc().stream()
                .filter(d -> g.contains(" " + d.getName().toLowerCase(Locale.ROOT)) || g.contains(" " + d.getCode().toLowerCase(Locale.ROOT) + " "))
                .findFirst();
        if (g.contains("assign") || g.contains("route")) {
            department.ifPresent(d -> actions.add(new ActionDef(ActionType.ASSIGN_DEPARTMENT, Map.of("departmentCode", d.getCode()))));
        }
        if (g.contains("escalat")) {
            actions.add(new ActionDef(ActionType.ESCALATE, Map.of("reason", "Escalated by workflow: " + truncate(goal.trim(), 120))));
        }
        if (g.contains("declare major") || g.contains("major incident")) {
            actions.add(new ActionDef(ActionType.DECLARE_MAJOR, Map.of()));
        }
        if (g.contains("runbook")) {
            runbookFor(g, service).ifPresent(r -> actions.add(new ActionDef(ActionType.START_RUNBOOK, Map.of("runbookId", String.valueOf(r.getId())))));
        }
        Role role = role(g);
        if (role != null) {
            actions.add(new ActionDef(ActionType.NOTIFY_ROLE, Map.of("role", role.name(), "message", "{{priority}} {{number}}: {{title}}")));
        }
        if (g.contains("assignee") || (trigger == Trigger.SCHEDULED && actions.isEmpty())) {
            actions.add(new ActionDef(ActionType.NOTIFY_ASSIGNEE, Map.of("message", "Workflow reminder for {{number}}: {{title}}")));
        }
        if ((g.contains("team") || g.contains("department")) && !g.contains("assign")) {
            Map<String, String> params = new LinkedHashMap<>();
            department.ifPresent(d -> params.put("departmentCode", d.getCode()));
            params.put("message", "{{number}} needs attention: {{title}}");
            actions.add(new ActionDef(ActionType.NOTIFY_DEPARTMENT, params));
        }
        if (g.contains("comment") || g.contains("note")) {
            actions.add(new ActionDef(ActionType.ADD_COMMENT, Map.of("message", "Automated note for {{number}}.", "internal", "true")));
        }
        if (g.contains("set priority") || g.contains("raise priority") || g.contains("make it")) {
            priority(g).ifPresent(p -> actions.add(new ActionDef(ActionType.SET_PRIORITY, Map.of("priority", p.name()))));
        }
        if (actions.isEmpty()) {
            actions.add(new ActionDef(ActionType.NOTIFY_DEPARTMENT, Map.of("message", "{{number}} matched the workflow: {{title}}")));
            notes.add("no action was recognised, so it notifies the owning team — adjust before saving");
        }
        Definition definition = new Definition(new Conditions("ALL", rules), actions.stream().limit(20).toList(), List.of(), schedule, null);
        String name = truncate(capitalise(goal.trim().replaceAll("[.!?]+$", "")), 150);
        WorkflowSuggestion suggestion = new WorkflowSuggestion(name, "Suggested from: " + truncate(goal.trim(), 900), trigger, definition,
                "Keyword-based proposal: " + String.join("; ", notes) + "; actions " + actions.stream().map(a -> a.type().name()).toList()
                        + ". Review every step before saving.");
        return validate(suggestion);
    }

    private Optional<Runbook> runbookFor(String g, Optional<BusinessService> service) {
        List<Runbook> active = runbooks.findAllByOrderByTitleAsc().stream().filter(Runbook::isActive).toList();
        Optional<Runbook> byService = service.flatMap(s -> active.stream()
                .filter(r -> r.getService() != null && r.getService().getId().equals(s.getId())).findFirst());
        return byService.or(() -> active.stream().filter(r -> g.contains(r.getTitle().toLowerCase(Locale.ROOT))).findFirst());
    }

    static Optional<Priority> priority(String g) {
        if (g.contains("critical") || g.contains(" p1 ")) {
            return Optional.of(Priority.CRITICAL);
        }
        if (g.contains(" high ") || g.contains(" p2 ")) {
            return Optional.of(Priority.HIGH);
        }
        if (g.contains(" medium ") || g.contains(" p3 ")) {
            return Optional.of(Priority.MEDIUM);
        }
        if (g.contains(" low ") || g.contains(" p4 ")) {
            return Optional.of(Priority.LOW);
        }
        return Optional.empty();
    }

    static Optional<Category> category(String g) {
        Map<String, Category> terms = new LinkedHashMap<>();
        terms.put("network", Category.NETWORK);
        terms.put("vpn", Category.NETWORK);
        terms.put("database", Category.DATABASE);
        terms.put("email", Category.EMAIL);
        terms.put("security", Category.SECURITY);
        terms.put("phishing", Category.SECURITY);
        terms.put("hardware", Category.HARDWARE);
        terms.put("laptop", Category.HARDWARE);
        terms.put("access", Category.ACCESS);
        terms.put("password", Category.ACCESS);
        terms.put("facilit", Category.FACILITIES);
        terms.put("application", Category.APPLICATION);
        return terms.entrySet().stream().filter(e -> g.contains(e.getKey())).map(Map.Entry::getValue).findFirst();
    }

    static Role role(String g) {
        if (g.contains("incident manager")) {
            return Role.INCIDENT_MANAGER;
        }
        if (g.contains("it manager") || g.contains(" managers") || g.contains(" manager ")) {
            return Role.IT_MANAGER;
        }
        if (g.contains("system admin") || g.contains("administrator") || g.contains(" admins")) {
            return Role.SYSTEM_ADMIN;
        }
        if (g.contains(" agents") || g.contains("support staff")) {
            return Role.SUPPORT_AGENT;
        }
        return null;
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
