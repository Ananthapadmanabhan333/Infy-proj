import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Activity,
  AlertTriangle,
  Cpu,
  Gauge,
  KeyRound,
  Laptop,
  Plus,
  Upload,
} from 'lucide-react';
import {
  useCreateDexProactiveRule,
  useDexOverview,
  useIngestDexEndpoints,
} from '../../api/dex';
import { SimulatedBadge } from '../../components/Badge';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { KpiCard } from '../../components/KpiCard';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { IncludeSimulatedToggle } from '../../components/sim/SimulationParts';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { useTabParam } from '../../hooks/useUrlState';
import { errorMessage } from '../../lib/api';
import { ADMINS } from '../../lib/constants';
import { formatDateTime, formatNumber, formatPercent } from '../../lib/format';
import type {
  DexAppScoreDto,
  DexDepartmentScoreDto,
  DexEndpointDto,
  DexModelScoreDto,
  DexProactiveRuleDto,
  DexServiceExperienceDto,
} from '../../lib/r4Types';

export type DexTab = 'score' | 'endpoints' | 'apps' | 'services' | 'rules';
const TABS: DexTab[] = ['score', 'endpoints', 'apps', 'services', 'rules'];
const TAB_LABELS: Record<DexTab, string> = {
  score: 'Experience Score & Breakdown',
  endpoints: 'Endpoints & Device Models',
  apps: 'Application & Login Telemetry',
  services: 'Service Experience Analytics',
  rules: 'Proactive Rules & Ingestion',
};

function scoreTone(score: number): 'success' | 'gold' | 'warning' | 'critical' {
  if (score >= 80) return 'success';
  if (score >= 60) return 'gold';
  if (score >= 45) return 'warning';
  return 'critical';
}

