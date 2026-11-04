import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { BookOpen, ChevronRight, ClipboardList, Plus, ShieldCheck } from 'lucide-react';
import { useArticles, useKnowledgeStats } from '../../api/knowledge';
import { useRunbooks } from '../../api/runbooks';
import { useServices } from '../../api/services';
import { FilterSelect } from '../../components/FilterSelect';
import { ArticleRow, ARTICLE_TYPE_LABELS } from '../../components/knowledge/ArticleRow';
import { KB_TILE_TYPES, KbCategoryTiles, tileForType, type KbTile } from '../../components/knowledge/KbCategoryTiles';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useAuth } from '../../hooks/useAuth';
import { useSearchInput, useUrlFilters } from '../../hooks/useUrlState';
import { ARTICLE_STATUSES, ARTICLE_STATUS_LABELS, ARTICLE_TYPES, CATEGORIES, READERS, STAFF } from '../../lib/constants';
import { formatNumber, titleCase } from '../../lib/format';
import type { ArticleSort, ArticleStatus, ArticleType, RunbookDto } from '../../lib/types';
import { ArticleEditor } from './ArticleEditor';
import '../../components/knowledge/knowledge.css';

const PAGE_SIZE = 10;
const SORT_OPTIONS: { value: ArticleSort; label: string }[] = [
  { value: 'recent', label: 'Most recent' },
  { value: 'popular', label: 'Most popular' },
  { value: 'rating', label: 'Highest rated' },
];
const TILE_TITLES: Record<KbTile, string> = { incidents: 'Incident solutions & known errors', guides: 'Guides', faqs: 'FAQs' };

