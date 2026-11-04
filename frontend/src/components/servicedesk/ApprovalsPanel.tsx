import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Check, CircleX, ShieldAlert, ShieldCheck, Sparkles } from 'lucide-react';
import { useApprovals, useDecideApproval } from '../../api/serviceDesk';
import { AiSourceBadge } from '../AiResultCard';
import { Badge, StatusBadge } from '../Badge';
import { ConfirmDialog } from '../ConfirmDialog';
import { FilterSelect } from '../FilterSelect';
import { JsonViewer } from '../JsonViewer';
import { Pagination } from '../Pagination';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';
import { errorMessage, toApiError } from '../../lib/api';
import { APPROVAL_STATUSES, APPROVAL_TYPE_LABELS, DEFAULT_PAGE_SIZE, roleLabel } from '../../lib/constants';
import { formatDateTime, formatRelative, titleCase } from '../../lib/format';
import type { AiSource, ApprovalDto } from '../../lib/types';

/** Link target for approvals that are not service requests (those open in the request drawer). */
function approvalLink(approval: ApprovalDto): string | null {
  const incidentId = approval.payload?.incidentId;
  if (typeof incidentId === 'number' || typeof incidentId === 'string') return `/incidents/${incidentId}`;
  const runbookId = approval.payload?.runbookId;
  if ((approval.type === 'RUNBOOK_EXECUTION' || approval.type === 'RUNBOOK_STEP') && (typeof runbookId === 'number' || typeof runbookId === 'string')) {
    return `/runbooks/${runbookId}`;
  }
  return null;
}

function aiSource(payload: ApprovalDto['payload']): { source: AiSource; provider: string; model: string | null } | null {
  const source = payload?.source;
  if (source !== 'LLM' && source !== 'FALLBACK') return null;
  return {
    source,
    provider: typeof payload?.provider === 'string' ? payload.provider : '',
    model: typeof payload?.model === 'string' ? payload.model : null,
  };
}

interface ApprovalsPanelProps {
  /** '' = pending (default), 'ALL' = every status, or a specific status. */
  status: string;
  onStatusChange: (status: string) => void;
  page: number;
  onPageChange: (page: number) => void;
  onOpenRequest: (requestId: number) => void;
}

/**
 * Approvals routed to the current approver role (SYSTEM_ADMIN sees all): service requests, approval-gated runbooks,
 * sensitive runbook steps and SA AI proposed actions. Rejecting needs a reason; the API's segregation-of-duties
 * refusal (approving your own request) is shown as returned.
 */
