import { Link } from 'react-router-dom';
import { Bug, CircleHelp, FileText, Wrench, type LucideIcon } from 'lucide-react';
import { titleCase } from '../../lib/format';
import type { ArticleSummary, ArticleType } from '../../lib/types';
import { DemoBadge, StatusBadge } from '../Badge';
import { RatingSummary } from '../StarRating';

export const ARTICLE_TYPE_ICONS: Record<ArticleType, LucideIcon> = {
  GUIDE: FileText,
  SOLUTION: Wrench,
  KNOWN_ERROR: Bug,
  FAQ: CircleHelp,
};

export const ARTICLE_TYPE_LABELS: Record<ArticleType, string> = {
  GUIDE: 'Guide',
  SOLUTION: 'Solution',
  KNOWN_ERROR: 'Known error',
  FAQ: 'FAQ',
};

/**
 * One knowledge article in a list: icon, title, rating (average + count) and read time — the reference
 * "Popular Articles" row. `detailed` adds the number / type / category / service line; unpublished articles
 * (visible to staff only) carry their status badge.
 */
export function ArticleRow({ article, detailed = false, showRelevance = false }: { article: ArticleSummary; detailed?: boolean; showRelevance?: boolean }) {
  const Icon = ARTICLE_TYPE_ICONS[article.articleType] ?? FileText;
  const unpublished = article.status !== 'PUBLISHED';
  return (
    <Link to={`/knowledge/${article.id}`} className="kb-row" aria-label={`${article.title}${unpublished ? ` (${titleCase(article.status)})` : ''}`}>
      <span className="kb-row-icon" aria-hidden="true">
        <Icon size={15} />
      </span>
      <span className="kb-row-main">
        <b title={article.title}>{article.title}</b>
        {(detailed || unpublished) && (
          <small>
            {detailed && (
              <>
                <span>{article.number}</span>
                <span>{ARTICLE_TYPE_LABELS[article.articleType] ?? titleCase(article.articleType)}</span>
                <span>{titleCase(article.category)}</span>
                {article.serviceName && <span>{article.serviceName}</span>}
              </>
            )}
            {unpublished && <StatusBadge status={article.status} />}
            <DemoBadge demo={article.demo} />
            {showRelevance && article.score !== null && <span className="gold-text">relevance {article.score.toFixed(2)}</span>}
          </small>
        )}
      </span>
      <span className="kb-row-meta">
        <RatingSummary value={article.averageRating} count={article.ratingCount} />
        <span className="read">{article.readMinutes} min read</span>
      </span>
    </Link>
  );
}
