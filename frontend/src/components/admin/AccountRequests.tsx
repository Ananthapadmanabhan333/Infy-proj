import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle, Check, Copy, Inbox, KeyRound, Link2, ShieldAlert, UserCheck, X } from 'lucide-react';
import {
  useAccountRequestCounts,
  useAccountRequests,
  useApproveAccountRequest,
  useCreateInvitation,
  useRejectAccountRequest,
} from '../../api/accounts';
import { useDepartments } from '../../api/users';
import { useAuth } from '../../hooks/useAuth';
import { useUrlFilters } from '../../hooks/useUrlState';
import { errorMessage, toApiError } from '../../lib/api';
import {
  approveAccessSchema,
  inviteUserSchema,
  rejectRequestSchema,
  type ApproveAccessValues,
  type InviteUserValues,
  type RejectRequestValues,
} from '../../lib/accountSchemas';
import {
  ACCOUNT_REQUEST_STATUS_LABELS,
  ACCOUNT_REQUEST_STATUSES,
  ACCOUNT_REQUEST_TYPE_LABELS,
  ACCOUNT_REQUEST_TYPES,
  type AccountRequestDto,
  type AccountRequestType,
  type IssuedLinkResponse,
} from '../../lib/accountTypes';
import { DEFAULT_PAGE_SIZE, ROLE_DESCRIPTIONS, USER_ADMINS } from '../../lib/constants';
import { formatDateTime, formatRelative } from '../../lib/format';
import type { Role } from '../../lib/types';
import { Badge, StatusBadge } from '../Badge';
import { DataTable, type Column } from '../DataTable';
import { Drawer } from '../Drawer';
import { FilterSelect } from '../FilterSelect';
import { idOrNull, SelectField, TextareaField, TextField, textOrNull } from '../FormField';
import { Modal } from '../Modal';
import { Pagination } from '../Pagination';
import { Panel } from '../Panel';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';
import { roleOptions } from './userAccess';
import { DepartmentOptions, RoleOptions } from './UserDrawers';

/** URL filters of the Access requests tab (`/users?tab=access-requests&type=…&status=…`). */
export const REQUEST_FILTER_KEYS = ['type', 'status'] as const;
/** `status` absent → pending queue; `status=ALL` → every status. */
const ALL_STATUSES = 'ALL';

export interface IssuedLink extends IssuedLinkResponse {
  kind: 'invite' | 'reset';
  /** Who the link is for ("Priya Raman <priya@…>"). */
  recipient: string;
}

