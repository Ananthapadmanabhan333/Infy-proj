import { useId } from 'react';
import { BRAND, SA_GLYPH } from '../../lib/brand';

function useGradientId(prefix: string): string {
  return `${prefix}-${useId().replace(/:/g, '')}`;
}

/** The S and A strokes of the logo, painted with `paint` (a colour or `url(#…)`). */
function Glyph({ paint, dotPaint = paint }: { paint: string; dotPaint?: string }) {
  return (
    <>
      <g fill="none" stroke={paint} strokeWidth={SA_GLYPH.stroke} strokeLinecap="round" strokeLinejoin="round">
        <path d={SA_GLYPH.s} />
        <path d={SA_GLYPH.a} />
      </g>
      <circle cx={SA_GLYPH.dot.cx} cy={SA_GLYPH.dot.cy} r={SA_GLYPH.dot.r} fill={dotPaint} />
    </>
  );
}

function GoldGradient({ id }: { id: string }) {
  return (
    <linearGradient id={id} gradientUnits="userSpaceOnUse" x1="0" y1={SA_GLYPH.height} x2={SA_GLYPH.width} y2="0">
      <stop offset="0" stopColor={SA_GLYPH.gold} />
      <stop offset="1" stopColor={SA_GLYPH.champagne} />
    </linearGradient>
  );
}

interface BrandMarkProps {
  className?: string;
  /** Accessible name; omit for a decorative mark (the default, next to visible text). */
  title?: string;
}

/**
 * Square SA monogram (same artwork as `public/brand/sa-mark.svg`, the favicon and the app icons): gold letters on
 * an obsidian tile with a hairline gold border. Inline SVG so it needs no request and inherits no CSS colour.
 */
export function BrandMark({ className = 'brand-logo', title }: BrandMarkProps) {
  const gold = useGradientId('sa-gold');
  const tile = useGradientId('sa-tile');
  return (
    <svg
      className={className}
      viewBox="0 0 64 64"
      role="img"
      aria-hidden={title ? undefined : true}
      aria-label={title}
      focusable="false"
      data-brand="mark"
    >
      <defs>
        <linearGradient id={tile} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#24201C" />
          <stop offset="1" stopColor="#101012" />
        </linearGradient>
        <GoldGradient id={gold} />
      </defs>
      <rect x="0.75" y="0.75" width="62.5" height="62.5" rx="14" fill={`url(#${tile})`} stroke={SA_GLYPH.gold} strokeOpacity="0.5" strokeWidth="1.5" />
      <g transform="translate(10.5 17) scale(0.5)">
        <Glyph paint={`url(#${gold})`} />
      </g>
    </svg>
  );
}

interface BrandWordmarkProps {
  className?: string;
  /** `gold` for dark surfaces (default), `ink` for light surfaces. */
  tone?: 'gold' | 'ink';
  /** Accessible name (defaults to the product name); pass `null` when the surrounding element already names it. */
  label?: string | null;
}

/** The SA wordmark letters (same artwork as `public/brand/sa-logo.svg` / `sa-logo-light.svg`). */
export function BrandWordmark({ className = 'brand-wordmark', tone = 'gold', label = BRAND.name }: BrandWordmarkProps) {
  const gold = useGradientId('sa-word');
  const paint = tone === 'gold' ? `url(#${gold})` : SA_GLYPH.ink;
  return (
    <svg
      className={className}
      viewBox={`-3 -3 ${SA_GLYPH.width + 6} ${SA_GLYPH.height + 6}`}
      role="img"
      aria-hidden={label ? undefined : true}
      aria-label={label ?? undefined}
      focusable="false"
      data-brand="wordmark"
    >
      {tone === 'gold' && (
        <defs>
          <GoldGradient id={gold} />
        </defs>
      )}
      <Glyph paint={paint} dotPaint={tone === 'gold' ? paint : SA_GLYPH.bronze} />
    </svg>
  );
}

interface BrandLockupProps {
  className?: string;
  /** Line under / beside the wordmark — the platform subtitle unless an organisation name replaces it. */
  subtitle?: string;
  /** Hide the subtitle (compact places). */
  compact?: boolean;
  /** Accessible name of the wordmark; `null` when a parent (e.g. a home link) already carries the name. */
  label?: string | null;
}

/** Wordmark + subtitle, used by the sidebar, the public navbar/footer, the auth pages and the landing preview. */
export function BrandLockup({ className = '', subtitle = BRAND.subtitle, compact = false, label = BRAND.name }: BrandLockupProps) {
  return (
    <span className={`brand-lockup ${className}`.trim()} data-brand="lockup">
      <BrandWordmark className="brand-lockup-word" label={label} />
      {!compact && (
        <>
          <span className="brand-lockup-rule" aria-hidden="true" />
          <small className="brand-lockup-sub">{subtitle}</small>
        </>
      )}
    </span>
  );
}
