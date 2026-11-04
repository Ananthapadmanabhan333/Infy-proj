import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle, FlaskConical, Timer } from 'lucide-react';
import { useSimulateElapsed } from '../../api/admin';
import { PayrollScenarioButton } from '../../components/dashboard/ScenarioRunner';
import { TextField } from '../../components/FormField';
import { Panel } from '../../components/Panel';
import { useToast } from '../../components/Toast';
import { applyFieldErrors, getJson } from '../../lib/api';
import { simulateElapsedSchema, type SimulateElapsedValues } from '../../lib/schemas';
import type { IncidentSummary, PageResponse } from '../../lib/types';

/** Settings → Demo Tools (SETTINGS). Field labels and the button name are used by the end-to-end tests. */
export function DemoToolsTab() {
  const simulate = useSimulateElapsed();
  const toast = useToast();
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<SimulateElapsedValues>({ resolver: zodResolver(simulateElapsedSchema), defaultValues: { incidentNumber: '', minutes: 60 } });

  const onSubmit = handleSubmit(async (values) => {
    const number = values.incidentNumber.trim().toUpperCase();
    let incident: IncidentSummary | undefined;
    try {
      const page = await getJson<PageResponse<IncidentSummary>>('/incidents', { search: number, size: 5 });
      incident = page.content.find((entry) => entry.number.toUpperCase() === number);
    } catch (error) {
      toast.error(error);
      return;
    }
    if (!incident) {
      setError('incidentNumber', { message: `${number} was not found` });
      return;
    }
    if (!incident.demo) {
      setError('incidentNumber', { message: `${number} is not a demo incident — simulation is only allowed on demo records` });
      return;
    }
    const target = incident;
    simulate.mutate(
      { incidentId: target.id, minutes: values.minutes },
      {
        onSuccess: () => {
          toast.success(`Simulated ${values.minutes} minutes on ${target.number}`, { action: { label: `Open ${target.number}`, to: `/incidents/${target.id}` } });
          reset({ incidentNumber: '', minutes: values.minutes });
        },
        onError: (error) => {
          if (!applyFieldErrors(error, setError)) toast.error(error);
        },
      },
    );
  });

  return (
    <div className="grid gap-[14px] xl:grid-cols-2">
      <Panel
        title={
          <span className="inline-flex items-center gap-2">
            <FlaskConical size={15} className="gold-text" /> Payroll outage scenario
          </span>
        }
        subtitle="Runs a realistic multi-report outage through the real engines"
      >
        <p className="mb-3 text-[11px] leading-relaxed text-muted">
          Creates several related payroll reports, which exercise classification, duplicate detection (parent/child), assignment, SLA timers and
          workflows. All records are flagged DEMO.
        </p>
        <PayrollScenarioButton className="gold-btn" />
      </Panel>
      <Panel
        title={
          <span className="inline-flex items-center gap-2">
            <Timer size={15} className="gold-text" /> Simulate elapsed time
          </span>
        }
        subtitle="Shift a demo incident's SLA clock forward"
      >
        <div className="callout red mb-3">
          <AlertTriangle size={16} />
          <div>
            <b>Demo simulation.</b> This moves the SLA clock of a <b>demo</b> incident only, so at-risk and breach behaviour can be shown without
            waiting. Each use is audited and recorded as a SIMULATED SLA event.
          </div>
        </div>
        <form onSubmit={onSubmit} noValidate>
          <div className="form-row">
            <TextField label="Demo incident number" placeholder="INC-7001" error={errors.incidentNumber?.message} {...register('incidentNumber')} />
            <TextField label="Minutes to advance" type="number" min={1} error={errors.minutes?.message} {...register('minutes', { valueAsNumber: true })} />
          </div>
          <button type="submit" className="gold-btn compact" disabled={isSubmitting || simulate.isPending}>
            <Timer size={13} /> {simulate.isPending ? 'Simulating…' : 'Simulate elapsed time'}
          </button>
        </form>
      </Panel>
    </div>
  );
}