/** Full URL of an issued link: the API returns a path, prefixed here with this app's origin. */
export function absoluteLink(link: string): string {
  if (/^https?:\/\//i.test(link)) return link;
  return `${window.location.origin}${link.startsWith('/') ? link : `/${link}`}`;
}

function ServerError({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <div className="callout red mt-2" role="alert">
      <AlertTriangle size={16} aria-hidden="true" />
      <div>{message}</div>
    </div>
  );
}

function defaultRole(roles: Role[]): Role {
  return roles.includes('EMPLOYEE') ? 'EMPLOYEE' : roles[roles.length - 1];
}

// ------------------------------------------------------------------ show-once link
/**
 * The raw link exists only in this component's state: it is never cached, stored or logged, and is gone once the
 * administrator closes the panel. Escape / backdrop clicks are disabled so it cannot be dismissed by accident.
 */
export function IssuedLinkPanel({ issued, onClose }: { issued: IssuedLink; onClose: () => void }) {
  const url = absoluteLink(issued.link);
  const fieldId = useId();
  const textRef = useRef<HTMLTextAreaElement>(null);
  const [copy, setCopy] = useState<'idle' | 'copied' | 'manual'>('idle');
  const invite = issued.kind === 'invite';

  const copyLink = async () => {
    try {
      if (!navigator.clipboard) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText(url);
      setCopy('copied');
    } catch {
      textRef.current?.focus();
      textRef.current?.select();
      setCopy('manual');
    }
  };

  return (
    <Modal
      open
      onClose={onClose}
      dismissible={false}
      size="lg"
      eyebrow="SHOWN ONCE"
      title={invite ? 'Invitation link ready' : 'Password reset link ready'}
      description={<>For {issued.recipient}</>}
      footer={
        <button type="button" className="gold-btn" onClick={onClose}>
          <Check size={14} aria-hidden="true" /> Done — I have handed it over
        </button>
      }
    >
      <div className="field">
        <div className="field-label">
          <label htmlFor={fieldId}>{invite ? 'Invitation link' : 'Password reset link'}</label>
        </div>
        <textarea
          ref={textRef}
          id={fieldId}
          className="control font-mono"
          rows={3}
          readOnly
          value={url}
          spellCheck={false}
          style={{ wordBreak: 'break-all', minHeight: 0 }}
          onFocus={(event) => event.currentTarget.select()}
        />
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <button type="button" className="subtle-btn" onClick={() => void copyLink()} data-autofocus>
          {copy === 'copied' ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />} {copy === 'copied' ? 'Copied' : 'Copy link'}
        </button>
        <span className="text-[11px] text-muted" role="status">
          {copy === 'copied' && 'Link copied to the clipboard.'}
          {copy === 'manual' && 'Copying is not available here — the link is selected, press Ctrl+C (⌘C) to copy it.'}
        </span>
      </div>
      <p className="mt-3 text-[12px] text-[#d6cab8]">
        Expires <b className="text-ivory">{formatDateTime(issued.expiresAt)}</b> ({formatRelative(issued.expiresAt)}). Single use.
      </p>
      <div className="callout mt-3" role="note">
        <ShieldAlert size={16} aria-hidden="true" />
        <div>
          <b>Shown only once.</b> SA stores only a hash of this link and cannot show it again; if it is lost, create a new one (that
          revokes this one). Hand it over through a trusted channel — in person, by phone or your organisation&apos;s secure messaging. Anyone
          holding it can {invite ? 'create this account' : "set this account's password"} until it expires. When e-mail is configured for this workspace, the link is also e-mailed to the recipient.
        </div>
      </div>
    </Modal>
  );
}

// ------------------------------------------------------------------ approve / reject
function RequestSummary({ request }: { request: AccountRequestDto }) {
  return (
    <div className="detail-grid tight mb-1">
      <div>
        <small>Requester</small>
        <b className="truncate">{request.fullName}</b>
      </div>
      <div>
        <small>E-mail</small>
        <b className="truncate" title={request.email}>
          {request.email}
        </b>
      </div>
      {request.type === 'ACCESS' && (
        <>
          <div>
            <small>Stated department</small>
            <b>{request.department ?? '—'}</b>
          </div>
          <div>
            <small>Stated job title</small>
            <b>{request.jobTitle ?? '—'}</b>
          </div>
        </>
      )}
    </div>
  );
}

function ApproveAccessDialog({ request, onClose, onIssued }: { request: AccountRequestDto; onClose: () => void; onIssued: (link: IssuedLink) => void }) {
  const { user: me } = useAuth();
  const roles = roleOptions(me);
  const departments = useDepartments();
  const approve = useApproveAccountRequest();
  const formId = useId();
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    control,
    getValues,
    setValue,
    formState: { errors },
  } = useForm<ApproveAccessValues>({
    resolver: zodResolver(approveAccessSchema),
    defaultValues: { role: defaultRole(roles), departmentId: '', jobTitle: request.jobTitle ?? '' },
  });
  const role = useWatch({ control, name: 'role' });

  // Pre-select the department the requester named, when it matches one of ours.
  useEffect(() => {
    const wanted = request.department?.trim().toLowerCase();
    if (!wanted || getValues('departmentId')) return;
    const match = departments.data?.find((department) => department.name.toLowerCase() === wanted || department.code.toLowerCase() === wanted);
    if (match) setValue('departmentId', String(match.id));
  }, [departments.data, request.department, getValues, setValue]);

  const onSubmit = handleSubmit((values) => {
    setServerError(null);
    approve.mutate(
      { id: request.id, body: { role: values.role, departmentId: idOrNull(values.departmentId) as number, jobTitle: textOrNull(values.jobTitle) } },
      {
        onSuccess: (result) => onIssued({ ...result, kind: 'invite', recipient: `${request.fullName} <${request.email}>` }),
        onError: (error) => setServerError(errorMessage(error)),
      },
    );
  });

  return (
    <Modal
      open
      onClose={onClose}
      dismissible={!approve.isPending}
      eyebrow="APPROVE ACCESS REQUEST"
      title={`Approve ${request.fullName}`}
      description="Creates a single-use invitation link (valid 7 days). The account is created when they open it and set a password."
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={approve.isPending}>
            Cancel
          </button>
          <button type="submit" form={formId} className="gold-btn" disabled={approve.isPending}>
            <UserCheck size={14} aria-hidden="true" /> {approve.isPending ? 'Approving…' : 'Approve and create link'}
          </button>
        </>
      }
    >
      <RequestSummary request={request} />
      <form id={formId} onSubmit={onSubmit} noValidate>
        <div className="form-row">
          <SelectField label="Role" required error={errors.role?.message} hint={role ? ROLE_DESCRIPTIONS[role] : undefined} {...register('role')}>
            <RoleOptions roles={roles} />
          </SelectField>
          <SelectField label="Department" required error={errors.departmentId?.message} {...register('departmentId')}>
            <DepartmentOptions emptyLabel="Choose a department" />
          </SelectField>
        </div>
        <TextField label="Job title" error={errors.jobTitle?.message} {...register('jobTitle')} />
        <ServerError message={serverError} />
      </form>
    </Modal>
  );
}

