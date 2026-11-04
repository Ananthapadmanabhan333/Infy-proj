import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { ChevronDown, Download, FileSpreadsheet, FileText } from 'lucide-react';
import { useAnalyticsExport } from '../../api/analytics';
import { useClickOutside } from '../../hooks/useClickOutside';
import type { AnalyticsFilters } from '../../lib/types';
import { useToast } from '../Toast';

const FORMATS = [
  { format: 'csv' as const, label: 'CSV', description: 'Incident records for spreadsheets', icon: FileSpreadsheet },
  { format: 'pdf' as const, label: 'PDF report', description: 'KPIs, priorities, services, departments and agents', icon: FileText },
];

/** Gold "Export" menu (CSV / PDF) — downloads GET /analytics/export with the active filters (audited server side). */
export function ExportMenu({ filters, scopeLabel }: { filters: AnalyticsFilters; scopeLabel: string }) {
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLDivElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const exporter = useAnalyticsExport();
  const toast = useToast();
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(anchor, open, close);

  useEffect(() => {
    if (open) menu.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus();
  }, [open]);

  const run = (format: 'csv' | 'pdf') => {
    setOpen(false);
    exporter.mutate(
      { format, filters },
      {
        onSuccess: () => toast.success(`${format === 'pdf' ? 'PDF report' : 'CSV export'} downloaded · ${scopeLabel}`),
        onError: (error) => toast.error(error),
      },
    );
  };

  const onMenuKey = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    event.preventDefault();
    const items = [...(menu.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? [])];
    const index = items.findIndex((item) => item === document.activeElement);
    const next = event.key === 'ArrowDown' ? (index + 1) % items.length : (index - 1 + items.length) % items.length;
    items[next]?.focus();
  };

  return (
    <div className="dropdown-anchor" ref={anchor}>
      <button type="button" className="gold-btn" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((value) => !value)} disabled={exporter.isPending}>
        <Download size={15} aria-hidden="true" /> {exporter.isPending ? 'Exporting…' : 'Export'} <ChevronDown size={14} aria-hidden="true" />
      </button>
      {open && (
        <div className="dropdown an-export-menu" role="menu" aria-label="Export format" ref={menu} onKeyDown={onMenuKey}>
          <div className="dropdown-group">{scopeLabel}</div>
          {FORMATS.map(({ format, label, description, icon: Icon }) => (
            <button key={format} type="button" role="menuitem" className="dropdown-item" onClick={() => run(format)}>
              <Icon size={16} className="gold-text mt-0.5 shrink-0" aria-hidden="true" />
              <span>
                <b>{label}</b>
                <small>{description}</small>
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
