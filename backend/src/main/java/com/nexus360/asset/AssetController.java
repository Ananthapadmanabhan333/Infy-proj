package com.nexus360.asset;

import com.nexus360.asset.AssetDtos.AssetDetail;
import com.nexus360.asset.AssetDtos.AssetRequest;
import com.nexus360.asset.AssetDtos.AssetSummary;
import com.nexus360.asset.AssetDtos.AssetSummaryStats;
import com.nexus360.asset.AssetDtos.AssignRequest;
import com.nexus360.asset.AssetDtos.AssignmentDto;
import com.nexus360.asset.AssetDtos.EventDto;
import com.nexus360.asset.AssetDtos.ExpiringItem;
import com.nexus360.asset.AssetDtos.ImportResult;
import com.nexus360.asset.AssetDtos.MaintenanceDto;
import com.nexus360.asset.AssetDtos.MaintenanceRequest;
import com.nexus360.asset.AssetDtos.ReturnRequest;
import com.nexus360.asset.AssetDtos.TransitionRequest;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.common.Csv;
import com.nexus360.common.PageResponse;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Assets")
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService service;
    private final AssetCsvService csv;
    private final AuditService audit;

    @PreAuthorize(Roles.READERS)
    @GetMapping
    public PageResponse<AssetSummary> list(@RequestParam(required = false) String search, @RequestParam(required = false) List<Asset.Type> type,
                                           @RequestParam(required = false) List<Asset.Status> status, @RequestParam(required = false) String category,
                                           @RequestParam(required = false) Long assignedToId, @RequestParam(required = false) Long departmentId,
                                           @RequestParam(required = false) Long ciId, @RequestParam(required = false) String warranty,
                                           @RequestParam(required = false) String location, @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return service.list(new AssetService.Filter(search, type, status, category, assignedToId, departmentId, ciId, warranty, location), page, size, sort);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/summary")
    public AssetSummaryStats summary() {
        return service.stats();
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/expiring")
    public List<ExpiringItem> expiring(@RequestParam(required = false) Integer days) {
        return service.expiring(days);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String search, @RequestParam(required = false) List<Asset.Type> type,
                                         @RequestParam(required = false) List<Asset.Status> status, @RequestParam(required = false) String category,
                                         @RequestParam(required = false) Long assignedToId, @RequestParam(required = false) Long departmentId,
                                         @RequestParam(required = false) Long ciId, @RequestParam(required = false) String warranty,
                                         @RequestParam(required = false) String location) {
        List<List<Object>> rows = csv.exportRows(new AssetService.Filter(search, type, status, category, assignedToId, departmentId, ciId, warranty,
                location));
        audit.record("ASSETS_EXPORTED", "ASSET", null, Map.of("rows", rows.size()));
        return Csv.response("assets.csv", AssetCsvService.COLUMNS, rows);
    }

    @PreAuthorize(Roles.ASSET_EDITORS)
    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        return Csv.response("assets-import-template.csv", AssetCsvService.COLUMNS, List.of(Arrays.asList("", "Dell Latitude 7440", "LAPTOP", "IN_STOCK",
                "Dell", "Latitude 7440", "SN-EXAMPLE-001", "", "IT-EUC", "Bengaluru HQ, store room", "Dell India", "PO-2026-0001", "98500.00", "INR",
                "2026-01-15", "2029-01-14", "", "", "", "", "Example row - replace or delete")));
    }

    @PreAuthorize(Roles.ASSET_EDITORS)
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ImportResult importCsv(@RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "true") boolean dryRun) {
        try {
            return csv.importCsv(file.getBytes(), dryRun, CurrentActor.require());
        } catch (IOException e) {
            throw ApiException.invalidField("file", "The file could not be read");
        }
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}")
    public AssetDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PreAuthorize(Roles.ASSET_EDITORS)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssetDetail create(@Valid @RequestBody AssetRequest request) {
        return service.create(request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ASSET_EDITORS)
    @PutMapping("/{id}")
    public AssetDetail update(@PathVariable Long id, @Valid @RequestBody AssetRequest request) {
        return service.update(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ASSET_EDITORS)
    @PostMapping("/{id}/transition")
    public AssetDetail transition(@PathVariable Long id, @Valid @RequestBody TransitionRequest request) {
        return service.transition(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ASSET_EDITORS)
    @PostMapping("/{id}/assign")
    public AssetDetail assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        return service.assign(id, request, CurrentActor.require());
    }

    @PreAuthorize(Roles.ASSET_EDITORS)
    @PostMapping("/{id}/return")
    public AssetDetail returnAsset(@PathVariable Long id, @Valid @RequestBody(required = false) ReturnRequest request) {
        return service.returnAsset(id, request == null ? new ReturnRequest(null, null) : request, CurrentActor.require());
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}/assignments")
    public List<AssignmentDto> assignments(@PathVariable Long id) {
        return service.assignments(id);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}/history")
    public List<EventDto> history(@PathVariable Long id) {
        return service.events(id);
    }

    @PreAuthorize(Roles.READERS)
    @GetMapping("/{id}/maintenance")
    public List<MaintenanceDto> maintenance(@PathVariable Long id) {
        return service.maintenance(id);
    }

    @PreAuthorize(Roles.ASSET_EDITORS)
    @PostMapping("/{id}/maintenance")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceDto addMaintenance(@PathVariable Long id, @Valid @RequestBody MaintenanceRequest request) {
        return service.addMaintenance(id, request, CurrentActor.require());
    }
}