function ApproveResetDialog({ request, onClose, onIssued }: { request: AccountRequestDto; onClose: () => void; onIssued: (link: IssuedLink) => void }) {
  const approve = useApproveAccountRequest();
  return (
    <Modal
      open
      onClose={onClose}
      dismissible={!approve.isPending}
      eyebrow="PASSWORD RESET REQUEST"
      title={`Create a reset link for ${request.fullName}?`}
      description="A single-use link valid for 2 hours is created. Any older unused reset link for this account stops working."
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={approve.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="gold-btn"
            data-autofocus
            disabled={approve.isPending}
            onClick={() =>
              approve.mutate(
                { id: request.id, body: {} },
                { onSuccess: (result) => onIssued({ ...result, kind: 'reset', recipient: `${request.fullName} <${request.email}>` }) },
              )
            }
          >
            <KeyRound size={14} aria-hidden="true" /> {approve.isPending ? 'Creating…' : 'Create reset link'}
          </button>
        </>
      }
    >
      <RequestSummary request={request} />
      <p className="mt-3 text-[12px] leading-relaxed text-[#d6cab8]">
        Confirm the person&apos;s identity before handing over the link. Setting the new password also signs them out everywhere.
      </p>
      <ServerError message={approve.isError ? errorMessage(approve.error) : null} />
    </Modal>
  );
}

