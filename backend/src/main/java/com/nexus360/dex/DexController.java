package com.nexus360.dex;

import com.nexus360.dex.DexDtos.*;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dex")
public class DexController {

  private final DexService dexService;

  public DexController(DexService dexService) {
    this.dexService = dexService;
  }

  @GetMapping("/overview")
  @PreAuthorize(Roles.DEX_READERS)
  public DexOverviewDto overview(
      @RequestParam(defaultValue = "false") boolean includeSimulated) {
    return dexService.overview(includeSimulated);
  }

  @PostMapping("/ingest/endpoints")
  @PreAuthorize(Roles.ADMINS)
  public DexEndpointDto ingestEndpoint(@RequestBody IngestEndpointRequest req) {
    return dexService.ingestEndpoint(req);
  }

  @PostMapping("/ingest/app-events")
  @PreAuthorize(Roles.ADMINS)
  public Map<String, Object> ingestAppEvent(@RequestBody IngestAppEventRequest req) {
    return dexService.ingestAppEvent(req);
  }

  @PostMapping("/ingest/login-events")
  @PreAuthorize(Roles.ADMINS)
  public Map<String, Object> ingestLoginEvent(@RequestBody IngestLoginEventRequest req) {
    return dexService.ingestLoginEvent(req);
  }

  @PostMapping("/proactive-rules")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(Roles.ADMINS)
  public DexProactiveRuleDto createProactiveRule(
      @Valid @RequestBody UpsertProactiveRuleRequest req) {
    return dexService.createProactiveRule(req);
  }

  @PutMapping("/proactive-rules/{id}")
  @PreAuthorize(Roles.ADMINS)
  public DexProactiveRuleDto updateProactiveRule(
      @PathVariable long id,
      @RequestBody UpsertProactiveRuleRequest req) {
    return dexService.updateProactiveRule(id, req);
  }

  @GetMapping("/me")
  public MyDeviceSummaryDto myDevice(
      @RequestParam(defaultValue = "false") boolean includeSimulated) {
    return dexService.myDevice(CurrentActor.orSystem(), includeSimulated);
  }
}
