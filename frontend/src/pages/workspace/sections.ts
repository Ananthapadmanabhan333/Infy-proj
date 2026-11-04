/**
 * Generic rendering of My Work / portal sections added by later modules (problems, changes, alerts, onCallShifts,
 * actionItems…): any `{ total, items }` value the page does not know is shown with a title, its count and item links.
 */
export interface GenericSection {
  key: string;
  title: string;
  total: number;
  items: GenericItem[];
}

export interface GenericItem {
  key: string;
  reference: string | null;
  label: string;
  meta: string | null;
  to: string | null;
}

type Raw = Record<string, unknown>;

const KNOWN_TITLES: Record<string, string> = {
  problems: 'Problems',
  changes: 'Changes',
  alerts: 'Alerts',
  onCallShifts: 'On-call shifts',
  actionItems: 'Action items',
  correctiveActions: 'Corrective actions',
  plannedChanges: 'Planned changes',
  deployments: 'Deployments',
  assets: 'Assets',
  reviews: 'Post-incident reviews',
};

/** Detail routes of the planned modules (RELEASE3_SPEC §3). */
const DETAIL_ROUTES: Record<string, (id: string) => string> = {
  problems: (id) => `/problems/${id}`,
  changes: (id) => `/changes/${id}`,
  alerts: (id) => `/alerts/${id}`,
  deployments: (id) => `/deployments/${id}`,
  assets: (id) => `/assets/${id}`,
  reviews: (id) => `/reviews/${id}`,
  incidents: (id) => `/incidents/${id}`,
};

export function sectionTitle(key: string): string {
  if (KNOWN_TITLES[key]) return KNOWN_TITLES[key];
  const words = key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .trim()
    .toLowerCase();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : key;
}

function asText(value: unknown): string | null {
  if (typeof value === 'string' && value.trim()) return value.trim();
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  return null;
}

function isSection(value: unknown): value is { total?: unknown; items: unknown[] } {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value) && Array.isArray((value as Raw).items));
}

/** Only in-app routes are linked (never absolute or protocol URLs from data). */
function safeRoute(value: unknown): string | null {
  const text = asText(value);
  return text && text.startsWith('/') && !text.startsWith('//') ? text : null;
}

function toItem(sectionKey: string, entry: unknown, index: number): GenericItem {
  const record = entry && typeof entry === 'object' ? (entry as Raw) : {};
  const id = asText(record.id);
  const reference = asText(record.number) ?? asText(record.referenceNumber) ?? asText(record.code);
  const label = asText(record.title) ?? asText(record.name) ?? asText(record.summary) ?? asText(record.displayName) ?? reference ?? `Item ${index + 1}`;
  const meta = asText(record.status) ?? asText(record.priority) ?? asText(record.severity) ?? null;
  // Problem corrective actions (R3 BE-2) link to their problem.
  const problemId = asText(record.problemId);
  const route =
    safeRoute(record.link) ??
    safeRoute(record.url) ??
    (problemId && sectionKey === 'correctiveActions' ? `/problems/${encodeURIComponent(problemId)}` : null) ??
    (id && DETAIL_ROUTES[sectionKey] ? DETAIL_ROUTES[sectionKey](encodeURIComponent(id)) : null);
  return { key: id ?? `${sectionKey}-${index}`, reference: reference !== label ? reference : null, label, meta, to: route };
}

/** Sections of `data` whose keys are not in `known`, in response order. */
export function extraSections(data: Record<string, unknown> | undefined, known: readonly string[]): GenericSection[] {
  if (!data) return [];
  return Object.entries(data)
    .filter(([key, value]) => !known.includes(key) && isSection(value))
    .map(([key, value]) => {
      const section = value as { total?: unknown; items: unknown[] };
      const total = typeof section.total === 'number' ? section.total : section.items.length;
      return { key, title: sectionTitle(key), total, items: section.items.map((entry, index) => toItem(key, entry, index)) };
    });
}