function RejectDialog({ request, onClose }: { request: AccountRequestDto; onClose: () => void }) {
  const reject = useRejectAccountRequest();
  const toast = useToast();
  const formId = useId();
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    control,
    formState: { errors },
  } = useForm<RejectRequestValues>({ resolver: zodResolver(rejectRequestSchema), defaultValues: { note: '' } });
  const note = useWatch({ control, name: 'note' }) ?? '';

  const onSubmit = handleSubmit((values) => {
    setServerError(null);
    reject.mutate(
      { id: request.id, body: { note: values.note } },
      {
        onSuccess: () => {
          toast.success(`${ACCOUNT_REQUEST_TYPE_LABELS[request.type]} request from ${request.fullName} rejected`);
          onClose();
        },
        onError: (error) => setServerError(errorMessage(error)),
      },
    );
  });

  return (
    <Modal
      open
      onClose={onClose}
      dismissible={!reject.isPending}
      eyebrow={request.type === 'ACCESS' ? 'ACCESS REQUEST' : 'PASSWORD RESET REQUEST'}
      title={`Reject the request from ${request.fullName}?`}
      description="The note is kept with the request and in the audit log. The requester is not notified automatically."
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={reject.isPending}>
            Cancel
          </button>
          <button type="submit" form={formId} className="danger-btn" disabled={reject.isPending}>
            <X size={14} aria-hidden="true" /> {reject.isPending ? 'Rejecting…' : 'Reject request'}
          </button>
        </>
      }
    >
      <RequestSummary request={request} />
      <form id={formId} onSubmit={onSubmit} noValidate>
        <TextareaField
          label="Decision note"
          required
          maxLength={500}
          hint={`${note.trim().length}/500 · required`}
          error={errors.note?.message}
          {...register('note')}
        />
        <ServerError message={serverError} />
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------ invite
export function InviteUserDrawer({ onClose, onIssued }: { onClose: () => void; onIssued: (link: IssuedLink) => void }) {
  const { user: me } = useAuth();
  const roles = roleOptions(me);
  const invite = useCreateInvitation();
  const formId = useId();
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    control,
    formState: { errors },
  } = useForm<InviteUserValues>({
    resolver: zodResolver(inviteUserSchema),
    defaultValues: { email: '', fullName: '', role: defaultRole(roles), departmentId: '', jobTitle: '' },
  });
  const role = useWatch({ control, name: 'role' });

  const onSubmit = handleSubmit((values) => {
    setServerError(null);
    invite.mutate(
      {
        email: values.email,
        fullName: textOrNull(values.fullName),
        role: values.role,
        departmentId: idOrNull(values.departmentId) as number,
        jobTitle: textOrNull(values.jobTitle),
      },
      {
        onSuccess: (result) => onIssued({ ...result, kind: 'invite', recipient: values.fullName ? `${values.fullName} <${values.email}>` : values.email }),
        onError: (error) => {
          const apiError = toApiError(error);
          setServerError(apiError.status === 409 ? 'This e-mail address already has an account. Use “Reset password” on their user record instead.' : apiError.message);
        },
      },
    );
  });

  return (
    <Drawer
      open
      onClose={() => !invite.isPending && onClose()}
      label="INVITE USER"
      title="Invite a user"
      subtitle="Creates a single-use invitation link (valid 7 days). The person sets their own name and password; no e-mail is sent."
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={invite.isPending}>
            Cancel
          </button>
          <button type="submit" form={formId} className="gold-btn" disabled={invite.isPending}>
            <Link2 size={14} aria-hidden="true" /> {invite.isPending ? 'Creating…' : 'Create invitation link'}
          </button>
        </>
      }
    >
      <form id={formId} onSubmit={onSubmit} noValidate>
        <TextField label="Email" type="email" required autoComplete="off" error={errors.email?.message} {...register('email')} />
        <TextField label="Full name" autoComplete="off" hint="Optional — the invitee can change it." error={errors.fullName?.message} {...register('fullName')} />
        <div className="form-row">
          <SelectField label="Role" required error={errors.role?.message} hint={role ? ROLE_DESCRIPTIONS[role] : undefined} {...register('role')}>
            <RoleOptions roles={roles} />
          </SelectField>
          <SelectField label="Department" required error={errors.departmentId?.message} {...register('departmentId')}>
            <DepartmentOptions emptyLabel="Choose a department" />
          </SelectField>
        </div>
        <TextField label="Job title" error={errors.jobTitle?.message} {...register('jobTitle')} />
        <ServerError message={serverError} />
      </form>
    </Drawer>
  );
}

// ------------------------------------------------------------------ list
type RequestDialog = { kind: 'view' | 'approve' | 'reject'; request: AccountRequestDto };

function TypeBadge({ type }: { type: AccountRequestType }) {
  return <Badge value={type} label={ACCOUNT_REQUEST_TYPE_LABELS[type] ?? type} tone={type === 'ACCESS' ? 'champagne' : 'warning'} />;
}

function Detail({ label, children, wide }: { label: string; children: ReactNode; wide?: boolean }) {
  return (
    <div style={wide ? { gridColumn: '1 / -1' } : undefined}>
      <small>{label}</small>
      <b className="whitespace-pre-wrap break-words">{children}</b>
    </div>
  );
}

