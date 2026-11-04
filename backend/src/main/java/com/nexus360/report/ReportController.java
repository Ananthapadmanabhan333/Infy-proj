package com.nexus360.report;

import com.nexus360.report.ReportDtos.ReportTypeDto;
import com.nexus360.report.ReportDtos.ScheduleDto;
import com.nexus360.report.ReportDtos.ScheduleRequest;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scheduled reports. Report schedulers (= report exporters) create schedules; the owner or an administrator changes them;
 * the owner and report exporters list runs and download files. Others get 404 for schedules they cannot see.
 */
@Tag(name = "Scheduled reports")
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportScheduleService service;

    @GetMapping("/api/reports/types")
    public List<ReportTypeDto> types() {
        return service.types();
    }

    @Operation(summary = "Schedules: all for report exporters, otherwise the caller's own")
    @GetMapping("/api/reports/schedules")
    public List<ScheduleDto> list() {
        return service.list(CurrentActor.require());
    }

    @GetMapping("/api/reports/schedules/{id}")
    public ScheduleDto get(@PathVariable Long id) {
        return service.get(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.REPORT_SCHEDULERS)
    @PostMapping("/api/reports/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleDto create(@Valid @RequestBody ScheduleRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.REPORT_SCHEDULERS)
    @PutMapping("/api/reports/schedules/{id}")
    public ScheduleDto update(@PathVariable Long id, @Valid @RequestBody ScheduleRequest request) {
        return service.update(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.REPORT_SCHEDULERS)
    @DeleteMapping("/api/reports/schedules/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id, CurrentActor.require());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Run now for the period ending yesterday (synchronous); returns the finished run")
    @PostMapping("/api/reports/schedules/{id}/run")
    public ReportRunDto runNow(@PathVariable Long id) {
        return service.runNow(id, CurrentActor.require());
    }

    @GetMapping("/api/reports/schedules/{id}/runs")
    public List<ReportRunDto> runs(@PathVariable Long id, @RequestParam(required = false) Integer size) {
        return service.runs(id, CurrentActor.require(), size);
    }

    @Operation(summary = "Download a run's file (owner or report exporters); audited")
    @GetMapping("/api/reports/runs/{runId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long runId) {
        ReportScheduleService.Download d = service.download(runId, CurrentActor.require());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.fileName() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(d.contentType()))
                .body(d.content());
    }
}
