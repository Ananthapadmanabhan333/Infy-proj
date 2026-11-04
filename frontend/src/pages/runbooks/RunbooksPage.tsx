import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ClipboardList, Plus } from 'lucide-react';
import { useRunbooks } from '../../api/runbooks';
import { useServices } from '../../api/services';
import { FilterSelect } from '../../components/FilterSelect';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { ExecutionHistory } from '../../components/runbooks/ExecutionHistory';
import { RunbookRow } from '../../components/runbooks/RunbookRow';
import { SearchInput } from '../../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useAuth } from '../../hooks/useAuth';
import { useSearchInput, useUrlFilters } from '../../hooks/useUrlState';
import { CATEGORIES, RUNBOOK_EDITORS } from '../../lib/constants';
import { titleCase } from '../../lib/format';
import type { Category } from '../../lib/types';
import { RunbookEditor } from './RunbookEditor';
import '../../components/runbooks/runbooks.css';

/** Runbook library (`/runbooks`, READERS): search, category / service / status filters and recent executions. */
export default function RunbooksPage() {
  const { hasRole } = useAuth();
  const canEdit = hasRole(...RUNBOOK_EDITORS);
  const navigate = useNavigate();
  const { values, setValues } = useUrlFilters(['search', 'category', 'serviceId', 'state'] as const);
  const [input, setInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const services = useServices();
  const serviceId = values.serviceId ? Number(values.serviceId) : null;
  const runbooks = useRunbooks({ search: values.search || undefined, serviceId, category: (values.category as Category) || '' });
  const [creating, setCreating] = useState(false);
  const filtered = Boolean(values.search || values.category || values.serviceId || values.state);
  // Active / inactive is filtered locally: the list endpoint returns both.
  const list = (runbooks.data ?? []).filter((runbook) => (values.state === 'active' ? runbook.active : values.state === 'inactive' ? !runbook.active : true));

  return (
    <>
      <PageHeader
        title="Runbooks"
        subtitle="Standard operating procedures for resolving incidents"
        actions={
          canEdit && (
            <button type="button" className="gold-btn" onClick={() => setCreating(true)}>
              <Plus size={16} /> New Runbook
            </button>
          )
        }
      />
      <div className="page-stack">
        <div className="toolbar !mb-0">
          <SearchInput value={input} onChange={setInput} placeholder="Search runbooks by title or description…" label="Search runbooks" />
          <FilterSelect label="Category" allLabel="All categories" value={values.category} onChange={(category) => setValues({ category })} options={CATEGORIES} formatOption={titleCase} />
          <FilterSelect
            label="Service"
            allLabel="All services"
            value={values.serviceId}
            onChange={(value) => setValues({ serviceId: value })}
            options={(services.data ?? []).map((service) => ({ value: String(service.id), label: service.name }))}
          />
          <FilterSelect
            label="Status"
            allLabel="Active & inactive"
            value={values.state}
            onChange={(state) => setValues({ state })}
            options={[
              { value: 'active', label: 'Active' },
              { value: 'inactive', label: 'Inactive' },
            ]}
          />
          {filtered && (
            <button type="button" className="link-btn text-[11px]" onClick={() => setValues({ search: '', category: '', serviceId: '', state: '' })}>
              Clear filters
            </button>
          )}
        </div>

        <div className="grid gap-[14px] xl:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)]">
          <Panel
            title="Runbook library"
            subtitle={runbooks.data ? `${list.length} runbook${list.length === 1 ? '' : 's'} · approval-gated runbooks need Incident Manager sign-off before they run` : undefined}
          >
            {runbooks.isLoading && <LoadingState rows={5} label="Loading runbooks…" />}
            {runbooks.isError && <ErrorState error={runbooks.error} onRetry={() => void runbooks.refetch()} />}
            {runbooks.data && list.length === 0 && (
              <EmptyState
                title={filtered ? 'No runbooks match these filters' : 'No runbooks yet'}
                description={filtered ? 'Try other keywords or clear the filters.' : canEdit ? 'Create the first runbook.' : undefined}
                icon={<ClipboardList size={20} />}
              />
            )}
            {list.length > 0 && (
              <ul className="rb-list" aria-label="Runbooks">
                {list.map((runbook) => (
                  <li key={runbook.id}>
                    <RunbookRow runbook={runbook} />
                  </li>
                ))}
              </ul>
            )}
          </Panel>
          <ExecutionHistory title="Recent executions" showRunbook pageSize={6} />
        </div>
      </div>
      {creating && <RunbookEditor runbook={null} onClose={() => setCreating(false)} onSaved={(saved) => navigate(`/runbooks/${saved.id}`)} />}
    </>
  );
}
