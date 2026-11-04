package com.nexus360.finops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexus360.audit.AuditService;
import com.nexus360.common.ApiException;
import com.nexus360.finops.FinOpsDtos.*;
import com.nexus360.notification.Notification.Severity;
import com.nexus360.notification.NotificationService;
import com.nexus360.observability.TelemetryJson;
import com.nexus360.security.Actor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
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
public class FinOpsService {

  private static final Set<String> SOURCE_KINDS =
      Set.of("FOCUS_CSV", "MAPPED_CSV", "AWS_CUR", "AZURE_COST", "GCP_BILLING");
  private static final Set<String> BUDGET_TARGET_TYPES =
      Set.of("GLOBAL", "SERVICE", "DEPARTMENT", "COST_CENTER");

  private final JdbcTemplate jdbc;
  private final NotificationService notificationService;
  private final AuditService auditService;

  public FinOpsService(
      JdbcTemplate jdbc,
      NotificationService notificationService,
      AuditService auditService) {
    this.jdbc = jdbc;
    this.notificationService = notificationService;
    this.auditService = auditService;
  }

  @Transactional(readOnly = true)
  public FinOpsOverviewDto overview(boolean includeSimulated) {
    long itemCount =
        count(
            "SELECT COUNT(*) FROM finops_cost_items WHERE (? OR NOT simulated)",
            includeSimulated);
    if (itemCount == 0) {
      return new FinOpsOverviewDto(
          "INSUFFICIENT_DATA",
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          0L,
          0L,
          List.of(),
          List.of(),
          List.of(),
          false);
    }

    BigDecimal totalBilled =
        queryDecimal(
            "SELECT COALESCE(SUM(billed_cost), 0) FROM finops_cost_items WHERE (? OR NOT simulated)",
            includeSimulated);
    BigDecimal totalEffective =
        queryDecimal(
            "SELECT COALESCE(SUM(effective_cost), 0) FROM finops_cost_items WHERE (? OR NOT simulated)",
            includeSimulated);
    BigDecimal unallocated =
        queryDecimal(
            """
            SELECT COALESCE(SUM(effective_cost), 0)
            FROM finops_cost_items
            WHERE (? OR NOT simulated)
              AND allocated_cost_center IS NULL
              AND allocated_service_id IS NULL
            """,
            includeSimulated);
    BigDecimal potentialSavings =
        queryDecimal(
            """
            SELECT COALESCE(SUM(estimated_monthly_savings), 0)
            FROM finops_recommendations
            WHERE status = 'OPEN' AND (? OR NOT simulated)
            """,
            includeSimulated);
    long openAnomalies =
        count(
            "SELECT COUNT(*) FROM finops_anomalies WHERE status = 'OPEN' AND (? OR NOT simulated)",
            includeSimulated);
    long idleCount =
        count(
            """
            SELECT COUNT(*)
            FROM finops_recommendations
            WHERE category = 'IDLE_SHUTDOWN' AND status = 'OPEN' AND (? OR NOT simulated)
            """,
            includeSimulated);

    double totalEffDouble = Math.max(0.0001, totalEffective.doubleValue());

    List<AllocationBucketDto> byService =
        jdbc.query(
            """
            SELECT COALESCE(bs.code, fci.service_name) AS bucket_key,
                   COALESCE(bs.name, fci.service_name) AS bucket_label,
                   COALESCE(SUM(fci.billed_cost), 0) AS billed,
                   COALESCE(SUM(fci.effective_cost), 0) AS effective
            FROM finops_cost_items fci
            LEFT JOIN business_services bs ON bs.id = fci.allocated_service_id
            WHERE (? OR NOT fci.simulated)
            GROUP BY COALESCE(bs.code, fci.service_name), COALESCE(bs.name, fci.service_name)
            ORDER BY effective DESC
            """,
            (rs, i) -> {
              BigDecimal billed = rs.getBigDecimal("billed").setScale(2, RoundingMode.HALF_UP);
              BigDecimal eff = rs.getBigDecimal("effective").setScale(2, RoundingMode.HALF_UP);
              double share = round1((eff.doubleValue() / totalEffDouble) * 100.0);
              return new AllocationBucketDto(
                  rs.getString("bucket_key"), rs.getString("bucket_label"), billed, eff, share);
            },
            includeSimulated);

    List<AllocationBucketDto> byCostCenter =
        jdbc.query(
            """
            SELECT COALESCE(allocated_cost_center, 'UNALLOCATED') AS cc_key,
                   COALESCE(SUM(billed_cost), 0) AS billed,
                   COALESCE(SUM(effective_cost), 0) AS effective
            FROM finops_cost_items
            WHERE (? OR NOT simulated)
            GROUP BY COALESCE(allocated_cost_center, 'UNALLOCATED')
            ORDER BY effective DESC
            """,
            (rs, i) -> {
              String key = rs.getString("cc_key");
              BigDecimal billed = rs.getBigDecimal("billed").setScale(2, RoundingMode.HALF_UP);
              BigDecimal eff = rs.getBigDecimal("effective").setScale(2, RoundingMode.HALF_UP);
              double share = round1((eff.doubleValue() / totalEffDouble) * 100.0);
              return new AllocationBucketDto(key, key, billed, eff, share);
            },
            includeSimulated);

    List<DailyCostPointDto> dailyTrend =
        jdbc.query(
            """
            SELECT charge_date,
                   COALESCE(SUM(billed_cost), 0) AS billed,
                   COALESCE(SUM(effective_cost), 0) AS effective
            FROM finops_cost_items
            WHERE (? OR NOT simulated)
            GROUP BY charge_date
            ORDER BY charge_date ASC
            """,
            (rs, i) ->
                new DailyCostPointDto(
                    rs.getDate("charge_date").toLocalDate(),
                    rs.getBigDecimal("billed").setScale(2, RoundingMode.HALF_UP),
                    rs.getBigDecimal("effective").setScale(2, RoundingMode.HALF_UP)),
            includeSimulated);

    boolean anySim =
        includeSimulated
            && count("SELECT COUNT(*) FROM finops_cost_items WHERE simulated = TRUE") > 0;

    return new FinOpsOverviewDto(
        "OK",
        totalBilled.setScale(2, RoundingMode.HALF_UP),
        totalEffective.setScale(2, RoundingMode.HALF_UP),
        unallocated.setScale(2, RoundingMode.HALF_UP),
        potentialSavings.setScale(2, RoundingMode.HALF_UP),
        openAnomalies,
        idleCount,
        byService,
        byCostCenter,
        dailyTrend,
        anySim);
  }