export function ApprovalsPanel({ status, onStatusChange, page, onPageChange, onOpenRequest }: ApprovalsPanelProps) {
  const effective = status === 'ALL' ? undefined : status || 'PENDING';
  const approvals = useApprovals({ status: effective, page, size: DEFAULT_PAGE_SIZE });
  const decide = useDecideApproval();
  const toast = useToast();
  const [decision, setDecision] = useState<{ approval: ApprovalDto; approved: boolean } | null>(null);
  const [refusal, setRefusal] = useState<{ id: number; message: string } | null>(null);

  const close = () => {
    setDecision(null);
    decide.reset();
  };

  const confirm = (comment: string) => {
    if (!decision) return;
    const { approval, approved } = decision;
    decide.mutate(
      { id: approval.id, approved, comment },
      {
        onSuccess: () => {
          toast.success(`${approval.referenceNumber ?? approval.title} ${approved ? 'approved' : 'rejected'}`);
          setRefusal(null);
          setDecision(null);
        },
        onError: (error) => {
          if (toApiError(error).status === 403) setRefusal({ id: approval.id, message: errorMessage(error) });
        },
      },
    );
  };

  return (
    <>
      <div className="toolbar">
        <p className="mr-auto text-[11px] text-muted">Requests, runbooks and SA AI actions routed to your approver role.</p>
        <FilterSelect
          label="Approval status"
          allLabel="All statuses"
          value={status === 'ALL' ? '' : status || 'PENDING'}
          onChange={(value) => onStatusChange(value === '' ? 'ALL' : value === 'PENDING' ? '' : value)}
          options={APPROVAL_STATUSES}
          formatOption={titleCase}
        />
      </div>
      {approvals.isLoading && <LoadingState rows={4} label="Loading approvals…" />}
      {approvals.isError && !approvals.data && <ErrorState error={approvals.error} onRetry={() => void approvals.refetch()} />}
      {approvals.data && approvals.data.content.length === 0 && (
        <EmptyState
          title={effective === 'PENDING' ? 'Nothing awaiting your decision' : 'No approvals match this status'}
          description={effective === 'PENDING' ? 'New approvals routed to your role appear here.' : undefined}
          icon={<ShieldCheck size={20} />}
        />
      )}
      {approvals.data && approvals.data.content.length > 0 && (
        <ul className="grid gap-2" aria-label="Approvals">
          {approvals.data.content.map((approval) => {
            const link = approval.type === 'SERVICE_REQUEST' ? null : approvalLink(approval);
            const source = approval.type === 'AI_ACTION' ? aiSource(approval.payload) : null;
            return (
              <li key={approval.id}>
                <article className="rounded-[10px] border border-line bg-panel-raised/60 px-3 py-3" aria-label={approval.title}>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="icon-tile" aria-hidden="true">
                      {approval.type === 'AI_ACTION' ? <Sparkles size={17} /> : <ShieldCheck size={17} />}
                    </span>
                    <div className="min-w-0 flex-1 basis-[200px]">
                      <b className="block truncate text-[12px] text-ivory" title={approval.title}>
                        {approval.title}
                      </b>
                      <small className="block text-[10px] text-muted">
                        {approval.referenceNumber &&
                          (approval.type === 'SERVICE_REQUEST' ? (
                            <button type="button" className="link-btn text-[10px]" onClick={() => onOpenRequest(approval.referenceId)}>
                              {approval.referenceNumber}
                            </button>
                          ) : link ? (
                            <Link to={link} className="font-semibold text-gold-light">
                              {approval.referenceNumber}
                            </Link>
                          ) : (
                            <span className="font-semibold text-gold-light/80">{approval.referenceNumber}</span>
                          ))}
                        {approval.referenceNumber ? ' • ' : ''}
                        {APPROVAL_TYPE_LABELS[approval.type] ?? titleCase(approval.type)} • {approval.requestedByName ?? 'System'} •{' '}
                        <span title={formatDateTime(approval.createdAt)}>{formatRelative(approval.createdAt)}</span>
                      </small>
                    </div>
                    <Badge value="outline" label={`${roleLabel(approval.approverRole)} approval`} />
                    <StatusBadge status={approval.status} />
                  </div>
                  {approval.details && <p className="mt-2 text-[11px] leading-relaxed text-[#d6cab8]">{approval.details}</p>}
                  {approval.type === 'AI_ACTION' && (
                    <div className="mt-2 flex flex-wrap items-center gap-2 text-[10px] text-muted">
                      {source ? <AiSourceBadge result={source} /> : <span className="chip">Proposed by SA AI</span>}
                      <span>Applied only after a human approves it.</span>
                    </div>
                  )}
                  {approval.type === 'AI_ACTION' && approval.payload && (
                    <details className="mt-2">
                      <summary className="cursor-pointer text-[10px] text-gold">Proposed change</summary>
                      <JsonViewer value={approval.payload} label="Proposed change" />
                    </details>
                  )}
                  {approval.decidedByName && (
                    <p className="mt-2 text-[10px] text-muted">
                      {titleCase(approval.status)} by {approval.decidedByName} · {formatDateTime(approval.decidedAt)}
                      {approval.decisionComment ? ` — “${approval.decisionComment}”` : ''}
                    </p>
                  )}
                  {refusal?.id === approval.id && (
                    <div className="callout red mt-2" role="alert">
                      <ShieldAlert size={16} aria-hidden="true" />
                      <div>{refusal.message}</div>
                    </div>
                  )}
                  {approval.status === 'PENDING' && (
                    <div className="mt-3 flex justify-end gap-2">
                      <button type="button" className="danger-btn compact" onClick={() => setDecision({ approval, approved: false })} aria-label={`Reject ${approval.title}`}>
                        <CircleX size={13} aria-hidden="true" /> Reject
                      </button>
                      <button type="button" className="gold-btn compact" onClick={() => setDecision({ approval, approved: true })} aria-label={`Approve ${approval.title}`}>
                        <Check size={13} aria-hidden="true" /> Approve
                      </button>
                    </div>
                  )}
                </article>
              </li>
            );
          })}
        </ul>
      )}
      {approvals.data && (
        <Pagination
          page={approvals.data.page}
          totalPages={approvals.data.totalPages}
          totalElements={approvals.data.totalElements}
          size={approvals.data.size}
          onPageChange={onPageChange}
          label="approvals"
        />
      )}
      <ConfirmDialog
        open={decision !== null}
        onClose={close}
        title={decision ? `${decision.approved ? 'Approve' : 'Reject'} “${decision.approval.title}”?` : ''}
        message={decision?.approved ? 'The item proceeds as soon as it is approved.' : 'The requester is notified with your reason.'}
        reasonLabel={decision?.approved ? 'Comment (optional)' : 'Reason'}
        reasonRequired={decision?.approved === false}
        confirmLabel={decision?.approved ? 'Approve' : 'Reject'}
        tone={decision?.approved ? 'gold' : 'danger'}
        pending={decide.isPending}
        error={decide.isError ? errorMessage(decide.error) : null}
        onConfirm={confirm}
      />
    </>
  );
}
