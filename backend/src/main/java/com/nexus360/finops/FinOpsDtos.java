package com.nexus360.finops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class FinOpsDtos {

  private FinOpsDtos() {}

  public record AllocationBucketDto(
      String key,
      String label,
      BigDecimal billedCost,
      BigDecimal effectiveCost,
      double sharePercent) {}

  public record DailyCostPointDto(
      LocalDate chargeDate,
      BigDecimal billedCost,
      BigDecimal effectiveCost) {}

  public record FinOpsOverviewDto(
      String status,
      BigDecimal totalBilledCost,
      BigDecimal totalEffectiveCost,
      BigDecimal unallocatedCost,
      BigDecimal potentialMonthlySavings,
      long openAnomalies,
      long idleResourcesCount,
      List<AllocationBucketDto> byService,
      List<AllocationBucketDto> byCostCenter,
      List<DailyCostPointDto> dailyTrend,
      boolean simulated) {}

  public record CostSourceDto(
      Long id,
      String name,
      String kind,
      String providerName,
      Map<String, String> columnMapping,
      String status,
      Instant lastIngestedAt,
      boolean isDemo,
      Instant createdAt) {}

  public record CreateCostSourceRequest(
      @NotBlank @Size(max = 140) String name,
      String kind,
      String providerName,
      Map<String, String> columnMapping) {}

  public record CostIngestRequest(
      Long sourceId,
      String format,
      boolean dryRun,
      String csvContent,
      Map<String, String> columnMapping,
      Boolean simulated) {}

  public record RowErrorDto(
      int rowNumber,
      String field,
      String message) {}

  public record CostIngestResultDto(
      boolean dryRun,
      int validRows,
      int errorRows,
      BigDecimal totalBilledCost,
      BigDecimal totalEffectiveCost,
      List<RowErrorDto> errors,
      int appliedCount) {}

  public record AllocationRuleDto(
      Long id,
      String name,
      int priority,
      String tagKey,
      String tagValue,
      String subAccountId,
      String resourcePattern,
      Long targetServiceId,
      Long targetDepartmentId,
      String targetCostCenter,
      double splitPercent,
      boolean enabled,
      boolean isDemo,
      Instant createdAt) {}

  public record CreateAllocationRuleRequest(
      @NotBlank @Size(max = 140) String name,
      Integer priority,
      String tagKey,
      String tagValue,
      String subAccountId,
      String resourcePattern,
      Long targetServiceId,
      Long targetDepartmentId,
      @NotBlank @Size(max = 80) String targetCostCenter,
      Double splitPercent,
      Boolean enabled) {}

  public record BudgetStatusDto(
      Long id,
      String name,
      String targetType,
      String targetKey,
      String periodMonth,
      BigDecimal amount,
      BigDecimal spentToDate,
      double utilizationPercent,
      BigDecimal forecastedEndOfMonth,
      List<Integer> triggeredThresholds,
      String status) {}

  public record CreateBudgetRequest(
      @NotBlank @Size(max = 140) String name,
      String targetType,
      String targetKey,
      String periodMonth,
      @NotNull BigDecimal amount,
      List<Integer> thresholds) {}

  public record CostAnomalyDto(
      Long id,
      String scopeKey,
      LocalDate chargeDate,
      BigDecimal actualCost,
      BigDecimal expectedCost,
      BigDecimal upperBound,
      double deviationScore,
      String status,
      boolean simulated,
      Instant createdAt) {}

  public record CostRecommendationDto(
      Long id,
      String category,
      String resourceId,
      String resourceName,
      String serviceName,
      String title,
      String evidence,
      BigDecimal estimatedMonthlySavings,
      String status,
      Long changeRequestId,
      String dismissReason,
      boolean simulated,
      Instant createdAt) {}

  public record DismissRecommendationRequest(
      String reason) {}
}
