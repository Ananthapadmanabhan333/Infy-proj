import type { SlaOverview, SlaOverviewKpis } from '../../lib/types';

/** §5 `kpis`, falling back to the Release 1 counters of the same response if an older API omits them. */
export function slaKpis(overview: SlaOverview): SlaOverviewKpis {
  return (
    overview.kpis ?? {
      compliance: overview.compliancePercent,
      breachesInPeriod: overview.missedCount + overview.breachedActiveCount,
      atRiskNow: overview.atRiskCount,
      avgResolutionMinutes: null,
    }
  );
}
