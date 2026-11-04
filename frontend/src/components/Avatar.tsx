import { initials, shortName } from '../lib/format';

export type AvatarSize = 'xs' | 'sm' | 'md' | 'lg';

/** Stable warm tint per name (gold / champagne / amber / taupe) so lists are easy to scan. */
function toneFor(name: string): number {
  let hash = 0;
  for (let i = 0; i < name.length; i += 1) hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
  return hash % 5; // 0 = default gold gradient
}

interface AvatarProps {
  name: string | null | undefined;
  size?: AvatarSize;
  /** Tooltip; defaults to the full name. */
  title?: string;
  className?: string;
}

/** Initials avatar — never an image (no personal photos are stored). */
export function Avatar({ name, size = 'md', title, className = '' }: AvatarProps) {
  const label = name?.trim() || 'Unassigned';
  const tone = toneFor(label);
  return (
    <span
      className={`avatar ${size === 'md' ? '' : size} ${tone ? `tone-${tone}` : ''} ${className}`.replace(/\s+/g, ' ').trim()}
      title={title ?? label}
      aria-hidden="true"
    >
      {name ? initials(name) : '–'}
    </span>
  );
}

/** Avatar + short name ("Priya R.") for table cells; shows `emptyLabel` when there is no user. */
export function UserChip({ name, size = 'xs', emptyLabel = 'Unassigned', full = false }: { name: string | null | undefined; size?: AvatarSize; emptyLabel?: string; full?: boolean }) {
  if (!name) return <span className="muted-text">{emptyLabel}</span>;
  return (
    <span className="user-chip" title={name}>
      <Avatar name={name} size={size} />
      <span>{full ? name : shortName(name)}</span>
    </span>
  );
}
