import { BRAND } from '../lib/brand';
import { BrandMark } from './layout/BrandMark';

/** Full-screen loading state with the SA monogram (route chunks, session restore). */
export function BrandLoader({ message = 'Restoring your secure session…' }: { message?: string }) {
  return (
    <div className="brand-loader" role="status" aria-live="polite">
      <div>
        <BrandMark className="brand-loader-mark" />
        <b className="sr-only">{BRAND.name}</b>
        <span className="brand-loader-sub" aria-hidden="true">
          {BRAND.subtitle}
        </span>
        <small>{message}</small>
      </div>
    </div>
  );
}
