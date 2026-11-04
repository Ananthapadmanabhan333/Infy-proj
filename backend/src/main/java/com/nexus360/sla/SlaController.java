package com.nexus360.sla;

import com.nexus360.common.PageResponse;
import com.nexus360.incident.IncidentDtos.IncidentSummary;
import com.nexus360.security.Roles;
import com.nexus360.sla.SlaDtos.CalendarDto;
import com.nexus360.sla.SlaDtos.CalendarRequest;
import com.nexus360.sla.SlaDtos.HolidayRequest;
import com.nexus360.sla.SlaDtos.SlaOverview;
import com.nexus360.sla.SlaDtos.SlaPolicyDto;
import com.nexus360.sla.SlaDtos.SlaPolicyRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "SLA")
@RestController
@RequestMapping("/api/sla")
@RequiredArgsConstructor
public class SlaController {

    private final SlaAdminService service;

    @PreAuthorize(Roles.READERS)
    @GetMapping("/overview")
    public SlaOverview overview(@RequestParam(defaultValue = "30") int days, @RequestParam(defaultValue = "false") boolean includeSimulated) {
        return service.overview(days, includeSimulated);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/active")
    public PageResponse<IncidentSummary> active(@RequestParam(required = false) String health, @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return service.active(health, page, size);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/policies")
    public List<SlaPolicyDto> policies() {
        return service.policies();
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    public SlaPolicyDto createPolicy(@Valid @RequestBody SlaPolicyRequest request) {
        return service.savePolicy(null, request);
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PutMapping("/policies/{id}")
    public SlaPolicyDto updatePolicy(@PathVariable Long id, @Valid @RequestBody SlaPolicyRequest request) {
        return service.savePolicy(id, request);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/calendars")
    public List<CalendarDto> calendars() {
        return service.calendars();
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PutMapping("/calendars/{id}")
    public CalendarDto updateCalendar(@PathVariable Long id, @Valid @RequestBody CalendarRequest request) {
        return service.updateCalendar(id, request);
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PostMapping("/calendars/{id}/holidays")
    public CalendarDto addHoliday(@PathVariable Long id, @Valid @RequestBody HolidayRequest request) {
        return service.addHoliday(id, request);
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @DeleteMapping("/calendars/{id}/holidays/{holidayId}")
    public CalendarDto removeHoliday(@PathVariable Long id, @PathVariable Long holidayId) {
        return service.removeHoliday(id, holidayId);
    }

    @PreAuthorize(Roles.CONFIG_ADMINS)
    @PostMapping("/evaluate")
    public SlaEngine.EvaluationResult evaluate() {
        return service.evaluateNow();
    }
}
