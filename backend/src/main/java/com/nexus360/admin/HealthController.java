package com.nexus360.admin;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public liveness summary used by the frontend status indicator (detailed health stays on /actuator/health). */
@Tag(name = "Health")
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbc;

    @GetMapping("/api/health")
    public Map<String, String> health() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("service", "SA API");
        body.put("version", com.nexus360.NexusApplication.VERSION);
        try {
            jdbc.queryForObject("select 1", Integer.class);
            body.put("status", "UP");
            body.put("database", "UP");
        } catch (RuntimeException e) {
            body.put("status", "DEGRADED");
            body.put("database", "DOWN");
        }
        return body;
    }
}
