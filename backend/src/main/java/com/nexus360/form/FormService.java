package com.nexus360.form;

import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.common.PageResponse;
import com.nexus360.security.Actor;
import com.nexus360.servicedesk.CatalogItem;
import com.nexus360.servicedesk.CatalogItemRepository;
import com.nexus360.servicedesk.FormField;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dynamic form builder: versioned form definitions and templates. Saving changed fields creates a new version; catalogue
 * items always use their form's current version (mirrored into {@code catalog_items.form_fields} for Release 2 clients)
 * and every service request records the form id and version it was submitted with.
 */
@Service
@RequiredArgsConstructor
public class FormService {

    public record FormSummary(Long id, String code, String name, String description, boolean template, boolean active, int currentVersion,
                              int fieldCount, long catalogItemCount, boolean demo, Instant updatedAt) {}

    public record ItemRef(Long id, String name, boolean active) {}

    public record FormDetail(Long id, String code, String name, String description, boolean template, boolean active, int currentVersion,
                             List<FormField> fields, List<ItemRef> catalogItems, boolean demo, String createdByName, Instant createdAt,
                             Instant updatedAt) {}

    public record VersionDto(int version, int fieldCount, String changeNote, String createdByName, Instant createdAt) {}

    public record VersionDetail(int version, List<FormField> fields, String changeNote, String createdByName, Instant createdAt) {}

