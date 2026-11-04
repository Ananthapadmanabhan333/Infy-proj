import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  AlertTriangle,
  CheckCircle2,
  FileWarning,
  KeyRound,
  Layers,
  Plus,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  Upload,
} from 'lucide-react';
import {
  useAcceptVulnerabilityRisk,
  useAddSecurityIncidentNote,
  useCompleteSecurityTask,
  useCreateSecurityIncident,
  useCreateVulnerabilitySource,
  useIngestVulnerabilities,
  useRaiseIncidentFromSecurityGroup,
  useRunSecurityCorrelation,
  useSecurityCorrelationGroups,
  useSecurityIncident,
  useSecurityIncidents,
  useSecurityOverview,
  useSecurityResponseTemplates,
  useTransitionSecurityIncident,
  useVulnerabilityFindings,
  useVulnerabilitySources,
} from '../../api/security';
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
import { SECOPS, type Tone } from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative } from '../../lib/format';
import type {
  SecurityCorrelationGroupDto,
  SecurityIncidentDto,
  SecurityIncidentStatus,
  SecurityIncidentTaskDto,
  SecurityResponseTemplateDto,
  VulnerabilityFindingDto,
  VulnerabilitySourceDto,
} from '../../lib/r4Types';

export type SecurityTab = 'overview' | 'incidents' | 'findings' | 'correlation' | 'templates';
const TABS: SecurityTab[] = ['overview', 'incidents', 'findings', 'correlation', 'templates'];
const TAB_LABELS: Record<SecurityTab, string> = {
  overview: 'Overview & Risk Formula',
  incidents: 'Security Incidents & Response Tasks',
  findings: 'Vulnerability Findings & Ingestion',
  correlation: 'Security Correlation Groups',
  templates: 'Response Templates',
};

const INCIDENT_STATUSES: SecurityIncidentStatus[] = [
  'NEW',
  'TRIAGE',
  'CONTAINMENT',
  'ERADICATION',
  'RECOVERY',
  'CLOSED',
];

const INGEST_FORMATS = ['GENERIC_JSON', 'SARIF', 'TRIVY_JSON', 'CSV'] as const;

function severityTone(sev: string | null | undefined): Tone {
  switch (sev) {
    case 'CRITICAL':
      return 'critical';
    case 'HIGH':
      return 'warning';
    case 'MEDIUM':
      return 'champagne';
    case 'LOW':
      return 'success';
    default:
      return 'neutral';
  }
}

