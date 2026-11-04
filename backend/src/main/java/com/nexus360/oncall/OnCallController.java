package com.nexus360.oncall;

import com.nexus360.oncall.OnCallDtos.DecisionNote;
import com.nexus360.oncall.OnCallDtos.HandoverDto;
import com.nexus360.oncall.OnCallDtos.HandoverRequest;
import com.nexus360.oncall.OnCallDtos.MyShift;
import com.nexus360.oncall.OnCallDtos.NowEntry;
import com.nexus360.oncall.OnCallDtos.OverrideDto;
import com.nexus360.oncall.OnCallDtos.OverrideRequest;
import com.nexus360.oncall.OnCallDtos.PageDto;
import com.nexus360.oncall.OnCallDtos.PolicyDto;
import com.nexus360.oncall.OnCallDtos.PolicyRequest;
import com.nexus360.oncall.OnCallDtos.ScheduleDetail;
import com.nexus360.oncall.OnCallDtos.ScheduleRequest;
import com.nexus360.oncall.OnCallDtos.ScheduleSummary;
import com.nexus360.oncall.OnCallDtos.ShiftsResponse;
import com.nexus360.oncall.OnCallDtos.UnavailabilityDto;
import com.nexus360.oncall.OnCallDtos.UnavailabilityRequest;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

/** On-call schedules, who is on call, overrides, availability, handovers, escalation policies and pages. */
@Tag(name = "On-call")
@RestController
@RequestMapping("/api/oncall")
@RequiredArgsConstructor
public class OnCallController {

    private final OnCallService service;

    @Operation(summary = "Who is on call now (or at 'at') for every active schedule, optionally of one team")
    @PreAuthorize(Roles.READERS)
    @GetMapping("/now")
    public List<NowEntry> now(@RequestParam(required = false) Long teamId,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        return service.onCall(teamId, at);
    }

    @PreAuthorize(Roles.STAFF)
    @GetMapping("/my-shifts")
    public List<MyShift> myShifts(@RequestParam(required = false) Integer days) {
        return service.myShifts(CurrentActor.require().id(), days == null ? 14 : days, 100);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/schedules")
    public List<ScheduleSummary> schedules(@RequestParam(required = false) Long teamId, @RequestParam(required = false) Boolean active) {
        return service.list(teamId, active);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/schedules/{id}")
    public ScheduleDetail schedule(@PathVariable Long id) {
        return service.get(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.ONCALL_EDITORS)
    @PostMapping("/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleDetail create(@Valid @RequestBody ScheduleRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ONCALL_EDITORS)
    @PutMapping("/schedules/{id}")
    public ScheduleDetail update(@PathVariable Long id, @Valid @RequestBody ScheduleRequest request) {
        return service.update(id, request, CurrentActor.require());
    }

    @Operation(summary = "Computed shifts of both layers in [from, to) (≤ 62 days, default the next 14 days)")
    @PreAuthorize(Roles.READERS)
    @GetMapping("/schedules/{id}/shifts")
    public ShiftsResponse shifts(@PathVariable Long id,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.shifts(id, from, to);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/schedules/{id}/overrides")
    public List<OverrideDto> overrides(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean includePast) {
        return service.listOverrides(id, CurrentActor.require(), includePast);
    }

    @Operation(summary = "Override (on-call editors) or offer a shift (offer: true; participants may offer their own shifts)")
    @PreAuthorize(Roles.STAFF)
    @PostMapping("/schedules/{id}/overrides")
    @ResponseStatus(HttpStatus.CREATED)
    public OverrideDto createOverride(@PathVariable Long id, @Valid @RequestBody OverrideRequest request) {
        return service.createOverride(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @GetMapping("/overrides/offers")
    public List<OverrideDto> offers() {
        return service.offersFor(CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/overrides/{id}/take")
    public OverrideDto take(@PathVariable Long id) {
        return service.take(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/overrides/{id}/decline")
    public OverrideDto decline(@PathVariable Long id, @Valid @RequestBody(required = false) DecisionNote note) {
        return service.decline(id, note, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @DeleteMapping("/overrides/{id}")
    public OverrideDto cancel(@PathVariable Long id) {
        return service.cancel(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/availability")
    public List<UnavailabilityDto> availability(@RequestParam(required = false) Long userId, @RequestParam(defaultValue = "false") boolean all) {
        return service.availability(userId, all, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/availability")
    @ResponseStatus(HttpStatus.CREATED)
    public UnavailabilityDto addAvailability(@Valid @RequestBody UnavailabilityRequest request) {
        return service.addUnavailability(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.STAFF)
    @DeleteMapping("/availability/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAvailability(@PathVariable Long id) {
        service.removeUnavailability(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/schedules/{id}/handovers")
    public List<HandoverDto> handovers(@PathVariable Long id, @RequestParam(required = false) Integer size) {
        return service.handoverNotes(id, size);
    }

    @PreAuthorize(Roles.STAFF)
    @PostMapping("/schedules/{id}/handovers")
    @ResponseStatus(HttpStatus.CREATED)
    public HandoverDto addHandover(@PathVariable Long id, @Valid @RequestBody HandoverRequest request) {
        return service.addHandover(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/escalation-policies")
    public List<PolicyDto> policies() {
        return service.listPolicies();
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/escalation-policies/{id}")
    public PolicyDto policy(@PathVariable Long id) {
        return service.getPolicy(id);
    }

    @PreAuthorize(Roles.ONCALL_EDITORS)
    @PostMapping("/escalation-policies")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyDto createPolicy(@Valid @RequestBody PolicyRequest request) {
        return service.createPolicy(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ONCALL_EDITORS)
    @PutMapping("/escalation-policies/{id}")
    public PolicyDto updatePolicy(@PathVariable Long id, @Valid @RequestBody PolicyRequest request) {
        return service.updatePolicy(id, request);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/pages")
    public List<PageDto> pages(@RequestParam(required = false) Long scheduleId, @RequestParam(required = false) Long alertId,
                               @RequestParam(required = false) Integer size) {
        return service.pages(scheduleId, alertId, size);
    }
}
