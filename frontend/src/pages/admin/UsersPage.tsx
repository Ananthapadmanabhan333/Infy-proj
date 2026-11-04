import { useMemo, useState } from 'react';
import { Building2, KeyRound, Link2, Lock, Pencil, Plus, UserCheck, UserPlus, Users, UserX, X } from 'lucide-react';
import { useDepartments, useUpdateUser, useUsers } from '../../api/users';
import {
  AccountRequestsTab,
  InviteUserDrawer,
  IssuedLinkPanel,
  REQUEST_FILTER_KEYS,
  usePendingRequestCount,
  type IssuedLink,
} from '../../components/admin/AccountRequests';
import { DepartmentDrawer } from '../../components/admin/DepartmentDrawer';
import { canManageUser, isSelf } from '../../components/admin/userAccess';
import { CreateUserDrawer, EditUserDrawer, ResetPasswordModal, UserDetailDrawer, updateBodyFrom } from '../../components/admin/UserDrawers';
import { Avatar, UserChip } from '../../components/Avatar';
import { BooleanBadge, DemoBadge, RoleBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { DataTable, type Column } from '../../components/DataTable';
import { FilterSelect } from '../../components/FilterSelect';
import { KpiCard } from '../../components/KpiCard';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { useSearchInput, useTabParam, useUrlFilters } from '../../hooks/useUrlState';
import { errorMessage } from '../../lib/api';
import { DEFAULT_PAGE_SIZE, ROLES, roleLabel, USER_ADMINS } from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative } from '../../lib/format';
import type { DepartmentDto, UserDto } from '../../lib/types';
import '../../components/admin/admin.css';

type DirectoryTab = 'users' | 'departments' | 'access-requests';
/** `?tab=requests` is accepted as an alias of `access-requests` (notification links use the latter). */
type TabParam = DirectoryTab | 'requests';
const FILTER_KEYS = ['search', 'role', 'departmentId', 'active'] as const;
/** Filters cleared when switching tabs. */
const TAB_RESET_KEYS = [...FILTER_KEYS, ...REQUEST_FILTER_KEYS];
const ADMIN_TABS: TabParam[] = ['users', 'departments', 'access-requests', 'requests'];
const READER_TABS: TabParam[] = ['users', 'departments'];

type UserDialog =
  | { kind: 'create' }
  | { kind: 'view'; user: UserDto }
  | { kind: 'edit'; user: UserDto }
  | { kind: 'reset'; user: UserDto }
  | { kind: 'toggle'; user: UserDto };

