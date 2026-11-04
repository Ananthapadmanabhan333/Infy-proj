/**
 * ILLUSTRATIVE SAMPLE DATA for the landing-page Command Center preview — not read from any API and not a claim
 * about any deployment. The preview is always labelled "Demo preview · illustrative data" (spec §6).
 * The numbers are internally consistent: 7 days of created incidents add up to the total and the priority split.
 */
export const ILLUSTRATIVE_PREVIEW_DATA = {
  user: { firstName: 'Anantha', displayName: 'Anantha P.', role: 'System Admin' },
  period: 'Last 7 Days',
  kpis: [
    { key: 'total', label: 'Total Incidents', value: '1,248', delta: '12%', direction: 'up', tone: 'good' },
    { key: 'critical', label: 'Critical', value: '27', delta: '36%', direction: 'up', tone: 'bad' },
    { key: 'sla', label: 'SLA Compliance', value: '96.4%', delta: '2.1 pp', direction: 'up', tone: 'good' },
    { key: 'mttr', label: 'MTTR', value: '28 min', delta: '18%', direction: 'down', tone: 'good' },
  ],
  trend: {
    days: ['Oct 28', 'Oct 29', 'Oct 30', 'Oct 31', 'Nov 1', 'Nov 2', 'Nov 3'],
    created: [148, 163, 181, 152, 170, 189, 245],
    resolved: [139, 151, 168, 160, 158, 176, 201],
    /** Projection for the next day, drawn dashed from the last actual point. */
    forecast: 232,
    max: 250,
  },
  distribution: [
    { label: 'Critical', value: 27, color: '#E56D68' },
    { label: 'High', value: 186, color: '#E5AE62' },
    { label: 'Medium', value: 645, color: '#D6B77A' },
    { label: 'Low', value: 390, color: '#F1DFBA' },
  ],
  /**
   * Service health: current state plus the last 30 days as status cells (u = up, d = degraded, o = outage,
   * m = maintenance). No uptime percentages — the landing page makes no availability claims.
   */
  services: [
    { name: 'Email & Collaboration', status: 'operational', days: 'uuuuuuuuuuuuuuuuuuuuuuuuuuuuuu' },
    { name: 'Payroll', status: 'degraded', days: 'uuuuuuuuuuuuuuuuuuuuuuuuuuuddd' },
    { name: 'VPN Gateway', status: 'operational', days: 'uuuuuuuuuuuuuouuuuuuuuuuuuuuuu' },
    { name: 'ERP Finance', status: 'maintenance', days: 'uuuuuuuuuumuuuuuuuuuuuuuuuuuum' },
  ],
  /** Operational activity feed (most recent first). */
  activity: [
    { tone: 'critical', text: 'INC-4821 escalated to Network Operations', ago: '2m' },
    { tone: 'success', text: 'CHG-1043 approved by the CAB', ago: '9m' },
    { tone: 'gold', text: 'Runbook “VPN tunnel reset” completed', ago: '14m' },
    { tone: 'warning', text: 'SLA at risk · INC-4817 Payroll export', ago: '21m' },
  ],
} as const;

export type PreviewKpi = (typeof ILLUSTRATIVE_PREVIEW_DATA.kpis)[number];
export type PreviewService = (typeof ILLUSTRATIVE_PREVIEW_DATA.services)[number];
