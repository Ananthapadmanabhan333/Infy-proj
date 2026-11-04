import type { KeyboardEvent, ReactNode } from 'react';
import { formatNumber } from '../lib/format';

export interface TabItem<T extends string> {
  id: T;
  label: string;
  /** Shown as "Label (12)"; null/undefined hides the count. */
  count?: number | null;
  hidden?: boolean;
}

interface TabsProps<T extends string> {
  tabs: TabItem<T>[];
  value: T;
  onChange: (id: T) => void;
  /** Right-hand content (e.g. a gold action button). */
  children?: ReactNode;
  /** Accessible name of the tab list. */
  label: string;
  /** Lighter variant for tabs inside a panel. */
  inner?: boolean;
}

/** Segmented tabs with counts ("All (1,248) · Open (246) · In Progress (134)"). Arrow keys move between tabs. */
export function Tabs<T extends string>({ tabs, value, onChange, children, label, inner }: TabsProps<T>) {
  const visible = tabs.filter((tab) => !tab.hidden);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft' && event.key !== 'Home' && event.key !== 'End') return;
    event.preventDefault();
    const index = visible.findIndex((tab) => tab.id === value);
    let next = index;
    if (event.key === 'ArrowRight') next = (index + 1) % visible.length;
    if (event.key === 'ArrowLeft') next = (index - 1 + visible.length) % visible.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = visible.length - 1;
    const target = visible[next];
    if (target) {
      onChange(target.id);
      const buttons = event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]');
      buttons[next]?.focus();
    }
  };

  return (
    <div className={`tabs ${inner ? 'inner' : ''}`}>
      <div role="tablist" aria-label={label} className="tab-list" onKeyDown={onKeyDown}>
        {visible.map((tab) => {
          const selected = tab.id === value;
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={selected}
              tabIndex={selected ? 0 : -1}
              className={selected ? 'selected' : ''}
              onClick={() => onChange(tab.id)}
            >
              {tab.label}
              {tab.count !== undefined && tab.count !== null && <span className="tab-count">({formatNumber(tab.count)})</span>}
            </button>
          );
        })}
      </div>
      {children && <div className="tabs-end flex flex-wrap items-center gap-2">{children}</div>}
    </div>
  );
}
