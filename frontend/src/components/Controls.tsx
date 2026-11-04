import { ChevronDown, Clock3 } from 'lucide-react';
import { PERIOD_OPTIONS, type PeriodDays } from '../lib/constants';

/** Replaces the prototype's static "Last 7 Days" button with a real 7/30/90 selector. */
export function PeriodSelector({ value, onChange }: { value: PeriodDays; onChange: (days: PeriodDays) => void }) {
  return (
    <label className="period-select">
      <Clock3 size={15} />
      <span className="sr-only">Reporting period</span>
      <select value={value} onChange={(event) => onChange(Number(event.target.value) as PeriodDays)}>
        {PERIOD_OPTIONS.map((days) => (
          <option key={days} value={days}>
            Last {days} Days
          </option>
        ))}
      </select>
      <ChevronDown size={14} />
    </label>
  );
}

// Tabs moved to ./Tabs (Release 2 segmented style); re-exported for existing imports.
export { Tabs, type TabItem } from './Tabs';

export function Toggle({
  checked,
  onChange,
  label,
  disabled,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  label: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      className="toggle"
      disabled={disabled}
      onClick={(event) => {
        event.stopPropagation();
        onChange(!checked);
      }}
    />
  );
}