function UsersTab({ admin, onCreate }: { admin: boolean; onCreate: () => void }) {
  const { user: me } = useAuth();
  const { values, setValues, page, setPage } = useUrlFilters(FILTER_KEYS);
  const [search, setSearch] = useSearchInput(values.search, (next) => setValues({ search: next }));
  const departments = useDepartments();
  const users = useUsers({
    search: values.search || undefined,
    role: values.role || undefined,
    departmentId: values.departmentId || undefined,
    active: values.active || undefined,
    page,
    size: DEFAULT_PAGE_SIZE,
  });
  const update = useUpdateUser();
  const toast = useToast();
  const [dialog, setDialog] = useState<UserDialog | null>(null);
  const hasFilters = FILTER_KEYS.some((key) => values[key]);
  const close = () => setDialog(null);

  const toggleActive = (user: UserDto) =>
    update.mutate(
      { id: user.id, body: updateBodyFrom(user, { active: !user.active }) },
      {
        onSuccess: () => {
          toast.success(user.active ? `${user.fullName} deactivated — they can no longer sign in` : `${user.fullName} reactivated`);
          close();
        },
      },
    );

  const columns: Column<UserDto>[] = [
    {
      key: 'user',
      header: 'User',
      render: (row) => (
        <span className="flex min-w-0 items-center gap-2.5">
          <Avatar name={row.fullName} size="sm" />
          <span className="min-w-0">
            <b className="block truncate text-ivory">
              {row.fullName} <DemoBadge demo={row.demo} />
            </b>
            <small className="block truncate text-[9px] text-muted">{row.email}</small>
          </span>
        </span>
      ),
    },
    { key: 'role', header: 'Role', render: (row) => <RoleBadge role={row.role} /> },
    { key: 'department', header: 'Department', render: (row) => row.departmentName ?? <span className="muted-text">—</span> },
    { key: 'title', header: 'Job title', render: (row) => row.jobTitle ?? <span className="muted-text">—</span> },
    { key: 'status', header: 'Status', render: (row) => <BooleanBadge value={row.active} /> },
    {
      key: 'login',
      header: 'Last login',
      render: (row) => (row.lastLoginAt ? <span title={formatDateTime(row.lastLoginAt)}>{formatRelative(row.lastLoginAt)}</span> : <span className="muted-text">Never</span>),
    },
  ];
  if (admin) {
    columns.push({
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      className: 'relative',
      render: (row) =>
        canManageUser(me, row) ? (
          <span className="flex justify-end gap-1" onClick={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}>
            <button type="button" className="icon-btn sm" title="Edit" aria-label={`Edit ${row.fullName}`} onClick={() => setDialog({ kind: 'edit', user: row })}>
              <Pencil size={14} />
            </button>
            <button type="button" className="icon-btn sm" title="Reset password" aria-label={`Reset password for ${row.fullName}`} onClick={() => setDialog({ kind: 'reset', user: row })}>
              <KeyRound size={14} />
            </button>
            {!isSelf(me, row) && (
              <button
                type="button"
                className="icon-btn sm"
                title={row.active ? 'Deactivate' : 'Activate'}
                aria-label={`${row.active ? 'Deactivate' : 'Activate'} ${row.fullName}`}
                onClick={() => setDialog({ kind: 'toggle', user: row })}
              >
                {row.active ? <UserX size={14} /> : <UserCheck size={14} />}
              </button>
            )}
          </span>
        ) : (
          <span className="flex justify-end text-muted" title="Only a System Admin can change System Admin accounts">
            <Lock size={13} aria-label="Read-only" />
          </span>
        ),
    });
  }

  return (
    <Panel title="Directory" subtitle={admin ? 'Accounts, roles and access. Click a user for details.' : 'Read-only directory'}>
      <div className="toolbar">
        <SearchInput value={search} onChange={setSearch} placeholder="Search by name or email…" label="Search users" />
        <FilterSelect label="Role" allLabel="All roles" value={values.role} onChange={(role) => setValues({ role })} options={ROLES} formatOption={roleLabel} />
        <FilterSelect
          label="Department"
          allLabel="All departments"
          value={values.departmentId}
          onChange={(departmentId) => setValues({ departmentId })}
          options={(departments.data ?? []).map((department) => ({ value: String(department.id), label: department.name }))}
        />
        <FilterSelect
          label="Status"
          allLabel="Any status"
          value={values.active}
          onChange={(active) => setValues({ active })}
          options={[
            { value: 'true', label: 'Active' },
            { value: 'false', label: 'Inactive' },
          ]}
        />
        {hasFilters && (
          <button type="button" className="subtle-btn compact" onClick={() => setValues({ search: '', role: '', departmentId: '', active: '' })}>
            <X size={13} /> Clear
          </button>
        )}
      </div>
      {users.isLoading && <LoadingState rows={8} label="Loading users…" />}
      {users.isError && !users.data && <ErrorState error={users.error} onRetry={() => void users.refetch()} />}
      {users.data && (
        <>
          <DataTable<UserDto>
            caption="Users"
            rows={users.data.content}
            rowKey={(row) => row.id}
            onRowClick={(row) => setDialog({ kind: 'view', user: row })}
            rowLabel={(row) => `${row.fullName}, ${roleLabel(row.role)}`}
            rowClassName={(row) => (row.active ? '' : 'opacity-70')}
            empty={
              hasFilters ? (
                'No users match these filters.'
              ) : (
                <EmptyState
                  title="No users yet"
                  icon={<Users size={20} />}
                  action={
                    admin ? (
                      <button type="button" className="gold-btn compact" onClick={onCreate}>
                        <UserPlus size={13} /> New user
                      </button>
                    ) : undefined
                  }
                />
              )
            }
            columns={columns}
          />
          <Pagination page={users.data.page} totalPages={users.data.totalPages} totalElements={users.data.totalElements} size={users.data.size} onPageChange={setPage} label="users" />
        </>
      )}
      {dialog?.kind === 'view' && (
        <UserDetailDrawer
          user={dialog.user}
          onClose={close}
          onEdit={() => setDialog({ kind: 'edit', user: dialog.user })}
          onResetPassword={() => setDialog({ kind: 'reset', user: dialog.user })}
          onToggleActive={() => setDialog({ kind: 'toggle', user: dialog.user })}
        />
      )}
      {dialog?.kind === 'edit' && <EditUserDrawer user={dialog.user} onClose={close} />}
      {dialog?.kind === 'reset' && <ResetPasswordModal user={dialog.user} onClose={close} />}
      <ConfirmDialog
        open={dialog?.kind === 'toggle'}
        onClose={() => {
          update.reset();
          close();
        }}
        title={dialog?.kind === 'toggle' ? `${dialog.user.active ? 'Deactivate' : 'Activate'} ${dialog.user.fullName}?` : ''}
        message={
          dialog?.kind === 'toggle' && dialog.user.active
            ? 'They are signed out at their next request and can no longer sign in. Their incidents and history are kept.'
            : 'They will be able to sign in again with their existing password.'
        }
        confirmLabel={dialog?.kind === 'toggle' && dialog.user.active ? 'Deactivate' : 'Activate'}
        tone={dialog?.kind === 'toggle' && dialog.user.active ? 'danger' : 'gold'}
        pending={update.isPending}
        error={update.isError ? errorMessage(update.error) : null}
        onConfirm={() => dialog?.kind === 'toggle' && toggleActive(dialog.user)}
      />
    </Panel>
  );
}

