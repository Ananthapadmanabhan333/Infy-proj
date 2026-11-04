/** Fired when a scroll target is not in the DOM yet, so lazy landing sections can load on demand. */
export const SECTION_REQUEST_EVENT = 'nexus:section-request';

export function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

interface ScrollOptions {
  /** Write `#id` to the address bar (without a router navigation). Default true. */
  updateHash?: boolean;
  /** Move keyboard focus to the target. Default true. */
  focus?: boolean;
}

/**
 * Smoothly scrolls to the landing section `id` (instantly with reduced motion) and moves focus to it.
 * When the section is lazy and not rendered yet, asks for it and retries for up to ~3 s.
 */
export function scrollToSection(id: string, { updateHash = true, focus = true }: ScrollOptions = {}): void {
  const attempt = (triesLeft: number) => {
    const target = document.getElementById(id);
    if (target) {
      target.scrollIntoView?.({ behavior: prefersReducedMotion() ? 'auto' : 'smooth', block: 'start' });
      if (focus) {
        if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1');
        target.focus({ preventScroll: true });
      }
      if (updateHash && window.location.hash !== `#${id}`) {
        window.history.replaceState(window.history.state, '', `${window.location.pathname}${window.location.search}#${id}`);
      }
      return;
    }
    if (triesLeft <= 0) return;
    window.dispatchEvent(new CustomEvent(SECTION_REQUEST_EVENT, { detail: id }));
    window.setTimeout(() => attempt(triesLeft - 1), 60);
  };
  // Deferred one task so a closing menu or drawer can restore scrolling and focus first.
  window.setTimeout(() => attempt(50), 0);
}