function RunbookMatches({ query, runbooks }: { query: string; runbooks: ReturnType<typeof useRunbooks> }) {
  const list: RunbookDto[] = runbooks.data ?? [];
  return (
    <Panel
      title="Runbooks"
      subtitle={runbooks.data ? `${list.length} runbook${list.length === 1 ? '' : 's'} match “${query}”` : 'Searching runbooks…'}
      action={list.length > 5 ? 'View all' : undefined}
      to={list.length > 5 ? `/runbooks?search=${encodeURIComponent(query)}` : undefined}
    >
      {runbooks.isLoading && <LoadingState rows={2} label="Searching runbooks…" />}
      {runbooks.isError && <ErrorState error={runbooks.error} onRetry={() => void runbooks.refetch()} compact />}
      {runbooks.data && list.length === 0 && <p className="text-[11px] text-muted">No runbooks match this search.</p>}
      {list.length > 0 && (
        <ul className="kb-list">
          {list.slice(0, 5).map((runbook) => (
            <li key={runbook.id}>
              <Link to={`/runbooks/${runbook.id}`} className="kb-row" aria-label={`Runbook ${runbook.title}`}>
                <span className="kb-row-icon" aria-hidden="true">
                  <ClipboardList size={15} />
                </span>
                <span className="kb-row-main">
                  <b>{runbook.title}</b>
                  <small>
                    <span>{runbook.serviceName ?? 'Any service'}</span>
                    <span>
                      {runbook.steps.length} step{runbook.steps.length === 1 ? '' : 's'}
                    </span>
                    {runbook.requiresApproval && (
                      <span className="inline-flex items-center gap-1 text-warning">
                        <ShieldCheck size={10} /> Approval required
                      </span>
                    )}
                  </small>
                </span>
                <span className="kb-row-meta">
                  {runbook.estimatedMinutes ? <span className="read">~{runbook.estimatedMinutes} min</span> : null}
                  <ChevronRight size={14} aria-hidden="true" />
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

/** Knowledge Base (reference screen 9): search, category tiles, popular articles and the filterable article list. */
export default function KnowledgePage({ mode = 'all' }: { mode?: 'all' | 'solutions' }) {
  const { hasRole } = useAuth();
  const staff = hasRole(...STAFF);
  const readers = hasRole(...READERS);
  const navigate = useNavigate();
  const { values, setValues, page, setPage } = useUrlFilters(['q', 'type', 'category', 'serviceId', 'status', 'sort'] as const, {
    // Release 1 "Solutions" page (now `/solutions` → `?type=SOLUTION`).
    type: mode === 'solutions' ? KB_TILE_TYPES.incidents : '',
  });
  const [input, setInput] = useSearchInput(values.q, (q) => setValues({ q }));
  const [creating, setCreating] = useState(false);

  const stats = useKnowledgeStats();
  const services = useServices();
  const searching = values.q.trim().length > 0;
  const filtered = Boolean(values.type || values.category || values.serviceId || values.status);
  const activeTile = tileForType(values.type);
  const sort = (values.sort as ArticleSort) || '';

  const popular = useArticles({ sort: 'popular', status: 'PUBLISHED', size: 5 }, !searching && !filtered);
  const articles = useArticles({
    q: values.q || undefined,
    type: values.type || undefined,
    category: values.category || undefined,
    serviceId: values.serviceId || undefined,
    status: staff ? values.status || undefined : undefined,
    sort: sort || (searching ? undefined : 'recent'),
    page,
    size: PAGE_SIZE,
  });
  const runbooks = useRunbooks({ search: values.q.trim() }, searching && readers);

  const listTitle = searching ? `Articles matching “${values.q}”` : activeTile ? TILE_TITLES[activeTile] : 'All articles';
  const typeOptions: { value: string; label: string }[] = ARTICLE_TYPES.map((type: ArticleType) => ({ value: type, label: ARTICLE_TYPE_LABELS[type] }));
  // Keep a multi-type tile filter selectable in the dropdown.
  if (values.type && !ARTICLE_TYPES.includes(values.type as ArticleType)) {
    typeOptions.unshift({ value: values.type, label: activeTile ? TILE_TITLES[activeTile] : titleCase(values.type) });
  }

  return (
    <>
      <PageHeader
        title="Knowledge Base"
        subtitle="Centralized knowledge for faster resolution"
        actions={
          staff && (
            <button type="button" className="gold-btn" onClick={() => setCreating(true)}>
              <Plus size={16} /> Create Article
            </button>
          )
        }
      />
      <div className="page-stack">
        <SearchInput
          className="kb-search"
          value={input}
          onChange={setInput}
          placeholder={readers ? 'Search articles, guides, runbooks…' : 'Search articles and guides…'}
          label={readers ? 'Search articles, guides, runbooks' : 'Search articles and guides'}
        />

        <KbCategoryTiles
          stats={stats.data}
          loading={stats.isLoading}
          active={activeTile}
          showRunbooks={readers}
          onSelect={(tile) => setValues({ type: activeTile === tile ? '' : KB_TILE_TYPES[tile] })}
        />
        {stats.isError && <p className="text-[10px] text-muted">Category counts are unavailable right now.</p>}

        {!searching && !filtered && (
          <Panel title="Popular Articles" subtitle="Most viewed and best rated published articles">
            {popular.isLoading && <LoadingState rows={4} label="Loading popular articles…" />}
            {popular.isError && <ErrorState error={popular.error} onRetry={() => void popular.refetch()} compact />}
            {popular.data && popular.data.content.length === 0 && (
              <EmptyState title="No published articles yet" icon={<BookOpen size={20} />} description={staff ? 'Create an article and publish it to see it here.' : undefined} />
            )}
            {popular.data && popular.data.content.length > 0 && (
              <ul className="kb-list" aria-label="Popular articles">
                {popular.data.content.map((article) => (
                  <li key={article.id}>
                    <ArticleRow article={article} />
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        )}

        {searching && readers && <RunbookMatches query={values.q.trim()} runbooks={runbooks} />}

        <Panel title={listTitle} subtitle={articles.data ? `${formatNumber(articles.data.totalElements)} article${articles.data.totalElements === 1 ? '' : 's'}` : undefined}>
          <div className="kb-toolbar">
            <FilterSelect label="Type" allLabel="All types" value={values.type} onChange={(type) => setValues({ type })} options={typeOptions} />
            <FilterSelect label="Category" allLabel="All categories" value={values.category} onChange={(category) => setValues({ category })} options={CATEGORIES} formatOption={titleCase} />
            <FilterSelect
              label="Service"
              allLabel="All services"
              value={values.serviceId}
              onChange={(serviceId) => setValues({ serviceId })}
              options={(services.data ?? []).map((service) => ({ value: String(service.id), label: service.name }))}
            />
            {staff && (
              <FilterSelect
                label="Status"
                allLabel="Any status"
                value={values.status}
                onChange={(status) => setValues({ status })}
                options={ARTICLE_STATUSES}
                formatOption={(status) => ARTICLE_STATUS_LABELS[status as ArticleStatus] ?? titleCase(status)}
              />
            )}
            <FilterSelect
              label="Sort"
              allLabel={searching ? 'Best match' : 'Most recent'}
              value={values.sort === 'recent' && !searching ? '' : values.sort}
              onChange={(value) => setValues({ sort: value })}
              options={SORT_OPTIONS.filter((option) => searching || option.value !== 'recent')}
            />
            {(filtered || values.sort) && (
              <button type="button" className="link-btn text-[11px]" onClick={() => setValues({ type: '', category: '', serviceId: '', status: '', sort: '' })}>
                Clear filters
              </button>
            )}
          </div>
          {articles.isLoading && <LoadingState rows={5} label="Loading articles…" />}
          {articles.isError && !articles.data && <ErrorState error={articles.error} onRetry={() => void articles.refetch()} />}
          {articles.data && articles.data.content.length === 0 && (
            <EmptyState
              title={searching ? `No articles match “${values.q}”` : filtered ? 'No articles match these filters' : 'No articles yet'}
              description={searching || filtered ? 'Try different keywords or clear the filters.' : staff ? 'Create the first article.' : undefined}
              icon={<BookOpen size={20} />}
            />
          )}
          {articles.data && articles.data.content.length > 0 && (
            <div className={articles.isPlaceholderData ? 'opacity-60' : ''}>
              <ul className="kb-list" aria-label="Articles">
                {articles.data.content.map((article) => (
                  <li key={article.id}>
                    <ArticleRow article={article} detailed showRelevance={searching} />
                  </li>
                ))}
              </ul>
              <Pagination
                page={articles.data.page}
                totalPages={articles.data.totalPages}
                totalElements={articles.data.totalElements}
                size={articles.data.size}
                onPageChange={setPage}
                label="articles"
              />
            </div>
          )}
        </Panel>
      </div>
      {creating && (
        <ArticleEditor
          article={null}
          defaultType={activeTile === 'faqs' ? 'FAQ' : activeTile === 'incidents' ? 'SOLUTION' : 'GUIDE'}
          onClose={() => setCreating(false)}
          onSaved={(saved) => navigate(`/knowledge/${saved.id}`)}
        />
      )}
    </>
  );
}
