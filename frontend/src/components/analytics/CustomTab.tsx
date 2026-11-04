import { useState, type FormEvent } from 'react';
import { CalendarRange, RotateCcw } from 'lucide-react';
import type { AnalyticsOverview, DepartmentDto } from '../../lib/types';
import { isoDate } from '../dashboard/Widgets';
import { SelectField, TextField } from '../FormField';
import { Panel } from '../Panel';
import { AnalyticsKpis } from './OperationsTab';
import { AskAnalytics } from './AskAnalytics';
import { DEFAULT_ANALYTICS_DAYS, type AnalyticsFilterState } from './useAnalyticsFilters';

export const MAX_RANGE_DAYS = 366;

const dayDiff = (from: string, to: string) => Math.round((new Date(`${to}T00:00:00`).getTime() - new Date(`${from}T00:00:00`).getTime()) / 86_400_000);

export function validateRange(from: string, to: string, today: string = isoDate(new Date())): { from?: string; to?: string } {
  const errors: { from?: string; to?: string } = {};
  if (!from) errors.from = 'Choose a start date';
  if (!to) errors.to = 'Choose an end date';
  if (to && to > today) errors.to = 'The end date cannot be in the future';
  if (from && to && from > to) errors.to = 'The end date must be on or after the start date';
  if (from && to && !errors.to && dayDiff(from, to) + 1 > MAX_RANGE_DAYS) errors.to = `Choose a range of at most ${MAX_RANGE_DAYS} days`;
  return errors;
}

/** Suggested SA AI questions phrased for the active period ("last N days" is what the engine understands). */
export function suggestedQuestions(state: AnalyticsFilterState, today: string = isoDate(new Date())): string[] {
  let window = `in the last ${state.days} days`;
  if (state.custom) {
    window = state.from && (!state.to || state.to >= today) ? `in the last ${Math.max(1, dayDiff(state.from, today) + 1)} days` : 'recently';
  }
  return [
    `How many critical incidents ${window}?`,
    `Which services had the most incidents ${window}?`,
    `What is the SLA compliance ${window}?`,
    'What is the incident forecast for next week?',
  ];
}

function RangeForm({ state, departments }: { state: AnalyticsFilterState; departments: DepartmentDto[] }) {
  const today = isoDate(new Date());
  const [from, setFrom] = useState(state.from);
  const [to, setTo] = useState(state.to || (state.from ? today : ''));
  const [department, setDepartment] = useState(state.departmentId ? String(state.departmentId) : '');
  const [submitted, setSubmitted] = useState(false);
  const errors = submitted ? validateRange(from, to, today) : {};

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    const problems = validateRange(from, to, today);
    if (problems.from || problems.to) return;
    state.setRange(from, to, department ? Number(department) : null);
  };

  return (
    <form className="an-range-form" onSubmit={onSubmit} noValidate aria-label="Custom report range">
      <TextField label="From" type="date" value={from} max={to || today} onChange={(event) => setFrom(event.target.value)} error={errors.from} required />
      <TextField label="To" type="date" value={to} min={from || undefined} max={today} onChange={(event) => setTo(event.target.value)} error={errors.to} required />
      <SelectField label="Department" value={department} onChange={(event) => setDepartment(event.target.value)}>
        <option value="">All departments</option>
        {departments.map((entry) => (
          <option key={entry.id} value={entry.id}>
            {entry.name}
          </option>
        ))}
      </SelectField>
      <div className="an-range-actions">
        <button type="submit" className="gold-btn">
          <CalendarRange size={15} aria-hidden="true" /> Apply to all tabs
        </button>
        {state.custom && (
          <button type="button" className="subtle-btn" onClick={() => state.setPeriod(DEFAULT_ANALYTICS_DAYS)}>
            <RotateCcw size={14} aria-hidden="true" /> Back to last {DEFAULT_ANALYTICS_DAYS} days
          </button>
        )}
      </div>
    </form>
  );
}

/** Custom tab: from/to + department applied to every tab and the export, and grounded natural-language questions. */
export function CustomTab({ state, departments, data }: { state: AnalyticsFilterState; departments: DepartmentDto[]; data: AnalyticsOverview | undefined }) {
  const departmentName = departments.find((entry) => entry.id === state.departmentId)?.name;
  return (
    <>
      <Panel title="Custom Report Range" subtitle={`Applied: ${state.rangeLabel}${departmentName ? ` · ${departmentName}` : ' · all departments'} — used by every tab and by Export`}>
        {/* Re-mount the form when the URL filters change elsewhere (tab bar, back/forward). */}
        <RangeForm key={`${state.from}|${state.to}|${state.departmentId ?? ''}|${state.days}`} state={state} departments={departments} />
      </Panel>
      {data && <AnalyticsKpis data={data} rangeLabel={state.rangeLabel} />}
      <Panel
        title="Ask SA AI About This Data"
        subtitle="Answers are computed from live SA records and labelled by source. Mention a period such as “last 30 days” in your question."
      >
        <AskAnalytics suggestions={suggestedQuestions(state)} />
      </Panel>
    </>
  );
}
