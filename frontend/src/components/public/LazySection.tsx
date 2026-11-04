import { Suspense, useEffect, useRef, useState, type ComponentType, type LazyExoticComponent } from 'react';
import { SECTION_REQUEST_EVENT } from './scroll';

interface LazySectionProps {
  component: LazyExoticComponent<ComponentType>;
  /** Placeholder height while the chunk is not loaded (limits layout shift). */
  minHeight?: number | string;
  /** Load this far before the section enters the viewport. */
  rootMargin?: string;
}

/**
 * Renders a lazily imported below-the-fold section once it approaches the viewport — or immediately when a
 * scroll target inside it is requested (`scrollToSection` dispatches `SECTION_REQUEST_EVENT`).
 */
export function LazySection({ component: Component, minHeight = '60vh', rootMargin = '800px 0px' }: LazySectionProps) {
  const [load, setLoad] = useState(false);
  const placeholderRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (load) return undefined;
    const start = () => setLoad(true);
    window.addEventListener(SECTION_REQUEST_EVENT, start);
    let observer: IntersectionObserver | undefined;
    if (typeof IntersectionObserver === 'function' && placeholderRef.current) {
      observer = new IntersectionObserver((entries) => entries.some((entry) => entry.isIntersecting) && start(), { rootMargin });
      observer.observe(placeholderRef.current);
    } else {
      start();
    }
    return () => {
      window.removeEventListener(SECTION_REQUEST_EVENT, start);
      observer?.disconnect();
    };
  }, [load, rootMargin]);

  const placeholder = <div ref={placeholderRef} className="pub-lazy-placeholder" style={{ minHeight }} aria-hidden="true" />;
  if (!load) return placeholder;
  return (
    <Suspense fallback={<div className="pub-lazy-placeholder" style={{ minHeight }} aria-hidden="true" />}>
      <Component />
    </Suspense>
  );
}