  @Transactional(readOnly = true)
  public List<CostSourceDto> listSources() {
    return jdbc.query(
        "SELECT * FROM finops_cost_sources ORDER BY id ASC",
        (rs, i) -> mapSource(rs));
  }

  @Transactional
  public CostSourceDto createSource(CreateCostSourceRequest req, Actor actor) {
    String kind =
        req.kind() == null || req.kind().isBlank()
            ? "FOCUS_CSV"
            : req.kind().trim().toUpperCase(Locale.ROOT);
    if (!SOURCE_KINDS.contains(kind)) {
      throw ApiException.badRequest("Unsupported cost source kind: " + kind);
    }
    String provider =
        req.providerName() == null || req.providerName().isBlank()
            ? "AWS"
            : req.providerName().trim();
    Long existing =
        jdbc.query(
            "SELECT id FROM finops_cost_sources WHERE LOWER(name) = LOWER(?)",
            rs -> rs.next() ? rs.getLong(1) : null,
            req.name().trim());
    if (existing != null) {
      throw ApiException.conflict("Cost source already exists: " + req.name());
    }

    Map<String, String> mapping = req.columnMapping() != null ? req.columnMapping() : Map.of();
    KeyHolder kh = new GeneratedKeyHolder();
    jdbc.update(
        con -> {
          PreparedStatement ps =
              con.prepareStatement(
                  """
                  INSERT INTO finops_cost_sources
                    (name, kind, provider_name, column_mapping, status, is_demo, created_at)
                  VALUES (?, ?, ?, ?::jsonb, 'READY', FALSE, now())
                  """,
                  new String[] {"id"});
          ps.setString(1, req.name().trim());
          ps.setString(2, kind);
          ps.setString(3, provider);
          ps.setString(4, TelemetryJson.write(mapping));
          return ps;
        },
        kh);
    long id = kh.getKey().longValue();
    return getSource(id);
  }

  @Transactional(readOnly = true)
  public CostSourceDto getSource(long id) {
    List<CostSourceDto> list =
        jdbc.query(
            "SELECT * FROM finops_cost_sources WHERE id = ?",
            (rs, i) -> mapSource(rs),
            id);
    if (list.isEmpty()) {
      throw ApiException.notFound("CostSource", id);
    }
    return list.getFirst();
  }

