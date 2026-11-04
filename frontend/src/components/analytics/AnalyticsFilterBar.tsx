import { CalendarRange, ChevronDown } from 'lucide-react';
import { PERIOD_OPTIONS, type PeriodDays } from '../../lib/constants';
import type { DepartmentDto } from '../../lib/types';
import { FilterSelect } from '../FilterSelect';
import type { AnalyticsFilterState } from './useAnalyticsFilters';

interface AnalyticsFilterBarProps {
  state: AnalyticsFilterState;
  departments: DepartmentDto[] | undefined;
  departmentsUnavailable?: boolean;
  /** Called when "Custom range…" is picked (opens the Custom tab). */
  onCustom: () => void;
}

/** Date range (7/30/90 days or custom) and department filters shared by every analytics tab. */
export function AnalyticsFilterBar({ state, departments, departmentsUnavailable, onCustom }: AnalyticsFilterBarProps) {
  const options = (departments ?? []).map((department) => ({ value: String(department.id), label: department.name }));
  // Keep a selected department visible even before the list has loaded.
  if (state.departmentId && !options.some((option) => option.value === String(state.departmentId))) {
    options.push({ value: String(state.departmentId), label: `Department #${state.departmentId}` });
  }
  return (
    <div className="an-filters">
      <label className="period-select">
        <CalendarRange size={15} aria-hidden="true" />
        <span className="sr-only">Date range</span>
        <select
          aria-label="Date range"
          value={state.custom ? 'custom' : String(state.days)}
          onChange={(event) => {
            if (event.target.value === 'custom') onCustom();
            else state.setPeriod(Number(event.target.value) as PeriodDays);
          }}
        >
          {PERIOD_OPTIONS.map((days) => (
            <option key={days} value={days}>
              Last {days} Days
            </option>
          ))}
          <option value="custom">{state.custom ? state.rangeLabel : 'Custom range…'}</option>
        </select>
        <ChevronDown size={14} aria-hidden="true" />
      </label>
      <FilterSelect
        label="Department"
        allLabel="All departments"
        value={state.departmentId ? String(state.departmentId) : ''}
        onChange={(value) => state.setDepartment(value ? Number(value) : null)}
        options={options}
        disabled={departmentsUnavailable && !options.length}
      />
    </div>
  );
}
