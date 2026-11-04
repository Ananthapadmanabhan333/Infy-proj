import { ChevronLeft, ChevronRight } from 'lucide-react';
import { formatNumber } from '../lib/format';

interface PaginationProps {
  page: number; // zero-based
  totalPages: number;
  totalElements: number;
  size: number;
  onPageChange: (page: number) => void;
  /** Noun after the total ("Showing 1–20 of 57 incidents"); omit for "Showing 1–5 of 1,248". */
  label?: string;
  /** How many consecutive page numbers to show around the current page (default 5). */
  window?: number;
}

/**
 * Page numbers to render (zero-based) with 'gap' markers, always including the first and last page.
 * E.g. page 0 of 250 → [0, 1, 2, 3, 4, 'gap', 249] ("1 2 3 4 5 … 250").
 */
export function pageWindow(page: number, totalPages: number, windowSize = 5): (number | 'gap')[] {
  if (totalPages <= 0) return [];
  if (totalPages <= windowSize + 2) return Array.from({ length: totalPages }, (_, i) => i);
  const half = Math.floor(windowSize / 2);
  let start = Math.max(0, page - half);
  let end = start + windowSize - 1;
  if (end > totalPages - 1) {
    end = totalPages - 1;
    start = Math.max(0, end - windowSize + 1);
  }
  const pages = new Set<number>([0, totalPages - 1]);
  for (let p = start; p <= end; p += 1) pages.add(p);
  const sorted = [...pages].sort((a, b) => a - b);
  const result: (number | 'gap')[] = [];
  sorted.forEach((p, i) => {
    if (i > 0 && p - sorted[i - 1] > 1) result.push('gap');
    result.push(p);
  });
  return result;
}

/** "Showing x–y of N" + numbered pages with previous / next (reference incident list). */
export function Pagination({ page, totalPages, totalElements, size, onPageChange, label, window: windowSize = 5 }: PaginationProps) {
  if (totalElements === 0) return null;
  const from = page * size + 1;
  const to = Math.min(totalElements, (page + 1) * size);
  return (
    <div className="pagination">
      <span aria-live="polite">
        Showing {formatNumber(from)}–{formatNumber(to)} of {formatNumber(totalElements)}
        {label ? ` ${label}` : ''}
      </span>
      {totalPages > 1 && (
        <nav aria-label="Pagination" className="pages">
          <button type="button" onClick={() => onPageChange(page - 1)} disabled={page <= 0} aria-label="Previous page">
            <ChevronLeft size={14} />
          </button>
          {pageWindow(page, totalPages, windowSize).map((entry, i) =>
            entry === 'gap' ? (
              <span key={`gap-${i}`} className="gap" aria-hidden="true">
                …
              </span>
            ) : (
              <button
                key={entry}
                type="button"
                onClick={() => onPageChange(entry)}
                aria-current={entry === page ? 'page' : undefined}
                aria-label={`Page ${entry + 1}`}
              >
                {formatNumber(entry + 1)}
              </button>
            ),
          )}
          <button type="button" onClick={() => onPageChange(page + 1)} disabled={page >= totalPages - 1} aria-label="Next page">
            <ChevronRight size={14} />
          </button>
        </nav>
      )}
    </div>
  );
}
