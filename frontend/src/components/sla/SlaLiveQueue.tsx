import { useState } from 'react';
import { useSlaActive } from '../../api/sla';
import { titleCase } from '../../lib/format';
import { IncidentTable } from '../incidents/IncidentTable';
import { Pagination } from '../Pagination';
import { Panel } from '../Panel';
import { ErrorState, LoadingState } from '../States';
import { Tabs } from '../Tabs';

export type QueueTab = 'AT_RISK' | 'BREACHED' | 'ON_TRACK' | 'PAUSED';
const QUEUE_TABS: QueueTab[] = ['AT_RISK', 'BREACHED', 'ON_TRACK', 'PAUSED'];

/** Live SLA queue: active clocks by health, ordered by due time, with ticking countdowns (refreshes every minute). */
export function SlaLiveQueue({ counts }: { counts?: Partial<Record<QueueTab, number>> }) {
  const [tab, setTab] = useState<QueueTab>('AT_RISK');
  const [page, setPage] = useState(0);
  const active = useSlaActive({ health: tab, page, size: 15 });
  return (
    <Panel title="Live SLA Queue" subtitle="Ordered by due time · countdowns tick every second and refresh every minute">
      <Tabs<QueueTab>
        inner
        label="SLA health"
        value={tab}
        onChange={(id) => {
          setTab(id);
          setPage(0);
        }}
        tabs={QUEUE_TABS.map((id) => ({
          id,
          label: titleCase(id),
          count: id === tab && active.data && !active.isPlaceholderData ? active.data.totalElements : counts?.[id],
        }))}
      />
      {active.isLoading && <LoadingState rows={5} label="Loading the SLA queue…" />}
      {active.isError && !active.data && <ErrorState error={active.error} onRetry={() => void active.refetch()} />}
      {active.data && (
        <>
          <IncidentTable
            incidents={active.data.content}
            columns={['number', 'title', 'priority', 'service', 'assignee', 'sla', 'status']}
            empty={`No incidents are ${titleCase(tab).toLowerCase()} right now.`}
          />
          <Pagination
            page={active.data.page}
            totalPages={active.data.totalPages}
            totalElements={active.data.totalElements}
            size={active.data.size}
            onPageChange={setPage}
            label="incidents"
          />
        </>
      )}
    </Panel>
  );
}
