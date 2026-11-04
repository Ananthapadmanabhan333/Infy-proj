import { useEffect, useId, useState, type ReactNode } from 'react';
import { Modal } from './Modal';

interface ConfirmDialogProps {
  open: boolean;
  onClose: () => void;
  onConfirm: (reason: string) => unknown;
  title: string;
  message: ReactNode;
  confirmLabel?: string;
  tone?: 'gold' | 'danger';
  /** Ask for a reason; confirm stays disabled until one is entered. */
  reasonLabel?: string;
  reasonRequired?: boolean;
  pending?: boolean;
  error?: string | null;
}

export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  message,
  confirmLabel = 'Confirm',
  tone = 'gold',
  reasonLabel,
  reasonRequired = false,
  pending = false,
  error,
}: ConfirmDialogProps) {
  const [reason, setReason] = useState('');
  const reasonId = useId();
  useEffect(() => {
    if (open) setReason('');
  }, [open]);

  const blocked = pending || (reasonRequired && reason.trim().length < 3);

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      dismissible={!pending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={pending}>
            Cancel
          </button>
          <button
            type="button"
            className={tone === 'danger' ? 'danger-btn' : 'gold-btn'}
            onClick={() => void onConfirm(reason.trim())}
            disabled={blocked}
            data-autofocus={reasonLabel ? undefined : true}
          >
            {pending ? 'Working…' : confirmLabel}
          </button>
        </>
      }
    >
      <div className="text-[12px] leading-relaxed text-[#d6cab8]">{message}</div>
      {reasonLabel && (
        <div className="field">
          <div className="field-label">
            <label htmlFor={reasonId}>{reasonLabel}</label>
            {reasonRequired && (
              <span className="required-mark" aria-hidden="true">
                *
              </span>
            )}
          </div>
          <textarea
            id={reasonId}
            className="control"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            maxLength={1000}
            aria-required={reasonRequired || undefined}
          />
          {reasonRequired && <span className="field-hint">At least 3 characters.</span>}
        </div>
      )}
      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
    </Modal>
  );
}
