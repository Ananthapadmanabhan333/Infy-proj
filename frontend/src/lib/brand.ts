/**
 * Product naming (RELEASE3_SPEC §1). User-facing text uses these constants; technical identifiers (package
 * `com.nexus360`, cookie `nexus_refresh`, header `X-Requested-With: nexus360`, demo domain `demo.nexus360.io`, API
 * paths) deliberately keep their old names for backward compatibility.
 */
export const BRAND = {
  name: 'SA',
  subtitle: 'Enterprise Operations Platform',
  tagline: 'Intelligent Service Management. Unified Operations.',
  ai: 'SA AI',
} as const;

/** Document title for a page: `SA | Incidents`; the product name alone when no page is given. */
export function pageTitle(page?: string | null): string {
  const label = page?.trim();
  return label ? `${BRAND.name} | ${label}` : `${BRAND.name} | ${BRAND.subtitle}`;
}

/**
 * Geometry of the SA letterforms, shared by the React components and the static files in `public/brand/`.
 * Drawn on an 86 × 60 grid as round-capped 10-unit strokes: a two-bowl geometric "S" and an open "A" whose
 * crossbar is a single dot (a status beacon). Original artwork — no font or third-party glyph.
 */
export const SA_GLYPH = {
  width: 86,
  height: 60,
  stroke: 10,
  s: 'M27.08 9.47A12.5 12.5 0 1 0 17.5 30A12.5 12.5 0 1 1 7.92 50.53',
  a: 'M47 55L64 5L81 55',
  dot: { cx: 64, cy: 42, r: 4 },
  gold: '#D6B77A',
  champagne: '#F1DFBA',
  ink: '#1A1714',
  bronze: '#96764B',
} as const;
