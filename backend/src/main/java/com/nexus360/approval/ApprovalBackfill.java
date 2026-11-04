package com.nexus360.approval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives approvals that were written without stages (Release 2 code paths such as the demo seeder, or rows inserted
 * directly) the one-stage, one-role-task shape of the approval engine — the same statements as migration V7. Runs at
 * start-up after the demo seeders and is a no-op when every approval has its stage.
 */
@Slf4j
@Component
@Order(25)
@RequiredArgsConstructor
public class ApprovalBackfill implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int n = backfill();
        if (n > 0) {
            log.info("Approval engine: {} single-step approval(s) given their stage and task", n);
        }
    }

    public int backfill() {
        int stages = jdbc.update("""
                INSERT INTO approval_stages (approval_id, stage_order, name, mode, quorum, required_count, status, started_at, completed_at)
                SELECT a.id, 1, 'Approval', 'PARALLEL', 'ANY', 1,
                       CASE a.status WHEN 'PENDING' THEN 'ACTIVE' WHEN 'APPROVED' THEN 'APPROVED' WHEN 'REJECTED' THEN 'REJECTED' ELSE 'CANCELLED' END,
                       a.created_at, a.decided_at
                  FROM approvals a
                 WHERE NOT EXISTS (SELECT 1 FROM approval_stages s WHERE s.approval_id = a.id)""");
        if (stages == 0) {
            return 0;
        }
        jdbc.update("""
                INSERT INTO approval_tasks (approval_id, stage_id, seq, approver_kind, approver_role, status, decided_by_id, decided_at, comment, created_at)
                SELECT a.id, s.id, 1, 'ROLE', coalesce(a.approver_role, 'IT_MANAGER'), a.status, a.decided_by_id,
                       CASE WHEN a.status IN ('APPROVED', 'REJECTED') THEN coalesce(a.decided_at, a.created_at) END, a.decision_comment, a.created_at
                  FROM approvals a JOIN approval_stages s ON s.approval_id = a.id AND s.stage_order = 1
                 WHERE NOT EXISTS (SELECT 1 FROM approval_tasks t WHERE t.approval_id = a.id)""");
        jdbc.update("UPDATE approvals SET current_stage = 1 WHERE current_stage IS NULL");
        jdbc.update("""
                INSERT INTO approval_events (approval_id, stage_order, event_type, actor_id, actor_name, created_at)
                SELECT a.id, NULL, 'REQUESTED', a.requested_by_id, coalesce(u.full_name, 'SA Automation'), a.created_at
                  FROM approvals a LEFT JOIN users u ON u.id = a.requested_by_id
                 WHERE NOT EXISTS (SELECT 1 FROM approval_events e WHERE e.approval_id = a.id)""");
        return stages;
    }
}
