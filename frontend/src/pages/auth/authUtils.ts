import { useCallback, useEffect, useRef, useState } from 'react';
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { useLocation, useNavigate } from 'react-router-dom';
import { toApiError } from '../../lib/api';

/** Same-app relative paths only; auth pages and "/" fall back to the Command Center. */
export function safeNext(next: string | null | undefined): string {
  if (!next || !next.startsWith('/') || next.startsWith('//')) return '/dashboard';
  // Backslashes and control characters can turn a path into a protocol-relative URL in some browsers.
  // eslint-disable-next-line no-control-regex
  if (/[\\\u0000-\u001f\u007f]/.test(next)) return '/dashboard';
  const path = next.split(/[?#]/)[0];
  if (path === '/' || AUTH_PATHS.some((authPath) => path === authPath || path.startsWith(`${authPath}/`))) return '/dashboard';
  return next;
}

/** Public account pages that signed-in users are sent away from. */
export const AUTH_PATHS = ['/login', '/register', '/forgot-password', '/reset-password'];

/**
 * Reads a one-time token from the query string, keeps it in memory and immediately removes it from the
 * address bar (history replace), so it is not left in the URL, browser history or a copied link.
 */
export function useUrlToken(name: string): [token: string | null, forget: () => void] {
  const location = useLocation();
  const navigate = useNavigate();
  const [token, setToken] = useState<string | null>(() => new URLSearchParams(location.search).get(name));
  const forget = useCallback(() => setToken(null), []);

  useEffect(() => {
    const params = new URLSearchParams(location.search);
    const fromUrl = params.get(name);
    if (fromUrl === null) return;
    setToken(fromUrl);
    params.delete(name);
    const search = params.toString();
    navigate({ pathname: location.pathname, search: search ? `?${search}` : '', hash: location.hash }, { replace: true, state: location.state });
  }, [location.search, location.pathname, location.hash, location.state, name, navigate]);

  return [token, forget];
}

/**
 * Maps API `fieldErrors` onto the form fields this form actually has (others are ignored) and focuses the
 * first one. Returns true when at least one field error was applied.
 */
export function applyKnownFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  known: readonly Path<T>[],
  fieldMap: Record<string, Path<T>> = {},
): boolean {
  const fieldErrors = toApiError(error).fieldErrors ?? [];
  let first = true;
  let applied = false;
  fieldErrors.forEach(({ field, message }) => {
    const name = (fieldMap[field] ?? field) as Path<T>;
    if (!known.includes(name)) return;
    setError(name, { type: 'server', message }, { shouldFocus: first });
    first = false;
    applied = true;
  });
  return applied;
}

/** Sets the document title (and meta description) while the page is mounted. */
export function usePageMeta(title: string, description?: string) {
  useEffect(() => {
    const previousTitle = document.title;
    document.title = title;
    const meta = document.querySelector<HTMLMetaElement>('meta[name="description"]');
    const previousDescription = meta?.content;
    if (meta && description) meta.content = description;
    return () => {
      document.title = previousTitle;
      if (meta && previousDescription !== undefined) meta.content = previousDescription;
    };
  }, [title, description]);
}

/** Moves focus to the element on mount (success screens, invalid-link states) so it is announced. */
export function useFocusOnMount<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  useEffect(() => {
    ref.current?.focus();
  }, []);
  return ref;
}

/** A friendly message for 429 responses of the public endpoints (shared login rate-limit bucket). */
export function rateLimitMessage(error: unknown): string | null {
  const apiError = toApiError(error);
  if (apiError.status !== 429) return null;
  return apiError.retryAfterSeconds
    ? `Too many attempts. Try again in ${apiError.retryAfterSeconds} seconds.`
    : 'Too many attempts. Please wait a minute and try again.';
}
