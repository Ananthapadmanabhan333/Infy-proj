-- Reference configuration required by every installation (not demo data).

INSERT INTO business_hours_calendars (name, timezone, work_days, start_time, end_time, is_default)
VALUES ('Standard Business Hours (IST)', 'Asia/Kolkata', 'MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY', '09:00', '18:00', TRUE);

INSERT INTO sla_policies (name, priority, response_minutes, resolution_minutes, business_hours_only, calendar_id, at_risk_percent, description)
VALUES
    ('P1 Critical — 24x7', 'CRITICAL', 15, 240, FALSE, NULL, 75,
     'Business-critical outage. 15 minute response and 4 hour resolution around the clock.'),
    ('P2 High — 24x7', 'HIGH', 30, 480, FALSE, NULL, 75,
     'Major degradation. 30 minute response and 8 hour resolution around the clock.'),
    ('P3 Medium — business hours', 'MEDIUM', 120, 1080, TRUE,
     (SELECT id FROM business_hours_calendars WHERE is_default), 80,
     'Single team affected. 2 business hour response, 2 business day resolution.'),
    ('P4 Low — business hours', 'LOW', 480, 2700, TRUE,
     (SELECT id FROM business_hours_calendars WHERE is_default), 85,
     'Minor issue or question. 1 business day response, 5 business day resolution.');

INSERT INTO system_settings (setting_key, setting_value, value_type, description) VALUES
    ('incident.duplicate.threshold', '0.48', 'NUMBER',
     'Similarity score (0-1) at or above which a new incident is linked to an existing one as a duplicate.'),
    ('incident.duplicate.suggestion_threshold', '0.30', 'NUMBER',
     'Similarity score (0-1) at or above which candidates are suggested to agents without automatic linking.'),
    ('incident.duplicate.window_hours', '72', 'NUMBER',
     'Only unresolved incidents created within this many hours are considered for duplicate detection.'),
    ('incident.duplicate.auto_link', 'true', 'BOOLEAN',
     'Automatically link detected duplicates under a parent incident.'),
    ('incident.major.report_threshold', '3', 'NUMBER',
     'Number of linked reports at which a parent incident is declared a major incident.'),
    ('incident.assignment.auto', 'true', 'BOOLEAN',
     'Automatically assign new incidents to the owning department and its least-loaded agent.'),
    ('ai.llm.enabled', 'true', 'BOOLEAN',
     'Use the configured LLM provider when available. When false, the deterministic fallback engine is always used.'),
    ('sla.escalation.severe_overrun_percent', '50', 'NUMBER',
     'Resolution overrun (percent of target) at which a breached SLA is escalated to level 3 (administrators).');
