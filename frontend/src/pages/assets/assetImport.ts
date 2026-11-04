import type { ImportError, ImportResult, ImportRow } from '../../lib/opsTypes';

/** Client-side limits mirrored from the API (UTF-8 CSV ≤ 2 MB); the server validates everything else. */
export const MAX_IMPORT_BYTES = 2 * 1024 * 1024;

export type ImportStage = 'valid' | 'invalid' | 'applied' | 'rejected' | 'empty';

export interface ImportRowView extends ImportRow {
  errors: ImportError[];
}

export interface ImportView {
  stage: ImportStage;
  tone: 'success' | 'critical' | 'warning';
  headline: string;
  /** Apply is offered only after a clean dry run. */
  canApply: boolean;
  /** Errors not tied to a parsed data row (header — row 1 — or rows that could not be parsed). */
  fileErrors: ImportError[];
  rows: ImportRowView[];
  errorRowCount: number;
}

function plural(count: number, word: string): string {
  return `${count} ${word}${count === 1 ? '' : 's'}`;
}

/** "Header" for row 1 (the API counts the header as row 1), else "Row n". */
export function rowLabel(row: number): string {
  return row <= 1 ? 'Header' : `Row ${row}`;
}

/** Turns an `ImportResult` into what the import dialog shows: a headline, row-level errors and whether apply is allowed. */
export function describeImport(result: ImportResult): ImportView {
  const byRow = new Map<number, ImportError[]>();
  result.errors.forEach((error) => byRow.set(error.row, [...(byRow.get(error.row) ?? []), error]));
  const parsedRows = new Set(result.rows.map((row) => row.row));
  const rows = [...result.rows].sort((a, b) => a.row - b.row).map((row) => ({ ...row, errors: byRow.get(row.row) ?? [] }));
  const fileErrors = result.errors.filter((error) => !parsedRows.has(error.row)).sort((a, b) => a.row - b.row);
  const errorRowCount = byRow.size;
  const counts = `${plural(result.created, 'asset')} to create, ${plural(result.updated, 'asset')} to update`;

  if (result.errors.length) {
    const what = `${plural(result.errors.length, 'error')} in ${plural(errorRowCount, 'row')}`;
    return result.dryRun
      ? { stage: 'invalid', tone: 'critical', headline: `${what} — fix the file and validate it again. Nothing was stored.`, canApply: false, fileErrors, rows, errorRowCount }
      : { stage: 'rejected', tone: 'critical', headline: `Not imported: ${what}. Nothing was stored.`, canApply: false, fileErrors, rows, errorRowCount };
  }
  if (result.totalRows === 0) {
    return { stage: 'empty', tone: 'warning', headline: 'The file has a header but no data rows.', canApply: false, fileErrors, rows, errorRowCount };
  }
  if (result.dryRun) {
    return {
      stage: 'valid',
      tone: 'success',
      headline: `All ${plural(result.totalRows, 'row')} are valid: ${counts}. Nothing is stored until you apply.`,
      canApply: true,
      fileErrors,
      rows,
      errorRowCount,
    };
  }
  if (result.applied) {
    return {
      stage: 'applied',
      tone: 'success',
      headline: `Imported ${plural(result.totalRows, 'row')}: ${plural(result.created, 'asset')} created, ${plural(result.updated, 'asset')} updated.`,
      canApply: false,
      fileErrors,
      rows,
      errorRowCount,
    };
  }
  return { stage: 'rejected', tone: 'critical', headline: 'The import was not applied. Nothing was stored.', canApply: false, fileErrors, rows, errorRowCount };
}

/** Pre-flight check before uploading (type and size); returns a message or null. */
export function checkImportFile(file: File | null | undefined): string | null {
  if (!file) return 'Choose a CSV file';
  if (!/\.csv$/i.test(file.name) && file.type !== 'text/csv') return 'Choose a .csv file (UTF-8, with a header row)';
  if (file.size === 0) return 'The file is empty';
  if (file.size > MAX_IMPORT_BYTES) return 'The file is larger than 2 MB';
  return null;
}
