import { useState, type FormEvent } from 'react';
import { AlertTriangle, CheckCircle2, Clock, Mail, MailCheck, MailX, Paperclip, RefreshCw, Send, ShieldAlert } from 'lucide-react';
import { useEmailOutbox, useEmailStatus, useSendTestEmail } from '../../api/email';
import { Badge, StatusBadge } from '../../components/Badge';
import { DataTable } from '../../components/DataTable';
import { FilterSelect } from '../../components/FilterSelect';
import { KpiCard } from '../../components/KpiCard';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { SETTINGS } from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative, titleCase } from '../../lib/format';
import { emailSchema } from '../../lib/schemas';
import type { OutboxItem, OutboxStatus } from '../../lib/types';

const OUTBOX_STATUSES: OutboxStatus[] = ['PENDING', 'SENT', 'FAILED', 'SKIPPED'];
const PAGE_SIZE = 20;

function TestEmailForm({ configured }: { configured: boolean }) {
  const { user } = useAuth();
  const toast = useToast();
  const send = useSendTestEmail();
  const [to, setTo] = useState(user?.email ?? '');
  const [fieldError, setFieldError] = useState<string | null>(null);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const parsed = emailSchema.safeParse(to);
    if (!parsed.success) {
      setFieldError(parsed.error.issues[0]?.message ?? 'Enter a valid email address');
      return;
    }
    setFieldError(null);
    send.mutate(parsed.data, {
      onSuccess: (result) => toast.success(`Test e-mail queued (${titleCase(result?.status ?? 'PENDING')}) — it appears in the outbox below`),
    });
  };

  const apiError = send.isError ? toApiError(send.error) : null;
  return (
    <Panel title="Send a test e-mail" subtitle="System Admin only. Queued through the outbox like every other message.">
      <form onSubmit={submit} noValidate className="flex flex-wrap items-start gap-2" aria-label="Send a test e-mail">
        <div className="field min-w-[220px] flex-1 !mb-0">
          <label htmlFor="test-email-to" className="sr-only">
            Recipient
          </label>
          <input
            id="test-email-to"
            className="control"
            type="email"
            placeholder="name@company.com"
            value={to}
            onChange={(event) => setTo(event.target.value)}
            aria-invalid={fieldError ? true : undefined}
            aria-describedby={fieldError ? 'test-email-error' : undefined}
          />
          {fieldError && (
            <span className="field-error" id="test-email-error" role="alert">
              {fieldError}
            </span>
          )}
        </div>
        <button type="submit" className="gold-btn" disabled={send.isPending || !configured} title={configured ? undefined : 'SMTP is not configured'}>
          <Send size={14} aria-hidden="true" /> {send.isPending ? 'Queuing…' : 'Send test'}
        </button>
      </form>
      {!configured && <p className="m-0 mt-2 text-[11px] text-muted">A test e-mail needs SMTP; the server answers 409 while it is not configured.</p>}
      {apiError && (
        <p className="field-error mt-2" role="alert">
          {apiError.status === 409 ? 'E-mail is not configured on the server (MAIL_HOST is not set), so no test e-mail can be sent.' : errorMessage(send.error)}
        </p>
      )}
    </Panel>
  );
}

