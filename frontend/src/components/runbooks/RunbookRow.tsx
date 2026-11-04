import { Link } from 'react-router-dom';
import { ClipboardList, Clock3, History, ListOrdered, ShieldAlert, ShieldCheck } from 'lucide-react';
import { titleCase } from '../../lib/format';
import type { RunbookDto } from '../../lib/types';
import { Badge, BooleanBadge, DemoBadge } from '../Badge';

/** Number of sensitive (approval-gated) steps. */
export function sensitiveCount(runbook: Pick<RunbookDto, 'steps'>): number {
  return runbook.steps.filter((step) => step.requiresApproval).length;
}

/** Runbook in the library list: title, description, service, categories, steps, estimate, approval and status. */
export function RunbookRow({ runbook }: { runbook: RunbookDto }) {
  const sensitive = sensitiveCount(runbook);
  return (
    <Link to={`/runbooks/${runbook.id}`} className="rb-row" aria-label={`Runbook ${runbook.title}`}>
      <span className="icon-tile" aria-hidden="true">
        <ClipboardList size={17} />
      </span>
      <span className="rb-row-main">
        <b>
          {runbook.title} <DemoBadge demo={runbook.demo} />
        </b>
        {runbook.description && <p title={runbook.description}>{runbook.description}</p>}
        <span className="rb-meta">
          <span>{runbook.serviceName ?? 'Any service'}</span>
          <span>{runbook.categories.length ? runbook.categories.map(titleCase).join(', ') : 'All categories'}</span>
          <span>
            <ListOrdered size={11} aria-hidden="true" /> {runbook.steps.length} step{runbook.steps.length === 1 ? '' : 's'}
          </span>
          {sensitive > 0 && (
            <span className="text-warning">
              <ShieldAlert size={11} aria-hidden="true" /> {sensitive} sensitive
            </span>
          )}
          {runbook.estimatedMinutes ? (
            <span>
              <Clock3 size={11} aria-hidden="true" /> ~{runbook.estimatedMinutes} min
            </span>
          ) : null}
          <span>
            <History size={11} aria-hidden="true" /> {runbook.executionCount} run{runbook.executionCount === 1 ? '' : 's'}
          </span>
        </span>
      </span>
      <span className="rb-row-side">
        {runbook.requiresApproval && <Badge value="pending-approval" label={<><ShieldCheck size={10} /> Approval required</>} />}
        <BooleanBadge value={runbook.active} />
      </span>
    </Link>
  );
}
