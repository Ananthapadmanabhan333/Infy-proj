import type { ReactNode } from 'react';
import { AlertTriangle, Link2Off, RefreshCw, WifiOff } from 'lucide-react';
import { AuthCardHeader } from './AuthLayout';
import { useFocusOnMount } from './authUtils';

export function FormAlert({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <div className="callout red" role="alert">
      <AlertTriangle size={16} aria-hidden="true" />
      <div>{message}</div>
    </div>
  );
}

/**
 * One generic message for every unusable link (unknown, used, revoked or expired) so the page never reveals
 * which of these applies.
 */
export function InvalidLinkState({ id, title, children, actions }: { id: string; title: string; children: ReactNode; actions: ReactNode }) {
  const headingRef = useFocusOnMount<HTMLHeadingElement>();
  return (
    <>
      <AuthCardHeader id={id} headingRef={headingRef} icon={<Link2Off size={20} />} tone="critical" eyebrow="Link unavailable" title={title} />
      <div className="mt-3 text-[12px] leading-relaxed text-[#d6cab8]">{children}</div>
      <div className="auth-links">{actions}</div>
    </>
  );
}

/** The link could not be checked (network or server error) — not the same as an invalid link. */
export function LinkCheckFailed({ id, onRetry, retrying }: { id: string; onRetry: () => void; retrying: boolean }) {
  const headingRef = useFocusOnMount<HTMLHeadingElement>();
  return (
    <>
      <AuthCardHeader
        id={id}
        headingRef={headingRef}
        icon={<WifiOff size={20} />}
        eyebrow="Connection problem"
        title="We could not check this link"
        description="SA is not reachable right now. Your link has not been used — try again in a moment."
      />
      <button type="button" className="gold-btn full" onClick={onRetry} disabled={retrying}>
        <RefreshCw size={15} aria-hidden="true" /> {retrying ? 'Checking…' : 'Try again'}
      </button>
    </>
  );
}
