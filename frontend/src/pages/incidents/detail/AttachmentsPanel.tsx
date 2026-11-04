import { useRef, useState } from 'react';
import { Download, FileUp, Paperclip, Trash2 } from 'lucide-react';
import { useDeleteAttachment, useIncidentAttachments, useUploadAttachment } from '../../../api/incidents';
import { ConfirmDialog } from '../../../components/ConfirmDialog';
import { PanelTitle } from '../../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../../components/States';
import { useToast } from '../../../components/Toast';
import { useAuth } from '../../../hooks/useAuth';
import { downloadFile, errorMessage, toApiError } from '../../../lib/api';
import { ADMIN_OR_MANAGER, ALLOWED_UPLOAD_EXTENSIONS, MAX_UPLOAD_BYTES } from '../../../lib/constants';
import { formatBytes, formatRelative } from '../../../lib/format';
import type { AttachmentDto } from '../../../lib/types';

export function validateUpload(file: File): string | null {
  if (file.size > MAX_UPLOAD_BYTES) return `${file.name} is ${formatBytes(file.size)} — the limit is 5 MB.`;
  const extension = file.name.includes('.') ? file.name.split('.').pop()!.toLowerCase() : '';
  if (!ALLOWED_UPLOAD_EXTENSIONS.includes(extension)) return `.${extension || '?'} files are not accepted. Allowed: ${ALLOWED_UPLOAD_EXTENSIONS.join(', ')}.`;
  return null;
}

function uploadErrorMessage(error: unknown): string {
  const apiError = toApiError(error);
  if (apiError.status === 413) return 'The file is too large. The maximum size is 5 MB.';
  if (apiError.status === 415) return apiError.message || 'This file type is not allowed (the file content did not match an allowed type).';
  return apiError.message;
}

export function AttachmentsPanel({ incidentId, readOnly }: { incidentId: number; readOnly: boolean }) {
  const { user, hasRole } = useAuth();
  const attachments = useIncidentAttachments(incidentId);
  const upload = useUploadAttachment(incidentId);
  const remove = useDeleteAttachment(incidentId);
  const toast = useToast();
  const inputRef = useRef<HTMLInputElement>(null);
  const [progress, setProgress] = useState<number | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<AttachmentDto | null>(null);

  const onFile = (file: File | undefined) => {
    if (!file) return;
    setUploadError(null);
    const problem = validateUpload(file);
    if (problem) {
      setUploadError(problem);
      return;
    }
    setProgress(0);
    upload.mutate(
      { file, onProgress: setProgress },
      {
        onSuccess: () => toast.success(`${file.name} uploaded`),
        onError: (error) => setUploadError(uploadErrorMessage(error)),
        onSettled: () => {
          setProgress(null);
          if (inputRef.current) inputRef.current.value = '';
        },
      },
    );
  };

  const canDelete = (attachment: AttachmentDto) => hasRole(...ADMIN_OR_MANAGER) || attachment.uploadedByName === user?.fullName;

  return (
    <section className="panel" aria-labelledby="attachments-title">
      <PanelTitle id="attachments-title" title="Attachments" subtitle="Screenshots, logs and documents (max 5 MB each)">
        {!readOnly && (
          <>
            <input
              ref={inputRef}
              type="file"
              className="sr-only"
              id={`upload-${incidentId}`}
              accept={ALLOWED_UPLOAD_EXTENSIONS.map((ext) => `.${ext}`).join(',')}
              onChange={(event) => onFile(event.target.files?.[0])}
              disabled={upload.isPending}
            />
            <label htmlFor={`upload-${incidentId}`} className={`subtle-btn compact cursor-pointer ${upload.isPending ? 'opacity-50' : ''}`}>
              <FileUp size={14} /> Upload file
            </label>
          </>
        )}
      </PanelTitle>
      {progress !== null && (
        <div className="mb-3" aria-live="polite">
          <div className="mb-1 text-[10px] text-muted">Uploading… {progress}%</div>
          <div className="progress" role="progressbar" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100} aria-label="Upload progress">
            <i style={{ width: `${progress}%` }} />
          </div>
        </div>
      )}
      {uploadError && (
        <p className="field-error mb-2" role="alert">
          {uploadError}
        </p>
      )}
      {attachments.isLoading && <LoadingState rows={2} />}
      {attachments.isError && <ErrorState error={attachments.error} onRetry={() => void attachments.refetch()} compact />}
      {attachments.data && attachments.data.length === 0 && <EmptyState title="No attachments" icon={<Paperclip size={20} />} />}
      {attachments.data?.map((attachment) => (
        <div key={attachment.id} className="list-row">
          <Paperclip size={14} className="gold-text" aria-hidden="true" />
          <div>
            <b className="truncate">{attachment.fileName}</b>
            <small>
              {formatBytes(attachment.sizeBytes)} · {attachment.uploadedByName} · {formatRelative(attachment.createdAt)}
            </small>
          </div>
          <button
            type="button"
            className="icon-btn sm"
            aria-label={`Download ${attachment.fileName}`}
            onClick={() =>
              downloadFile(`/incidents/${incidentId}/attachments/${attachment.id}/download`, attachment.fileName).catch((error) => toast.error(error))
            }
          >
            <Download size={15} />
          </button>
          {!readOnly && canDelete(attachment) && (
            <button type="button" className="icon-btn sm" aria-label={`Delete ${attachment.fileName}`} onClick={() => setPendingDelete(attachment)}>
              <Trash2 size={15} />
            </button>
          )}
        </div>
      ))}
      <ConfirmDialog
        open={pendingDelete !== null}
        onClose={() => setPendingDelete(null)}
        title="Delete attachment?"
        message={`${pendingDelete?.fileName ?? ''} will be removed from this incident. This is recorded in the activity history.`}
        confirmLabel="Delete"
        tone="danger"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() =>
          pendingDelete &&
          remove.mutate(pendingDelete.id, {
            onSuccess: () => {
              toast.success('Attachment deleted');
              setPendingDelete(null);
            },
          })
        }
      />
    </section>
  );
}
