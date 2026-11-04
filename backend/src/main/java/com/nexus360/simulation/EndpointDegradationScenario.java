package com.nexus360.simulation;

import com.nexus360.dex.DexDtos.IngestAppEventRequest;
import com.nexus360.dex.DexDtos.IngestEndpointRequest;
import com.nexus360.dex.DexDtos.IngestLoginEventRequest;
import com.nexus360.dex.DexService;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ENDPOINT_DEGRADATION: simulates degraded Engineering endpoints, application crashes/hangs, and
 * login failures/lockouts via DexService with simulated = true.
 */
@Component
public class EndpointDegradationScenario implements SimulationScenario {

  private final DexService dexService;

  public EndpointDegradationScenario(DexService dexService) {
    this.dexService = dexService;
  }

  @Override
  public String code() {
    return "ENDPOINT_DEGRADATION";
  }

  @Override
  public String title() {
    return "Endpoint fleet & app experience degradation";
  }

  @Override
  public String description() {
    return "Ingests simulated endpoint health degradation across Engineering laptops, app crashes, and SSO login failures.";
  }

  @Override
  public String module() {
    return "DEX";
  }

  @Override
  public Set<String> ownedServiceNames() {
    return Set.of();
  }

  @Override
  public void start(SimulationContext ctx) {
    dexService.ingestAppEvent(
        new IngestAppEventRequest(
            "NexusVPN Client",
            "4.2.0",
            "DEV-ENG-001",
            "rohan.kulkarni@nexus360.local",
            "Engineering",
            2800.0,
            3400.0,
            true,
            false,
            "ERR_TUNNEL_RESET",
            true));
    dexService.ingestAppEvent(
        new IngestAppEventRequest(
            "IDE Workspace",
            "2026.2",
            "DEV-ENG-002",
            "vikram.nair@demo.nexus360.io",
            "Engineering",
            3100.0,
            4200.0,
            false,
            true,
            "ERR_UI_FREEZE",
            true));

    dexService.ingestLoginEvent(
        new IngestLoginEventRequest(
            "rohan.kulkarni@nexus360.local", "Engineering", "SA_AUTH", "FAILURE", true));
    dexService.ingestLoginEvent(
        new IngestLoginEventRequest(
            "vikram.nair@demo.nexus360.io", "Engineering", "SA_AUTH", "LOCKOUT", true));

    dexService.ingestEndpoint(
        new IngestEndpointRequest(
            "DEV-ENG-001",
            null,
            "rohan.kulkarni@nexus360.local",
            "Rohan Kulkarni",
            null,
            "Engineering",
            "Windows 11 Enterprise",
            "23H2",
            "ThinkPad X1 Carbon Gen 11",
            88.0,
            86.0,
            82.0,
            68.0,
            5,
            74.0,
            true));

    dexService.ingestEndpoint(
        new IngestEndpointRequest(
            "DEV-ENG-002",
            null,
            "vikram.nair@demo.nexus360.io",
            "Vikram Nair",
            null,
            "Engineering",
            "Windows 11 Enterprise",
            "23H2",
            "ThinkPad X1 Carbon Gen 11",
            92.0,
            89.0,
            85.0,
            75.0,
            6,
            69.0,
            true));

    dexService.ingestEndpoint(
        new IngestEndpointRequest(
            "DEV-FIN-003",
            null,
            "kavya.reddy@nexus360.local",
            "Kavya Reddy",
            null,
            "Finance",
            "macOS Sonoma",
            "14.6",
            "MacBook Pro 14 M3",
            34.0,
            48.0,
            42.0,
            19.0,
            0,
            96.0,
            true));

    ctx.count("simulatedEndpoints", 3);
    ctx.count("simulatedAppEvents", 2);
    ctx.count("simulatedLoginEvents", 2);
  }

  @Override
  public void tick(SimulationContext ctx) {
    ctx.state().put("lastTick", ctx.tick());
  }
}
