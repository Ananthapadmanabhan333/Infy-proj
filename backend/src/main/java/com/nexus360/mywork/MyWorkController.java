package com.nexus360.mywork;

import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "My Work")
@RestController
@RequiredArgsConstructor
public class MyWorkController {

    private final MyWorkService service;

    @Operation(summary = "Everything waiting for me: {generatedAt, teams, incidents, teamQueue, requests, teamRequests, approvals, ...}")
    @PreAuthorize(Roles.STAFF)
    @GetMapping("/api/my-work")
    public Map<String, Object> myWork(@RequestParam(required = false) Integer limit) {
        return service.myWork(CurrentActor.require(), limit);
    }
}
