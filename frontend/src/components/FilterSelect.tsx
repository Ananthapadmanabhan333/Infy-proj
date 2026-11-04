import { ChevronDown } from 'lucide-react';

export type FilterOption = string | { value: string; label: string };

interface FilterSelectProps {
  /** Shown when nothing is selected ("Priority") and used as the accessible name. */
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: FilterOption[];
  /** Label of the "no filter" option; defaults to `label`. */
  allLabel?: string;
  /** Map raw option values to display text (e.g. STATUS_LABELS). */
  formatOption?: (value: string) => string;
  disabled?: boolean;
  className?: string;
}

/** Compact filter dropdown ("Priority ⌄") — a styled native select, so it is keyboard and screen-reader friendly. */
export function FilterSelect({ label, value, onChange, options, allLabel, formatOption, disabled, className = '' }: FilterSelectProps) {
  const normalised = options.map((option) =>
    typeof option === 'string' ? { value: option, label: formatOption ? formatOption(option) : option } : option,
  );
  return (
    <label className={`filter-select ${value ? 'active' : ''} ${className}`.trim()}>
      <span className="sr-only">{label}</span>
      <select value={value} onChange={(event) => onChange(event.target.value)} disabled={disabled} aria-label={label}>
        <option value="">{allLabel ?? label}</option>
        {normalised.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <ChevronDown size={14} aria-hidden="true" />
    </label>
  );
}
