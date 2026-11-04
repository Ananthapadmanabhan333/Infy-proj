package com.nexus360.change;

import com.nexus360.approval.Approval;
import com.nexus360.approval.ApprovalHandler;
import com.nexus360.cmdb.ServiceMaintenanceSource;
import com.nexus360.security.Actor;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

/**
 * Change module extension points: the CHANGE approval handler (CAB decisions move the change to APPROVED or REJECTED) and
 * the maintenance source (approved/scheduled changes inside their planned window, and changes being implemented, show
 * their services as MAINTENANCE).
 */
@Configuration
@RequiredArgsConstructor
class ChangeApprovalHandler {

    private final ChangeService changes;
    private final ChangeRepository repository;

    @Bean
    ApprovalHandler changeApprovals() {
        return new ApprovalHandler() {
            @Override
            public Approval.Type type() {
                return Approval.Type.CHANGE;
            }

            @Override
            public void onDecision(Approval approval, boolean approved, Actor approver) {
                changes.onApprovalDecision(approval, approved, approver);
            }
        };
    }

    @Bean
    ServiceMaintenanceSource changeMaintenance() {
        return new ServiceMaintenanceSource() {
            @Override
            @Transactional(readOnly = true)
            public Map<Long, String> activeMaintenance(Instant now) {
                Map<Long, String> result = new HashMap<>();
                for (Change c : repository.findInWindow(java.util.EnumSet.of(ChangeStatus.APPROVED, ChangeStatus.SCHEDULED), now)) {
                    c.getServices().forEach(s -> result.putIfAbsent(s.getId(), "Change " + c.getNumber() + ": " + c.getTitle()));
                }
                return result;
            }
        };
    }
}
