import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { GitBranch, X } from 'lucide-react';
import { useTrace, useTraces } from '../../api/observability';
import { useServices } from '../../api/services';
import { SimulatedBadge } from '../../components/Badge';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { formatDateTime, formatNumber, formatRelative, titleCase } from '../../lib/format';
import type { SpanNode, TraceDetail } from '../../lib/r4Types';
import { formatMs, KeyValueList, RangeSelect, useTimeWindow, type RangeValue } from './obsShared';

export interface FlatSpan {
  span: SpanNode;
  depth: number;
}

/** Depth-first order of the span tree (children by start offset), for the waterfall. */
export function flattenSpans(roots: SpanNode[]): FlatSpan[] {
  const out: FlatSpan[] = [];
  const visit = (node: SpanNode, depth: number) => {
    out.push({ span: node, depth });
    [...(node.children ?? [])].sort((a, b) => a.offsetMs - b.offsetMs).forEach((child) => visit(child, depth + 1));
  };
  [...roots].sort((a, b) => a.offsetMs - b.offsetMs).forEach((root) => visit(root, 0));
  return out;
}

/** Bar position in % of the trace duration (clamped; zero-length traces show full-width bars). */
export function spanBar(span: Pick<SpanNode, 'offsetMs' | 'durationMs'>, traceDurationMs: number): { left: number; width: number } {
  if (!traceDurationMs || traceDurationMs <= 0) return { left: 0, width: 100 };
  const left = Math.min(100, Math.max(0, (span.offsetMs / traceDurationMs) * 100));
  const width = Math.min(100 - left, Math.max(0.4, (span.durationMs / traceDurationMs) * 100));
  return { left, width };
}

export function TraceWaterfall({ trace }: { trace: TraceDetail }) {
  const flat = flattenSpans(trace.roots);
  const [selected, setSelected] = useState<string | null>(null);
  const chosen = flat.find((entry) => entry.span.spanId === selected)?.span ?? null;
  return (
    <div className="grid gap-3">
      <ol className="obs-waterfall" aria-label="Span waterfall">
        {flat.map(({ span, depth }) => {
          const bar = spanBar(span, trace.durationMs);
          const error = span.statusCode === 'ERROR';
          return (
            <li key={span.spanId} className="obs-span">
              <button type="button" style={{ paddingLeft: Math.min(depth, 12) * 12 }} aria-pressed={span.spanId === selected} onClick={() => setSelected((current) => (current === span.spanId ? null : span.spanId))} title={`${span.serviceName ?? ''} ${span.name}`}>
                <span className="text-muted">{span.serviceName ?? 'unknown'}</span> {span.name}
                {error && <span className="red-text"> · error</span>}
              </button>
              <div className="obs-span-track" aria-label={`${span.name}: starts at ${formatMs(span.offsetMs)}, takes ${formatMs(span.durationMs)}${error ? ', error' : ''}`}>
                <span className="obs-span-bar" data-error={error} style={{ left: `${bar.left}%`, width: `${bar.width}%` }} />
                <span className="obs-span-label">{formatMs(span.durationMs)}</span>
              </div>
            </li>
          );
        })}
      </ol>
      {chosen && (
        <div className="rounded-[9px] border border-line p-3" role="region" aria-label={`Span ${chosen.name}`}>
          <div className="mb-2 flex flex-wrap items-center gap-1.5 text-[11px]">
            <b>{chosen.name}</b>
            <ToneBadge value={chosen.kind} label={titleCase(chosen.kind)} tone="neutral" />
            <ToneBadge value={chosen.statusCode} label={titleCase(chosen.statusCode)} tone={chosen.statusCode === 'ERROR' ? 'critical' : chosen.statusCode === 'OK' ? 'success' : 'muted'} />
            <SimulatedBadge simulated={chosen.simulated} />
            <button type="button" className="icon-btn sm ml-auto" aria-label="Close span details" onClick={() => setSelected(null)}>
              <X size={12} />
            </button>
          </div>
          <small className="mb-2 block text-muted">
            {chosen.serviceName ?? 'Unknown service'} · span {chosen.spanId}
            {chosen.parentSpanId ? ` · parent ${chosen.parentSpanId}` : ' · root'} · {formatMs(chosen.durationMs)} from {formatDateTime(chosen.startTime)}
            {chosen.statusMessage ? ` · ${chosen.statusMessage}` : ''}
          </small>
          <KeyValueList values={chosen.attributes} label="Span attributes" />
        </div>
      )}
    </div>
  );
}

