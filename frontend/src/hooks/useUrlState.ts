import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { PERIOD_OPTIONS, type PeriodDays } from '../lib/constants';
import { useDebounce } from './useDebounce';

/**
 * Reads/writes a flat set of string filters in the URL query string. Setting a
 * value to '' removes it. Any filter change resets `page` unless `page` itself
 * is being set.
 */
export function useUrlFilters<K extends string>(keys: readonly K[], defaults: Partial<Record<K, string>> = {}) {
  const [params, setParams] = useSearchParams();

  const values = useMemo(() => {
    const result = {} as Record<K, string>;
    keys.forEach((key) => {
      result[key] = params.get(key) ?? defaults[key] ?? '';
    });
    return result;
  }, [params, keys.join('|')]);

  const setValues = useCallback(
    (patch: Partial<Record<K | 'page', string>>) => {
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          Object.entries(patch).forEach(([key, value]) => {
            if (value === undefined || value === '' || value === null) next.delete(key);
            else next.set(key, String(value));
          });
          if (!('page' in patch)) next.delete('page');
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);
  const setPage = useCallback((value: number) => setValues({ page: value ? String(value) : '' } as Partial<Record<K | 'page', string>>), [setValues]);

  return { values, setValues, page, setPage };
}

/** URL-synced reporting period (?days=7|30|90). */
export function usePeriod(defaultDays: PeriodDays = 7): [PeriodDays, (days: PeriodDays) => void] {
  const [params, setParams] = useSearchParams();
  const raw = Number.parseInt(params.get('days') ?? '', 10);
  const days = (PERIOD_OPTIONS as readonly number[]).includes(raw) ? (raw as PeriodDays) : defaultDays;
  const setDays = useCallback(
    (value: PeriodDays) =>
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          next.set('days', String(value));
          return next;
        },
        { replace: true },
      ),
    [setParams],
  );
  return [days, setDays];
}

/**
 * Text input bound to a URL filter: typing is debounced before it is committed,
 * and external URL changes (back/forward, "clear filters") flow back into the input.
 */
export function useSearchInput(urlValue: string, commit: (value: string) => void, delayMs = 350) {
  const [input, setInput] = useState(urlValue);
  const debounced = useDebounce(input, delayMs);
  const lastCommitted = useRef(urlValue);
  const commitRef = useRef(commit);
  commitRef.current = commit;

  useEffect(() => {
    if (debounced !== lastCommitted.current) {
      lastCommitted.current = debounced;
      commitRef.current(debounced);
    }
  }, [debounced]);

  useEffect(() => {
    if (urlValue !== lastCommitted.current) {
      lastCommitted.current = urlValue;
      setInput(urlValue);
    }
  }, [urlValue]);

  return [input, setInput] as const;
}

/**
 * URL-synced tab (`?tab=`). Unknown or disallowed values fall back to `defaultTab`; selecting the
 * default removes the parameter. Other query parameters are kept unless listed in `resetKeys`.
 */
export function useTabParam<T extends string>(allowed: readonly T[], defaultTab: T, resetKeys: readonly string[] = []): [T, (tab: T) => void] {
  const [params, setParams] = useSearchParams();
  const raw = params.get('tab') as T | null;
  const tab = raw && allowed.includes(raw) ? raw : defaultTab;
  const setTab = useCallback(
    (next: T) =>
      setParams(
        (current) => {
          const updated = new URLSearchParams(current);
          if (next === defaultTab) updated.delete('tab');
          else updated.set('tab', next);
          updated.delete('page');
          resetKeys.forEach((key) => updated.delete(key));
          return updated;
        },
        { replace: true },
      ),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [setParams, defaultTab, resetKeys.join('|')],
  );
  return [tab, setTab];
}