  @Transactional
  public CostIngestResultDto ingest(CostIngestRequest req, Actor actor) {
    if (req.csvContent() == null || req.csvContent().isBlank()) {
      throw ApiException.badRequest("csvContent is required");
    }
    Long sourceId = req.sourceId();
    Map<String, String> mapping = new HashMap<>();
    String defaultProvider = "AWS";
    if (sourceId != null) {
      CostSourceDto src = getSource(sourceId);
      if (src.columnMapping() != null) {
        mapping.putAll(src.columnMapping());
      }
      defaultProvider = src.providerName();
    } else {
      List<CostSourceDto> existing = listSources();
      if (!existing.isEmpty()) {
        CostSourceDto first = existing.getFirst();
        sourceId = first.id();
        if (first.columnMapping() != null) {
          mapping.putAll(first.columnMapping());
        }
        defaultProvider = first.providerName();
      } else {
        CostSourceDto created =
            createSource(
                new CreateCostSourceRequest("Default FOCUS CSV Source", "FOCUS_CSV", "AWS", Map.of()),
                actor);
        sourceId = created.id();
      }
    }
    if (req.columnMapping() != null) {
      mapping.putAll(req.columnMapping());
    }

    boolean simulated = Boolean.TRUE.equals(req.simulated());
    String[] rawLines = req.csvContent().split("\\r?\\n");
    List<String> lines = new ArrayList<>();
    for (String line : rawLines) {
      if (line != null && !line.trim().isEmpty()) {
        lines.add(line);
      }
    }
    if (lines.size() < 2) {
      throw ApiException.badRequest("CSV must include a header row and at least one data row");
    }

    List<String> headers = parseCsvLine(lines.getFirst());
    Map<String, Integer> headerIndex = new HashMap<>();
    for (int i = 0; i < headers.size(); i++) {
      headerIndex.put(headers.get(i).trim().toLowerCase(Locale.ROOT), i);
    }

    List<ParsedCostRow> validRows = new ArrayList<>();
    List<RowErrorDto> errors = new ArrayList<>();
    BigDecimal totalBilled = BigDecimal.ZERO;
    BigDecimal totalEffective = BigDecimal.ZERO;

    for (int lineIdx = 1; lineIdx < lines.size(); lineIdx++) {
      int rowNumber = lineIdx + 1;
      List<String> cols = parseCsvLine(lines.get(lineIdx));

      String rawDate =
          resolveColumn(
              cols,
              headerIndex,
              mapping,
              "ChargePeriodStart",
              "ChargeDate",
              "charge_date",
              "date");
      String rawBilled =
          resolveColumn(
              cols, headerIndex, mapping, "BilledCost", "billed_cost", "cost", "amount");
      String rawEffective =
          resolveColumn(
              cols, headerIndex, mapping, "EffectiveCost", "effective_cost", "amortized_cost");
      String rawResourceId =
          resolveColumn(
              cols, headerIndex, mapping, "ResourceId", "resource_id", "resource");
      String rawResourceName =
          resolveColumn(
              cols, headerIndex, mapping, "ResourceName", "resource_name");
      String rawServiceName =
          resolveColumn(
              cols, headerIndex, mapping, "ServiceName", "service_name", "service");
      String rawProvider =
          resolveColumn(
              cols, headerIndex, mapping, "ProviderName", "provider_name", "provider");
      String rawSubAccount =
          resolveColumn(
              cols, headerIndex, mapping, "SubAccountId", "sub_account_id", "account_id");
      String rawRegion =
          resolveColumn(
              cols, headerIndex, mapping, "RegionId", "region_id", "region");
      String rawCurrency =
          resolveColumn(
              cols, headerIndex, mapping, "BillingCurrency", "Currency", "currency");
      String rawTags =
          resolveColumn(cols, headerIndex, mapping, "Tags", "tags");
      String rawCpu =
          resolveColumn(cols, headerIndex, mapping, "CpuUtilAvg", "cpu_util_avg", "cpu");
      String rawMem =
          resolveColumn(cols, headerIndex, mapping, "MemUtilAvg", "mem_util_avg", "memory");

      boolean rowValid = true;
      LocalDate chargeDate = null;
      if (rawDate == null || rawDate.isBlank()) {
        errors.add(new RowErrorDto(rowNumber, "ChargePeriodStart", "Charge date is required"));
        rowValid = false;
      } else {
        try {
          String datePart = rawDate.trim();
          if (datePart.length() >= 10) {
            datePart = datePart.substring(0, 10);
          }
          chargeDate = LocalDate.parse(datePart);
        } catch (Exception e) {
          errors.add(
              new RowErrorDto(
                  rowNumber, "ChargePeriodStart", "Invalid date format: " + rawDate.trim()));
          rowValid = false;
        }
      }

      BigDecimal billedCost = null;
      if (rawBilled == null || rawBilled.isBlank()) {
        errors.add(new RowErrorDto(rowNumber, "BilledCost", "BilledCost is required"));
        rowValid = false;
      } else {
        try {
          billedCost = new BigDecimal(rawBilled.trim());
          if (billedCost.compareTo(BigDecimal.ZERO) < 0) {
            errors.add(new RowErrorDto(rowNumber, "BilledCost", "BilledCost must be >= 0"));
            rowValid = false;
          }
        } catch (NumberFormatException e) {
          errors.add(
              new RowErrorDto(
                  rowNumber, "BilledCost", "Invalid numeric value: " + rawBilled.trim()));
          rowValid = false;
        }
      }

      BigDecimal effectiveCost = null;
      if (rawEffective == null || rawEffective.isBlank()) {
        effectiveCost = billedCost;
      } else {
        try {
          effectiveCost = new BigDecimal(rawEffective.trim());
          if (effectiveCost.compareTo(BigDecimal.ZERO) < 0) {
            errors.add(new RowErrorDto(rowNumber, "EffectiveCost", "EffectiveCost must be >= 0"));
            rowValid = false;
          }
        } catch (NumberFormatException e) {
          errors.add(
              new RowErrorDto(
                  rowNumber, "EffectiveCost", "Invalid numeric value: " + rawEffective.trim()));
          rowValid = false;
        }
      }

      if (rawResourceId == null || rawResourceId.isBlank()) {
        errors.add(new RowErrorDto(rowNumber, "ResourceId", "ResourceId is required"));
        rowValid = false;
      }
      if (rawServiceName == null || rawServiceName.isBlank()) {
        errors.add(new RowErrorDto(rowNumber, "ServiceName", "ServiceName is required"));
        rowValid = false;
      }

      if (!rowValid) {
        continue;
      }

      Map<String, String> tags = parseTags(rawTags);
      Double cpuUtil = parseOptionalDouble(rawCpu);
      Double memUtil = parseOptionalDouble(rawMem);

      totalBilled = totalBilled.add(billedCost);
      totalEffective = totalEffective.add(effectiveCost);

      validRows.add(
          new ParsedCostRow(
              chargeDate,
              rawProvider != null && !rawProvider.isBlank() ? rawProvider.trim() : defaultProvider,
              rawSubAccount != null && !rawSubAccount.isBlank() ? rawSubAccount.trim() : null,
              rawServiceName.trim(),
              rawResourceId.trim(),
              rawResourceName != null && !rawResourceName.isBlank()
                  ? rawResourceName.trim()
                  : rawResourceId.trim(),
              rawRegion != null && !rawRegion.isBlank() ? rawRegion.trim() : "us-east-1",
              billedCost,
              effectiveCost,
              rawCurrency != null && !rawCurrency.isBlank()
                  ? rawCurrency.trim().toUpperCase(Locale.ROOT)
                  : "USD",
              tags,
              cpuUtil,
              memUtil));
    }

    if (req.dryRun() || !errors.isEmpty()) {
      return new CostIngestResultDto(
          req.dryRun(),
          validRows.size(),
          errors.size(),
          totalBilled.setScale(2, RoundingMode.HALF_UP),
          totalEffective.setScale(2, RoundingMode.HALF_UP),
          errors,
          0);
    }

    int applied = 0;
    for (ParsedCostRow row : validRows) {
      String dedupeKey = row.chargeDate() + ":" + row.resourceId() + ":" + row.serviceName();
      jdbc.update(
          """
          INSERT INTO finops_cost_items
            (source_id, dedupe_key, charge_date, provider_name, sub_account_id, service_name,
             resource_id, resource_name, region_id, billed_cost, effective_cost, currency,
             tags, cpu_util_avg, mem_util_avg, simulated, created_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, now())
          ON CONFLICT (source_id, dedupe_key) DO UPDATE SET
            provider_name = EXCLUDED.provider_name,
            sub_account_id = EXCLUDED.sub_account_id,
            resource_name = EXCLUDED.resource_name,
            region_id = EXCLUDED.region_id,
            billed_cost = EXCLUDED.billed_cost,
            effective_cost = EXCLUDED.effective_cost,
            currency = EXCLUDED.currency,
            tags = EXCLUDED.tags,
            cpu_util_avg = COALESCE(EXCLUDED.cpu_util_avg, finops_cost_items.cpu_util_avg),
            mem_util_avg = COALESCE(EXCLUDED.mem_util_avg, finops_cost_items.mem_util_avg),
            simulated = EXCLUDED.simulated
          """,
          sourceId,
          dedupeKey,
          Date.valueOf(row.chargeDate()),
          row.providerName(),
          row.subAccountId(),
          row.serviceName(),
          row.resourceId(),
          row.resourceName(),
          row.regionId(),
          row.billedCost(),
          row.effectiveCost(),
          row.currency(),
          TelemetryJson.write(row.tags()),
          row.cpuUtilAvg(),
          row.memUtilAvg(),
          simulated);
      applied++;
    }

    jdbc.update(
        "UPDATE finops_cost_sources SET last_ingested_at = now(), status = 'CONNECTED' WHERE id = ?",
        sourceId);

    applyAllocationRules();
    evaluateBudgets(true);
    detectAnomalies(simulated);
    generateRecommendations(simulated);

    return new CostIngestResultDto(
        false,
        validRows.size(),
        0,
        totalBilled.setScale(2, RoundingMode.HALF_UP),
        totalEffective.setScale(2, RoundingMode.HALF_UP),
        List.of(),
        applied);
  }

