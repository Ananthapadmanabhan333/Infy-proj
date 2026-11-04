import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Archive, CheckCircle2, Eye, FileClock, Link2, Pencil, Send, Trash2 } from 'lucide-react';
import { useArticle, useArticleWorkflow, useDeleteArticle, useRateArticle } from '../../api/knowledge';
import { Badge, DemoBadge, StatusBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ARTICLE_TYPE_LABELS } from '../../components/knowledge/ArticleRow';
import { ArticleVersions } from '../../components/knowledge/ArticleVersions';
import { Markdown } from '../../components/Markdown';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { StarRating } from '../../components/StarRating';
import { ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage } from '../../lib/api';
import { KB_DELETERS, KB_PUBLISHERS, READERS, STAFF } from '../../lib/constants';
import { formatDate, formatDateTime, formatNumber, formatRelative, titleCase } from '../../lib/format';
import type { ArticleStatus } from '../../lib/types';
import { ArticleEditor } from './ArticleEditor';
import '../../components/knowledge/knowledge.css';

type Dialog = 'archive' | 'delete' | null;

const STATUS_NOTES: Partial<Record<ArticleStatus, string>> = {
  DRAFT: 'Draft — visible to staff only. Submit it for review when it is ready.',
  IN_REVIEW: 'In review — waiting for a knowledge publisher to publish it.',
  ARCHIVED: 'Archived — hidden from employees and from search results.',
};