function RequestDetailDrawer({
  request,
  admin,
  onClose,
  onApprove,
  onReject,
}: {
  request: AccountRequestDto;
  admin: boolean;
  onClose: () => void;
  onApprove: () => void;
  onReject: () => void;
}) {
  const actionable = admin && request.status === 'PENDING';
  return (
    <Drawer
      open
      onClose={onClose}
      label={request.type === 'ACCESS' ? 'ACCESS REQUEST' : 'PASSWORD RESET REQUEST'}
      title={request.fullName}
      subtitle={request.email}
      footer={
        actionable ? (
          <>
            <button type="button" className="subtle-btn compact" onClick={onReject}>
              <X size={13} aria-hidden="true" /> Reject
            </button>
            <button type="button" className="gold-btn compact" onClick={onApprove}>
              <UserCheck size={13} aria-hidden="true" /> {request.type === 'ACCESS' ? 'Approve' : 'Create reset link'}
            </button>
          </>
        ) : undefined
      }
    >
      <div className="tag-row mt-1">
        <TypeBadge type={request.type} />
        <StatusBadge status={request.status} />
      </div>
      <div className="detail-grid">
        <Detail label="Requested">{formatDateTime(request.createdAt)}</Detail>
        <Detail label="Department">{request.department ?? '—'}</Detail>
        <Detail label="Job title">{request.jobTitle ?? '—'}</Detail>
        <Detail label="Decided">{request.decidedAt ? `${formatDateTime(request.decidedAt)}${request.decidedByName ? ` · ${request.decidedByName}` : ''}` : '—'}</Detail>
        {request.reason && (
          <Detail label="Reason" wide>
            {request.reason}
          </Detail>
        )}
        {request.decisionNote && (
          <Detail label="Decision note" wide>
            {request.decisionNote}
          </Detail>
        )}
      </div>
    </Drawer>
  );
}

