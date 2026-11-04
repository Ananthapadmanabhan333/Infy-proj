package com.nexus360.simulation;

import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulation mode. {@code GET /status} is open to every signed-in user (the global SIMULATION banner); runs are readable by READERS;
 * the mode, scenarios and the purge are SIMULATION_ADMINS (system administrators) only and audited.
 */
@Tag(name = "Simulation mode")
@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
public class SimulationController {

    public record ModeRequest(@NotNull Boolean enabled) {}

    private final SimulationService simulation;

    @Operation(summary = "Simulation mode status, scenarios and stored simulated records (every signed-in user: global banner)")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/status")
    public SimulationService.StatusDto status() {
        return simulation.status();
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/runs")
    public List<SimulationService.RunDto> runs(@RequestParam(required = false) Integer limit) {
        return simulation.runs(limit);
    }

    @Operation(summary = "Switch simulation mode on or off (switching off stops running scenarios)")
    @PreAuthorize(Roles.SIMULATION_ADMINS)
    @PutMapping("/mode")
    public SimulationService.StatusDto mode(@Valid @RequestBody ModeRequest request) {
        return simulation.setMode(request.enabled(), CurrentActor.require());
    }

    @Operation(summary = "Start a scenario (BASELINE, PAYROLL_DB_SATURATION, DEPLOY_REGRESSION, ...)")
    @PreAuthorize(Roles.SIMULATION_ADMINS)
    @PostMapping("/scenarios/{code}/start")
    public SimulationService.RunDto start(@PathVariable String code) {
        return simulation.start(code, CurrentActor.require());
    }

    @PreAuthorize(Roles.SIMULATION_ADMINS)
    @PostMapping("/scenarios/{code}/stop")
    public SimulationService.RunDto stop(@PathVariable String code) {
        return simulation.stop(code, CurrentActor.require());
    }

    @Operation(summary = "Delete every simulated record (simulated = true) and stop running scenarios")
    @PreAuthorize(Roles.SIMULATION_ADMINS)
    @DeleteMapping("/data")
    public Map<String, Long> purge() {
        return simulation.purge(CurrentActor.require());
    }
}
