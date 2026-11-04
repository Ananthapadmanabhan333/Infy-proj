import { Download, Paperclip } from 'lucide-react';
import { useRequestAttachments } from '../../api/forms';
import { downloadFile } from '../../lib/api';
import { formatBytes } from '../../lib/format';
import { useToast } from '../Toast';

/** Files uploaded through the request form's file fields (requester, staff and auditors). Hidden when there are none. */
export function RequestAttachments({ requestId }: { requestId: number }) {
  const attachments = useRequestAttachments(requestId);
  const toast = useToast();
  if (!attachments.data || attachments.data.length === 0) return null;
  return (
    <div className="drawer-section">
      <h3>Attachments</h3>
      <ul className="m-0 grid list-none gap-1 p-0" aria-label="Request attachments">
        {attachments.data.map((file) => (
          <li key={file.id} className="flex items-center gap-2 text-[11px]">
            <Paperclip size={12} aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate">{file.fileName}</span>
            <span className="text-muted">{formatBytes(file.sizeBytes)}</span>
            <button
              type="button"
              className="icon-btn sm"
              aria-label={`Download ${file.fileName}`}
              onClick={() => void downloadFile(`/service-requests/${requestId}/attachments/${file.id}/download`, file.fileName).catch((error) => toast.error(error))}
            >
              <Download size={12} />
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
