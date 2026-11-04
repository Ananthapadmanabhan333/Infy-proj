import { Check } from 'lucide-react';

export interface StepperStep {
  label: string;
  /** e.g. a timestamp under the label. */
  caption?: string | null;
}

interface StepperProps {
  steps: (string | StepperStep)[];
  /** Index of the current step (0-based). Earlier steps render as done. */
  current: number;
  /** When true the current step also renders as done (e.g. Resolved reached). */
  complete?: boolean;
  label?: string;
}

/** Horizontal progress timeline (reference incident detail: Created → Assigned → In Progress → Resolved). */
export function Stepper({ steps, current, complete = false, label = 'Progress' }: StepperProps) {
  const items = steps.map((step) => (typeof step === 'string' ? { label: step } : step));
  return (
    <ol className="stepper" aria-label={label}>
      {items.map((step, index) => {
        const done = index < current || (complete && index === current);
        const isCurrent = index === current && !complete;
        return (
          <li key={step.label} className={done ? 'done' : isCurrent ? 'current' : ''} aria-current={isCurrent ? 'step' : undefined}>
            <span className="step-dot" aria-hidden="true">
              {done && <Check size={10} strokeWidth={3} color="#1b140b" />}
            </span>
            <b>{step.label}</b>
            {step.caption && <small>{step.caption}</small>}
            <span className="sr-only">{done ? ' (done)' : isCurrent ? ' (current)' : ' (upcoming)'}</span>
          </li>
        );
      })}
    </ol>
  );
}
