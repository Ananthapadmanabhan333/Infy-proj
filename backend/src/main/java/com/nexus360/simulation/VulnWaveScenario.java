package com.nexus360.simulation;

import com.nexus360.observability.TelemetryJson;
import com.nexus360.secops.SecOpsDtos.SecurityCorrelationGroupDto;
import com.nexus360.secops.SecOpsDtos.VulnerabilityIngestRequest;
import com.nexus360.secops.SecOpsDtos.VulnerabilityIngestResultDto;
import com.nexus360.secops.SecOpsService;
import com.nexus360.security.Actor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VULN_WAVE: simulates a wave of critical/exploitable vulnerability findings across production
 * assets along with a correlated security alert, producing a security correlation group.
 */
@Component
public class VulnWaveScenario implements SimulationScenario {

  private final SecOpsService secOpsService;
  private final JdbcTemplate jdbc;

  public VulnWaveScenario(SecOpsService secOpsService, JdbcTemplate jdbc) {
    this.secOpsService = secOpsService;
    this.jdbc = jdbc;
  }

  @Override
  public String code() {
    return "VULN_WAVE";
  }

  @Override
  public String title() {
    return "Vulnerability wave & exploit correlation";
  }

  @Override
  public String description() {
    return "Ingests simulated critical/exploitable CVEs on internet-exposed services, raises a simulated security alert, and correlates them.";
  }

  @Override
  public String module() {
    return "SECOPS";
  }

  @Override
  public Set<String> ownedServiceNames() {
    return Set.of("payroll-api", "sso-gateway");
  }

  @Override
  public void start(SimulationContext ctx) {
    Actor actor = ctx.actor() != null ? ctx.actor() : Actor.SYSTEM;

    List<Map<String, Object>> payload =
        List.of(
            Map.of(
                "cveId", "CVE-2026-44101",
                "cweId", "CWE-502",
                "title", "Remote Code Execution in Jackson Deserializer on payroll-api-prod",
                "assetName", "payroll-api-prod",
                "packageName", "com.fasterxml.jackson.core:jackson-databind",
                "installedVersion", "2.15.0",
                "fixedVersion", "2.17.2",
                "cvssBase", 9.8,
                "internetExposed", true,
                "exploitKnown", true),
            Map.of(
                "cveId", "CVE-2026-44102",
                "cweId", "CWE-89",
                "title", "Authentication Bypass in OAuth Token Validator on payroll-api-prod",
                "assetName", "payroll-api-prod",
                "packageName", "org.nimbusds:oauth2-oidc-sdk",
                "installedVersion", "10.1",
                "fixedVersion", "11.9",
                "cvssBase", 9.1,
                "internetExposed", true,
                "exploitKnown", true),
            Map.of(
                "cveId", "CVE-2026-44103",
                "cweId", "CWE-79",
                "title", "Reflected XSS in Portal Header Parser",
                "assetName", "sso-gateway-prod",
                "packageName", "org.eclipse.jetty:jetty-http",
                "installedVersion", "12.0.1",
                "fixedVersion", "12.0.8",
                "cvssBase", 6.5,
                "internetExposed", true,
                "exploitKnown", false));

    VulnerabilityIngestResultDto ingestRes =
        secOpsService.ingest(
            new VulnerabilityIngestRequest(
                null, "GENERIC_JSON", false, TelemetryJson.MAPPER.valueToTree(payload), true),
            null,
            actor);

    Long alertSeq = jdbc.queryForObject("SELECT nextval('alert_number_seq')", Long.class);
    String alertNum = String.format("ALT-%05d", alertSeq != null ? alertSeq : 99001L);
    Long ciId = ctx.ciId("payroll-api-prod");

    jdbc.update(
        """
        INSERT INTO alerts
          (number, source_kind, scope_key, fingerprint, title, description,
           severity, status, firing, labels, annotations, ci_id,
           occurrence_count, first_seen_at, last_seen_at, is_demo, simulated, created_at, updated_at)
        VALUES (?, 'MANUAL', 'SIMULATION', ?, ?, ?, 'CRITICAL', 'OPEN', TRUE,
                '{"category":"SECURITY","asset":"payroll-api-prod"}'::jsonb, '{}'::jsonb, ?,
                1, now(), now(), FALSE, TRUE, now(), now())
        ON CONFLICT (scope_key, fingerprint) WHERE status IN ('OPEN', 'ACKNOWLEDGED', 'SUPPRESSED')
        DO UPDATE SET occurrence_count = alerts.occurrence_count + 1, last_seen_at = now()
        """,
        alertNum,
        "sim-vuln-wave-payroll-api",
        "Security Alert: Exploit payload detected against payroll-api-prod",
        "Simulated WAF/IDS detection of CVE-2026-44101 exploit attempt on payroll-api-prod.",
        ciId);

    List<SecurityCorrelationGroupDto> groups = secOpsService.correlate(true, actor);
    ctx.count("findingsIngested", ingestRes.ingested() + ingestRes.updated());
    ctx.count("correlationGroups", groups.size());
  }

  @Override
  public void tick(SimulationContext ctx) {
    ctx.state().put("lastTick", ctx.tick());
  }
}
