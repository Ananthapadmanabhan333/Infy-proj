import { toApiError } from '../../lib/api';
import { titleCase } from '../../lib/format';
import type { Confidence, ConditionOperator, OperationalInsight, Severity } from '../../lib/types';

/** User-facing message for a failed AI call (429 = per-user AI rate limit, spec §7). */
export function aiErrorMessage(error: unknown): string {
  const apiError = toApiError(error);
  if (apiError.status === 429) return 'SA AI rate limit reached — wait a minute and try again.';
  if (apiError.status === 403) return 'Your role cannot use this SA AI feature.';
  if (apiError.status === 404) return 'This SA AI feature is not available on the server yet.';
  return apiError.message;
}

const FEATURE_LABELS: Record<string, string> = {
  ROOT_CAUSE: 'Root cause',
  RESOLUTION: 'Resolution steps',
  ASK: 'Question',
  INSIGHTS: 'Operational insights',
  WORKFLOW_SUGGESTION: 'Workflow draft',
  SUMMARIZE: 'Summary',
  SUMMARY: 'Summary',
  CLASSIFY: 'Classification',
  CLASSIFICATION: 'Classification',
  DUPLICATES: 'Duplicate check',
  KNOWLEDGE: 'Knowledge match',
  FORECAST: 'Incident forecast',
};

/** "ROOT_CAUSE" → "Root cause"; unknown features are title-cased. */
export function featureLabel(feature: string): string {
  return FEATURE_LABELS[feature] ?? titleCase(feature);
}

/** Incident numbers as used in AI interaction subjects ("INC-7846"). */
export function isIncidentNumber(subject: string | null | undefined): subject is string {
  return Boolean(subject && /^INC-\d+$/i.test(subject.trim()));
}

const SEVERITY_RANK: Record<Severity, number> = { CRITICAL: 0, WARNING: 1, INFO: 2, SUCCESS: 3 };
const CONFIDENCE_RANK: Record<Confidence, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 };

/** "Insight of the Day": the most severe insight, ties broken by confidence, then API order. */
export function topInsight(insights: OperationalInsight[] | null | undefined): OperationalInsight | null {
  if (!insights?.length) return null;
  return insights
    .map((insight, index) => ({ insight, index }))
    .sort(
      (a, b) =>
        (SEVERITY_RANK[a.insight.severity] ?? 9) - (SEVERITY_RANK[b.insight.severity] ?? 9) ||
        (CONFIDENCE_RANK[a.insight.confidence] ?? 9) - (CONFIDENCE_RANK[b.insight.confidence] ?? 9) ||
        a.index - b.index,
    )[0].insight;
}

/** 24 hourly buckets (0–23), missing hours filled with 0. */
export function hourlySeries(hourly: { hour: number; count: number }[] | null | undefined): number[] {
  const series = Array.from({ length: 24 }, () => 0);
  hourly?.forEach(({ hour, count }) => {
    if (hour >= 0 && hour < 24) series[hour] += count;
  });
  return series;
}

const hourLabel = (hour: number) => `${String(hour).padStart(2, '0')}:00`;

/** Busiest hour of day, e.g. "Peak 10:00–11:00 (42 incidents)"; null without data. */
export function peakLabel(series: number[]): string | null {
  const max = Math.max(0, ...series);
  if (max === 0) return null;
  const hour = series.indexOf(max);
  return `Peak ${hourLabel(hour)}–${hourLabel((hour + 1) % 24)} (${max} incident${max === 1 ? '' : 's'})`;
}

export const CONFIDENCE_LABELS: Record<Confidence, string> = { HIGH: 'High confidence', MEDIUM: 'Medium confidence', LOW: 'Low confidence' };
export const CONFIDENCE_TONES: Record<Confidence, 'success' | 'warning' | 'neutral'> = { HIGH: 'success', MEDIUM: 'warning', LOW: 'neutral' };

export const OPERATOR_LABELS: Record<ConditionOperator, string> = {
  EQUALS: 'is',
  NOT_EQUALS: 'is not',
  IN: 'is one of',
  CONTAINS: 'contains',
  GREATER_THAN: 'is greater than',
  LESS_THAN: 'is less than',
};
