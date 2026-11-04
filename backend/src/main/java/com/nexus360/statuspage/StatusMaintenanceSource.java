package com.nexus360.statuspage;

import com.nexus360.cmdb.ServiceMaintenanceSource;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Maintenance windows in progress put their components' services into MAINTENANCE (services list, status page, Command Center). */
@Component
@RequiredArgsConstructor
class StatusMaintenanceSource implements ServiceMaintenanceSource {

    private final MaintenanceWindowRepository windows;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> activeMaintenance(Instant now) {
        Map<Long, String> result = new HashMap<>();
        for (MaintenanceWindow w : windows.findActive(now)) {
            w.getComponents().forEach(c -> result.putIfAbsent(c.getService().getId(), w.getTitle()));
        }
        return result;
    }
}