export default function SecurityPage() {
  const { hasRole } = useAuth();
  const canSecOps = hasRole(...SECOPS);
  const toast = useToast();

  const [tab, setTab] = useTabParam<SecurityTab>(TABS, 'overview');
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

  // Queries
  const overviewQuery = useSecurityOverview(includeSimulated);
  const incidentsQuery = useSecurityIncidents(includeSimulated);
  const [selectedIncidentId, setSelectedIncidentId] = useState<number | null>(null);
  const effectiveIncidentId = selectedIncidentId ?? incidentsQuery.data?.[0]?.id ?? null;
  const incidentDetailQuery = useSecurityIncident(effectiveIncidentId);
  const sourcesQuery = useVulnerabilitySources();
  const findingsQuery = useVulnerabilityFindings(includeSimulated);
  const corrGroupsQuery = useSecurityCorrelationGroups(includeSimulated);
  const templatesQuery = useSecurityResponseTemplates();

  // Mutations
  const createIncident = useCreateSecurityIncident();
  const transitionIncident = useTransitionSecurityIncident();
  const addNote = useAddSecurityIncidentNote();
  const completeTask = useCompleteSecurityTask();
  const createSource = useCreateVulnerabilitySource();
  const ingestVulns = useIngestVulnerabilities();
  const acceptRisk = useAcceptVulnerabilityRisk();
  const runCorrelation = useRunSecurityCorrelation();
  const raiseFromGroup = useRaiseIncidentFromSecurityGroup();

  // Create incident state
  const [showNewIncident, setShowNewIncident] = useState(false);
  const [incTitle, setIncTitle] = useState('');
  const [incCategory, setIncCategory] = useState('PHISHING');
  const [incSeverity, setIncSeverity] = useState('HIGH');
  const [incUserEmail] = useState('');
  const [incTemplateCode, setIncTemplateCode] = useState('');
  const [lessonsInput, setLessonsInput] = useState('');
  const [noteInput, setNoteInput] = useState('');
  const [iocType] = useState('IP');
  const [iocValue, setIocValue] = useState('');

  // Vulnerability source & ingestion state
  const [srcName, setSrcName] = useState('');
  const [srcFormat, setSrcFormat] = useState<'GENERIC_JSON' | 'SARIF' | 'TRIVY_JSON' | 'CSV'>('TRIVY_JSON');
  const [srcScanner] = useState('Trivy');
  const [createdToken, setCreatedToken] = useState<string | null>(null);

  const [ingestFormat, setIngestFormat] = useState<'GENERIC_JSON' | 'SARIF' | 'TRIVY_JSON' | 'CSV'>('GENERIC_JSON');
  const [ingestPayload, setIngestPayload] = useState(
    '[{"cveId":"CVE-2026-4101","title":"OpenSSL buffer over-read","assetName":"payroll-app-01","packageName":"openssl","installedVersion":"3.0.1","fixedVersion":"3.0.15","cvssBase":9.1,"exploitKnown":true}]',
  );
  const [ingestFullScan, setIngestFullScan] = useState(false);

  const overview = overviewQuery.data;
  const incidents = incidentsQuery.data ?? [];
  const incidentDetail = incidentDetailQuery.data;
  const findings = findingsQuery.data ?? [];
  const sources = sourcesQuery.data ?? [];
  const corrGroups = corrGroupsQuery.data ?? [];
  const templates = templatesQuery.data ?? [];

  function handleCreateIncident() {
    if (!incTitle.trim()) {
      toast.error('Security incident title is required');
      return;
    }
    createIncident.mutate(
      {
        title: incTitle.trim(),
        category: incCategory,
        severity: incSeverity,
        affectedUserEmail: incUserEmail.trim() || null,
        templateCode: incTemplateCode || null,
      },
      {
        onSuccess: (created) => {
          setSelectedIncidentId(created.incident.id);
          setShowNewIncident(false);
          setIncTitle('');
          setTab('incidents');
          toast.success(`Raised security incident ${created.incident.number}`);
        },
        onError: (err) => toast.error(errorMessage(err)),
      },
    );
  }

  function handleCreateSource() {
    if (!srcName.trim()) {
      toast.error('Scanner source name is required');
      return;
    }
    createSource.mutate(
      { name: srcName.trim(), format: srcFormat, scannerName: srcScanner.trim() || 'Scanner' },
      {
        onSuccess: (res) => {
          setCreatedToken(res.rawToken);
          setSrcName('');
          toast.success(`Created source ${res.source.name} — copy token now (shown once)`);
        },
        onError: (err) => toast.error(errorMessage(err)),
      },
    );
  }

  function handleIngest() {
    if (!ingestPayload.trim()) {
      toast.error('Provide scanner payload');
      return;
    }
    ingestVulns.mutate(
      {
        sourceId: sources[0]?.id ?? null,
        format: ingestFormat,
        fullScan: ingestFullScan,
        payload: ingestPayload,
      },
      {
        onSuccess: (res) => {
          toast.success(
            `Ingested ${res.ingested} new, updated ${res.updated}, auto-closed ${res.autoClosed} findings`,
          );
        },
        onError: (err) => toast.error(errorMessage(err)),
      },
    );
  }

  return (
    <>
      <PageHeader
        title="Security Operations"
        subtitle="Security incidents, multi-format vulnerability ingestion (GENERIC_JSON, SARIF, TRIVY_JSON, CSV), risk prioritisation and security alert correlation."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <IncludeSimulatedToggle value={includeSimulated} onChange={setIncludeSimulated} />
            {canSecOps && (
              <button type="button" className="gold-btn" onClick={() => setShowNewIncident((v) => !v)}>
                <Plus size={14} /> Declare Security Incident
              </button>
            )}
          </div>
        }
      />

      <div className="page-stack">
        {showNewIncident && canSecOps && (
          <Panel title="Declare Security Incident" subtitle="Optionally attach a response template to populate phase checklists">
            <div className="grid gap-3 md:grid-cols-5">
              <div className="md:col-span-2">
                <label htmlFor="sec-inc-title" className="field-label">Incident Title</label>
                <input
                  id="sec-inc-title"
                  className="input-control"
                  value={incTitle}
                  onChange={(e) => setIncTitle(e.target.value)}
                  placeholder="Suspicious credential stuffing & token replay on Payroll API"
                />
              </div>
              <div>
                <label htmlFor="sec-inc-cat" className="field-label">Category</label>
                <select
                  id="sec-inc-cat"
                  className="input-control"
                  value={incCategory}
                  onChange={(e) => setIncCategory(e.target.value)}
                >
                  <option value="PHISHING">PHISHING</option>
                  <option value="MALWARE">MALWARE</option>
                  <option value="UNAUTHORISED_ACCESS">UNAUTHORISED_ACCESS</option>
                  <option value="DATA_EXPOSURE">DATA_EXPOSURE</option>
                  <option value="VULNERABILITY_EXPLOITATION">VULNERABILITY_EXPLOITATION</option>
                  <option value="DOS">DOS</option>
                  <option value="POLICY_VIOLATION">POLICY_VIOLATION</option>
                </select>
              </div>
              <div>
                <label htmlFor="sec-inc-sev" className="field-label">Severity</label>
                <select
                  id="sec-inc-sev"
                  className="input-control"
                  value={incSeverity}
                  onChange={(e) => setIncSeverity(e.target.value)}
                >
                  <option value="CRITICAL">CRITICAL</option>
                  <option value="HIGH">HIGH</option>
                  <option value="MEDIUM">MEDIUM</option>
                  <option value="LOW">LOW</option>
                </select>
              </div>
              <div>
                <label htmlFor="sec-inc-tpl" className="field-label">Response Template</label>
                <div className="flex gap-2">
                  <select
                    id="sec-inc-tpl"
                    className="input-control flex-1"
                    value={incTemplateCode}
                    onChange={(e) => setIncTemplateCode(e.target.value)}
                  >
                    <option value="">None</option>
                    {templates.map((t) => (
                      <option key={t.code} value={t.code}>
                        {t.name}
                      </option>
                    ))}
                  </select>
                  <button type="button" className="gold-btn" onClick={handleCreateIncident}>
                    Create
                  </button>
                </div>
              </div>
            </div>
          </Panel>
        )}

        <Tabs<SecurityTab>
          label="Security Operations views"
          value={tab}
          onChange={setTab}
          tabs={TABS.map((id) => ({ id, label: TAB_LABELS[id] }))}
        />

        {tab === 'overview' && (
          <div className="space-y-4">
            {overviewQuery.isLoading && <LoadingState label="Loading security posture…" />}
            {overviewQuery.isError && <ErrorState error={overviewQuery.error} onRetry={() => void overviewQuery.refetch()} />}
            {overview && (
              <>
                <div className="kpi-grid">
                  <KpiCard
                    icon={<ShieldAlert size={18} />}
                    label="Open Security Incidents"
                    value={formatNumber(overview.openIncidents)}
                    tone={overview.openIncidents > 0 ? 'critical' : 'success'}
                  />
                  <KpiCard
                    icon={<FileWarning size={18} />}
                    label="Critical Findings"
                    value={formatNumber(overview.criticalFindings)}
                    tone={overview.criticalFindings > 0 ? 'critical' : 'neutral'}
                    caption={`${formatNumber(overview.highFindings)} High · ${formatNumber(overview.openFindings)} Open total`}
                  />
                  <KpiCard
                    icon={<AlertTriangle size={18} />}
                    label="SLA Breached Findings"
                    value={formatNumber(overview.slaBreachedFindings)}
                    tone={overview.slaBreachedFindings > 0 ? 'warning' : 'success'}
                  />
                  <KpiCard
                    icon={<Layers size={18} />}
                    label="Security Correlation Groups"
                    value={formatNumber(overview.openCorrelationGroups)}
                    tone="gold"
                  />
                </div>

                <Panel
                  title="Documented Vulnerability Risk Prioritisation Formula"
                  subtitle="Deterministic 0–100 composite risk score with band-driven remediation SLAs"
                  actions={<SimulatedBadge simulated={overview.simulated} />}
                >
                  <div className="callout">
                    <ShieldCheck size={16} />
                    <div>
                      <b>Formula:</b> <code>{overview.riskFormula}</code>
                      <div className="mt-1 text-xs text-muted">
                        Remediation SLA bands: <b>CRITICAL (≥ 80)</b> = 7 days · <b>HIGH (60–79)</b> = 14 days ·{' '}
                        <b>MEDIUM (40–59)</b> = 30 days · <b>LOW (&lt; 40)</b> = 90 days.
                      </div>
                    </div>
                  </div>
                </Panel>
              </>
            )}
          </div>
        )}

        {tab === 'incidents' && (
          <div className="grid gap-4 lg:grid-cols-12">
            <div className="lg:col-span-5">
              <Panel title="Security Incidents" subtitle="Restricted visibility (SECOPS & SECOPS_READERS)">
                {incidentsQuery.isLoading && <LoadingState label="Loading security incidents…" />}
                {incidents.length === 0 && !incidentsQuery.isLoading && (
                  <EmptyState title="No security incidents recorded" description="Declare a security incident or raise one from a correlation group." />
                )}
                <div className="space-y-2">
                  {incidents.map((inc: SecurityIncidentDto) => (
                    <button
                      key={inc.id}
                      type="button"
                      onClick={() => setSelectedIncidentId(inc.id)}
                      className={`w-full text-left rounded-lg border p-3 transition ${
                        inc.id === effectiveIncidentId
                          ? 'border-amber-500 bg-amber-50/20 dark:bg-amber-950/20'
                          : 'border-slate-200 dark:border-slate-800'
                      }`}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-mono text-xs font-semibold">
                          {inc.number} · {inc.category}
                        </span>
                        <div className="flex items-center gap-1.5">
                          <SimulatedBadge simulated={inc.simulated} />
                          <ToneBadge value={inc.severity} label={inc.severity} tone={severityTone(inc.severity)} />
                          <ToneBadge value={inc.status} label={inc.status} tone="gold" />
                        </div>
                      </div>
                      <div className="mt-1 text-sm font-medium">{inc.title}</div>
                      <div className="mt-1 flex items-center justify-between text-[11px] text-muted">
                        <span>{inc.affectedUserEmail ?? 'No user specified'}</span>
                        <span>{formatRelative(inc.createdAt)}</span>
                      </div>
                    </button>
                  ))}
                </div>
              </Panel>
            </div>

            <div className="lg:col-span-7">
              <Panel
                title={
                  incidentDetail
                    ? `${incidentDetail.incident.number} · ${incidentDetail.incident.title}`
                    : 'Security Incident Response'
                }
                subtitle={
                  incidentDetail
                    ? `Lifecycle: NEW → TRIAGE → CONTAINMENT → ERADICATION → RECOVERY → CLOSED`
                    : 'Select an incident'
                }
              >
                {!incidentDetail ? (
                  <EmptyState title="No security incident selected" />
                ) : (
                  <div className="space-y-4">
                    <div className="flex flex-wrap items-center gap-2">
                      <SimulatedBadge simulated={incidentDetail.incident.simulated} />
                      <ToneBadge
                        value={incidentDetail.incident.severity}
                        label={incidentDetail.incident.severity}
                        tone={severityTone(incidentDetail.incident.severity)}
                      />
                      <ToneBadge value={incidentDetail.incident.status} label={incidentDetail.incident.status} tone="gold" />
                      {canSecOps &&
                        INCIDENT_STATUSES.filter((s) => s !== incidentDetail.incident.status).map((nextStatus) => (
                          <button
                            key={nextStatus}
                            type="button"
                            className="subtle-btn compact"
                            disabled={transitionIncident.isPending}
                            onClick={() =>
                              transitionIncident.mutate(
                                {
                                  id: incidentDetail.incident.id,
                                  status: nextStatus,
                                  lessonsLearned: nextStatus === 'CLOSED' ? lessonsInput || 'Post-incident containment verified' : undefined,
                                },
                                {
                                  onSuccess: () => toast.success(`Transitioned to ${nextStatus}`),
                                  onError: (err) => toast.error(errorMessage(err)),
                                },
                              )
                            }
                          >
                            → {nextStatus}
                          </button>
                        ))}
                    </div>

                    {canSecOps && incidentDetail.incident.status !== 'CLOSED' && (
                      <div>
                        <label htmlFor="sec-lessons" className="field-label">Lessons Learned (for CLOSED transition)</label>
                        <input
                          id="sec-lessons"
                          className="input-control"
                          value={lessonsInput}
                          onChange={(e) => setLessonsInput(e.target.value)}
                          placeholder="Document root cause and control improvements…"
                        />
                      </div>
                    )}

                    {/* Response Tasks Checklist */}
                    <div>
                      <h4 className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted">
                        Response Tasks Checklist ({incidentDetail.tasks.length})
                      </h4>
                      {incidentDetail.tasks.length === 0 ? (
                        <p className="text-xs text-muted">No response checklist tasks attached.</p>
                      ) : (
                        <div className="space-y-1.5">
                          {incidentDetail.tasks.map((task: SecurityIncidentTaskDto) => (
                            <div
                              key={task.id}
                              className="flex items-center justify-between rounded border border-slate-200 p-2.5 text-xs dark:border-slate-800"
                            >
                              <div>
                                <span className="font-mono font-semibold mr-2">[{task.phase}]</span>
                                <b>{task.title}</b>
                                {task.remediationPlaybookCode && (
                                  <span className="ml-2 font-mono text-[11px] text-amber-500">
                                    Playbook: {task.remediationPlaybookCode}
                                  </span>
                                )}
                              </div>
                              <div className="flex items-center gap-2">
                                <ToneBadge
                                  value={task.status}
                                  label={task.status}
                                  tone={task.status === 'COMPLETED' ? 'success' : 'warning'}
                                />
                                {canSecOps && task.status !== 'COMPLETED' && (
                                  <button
                                    type="button"
                                    className="gold-btn compact"
                                    disabled={completeTask.isPending}
                                    onClick={() =>
                                      completeTask.mutate(
                                        { incidentId: incidentDetail.incident.id, taskId: task.id },
                                        {
                                          onSuccess: () => toast.success(`Completed task: ${task.title}`),
                                          onError: (err) => toast.error(errorMessage(err)),
                                        },
                                      )
                                    }
                                  >
                                    <CheckCircle2 size={12} /> Complete
                                  </button>
                                )}
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>

                    {/* Indicators of Compromise & Evidence Notes */}
                    <div className="space-y-2">
                      <h4 className="text-xs font-semibold uppercase tracking-wider text-muted">
                        Indicators of Compromise ({incidentDetail.incident.indicators.length}) & Evidence Notes (
                        {incidentDetail.incident.evidenceNotes.length})
                      </h4>
                      {incidentDetail.incident.indicators.length > 0 && (
                        <div className="flex flex-wrap gap-1.5">
                          {incidentDetail.incident.indicators.map((ioc, idx) => (
                            <span key={idx} className="rounded border border-slate-200 px-2 py-0.5 font-mono text-[11px] dark:border-slate-700">
                              {ioc.type}: {ioc.value} ({ioc.confidence})
                            </span>
                          ))}
                        </div>
                      )}
                      {incidentDetail.incident.evidenceNotes.map((n, idx) => (
                        <div key={idx} className="rounded border border-slate-200 p-2 text-xs dark:border-slate-800">
                          <b>{n.author}:</b> {n.note} <span className="text-muted">({formatDateTime(n.createdAt)})</span>
                        </div>
                      ))}

                      {canSecOps && (
                        <div className="grid gap-2 sm:grid-cols-4 pt-2">
                          <input
                            aria-label="Evidence note"
                            className="input-control sm:col-span-2"
                            value={noteInput}
                            onChange={(e) => setNoteInput(e.target.value)}
                            placeholder="Add forensic evidence note…"
                          />
                          <input
                            aria-label="IOC value"
                            className="input-control"
                            value={iocValue}
                            onChange={(e) => setIocValue(e.target.value)}
                            placeholder="IOC (e.g. 198.51.100.42)"
                          />
                          <button
                            type="button"
                            className="subtle-btn"
                            onClick={() => {
                              if (!noteInput.trim()) return;
                              addNote.mutate(
                                {
                                  id: incidentDetail.incident.id,
                                  note: noteInput.trim(),
                                  indicatorType: iocValue.trim() ? iocType : null,
                                  indicatorValue: iocValue.trim() || null,
                                },
                                {
                                  onSuccess: () => {
                                    setNoteInput('');
                                    setIocValue('');
                                    toast.success('Evidence note & IOC recorded');
                                  },
                                },
                              );
                            }}
                          >
                            Add Evidence
                          </button>
                        </div>
                      )}
                    </div>
                  </div>
                )}
              </Panel>
            </div>
          </div>
        )}

        {tab === 'findings' && (
          <div className="space-y-4">
            {canSecOps && (
              <div className="grid gap-4 lg:grid-cols-2">
                <Panel
                  title="Vulnerability Scanner Sources (Token shown once)"
                  subtitle="Configure inbound scanner sources for GENERIC_JSON, SARIF 2.1.0, TRIVY_JSON or CSV"
                >
                  <div className="grid gap-2 sm:grid-cols-3 mb-3">
                    <input
                      aria-label="Source name"
                      className="input-control"
                      value={srcName}
                      onChange={(e) => setSrcName(e.target.value)}
                      placeholder="Prod Trivy Scanner"
                    />
                    <select
                      aria-label="Source format"
                      className="input-control"
                      value={srcFormat}
                      onChange={(e) => setSrcFormat(e.target.value as typeof srcFormat)}
                    >
                      {INGEST_FORMATS.map((f) => (
                        <option key={f} value={f}>{f}</option>
                      ))}
                    </select>
                    <button type="button" className="gold-btn" onClick={handleCreateSource}>
                      <KeyRound size={14} /> Create Source
                    </button>
                  </div>

                  {createdToken && (
                    <div className="callout green mb-3" role="alert">
                      <KeyRound size={16} />
                      <div>
                        <b>One-Time Ingestion Token (save now — never shown again):</b>{' '}
                        <code className="select-all">{createdToken}</code>
                      </div>
                    </div>
                  )}

                  <div className="space-y-1.5">
                    {sources.map((s: VulnerabilitySourceDto) => (
                      <div key={s.id} className="flex items-center justify-between rounded border border-slate-200 p-2 text-xs dark:border-slate-800">
                        <span>
                          <b>{s.name}</b> ({s.scannerName} · <code>{s.format}</code>)
                        </span>
                        <span className="font-mono text-muted">Prefix: {s.tokenPrefix}…</span>
                      </div>
                    ))}
                  </div>
                </Panel>

                <Panel
                  title="Ingest Vulnerability Scan Payload"
                  subtitle="Deduplicates by (source, CVE, asset, package); full scans auto-close absent findings"
                >
                  <div className="space-y-2">
                    <div className="flex flex-wrap items-center gap-3">
                      <select
                        aria-label="Ingest format"
                        className="input-control"
                        value={ingestFormat}
                        onChange={(e) => setIngestFormat(e.target.value as typeof ingestFormat)}
                      >
                        {INGEST_FORMATS.map((f) => (
                          <option key={f} value={f}>{f}</option>
                        ))}
                      </select>
                      <label className="inline-flex items-center gap-1.5 text-xs">
                        <input
                          type="checkbox"
                          checked={ingestFullScan}
                          onChange={(e) => setIngestFullScan(e.target.checked)}
                        />
                        Full scan (auto-close absent findings)
                      </label>
                      <button
                        type="button"
                        className="gold-btn compact"
                        disabled={ingestVulns.isPending}
                        onClick={handleIngest}
                      >
                        <Upload size={13} /> Ingest Findings
                      </button>
                    </div>
                    <textarea
                      aria-label="Vulnerability payload"
                      rows={3}
                      className="input-control font-mono text-xs w-full"
                      value={ingestPayload}
                      onChange={(e) => setIngestPayload(e.target.value)}
                    />
                  </div>
                </Panel>
              </div>
            )}

            <Panel
              title={`Prioritised Vulnerability Findings (${findings.length})`}
              subtitle="Sorted by composite risk score (CVSS × Criticality × Exposure × Known Exploit)"
            >
              {findingsQuery.isLoading && <LoadingState label="Loading vulnerability findings…" />}
              {findings.length === 0 && !findingsQuery.isLoading && (
                <EmptyState title="No vulnerability findings ingested yet" />
              )}
              {findings.length > 0 && (
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>CVE / Title</th>
                        <th>Asset / Package</th>
                        <th>CVSS</th>
                        <th>Exposure / Exploit</th>
                        <th>Risk Score</th>
                        <th>SLA</th>
                        <th>Status</th>
                        {canSecOps && <th>Action</th>}
                      </tr>
                    </thead>
                    <tbody>
                      {findings.map((f: VulnerabilityFindingDto) => (
                        <tr key={f.id}>
                          <td>
                            <b>{f.cveId ?? 'N/A'}</b> · {f.title}
                            <div className="text-[11px] text-muted">
                              Scanner: {f.scanner} <SimulatedBadge simulated={f.simulated} />
                            </div>
                          </td>
                          <td>
                            <code>{f.assetName}</code>
                            <div className="text-[11px] text-muted">
                              {f.packageName} {f.installedVersion} → {f.fixedVersion ?? 'unfixed'}
                            </div>
                          </td>
                          <td><b>{f.cvssBase.toFixed(1)}</b></td>
                          <td>
                            <div className="flex flex-wrap gap-1">
                              <ToneBadge value={f.assetCriticality} label={f.assetCriticality} tone="neutral" />
                              {f.internetExposed && <ToneBadge value="INTERNET" label="Internet-exposed" tone="warning" />}
                              {f.exploitKnown && <ToneBadge value="KEV" label="Known Exploit" tone="critical" />}
                            </div>
                          </td>
                          <td>
                            <b>{f.riskScore.toFixed(1)}</b>{' '}
                            <ToneBadge value={f.riskBand} label={f.riskBand} tone={severityTone(f.riskBand)} />
                          </td>
                          <td>{f.slaDays}d</td>
                          <td>
                            <ToneBadge value={f.status} label={f.status} tone={f.status === 'OPEN' ? 'warning' : 'success'} />
                          </td>
                          {canSecOps && (
                            <td>
                              {f.status === 'OPEN' && (
                                <button
                                  type="button"
                                  className="subtle-btn compact"
                                  onClick={() =>
                                    acceptRisk.mutate(
                                      { id: f.id, reason: 'Compensating WAF control in place for 30 days', days: 30 },
                                      {
                                        onSuccess: () => toast.info(`Accepted risk for ${f.cveId ?? f.title} (30 days)`),
                                      },
                                    )
                                  }
                                >
                                  Accept Risk (30d)
                                </button>
                              )}
                            </td>
                          )}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Panel>
          </div>
        )}

        {tab === 'correlation' && (
          <Panel
            title="Security Alert & Signal Correlation Groups"
            subtitle="Correlates security-labelled alerts, critical vulnerability findings and auth failed-login bursts by asset and user"
            actions={
              canSecOps ? (
                <button
                  type="button"
                  className="gold-btn compact"
                  disabled={runCorrelation.isPending}
                  onClick={() =>
                    runCorrelation.mutate(undefined, {
                      onSuccess: (groups) => toast.success(`Correlation complete: ${groups.length} active groups`),
                    })
                  }
                >
                  <RefreshCw size={13} /> Run Correlation
                </button>
              ) : undefined
            }
          >
            {corrGroupsQuery.isLoading && <LoadingState label="Loading security correlation groups…" />}
            {corrGroups.length === 0 && !corrGroupsQuery.isLoading && (
              <EmptyState title="No security correlation groups" description="Run correlation to group multi-signal security events." />
            )}
            <div className="space-y-3">
              {corrGroups.map((g: SecurityCorrelationGroupDto) => (
                <div key={g.id} className="rounded-lg border border-slate-200 p-3.5 dark:border-slate-800 flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="font-mono text-xs font-semibold">{g.groupKey}</span>
                      <SimulatedBadge simulated={g.simulated} />
                      <ToneBadge value={g.severity} label={g.severity} tone={severityTone(g.severity)} />
                      <ToneBadge value={g.status} label={g.status} tone="gold" />
                    </div>
                    <div className="mt-1 text-sm font-medium">{g.title}</div>
                    <div className="mt-0.5 text-xs text-muted">
                      Asset: <code>{g.targetAsset ?? '—'}</code> · User: <code>{g.targetUser ?? '—'}</code> · Signals:{' '}
                      <code>{JSON.stringify(g.signalSummary)}</code>
                    </div>
                  </div>
                  <div>
                    {g.securityIncidentId ? (
                      <ToneBadge value="LINKED" label={`Security Incident #${g.securityIncidentId}`} tone="success" />
                    ) : (
                      canSecOps && (
                        <button
                          type="button"
                          className="gold-btn compact"
                          disabled={raiseFromGroup.isPending}
                          onClick={() =>
                            raiseFromGroup.mutate(g.id, {
                              onSuccess: (created) => {
                                setSelectedIncidentId(created.incident.id);
                                setTab('incidents');
                                toast.success(`Raised Security Incident ${created.incident.number}`);
                              },
                            })
                          }
                        >
                          Raise Security Incident
                        </button>
                      )
                    )}
                  </div>
                </div>
              ))}
            </div>
          </Panel>
        )}

        {tab === 'templates' && (
          <Panel
            title="Security Response Playbook Templates"
            subtitle="Standardised NIST/SANS phase task checklists with linked autonomous remediation playbooks"
          >
            <div className="grid gap-4 md:grid-cols-3">
              {templates.map((tpl: SecurityResponseTemplateDto) => (
                <div key={tpl.code} className="rounded-lg border border-slate-200 p-4 dark:border-slate-800 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs font-semibold">{tpl.code}</span>
                    <ToneBadge value={tpl.category} label={tpl.category} tone="gold" />
                  </div>
                  <h4 className="text-sm font-semibold">{tpl.name}</h4>
                  {tpl.description && <p className="text-xs text-muted">{tpl.description}</p>}
                  {tpl.recommendedPlaybookCode && (
                    <div className="text-xs">
                      Recommended Remediation Playbook: <code>{tpl.recommendedPlaybookCode}</code>
                    </div>
                  )}
                  <div className="text-[11px] text-muted">{tpl.tasks.length} checklist tasks across response phases</div>
                </div>
              ))}
            </div>
          </Panel>
        )}
      </div>
    </>
  );
}
