import { ALERT_SOURCE_LABELS } from '../../lib/ops';
import type { AlertSeverity, AlertSourceKind, MatchCriteria } from '../../lib/opsTypes';
import { titleCase } from '../../lib/format';

export interface LabelRow {
  name: string;
  value: string;
}

const LABEL_NAME = /^[A-Za-z_][A-Za-z0-9_.-]{0,99}$/;

/** True when no criterion is set (the rule matches every alert). */
export function isEmptyMatch(match: MatchCriteria | null | undefined): boolean {
  if (!match) return true;
  return (
    !Object.keys(match.labels ?? {}).length &&
    !match.sources?.length &&
    !match.severities?.length &&
    !match.serviceIds?.length &&
    !match.ciIds?.length &&
    !match.sourceIds?.length &&
    !(match.titleContains ?? '').trim()
  );
}

/** Label rows → `labels` map (≤ 20). Returns an error message for invalid rows. */
export function labelsFromRows(rows: LabelRow[]): Record<string, string> | string {
  const labels: Record<string, string> = {};
  for (const row of rows) {
    const name = row.name.trim();
    const value = row.value.trim();
    if (!name && !value) continue;
    if (!LABEL_NAME.test(name)) return `“${name || '(empty)'}” is not a valid label name`;
    if (!value) return `Give a value for label “${name}” (use * for “present”)`;
    if (name in labels) return `Label “${name}” is listed twice`;
    labels[name] = value;
  }
  if (Object.keys(labels).length > 20) return 'At most 20 label matchers';
  return labels;
}

/** Drops empty criteria so the request carries only what is set (`null` = match every alert). */
export function normaliseMatch(match: MatchCriteria): MatchCriteria | null {
  const result: MatchCriteria = {};
  if (match.labels && Object.keys(match.labels).length) result.labels = match.labels;
  if (match.sources?.length) result.sources = match.sources;
  if (match.severities?.length) result.severities = match.severities;
  if (match.serviceIds?.length) result.serviceIds = match.serviceIds;
  if (match.ciIds?.length) result.ciIds = match.ciIds;
  if (match.sourceIds?.length) result.sourceIds = match.sourceIds;
  if (match.titleContains?.trim()) result.titleContains = match.titleContains.trim();
  return Object.keys(result).length ? result : null;
}

function labelMatcher(name: string, value: string): string {
  if (value === '*') return `${name} present`;
  if (value.includes('|')) return `${name} ∈ {${value.split('|').map((part) => part.trim()).join(', ')}}`;
  return `${name}=${value}`;
}

/** One-line description of a matcher ("Severity Critical, Major · labels job=backup · title contains “disk”"). */
export function describeMatch(
  match: MatchCriteria | null | undefined,
  names: { services?: Map<number, string>; cis?: Map<number, string>; sources?: Map<number, string> } = {},
): string {
  if (isEmptyMatch(match) || !match) return 'Every alert';
  const parts: string[] = [];
  if (match.severities?.length) parts.push(`Severity ${match.severities.map((severity) => titleCase(severity)).join(', ')}`);
  if (match.sources?.length) parts.push(`From ${match.sources.map((source) => ALERT_SOURCE_LABELS[source] ?? titleCase(source)).join(', ')}`);
  if (match.sourceIds?.length) parts.push(`Source ${match.sourceIds.map((id) => names.sources?.get(id) ?? `#${id}`).join(', ')}`);
  if (match.serviceIds?.length) parts.push(`Service ${match.serviceIds.map((id) => names.services?.get(id) ?? `#${id}`).join(', ')}`);
  if (match.ciIds?.length) parts.push(`CI ${match.ciIds.map((id) => names.cis?.get(id) ?? `#${id}`).join(', ')}`);
  const labels = Object.entries(match.labels ?? {});
  if (labels.length) parts.push(`Labels ${labels.map(([name, value]) => labelMatcher(name, value)).join(', ')}`);
  if (match.titleContains?.trim()) parts.push(`Title contains “${match.titleContains.trim()}”`);
  return parts.join(' · ');
}

/** "01:00–03:00 Mon–Fri" style window text for suppression rules. */
export function describeWindow(rule: { startsAt: string | null; endsAt: string | null; dailyStart: string | null; dailyEnd: string | null; daysOfWeek: number[] | null }, formatInstant: (iso: string) => string): string {
  const parts: string[] = [];
  if (rule.startsAt || rule.endsAt) parts.push(`${rule.startsAt ? formatInstant(rule.startsAt) : 'now'} → ${rule.endsAt ? formatInstant(rule.endsAt) : 'open-ended'}`);
  if (rule.dailyStart && rule.dailyEnd) parts.push(`daily ${rule.dailyStart}–${rule.dailyEnd}`);
  if (rule.daysOfWeek?.length && rule.daysOfWeek.length < 7) {
    const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    parts.push([...rule.daysOfWeek].sort((a, b) => a - b).map((day) => days[day - 1] ?? String(day)).join(', '));
  }
  return parts.length ? parts.join(' · ') : 'Always';
}

/** Editable form of a matcher (label rows, chosen CIs with names). */
export interface MatchDraft {
  labels: LabelRow[];
  sources: AlertSourceKind[];
  severities: AlertSeverity[];
  serviceIds: number[];
  cis: { id: number; name: string }[];
  sourceIds: number[];
  titleContains: string;
}

export function draftFromMatch(match: MatchCriteria | null | undefined): MatchDraft {
  return {
    labels: Object.entries(match?.labels ?? {}).map(([name, value]) => ({ name, value })),
    sources: match?.sources ?? [],
    severities: match?.severities ?? [],
    serviceIds: match?.serviceIds ?? [],
    cis: (match?.ciIds ?? []).map((id) => ({ id, name: `CI #${id}` })),
    sourceIds: match?.sourceIds ?? [],
    titleContains: match?.titleContains ?? '',
  };
}

/** Draft → request matcher (`null` = every alert), or an error message. */
export function matchFromDraft(draft: MatchDraft): MatchCriteria | null | string {
  const labels = labelsFromRows(draft.labels);
  if (typeof labels === 'string') return labels;
  if (draft.titleContains.trim().length > 200) return 'The title text is longer than 200 characters';
  return normaliseMatch({
    labels,
    sources: draft.sources,
    severities: draft.severities,
    serviceIds: draft.serviceIds,
    ciIds: draft.cis.map((ci) => ci.id),
    sourceIds: draft.sourceIds,
    titleContains: draft.titleContains,
  });
}
