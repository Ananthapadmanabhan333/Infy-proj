package com.nexus360.alert;

import java.time.Instant;
import java.util.Map;

/**
 * One alert notification normalised from any source format. {@code severity} {@code null} = the source's default;
 * {@code serviceCode} / {@code ciName}: hints for attaching the alert to a business service and a CI; {@code firing} false = the
 * source reports the alert as resolved.
 */
public record AlertSignal(String fingerprint, String groupKey, String title, String description, AlertSeverity severity, boolean firing,
                          Map<String, String> labels, Map<String, String> annotations, String externalUrl, String serviceCode, String ciName,
                          Instant startsAt, Instant endsAt) {}