function TraceDetailPanel({ traceId, onClose }: { traceId: string; onClose: () => void }) {
  const trace = useTrace(traceId);
  return (
    <Panel
      title={<span className="break-all">Trace {traceId}</span>}
      subtitle={trace.data ? `${formatNumber(trace.data.spanCount)} spans · ${formatMs(trace.data.durationMs)} · ${formatNumber(trace.data.errorCount)} errors · ${formatDateTime(trace.data.startTime)}` : undefined}
      actions={
        <button type="button" className="icon-btn sm" aria-label="Close trace" onClick={onClose}>
          <X size={13} />
        </button>
      }
    >
      {trace.isLoading && <LoadingState variant="panel" label="Loading trace…" />}
      {trace.isError && <ErrorState error={trace.error} onRetry={() => void trace.refetch()} />}
      {trace.data && (
        <>
          <div className="mb-2 flex flex-wrap gap-1.5">
            <SimulatedBadge simulated={trace.data.simulated} />
            {trace.data.truncated && <ToneBadge value="truncated" label="Truncated to 5,000 spans" tone="warning" />}
          </div>
          {trace.data.roots.length ? <TraceWaterfall trace={trace.data} /> : <EmptyState title="This trace has no spans" />}
        </>
      )}
    </Panel>
  );
}

/** Traces: list (service, name, min duration, errors only, window) and the trace detail with a span waterfall/tree. */
export function TracesTab({ includeSimulated }: { includeSimulated: boolean }) {
  const [params, setParams] = useSearchParams();
  const [serviceId, setServiceId] = useState('');
  const [name, setName] = useState('');
  const [minDuration, setMinDuration] = useState('');
  const [errorsOnly, setErrorsOnly] = useState(false);
  const [range, setRange] = useState<RangeValue>('1h');
  const window = useTimeWindow(range);
  const services = useServices();
  const traceId = params.get('trace');
  const setTraceId = (id: string | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (id) next.set('trace', id);
        else next.delete('trace');
        return next;
      },
      { replace: true },
    );
  const traces = useTraces({
    serviceId: serviceId ? Number(serviceId) : undefined,
    name: name.trim() || undefined,
    minDurationMs: minDuration ? Number(minDuration) : undefined,
    error: errorsOnly || undefined,
    from: window.from,
    to: window.to,
    includeSimulated,
    limit: 100,
  });

  return (
    <div className="grid items-start gap-[14px]">
      <Panel title="Traces" subtitle="Newest first, up to 100 per query">
        <div className="obs-filters" role="group" aria-label="Trace filters">
          <div className="field">
            <div className="field-label">
              <label htmlFor="obs-trace-service">Service</label>
            </div>
            <select id="obs-trace-service" className="control sm" value={serviceId} onChange={(event) => setServiceId(event.target.value)}>
              <option value="">All services</option>
              {(services.data ?? []).map((service) => (
                <option key={service.id} value={service.id}>
                  {service.name}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <div className="field-label">
              <label htmlFor="obs-trace-name">Root span name</label>
            </div>
            <input id="obs-trace-name" className="control sm" value={name} onChange={(event) => setName(event.target.value)} placeholder="e.g. GET /payslips" />
          </div>
          <div className="field">
            <div className="field-label">
              <label htmlFor="obs-trace-min">Min duration (ms)</label>
            </div>
            <input id="obs-trace-min" className="control sm" type="number" min={0} value={minDuration} onChange={(event) => setMinDuration(event.target.value)} />
          </div>
          <div className="field">
            <div className="field-label">
              <label htmlFor="obs-trace-range">Time range</label>
            </div>
            <RangeSelect id="obs-trace-range" value={range} onChange={setRange} onRefresh={window.refresh} />
          </div>
          <label className="inline-flex items-center gap-2 text-[11px]">
            <input type="checkbox" checked={errorsOnly} onChange={(event) => setErrorsOnly(event.target.checked)} /> Errors only
          </label>
        </div>
        {traces.isLoading && <LoadingState rows={6} label="Loading traces…" />}
        {traces.isError && <ErrorState error={traces.error} onRetry={() => void traces.refetch()} />}
        {traces.data && traces.data.length === 0 && <EmptyState icon={<GitBranch size={20} />} title="No traces in this window" description="Traces appear once an OTLP source delivers spans." />}
        {traces.data && traces.data.length > 0 && (
          <div className="table-scroll">
            <table className="data-table">
              <caption className="sr-only">Traces</caption>
              <thead>
                <tr>
                  <th scope="col">Root span</th>
                  <th scope="col">Service</th>
                  <th scope="col">Duration</th>
                  <th scope="col">Spans</th>
                  <th scope="col">Errors</th>
                  <th scope="col">Started</th>
                </tr>
              </thead>
              <tbody>
                {traces.data.map((trace) => (
                  <tr key={trace.traceId} className={trace.traceId === traceId ? 'selected' : undefined}>
                    <td>
                      <button type="button" className="link-btn" onClick={() => setTraceId(trace.traceId)} aria-label={`Open trace ${trace.rootName ?? trace.traceId}`}>
                        {trace.rootName ?? trace.traceId.slice(0, 16)}
                      </button>{' '}
                      <SimulatedBadge simulated={trace.simulated} />
                    </td>
                    <td>{trace.rootServiceName ?? '—'}</td>
                    <td>{formatMs(trace.durationMs)}</td>
                    <td>{formatNumber(trace.spanCount)}</td>
                    <td className={trace.errorCount ? 'red-text' : undefined}>{formatNumber(trace.errorCount)}</td>
                    <td title={formatDateTime(trace.startTime)}>{formatRelative(trace.startTime)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
      {traceId && <TraceDetailPanel traceId={traceId} onClose={() => setTraceId(null)} />}
    </div>
  );
}
