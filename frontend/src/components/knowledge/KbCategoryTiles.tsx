import { Link } from 'react-router-dom';
import { BookOpen, CircleHelp, ClipboardList, Siren, type LucideIcon } from 'lucide-react';
import { formatNumber } from '../../lib/format';
import type { KnowledgeStats } from '../../lib/types';

export type KbTile = 'incidents' | 'guides' | 'faqs';

/** Type filter (`?type=`) applied by each article tile (§9: Incidents = SOLUTION + KNOWN_ERROR). */
export const KB_TILE_TYPES: Record<KbTile, string> = {
  incidents: 'SOLUTION,KNOWN_ERROR',
  guides: 'GUIDE',
  faqs: 'FAQ',
};

/** Which tile a `?type=` value belongs to (`/solutions` redirects to `?type=SOLUTION`). */
export function tileForType(type: string): KbTile | null {
  if (!type) return null;
  if (type === KB_TILE_TYPES.incidents || type === 'SOLUTION' || type === 'KNOWN_ERROR') return 'incidents';
  if (type === 'GUIDE') return 'guides';
  if (type === 'FAQ') return 'faqs';
  return null;
}

interface KbCategoryTilesProps {
  stats: KnowledgeStats | undefined;
  loading: boolean;
  active: KbTile | null;
  onSelect: (tile: KbTile) => void;
  /** Runbooks are READERS-only; the tile is hidden for other roles. */
  showRunbooks: boolean;
}

function count(stats: KnowledgeStats | undefined, ...types: (keyof KnowledgeStats['countsByType'])[]): number | null {
  if (!stats?.countsByType) return null;
  return types.reduce((total, type) => total + (stats.countsByType[type] ?? 0), 0);
}

function caption(value: number | null, loading: boolean, noun: string) {
  if (value === null) return loading ? '…' : '—';
  return `${formatNumber(value)} ${noun}${value === 1 ? '' : 's'}`;
}

/** The four category tiles: Incidents, Runbooks, Guides, FAQs with counts; article tiles act as type filters. */
export function KbCategoryTiles({ stats, loading, active, onSelect, showRunbooks }: KbCategoryTilesProps) {
  const tiles: { id: KbTile; label: string; icon: LucideIcon; value: number | null }[] = [
    { id: 'incidents', label: 'Incidents', icon: Siren, value: count(stats, 'SOLUTION', 'KNOWN_ERROR') },
    { id: 'guides', label: 'Guides', icon: BookOpen, value: count(stats, 'GUIDE') },
    { id: 'faqs', label: 'FAQs', icon: CircleHelp, value: count(stats, 'FAQ') },
  ];
  const [incidents, ...rest] = tiles;

  const renderTile = ({ id, label, icon: Icon, value }: (typeof tiles)[number]) => (
    <button key={id} type="button" className={`kb-tile ${active === id ? 'selected' : ''}`} aria-pressed={active === id} onClick={() => onSelect(id)}>
      <span className="icon-tile" aria-hidden="true">
        <Icon size={18} />
      </span>
      <span>
        <b>{label}</b>
        <small>{caption(value, loading, 'article')}</small>
      </span>
    </button>
  );

  return (
    <div className="kb-tiles" role="group" aria-label="Knowledge categories">
      {renderTile(incidents)}
      {showRunbooks && (
        <Link to="/runbooks" className="kb-tile">
          <span className="icon-tile" aria-hidden="true">
            <ClipboardList size={18} />
          </span>
          <span>
            <b>Runbooks</b>
            <small>{caption(stats ? stats.runbookCount ?? null : null, loading, 'runbook')}</small>
          </span>
        </Link>
      )}
      {rest.map(renderTile)}
    </div>
  );
}