function DepartmentsTab({ admin, onCreate }: { admin: boolean; onCreate: () => void }) {
  const departments = useDepartments();
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState<DepartmentDto | null>(null);
  const rows = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const list = departments.data ?? [];
    if (!needle) return list;
    return list.filter((department) =>
      [department.code, department.name, department.managerName, department.email].some((value) => value?.toLowerCase().includes(needle)),
    );
  }, [departments.data, query]);

  return (
    <Panel title="Departments" subtitle="Support teams and business units that own services and queues">
      <div className="toolbar">
        <SearchInput value={query} onChange={setQuery} placeholder="Search departments…" label="Search departments" />
      </div>
      {departments.isLoading && <LoadingState rows={5} label="Loading departments…" />}
      {departments.isError && <ErrorState error={departments.error} onRetry={() => void departments.refetch()} />}
      {departments.data && (
        <DataTable<DepartmentDto>
          caption="Departments"
          rows={rows}
          rowKey={(row) => row.id}
          onRowClick={admin ? (row) => setEditing(row) : undefined}
          rowLabel={(row) => `Edit ${row.name}`}
          empty={
            query ? (
              'No departments match this search.'
            ) : (
              <EmptyState
                title="No departments yet"
                icon={<Building2 size={20} />}
                action={
                  admin ? (
                    <button type="button" className="gold-btn compact" onClick={onCreate}>
                      <Plus size={13} /> New department
                    </button>
                  ) : undefined
                }
              />
            )
          }
          columns={[
            {
              key: 'name',
              header: 'Department',
              className: 'wrap',
              render: (row) => (
                <span className="flex items-center gap-2.5">
                  <span className="icon-tile sm" aria-hidden="true">
                    <Building2 size={15} />
                  </span>
                  <span className="min-w-0">
                    <b className="block text-ivory">
                      {row.name} <DemoBadge demo={row.demo} />
                    </b>
                    <small className="block text-[9px] text-muted">
                      {row.code}
                      {row.description ? ` · ${row.description}` : ''}
                    </small>
                  </span>
                </span>
              ),
            },
            { key: 'manager', header: 'Manager', render: (row) => <UserChip name={row.managerName} emptyLabel="No manager" full /> },
            { key: 'email', header: 'Email', render: (row) => row.email ?? <span className="muted-text">—</span> },
            { key: 'members', header: 'Members', render: (row) => formatNumber(row.memberCount) },
            ...(admin
              ? [
                  {
                    key: 'edit',
                    header: <span className="sr-only">Edit</span>,
                    className: 'relative',
                    render: (row: DepartmentDto) => (
                      <button
                        type="button"
                        className="icon-btn sm"
                        aria-label={`Edit department ${row.name}`}
                        onClick={(event) => {
                          event.stopPropagation();
                          setEditing(row);
                        }}
                      >
                        <Pencil size={14} />
                      </button>
                    ),
                  },
                ]
              : []),
          ]}
        />
      )}
      {editing && <DepartmentDrawer department={editing} onClose={() => setEditing(null)} />}
    </Panel>
  );
}