  @Transactional(readOnly = true)
  public List<AllocationRuleDto> listAllocationRules() {
    return jdbc.query(
        "SELECT * FROM finops_allocation_rules ORDER BY priority ASC, id ASC",
        (rs, i) -> mapAllocationRule(rs));
  }

  @Transactional
  public AllocationRuleDto createAllocationRule(CreateAllocationRuleRequest req, Actor actor) {
    int priority = req.priority() != null ? req.priority() : 100;
    double split = req.splitPercent() != null ? req.splitPercent() : 100.0;
    boolean enabled = req.enabled() == null || req.enabled();

    KeyHolder kh = new GeneratedKeyHolder();
    jdbc.update(
        con -> {
          PreparedStatement ps =
              con.prepareStatement(
                  """
                  INSERT INTO finops_allocation_rules
                    (name, priority, tag_key, tag_value, sub_account_id, resource_pattern,
                     target_service_id, target_department_id, target_cost_center,
                     split_percent, enabled, is_demo, created_at)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, now())
                  """,
                  new String[] {"id"});
          ps.setString(1, req.name().trim());
          ps.setInt(2, priority);
          ps.setString(3, trimToNull(req.tagKey()));
          ps.setString(4, trimToNull(req.tagValue()));
          ps.setString(5, trimToNull(req.subAccountId()));
          ps.setString(6, trimToNull(req.resourcePattern()));
          if (req.targetServiceId() != null) {
            ps.setLong(7, req.targetServiceId());
          } else {
            ps.setNull(7, Types.BIGINT);
          }
          if (req.targetDepartmentId() != null) {
            ps.setLong(8, req.targetDepartmentId());
          } else {
            ps.setNull(8, Types.BIGINT);
          }
          ps.setString(9, req.targetCostCenter().trim());
          ps.setDouble(10, split);
          ps.setBoolean(11, enabled);
          return ps;
        },
        kh);
    long id = kh.getKey().longValue();
    applyAllocationRules();
    return jdbc.query(
            "SELECT * FROM finops_allocation_rules WHERE id = ?",
            (rs, i) -> mapAllocationRule(rs),
            id)
        .getFirst();
  }

  @Transactional
  public void applyAllocationRules() {
    List<AllocationRuleDto> rules =
        jdbc.query(
            "SELECT * FROM finops_allocation_rules WHERE enabled = TRUE ORDER BY priority ASC, id ASC",
            (rs, i) -> mapAllocationRule(rs));
    if (rules.isEmpty()) {
      return;
    }

    Map<String, Long> serviceByKey = new HashMap<>();
    jdbc.query(
        "SELECT id, LOWER(code) AS code, LOWER(name) AS name FROM business_services",
        rs -> {
          long id = rs.getLong("id");
          if (rs.getString("code") != null) {
            serviceByKey.put(rs.getString("code"), id);
          }
          if (rs.getString("name") != null) {
            serviceByKey.put(rs.getString("name"), id);
          }
        });

    List<Map<String, Object>> items =
        jdbc.queryForList(
            "SELECT id, sub_account_id, service_name, resource_id, resource_name, tags FROM finops_cost_items");

    for (Map<String, Object> item : items) {
      long itemId = ((Number) item.get("id")).longValue();
      String subAccount = (String) item.get("sub_account_id");
      String serviceName = (String) item.get("service_name");
      String resourceId = (String) item.get("resource_id");
      String resourceName = (String) item.get("resource_name");
      Map<String, String> tags =
          TelemetryJson.stringMap(String.valueOf(item.getOrDefault("tags", "{}")));

      for (AllocationRuleDto rule : rules) {
        if (matchesRule(rule, subAccount, serviceName, resourceId, resourceName, tags)) {
          Long targetServiceId = rule.targetServiceId();
          if (targetServiceId == null && tags.containsKey("service")) {
            targetServiceId = serviceByKey.get(tags.get("service").toLowerCase(Locale.ROOT));
          }
          String costCenter = rule.targetCostCenter();
          for (Map.Entry<String, String> te : tags.entrySet()) {
            if (te.getKey().equalsIgnoreCase("costCenter") && te.getValue() != null && !te.getValue().isBlank()) {
              costCenter = te.getValue();
              break;
            }
          }
          jdbc.update(
              """
              UPDATE finops_cost_items
              SET allocated_service_id = COALESCE(?, allocated_service_id),
                  allocated_department_id = COALESCE(?, allocated_department_id),
                  allocated_cost_center = ?
              WHERE id = ?
              """,
              targetServiceId,
              rule.targetDepartmentId(),
              costCenter,
              itemId);
          break;
        }
      }
    }
  }

  private boolean matchesRule(
      AllocationRuleDto rule,
      String subAccount,
      String serviceName,
      String resourceId,
      String resourceName,
      Map<String, String> tags) {
    boolean hasCondition = false;
    if (rule.tagKey() != null && !rule.tagKey().isBlank()) {
      hasCondition = true;
      String val = null;
      for (Map.Entry<String, String> e : tags.entrySet()) {
        if (e.getKey().equalsIgnoreCase(rule.tagKey())) {
          val = e.getValue();
          break;
        }
      }
      if (val == null) {
        return false;
      }
      if (rule.tagValue() != null
          && !rule.tagValue().isBlank()
          && !val.equalsIgnoreCase(rule.tagValue())) {
        return false;
      }
    }
    if (rule.subAccountId() != null && !rule.subAccountId().isBlank()) {
      hasCondition = true;
      if (subAccount == null || !subAccount.equalsIgnoreCase(rule.subAccountId())) {
        return false;
      }
    }
    if (rule.resourcePattern() != null && !rule.resourcePattern().isBlank()) {
      hasCondition = true;
      String pat = rule.resourcePattern().toLowerCase(Locale.ROOT);
      String rId = resourceId != null ? resourceId.toLowerCase(Locale.ROOT) : "";
      String rName = resourceName != null ? resourceName.toLowerCase(Locale.ROOT) : "";
      String sName = serviceName != null ? serviceName.toLowerCase(Locale.ROOT) : "";
      if (!rId.contains(pat) && !rName.contains(pat) && !sName.contains(pat)) {
        return false;
      }
    }
    return hasCondition;
  }

