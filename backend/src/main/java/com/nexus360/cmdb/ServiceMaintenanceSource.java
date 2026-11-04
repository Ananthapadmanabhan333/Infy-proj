package com.nexus360.cmdb;

import java.time.Instant;
import java.util.Map;

/**
 * Planned maintenance that is in progress right now, by business service id (value: the maintenance title). Services in
 * an active window are shown as {@code MAINTENANCE}. Implemented by the status page module (maintenance windows); other
 * modules (e.g. change freeze or implementation windows) may contribute further implementations.
 */
public interface ServiceMaintenanceSource {

    Map<Long, String> activeMaintenance(Instant now);
}
