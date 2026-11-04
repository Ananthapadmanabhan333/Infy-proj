import { useState } from 'react';
import { Star, StarHalf } from 'lucide-react';
import { formatNumber } from '../lib/format';

interface StarRatingProps {
  /** Average rating 1–5 (null = not rated yet). */
  value: number | null;
  /** Number of ratings, shown as "(212)". */
  count?: number;
  size?: number;
  /** Makes the stars an input: called with 1–5. */
  onChange?: (rating: 1 | 2 | 3 | 4 | 5) => void;
  disabled?: boolean;
  /** Accessible name for the input variant. */
  label?: string;
}

const STARS = [1, 2, 3, 4, 5] as const;

/** Star rating display ("★ 4.8 (212)") or, with `onChange`, a 1–5 rating input. */
export function StarRating({ value, count, size = 12, onChange, disabled, label = 'Rate this article' }: StarRatingProps) {
  const [hover, setHover] = useState<number | null>(null);

  if (onChange) {
    const shown = hover ?? Math.round(value ?? 0);
    return (
      <span className="star-rating" role="radiogroup" aria-label={label} onMouseLeave={() => setHover(null)}>
        {STARS.map((star) => (
          <button
            key={star}
            type="button"
            role="radio"
            aria-checked={Math.round(value ?? 0) === star}
            aria-label={`${star} star${star > 1 ? 's' : ''}`}
            className={star <= shown ? 'on' : ''}
            disabled={disabled}
            onMouseEnter={() => setHover(star)}
            onFocus={() => setHover(star)}
            onBlur={() => setHover(null)}
            onClick={() => onChange(star)}
          >
            <Star size={size + 4} fill={star <= shown ? 'currentColor' : 'none'} />
          </button>
        ))}
      </span>
    );
  }

  if (value === null || value === undefined || !count) {
    return <span className="star-rating muted-text">No ratings yet</span>;
  }
  const rounded = Math.round(value * 2) / 2;
  return (
    <span className="star-rating" aria-label={`Rated ${value.toFixed(1)} out of 5 from ${count} ratings`}>
      <span className="stars" aria-hidden="true">
        {STARS.map((star) =>
          rounded >= star ? (
            <Star key={star} size={size} fill="currentColor" />
          ) : rounded >= star - 0.5 ? (
            <StarHalf key={star} size={size} fill="currentColor" />
          ) : (
            <Star key={star} size={size} className="empty" />
          ),
        )}
      </span>
      <b aria-hidden="true">{value.toFixed(1)}</b>
      <span className="count" aria-hidden="true">
        ({formatNumber(count)})
      </span>
    </span>
  );
}

/** Compact single-star variant used in dense lists: "★ 4.8 (212)". */
export function RatingSummary({ value, count }: { value: number | null; count: number }) {
  if (value === null || !count) return <span className="star-rating muted-text">No ratings yet</span>;
  return (
    <span className="star-rating" aria-label={`Rated ${value.toFixed(1)} out of 5 from ${count} ratings`}>
      <span className="stars" aria-hidden="true">
        <Star size={12} fill="currentColor" />
      </span>
      <b aria-hidden="true">{value.toFixed(1)}</b>
      <span className="count" aria-hidden="true">
        ({formatNumber(count)})
      </span>
    </span>
  );
}