  @Transactional(readOnly = true)
  public List<BudgetStatusDto> listBudgets(boolean includeSimulated) {
    return jdbc.query(
        "SELECT * FROM finops_budgets ORDER BY id ASC",
        (rs, i) -> computeBudgetStatus(rs, includeSimulated));
  }

  @Transactional
  public BudgetStatusDto createBudget(CreateBudgetRequest req, Actor actor) {
    String targetType =
        req.targetType() == null || req.targetType().isBlank()
            ? "GLOBAL"
            : req.targetType().trim().toUpperCase(Locale.ROOT);
    if (!BUDGET_TARGET_TYPES.contains(targetType)) {
      throw ApiException.badRequest("Unsupported budget targetType: " + targetType);
    }
    if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
      throw ApiException.badRequest("Budget amount must be > 0");
    }
    String targetKey =
        req.targetKey() == null || req.targetKey().isBlank() ? "ALL" : req.targetKey().trim();
    String periodMonth =
        req.periodMonth() == null || req.periodMonth().isBlank()
            ? YearMonth.now().toString()
            : req.periodMonth().trim();
    List<Integer> thresholds =
        req.thresholds() != null && !req.thresholds().isEmpty()
            ? req.thresholds()
            : List.of(50, 80, 100);

    KeyHolder kh = new GeneratedKeyHolder();
    jdbc.update(
        con -> {
          PreparedStatement ps =
              con.prepareStatement(
                  """
                  INSERT INTO finops_budgets
                    (name, target_type, target_key, period_month, amount, thresholds,
                     last_notified_threshold, is_demo, created_at)
                  VALUES (?, ?, ?, ?, ?, ?::jsonb, 0, FALSE, now())
                  """,
                  new String[] {"id"});
          ps.setString(1, req.name().trim());
          ps.setString(2, targetType);
          ps.setString(3, targetKey);
          ps.setString(4, periodMonth);
          ps.setBigDecimal(5, req.amount());
          ps.setString(6, TelemetryJson.write(thresholds));
          return ps;
        },
        kh);
    long id = kh.getKey().longValue();
    evaluateBudgets(true);
    return jdbc.query(
            "SELECT * FROM finops_budgets WHERE id = ?",
            (rs, i) -> computeBudgetStatus(rs, true),
            id)
        .getFirst();
  }

  @Transactional
  public void evaluateBudgets(boolean includeSimulated) {
    List<Long> ids =
        jdbc.query("SELECT id FROM finops_budgets ORDER BY id ASC", (rs, i) -> rs.getLong(1));
    for (Long id : ids) {
      jdbc.query(
          "SELECT * FROM finops_budgets WHERE id = ?",
          rs -> {
            if (!rs.next()) {
              return null;
            }
            int lastNotified = rs.getInt("last_notified_threshold");
            BudgetStatusDto status = computeBudgetStatus(rs, includeSimulated);
            int maxTriggered =
                status.triggeredThresholds().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (maxTriggered > lastNotified) {
              jdbc.update(
                  "UPDATE finops_budgets SET last_notified_threshold = ? WHERE id = ?",
                  maxTriggered,
                  id);
              notifyBudgetThreshold(status, maxTriggered);
            }
            return null;
          },
          id);
    }
  }

  private void notifyBudgetThreshold(BudgetStatusDto budget, int threshold) {
    List<Long> recipients =
        jdbc.query(
            """
            SELECT id FROM users
            WHERE active = TRUE
              AND role IN ('FINOPS_ANALYST', 'IT_MANAGER', 'SYSTEM_ADMIN')
            """,
            (rs, i) -> rs.getLong(1));
    String title =
        "FinOps Budget Alert: " + budget.name() + " crossed " + threshold + "%";
    String message =
        String.format(
            Locale.ROOT,
            "Budget '%s' (%s) reached %.1f%% utilization ($%s of $%s, forecasted EOM: $%s).",
            budget.name(),
            budget.periodMonth(),
            budget.utilizationPercent(),
            budget.spentToDate(),
            budget.amount(),
            budget.forecastedEndOfMonth());
    notificationService.notifyUsers(
        recipients,
        "FINOPS_BUDGET_THRESHOLD",
        title,
        message,
        "/finops",
        Severity.CRITICAL);
  }

  private BudgetStatusDto computeBudgetStatus(ResultSet rs, boolean includeSimulated)
      throws SQLException {
    long id = rs.getLong("id");
    String name = rs.getString("name");
    String targetType = rs.getString("target_type");
    String targetKey = rs.getString("target_key");
    String periodMonth = rs.getString("period_month");
    BigDecimal amount = rs.getBigDecimal("amount").setScale(2, RoundingMode.HALF_UP);
    List<Integer> thresholds = parseThresholds(rs.getString("thresholds"));

    StringBuilder sql =
        new StringBuilder(
            """
            SELECT COALESCE(SUM(effective_cost), 0) AS spent,
                   COUNT(DISTINCT charge_date) AS days_count
            FROM finops_cost_items
            WHERE (? OR NOT simulated)
            """);
    List<Object> params = new ArrayList<>();
    params.add(includeSimulated);

    long monthMatches =
        count(
            "SELECT COUNT(*) FROM finops_cost_items WHERE (? OR NOT simulated) AND to_char(charge_date, 'YYYY-MM') = ?",
            includeSimulated,
            periodMonth);
    if (monthMatches > 0) {
      sql.append(" AND to_char(charge_date, 'YYYY-MM') = ?");
      params.add(periodMonth);
    }

    if ("COST_CENTER".equalsIgnoreCase(targetType) && !"ALL".equalsIgnoreCase(targetKey)) {
      sql.append(" AND LOWER(COALESCE(allocated_cost_center, '')) = LOWER(?)");
      params.add(targetKey);
    } else if ("SERVICE".equalsIgnoreCase(targetType) && !"ALL".equalsIgnoreCase(targetKey)) {
      sql.append(
          " AND (LOWER(service_name) = LOWER(?) OR CAST(allocated_service_id AS TEXT) = ?)");
      params.add(targetKey);
      params.add(targetKey);
    } else if ("DEPARTMENT".equalsIgnoreCase(targetType) && !"ALL".equalsIgnoreCase(targetKey)) {
      sql.append(" AND CAST(allocated_department_id AS TEXT) = ?");
      params.add(targetKey);
    }

    record SpendRow(BigDecimal spent, int days) {}
    SpendRow spendRow =
        jdbc.query(
            sql.toString(),
            r -> {
              if (r.next()) {
                return new SpendRow(
                    r.getBigDecimal("spent") != null ? r.getBigDecimal("spent") : BigDecimal.ZERO,
                    r.getInt("days_count"));
              }
              return new SpendRow(BigDecimal.ZERO, 0);
            },
            params.toArray());

    BigDecimal spent = spendRow.spent().setScale(2, RoundingMode.HALF_UP);
    int daysInMonth = 30;
    try {
      daysInMonth = YearMonth.parse(periodMonth).lengthOfMonth();
    } catch (Exception ignored) {
    }
    int observedDays = Math.max(1, spendRow.days());
    BigDecimal dailyAvg =
        spendRow.days() > 0
            ? spent.divide(BigDecimal.valueOf(observedDays), 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
    BigDecimal forecasted =
        dailyAvg
            .multiply(BigDecimal.valueOf(daysInMonth))
            .max(spent)
            .setScale(2, RoundingMode.HALF_UP);

    double utilPct =
        amount.compareTo(BigDecimal.ZERO) > 0
            ? round1((spent.doubleValue() / amount.doubleValue()) * 100.0)
            : 0.0;

    List<Integer> triggered =
        thresholds.stream().filter(t -> utilPct >= t).sorted().toList();

    String status =
        utilPct >= 100.0
            ? "EXCEEDED"
            : (!triggered.isEmpty() || forecasted.compareTo(amount) > 0 ? "AT_RISK" : "ON_TRACK");

    return new BudgetStatusDto(
        id,
        name,
        targetType,
        targetKey,
        periodMonth,
        amount,
        spent,
        utilPct,
        forecasted,
        triggered,
        status);
  }

  private static List<Integer> parseThresholds(String jsonStr) {
    if (jsonStr == null || jsonStr.isBlank()) {
      return List.of(50, 80, 100);
    }
    try {
      return TelemetryJson.MAPPER.readValue(jsonStr, new TypeReference<List<Integer>>() {});
    } catch (Exception e) {
      return List.of(50, 80, 100);
    }
  }

  @Transactional
  public void detectAnomalies(boolean simulated) {
    List<String> scopes =
        jdbc.query(
            """
            SELECT DISTINCT resource_id
            FROM finops_cost_items
            GROUP BY resource_id
            HAVING COUNT(DISTINCT charge_date) >= 3
            """,
            (rs, i) -> rs.getString(1));

    for (String scopeKey : scopes) {
      record DayCost(LocalDate date, BigDecimal cost, boolean sim) {}
      List<DayCost> series =
          jdbc.query(
              """
              SELECT charge_date,
                     SUM(effective_cost) AS cost,
                     BOOL_OR(simulated) AS sim
              FROM finops_cost_items
              WHERE resource_id = ?
              GROUP BY charge_date
              ORDER BY charge_date ASC
              """,
              (rs, i) ->
                  new DayCost(
                      rs.getDate("charge_date").toLocalDate(),
                      rs.getBigDecimal("cost"),
                      rs.getBoolean("sim")),
              scopeKey);

      if (series.size() < 3) {
        continue;
      }

      double ewma = series.getFirst().cost().doubleValue();
      double ewmaVar = 1.0;
      double alpha = 0.35;

      for (int i = 1; i < series.size(); i++) {
        DayCost pt = series.get(i);
        double actual = pt.cost().doubleValue();
        double expected = Math.max(0.01, ewma);
        double std = Math.max(Math.sqrt(ewmaVar), expected * 0.12);
        double upper = expected + Math.max(expected * 0.40, 2.5 * std);

        if (i >= 2 && actual > upper && (actual - expected) >= 20.0) {
          double z = round1((actual - expected) / Math.max(1.0, std));
          Long existingId =
              jdbc.query(
                  "SELECT id FROM finops_anomalies WHERE scope_key = ? AND charge_date = ?",
                  rs -> rs.next() ? rs.getLong(1) : null,
                  scopeKey,
                  Date.valueOf(pt.date()));
          if (existingId == null) {
            jdbc.update(
                """
                INSERT INTO finops_anomalies
                  (scope_key, charge_date, actual_cost, expected_cost, upper_bound,
                   deviation_score, status, simulated, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, now())
                """,
                scopeKey,
                Date.valueOf(pt.date()),
                BigDecimal.valueOf(actual).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(expected).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(upper).setScale(4, RoundingMode.HALF_UP),
                z,
                pt.sim() || simulated);
          }
        }

        double diff = actual - ewma;
        ewma = alpha * actual + (1.0 - alpha) * ewma;
        ewmaVar = (1.0 - alpha) * (ewmaVar + alpha * diff * diff);
      }
    }
  }

  @Transactional(readOnly = true)
  public List<CostAnomalyDto> listAnomalies(boolean includeSimulated) {
    return jdbc.query(
        """
        SELECT * FROM finops_anomalies
        WHERE (? OR NOT simulated)
        ORDER BY deviation_score DESC, charge_date DESC
        """,
        (rs, i) ->
            new CostAnomalyDto(
                rs.getLong("id"),
                rs.getString("scope_key"),
                rs.getDate("charge_date").toLocalDate(),
                rs.getBigDecimal("actual_cost").setScale(2, RoundingMode.HALF_UP),
                rs.getBigDecimal("expected_cost").setScale(2, RoundingMode.HALF_UP),
                rs.getBigDecimal("upper_bound").setScale(2, RoundingMode.HALF_UP),
                rs.getDouble("deviation_score"),
                rs.getString("status"),
                rs.getBoolean("simulated"),
                toInstant(rs.getTimestamp("created_at"))),
        includeSimulated);
  }

  @Transactional
  public void generateRecommendations(boolean simulated) {
    record ResourceStats(
        String resourceId,
        String resourceName,
        String serviceName,
        double avgDailyCost,
        Double avgCpu,
        Double avgMem,
        boolean sim) {}

    List<ResourceStats> stats =
        jdbc.query(
            """
            SELECT resource_id,
                   MAX(resource_name) AS resource_name,
                   MAX(service_name) AS service_name,
                   AVG(effective_cost) AS avg_daily_cost,
                   AVG(cpu_util_avg) AS avg_cpu,
                   AVG(mem_util_avg) AS avg_mem,
                   BOOL_OR(simulated) AS sim
            FROM finops_cost_items
            GROUP BY resource_id
            """,
            (rs, i) ->
                new ResourceStats(
                    rs.getString("resource_id"),
                    rs.getString("resource_name"),
                    rs.getString("service_name"),
                    rs.getDouble("avg_daily_cost"),
                    rs.getObject("avg_cpu") != null ? rs.getDouble("avg_cpu") : null,
                    rs.getObject("avg_mem") != null ? rs.getDouble("avg_mem") : null,
                    rs.getBoolean("sim")));

    for (ResourceStats st : stats) {
      Long existing =
          jdbc.query(
              "SELECT id FROM finops_recommendations WHERE resource_id = ?",
              rs -> rs.next() ? rs.getLong(1) : null,
              st.resourceId());
      if (existing != null) {
        continue;
      }

      boolean isIdleByCpu = st.avgCpu() != null && st.avgCpu() < 6.0;
      boolean isIdleByName =
          st.resourceId().toLowerCase(Locale.ROOT).contains("idle")
              || st.resourceId().toLowerCase(Locale.ROOT).contains("unused");

      if (isIdleByCpu || isIdleByName) {
        double monthly = Math.max(45.0, st.avgDailyCost() * 30.0 * 0.90);
        String cpuNote =
            st.avgCpu() != null
                ? String.format(Locale.ROOT, "%.1f%% avg CPU", st.avgCpu())
                : "near-zero utilization";
        jdbc.update(
            """
            INSERT INTO finops_recommendations
              (category, resource_id, resource_name, service_name, title, evidence,
               estimated_monthly_savings, status, simulated, created_at)
            VALUES ('IDLE_SHUTDOWN', ?, ?, ?, ?, ?, ?, 'OPEN', ?, now())
            """,
            st.resourceId(),
            st.resourceName(),
            st.serviceName(),
            "Decommission or stop idle resource " + st.resourceId(),
            "Resource "
                + st.resourceId()
                + " exhibits "
                + cpuNote
                + " with average daily spend of $"
                + String.format(Locale.ROOT, "%.2f", st.avgDailyCost())
                + ".",
            BigDecimal.valueOf(monthly).setScale(2, RoundingMode.HALF_UP),
            st.sim() || simulated);
      } else if (st.avgCpu() != null && st.avgCpu() < 30.0 && st.avgDailyCost() >= 8.0) {
        double monthly = Math.max(25.0, st.avgDailyCost() * 30.0 * 0.40);
        jdbc.update(
            """
            INSERT INTO finops_recommendations
              (category, resource_id, resource_name, service_name, title, evidence,
               estimated_monthly_savings, status, simulated, created_at)
            VALUES ('RIGHTSIZING', ?, ?, ?, ?, ?, ?, 'OPEN', ?, now())
            """,
            st.resourceId(),
            st.resourceName(),
            st.serviceName(),
            "Rightsize underutilized instance " + st.resourceId(),
            String.format(
                Locale.ROOT,
                "Average CPU utilization is %.1f%% (memory %.1f%%) at $%.2f/day; downsizing one tier saves ~40%%.",
                st.avgCpu(),
                st.avgMem() != null ? st.avgMem() : 25.0,
                st.avgDailyCost()),
            BigDecimal.valueOf(monthly).setScale(2, RoundingMode.HALF_UP),
            st.sim() || simulated);
      }
    }
  }

  @Transactional(readOnly = true)
  public List<CostRecommendationDto> listRecommendations(boolean includeSimulated) {
    return jdbc.query(
        """
        SELECT * FROM finops_recommendations
        WHERE (? OR NOT simulated)
        ORDER BY estimated_monthly_savings DESC, id ASC
        """,
        (rs, i) -> mapRecommendation(rs),
        includeSimulated);
  }

  @Transactional
  public CostRecommendationDto acceptRecommendation(long id, Actor actor) {
    CostRecommendationDto rec = getRecommendation(id);
    if ("ACCEPTED".equals(rec.status())) {
      return rec;
    }

    Long seq = jdbc.queryForObject("SELECT nextval('change_number_seq')", Long.class);
    String changeNumber = String.format("CHG-%06d", seq != null ? seq : id);
    Long requesterId = resolveActorUserId(actor);

    KeyHolder kh = new GeneratedKeyHolder();
    jdbc.update(
        con -> {
          PreparedStatement ps =
              con.prepareStatement(
                  """
                  INSERT INTO changes
                    (number, title, description, change_type, risk, status,
                     requester_id, implementation_plan, rollback_plan, test_plan,
                     is_demo, created_at, updated_at)
                  VALUES (?, ?, ?, 'NORMAL', 'LOW', 'DRAFT', ?, ?, ?, ?, FALSE, now(), now())
                  """,
                  new String[] {"id"});
          ps.setString(1, changeNumber);
          ps.setString(2, "FinOps Optimization: " + rec.title());
          ps.setString(3, rec.evidence());
          if (requesterId != null) {
            ps.setLong(4, requesterId);
          } else {
            ps.setNull(4, Types.BIGINT);
          }
          ps.setString(5, "Execute FinOps recommendation on resource " + rec.resourceId());
          ps.setString(6, "Revert resource configuration for " + rec.resourceId());
          ps.setString(7, "Verify service latency and error rates after rightsizing/shutdown.");
          return ps;
        },
        kh);
    long changeId = kh.getKey().longValue();

    jdbc.update(
        """
        UPDATE finops_recommendations
        SET status = 'ACCEPTED', change_request_id = ?
        WHERE id = ?
        """,
        changeId,
        id);

    auditService.record(
        "FINOPS_RECOMMENDATION_ACCEPTED",
        "FINOPS_RECOMMENDATION",
        id,
        Map.of("resourceId", rec.resourceId(), "changeRequestId", changeId));

    return getRecommendation(id);
  }

  @Transactional
  public CostRecommendationDto dismissRecommendation(
      long id, DismissRecommendationRequest req, Actor actor) {
    getRecommendation(id);
    String reason =
        req != null && req.reason() != null && !req.reason().isBlank()
            ? req.reason().trim()
            : "Dismissed by FinOps analyst";
    jdbc.update(
        """
        UPDATE finops_recommendations
        SET status = 'DISMISSED', dismiss_reason = ?
        WHERE id = ?
        """,
        reason,
        id);
    return getRecommendation(id);
  }

  @Transactional(readOnly = true)
  public CostRecommendationDto getRecommendation(long id) {
    List<CostRecommendationDto> list =
        jdbc.query(
            "SELECT * FROM finops_recommendations WHERE id = ?",
            (rs, i) -> mapRecommendation(rs),
            id);
    if (list.isEmpty()) {
      throw ApiException.notFound("FinOpsRecommendation", id);
    }
    return list.getFirst();
  }

  private CostSourceDto mapSource(ResultSet rs) throws SQLException {
    Map<String, String> mapping = TelemetryJson.stringMap(rs.getString("column_mapping"));
    return new CostSourceDto(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("kind"),
        rs.getString("provider_name"),
        mapping,
        rs.getString("status"),
        toInstant(rs.getTimestamp("last_ingested_at")),
        rs.getBoolean("is_demo"),
        toInstant(rs.getTimestamp("created_at")));
  }

  private AllocationRuleDto mapAllocationRule(ResultSet rs) throws SQLException {
    return new AllocationRuleDto(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getInt("priority"),
        rs.getString("tag_key"),
        rs.getString("tag_value"),
        rs.getString("sub_account_id"),
        rs.getString("resource_pattern"),
        rs.getObject("target_service_id") != null ? rs.getLong("target_service_id") : null,
        rs.getObject("target_department_id") != null ? rs.getLong("target_department_id") : null,
        rs.getString("target_cost_center"),
        rs.getDouble("split_percent"),
        rs.getBoolean("enabled"),
        rs.getBoolean("is_demo"),
        toInstant(rs.getTimestamp("created_at")));
  }

  private CostRecommendationDto mapRecommendation(ResultSet rs) throws SQLException {
    return new CostRecommendationDto(
        rs.getLong("id"),
        rs.getString("category"),
        rs.getString("resource_id"),
        rs.getString("resource_name"),
        rs.getString("service_name"),
        rs.getString("title"),
        rs.getString("evidence"),
        rs.getBigDecimal("estimated_monthly_savings").setScale(2, RoundingMode.HALF_UP),
        rs.getString("status"),
        rs.getObject("change_request_id") != null ? rs.getLong("change_request_id") : null,
        rs.getString("dismiss_reason"),
        rs.getBoolean("simulated"),
        toInstant(rs.getTimestamp("created_at")));
  }

  private Long resolveActorUserId(Actor actor) {
    if (actor == null) {
      return null;
    }
    if (actor.id() != null) {
      return actor.id();
    }
    if (actor.email() != null) {
      return jdbc.query(
          "SELECT id FROM users WHERE LOWER(SPLIT_PART(email, '@', 1)) = LOWER(?) OR LOWER(email) = LOWER(?)",
          rs -> rs.next() ? rs.getLong(1) : null,
          actor.email(),
          actor.email());
    }
    return null;
  }

  private static String resolveColumn(
      List<String> cols,
      Map<String, Integer> headerIndex,
      Map<String, String> mapping,
      String... candidates) {
    for (String candidate : candidates) {
      String mappedHeader = mapping.get(candidate);
      if (mappedHeader != null) {
        Integer idx = headerIndex.get(mappedHeader.trim().toLowerCase(Locale.ROOT));
        if (idx != null && idx < cols.size()) {
          return cols.get(idx);
        }
      }
      Integer idx = headerIndex.get(candidate.toLowerCase(Locale.ROOT));
      if (idx != null && idx < cols.size()) {
        return cols.get(idx);
      }
    }
    return null;
  }

  private Map<String, String> parseTags(String rawTags) {
    if (rawTags == null || rawTags.isBlank()) {
      return Map.of();
    }
    String trimmed = rawTags.trim();
    if (trimmed.startsWith("{")) {
      return TelemetryJson.stringMap(trimmed);
    }
    Map<String, String> map = new LinkedHashMap<>();
    String[] pairs = trimmed.split("[;|]");
    for (String pair : pairs) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        eq = pair.indexOf(':');
      }
      if (eq > 0) {
        map.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
      }
    }
    return map;
  }

  private static Double parseOptionalDouble(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Double.parseDouble(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static List<String> parseCsvLine(String line) {
    List<String> result = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          sb.append('"');
          i++;
        } else {
          inQuotes = !inQuotes;
        }
      } else if (c == ',' && !inQuotes) {
        result.add(sb.toString().trim());
        sb.setLength(0);
      } else {
        sb.append(c);
      }
    }
    result.add(sb.toString().trim());
    return result;
  }

  private long count(String sql, Object... args) {
    Long v = jdbc.queryForObject(sql, Long.class, args);
    return v != null ? v : 0L;
  }

  private BigDecimal queryDecimal(String sql, Object... args) {
    BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, args);
    return v != null ? v : BigDecimal.ZERO;
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

  private static double round1(double v) {
    return Math.round(v * 10.0) / 10.0;
  }

  private record ParsedCostRow(
      LocalDate chargeDate,
      String providerName,
      String subAccountId,
      String serviceName,
      String resourceId,
      String resourceName,
      String regionId,
      BigDecimal billedCost,
      BigDecimal effectiveCost,
      String currency,
      Map<String, String> tags,
      Double cpuUtilAvg,
      Double memUtilAvg) {}
}