/** Users → Access requests (spec §2, §5): review queue for access and password-reset requests. USER_ADMINS only. */
export function AccountRequestsTab({ onIssued }: { onIssued: (link: IssuedLink) => void }) {
  const { hasRole } = useAuth();
  const admin = hasRole(...USER_ADMINS);
  const { values, setValues, page, setPage } = useUrlFilters(REQUEST_FILTER_KEYS);
  const statusFilter = values.status === ALL_STATUSES ? '' : values.status || 'PENDING';
  const requests = useAccountRequests(
    { type: values.type || undefined, status: statusFilter || undefined, page, size: DEFAULT_PAGE_SIZE },
    admin,
  );
  const counts = useAccountRequestCounts(admin);
  const [dialog, setDialog] = useState<RequestDialog | null>(null);
  const close = () => setDialog(null);
  const issue = (link: IssuedLink) => {
    close();
    onIssued(link);
  };
  const filtered = Boolean(values.type) || statusFilter !== 'PENDING';

  const actions = (row: AccountRequestDto) =>
    admin && row.status === 'PENDING' ? (
      <span className="flex justify-end gap-1" onClick={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}>
        <button type="button" className="gold-btn compact" aria-label={`Approve request from ${row.fullName}`} onClick={() => setDialog({ kind: 'approve', request: row })}>
          <Check size={13} aria-hidden="true" /> Approve
        </button>
        <button type="button" className="subtle-btn compact" aria-label={`Reject request from ${row.fullName}`} onClick={() => setDialog({ kind: 'reject', request: row })}>
          <X size={13} aria-hidden="true" /> Reject
        </button>
      </span>
    ) : null;

  const columns: Column<AccountRequestDto>[] = [
    { key: 'requester', header: 'Requester', render: (row) => <b className="text-ivory">{row.fullName}</b> },
    { key: 'email', header: 'E-mail', render: (row) => row.email },
    { key: 'type', header: 'Type', render: (row) => <TypeBadge type={row.type} /> },
    {
      key: 'department',
      header: 'Department / job title',
      render: (row) =>
        row.department || row.jobTitle ? (
          <span className="block min-w-0">
            <span className="block truncate">{row.department ?? '—'}</span>
            <small className="block truncate text-[9px] text-muted">{row.jobTitle ?? '—'}</small>
          </span>
        ) : (
          <span className="muted-text">—</span>
        ),
    },
    {
      key: 'reason',
      header: 'Reason',
      className: 'wrap',
      render: (row) =>
        row.reason ? (
          <span className="line-clamp-2 max-w-[320px]" title={row.reason}>
            {row.reason}
          </span>
        ) : (
          <span className="muted-text">—</span>
        ),
    },
    {
      key: 'requested',
      header: 'Requested at',
      render: (row) => <span title={formatDateTime(row.createdAt)}>{formatRelative(row.createdAt)}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <span className="block">
          <StatusBadge status={row.status} />
          {row.decidedByName && <small className="mt-1 block text-[9px] text-muted">by {row.decidedByName}</small>}
        </span>
      ),
    },
  ];
  // `relative` keeps the visually hidden header inside the table's scroll container (no page-level overflow on phones).
  if (admin) columns.push({ key: 'actions', header: <span className="sr-only">Actions</span>, className: 'relative', render: actions });

  const pendingAccess = counts.data?.pendingAccess ?? 0;
  const pendingReset = counts.data?.pendingReset ?? 0;

  return (
    <Panel
      title="Access requests"
      subtitle={
        counts.data
          ? `${pendingAccess} access and ${pendingReset} password reset ${pendingAccess + pendingReset === 1 ? 'request' : 'requests'} waiting for review. Links are shown once — hand them over through a trusted channel.`
          : 'Requests from the public sign-up and password recovery pages. Links are shown once.'
      }
    >
      <div className="toolbar">
        <FilterSelect
          label="Type"
          allLabel="All types"
          value={values.type}
          onChange={(type) => setValues({ type })}
          options={ACCOUNT_REQUEST_TYPES.map((type) => ({ value: type, label: ACCOUNT_REQUEST_TYPE_LABELS[type] }))}
        />
        <FilterSelect
          label="Status"
          allLabel="All statuses"
          value={statusFilter}
          onChange={(status) => setValues({ status: status === '' ? ALL_STATUSES : status === 'PENDING' ? '' : status })}
          options={ACCOUNT_REQUEST_STATUSES.map((status) => ({ value: status, label: ACCOUNT_REQUEST_STATUS_LABELS[status] }))}
        />
        {filtered && (
          <button type="button" className="subtle-btn compact" onClick={() => setValues({ type: '', status: '' })}>
            <X size={13} aria-hidden="true" /> Pending queue
          </button>
        )}
      </div>
      {requests.isLoading && <LoadingState rows={5} label="Loading access requests…" />}
      {requests.isError && !requests.data && <ErrorState error={requests.error} onRetry={() => void requests.refetch()} />}
      {requests.data && (
        <>
          <DataTable<AccountRequestDto>
            caption="Access requests"
            rows={requests.data.content}
            rowKey={(row) => row.id}
            onRowClick={(row) => setDialog({ kind: 'view', request: row })}
            rowLabel={(row) => `${ACCOUNT_REQUEST_TYPE_LABELS[row.type]} request from ${row.fullName}, ${ACCOUNT_REQUEST_STATUS_LABELS[row.status] ?? row.status}`}
            empty={
              filtered ? (
                'No requests match these filters.'
              ) : (
                <EmptyState
                  title="No pending requests"
                  description="New access and password-reset requests appear here and are announced in your notifications."
                  icon={<Inbox size={20} />}
                />
              )
            }
            columns={columns}
          />
          <Pagination
            page={requests.data.page}
            totalPages={requests.data.totalPages}
            totalElements={requests.data.totalElements}
            size={requests.data.size}
            onPageChange={setPage}
            label="requests"
          />
        </>
      )}
      {dialog?.kind === 'view' && (
        <RequestDetailDrawer
          request={dialog.request}
          admin={admin}
          onClose={close}
          onApprove={() => setDialog({ kind: 'approve', request: dialog.request })}
          onReject={() => setDialog({ kind: 'reject', request: dialog.request })}
        />
      )}
      {dialog?.kind === 'approve' &&
        (dialog.request.type === 'ACCESS' ? (
          <ApproveAccessDialog request={dialog.request} onClose={close} onIssued={issue} />
        ) : (
          <ApproveResetDialog request={dialog.request} onClose={close} onIssued={issue} />
        ))}
      {dialog?.kind === 'reject' && <RejectDialog request={dialog.request} onClose={close} />}
    </Panel>
  );
}

/** Pending total for the tab badge (0 while loading or for non-admins). */
export function usePendingRequestCount(enabled: boolean): number | undefined {
  const counts = useAccountRequestCounts(enabled);
  if (!enabled || !counts.data) return undefined;
  return counts.data.pendingAccess + counts.data.pendingReset;
}

