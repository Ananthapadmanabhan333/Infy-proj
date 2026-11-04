package com.nexus360.incident;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.security.Actor;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration of the fixed incident category set: names, descriptions, default department, extra classifier
 * keywords and the active flag. Read on every classification (nine rows), so changes apply immediately on all instances.
 */
@Service
@RequiredArgsConstructor
public class IncidentCategoryService {

    public record CategoryConfigDto(Category code, String name, String description, Long defaultDepartmentId,
                                    String defaultDepartmentName, List<String> keywords, boolean active) {}

    public record CategoryConfigRequest(
            @NotNull Category code,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 500) String description,
            Long defaultDepartmentId,
            @Size(max = 50) List<@NotBlank @Size(max = 40) String> keywords,
            boolean active) {}

    private final IncidentCategorySettingRepository repository;
    private final DepartmentRepository departments;
    private final AuditService audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<CategoryConfigDto> list() {
        return repository.findAllByOrderBySortOrderAsc().stream().map(IncidentCategoryService::dto).toList();
    }

    @Transactional
    public List<CategoryConfigDto> update(List<CategoryConfigRequest> requests, Actor actor) {
        if (requests == null || requests.isEmpty()) {
            throw ApiException.badRequest("No categories given");
        }
        Map<Category, IncidentCategorySetting> byCode = repository.findAllByOrderBySortOrderAsc().stream()
                .collect(Collectors.toMap(IncidentCategorySetting::getCode, s -> s));
        if (requests.stream().map(CategoryConfigRequest::code).distinct().count() != requests.size()) {
            throw ApiException.badRequest("Each category may appear only once");
        }
        Map<String, Object> changed = new LinkedHashMap<>();
        for (CategoryConfigRequest r : requests) {
            IncidentCategorySetting s = byCode.get(r.code());
            if (s == null) {
                throw ApiException.badRequest("Unknown category " + r.code());
            }
            if (r.code() == Category.OTHER && !r.active()) {
                throw ApiException.badRequest("OTHER is the fallback category and cannot be deactivated");
            }
            Department department = r.defaultDepartmentId() == null ? null : departments.findById(r.defaultDepartmentId())
                    .orElseThrow(() -> ApiException.badRequest("Department " + r.defaultDepartmentId() + " does not exist"));
            String keywords = r.keywords() == null ? "" : r.keywords().stream().map(k -> k.trim().toLowerCase(Locale.ROOT))
                    .filter(k -> !k.isEmpty()).distinct().collect(Collectors.joining(","));
            if (keywords.length() > 1000) {
                throw ApiException.badRequest("Too many keywords for " + r.code());
            }
            s.setName(r.name().trim());
            s.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
            s.setDefaultDepartment(department);
            s.setKeywords(keywords.isEmpty() ? null : keywords);
            s.setActive(r.active());
            s.setUpdatedById(actor.id());
            s.setUpdatedAt(clock.instant());
            changed.put(r.code().name(), Map.of("active", r.active(), "keywords", keywords,
                    "defaultDepartment", department == null ? "" : department.getCode()));
        }
        repository.flush();
        audit.record("INCIDENT_CATEGORIES_UPDATED", "INCIDENT_CATEGORY", null, changed);
        audit.record("SETTINGS_CHANGED", "INCIDENT_CATEGORY", null, Map.of("categories", List.copyOf(changed.keySet())));
        return list();
    }

    /** Classifier configuration: extra keywords per active category and the set of inactive categories. */
    @Transactional(readOnly = true)
    public IncidentClassifier.Rules rules() {
        Map<Category, List<String>> extra = new EnumMap<>(Category.class);
        Set<Category> inactive = EnumSet.noneOf(Category.class);
        for (IncidentCategorySetting s : repository.findAll()) {
            if (!s.isActive()) {
                inactive.add(s.getCode());
            } else if (!s.keywordList().isEmpty()) {
                extra.put(s.getCode(), s.keywordList());
            }
        }
        return new IncidentClassifier.Rules(extra, inactive);
    }

    @Transactional(readOnly = true)
    public Optional<Department> defaultDepartment(Category category) {
        if (category == null) {
            return Optional.empty();
        }
        return repository.findById(category).map(IncidentCategorySetting::getDefaultDepartment)
                .flatMap(d -> departments.findById(d.getId()));
    }

    /** Rejects a category an administrator has deactivated (it is hidden from pickers). */
    @Transactional(readOnly = true)
    public void requireActive(Category category) {
        if (category != null && repository.findById(category).map(s -> !s.isActive()).orElse(false)) {
            throw ApiException.badRequest("Category " + category + " is not active");
        }
    }

    private static CategoryConfigDto dto(IncidentCategorySetting s) {
        return new CategoryConfigDto(s.getCode(), s.getName(), s.getDescription(),
                s.getDefaultDepartment() == null ? null : s.getDefaultDepartment().getId(),
                s.getDefaultDepartment() == null ? null : s.getDefaultDepartment().getName(), s.keywordList(), s.isActive());
    }
}
