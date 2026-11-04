import { useMemo, useState } from 'react';
import type { SortState } from '../DataTable';

type Accessor<T> = (row: T) => string | number | null | undefined;

/** Client-side sorting for the analytics tables (nulls always last). */
export function sortRows<T>(rows: T[], sort: SortState | null, accessors: Record<string, Accessor<T>>): T[] {
  if (!sort || !accessors[sort.key]) return rows;
  const get = accessors[sort.key];
  const factor = sort.dir === 'asc' ? 1 : -1;
  return [...rows].sort((a, b) => {
    const left = get(a);
    const right = get(b);
    if (left === null || left === undefined) return right === null || right === undefined ? 0 : 1;
    if (right === null || right === undefined) return -1;
    if (typeof left === 'number' && typeof right === 'number') return (left - right) * factor;
    return String(left).localeCompare(String(right)) * factor;
  });
}

export function useSortedRows<T>(rows: T[], initial: SortState, accessors: Record<string, Accessor<T>>) {
  const [sort, setSort] = useState<SortState>(initial);
  // Accessors are static per table; re-sorting follows the rows and the sort state.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const sorted = useMemo(() => sortRows(rows, sort, accessors), [rows, sort]);
  return { sorted, sort, setSort };
}