/**
 * /users (§14): directory with Users and Departments tabs, plus Access requests (Release 2.1) for USER_ADMINS.
 * USER_ADMINS manage; managers and auditors read the directory only.
 */
export default function UsersPage() {
  const { hasRole } = useAuth();
  const admin = hasRole(...USER_ADMINS);
  const [tabParam, setTab] = useTabParam<TabParam>(admin ? ADMIN_TABS : READER_TABS, 'users', TAB_RESET_KEYS);
  const tab: DirectoryTab = tabParam === 'requests' ? 'access-requests' : tabParam;
  const [creating, setCreating] = useState<'users' | 'departments' | null>(null);
  const [inviting, setInviting] = useState(false);
  // The single-use link lives only here while the show-once panel is open.
  const [issued, setIssued] = useState<IssuedLink | null>(null);
  const pendingRequests = usePendingRequestCount(admin);
  const total = useUsers({ size: 1 });
  const active = useUsers({ active: 'true', size: 1 });
  const inactive = useUsers({ active: 'false', size: 1 });
  const departments = useDepartments();

  const createUser = () => setCreating('users');
  const createDepartment = () => setCreating('departments');

  return (
    <>
      <PageHeader
        title="Users"
        subtitle="Manage users, departments and access"
        actions={
          admin ? (
            tab === 'departments' ? (
              <button type="button" className="gold-btn" onClick={createDepartment}>
                <Plus size={16} /> New Department
              </button>
            ) : (
              <>
                <button type="button" className="subtle-btn" onClick={() => setInviting(true)}>
                  <Link2 size={16} aria-hidden="true" /> Invite user
                </button>
                <button type="button" className="gold-btn" onClick={createUser}>
                  <UserPlus size={16} /> New User
                </button>
              </>
            )
          ) : (
            <span className="chip">
              <Lock size={11} /> Read-only access
            </span>
          )
        }
      />
      <div className="page-stack">
        <Tabs<DirectoryTab>
          label="Directory views"
          value={tab}
          onChange={setTab}
          tabs={[
            { id: 'users', label: 'Users', count: total.data?.totalElements },
            { id: 'departments', label: 'Departments', count: departments.data?.length },
            { id: 'access-requests', label: 'Access requests', count: pendingRequests, hidden: !admin },
          ]}
        />
        <div className="kpi-grid">
          <KpiCard icon={<Users />} label="Total users" value={formatNumber(total.data?.totalElements)} loading={total.isLoading} to="/users" caption="All accounts" />
          <KpiCard
            icon={<UserCheck />}
            label="Active"
            tone="success"
            value={formatNumber(active.data?.totalElements)}
            loading={active.isLoading}
            to="/users?active=true"
            caption="Can sign in"
          />
          <KpiCard
            icon={<UserX />}
            label="Inactive"
            tone="neutral"
            value={formatNumber(inactive.data?.totalElements)}
            loading={inactive.isLoading}
            to="/users?active=false"
            caption="Deactivated accounts"
          />
          <KpiCard
            icon={<Building2 />}
            label="Departments"
            value={formatNumber(departments.data?.length)}
            loading={departments.isLoading}
            to="/users?tab=departments"
            caption="Teams and business units"
          />
        </div>
        {tab === 'users' && <UsersTab admin={admin} onCreate={createUser} />}
        {tab === 'departments' && <DepartmentsTab admin={admin} onCreate={createDepartment} />}
        {tab === 'access-requests' && admin && <AccountRequestsTab onIssued={setIssued} />}
      </div>
      {creating === 'users' && <CreateUserDrawer onClose={() => setCreating(null)} />}
      {creating === 'departments' && <DepartmentDrawer department={null} onClose={() => setCreating(null)} />}
      {inviting && (
        <InviteUserDrawer
          onClose={() => setInviting(false)}
          onIssued={(link) => {
            setInviting(false);
            setIssued(link);
          }}
        />
      )}
      {issued && <IssuedLinkPanel issued={issued} onClose={() => setIssued(null)} />}
    </>
  );
}
