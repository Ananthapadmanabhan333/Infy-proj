import type { KeyboardEvent, ReactNode } from 'react';
import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-react';

export interface Column<T> {
  key: string;
  header: ReactNode;
  render: (row: T) => ReactNode;
  className?: string;
  /** API sort property; makes the header clickable. */
  sortKey?: string;
}

export interface SortState {
  key: string;
  dir: 'asc' | 'desc';
}

type RowKey = string | number;

interface DataTableProps<T> {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => RowKey;
  onRowClick?: (row: T) => void;
  rowLabel?: (row: T) => string;
  sort?: SortState | null;
  onSortChange?: (sort: SortState) => void;
  empty?: ReactNode;
  caption?: string;
  rowClassName?: (row: T) => string;
  /** Enables a checkbox column (bulk actions). Selection is controlled by the caller. */
  selected?: ReadonlySet<RowKey>;
  onSelectedChange?: (next: Set<RowKey>) => void;
  /** Rows that cannot be selected (checkbox disabled). */
  isSelectable?: (row: T) => boolean;
}

/** Dense table (reference incident list) with optional sorting, row click and checkbox selection. */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  onRowClick,
  rowLabel,
  sort,
  onSortChange,
  empty,
  caption,
  rowClassName,
  selected,
  onSelectedChange,
  isSelectable,
}: DataTableProps<T>) {
  const clickable = Boolean(onRowClick);
  const selectable = Boolean(selected && onSelectedChange);
  const selectableRows = rows.filter((row) => !isSelectable || isSelectable(row));
  const allSelected = selectable && selectableRows.length > 0 && selectableRows.every((row) => selected?.has(rowKey(row)));
  const someSelected = selectable && !allSelected && selectableRows.some((row) => selected?.has(rowKey(row)));

  const toggleSort = (key: string) => {
    if (!onSortChange) return;
    const dir = sort?.key === key && sort.dir === 'desc' ? 'asc' : 'desc';
    onSortChange({ key, dir });
  };

  const onKey = (event: KeyboardEvent<HTMLTableRowElement>, row: T) => {
    if (!onRowClick || event.target !== event.currentTarget) return;
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onRowClick(row);
    }
  };

  const toggleRow = (key: RowKey) => {
    if (!selected || !onSelectedChange) return;
    const next = new Set(selected);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    onSelectedChange(next);
  };

  const toggleAll = () => {
    if (!selected || !onSelectedChange) return;
    const next = new Set(selected);
    if (allSelected) selectableRows.forEach((row) => next.delete(rowKey(row)));
    else selectableRows.forEach((row) => next.add(rowKey(row)));
    onSelectedChange(next);
  };

  return (
    <div className="table-scroll">
      <table className={`data-table ${clickable ? '' : 'static-table'}`.trim()}>
        {caption && <caption className="sr-only">{caption}</caption>}
        <thead>
          <tr>
            {selectable && (
              <th scope="col" className="select-cell">
                <input
                  type="checkbox"
                  aria-label="Select all rows"
                  checked={allSelected}
                  ref={(node) => {
                    if (node) node.indeterminate = someSelected;
                  }}
                  onChange={toggleAll}
                  disabled={!selectableRows.length}
                />
              </th>
            )}
            {columns.map((column) => {
              const active = sort?.key === column.sortKey;
              const ariaSort = active ? (sort?.dir === 'asc' ? 'ascending' : 'descending') : undefined;
              return (
                <th key={column.key} className={column.className} aria-sort={column.sortKey ? ariaSort ?? 'none' : undefined} scope="col">
                  {column.sortKey && onSortChange ? (
                    <button type="button" onClick={() => toggleSort(column.sortKey as string)}>
                      {column.header}
                      {active ? sort?.dir === 'asc' ? <ArrowUp size={11} /> : <ArrowDown size={11} /> : <ArrowUpDown size={11} />}
                    </button>
                  ) : (
                    column.header
                  )}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const key = rowKey(row);
            const isSelected = selectable && Boolean(selected?.has(key));
            const canSelect = !isSelectable || isSelectable(row);
            return (
              <tr
                key={key}
                className={`${rowClassName?.(row) ?? ''} ${isSelected ? 'selected' : ''}`.trim() || undefined}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                onKeyDown={onRowClick ? (event) => onKey(event, row) : undefined}
                tabIndex={onRowClick ? 0 : undefined}
                aria-label={onRowClick && rowLabel ? rowLabel(row) : undefined}
                aria-selected={selectable ? isSelected : undefined}
              >
                {selectable && (
                  <td className="select-cell" onClick={(event) => event.stopPropagation()}>
                    <input
                      type="checkbox"
                      aria-label={`Select ${rowLabel ? rowLabel(row) : `row ${key}`}`}
                      checked={isSelected}
                      disabled={!canSelect}
                      onChange={() => toggleRow(key)}
                    />
                  </td>
                )}
                {columns.map((column) => (
                  <td key={column.key} className={column.className}>
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
      {rows.length === 0 && <div className="empty">{empty ?? 'No records match these filters.'}</div>}
    </div>
  );
}
