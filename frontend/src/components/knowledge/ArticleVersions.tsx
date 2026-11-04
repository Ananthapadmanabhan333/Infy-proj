import { useState } from 'react';
import { History, RotateCcw } from 'lucide-react';
import { useArticleVersions, useRestoreArticleVersion } from '../../api/knowledge';
import { errorMessage } from '../../lib/api';
import { formatDateTime, formatRelative } from '../../lib/format';
import type { ArticleVersionDto } from '../../lib/types';
import { ConfirmDialog } from '../ConfirmDialog';
import { Panel } from '../Panel';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';

/** §9 version history (newest first) with restore for KB_PUBLISHERS. */
export function ArticleVersions({ articleId, canRestore }: { articleId: number; canRestore: boolean }) {
  const versions = useArticleVersions(articleId);
  const restore = useRestoreArticleVersion(articleId);
  const toast = useToast();
  const [target, setTarget] = useState<ArticleVersionDto | null>(null);
  const list = [...(versions.data ?? [])].sort((a, b) => b.version - a.version);
  const latest = list[0]?.version;

  return (
    <Panel title="Versions" subtitle={list.length ? `${list.length} stored version${list.length === 1 ? '' : 's'}` : undefined}>
      {versions.isLoading && <LoadingState rows={3} label="Loading versions…" />}
      {versions.isError && <ErrorState error={versions.error} onRetry={() => void versions.refetch()} compact />}
      {versions.data && list.length === 0 && <EmptyState title="No versions yet" icon={<History size={20} />} />}
      {list.length > 0 && (
        <ul className="kb-versions">
          {list.map((version) => (
            <li key={version.version}>
              <span className="kb-version-num">v{version.version}</span>
              <div className="min-w-0 flex-1">
                <b>
                  {version.title}
                  {version.version === latest && <span className="muted-text"> · current</span>}
                </b>
                <small title={formatDateTime(version.createdAt)}>
                  {version.editorName ?? 'Unknown editor'} · {formatRelative(version.createdAt)}
                </small>
                {version.changeNote && <small>“{version.changeNote}”</small>}
              </div>
              {canRestore && version.version !== latest && (
                <button type="button" className="subtle-btn compact" onClick={() => setTarget(version)} aria-label={`Restore version ${version.version}`}>
                  <RotateCcw size={12} /> Restore
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={target !== null}
        onClose={() => setTarget(null)}
        title={`Restore version ${target?.version ?? ''}`}
        message="The article content is replaced by this version. A new version is stored, so nothing is lost."
        confirmLabel="Restore version"
        pending={restore.isPending}
        error={restore.isError ? errorMessage(restore.error) : null}
        onConfirm={() => {
          if (!target) return;
          restore.mutate(target.version, {
            onSuccess: () => {
              toast.success(`Version ${target.version} restored`);
              setTarget(null);
            },
          });
        }}
      />
    </Panel>
  );
}
