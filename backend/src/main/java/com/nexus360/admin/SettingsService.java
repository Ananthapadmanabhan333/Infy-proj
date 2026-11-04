package com.nexus360.admin;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runtime-tunable settings (duplicate thresholds, auto-assignment, AI toggle) with a small read-through cache. */
@Service
@RequiredArgsConstructor
public class SettingsService {

    public static final String DUPLICATE_THRESHOLD = "incident.duplicate.threshold";
    public static final String DUPLICATE_SUGGESTION_THRESHOLD = "incident.duplicate.suggestion_threshold";
    public static final String DUPLICATE_WINDOW_HOURS = "incident.duplicate.window_hours";
    public static final String DUPLICATE_AUTO_LINK = "incident.duplicate.auto_link";
    public static final String MAJOR_REPORT_THRESHOLD = "incident.major.report_threshold";
    public static final String AUTO_ASSIGN = "incident.assignment.auto";
    public static final String AI_LLM_ENABLED = "ai.llm.enabled";
    public static final String SLA_SEVERE_OVERRUN_PERCENT = "sla.escalation.severe_overrun_percent";
    public static final String ORG_NAME = "org.name";
    public static final String ORG_SUPPORT_EMAIL = "org.support_email";
    public static final String ORG_TIMEZONE = "org.timezone";
    public static final String ORG_BUSINESS_HOURS_NOTE = "org.business_hours_note";
    public static final String SLA_AT_RISK_NOTIFY_MANAGERS = "notifications.sla_at_risk.notify_managers";
    public static final String MAJOR_NOTIFY_ALL_STAFF = "notifications.major.notify_all_staff";

    public record SettingDto(String key, String value, String type, String description, Instant updatedAt) {}

    private final SystemSettingRepository repository;
    private final AuditService audit;
    private final Clock clock;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public double getDouble(String key, double fallback) {
        String v = raw(key);
        try {
            return v == null ? fallback : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public int getInt(String key, int fallback) {
        return (int) Math.round(getDouble(key, fallback));
    }

    public boolean getBoolean(String key, boolean fallback) {
        String v = raw(key);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }

    @Transactional(readOnly = true)
    public List<SettingDto> list() {
        return repository.findAllByOrderByKeyAsc().stream()
                .map(s -> new SettingDto(s.getKey(), s.getValue(), s.getType(), s.getDescription(), s.getUpdatedAt()))
                .toList();
    }

    @Transactional
    public SettingDto update(String key, String value) {
        SystemSetting setting = repository.findById(key).orElseThrow(() -> ApiException.notFound("Setting", key));
        String normalized = value == null ? "" : value.trim();
        switch (setting.getType()) {
            case "NUMBER" -> {
                try {
                    double d = Double.parseDouble(normalized);
                    if (key.endsWith("threshold") && key.startsWith("incident.duplicate") && (d < 0.05 || d > 0.99)) {
                        throw ApiException.badRequest("Similarity thresholds must be between 0.05 and 0.99");
                    }
                    if (d < 0) {
                        throw ApiException.badRequest("Value must not be negative");
                    }
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("Value must be a number");
                }
            }
            case "BOOLEAN" -> {
                if (!normalized.equals("true") && !normalized.equals("false")) {
                    throw ApiException.badRequest("Value must be true or false");
                }
            }
            default -> {
                if (normalized.length() > 1000) {
                    throw ApiException.badRequest("Value is too long");
                }
            }
        }
        validateKnownKey(key, normalized);
        String previous = setting.getValue();
        setting.setValue(normalized);
        setting.setUpdatedAt(clock.instant());
        setting.setUpdatedById(CurrentActor.orSystem().id());
        cache.put(key, normalized);
        audit.record("SETTING_UPDATED", "SETTING", key, Map.of("from", previous, "to", normalized));
        audit.record("SETTINGS_CHANGED", "SETTING", key, Map.of("from", previous, "to", normalized));
        return new SettingDto(setting.getKey(), setting.getValue(), setting.getType(), setting.getDescription(), setting.getUpdatedAt());
    }

    public String getString(String key, String fallback) {
        String v = raw(key);
        return v == null ? fallback : v;
    }

    /** Value rules of the organisation settings (other keys are checked by type only). */
    private static void validateKnownKey(String key, String value) {
        switch (key) {
            case ORG_NAME -> {
                if (value.isBlank() || value.length() > 120) {
                    throw ApiException.badRequest("Organisation name must be 1-120 characters");
                }
            }
            case ORG_SUPPORT_EMAIL -> {
                if (!value.isEmpty() && (value.length() > 160 || !value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
                    throw ApiException.badRequest("Support e-mail must be a valid e-mail address (or empty)");
                }
            }
            case ORG_TIMEZONE -> {
                try {
                    java.time.ZoneId.of(value);
                } catch (java.time.DateTimeException e) {
                    throw ApiException.badRequest("Unknown time zone '" + value + "' (use an IANA name such as Asia/Kolkata)");
                }
            }
            case ORG_BUSINESS_HOURS_NOTE -> {
                if (value.length() > 300) {
                    throw ApiException.badRequest("Business hours note must be at most 300 characters");
                }
            }
            case "alert.escalation.min_severity", "alert.notify.min_severity" -> {
                if (!java.util.Set.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO").contains(value)) {
                    throw ApiException.badRequest("Severity must be CRITICAL, MAJOR, MINOR, WARNING or INFO");
                }
            }
            default -> { }
        }
    }

    private String raw(String key) {
        return cache.computeIfAbsent(key, k -> repository.findById(k).map(SystemSetting::getValue).orElse(null));
    }
}