/** Settings → E-mail (admins): delivery status, outbox and (System Admin) a test message. Credentials are never shown. */
export function EmailTab() {
  const { hasRole } = useAuth();
  const status = useEmailStatus();
  const [filter, setFilter] = useState('');
  const [page, setPage] = useState(0);
  const outbox = useEmailOutbox({ status: filter, page, size: PAGE_SIZE });

  if (status.isLoading) return <LoadingState variant="kpis" label="Loading e-mail status…" />;
  if (status.isError && !status.data) return <ErrorState error={status.error} onRetry={() => void status.refetch()} />;
  const data = status.data;
  if (!data) return null;

  return (
    <>
      {data.configured ? (
        <div className="callout green" role="status">
          <CheckCircle2 size={16} aria-hidden="true" />
          <div>
            <b>SMTP is configured.</b> Messages are sent from {data.fromAddress ?? 'the configured address'}
            {data.lastRunAt ? ` · delivery last ran ${formatRelative(data.lastRunAt)}` : ''}.
          </div>
        </div>
      ) : (
        <div className="callout" role="status">
          <ShieldAlert size={16} aria-hidden="true" />
          <div>
            <b>E-mail is not configured.</b> Without <code>MAIL_HOST</code> every message is stored as <i>Skipped</i> and never reported as sent. Invitation and reset links are still shown
            once to the administrator. Set <code>MAIL_HOST</code>, <code>MAIL_PORT</code>, <code>MAIL_USERNAME</code>, <code>MAIL_PASSWORD</code> and <code>MAIL_FROM</code> on the server to enable delivery.
          </div>
        </div>
      )}
      <div className="kpi-grid">
        <KpiCard icon={<Mail />} label="Status" value={data.configured ? 'Configured' : 'Not configured'} tone={data.configured ? 'success' : 'warning'} caption={data.fromAddress ?? 'No sender address'} />
        <KpiCard icon={<Clock />} label="Pending" value={formatNumber(data.pending)} tone={data.pending ? 'gold' : 'neutral'} caption="Waiting for delivery or retry" />
        <KpiCard
          icon={<MailCheck />}
          label="Sent · 24 h"
          value={formatNumber(data.sentLast24h)}
          tone="success"
          caption={data.lastSentAt ? `Last sent ${formatRelative(data.lastSentAt)}` : 'Nothing sent yet'}
        />
        <KpiCard
          icon={<MailX />}
          label="Failed · 24 h"
          value={formatNumber(data.failedLast24h)}
          tone={data.failedLast24h ? 'critical' : 'success'}
          caption={`${formatNumber(data.skippedLast24h)} skipped (no SMTP)`}
        />
      </div>

      {hasRole(...SETTINGS) && <TestEmailForm configured={data.configured} />}

      <Panel
        title="Outbox"
        subtitle="Message bodies are not shown; one-time links are removed once a message is final. Finished rows are purged after 30 days."
        actions={
          <span className="flex items-center gap-2">
            <FilterSelect
              label="Status"
              allLabel="All statuses"
              value={filter}
              onChange={(value) => {
                setFilter(value);
                setPage(0);
              }}
              options={OUTBOX_STATUSES}
              formatOption={(value) => titleCase(value)}
            />
            <button type="button" className="icon-btn sm" aria-label="Refresh outbox" onClick={() => void outbox.refetch()} disabled={outbox.isFetching}>
              <RefreshCw size={13} className={outbox.isFetching ? 'animate-spin' : ''} />
            </button>
          </span>
        }
      >
        {outbox.isLoading && <LoadingState rows={5} label="Loading outbox…" />}
        {outbox.isError && !outbox.data && <ErrorState error={outbox.error} onRetry={() => void outbox.refetch()} compact />}
        {outbox.data && (
          <>
            <DataTable<OutboxItem>
              caption="E-mail outbox"
              rows={outbox.data.content}
              rowKey={(item) => item.id}
              empty={filter ? 'No messages with this status.' : 'The outbox is empty.'}
              columns={[
                {
                  key: 'subject',
                  header: 'Message',
                  className: 'summary-cell',
                  render: (item) => (
                    <span className="flex min-w-0 flex-col">
                      <span className="flex items-center gap-1.5 truncate font-semibold">
                        {item.subject}
                        {item.attachmentName && (
                          <span title={item.attachmentName}>
                            <Paperclip size={11} aria-label="Has attachment" />
                          </span>
                        )}
                        {item.sensitive && <Badge value="sensitive" tone="muted" label="One-time link" />}
                      </span>
                      <span className="text-muted">{item.to}</span>
                    </span>
                  ),
                },
                { key: 'category', header: 'Category', render: (item) => titleCase(item.category) },
                { key: 'status', header: 'Status', render: (item) => <StatusBadge status={item.status} /> },
                {
                  key: 'attempts',
                  header: 'Attempts',
                  render: (item) => (
                    <span title={item.lastError ?? undefined} className={item.lastError ? 'warning-text inline-flex items-center gap-1' : undefined}>
                      {formatNumber(item.attempts)}
                      {item.lastError && <AlertTriangle size={11} aria-label={`Last error: ${item.lastError}`} />}
                    </span>
                  ),
                },
                {
                  key: 'when',
                  header: 'When',
                  render: (item) =>
                    item.sentAt ? `Sent ${formatDateTime(item.sentAt)}` : item.status === 'PENDING' && item.nextAttemptAt ? `Next try ${formatRelative(item.nextAttemptAt)}` : formatDateTime(item.createdAt),
                },
              ]}
            />
            <Pagination page={outbox.data.page} totalPages={outbox.data.totalPages} totalElements={outbox.data.totalElements} size={outbox.data.size} onPageChange={setPage} />
          </>
        )}
      </Panel>
    </>
  );
}