/** Knowledge article: content, metadata, tags, rating, versions and the role-based publishing workflow (§9). */
export default function ArticlePage() {
  const { id } = useParams();
  const articleId = Number(id);
  const navigate = useNavigate();
  const { hasRole } = useAuth();
  const staff = hasRole(...STAFF);
  const publisher = hasRole(...KB_PUBLISHERS);
  const deleter = hasRole(...KB_DELETERS);
  const readers = hasRole(...READERS);
  const article = useArticle(articleId);
  const workflow = useArticleWorkflow(articleId);
  const remove = useDeleteArticle();
  const rate = useRateArticle(articleId);
  const toast = useToast();
  const [myRating, setMyRating] = useState<number | null>(null);
  const [editing, setEditing] = useState(false);
  const [dialog, setDialog] = useState<Dialog>(null);

  if (article.isLoading) return <LoadingState variant="panel" label="Loading article…" />;
  if (article.isError || !article.data) return <ErrorState error={article.error} onRetry={() => void article.refetch()} title="Article unavailable" />;
  const data = article.data;

  const canSubmit = staff && data.status === 'DRAFT';
  const canPublish = publisher && (data.status === 'DRAFT' || data.status === 'IN_REVIEW');
  const canArchive = publisher && data.status === 'PUBLISHED';
  const busy = workflow.submit.isPending || workflow.publish.isPending || workflow.archive.isPending;
  const statusNote = STATUS_NOTES[data.status];

  const submitRating = (rating: 1 | 2 | 3 | 4 | 5) =>
    rate.mutate(
      { rating },
      {
        onSuccess: () => {
          setMyRating(rating);
          toast.success('Thanks — your rating was saved');
        },
        onError: (error) => toast.error(error),
      },
    );

  const runSubmit = () =>
    workflow.submit.mutate(undefined, {
      onSuccess: () => toast.success(`${data.number} submitted for review`),
      onError: (error) => toast.error(error),
    });
  const runPublish = () =>
    workflow.publish.mutate(undefined, {
      onSuccess: () => toast.success(`${data.number} published`),
      onError: (error) => toast.error(error),
    });

  // One gold primary action per header: Publish › Submit for review › Edit.
  const primary = canPublish ? 'publish' : canSubmit ? 'submit' : staff ? 'edit' : null;

  return (
    <>
      <PageHeader
        crumbs={[{ label: 'Knowledge Base', to: '/knowledge' }, { label: data.number }]}
        title={data.title}
        subtitle={data.summary}
        actions={
          (staff || publisher || deleter) && (
            <>
              {deleter && (
                <button type="button" className="danger-btn" onClick={() => setDialog('delete')} disabled={busy}>
                  <Trash2 size={14} /> Delete
                </button>
              )}
              {canArchive && (
                <button type="button" className="subtle-btn" onClick={() => setDialog('archive')} disabled={busy}>
                  <Archive size={14} /> Archive
                </button>
              )}
              {staff && (
                <button type="button" className={primary === 'edit' ? 'gold-btn' : 'subtle-btn'} onClick={() => setEditing(true)} disabled={busy}>
                  <Pencil size={14} /> Edit
                </button>
              )}
              {canSubmit && (
                <button type="button" className={primary === 'submit' ? 'gold-btn' : 'subtle-btn'} onClick={runSubmit} disabled={busy}>
                  <Send size={14} /> {workflow.submit.isPending ? 'Submitting…' : 'Submit for review'}
                </button>
              )}
              {canPublish && (
                <button type="button" className="gold-btn" onClick={runPublish} disabled={busy}>
                  <CheckCircle2 size={14} /> {workflow.publish.isPending ? 'Publishing…' : 'Publish'}
                </button>
              )}
            </>
          )
        }
      >
        <div className="tag-row mt-2">
          <StatusBadge status={data.status} />
          <Badge value="outline" label={ARTICLE_TYPE_LABELS[data.articleType] ?? titleCase(data.articleType)} />
          <Badge value="outline" label={titleCase(data.category)} />
          {data.serviceName && <Badge value="outline" label={data.serviceName} />}
          <span className="text-[10px] text-muted">
            {data.readMinutes} min read · updated {formatRelative(data.updatedAt)}
          </span>
          <DemoBadge demo={data.demo} />
        </div>
      </PageHeader>

      <div className="grid gap-[14px] xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="grid content-start gap-[14px]">
          {statusNote && (
            <div className="callout kb-status-note" role="note">
              <FileClock size={16} />
              <div>{statusNote}</div>
            </div>
          )}
          <article className="panel" aria-label={data.title}>
            <Markdown source={data.content} />
            {data.tags.length > 0 && (
              <div className="mt-5 flex flex-wrap gap-1.5 border-t border-line pt-3" aria-label="Tags">
                {data.tags.map((tag) => (
                  <span key={tag} className="chip">
                    #{tag}
                  </span>
                ))}
              </div>
            )}
          </article>
        </div>

        <aside className="grid content-start gap-[14px]">
          <Panel title="Rate this article" subtitle={data.ratingCount ? undefined : 'Be the first to rate it'}>
            {data.ratingCount > 0 && (
              <div className="mb-2">
                <StarRating value={data.averageRating} count={data.ratingCount} />
              </div>
            )}
            <StarRating value={myRating} onChange={submitRating} disabled={rate.isPending} label="Your rating" />
            <p className="mt-2 text-[10px] text-muted">
              {myRating !== null ? `You rated this ${myRating} out of 5. You can change your rating at any time.` : 'One rating per person — you can update it later.'}
            </p>
          </Panel>

          <Panel title="Article details">
            <div className="detail-grid tight">
              <div>
                <small>Number</small>
                <b>{data.number}</b>
              </div>
              <div>
                <small>Status</small>
                <b>
                  <StatusBadge status={data.status} />
                </b>
              </div>
              <div>
                <small>Author</small>
                <b>{data.authorName ?? '—'}</b>
              </div>
              <div>
                <small>Views</small>
                <b className="inline-flex items-center gap-1">
                  <Eye size={11} /> {formatNumber(data.viewCount)}
                </b>
              </div>
              <div>
                <small>Published</small>
                <b>{formatDate(data.publishedAt)}</b>
              </div>
              <div>
                <small>Updated</small>
                <b title={formatDateTime(data.updatedAt)}>{formatDate(data.updatedAt)}</b>
              </div>
              <div>
                <small>Service</small>
                <b>{data.serviceName ?? 'Any'}</b>
              </div>
              <div>
                <small>Linked incidents</small>
                <b className="inline-flex items-center gap-1">
                  <Link2 size={11} /> {formatNumber(data.linkedIncidentCount)}
                </b>
              </div>
            </div>
          </Panel>

          {readers && <ArticleVersions articleId={articleId} canRestore={publisher} />}
        </aside>
      </div>

      {editing && <ArticleEditor article={data} onClose={() => setEditing(false)} />}

      <ConfirmDialog
        open={dialog === 'archive'}
        onClose={() => setDialog(null)}
        title={`Archive ${data.number}`}
        message="The article is hidden from employees and search results. It can be published again later."
        confirmLabel="Archive article"
        pending={workflow.archive.isPending}
        error={workflow.archive.isError ? errorMessage(workflow.archive.error) : null}
        onConfirm={() =>
          workflow.archive.mutate(undefined, {
            onSuccess: () => {
              toast.info(`${data.number} archived`);
              setDialog(null);
            },
          })
        }
      />
      <ConfirmDialog
        open={dialog === 'delete'}
        onClose={() => setDialog(null)}
        title={`Delete ${data.number}`}
        message={
          data.linkedIncidentCount > 0
            ? `This permanently deletes the article and its versions. ${data.linkedIncidentCount} incident${data.linkedIncidentCount === 1 ? '' : 's'} citing it will lose the reference. The deletion is audited.`
            : 'This permanently deletes the article and its versions. The deletion is audited.'
        }
        confirmLabel="Delete article"
        tone="danger"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() =>
          remove.mutate(articleId, {
            onSuccess: () => {
              toast.success(`${data.number} deleted`);
              setDialog(null);
              navigate('/knowledge', { replace: true });
            },
          })
        }
      />
    </>
  );
}
