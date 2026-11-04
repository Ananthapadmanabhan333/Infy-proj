import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Lock, MessageSquare, Send } from 'lucide-react';
import { useAddServiceRequestComment, useServiceRequestComments } from '../../api/serviceDesk';
import { Toggle } from '../Controls';
import { TextareaField } from '../FormField';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';
import { applyFieldErrors } from '../../lib/api';
import { formatDateTime, formatRelative } from '../../lib/format';
import { commentSchema, type CommentValues } from '../../lib/schemas';

interface RequestCommentsProps {
  requestId: number;
  /** Requester or staff (auditors are read-only). */
  canComment: boolean;
  /** Staff may post internal notes, hidden from the requester. */
  canInternal: boolean;
}

/** Request conversation: public replies and (for staff) internal notes, oldest first, with an add-comment form. */
export function RequestComments({ requestId, canComment, canInternal }: RequestCommentsProps) {
  const comments = useServiceRequestComments(requestId);
  const add = useAddServiceRequestComment(requestId);
  const toast = useToast();
  const [internal, setInternal] = useState(false);
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<CommentValues>({ resolver: zodResolver(commentSchema), defaultValues: { body: '' } });
  const asInternal = canInternal && internal;

  const onSubmit = handleSubmit((values) =>
    add.mutate(
      { body: values.body.trim(), internal: asInternal },
      {
        onSuccess: () => {
          reset();
          toast.success(asInternal ? 'Internal note added' : 'Comment posted');
        },
        onError: (error) => {
          if (!applyFieldErrors(error, setError)) toast.error(error);
        },
      },
    ),
  );

  return (
    <div>
      {comments.isLoading && <LoadingState rows={2} label="Loading comments…" />}
      {comments.isError && <ErrorState error={comments.error} onRetry={() => void comments.refetch()} compact />}
      {comments.data && comments.data.length === 0 && <EmptyState title="No comments yet" icon={<MessageSquare size={20} />} />}
      {comments.data && comments.data.length > 0 && (
        <div className="grid gap-2" aria-label="Comments">
          {comments.data.map((comment) => (
            <article key={comment.id} className={`comment ${comment.internal ? 'internal' : ''} ${comment.systemGenerated ? 'system' : ''}`}>
              <header>
                <b>{comment.authorName}</b>
                {comment.internal && (
                  <span className="chip">
                    <Lock size={10} aria-hidden="true" /> Internal
                  </span>
                )}
                {comment.systemGenerated && <span className="chip">System</span>}
                <span className="ml-auto" title={formatDateTime(comment.createdAt)}>
                  {formatRelative(comment.createdAt)}
                </span>
              </header>
              <p>{comment.body}</p>
            </article>
          ))}
        </div>
      )}
      {canComment && (
        <form onSubmit={onSubmit} noValidate className="mt-3">
          <TextareaField
            label={asInternal ? 'Add an internal note' : 'Add a comment'}
            placeholder={asInternal ? 'Only support staff can see internal notes' : canInternal ? 'Visible to the requester and the team' : 'Write an update for the support team'}
            maxLength={5000}
            error={errors.body?.message}
            {...register('body')}
          />
          <div className="flex flex-wrap items-center justify-end gap-3">
            {canInternal && (
              <span className="mr-auto inline-flex items-center gap-2 text-[11px] text-muted">
                <Toggle checked={internal} onChange={setInternal} label="Internal note (staff only)" />
                <span aria-hidden="true">Internal note</span>
              </span>
            )}
            <button type="submit" className="gold-btn compact" disabled={add.isPending}>
              <Send size={13} aria-hidden="true" /> {add.isPending ? 'Posting…' : asInternal ? 'Add note' : 'Post comment'}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
