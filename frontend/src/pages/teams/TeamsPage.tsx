import { useMemo, useState } from 'react';
import { ClipboardList, Inbox, Plus, Siren, UsersRound } from 'lucide-react';
import { useTeams } from '../../api/teams';
import { useDepartments } from '../../api/users';
import { UserChip } from '../../components/Avatar';
import { Badge, BooleanBadge, DemoBadge } from '../../components/Badge';
import { DataTable } from '../../components/DataTable';
import { FilterSelect } from '../../components/FilterSelect';
import { KpiCard } from '../../components/KpiCard';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { FILTER_CLASS, FILTER_ROW_CLASS } from '../../components/servicedesk/requestModel';
import { useAuth } from '../../hooks/useAuth';
import { useSearchInput, useUrlFilters } from '../../hooks/useUrlState';
import { formatNumber } from '../../lib/format';
import type { TeamDto } from '../../lib/types';
import { canManageDepartmentTeams, manageableDepartments } from './teamAccess';
import { TeamDrawer } from './TeamDrawer';
import { TeamEditor } from './TeamEditor';

const KEYS = ['search', 'department', 'active', 'team'] as const;
const ACTIVE_OPTIONS = [
  { value: 'true', label: 'Active' },
  { value: 'false', label: 'Inactive' },
];

/** /teams (ADMINISTRATION, readers): teams with their queues; team admins create, edit and maintain members. */
export default function TeamsPage() {
  const { user } = useAuth();
  const { values, setValues } = useUrlFilters(KEYS, { active: 'true' });
  const [searchInput, setSearchInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const departments = useDepartments();
  const teams = useTeams({ search: values.search || undefined, departmentId: values.department || undefined, active: values.active === 'all' ? undefined : values.active || undefined });
  const [editing, setEditing] = useState<TeamDto | 'new' | null>(null);
  const canCreate = manageableDepartments(user, departments.data).length > 0;
  const openTeamId = values.team ? Number(values.team) : null;

  const all = teams.data ?? [];
  const totals = useMemo(
    () =>
      all.reduce(
        (sum, team) => ({
          members: sum.members + team.memberCount,
          incidents: sum.incidents + team.openIncidents,
          unassigned: sum.unassigned + team.unassignedIncidents,
          requests: sum.requests + team.openRequests,
        }),
        { members: 0, incidents: 0, unassigned: 0, requests: 0 },
      ),
    [all],
  );
  const filtering = Boolean(values.search || values.department || values.active !== 'true');
  const manage = (team: Pick<TeamDto, 'departmentId'>) => canManageDepartmentTeams(user, team.departmentId, departments.data);

  return (
    <>
      <PageHeader
        title="Teams"
        subtitle="Teams, members and their queues"
        actions={
          canCreate ? (
            <button type="button" className="gold-btn" onClick={() => setEditing('new')}>
              <Plus size={16} aria-hidden="true" /> New team
            </button>
          ) : undefined
        }
      />
      <div className="page-stack">
        {teams.isLoading && <LoadingState variant="kpis" label="Loading teams…" />}
        {teams.data && (
          <div className="kpi-grid">
            <KpiCard icon={<UsersRound />} label="Members" value={formatNumber(totals.members)} caption={`${formatNumber(all.length)} teams in this view`} />
            <KpiCard icon={<Siren />} label="Open incidents" value={formatNumber(totals.incidents)} tone={totals.incidents ? 'warning' : 'success'} />
            <KpiCard
              icon={<Inbox />}
              label="Unassigned (team queues)"
              value={formatNumber(totals.unassigned)}
              tone={totals.unassigned ? 'critical' : 'success'}
              caption={totals.unassigned ? 'Waiting for a team member' : 'Every queue is empty'}
            />
            <KpiCard icon={<ClipboardList />} label="Open requests" value={formatNumber(totals.requests)} tone="gold" />
          </div>
        )}

        <Panel aria-label="Team list">
          <div className="toolbar">
            <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search teams by name or code…" label="Search teams" />
            <div className={FILTER_ROW_CLASS}>
              <FilterSelect
                className={FILTER_CLASS}
                label="Department"
                value={values.department}
                onChange={(department) => setValues({ department })}
                options={(departments.data ?? []).map((department) => ({ value: String(department.id), label: department.name }))}
                disabled={departments.isError}
              />
              <FilterSelect
                className={FILTER_CLASS}
                label="Status"
                allLabel="All teams"
                value={values.active === 'all' ? '' : values.active}
                onChange={(active) => setValues({ active: active || 'all' })}
                options={ACTIVE_OPTIONS}
              />
            </div>
          </div>
          {teams.isLoading && <LoadingState rows={6} label="Loading teams…" />}
          {teams.isError && <ErrorState error={teams.error} onRetry={() => void teams.refetch()} />}
          {teams.data && all.length === 0 && (
            <EmptyState
              title={filtering ? 'No teams match these filters' : 'No teams yet'}
              description={filtering ? 'Try another search or clear the filters.' : canCreate ? 'Create the first team to route work to it.' : 'A team admin creates teams.'}
              icon={<UsersRound size={20} />}
              action={
                filtering ? (
                  <button type="button" className="subtle-btn compact" onClick={() => setValues({ search: '', department: '', active: '' })}>
                    Clear filters
                  </button>
                ) : undefined
              }
            />
          )}
          {all.length > 0 && (
            <DataTable<TeamDto>
              caption="Teams"
              rows={all}
              rowKey={(team) => team.id}
              rowLabel={(team) => team.name}
              onRowClick={(team) => setValues({ team: String(team.id) })}
              columns={[
                {
                  key: 'name',
                  header: 'Team',
                  className: 'summary-cell',
                  render: (team) => (
                    <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
                      <span className="truncate font-semibold">{team.name}</span>
                      <span className="text-muted">{team.code}</span>
                      {team.departmentDefault && <Badge value="default" tone="gold" label="Default" />}
                      <DemoBadge demo={team.demo} />
                    </span>
                  ),
                },
                { key: 'department', header: 'Department', render: (team) => team.departmentName ?? '—' },
                { key: 'lead', header: 'Lead', render: (team) => <UserChip name={team.leadName} emptyLabel="No lead" /> },
                { key: 'members', header: 'Members', render: (team) => formatNumber(team.memberCount) },
                { key: 'incidents', header: 'Open incidents', render: (team) => formatNumber(team.openIncidents) },
                {
                  key: 'queue',
                  header: 'Unassigned',
                  render: (team) => <span className={team.unassignedIncidents ? 'warning-text font-semibold' : undefined}>{formatNumber(team.unassignedIncidents)}</span>,
                },
                { key: 'requests', header: 'Open requests', render: (team) => formatNumber(team.openRequests) },
                { key: 'status', header: 'Status', render: (team) => <BooleanBadge value={team.active} /> },
              ]}
            />
          )}
        </Panel>
      </div>

      {openTeamId !== null && openTeamId > 0 && (
        <TeamDrawer
          teamId={openTeamId}
          canManage={manage}
          onClose={() => setValues({ team: '' })}
          onEdit={(team) => setEditing(team)}
        />
      )}
      {editing !== null && (
        <TeamEditor
          team={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(team) => setValues({ team: String(team.id) })}
        />
      )}
    </>
  );
}