export default function DexPage() {
  const { hasRole } = useAuth();
  const canAdmin = hasRole(...ADMINS);
  const toast = useToast();

  const [tab, setTab] = useTabParam<DexTab>(TABS, 'score');
  const [params, setParams] = useSearchParams();
  const includeSimulated = params.get('sim') === '1';

  const setIncludeSimulated = (next: boolean) =>
    setParams(
      (current) => {
        const updated = new URLSearchParams(current);
        if (next) updated.set('sim', '1');
        else updated.delete('sim');
        return updated;
      },
      { replace: true },
    );

  const overviewQuery = useDexOverview(includeSimulated);
  const ingestEndpoints = useIngestDexEndpoints();
  const createRule = useCreateDexProactiveRule();

  // Proactive rule state
  const [ruleName, setRuleName] = useState('');
  const [ruleDept, setRuleDept] = useState('Engineering');
  const [ruleThreshold, setRuleThreshold] = useState(65);

  // Endpoint ingestion state
  const [endpointPayload, setEndpointPayload] = useState(
    '[{"deviceId":"DEV-ENG-104","userEmail":"ananya@nexus360.test","userName":"Ananya Sharma","departmentName":"Engineering","osName":"Windows 11","osVersion":"23H2","deviceModel":"ThinkPad T14 Gen 4","cpuUsagePct":38,"memoryUsagePct":62,"diskUsagePct":54,"bootTimeSeconds":24,"crashCount7d":0,"batteryHealthPct":94}]',
  );

  const overview = overviewQuery.data;

  function handleIngestEndpoints() {
    try {
      const parsed = JSON.parse(endpointPayload);
      const list = Array.isArray(parsed) ? parsed : [parsed];
      ingestEndpoints.mutate(
        { endpoints: list },
        {
          onSuccess: (res) => toast.success(`Ingested ${res.length} endpoint health records`),
          onError: (err) => toast.error(errorMessage(err)),
        },
      );
    } catch {
      toast.error('Endpoint telemetry payload must be valid JSON');
    }
  }

  return (
    <>
      <PageHeader
        title="Employee Experience"
        subtitle="Digital Employee Experience (DEX) composite 0–100 score across endpoint device health, application performance, SSO/login success and IT service ticket signals."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <IncludeSimulatedToggle value={includeSimulated} onChange={setIncludeSimulated} />
            {overview && <SimulatedBadge simulated={overview.simulated} />}
          </div>
        }
      />

      <div className="page-stack">
        <Tabs<DexTab>
          label="Digital Employee Experience views"
          value={tab}
          onChange={setTab}
          tabs={TABS.map((id) => ({ id, label: TAB_LABELS[id] }))}
        />

        {overviewQuery.isLoading && <LoadingState label="Loading Digital Employee Experience analytics…" />}
        {overviewQuery.isError && <ErrorState error={overviewQuery.error} onRetry={() => void overviewQuery.refetch()} />}

        {overview && overview.status === 'INSUFFICIENT_DATA' && tab !== 'rules' && (
          <div className="callout" role="status">
            <AlertTriangle size={16} />
            <div>
              <b>Insufficient data — 0 endpoint telemetry samples</b>
              <div className="text-xs text-muted">
                SA never invents live endpoint health or DEX scores. Ingest endpoint telemetry under Proactive Rules &
                Ingestion or enable Include Simulated Data to inspect simulated endpoint degradation.
              </div>
            </div>
          </div>
        )}

        {overview && tab === 'score' && (
          <div className="space-y-4">
            {overview.status === 'OK' && (
              <>
                <div className="kpi-grid">
                  <KpiCard
                    icon={<Gauge size={18} />}
                    label="Overall Experience Score"
                    value={`${overview.overallExperienceScore.toFixed(1)} / 100`}
                    tone={scoreTone(overview.overallExperienceScore)}
                    caption={`${formatNumber(overview.endpointCount)} reporting endpoints`}
                  />
                  <KpiCard
                    icon={<Laptop size={18} />}
                    label="Device Health Average"
                    value={`${overview.deviceHealthAvg.toFixed(1)} / 100`}
                    tone={scoreTone(overview.deviceHealthAvg)}
                  />
                  <KpiCard
                    icon={<Activity size={18} />}
                    label="App Performance Average"
                    value={`${overview.appPerformanceAvg.toFixed(1)} / 100`}
                    tone={scoreTone(overview.appPerformanceAvg)}
                  />
                  <KpiCard
                    icon={<KeyRound size={18} />}
                    label="Login & SSO Success Rate"
                    value={formatPercent(overview.loginSuccessRatePct, 1)}
                    tone={overview.loginSuccessRatePct >= 95 ? 'success' : 'warning'}
                  />
                </div>

                <Panel
                  title="Documented Experience Score Formula (0–100)"
                  subtitle="Weighted composite of endpoint health, application RUM telemetry, sign-in reliability and ticket signals"
                >
                  <div className="callout">
                    <Cpu size={16} />
                    <div>
                      <b>Formula:</b> <code>{overview.formula}</code>
                    </div>
                  </div>
                </Panel>

                <Panel
                  title="Experience Score by Department"
                  subtitle="Component breakdown across Device Health, App Performance, Login Success and Ticket Signals"
                >
                  <div className="table-wrap">
                    <table className="data-table">
                      <thead>
                        <tr>
                          <th>Department</th>
                          <th>Endpoints</th>
                          <th>Composite DEX Score</th>
                          <th>Device Health</th>
                          <th>App Performance</th>
                          <th>Login Success</th>
                          <th>Ticket Signals</th>
                        </tr>
                      </thead>
                      <tbody>
                        {overview.byDepartment.map((dept: DexDepartmentScoreDto) => {
                          const score = dept.score ?? dept.avgExperienceScore ?? 0;
                          const devHealth = dept.deviceHealth ?? dept.avgDeviceHealthScore ?? 0;
                          const appPerf = dept.appPerformance ?? overview.appPerformanceAvg ?? 0;
                          const loginSucc = dept.loginSuccess ?? overview.loginSuccessRatePct ?? 0;
                          const ticketSig = dept.ticketSignals ?? 100;
                          return (
                            <tr key={dept.key}>
                              <td><b>{dept.name ?? dept.label}</b></td>
                              <td>{formatNumber(dept.count ?? dept.endpointCount ?? 0)}</td>
                              <td>
                                <ToneBadge
                                  value={String(score)}
                                  label={`${score.toFixed(1)} / 100`}
                                  tone={scoreTone(score)}
                                />
                              </td>
                              <td>{devHealth.toFixed(1)}</td>
                              <td>{appPerf.toFixed(1)}</td>
                              <td>{loginSucc.toFixed(1)}</td>
                              <td>{ticketSig.toFixed(1)}</td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                </Panel>
              </>
            )}
          </div>
        )}

        {overview && tab === 'endpoints' && (
          <div className="space-y-4">
            <Panel title="Experience Score by Device Model" subtitle="Hardware fleet comparison">
              {overview.byDeviceModel.length === 0 ? (
                <EmptyState title="No device model telemetry yet" />
              ) : (
                <div className="grid gap-3 sm:grid-cols-3">
                  {overview.byDeviceModel.map((m: DexModelScoreDto) => {
                    const mScore = m.score ?? m.avgExperienceScore ?? 0;
                    return (
                      <div key={m.key} className="rounded border border-slate-200 p-3 dark:border-slate-800 flex items-center justify-between">
                        <div>
                          <b className="text-xs">{m.name ?? m.label}</b>
                          <div className="text-[11px] text-muted">{m.count ?? m.endpointCount ?? 0} endpoints</div>
                        </div>
                        <ToneBadge value={String(mScore)} label={`${mScore.toFixed(1)}`} tone={scoreTone(mScore)} />
                      </div>
                    );
                  })}
                </div>
              )}
            </Panel>

            <Panel
              title={`Managed Endpoints (${overview.endpoints.length})`}
              subtitle="Agent-agnostic endpoint telemetry (Intune / Jamf / JSON compatible)"
            >
              {overview.endpoints.length === 0 ? (
                <EmptyState title="No endpoints reporting telemetry" />
              ) : (
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Device / Model</th>
                        <th>User / Department</th>
                        <th>OS</th>
                        <th>CPU / Mem / Disk</th>
                        <th>Boot / 7d Crashes</th>
                        <th>Device Health</th>
                        <th>Experience Score</th>
                        <th>Last Seen</th>
                      </tr>
                    </thead>
                    <tbody>
                      {overview.endpoints.map((ep: DexEndpointDto) => (
                        <tr key={ep.id}>
                          <td>
                            <b>{ep.deviceId}</b> <SimulatedBadge simulated={ep.simulated} />
                            <div className="text-[11px] text-muted">{ep.deviceModel}</div>
                          </td>
                          <td>
                            <b>{ep.userName}</b>
                            <div className="text-[11px] text-muted">{ep.userEmail} · {ep.departmentName}</div>
                          </td>
                          <td>{ep.osName} {ep.osVersion}</td>
                          <td>
                            {ep.cpuUsagePct.toFixed(0)}% / {ep.memoryUsagePct.toFixed(0)}% / {ep.diskUsagePct.toFixed(0)}%
                          </td>
                          <td>
                            {ep.bootTimeSeconds}s · {ep.crashCount7d} crashes
                          </td>
                          <td>{ep.deviceHealthScore.toFixed(1)}</td>
                          <td>
                            <ToneBadge
                              value={String(ep.experienceScore)}
                              label={`${ep.experienceScore.toFixed(1)}`}
                              tone={scoreTone(ep.experienceScore)}
                            />
                          </td>
                          <td>{formatDateTime(ep.lastSeenAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Panel>
          </div>
        )}

        {overview && tab === 'apps' && (
          <Panel
            title="Application Performance & RUM Telemetry"
            subtitle="App launch latency, page load times and crash rates per application version"
          >
            {overview.byApp.length === 0 ? (
              <EmptyState title="No application performance events ingested" />
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Application</th>
                      <th>Version</th>
                      <th>Avg Launch (ms)</th>
                      <th>Avg Page Load (ms)</th>
                      <th>Crash Rate</th>
                      <th>Events</th>
                    </tr>
                  </thead>
                  <tbody>
                    {overview.byApp.map((app: DexAppScoreDto) => (
                      <tr key={`${app.appName}-${app.appVersion}`}>
                        <td><b>{app.appName}</b></td>
                        <td><code>{app.appVersion}</code></td>
                        <td>{app.avgLaunchMs.toFixed(0)} ms</td>
                        <td>{app.avgPageLoadMs.toFixed(0)} ms</td>
                        <td>
                          <ToneBadge
                            value={String(app.crashRatePct)}
                            label={formatPercent(app.crashRatePct, 2)}
                            tone={app.crashRatePct > 2 ? 'critical' : 'success'}
                          />
                        </td>
                        <td>{formatNumber(app.eventCount)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        )}

        {overview && tab === 'services' && (
          <Panel
            title="Service Experience Analytics"
            subtitle="Per-service incident density per 100 users, resolution speed, reopen rate, first-contact resolution and CSAT"
          >
            {overview.serviceExperience.length === 0 ? (
              <EmptyState title="No service experience metrics available" />
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Service</th>
                      <th>Incidents / 100 Users</th>
                      <th>Avg Time to Resolve</th>
                      <th>Reopen Rate</th>
                      <th>First-Contact Resolution</th>
                      <th>Avg CSAT</th>
                    </tr>
                  </thead>
                  <tbody>
                    {overview.serviceExperience.map((svc: DexServiceExperienceDto) => {
                      const resolveHours = svc.avgResolveHours ?? svc.avgResolutionHours ?? 0;
                      const fcrRate = svc.fcrRatePct ?? svc.firstContactResolutionPct ?? 0;
                      const csat = svc.csatAvg ?? 0;
                      return (
                        <tr key={svc.serviceId}>
                          <td><b>{svc.serviceName}</b></td>
                          <td>{svc.incidentsPer100Users.toFixed(1)}</td>
                          <td>{resolveHours.toFixed(1)} h</td>
                          <td>{formatPercent(svc.reopenRatePct, 1)}</td>
                          <td>{formatPercent(fcrRate, 1)}</td>
                          <td>{csat > 0 ? `${csat.toFixed(2)} / 5` : '—'}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        )}

        {tab === 'rules' && (
          <div className="space-y-4">
            {canAdmin && (
              <div className="grid gap-4 lg:grid-cols-2">
                <Panel
                  title="Create Proactive Department Incident Rule"
                  subtitle="Opt-in rule that raises a proactive IT incident when a department's DEX score drops below threshold"
                >
                  <div className="grid gap-2 sm:grid-cols-4">
                    <input
                      aria-label="Proactive rule name"
                      className="input-control sm:col-span-2"
                      value={ruleName}
                      onChange={(e) => setRuleName(e.target.value)}
                      placeholder="Engineering DEX degradation alert"
                    />
                    <input
                      aria-label="Department name"
                      className="input-control"
                      value={ruleDept}
                      onChange={(e) => setRuleDept(e.target.value)}
                      placeholder="Engineering"
                    />
                    <div className="flex gap-2">
                      <input
                        aria-label="Threshold score"
                        type="number"
                        className="input-control flex-1"
                        value={ruleThreshold}
                        onChange={(e) => setRuleThreshold(Number(e.target.value))}
                      />
                      <button
                        type="button"
                        className="gold-btn"
                        onClick={() => {
                          if (!ruleName.trim()) return;
                          createRule.mutate(
                            {
                              name: ruleName.trim(),
                              departmentName: ruleDept.trim(),
                              thresholdScore: ruleThreshold,
                              enabled: true,
                            },
                            {
                              onSuccess: () => {
                                setRuleName('');
                                toast.success('Created proactive DEX rule');
                              },
                            },
                          );
                        }}
                      >
                        <Plus size={14} /> Add
                      </button>
                    </div>
                  </div>
                </Panel>

                <Panel
                  title="Ingest Endpoint Health Telemetry (Agent-Agnostic JSON)"
                  subtitle="Accepts normalised endpoint records or Intune / Jamf export mappings"
                >
                  <div className="space-y-2">
                    <textarea
                      aria-label="Endpoint JSON payload"
                      rows={3}
                      className="input-control font-mono text-xs w-full"
                      value={endpointPayload}
                      onChange={(e) => setEndpointPayload(e.target.value)}
                    />
                    <button
                      type="button"
                      className="gold-btn compact"
                      disabled={ingestEndpoints.isPending}
                      onClick={handleIngestEndpoints}
                    >
                      <Upload size={13} /> Ingest Endpoint Telemetry
                    </button>
                  </div>
                </Panel>
              </div>
            )}

            <Panel title="Configured Proactive DEX Incident Rules">
              {!overview || overview.proactiveRules.length === 0 ? (
                <EmptyState title="No proactive DEX rules configured" />
              ) : (
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Rule Name</th>
                        <th>Department</th>
                        <th>Threshold Score</th>
                        <th>Status</th>
                        <th>Last Triggered Incident</th>
                      </tr>
                    </thead>
                    <tbody>
                      {overview.proactiveRules.map((r: DexProactiveRuleDto) => (
                        <tr key={r.id}>
                          <td><b>{r.name}</b></td>
                          <td>{r.departmentName}</td>
                          <td>&lt; {r.thresholdScore} / 100</td>
                          <td>
                            <ToneBadge
                              value={r.enabled ? 'ENABLED' : 'DISABLED'}
                              label={r.enabled ? 'ENABLED' : 'DISABLED'}
                              tone={r.enabled ? 'success' : 'muted'}
                            />
                          </td>
                          <td>{r.lastTriggeredIncidentId ? `Incident #${r.lastTriggeredIncidentId}` : 'None'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Panel>
          </div>
        )}
      </div>
    </>
  );
}
