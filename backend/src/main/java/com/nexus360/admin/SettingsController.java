package com.nexus360.admin;

import com.nexus360.admin.SettingsService.SettingDto;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Administration")
@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
@PreAuthorize(Roles.SETTINGS)
public class SettingsController {

    public record SettingUpdate(@NotNull @Size(max = 1000) String value) {}

    private final SettingsService service;

    @GetMapping
    public List<SettingDto> list() {
        return service.list();
    }

    @PutMapping("/{key}")
    public SettingDto update(@PathVariable String key, @Valid @RequestBody SettingUpdate request) {
        return service.update(key, request.value());
    }
}
