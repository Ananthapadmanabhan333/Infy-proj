package com.nexus360.admin;

import com.nexus360.ai.LlmProvider;
import com.nexus360.common.ApiException;
import com.nexus360.config.AppProperties;
import com.nexus360.incident.IncidentCategoryService;
import com.nexus360.incident.IncidentCategoryService.CategoryConfigDto;
import com.nexus360.incident.IncidentCategoryService.CategoryConfigRequest;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Administration")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    /** AI configuration as seen by administrators. API keys are configured only in the environment and never returned. */
    public record AiConfigDto(String provider, String model, boolean keyConfigured, boolean llmEnabled, int rateLimitPerMinute,
                              int timeoutSeconds) {}

    private final IncidentCategoryService categories;
    private final SettingsService settings;
    private final SystemHealthService health;
    private final LlmProvider llm;
    private final AppProperties properties;
    private final Validator validator;

    @PreAuthorize(Roles.READERS)
    @GetMapping("/incident-categories")
    public List<CategoryConfigDto> incidentCategories() {
        return categories.list();
    }

    @Operation(summary = "Replace the configurable attributes of the incident categories (full list); unknown codes are rejected")
    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PutMapping("/incident-categories")
    public List<CategoryConfigDto> updateIncidentCategories(@RequestBody List<CategoryConfigRequest> request) {
        if (request == null || request.isEmpty() || request.size() > 20) {
            throw ApiException.badRequest("Send the list of categories (1-20 entries)");
        }
        Set<ConstraintViolation<?>> violations = new HashSet<>();
        for (CategoryConfigRequest r : request) {
            if (r == null) {
                throw ApiException.badRequest("Category entries must not be null");
            }
            violations.addAll(validator.validate(r));
        }
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return categories.update(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.SETTINGS)
    @GetMapping("/ai")
    public AiConfigDto ai() {
        return aiConfig();
    }

    @Operation(summary = "Switch LLM use on or off: {\"llmEnabled\": boolean}. No other field (in particular no API key) is accepted.")
    @PreAuthorize(Roles.SETTINGS)
    @PutMapping("/ai")
    public AiConfigDto updateAi(@RequestBody Map<String, Object> request) {
        if (request == null || request.size() != 1 || !(request.get("llmEnabled") instanceof Boolean enabled)) {
            throw ApiException.badRequest("Only {\"llmEnabled\": true|false} can be changed here; API keys are configured in the server environment");
        }
        settings.update(SettingsService.AI_LLM_ENABLED, String.valueOf(enabled));
        return aiConfig();
    }

    @PreAuthorize(Roles.HEALTH_READERS)
    @GetMapping("/system-health")
    public SystemHealthService.SystemHealth systemHealth() {
        return health.health();
    }

    private AiConfigDto aiConfig() {
        return new AiConfigDto(llm.name(), llm.model(), properties.ai().hasApiKey(), settings.getBoolean(SettingsService.AI_LLM_ENABLED, true),
                properties.rateLimit().aiPerMinute(), properties.ai().timeoutSeconds());
    }
}
