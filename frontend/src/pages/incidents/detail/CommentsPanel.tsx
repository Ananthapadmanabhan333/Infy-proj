import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Lock, MessageSquare, Send } from 'lucide-react';
import { useAddComment, useIncidentComments } from '../../../api/incidents';
import { Tabs } from '../../../components/Controls';
import { TextareaField } from '../../../components/FormField';
import { PanelTitle } from '../../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../../components/States';
import { useToast } from '../../../components/Toast';
import { useAuth } from '../../../hooks/useAuth';
import { applyFieldErrors } from '../../../lib/api';
import { formatDateTime, formatRelative } from '../../../lib/format';
import { commentSchema, type CommentValues } from '../../../lib/schemas';

type CommentTab = 'public' | 'internal';

export function CommentsPanel({ incidentId, readOnly }: { incidentId: number; readOnly: boolean }) {
  const { user } = useAuth();
  const canInternal = user?.role !== 'EMPLOYEE';
  const [tab, setTab] = useState<CommentTab>('public');
  const comments = useIncidentComments(incidentId);
  const add = useAddComment(incidentId);
  const toast = useToast();
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<CommentValues>({ resolver: zodResolver(commentSchema), defaultValues: { body: '' } });

  const internal = canInternal && tab === 'internal';
  const visible = (comments.data ?? []).filter((comment) => (internal ? comment.internal : !comment.internal));
  const internalCount = (comments.data ?? []).filter((comment) => comment.internal).length;

  const onSubmit = handleSubmit((values) =>
    add.mutate(
      { body: values.body.trim(), internal },
      {
        onSuccess: () => {
          reset();
          toast.success(internal ? 'Internal note added' : 'Comment posted');
        },
        onError: (error) => {
          if (!applyFieldErrors(error, setError)) toast.error(error);
        },
      },
    ),
  );

  return (
    <section className="panel" aria-labelledby="comments-title">
      <PanelTitle id="comments-title" title="Conversation" subtitle={canInternal ? 'Public replies are visible to the requester; internal notes are staff-only' : 'Updates between you and the support team'} />
      {canInternal && (
        <Tabs<CommentTab>
          inner
          label="Comment visibility"
          value={tab}
          onChange={setTab}
          tabs={[
            { id: 'public', label: 'Public', count: (comments.data?.length ?? 0) - internalCount },
            { id: 'internal', label: 'Internal notes', count: internalCount },
          ]}
        />
      )}
      {comments.isLoading && <LoadingState rows={3} />}
      {comments.isError && <ErrorState error={comments.error} onRetry={() => void comments.refetch()} compact />}
      {comments.data && visible.length === 0 && (
        <EmptyState title={internal ? 'No internal notes' : 'No comments yet'} icon={<MessageSquare size={20} />} />
      )}
      <div className="grid gap-2">
        {visible.map((comment) => (
          <article key={comment.id} className={`comment ${comment.internal ? 'internal' : ''} ${comment.systemGenerated ? 'system' : ''}`}>
            <header>
              <b>{comment.authorName}</b>
              {comment.internal && (
                <span className="chip">
                  <Lock size={10} /> Internal
                </span>
              )}
              {comment.systemGenerated && <span className="chip">System</span>}
              <span title={formatDateTime(comment.createdAt)} className="ml-auto">
                {formatRelative(comment.createdAt)}
              </span>
            </header>
            <p>{comment.body}</p>
          </article>
        ))}
      </div>
      {!readOnly && (
        <form onSubmit={onSubmit} noValidate className="mt-2">
          <TextareaField
            label={internal ? 'Add an internal note' : 'Add a comment'}
            placeholder={internal ? 'Only staff can see internal notes' : 'Write an update for the requester and team'}
            maxLength={5000}
            error={errors.body?.message}
            {...register('body')}
          />
          <div className="flex justify-end">
            <button type="submit" className="gold-btn compact" disabled={add.isPending}>
              <Send size={14} /> {add.isPending ? 'Posting…' : internal ? 'Add note' : 'Post comment'}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}
