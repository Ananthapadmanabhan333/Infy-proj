import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle, CalendarClock, Info, Megaphone, OctagonAlert, Pencil, Plus, Trash2, Wrench, type LucideIcon } from 'lucide-react';
import { useAnnouncements, useDeleteAnnouncement, useSaveAnnouncement } from '../api/announcements';
import { useServices } from '../api/services';
import { Badge, DemoBadge } from '../components/Badge';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { CheckboxField, idOrNull, SelectField, TextareaField, TextField } from '../components/FormField';
import { Modal } from '../components/Modal';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { Tabs } from '../components/Tabs';
import { useToast } from '../components/Toast';
import { useAuth } from '../hooks/useAuth';
import { useTabParam } from '../hooks/useUrlState';
import { applyFieldErrors, errorMessage } from '../lib/api';
import { ADMIN_OR_MANAGER, ANNOUNCEMENT_SEVERITIES } from '../lib/constants';
import { formatDateTime, formatRelative, isoToLocalInput, localInputToIso, titleCase } from '../lib/format';
import { announcementSchema, type AnnouncementValues } from '../lib/schemas';
import type { AnnouncementDto } from '../lib/types';
import '../components/admin/admin.css';

type Severity = AnnouncementDto['severity'];

const SEVERITY_ICONS: Record<Severity, LucideIcon> = { INFO: Info, MAINTENANCE: Wrench, WARNING: AlertTriangle, CRITICAL: OctagonAlert };
const SEVERITY_TILE: Record<Severity, string> = { INFO: '', MAINTENANCE: 'neutral', WARNING: 'warning', CRITICAL: 'critical' };

/** Scheduling state relative to now. */
export function announcementState(announcement: Pick<AnnouncementDto, 'startsAt' | 'endsAt' | 'published'>, now: number): 'draft' | 'scheduled' | 'ended' | 'live' {
  if (!announcement.published) return 'draft';
  if (announcement.endsAt && new Date(announcement.endsAt).getTime() < now) return 'ended';
  if (announcement.startsAt && new Date(announcement.startsAt).getTime() > now) return 'scheduled';
  return 'live';
}

