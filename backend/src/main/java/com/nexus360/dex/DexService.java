package com.nexus360.dex;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexus360.common.ApiException;
import com.nexus360.dex.DexDtos.*;
import com.nexus360.incident.Category;
import com.nexus360.incident.IncidentDtos.CreateIncidentRequest;
import com.nexus360.incident.IncidentDtos.CreateIncidentResult;
import com.nexus360.incident.IncidentService;
import com.nexus360.incident.IncidentSource;
import com.nexus360.incident.Level;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.observability.TelemetryJson;
import com.nexus360.security.Actor;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DexService {

  public static final String FORMULA_DOC =
      "experienceScore = round1(0.35 * deviceHealth + 0.25 * appPerformance + 0.20 * loginSuccess + 0.20 * ticketSignals)";

  private static final Set<String> LOGIN_OUTCOMES =
      Set.of("SUCCESS", "FAILURE", "LOCKOUT", "MFA_FAILURE");

  private final JdbcTemplate jdbc;
  private final IncidentService incidentService;
  private final NotificationService notificationService;

  public DexService(
      JdbcTemplate jdbc,
      IncidentService incidentService,
      NotificationService notificationService) {
    this.jdbc = jdbc;
    this.incidentService = incidentService;
    this.notificationService = notificationService;
  }

  @Transactional(readOnly = true)
  public DexOverviewDto overview(boolean includeSimulated) {
    List<DexEndpointDto> endpoints =
        jdbc.query(
            """
            SELECT * FROM dex_endpoints
            WHERE (? OR NOT simulated)
            ORDER BY experience_score ASC, id ASC
            """,
            (rs, i) -> mapEndpoint(rs),
            includeSimulated);

    List<ServiceExperienceDto> serviceExperience = computeServiceExperience();
    List<DexProactiveRuleDto> rules = listProactiveRules();

    if (endpoints.isEmpty()) {
      return new DexOverviewDto(
          "INSUFFICIENT_DATA",
          0.0,
          0.0,
          0.0,
          0.0,
          0L,
          FORMULA_DOC,
          List.of(),
          List.of(),
          List.of(),
          serviceExperience,
          List.of(),
          rules,
          false);
    }

    double overallExp =
        round1(
            endpoints.stream()
                .mapToDouble(DexEndpointDto::experienceScore)
                .average()
                .orElse(0.0));
    double deviceHealthAvg =
        round1(
            endpoints.stream()
                .mapToDouble(DexEndpointDto::deviceHealthScore)
                .average()
                .orElse(0.0));
    double appPerfAvg =
        round1(
            endpoints.stream()
                .mapToDouble(
                    e -> e.scoreBreakdown().getOrDefault("appPerformance", 85.0))
                .average()
                .orElse(85.0));
    double loginSuccessAvg =
        round1(
            endpoints.stream()
                .mapToDouble(
                    e -> e.scoreBreakdown().getOrDefault("loginSuccess", 100.0))
                .average()
                .orElse(100.0));

    List<DexBucketDto> byDepartment =
        jdbc.query(
            """
            SELECT COALESCE(department_name, 'Unassigned') AS dept,
                   COUNT(*) AS cnt,
                   AVG(experience_score) AS avg_exp,
                   AVG(device_health_score) AS avg_dev
            FROM dex_endpoints
            WHERE (? OR NOT simulated)
            GROUP BY COALESCE(department_name, 'Unassigned')
            ORDER BY avg_exp ASC
            """,
            (rs, i) -> {
              String d = rs.getString("dept");
              return new DexBucketDto(
                  d,
                  d,
                  rs.getLong("cnt"),
                  round1(rs.getDouble("avg_exp")),
                  round1(rs.getDouble("avg_dev")));
            },
            includeSimulated);

    List<DexBucketDto> byDeviceModel =
        jdbc.query(
            """
            SELECT device_model,
                   COUNT(*) AS cnt,
                   AVG(experience_score) AS avg_exp,
                   AVG(device_health_score) AS avg_dev
            FROM dex_endpoints
            WHERE (? OR NOT simulated)
            GROUP BY device_model
            ORDER BY avg_exp ASC
            """,
            (rs, i) -> {
              String m = rs.getString("device_model");
              return new DexBucketDto(
                  m,
                  m,
                  rs.getLong("cnt"),
                  round1(rs.getDouble("avg_exp")),
                  round1(rs.getDouble("avg_dev")));
            },
            includeSimulated);

    List<DexAppSummaryDto> byApp =
        jdbc.query(
            """
            SELECT app_name,
                   MAX(app_version) AS app_version,
                   COUNT(*) AS cnt,
                   COALESCE(AVG(launch_time_ms), 0) AS avg_launch,
                   COALESCE(AVG(page_load_ms), 0) AS avg_load,
                   SUM(CASE WHEN crashed THEN 1 ELSE 0 END) AS crash_cnt,
                   SUM(CASE WHEN hang THEN 1 ELSE 0 END) AS hang_cnt
            FROM dex_app_events
            WHERE (? OR NOT simulated)
            GROUP BY app_name
            ORDER BY cnt DESC, app_name ASC
            """,
            (rs, i) -> {
              long cnt = Math.max(1L, rs.getLong("cnt"));
              double crashPct = round1((rs.getLong("crash_cnt") * 100.0) / cnt);
              double hangPct = round1((rs.getLong("hang_cnt") * 100.0) / cnt);
              return new DexAppSummaryDto(
                  rs.getString("app_name"),
                  rs.getString("app_version"),
                  rs.getLong("cnt"),
                  round1(rs.getDouble("avg_launch")),
                  round1(rs.getDouble("avg_load")),
                  crashPct,
                  hangPct);
            },
            includeSimulated);

    boolean anySim = endpoints.stream().anyMatch(DexEndpointDto::simulated);

    return new DexOverviewDto(
        "OK",
        overallExp,
        deviceHealthAvg,
        appPerfAvg,
        loginSuccessAvg,
        endpoints.size(),
        FORMULA_DOC,
        byDepartment,
        byDeviceModel,
        byApp,
        serviceExperience,
        endpoints,
        rules,
        anySim);
  }

  @Transactional
  public DexEndpointDto ingestEndpoint(IngestEndpointRequest req) {
    if (req.deviceId() == null || req.deviceId().isBlank()) {
      throw ApiException.badRequest("deviceId is required");
    }
    if (req.userEmail() == null || req.userEmail().isBlank()) {
      throw ApiException.badRequest("userEmail is required");
    }

    String deviceId = req.deviceId().trim();
    String userEmail = req.userEmail().trim();
    boolean simulated = Boolean.TRUE.equals(req.simulated());

    ResolvedUser resolved = resolveUser(req.userId(), userEmail, req.userName(), req.departmentId(), req.departmentName());

    double cpu = clamp(req.cpuUsagePct() != null ? req.cpuUsagePct() : 25.0, 0.0, 100.0);
    double mem = clamp(req.memoryUsagePct() != null ? req.memoryUsagePct() : 40.0, 0.0, 100.0);
    double disk = clamp(req.diskUsagePct() != null ? req.diskUsagePct() : 45.0, 0.0, 100.0);
    double bootSec = Math.max(0.0, req.bootTimeSeconds() != null ? req.bootTimeSeconds() : 22.0);
    int crashes7d = Math.max(0, req.crashCount7d() != null ? req.crashCount7d() : 0);
    Double battery = req.batteryHealthPct();

    String osName = req.osName() != null && !req.osName().isBlank() ? req.osName().trim() : "Windows 11 Enterprise";
    String osVersion = req.osVersion() != null && !req.osVersion().isBlank() ? req.osVersion().trim() : "23H2";
    String deviceModel = req.deviceModel() != null && !req.deviceModel().isBlank() ? req.deviceModel().trim() : "Latitude 7440";

    ScoreComputation scores =
        computeScores(deviceId, userEmail, resolved.userId(), cpu, mem, disk, bootSec, crashes7d, true);

    jdbc.update(
        """
        INSERT INTO dex_endpoints
          (device_id, user_id, user_email, user_name, department_id, department_name,
           os_name, os_version, device_model, cpu_usage_pct, memory_usage_pct, disk_usage_pct,
           boot_time_seconds, crash_count_7d, battery_health_pct, device_health_score,
           experience_score, score_breakdown, last_seen_at, simulated, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now(), ?, now())
        ON CONFLICT (device_id) DO UPDATE SET
          user_id = EXCLUDED.user_id,
          user_email = EXCLUDED.user_email,
          user_name = EXCLUDED.user_name,
          department_id = EXCLUDED.department_id,
          department_name = EXCLUDED.department_name,
          os_name = EXCLUDED.os_name,
          os_version = EXCLUDED.os_version,
          device_model = EXCLUDED.device_model,
          cpu_usage_pct = EXCLUDED.cpu_usage_pct,
          memory_usage_pct = EXCLUDED.memory_usage_pct,
          disk_usage_pct = EXCLUDED.disk_usage_pct,
          boot_time_seconds = EXCLUDED.boot_time_seconds,
          crash_count_7d = EXCLUDED.crash_count_7d,
          battery_health_pct = EXCLUDED.battery_health_pct,
          device_health_score = EXCLUDED.device_health_score,
          experience_score = EXCLUDED.experience_score,
          score_breakdown = EXCLUDED.score_breakdown,
          last_seen_at = now(),
          simulated = EXCLUDED.simulated,
          updated_at = now()
        """,
        deviceId,
        resolved.userId(),
        userEmail,
        resolved.userName(),
        resolved.departmentId(),
        resolved.departmentName(),
        osName,
        osVersion,
        deviceModel,
        cpu,
        mem,
        disk,
        bootSec,
        crashes7d,
        battery,
        scores.deviceHealth(),
        scores.experienceScore(),
        TelemetryJson.write(scores.breakdown()),
        simulated);

    evaluateProactiveRules(true);

    return jdbc.query(
            "SELECT * FROM dex_endpoints WHERE device_id = ?",
            (rs, i) -> mapEndpoint(rs),
            deviceId)
        .getFirst();
  }

  @Transactional
  public Map<String, Object> ingestAppEvent(IngestAppEventRequest req) {
    if (req.appName() == null || req.appName().isBlank()) {
      throw ApiException.badRequest("appName is required");
    }
    String appName = req.appName().trim();
    String appVersion =
        req.appVersion() != null && !req.appVersion().isBlank() ? req.appVersion().trim() : "1.0.0";
    boolean crashed = Boolean.TRUE.equals(req.crashed());
    boolean hang = Boolean.TRUE.equals(req.hang());
    boolean simulated = Boolean.TRUE.equals(req.simulated());

    jdbc.update(
        """
        INSERT INTO dex_app_events
          (app_name, app_version, device_id, user_email, department_name,
           launch_time_ms, page_load_ms, crashed, hang, error_code, simulated, recorded_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        """,
        appName,
        appVersion,
        trimToNull(req.deviceId()),
        trimToNull(req.userEmail()),
        trimToNull(req.departmentName()),
        req.launchTimeMs(),
        req.pageLoadMs(),
        crashed,
        hang,
        trimToNull(req.errorCode()),
        simulated);

    recomputeEndpointsForUserOrDevice(req.deviceId(), req.userEmail());
    evaluateProactiveRules(true);

    return Map.of("ingested", 1, "appName", appName);
  }

  @Transactional
  public Map<String, Object> ingestLoginEvent(IngestLoginEventRequest req) {
    if (req.userEmail() == null || req.userEmail().isBlank()) {
      throw ApiException.badRequest("userEmail is required");
    }
    String outcome =
        req.outcome() != null && !req.outcome().isBlank()
            ? req.outcome().trim().toUpperCase(Locale.ROOT)
            : "SUCCESS";
    if (!LOGIN_OUTCOMES.contains(outcome)) {
      throw ApiException.badRequest("Unsupported login outcome: " + outcome);
    }
    String provider =
        req.provider() != null && !req.provider().isBlank() ? req.provider().trim() : "SA_AUTH";
    boolean simulated = Boolean.TRUE.equals(req.simulated());

    jdbc.update(
        """
        INSERT INTO dex_login_events
          (user_email, department_name, provider, outcome, simulated, recorded_at)
        VALUES (?, ?, ?, ?, ?, now())
        """,
        req.userEmail().trim(),
        trimToNull(req.departmentName()),
        provider,
        outcome,
        simulated);

    recomputeEndpointsForUserOrDevice(null, req.userEmail());
    evaluateProactiveRules(true);

    return Map.of("ingested", 1, "outcome", outcome);
  }

  @Transactional(readOnly = true)
  public List<DexProactiveRuleDto> listProactiveRules() {
    return jdbc.query(
        "SELECT * FROM dex_proactive_rules ORDER BY id ASC",
        (rs, i) -> mapProactiveRule(rs));
  }

  @Transactional
  public DexProactiveRuleDto createProactiveRule(UpsertProactiveRuleRequest req) {
    double threshold = req.thresholdScore() != null ? req.thresholdScore() : 60.0;
    boolean enabled = req.enabled() == null || req.enabled();

    KeyHolder kh = new GeneratedKeyHolder();
    jdbc.update(
        con -> {
          PreparedStatement ps =
              con.prepareStatement(
                  """
                  INSERT INTO dex_proactive_rules
                    (name, department_name, threshold_score, enabled, is_demo, created_at)
                  VALUES (?, ?, ?, ?, FALSE, now())
                  """,
                  new String[] {"id"});
          ps.setString(1, req.name().trim());
          ps.setString(2, trimToNull(req.departmentName()));
          ps.setDouble(3, threshold);
          ps.setBoolean(4, enabled);
          return ps;
        },
        kh);
    long id = kh.getKey().longValue();
    evaluateProactiveRules(true);
    return getProactiveRule(id);
  }

  @Transactional
  public DexProactiveRuleDto updateProactiveRule(long id, UpsertProactiveRuleRequest req) {
    DexProactiveRuleDto existing = getProactiveRule(id);
    String name = req.name() != null && !req.name().isBlank() ? req.name().trim() : existing.name();
    String dept =
        req.departmentName() != null ? trimToNull(req.departmentName()) : existing.departmentName();
    double threshold =
        req.thresholdScore() != null ? req.thresholdScore() : existing.thresholdScore();
    boolean enabled = req.enabled() != null ? req.enabled() : existing.enabled();

    jdbc.update(
        """
        UPDATE dex_proactive_rules
        SET name = ?, department_name = ?, threshold_score = ?, enabled = ?
        WHERE id = ?
        """,
        name,
        dept,
        threshold,
        enabled,
        id);
    evaluateProactiveRules(true);
    return getProactiveRule(id);
  }

  @Transactional(readOnly = true)
  public DexProactiveRuleDto getProactiveRule(long id) {
    List<DexProactiveRuleDto> list =
        jdbc.query(
            "SELECT * FROM dex_proactive_rules WHERE id = ?",
            (rs, i) -> mapProactiveRule(rs),
            id);
    if (list.isEmpty()) {
      throw ApiException.notFound("DexProactiveRule", id);
    }
    return list.getFirst();
  }

  @Transactional(readOnly = true)
  public MyDeviceSummaryDto myDevice(Actor actor, boolean includeSimulated) {
    if (actor == null) {
      return new MyDeviceSummaryDto(
          "NO_DEVICE", null, "No managed endpoint telemetry registered for your account.");
    }
    Long userId = actor.id();
    String email = actor.email() != null ? actor.email().trim() : "";
    if (userId != null && email.isEmpty()) {
      String dbEmail =
          jdbc.query(
              "SELECT email FROM users WHERE id = ?",
              rs -> rs.next() ? rs.getString(1) : null,
              userId);
      if (dbEmail != null) {
        email = dbEmail;
      }
    }

    String localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;

    List<DexEndpointDto> matches =
        jdbc.query(
            """
            SELECT * FROM dex_endpoints
            WHERE (? OR NOT simulated)
              AND (
                (CAST(? AS BIGINT) IS NOT NULL AND user_id = CAST(? AS BIGINT))
                OR LOWER(user_email) = LOWER(?)
                OR LOWER(SPLIT_PART(user_email, '@', 1)) = LOWER(?)
              )
            ORDER BY updated_at DESC, id DESC
            """,
            (rs, i) -> mapEndpoint(rs),
            includeSimulated,
            userId,
            userId,
            email,
            localPart);

    if (matches.isEmpty()) {
      return new MyDeviceSummaryDto(
          "NO_DEVICE", null, "No managed endpoint telemetry registered for your account.");
    }
    return new MyDeviceSummaryDto(
        "OK", matches.getFirst(), "Device health and experience telemetry active.");
  }

  @Transactional
  public void evaluateProactiveRules(boolean includeSimulated) {
    List<DexProactiveRuleDto> rules =
        jdbc.query(
            "SELECT * FROM dex_proactive_rules WHERE enabled = TRUE ORDER BY id ASC",
            (rs, i) -> mapProactiveRule(rs));

    for (DexProactiveRuleDto rule : rules) {
      String dept = rule.departmentName();
      boolean filterByDept = dept != null && !dept.isBlank() && !"ALL".equalsIgnoreCase(dept);

      record DeptStats(long count, double avgScore, boolean anySimulated) {}
      DeptStats stats =
          filterByDept
              ? jdbc.query(
                  """
                  SELECT COUNT(*) AS cnt,
                         COALESCE(AVG(experience_score), 100.0) AS avg_score,
                         COALESCE(BOOL_OR(simulated), FALSE) AS any_sim
                  FROM dex_endpoints
                  WHERE (? OR NOT simulated)
                    AND LOWER(COALESCE(department_name, '')) = LOWER(?)
                  """,
                  rs ->
                      rs.next()
                          ? new DeptStats(
                              rs.getLong("cnt"),
                              rs.getDouble("avg_score"),
                              rs.getBoolean("any_sim"))
                          : new DeptStats(0, 100.0, false),
                  includeSimulated,
                  dept)
              : jdbc.query(
                  """
                  SELECT COUNT(*) AS cnt,
                         COALESCE(AVG(experience_score), 100.0) AS avg_score,
                         COALESCE(BOOL_OR(simulated), FALSE) AS any_sim
                  FROM dex_endpoints
                  WHERE (? OR NOT simulated)
                  """,
                  rs ->
                      rs.next()
                          ? new DeptStats(
                              rs.getLong("cnt"),
                              rs.getDouble("avg_score"),
                              rs.getBoolean("any_sim"))
                          : new DeptStats(0, 100.0, false),
                  includeSimulated);

      if (stats.count() == 0 || stats.avgScore() >= rule.thresholdScore()) {
        continue;
      }

      if (rule.lastTriggeredIncidentId() != null) {
        String existingStatus =
            jdbc.query(
                "SELECT status FROM incidents WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                rule.lastTriggeredIncidentId());
        if (existingStatus != null
            && !"RESOLVED".equalsIgnoreCase(existingStatus)
            && !"CLOSED".equalsIgnoreCase(existingStatus)
            && !"CANCELLED".equalsIgnoreCase(existingStatus)) {
          continue;
        }
      }

      String scopeLabel = filterByDept ? dept : "Enterprise";
      double avgRounded = round1(stats.avgScore());
      String title =
          "Proactive DEX Alert: "
              + scopeLabel
              + " experience score dropped to "
              + avgRounded;
      String description =
          String.format(
              Locale.ROOT,
              "Proactive incident automatically raised by DEX rule '%s'. Average experience score for %s (%d endpoints) is %.1f, below threshold %.1f.",
              rule.name(),
              scopeLabel,
              stats.count(),
              avgRounded,
              rule.thresholdScore());

      CreateIncidentResult created =
          incidentService.create(
              new CreateIncidentRequest(
                  title,
                  description,
                  null,
                  Category.HARDWARE,
                  Level.MEDIUM,
                  Level.HIGH,
                  (int) Math.max(1L, stats.count()),
                  null,
                  IncidentSource.MONITORING),
              Actor.SYSTEM);

      long incId = created.incident().id();
      if (stats.anySimulated()) {
        jdbc.update("UPDATE incidents SET simulated = TRUE WHERE id = ?", incId);
      }
      jdbc.update(
          "UPDATE dex_proactive_rules SET last_triggered_incident_id = ? WHERE id = ?",
          incId,
          rule.id());

      List<Long> recipients =
          jdbc.query(
              """
              SELECT id FROM users
              WHERE active = TRUE
                AND role IN ('IT_ADMIN', 'IT_MANAGER', 'SYSTEM_ADMIN')
              """,
              (rs, i) -> rs.getLong(1));
      notificationService.notifyUsers(
          recipients,
          "DEX_PROACTIVE_INCIDENT",
          title,
          description,
          "/incidents/" + incId,
          Severity.CRITICAL);
    }
  }

  private void recomputeEndpointsForUserOrDevice(String deviceId, String userEmail) {
    List<Map<String, Object>> rows = new ArrayList<>();
    if (deviceId != null && !deviceId.isBlank()) {
      rows.addAll(
          jdbc.queryForList(
              "SELECT * FROM dex_endpoints WHERE LOWER(device_id) = LOWER(?)",
              deviceId.trim()));
    }
    if (rows.isEmpty() && userEmail != null && !userEmail.isBlank()) {
      rows.addAll(
          jdbc.queryForList(
              "SELECT * FROM dex_endpoints WHERE LOWER(user_email) = LOWER(?)",
              userEmail.trim()));
    }
    for (Map<String, Object> row : rows) {
      long id = ((Number) row.get("id")).longValue();
      String devId = (String) row.get("device_id");
      String email = (String) row.get("user_email");
      Long userId = row.get("user_id") != null ? ((Number) row.get("user_id")).longValue() : null;
      double cpu = ((Number) row.get("cpu_usage_pct")).doubleValue();
      double mem = ((Number) row.get("memory_usage_pct")).doubleValue();
      double disk = ((Number) row.get("disk_usage_pct")).doubleValue();
      double boot = ((Number) row.get("boot_time_seconds")).doubleValue();
      int crashes = ((Number) row.get("crash_count_7d")).intValue();

      ScoreComputation sc =
          computeScores(devId, email, userId, cpu, mem, disk, boot, crashes, true);
      jdbc.update(
          """
          UPDATE dex_endpoints
          SET device_health_score = ?,
              experience_score = ?,
              score_breakdown = ?::jsonb,
              updated_at = now()
          WHERE id = ?
          """,
          sc.deviceHealth(),
          sc.experienceScore(),
          TelemetryJson.write(sc.breakdown()),
          id);
    }
  }

  public ScoreComputation computeScores(
      String deviceId,
      String userEmail,
      Long userId,
      double cpu,
      double mem,
      double disk,
      double bootTimeSec,
      int crashCount7d,
      boolean includeSimulated) {
    double penaltyDevice =
        0.30 * Math.max(0.0, cpu - 50.0)
            + 0.30 * Math.max(0.0, mem - 60.0)
            + 0.20 * Math.max(0.0, disk - 70.0)
            + 4.0 * Math.min(10, crashCount7d)
            + 0.20 * Math.max(0.0, bootTimeSec - 25.0);
    double deviceHealth = round1(clamp(100.0 - penaltyDevice, 0.0, 100.0));

    record AppStats(long count, double avgLoadMs, long badCount) {}
    AppStats appStats =
        jdbc.query(
            """
            SELECT COUNT(*) AS cnt,
                   COALESCE(AVG(COALESCE(page_load_ms, launch_time_ms, 0)), 0) AS avg_load,
                   SUM(CASE WHEN crashed OR hang THEN 1 ELSE 0 END) AS bad_cnt
            FROM dex_app_events
            WHERE (? OR NOT simulated)
              AND (LOWER(COALESCE(device_id, '')) = LOWER(?) OR LOWER(COALESCE(user_email, '')) = LOWER(?))
            """,
            rs ->
                rs.next()
                    ? new AppStats(
                        rs.getLong("cnt"),
                        rs.getDouble("avg_load"),
                        rs.getLong("bad_cnt"))
                    : new AppStats(0, 0.0, 0),
            includeSimulated,
            deviceId != null ? deviceId : "",
            userEmail != null ? userEmail : "");

    double appPerformance;
    if (appStats.count() == 0) {
      appPerformance = 85.0;
    } else {
      double crashOrHangRate = (double) appStats.badCount() / appStats.count();
      double appPenalty =
          0.015 * Math.max(0.0, appStats.avgLoadMs() - 400.0) + 25.0 * crashOrHangRate;
      appPerformance = round1(clamp(100.0 - appPenalty, 0.0, 100.0));
    }

    record LoginStats(long total, long success, long lockouts) {}
    LoginStats loginStats =
        jdbc.query(
            """
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN outcome = 'SUCCESS' THEN 1 ELSE 0 END) AS succ,
                   SUM(CASE WHEN outcome = 'LOCKOUT' THEN 1 ELSE 0 END) AS lockouts
            FROM dex_login_events
            WHERE (? OR NOT simulated)
              AND LOWER(user_email) = LOWER(?)
            """,
            rs ->
                rs.next()
                    ? new LoginStats(
                        rs.getLong("total"),
                        rs.getLong("succ"),
                        rs.getLong("lockouts"))
                    : new LoginStats(0, 0, 0),
            includeSimulated,
            userEmail != null ? userEmail : "");

    double loginSuccess;
    if (loginStats.total() == 0) {
      loginSuccess = 100.0;
    } else {
      double successRatePct = (loginStats.success() * 100.0) / loginStats.total();
      loginSuccess = round1(clamp(successRatePct - 10.0 * loginStats.lockouts(), 0.0, 100.0));
    }

    long openIncidents = 0;
    if (userId != null) {
      Long cnt =
          jdbc.queryForObject(
              """
              SELECT COUNT(*) FROM incidents
              WHERE requester_id = ?
                AND status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
              """,
              Long.class,
              userId);
      openIncidents = cnt != null ? cnt : 0L;
    }
    double ticketSignals = round1(clamp(100.0 - (8.0 * openIncidents), 0.0, 100.0));

    double experienceScore =
        round1(
            0.35 * deviceHealth
                + 0.25 * appPerformance
                + 0.20 * loginSuccess
                + 0.20 * ticketSignals);

    Map<String, Double> breakdown = new LinkedHashMap<>();
    breakdown.put("deviceHealth", deviceHealth);
    breakdown.put("appPerformance", appPerformance);
    breakdown.put("loginSuccess", loginSuccess);
    breakdown.put("ticketSignals", ticketSignals);

    return new ScoreComputation(deviceHealth, experienceScore, breakdown);
  }

  private List<ServiceExperienceDto> computeServiceExperience() {
    Long userCntObj = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE active = TRUE", Long.class);
    long totalUsers = Math.max(1L, userCntObj != null ? userCntObj : 1L);

    return jdbc.query(
        """
        SELECT bs.id,
               bs.code,
               bs.name,
               COUNT(i.id) AS inc_count,
               COALESCE(AVG(EXTRACT(EPOCH FROM (COALESCE(i.resolved_at, now()) - i.created_at)) / 3600.0), 0) AS avg_res_hours,
               SUM(CASE WHEN i.escalation_level = 0 AND i.status IN ('RESOLVED', 'CLOSED') THEN 1 ELSE 0 END) AS fcr_cnt,
               SUM(CASE WHEN i.status IN ('RESOLVED', 'CLOSED') THEN 1 ELSE 0 END) AS resolved_cnt,
               SUM(CASE WHEN i.escalation_level > 1 THEN 1 ELSE 0 END) AS reopen_proxy_cnt
        FROM business_services bs
        LEFT JOIN incidents i ON i.service_id = bs.id AND NOT i.simulated
        GROUP BY bs.id, bs.code, bs.name, bs.tier
        ORDER BY bs.tier ASC, bs.name ASC
        """,
        (rs, idx) -> {
          long incCount = rs.getLong("inc_count");
          long resolvedCnt = rs.getLong("resolved_cnt");
          long fcrCnt = rs.getLong("fcr_cnt");
          long reopenProxy = rs.getLong("reopen_proxy_cnt");
          double per100 = round1((incCount * 100.0) / totalUsers);
          double avgHours = incCount > 0 ? round1(rs.getDouble("avg_res_hours")) : 0.0;
          double reopenPct = incCount > 0 ? round1((reopenProxy * 100.0) / incCount) : 0.0;
          double fcrPct = resolvedCnt > 0 ? round1((fcrCnt * 100.0) / resolvedCnt) : 100.0;
          return new ServiceExperienceDto(
              rs.getLong("id"),
              rs.getString("code"),
              rs.getString("name"),
              per100,
              avgHours,
              reopenPct,
              fcrPct);
        });
  }

  private ResolvedUser resolveUser(
      Long reqUserId,
      String userEmail,
      String reqUserName,
      Long reqDeptId,
      String reqDeptName) {
    Long userId = reqUserId;
    String userName = reqUserName;
    Long deptId = reqDeptId;
    String deptName = reqDeptName;

    String localPart =
        userEmail.contains("@") ? userEmail.substring(0, userEmail.indexOf('@')) : userEmail;

    List<Map<String, Object>> found =
        jdbc.queryForList(
            """
            SELECT u.id, u.full_name, u.department_id, d.name AS dept_name
            FROM users u
            LEFT JOIN departments d ON d.id = u.department_id
            WHERE (CAST(? AS BIGINT) IS NOT NULL AND u.id = CAST(? AS BIGINT))
               OR LOWER(u.email) = LOWER(?)
               OR LOWER(SPLIT_PART(u.email, '@', 1)) = LOWER(?)
            ORDER BY u.id ASC
            LIMIT 1
            """,
            userId,
            userId,
            userEmail,
            localPart);

    if (!found.isEmpty()) {
      Map<String, Object> row = found.getFirst();
      if (userId == null && row.get("id") != null) {
        userId = ((Number) row.get("id")).longValue();
      }
      if ((userName == null || userName.isBlank()) && row.get("full_name") != null) {
        userName = (String) row.get("full_name");
      }
      if (deptId == null && row.get("department_id") != null) {
        deptId = ((Number) row.get("department_id")).longValue();
      }
      if ((deptName == null || deptName.isBlank()) && row.get("dept_name") != null) {
        deptName = (String) row.get("dept_name");
      }
    }

    if (userName == null || userName.isBlank()) {
      userName = localPart;
    }
    if (deptName == null || deptName.isBlank()) {
      deptName = "Engineering";
    }
    return new ResolvedUser(userId, userName, deptId, deptName);
  }

  private DexEndpointDto mapEndpoint(ResultSet rs) throws SQLException {
    Map<String, Double> breakdown = new LinkedHashMap<>();
    try {
      String raw = rs.getString("score_breakdown");
      if (raw != null && !raw.isBlank()) {
        breakdown =
            TelemetryJson.MAPPER.readValue(raw, new TypeReference<LinkedHashMap<String, Double>>() {});
      }
    } catch (Exception ignored) {
    }
    return new DexEndpointDto(
        rs.getLong("id"),
        rs.getString("device_id"),
        rs.getObject("user_id") != null ? rs.getLong("user_id") : null,
        rs.getString("user_email"),
        rs.getString("user_name"),
        rs.getObject("department_id") != null ? rs.getLong("department_id") : null,
        rs.getString("department_name"),
        rs.getString("os_name"),
        rs.getString("os_version"),
        rs.getString("device_model"),
        rs.getDouble("cpu_usage_pct"),
        rs.getDouble("memory_usage_pct"),
        rs.getDouble("disk_usage_pct"),
        rs.getDouble("boot_time_seconds"),
        rs.getInt("crash_count_7d"),
        rs.getObject("battery_health_pct") != null ? rs.getDouble("battery_health_pct") : null,
        rs.getDouble("device_health_score"),
        rs.getDouble("experience_score"),
        breakdown,
        toInstant(rs.getTimestamp("last_seen_at")),
        rs.getBoolean("simulated"),
        toInstant(rs.getTimestamp("updated_at")));
  }

  private DexProactiveRuleDto mapProactiveRule(ResultSet rs) throws SQLException {
    return new DexProactiveRuleDto(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("department_name"),
        rs.getDouble("threshold_score"),
        rs.getBoolean("enabled"),
        rs.getObject("last_triggered_incident_id") != null
            ? rs.getLong("last_triggered_incident_id")
            : null,
        rs.getBoolean("is_demo"),
        toInstant(rs.getTimestamp("created_at")));
  }

  private static String trimToNull(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    return s.trim();
  }

  private static Instant toInstant(Timestamp ts) {
    return ts != null ? ts.toInstant() : null;
  }

  private static double clamp(double v, double min, double max) {
    return Math.max(min, Math.min(max, v));
  }

  private static double round1(double v) {
    return Math.round(v * 10.0) / 10.0;
  }

  public record ScoreComputation(
      double deviceHealth, double experienceScore, Map<String, Double> breakdown) {}

  private record ResolvedUser(
      Long userId, String userName, Long departmentId, String departmentName) {}
}
