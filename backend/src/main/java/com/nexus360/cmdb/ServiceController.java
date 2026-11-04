package com.nexus360.cmdb;

import com.nexus360.cmdb.ServiceCatalogDtos.ServiceDetail;
import com.nexus360.cmdb.ServiceCatalogDtos.ServiceDto;
import com.nexus360.cmdb.ServiceCatalogDtos.BusinessServiceRequest;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Business services")
@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceHealthService service;

    @GetMapping
    public List<ServiceDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ServiceDetail get(@PathVariable Long id) {
        return service.detail(id);
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceDto create(@Valid @RequestBody BusinessServiceRequest request) {
        return service.save(null, request);
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PutMapping("/{id}")
    public ServiceDto update(@PathVariable Long id, @Valid @RequestBody BusinessServiceRequest request) {
        return service.save(id, request);
    }
}
