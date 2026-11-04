import { useState } from 'react';
import { ChevronDown, ChevronRight, ScrollText } from 'lucide-react';
import { useLogs } from '../../api/observability';
import { useServices } from '../../api/services';
import { SimulatedBadge } from '../../components/Badge';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useDebounce } from '../../hooks/useDebounce';
import { formatDateTime } from '../../lib/format';
import type { LogDto } from '../../lib/r4Types';
import { KeyValueList, LOG_SEVERITIES, LogSeverityBadge, RangeSelect, useTimeWindow, type RangeValue } from './obsShared';

const PAGE_SIZE = 50;

function LogRow({ log }: { log: LogDto }) {
  const [open, setOpen] = useState(false);
  return (
    <li className="obs-log-row">
      <button type="button" className="obs-log-toggle" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
        {open ? <ChevronDown size={12} aria-hidden="true" /> : <ChevronRight size={12} aria-hidden="true" />}
        <span className="obs-log-time" title={formatDateTime(log.timestamp)}>
          {new Date(log.timestamp).toLocaleTimeString()}
        </span>
        <span className="min-w-0">
          <span className="mr-1.5 inline-flex flex-wrap items-center gap-1 align-middle">
            <LogSeverityBadge severity={log.severity} />
            {log.resourceServiceName && <span className="text-muted">{log.resourceServiceName}</span>}
            <SimulatedBadge simulated={log.simulated} />
          </span>
          <span className="obs-log-body">{log.body}</span>
          {log.bodyTruncated && <small className="ml-1 text-muted">(truncated to 8 KB)</small>}
        </span>
      </button>
      {open && (
        <div className="ml-5 mt-2 grid gap-1.5">
          <small className="text-muted">
            {formatDateTime(log.timestamp)}
            {log.severityText ? ` · severity text ${log.severityText}` : ''}
            {log.traceId ? ` · trace ${log.traceId}` : ''}
            {log.spanId ? ` · span ${log.spanId}` : ''}
          </small>
          <KeyValueList values={log.attributes} label="Log attributes" />
        </div>
      )}
    </li>
  );
}

/** Log search: text (substring), minimum severity, service and time window; rows expand to their attributes. */
export function LogsTab({ includeSimulated }: { includeSimulated: boolean }) {
  const [text, setText] = useState('');
  const debounced = useDebounce(text, 350);
  const [severity, setSeverity] = useState('');
  const [serviceId, setServiceId] = useState('');
  const [range, setRange] = useState<RangeValue>('1h');
  const [page, setPage] = useState(0);
  const window = useTimeWindow(range);
  const services = useServices();
  const logs = useLogs({
    text: debounced.trim() || undefined,
    severity: severity || undefined,
    serviceId: serviceId ? Number(serviceId) : undefined,
    from: window.from,
    to: window.to,
    includeSimulated,
    page,
    size: PAGE_SIZE,
  });
  const reset = <T,>(set: (value: T) => void) => (value: T) => {
    setPage(0);
    set(value);
  };
  const rows = logs.data?.content ?? [];

  return (
    <Panel title="Logs" subtitle="Log records from OTLP sources, newest first">
      <div className="toolbar">
        <SearchInput value={text} onChange={reset(setText)} placeholder="Search log bodies…" label="Search logs" />
        <div className="grid w-full grid-cols-2 gap-2 sm:flex sm:w-auto sm:flex-wrap sm:items-center">
          <label className="sr-only" htmlFor="obs-log-severity">
            Minimum severity
          </label>
          <select id="obs-log-severity" className="control sm w-full sm:w-auto" value={severity} onChange={(event) => reset(setSeverity)(event.target.value)}>
            <option value="">Any severity</option>
            {LOG_SEVERITIES.map((value) => (
              <option key={value} value={value}>
                {value} and above
              </option>
            ))}
          </select>
          <label className="sr-only" htmlFor="obs-log-service">
            Service
          </label>
          <select id="obs-log-service" className="control sm w-full sm:w-auto" value={serviceId} onChange={(event) => reset(setServiceId)(event.target.value)}>
            <option value="">All services</option>
            {(services.data ?? []).map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </select>
          <RangeSelect id="obs-log-range" value={range} onChange={reset(setRange)} onRefresh={window.refresh} />
        </div>
      </div>
      {logs.isLoading && <LoadingState rows={8} label="Loading logs…" />}
      {logs.isError && <ErrorState error={logs.error} onRetry={() => void logs.refetch()} />}
      {logs.data && rows.length === 0 && <EmptyState icon={<ScrollText size={20} />} title="No log records in this window" description={debounced || severity || serviceId ? 'Try a wider time range or fewer filters.' : 'Logs appear once an OTLP source delivers them.'} />}
      {rows.length > 0 && logs.data && (
        <>
          <ul className="m-0 list-none p-0" aria-label="Log records">
            {rows.map((log) => (
              <LogRow key={log.id} log={log} />
            ))}
          </ul>
          <Pagination page={logs.data.page} totalPages={logs.data.totalPages} totalElements={logs.data.totalElements} size={logs.data.size} onPageChange={setPage} label="log records" />
        </>
      )}
    </Panel>
  );
}
