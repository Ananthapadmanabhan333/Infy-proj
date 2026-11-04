import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { History } from 'lucide-react';
import { useCi } from '../../api/cmdb';
import { useServices } from '../../api/services';
import { useTwinChanges } from '../../api/twin';
import { SimulatedBadge } from '../../components/Badge';
import { CiSinglePicker } from '../../components/cmdb/CiPicker';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import type { Tone } from '../../lib/constants';
import { formatDateTime, formatNumber } from '../../lib/format';
import type { TimelineEntry, TimelineKind } from '../../lib/r4Types';

const KIND_TONES: Record<TimelineKind, Tone> = { CMDB: 'gold', CHANGE: 'champagne', DEPLOYMENT: 'warning', TELEMETRY: 'neutral' };
const KIND_LABELS: Record<TimelineKind, string> = { CMDB: 'CMDB', CHANGE: 'Change', DEPLOYMENT: 'Deployment', TELEMETRY: 'Telemetry' };

export function referenceLink(entry: TimelineEntry): string | null {
  const ref = entry.reference;
  if (ref?.id && ref.type) {
    const type = ref.type.toUpperCase();
    if (type.includes('CHANGE')) return `/changes/${ref.id}`;
    if (type.includes('DEPLOYMENT')) return `/deployments/${ref.id}`;
    if (type === 'CI' || type.includes('CMDB')) return `/cmdb/${ref.id}`;
  }
  if (entry.kind === 'CMDB' && entry.ciId) return `/cmdb/${entry.ciId}`;
  return null;
}

/** Unified change timeline (`/twin/changes`): CMDB history, change requests, deployments, telemetry attribute changes. */
export function TwinTimelineTab({ includeSimulated, initialCi }: { includeSimulated: boolean; initialCi: number | null }) {
  const services = useServices();
  const [ciId, setCiId] = useState<number | null>(initialCi);
  const [serviceId, setServiceId] = useState('');
  const [days, setDays] = useState('30');
  const [kind, setKind] = useState<TimelineKind | ''>('');
  const ciDetail = useCi(ciId);
  const window = useMemo(() => {
    const to = Math.ceil(Date.now() / 60_000) * 60_000;
    return { from: new Date(to - Number(days) * 86_400_000).toISOString(), to: new Date(to).toISOString() };
  }, [days]);
  const timeline = useTwinChanges({ ciId: ciId ?? undefined, serviceId: serviceId ? Number(serviceId) : undefined, from: window.from, to: window.to, includeSimulated, limit: 200 });
  const entries = (timeline.data?.entries ?? []).filter((entry) => !kind || entry.kind === kind);

  return (
    <Panel title="Change timeline" subtitle="CMDB history, change requests, deployments and telemetry resource changes, newest first">
      <div className="mb-3 grid items-end gap-2 sm:grid-cols-2 lg:grid-cols-4" role="group" aria-label="Timeline filters">
        <div className="sm:col-span-2">
          <CiSinglePicker label="Configuration item" value={ciId ? { id: ciId, name: ciDetail.data?.name ?? `CI #${ciId}`, ciType: ciDetail.data?.ciType } : null} onChange={(ci) => setCiId(ci?.id ?? null)} />
        </div>
        <div className="field">
          <div className="field-label">
            <label htmlFor="twin-tl-service">Service</label>
          </div>
          <select id="twin-tl-service" className="control" value={serviceId} onChange={(event) => setServiceId(event.target.value)}>
            <option value="">All services</option>
            {(services.data ?? []).map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </select>
        </div>
        <div className="grid grid-cols-2 gap-2">
          <div className="field">
            <div className="field-label">
              <label htmlFor="twin-tl-days">Period</label>
            </div>
            <select id="twin-tl-days" className="control" value={days} onChange={(event) => setDays(event.target.value)}>
              <option value="7">7 days</option>
              <option value="30">30 days</option>
              <option value="90">90 days</option>
            </select>
          </div>
          <div className="field">
            <div className="field-label">
              <label htmlFor="twin-tl-kind">Source</label>
            </div>
            <select id="twin-tl-kind" className="control" value={kind} onChange={(event) => setKind(event.target.value as TimelineKind | '')}>
              <option value="">All</option>
              {(Object.keys(KIND_LABELS) as TimelineKind[]).map((value) => (
                <option key={value} value={value}>
                  {KIND_LABELS[value]}
                </option>
              ))}
            </select>
          </div>
        </div>
      </div>
      {timeline.isLoading && <LoadingState rows={6} />}
      {timeline.isError && <ErrorState error={timeline.error} onRetry={() => void timeline.refetch()} />}
      {timeline.data && entries.length === 0 && <EmptyState icon={<History size={20} />} title="No changes in this period" />}
      {entries.length > 0 && (
        <>
          <ol className="m-0 grid list-none gap-1 p-0" aria-label="Change timeline">
            {entries.map((entry, index) => {
              const href = referenceLink(entry);
              return (
                <li key={`${entry.at}-${index}`} className="list-row !items-start">
                  <div className="min-w-0 flex-1">
                    <b className="flex flex-wrap items-center gap-1.5">
                      <ToneBadge value={entry.kind} label={KIND_LABELS[entry.kind] ?? entry.kind} tone={KIND_TONES[entry.kind] ?? 'neutral'} />
                      {href ? <Link to={href}>{entry.title}</Link> : <span className="break-words">{entry.title}</span>}
                      {entry.reference?.number && <span className="id-cell">{entry.reference.number}</span>}
                      <SimulatedBadge simulated={entry.simulated} />
                    </b>
                    <small className="block">
                      {formatDateTime(entry.at)} · {entry.actorName ?? 'System'}
                      {entry.ciName ? ` · ${entry.ciName}` : ''}
                    </small>
                    {entry.diff && entry.diff.length > 0 && (
                      <ul className="kv-list mt-1" aria-label={`Diff of ${entry.title}`}>
                        {entry.diff.map((change, diffIndex) => (
                          <li key={`${change.field}-${diffIndex}`}>
                            <span>{change.field}</span>
                            <span>
                              <del className="text-muted">{change.from ?? '—'}</del> → <ins className="no-underline">{change.to ?? '—'}</ins>
                            </span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                </li>
              );
            })}
          </ol>
          {timeline.data?.truncated && <p className="mb-0 mt-2 text-[11px] text-muted">Showing the newest {formatNumber(timeline.data.entries.length)} entries — narrow the filters to see older ones.</p>}
        </>
      )}
    </Panel>
  );
}