function AnnouncementEditor({ announcement, onClose }: { announcement: AnnouncementDto | null; onClose: () => void }) {
  const save = useSaveAnnouncement();
  const services = useServices();
  const toast = useToast();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<AnnouncementValues>({
    resolver: zodResolver(announcementSchema),
    defaultValues: {
      title: announcement?.title ?? '',
      body: announcement?.body ?? '',
      severity: announcement?.severity ?? 'INFO',
      serviceId: announcement?.serviceId ? String(announcement.serviceId) : '',
      startsAt: isoToLocalInput(announcement?.startsAt),
      endsAt: isoToLocalInput(announcement?.endsAt),
      published: announcement?.published ?? true,
    },
  });

  const onSubmit = handleSubmit((values) =>
    save.mutate(
      {
        id: announcement?.id,
        body: {
          title: values.title.trim(),
          body: values.body.trim(),
          severity: values.severity,
          serviceId: idOrNull(values.serviceId),
          startsAt: localInputToIso(values.startsAt),
          endsAt: localInputToIso(values.endsAt),
          published: values.published,
        },
      },
      {
        onSuccess: () => {
          toast.success(announcement ? 'Announcement updated' : 'Announcement created');
          onClose();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    ),
  );

  return (
    <Modal open onClose={onClose} title={announcement ? 'Edit announcement' : 'New announcement'} eyebrow="ANNOUNCEMENTS" size="lg" dismissible={!save.isPending}>
      <form onSubmit={onSubmit} noValidate>
        <TextField label="Title" required error={errors.title?.message} {...register('title')} />
        <TextareaField label="Message" required error={errors.body?.message} {...register('body')} />
        <div className="form-row">
          <SelectField label="Severity" error={errors.severity?.message} {...register('severity')}>
            {ANNOUNCEMENT_SEVERITIES.map((severity) => (
              <option key={severity} value={severity}>
                {titleCase(severity)}
              </option>
            ))}
          </SelectField>
          <SelectField label="Affected service" error={errors.serviceId?.message} {...register('serviceId')}>
            <option value="">All services</option>
            {services.data?.map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </SelectField>
        </div>
        <div className="form-row">
          <TextField label="Starts" type="datetime-local" hint="Leave empty to start immediately" error={errors.startsAt?.message} {...register('startsAt')} />
          <TextField label="Ends" type="datetime-local" hint="Leave empty for no end" error={errors.endsAt?.message} {...register('endsAt')} />
        </div>
        <CheckboxField label="Published (visible to everyone)" {...register('published')} />
        {save.isError && !Object.keys(errors).length && <p className="field-error">{errorMessage(save.error)}</p>}
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="gold-btn" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save announcement'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function AnnouncementCard({ announcement, now, canEdit, onEdit, onDelete }: { announcement: AnnouncementDto; now: number; canEdit: boolean; onEdit: () => void; onDelete: () => void }) {
  const state = announcementState(announcement, now);
  const Icon = SEVERITY_ICONS[announcement.severity] ?? Megaphone;
  return (
    <article className={`panel announcement ${announcement.severity.toLowerCase()} ${state === 'ended' ? 'opacity-70' : ''}`} aria-label={announcement.title}>
      <div className="flex items-start gap-3">
        <span className={`icon-tile ${SEVERITY_TILE[announcement.severity] ?? ''}`} aria-hidden="true">
          <Icon size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-start justify-between gap-2">
            <h3 className="m-0 font-heading text-[14px] text-ivory">{announcement.title}</h3>
            {canEdit && (
              <div className="flex gap-1">
                <button type="button" className="icon-btn sm" aria-label={`Edit ${announcement.title}`} onClick={onEdit}>
                  <Pencil size={14} />
                </button>
                <button type="button" className="icon-btn sm" aria-label={`Delete ${announcement.title}`} onClick={onDelete}>
                  <Trash2 size={14} />
                </button>
              </div>
            )}
          </div>
          <div className="tag-row mt-1.5">
            <Badge value={announcement.severity} />
            {state === 'draft' && <Badge value="draft" label="Unpublished" />}
            {state === 'ended' && <Badge value="closed" label="Ended" />}
            {state === 'scheduled' && <Badge value="pending" label="Scheduled" />}
            {state === 'live' && <Badge value="active" label="Live" />}
            {announcement.serviceName && <Badge value="outline" label={announcement.serviceName} />}
            <DemoBadge demo={announcement.demo} />
          </div>
          <p className="description-text mt-2 whitespace-pre-line">{announcement.body}</p>
          <p className="mt-2 flex flex-wrap items-center gap-1.5 text-[10px] text-muted">
            <CalendarClock size={11} /> {announcement.startsAt ? formatDateTime(announcement.startsAt) : 'Immediately'} →{' '}
            {announcement.endsAt ? formatDateTime(announcement.endsAt) : 'until removed'}
            <span aria-hidden="true">·</span>
            <span title={formatDateTime(announcement.createdAt)}>Posted {formatRelative(announcement.createdAt, now)}</span>
            {announcement.authorName ? ` by ${announcement.authorName}` : ''}
          </p>
        </div>
      </div>
    </article>
  );
}

/** /announcements: operational notices for everyone; ADMIN_OR_MANAGER create, edit and delete. */
export default function AnnouncementsPage() {
  const { hasRole } = useAuth();
  const canEdit = hasRole(...ADMIN_OR_MANAGER);
  const [view, setView] = useTabParam<'active' | 'all'>(['active', 'all'], 'active');
  const active = useAnnouncements(true);
  const all = useAnnouncements(false);
  const announcements = view === 'active' ? active : all;
  const remove = useDeleteAnnouncement();
  const toast = useToast();
  const [editing, setEditing] = useState<AnnouncementDto | null | 'new'>(null);
  const [deleting, setDeleting] = useState<AnnouncementDto | null>(null);
  const now = Date.now();

  return (
    <>
      <PageHeader
        title="Announcements"
        subtitle="Operational notices and planned maintenance"
        actions={
          canEdit && (
            <button type="button" className="gold-btn" onClick={() => setEditing('new')}>
              <Plus size={16} /> New Announcement
            </button>
          )
        }
      />
      <div className="page-stack">
        <Tabs<'active' | 'all'>
          label="Announcement views"
          value={view}
          onChange={setView}
          tabs={[
            { id: 'active', label: 'Active', count: active.data?.length },
            { id: 'all', label: 'All (including past)', count: all.data?.length },
          ]}
        />
        {announcements.isLoading && <LoadingState variant="cards" rows={3} label="Loading announcements…" />}
        {announcements.isError && <ErrorState error={announcements.error} onRetry={() => void announcements.refetch()} />}
        {announcements.data && announcements.data.length === 0 && (
          <EmptyState
            title={view === 'active' ? 'No active announcements' : 'No announcements yet'}
            description={view === 'active' ? 'All services are running without planned notices.' : undefined}
            icon={<Megaphone size={20} />}
            action={
              canEdit ? (
                <button type="button" className="gold-btn compact" onClick={() => setEditing('new')}>
                  <Plus size={13} /> New announcement
                </button>
              ) : undefined
            }
          />
        )}
        {announcements.data && announcements.data.length > 0 && (
          <div className="grid gap-3">
            {announcements.data.map((announcement) => (
              <AnnouncementCard
                key={announcement.id}
                announcement={announcement}
                now={now}
                canEdit={canEdit}
                onEdit={() => setEditing(announcement)}
                onDelete={() => setDeleting(announcement)}
              />
            ))}
          </div>
        )}
      </div>
      {editing && <AnnouncementEditor announcement={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onClose={() => {
          remove.reset();
          setDeleting(null);
        }}
        title="Delete announcement?"
        message={`“${deleting?.title ?? ''}” will be removed for everyone.`}
        confirmLabel="Delete"
        tone="danger"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() =>
          deleting &&
          remove.mutate(deleting.id, {
            onSuccess: () => {
              toast.success('Announcement deleted');
              setDeleting(null);
            },
          })
        }
      />
    </>
  );
}
