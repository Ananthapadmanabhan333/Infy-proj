package com.nexus360.dex;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DexDtos {

  private DexDtos() {}

  public record DexBucketDto(
      String key,
      String label,
      long endpointCount,
      double avgExperienceScore,
      double avgDeviceHealthScore) {}

  public record DexAppSummaryDto(
      String appName,
      String appVersion,
      long eventCount,
      double avgLaunchMs,
      double avgPageLoadMs,
      double crashRatePct,
      double hangRatePct) {}

  public record ServiceExperienceDto(
      Long serviceId,
      String serviceCode,
      String serviceName,
      double incidentsPer100Users,
      double avgResolutionHours,
      double reopenRatePct,
      double firstContactResolutionPct) {}

  public record DexEndpointDto(
      Long id,
      String deviceId,
      Long userId,
      String userEmail,
      String userName,
      Long departmentId,
      String departmentName,
      String osName,
      String osVersion,
      String deviceModel,
      double cpuUsagePct,
      double memoryUsagePct,
      double diskUsagePct,
      double bootTimeSeconds,
      int crashCount7d,
      Double batteryHealthPct,
      double deviceHealthScore,
      double experienceScore,
      Map<String, Double> scoreBreakdown,
      Instant lastSeenAt,
      boolean simulated,
      Instant updatedAt) {}

  public record DexProactiveRuleDto(
      Long id,
      String name,
      String departmentName,
      double thresholdScore,
      boolean enabled,
      Long lastTriggeredIncidentId,
      boolean isDemo,
      Instant createdAt) {}

  public record DexOverviewDto(
      String status,
      double overallExperienceScore,
      double deviceHealthAvg,
      double appPerformanceAvg,
      double loginSuccessRatePct,
      long endpointCount,
      String formula,
      List<DexBucketDto> byDepartment,
      List<DexBucketDto> byDeviceModel,
      List<DexAppSummaryDto> byApp,
      List<ServiceExperienceDto> serviceExperience,
      List<DexEndpointDto> endpoints,
      List<DexProactiveRuleDto> proactiveRules,
      boolean simulated) {}

  public record IngestEndpointRequest(
      String deviceId,
      Long userId,
      String userEmail,
      String userName,
      Long departmentId,
      String departmentName,
      String osName,
      String osVersion,
      String deviceModel,
      Double cpuUsagePct,
      Double memoryUsagePct,
      Double diskUsagePct,
      Double bootTimeSeconds,
      Integer crashCount7d,
      Double batteryHealthPct,
      Boolean simulated) {}

  public record IngestAppEventRequest(
      String appName,
      String appVersion,
      String deviceId,
      String userEmail,
      String departmentName,
      Double launchTimeMs,
      Double pageLoadMs,
      Boolean crashed,
      Boolean hang,
      String errorCode,
      Boolean simulated) {}

  public record IngestLoginEventRequest(
      String userEmail,
      String departmentName,
      String provider,
      String outcome,
      Boolean simulated) {}

  public record UpsertProactiveRuleRequest(
      @NotBlank @Size(max = 140) String name,
      String departmentName,
      Double thresholdScore,
      Boolean enabled) {}

  public record MyDeviceSummaryDto(
      String status,
      DexEndpointDto device,
      String message) {}
}