    /** {@code copyFromId}: start from another form's (e.g. a template's) current fields when {@code fields} is omitted. */
    public record FormRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{2,40}$") String code,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 1000) String description,
            Boolean template,
            Boolean active,
            @Size(max = FormValidator.MAX_FIELDS) List<@Valid FormField> fields,
            @Size(max = 500) String changeNote,
            Long copyFromId) {}

    public record ValidateRequest(Map<String, Object> formData) {}

    public record ValidationResult(boolean valid, Map<String, Object> formData) {}

    /** The form a catalogue item uses now: id and version ({@code null} for an item without form definition) and fields. */
    public record ResolvedForm(Long formId, Integer version, List<FormField> fields) {}

    private final FormDefinitionRepository forms;
    private final FormDefinitionVersionRepository versions;
    private final CatalogItemRepository items;
    private final com.nexus360.user.UserRepository users;
    private final AuditService audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<FormSummary> list(String search, Boolean template, Boolean active, Integer page, Integer size, String sort) {
        Specification<FormDefinition> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (template != null) {
                p.add(cb.equal(root.get("template"), template));
            }
            if (active != null) {
                p.add(cb.equal(root.get("active"), active));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("name")), like, '\\'), cb.like(cb.lower(root.get("code")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<FormDefinition> result = forms.findAll(spec, PageResponse.pageable(page, size,
                sortOf(sort, Set.of("name", "code", "updatedAt", "createdAt"), Sort.by(Sort.Direction.ASC, "name"))));
        List<Long> ids = result.getContent().stream().map(FormDefinition::getId).toList();
        Map<Long, Integer> fieldCounts = new HashMap<>();
        if (!ids.isEmpty()) {
            versions.findCurrent(ids).forEach(v -> fieldCounts.put(v.getForm().getId(), v.getFields().size()));
        }
        Map<Long, Long> itemCounts = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : items.countByForms(ids)) {
                itemCounts.put((Long) row[0], (Long) row[1]);
            }
        }
        return PageResponse.of(result, result.getContent().stream().map(f -> new FormSummary(f.getId(), f.getCode(), f.getName(),
                f.getDescription(), f.isTemplate(), f.isActive(), f.getCurrentVersion(), fieldCounts.getOrDefault(f.getId(), 0),
                itemCounts.getOrDefault(f.getId(), 0L), f.isDemo(), f.getUpdatedAt())).toList());
    }

    @Transactional(readOnly = true)
    public FormDetail get(Long id) {
        FormDefinition f = load(id);
        return detail(f, currentFields(f));
    }

    @Transactional(readOnly = true)
    public List<VersionDto> versions(Long id) {
        load(id);
        return versions.findByFormIdOrderByVersionNumberDesc(id).stream().map(v -> new VersionDto(v.getVersionNumber(), v.getFields().size(),
                v.getChangeNote(), v.getCreatedBy() == null ? null : v.getCreatedBy().getFullName(), v.getCreatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public VersionDetail version(Long id, int number) {
        load(id);
        FormDefinitionVersion v = versions.findByFormIdAndVersionNumber(id, number).orElseThrow(() -> ApiException.notFound("Form version", number));
        return new VersionDetail(v.getVersionNumber(), v.getFields(), v.getChangeNote(), v.getCreatedBy() == null ? null : v.getCreatedBy().getFullName(),
                v.getCreatedAt());
    }

    @Transactional
    public FormDetail create(FormRequest r, Actor actor) {
        if (forms.existsByCodeIgnoreCase(r.code())) {
            throw ApiException.conflict("Form code " + r.code() + " is already in use");
        }
        List<FormField> fields = r.fields();
        if (fields == null && r.copyFromId() != null) {
            fields = currentFields(load(r.copyFromId()));
        }
        fields = fields == null ? List.of() : fields;
        FormValidator.validateDefinition(fields);
        FormDefinition f = new FormDefinition();
        f.setCode(r.code());
        f.setName(r.name().trim());
        f.setDescription(r.description());
        f.setTemplate(Boolean.TRUE.equals(r.template()));
        f.setActive(r.active() == null || r.active());
        f.setCurrentVersion(1);
        f.setCreatedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        forms.save(f);
        saveVersion(f, 1, fields, r.changeNote() == null || r.changeNote().isBlank()
                ? (r.copyFromId() != null ? "Copied from form #" + r.copyFromId() : "Initial version") : r.changeNote(), actor);
        audit.record("FORM_CREATED", "FORM", f.getCode(), Map.of("name", f.getName(), "fields", fields.size(), "template", f.isTemplate()));
        return detail(f, fields);
    }

    @Transactional
    public FormDetail update(Long id, FormRequest r, Actor actor) {
        FormDefinition f = load(id);
        if (!f.getCode().equalsIgnoreCase(r.code())) {
            throw ApiException.invalidField("code", "The code of a form cannot change");
        }
        List<FormField> current = currentFields(f);
        List<FormField> fields = r.fields() == null ? current : r.fields();
        FormValidator.validateDefinition(fields);
        boolean deactivating = r.active() != null && !r.active() && f.isActive();
        if (deactivating && items.findByFormDefinitionIdOrderByNameAsc(id).stream().anyMatch(CatalogItem::isActive)) {
            throw ApiException.conflict("Active catalogue items use this form; link them to another form first");
        }
        f.setName(r.name().trim());
        f.setDescription(r.description());
        if (r.template() != null) {
            f.setTemplate(r.template());
        }
        if (r.active() != null) {
            f.setActive(r.active());
        }
        boolean changed = !Objects.equals(current, fields);
        if (changed) {
            f.setCurrentVersion(f.getCurrentVersion() + 1);
            saveVersion(f, f.getCurrentVersion(), fields, r.changeNote(), actor);
            for (CatalogItem item : items.findByFormDefinitionIdOrderByNameAsc(id)) {
                item.setFormFields(new ArrayList<>(fields));
            }
        }
        f.setUpdatedAt(clock.instant());
        audit.record("FORM_UPDATED", "FORM", f.getCode(), Map.of("version", f.getCurrentVersion(), "newVersion", changed));
        return detail(f, fields);
    }

    /** Dry-run validation of form data against the form's current version (form builder preview). */
    @Transactional(readOnly = true)
    public ValidationResult validate(Long id, Map<String, Object> data) {
        FormDefinition f = load(id);
        return new ValidationResult(true, FormValidator.validate(currentFields(f), data, FormValidator.NO_LOOKUPS, today()));
    }

    // ------------------------------------------------------------------ catalogue integration

    @Transactional(readOnly = true)
    public ResolvedForm resolve(CatalogItem item) {
        FormDefinition f = item.getFormDefinition();
        if (f == null) {
            return new ResolvedForm(null, null, item.getFormFields() == null ? List.of() : item.getFormFields());
        }
        return new ResolvedForm(f.getId(), f.getCurrentVersion(), currentFields(f));
    }

    /**
     * Links a catalogue item to its form after the item was saved: an explicit {@code formDefinitionId} (active form) wins;
     * otherwise the Release 2 {@code formFields} are kept in the item's own form ({@code ITEM-<id>}, new version on change).
     * An item linked to a shared form keeps it while the submitted fields are unchanged.
     */
    @Transactional
    public void attach(CatalogItem item, Long formDefinitionId, List<FormField> legacyFields, Actor actor) {
        if (formDefinitionId != null) {
            FormDefinition f = forms.findById(formDefinitionId).filter(FormDefinition::isActive)
                    .orElseThrow(() -> ApiException.invalidField("formDefinitionId", "Form " + formDefinitionId + " does not exist or is inactive"));
            item.setFormDefinition(f);
            item.setFormFields(new ArrayList<>(currentFields(f)));
            return;
        }
        List<FormField> fields = legacyFields == null ? List.of() : legacyFields;
        FormValidator.validateDefinition(fields);
        FormDefinition current = item.getFormDefinition();
        if (current != null && Objects.equals(currentFields(current), fields)) {
            item.setFormFields(new ArrayList<>(fields));
            return;
        }
        String ownCode = "ITEM-" + item.getId();
        if (current != null && current.getCode().equals(ownCode)) {
            current.setCurrentVersion(current.getCurrentVersion() + 1);
            current.setName(truncate(item.getName() + " form", 150));
            saveVersion(current, current.getCurrentVersion(), fields, "Updated with the catalogue item", actor);
            item.setFormFields(new ArrayList<>(fields));
            audit.record("FORM_UPDATED", "FORM", current.getCode(), Map.of("version", current.getCurrentVersion(), "newVersion", true));
            return;
        }
        item.setFormDefinition(createItemForm(item, fields, actor, current == null ? "Created with the catalogue item"
                : "Forked from form " + current.getCode() + " with changed fields"));
        item.setFormFields(new ArrayList<>(fields));
    }

    /** Gives items without a form definition (created by Release 2 code paths) their own form; returns how many. */
    @Transactional
    public int backfillItems() {
        int n = 0;
        for (CatalogItem item : items.findByFormDefinitionIsNull()) {
            item.setFormDefinition(createItemForm(item, item.getFormFields() == null ? List.of() : item.getFormFields(), Actor.SYSTEM,
                    "Created from the catalogue item's fields"));
            n++;
        }
        return n;
    }

    private FormDefinition createItemForm(CatalogItem item, List<FormField> fields, Actor actor, String note) {
        String code = "ITEM-" + item.getId();
        int suffix = 2;
        while (forms.existsByCodeIgnoreCase(code)) {
            code = "ITEM-" + item.getId() + "-" + suffix++;
        }
        FormDefinition f = new FormDefinition();
        f.setCode(code);
        f.setName(truncate(item.getName() + " form", 150));
        f.setDescription("Request form of the catalogue item “" + item.getName() + "”.");
        f.setDemo(item.isDemo());
        f.setCreatedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        forms.save(f);
        saveVersion(f, 1, fields, note, actor);
        return f;
    }

    public List<FormField> currentFields(FormDefinition f) {
        return versions.findByFormIdAndVersionNumber(f.getId(), f.getCurrentVersion()).map(FormDefinitionVersion::getFields).orElse(List.of());
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    private void saveVersion(FormDefinition f, int number, List<FormField> fields, String note, Actor actor) {
        FormDefinitionVersion v = new FormDefinitionVersion();
        v.setForm(f);
        v.setVersionNumber(number);
        v.setFields(new ArrayList<>(fields));
        v.setChangeNote(note == null || note.isBlank() ? null : truncate(note.trim(), 500));
        v.setCreatedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        v.setCreatedAt(clock.instant());
        versions.save(v);
    }

    private FormDefinition load(Long id) {
        return forms.findById(id).orElseThrow(() -> ApiException.notFound("Form", id));
    }

    private FormDetail detail(FormDefinition f, List<FormField> fields) {
        List<ItemRef> refs = f.getId() == null ? List.of() : items.findByFormDefinitionIdOrderByNameAsc(f.getId()).stream()
                .map(i -> new ItemRef(i.getId(), i.getName(), i.isActive())).toList();
        return new FormDetail(f.getId(), f.getCode(), f.getName(), f.getDescription(), f.isTemplate(), f.isActive(), f.getCurrentVersion(), fields,
                refs, f.isDemo(), f.getCreatedBy() == null ? null : f.getCreatedBy().getFullName(), f.getCreatedAt(), f.getUpdatedAt());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    static Sort sortOf(String sort, Set<String> allowed, Sort fallback) {
        if (sort == null || sort.isBlank()) {
            return fallback;
        }
        String[] parts = sort.split(",");
        if (!allowed.contains(parts[0].trim())) {
            throw ApiException.badRequest("Cannot sort by '" + parts[0].trim() + "'");
        }
        return Sort.by(parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.DESC : Sort.Direction.ASC, parts[0].trim());
    }
}
