package com.nexus360.portal;

import com.nexus360.security.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Employee portal")
@RestController
@RequiredArgsConstructor
public class PortalController {

    private final PortalService service;

    @Operation(summary = "My open incidents and requests, approvals I am waiting for, service status and recommended articles")
    @GetMapping("/api/portal/overview")
    public Map<String, Object> overview() {
        return service.overview(CurrentActor.require());
    }
}
