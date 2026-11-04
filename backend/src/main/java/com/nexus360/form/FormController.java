package com.nexus360.form;

import com.nexus360.common.PageResponse;
import com.nexus360.form.FormService.FormDetail;
import com.nexus360.form.FormService.FormRequest;
import com.nexus360.form.FormService.FormSummary;
import com.nexus360.form.FormService.ValidateRequest;
import com.nexus360.form.FormService.ValidationResult;
import com.nexus360.form.FormService.VersionDetail;
import com.nexus360.form.FormService.VersionDto;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Forms")
@RestController
@RequestMapping("/api/forms")
@RequiredArgsConstructor
public class FormController {

    private final FormService service;

    @PreAuthorize(Roles.READERS)
    @GetMapping
    public PageResponse<FormSummary> list(@RequestParam(required = false) String search, @RequestParam(required = false) Boolean template,
                                          @RequestParam(required = false) Boolean active, @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return service.list(search, template, active, page, size, sort);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}")
    public FormDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}/versions")
    public List<VersionDto> versions(@PathVariable Long id) {
        return service.versions(id);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}/versions/{version}")
    public VersionDetail version(@PathVariable Long id, @PathVariable int version) {
        return service.version(id, version);
    }

    @PreAuthorize(Roles.FORM_EDITORS)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FormDetail create(@Valid @RequestBody FormRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.FORM_EDITORS)
    @PutMapping("/{id}")
    public FormDetail update(@PathVariable Long id, @Valid @RequestBody FormRequest request) {
        return service.update(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @PostMapping("/{id}/validate")
    public ValidationResult validate(@PathVariable Long id, @RequestBody ValidateRequest request) {
        return service.validate(id, request.formData());
    }
}
