import { describe, expect, it } from 'vitest';
import type { ImportResult } from '../../lib/opsTypes';
import { buildAssetRequest } from './AssetEditor';
import { checkImportFile, describeImport, MAX_IMPORT_BYTES, rowLabel } from './assetImport';

const base: ImportResult = { dryRun: true, applied: false, totalRows: 2, validRows: 2, created: 1, updated: 1, errors: [], rows: [
  { row: 2, assetTag: 'AST-0100', name: 'Spare laptop', assetType: 'LAPTOP', status: 'IN_STOCK', action: 'CREATE' },
  { row: 3, assetTag: 'AST-0051', name: 'ThinkPad T14', assetType: 'LAPTOP', status: 'IN_USE', action: 'UPDATE' },
] };

describe('describeImport (CSV import result handling)', () => {
  it('allows applying only after a clean dry run', () => {
    const view = describeImport(base);
    expect(view.stage).toBe('valid');
    expect(view.canApply).toBe(true);
    expect(view.headline).toBe('All 2 rows are valid: 1 asset to create, 1 asset to update. Nothing is stored until you apply.');
    expect(view.rows.map((row) => row.errors)).toEqual([[], []]);
  });

  it('groups row-level errors and separates header / unparsed rows', () => {
    const view = describeImport({
      ...base,
      validRows: 1,
      errors: [
        { row: 1, field: 'colour', message: 'Unknown column' },
        { row: 3, field: 'assignedToEmail', message: 'No active user with this e-mail' },
        { row: 3, field: 'warrantyExpires', message: 'Not an ISO date' },
        { row: 9, field: null, message: 'Unterminated quote' },
      ],
    });
    expect(view.stage).toBe('invalid');
    expect(view.canApply).toBe(false);
    expect(view.errorRowCount).toBe(3);
    expect(view.headline).toBe('4 errors in 3 rows — fix the file and validate it again. Nothing was stored.');
    expect(view.rows.find((row) => row.row === 3)!.errors.map((error) => error.field)).toEqual(['assignedToEmail', 'warrantyExpires']);
    expect(view.fileErrors.map((error) => error.row)).toEqual([1, 9]);
    expect(rowLabel(1)).toBe('Header');
    expect(rowLabel(9)).toBe('Row 9');
  });

  it('reports an applied import and a rejected apply', () => {
    expect(describeImport({ ...base, dryRun: false, applied: true })).toMatchObject({ stage: 'applied', tone: 'success', canApply: false, headline: 'Imported 2 rows: 1 asset created, 1 asset updated.' });
    expect(describeImport({ ...base, dryRun: false, applied: false, errors: [{ row: 2, field: 'name', message: 'Required' }] })).toMatchObject({ stage: 'rejected', tone: 'critical', canApply: false });
  });

  it('flags a header-only file', () => {
    expect(describeImport({ ...base, totalRows: 0, validRows: 0, created: 0, updated: 0, rows: [] })).toMatchObject({ stage: 'empty', canApply: false });
  });
});

describe('checkImportFile', () => {
  it('accepts a CSV under 2 MB only', () => {
    expect(checkImportFile(null)).toBe('Choose a CSV file');
    expect(checkImportFile(new File(['a,b'], 'assets.xlsx', { type: 'application/vnd.ms-excel' }))).toMatch(/\.csv/);
    expect(checkImportFile(new File([], 'assets.csv', { type: 'text/csv' }))).toBe('The file is empty');
    expect(checkImportFile(new File([new Uint8Array(MAX_IMPORT_BYTES + 1)], 'assets.csv', { type: 'text/csv' }))).toBe('The file is larger than 2 MB');
    expect(checkImportFile(new File(['assetTag,name\n'], 'assets.csv', { type: 'text/csv' }))).toBeNull();
  });
});

describe('buildAssetRequest', () => {
  const values = {
    assetTag: 'ast-0200',
    name: 'Adobe Acrobat Pro',
    assetType: 'SOFTWARE_LICENSE' as const,
    manufacturer: 'Adobe',
    model: '',
    serialNumber: '',
    departmentId: '',
    location: '',
    vendor: '',
    purchaseOrder: '',
    purchaseCost: '1200.50',
    currency: 'inr',
    purchaseDate: '2026-01-10',
    warrantyExpires: '2027-01-01',
    licenseSeats: '10',
    licenseExpires: '2027-01-10',
    notes: '',
    ciId: null,
    assignedToId: 7,
  };

  it('builds a licence request: seats required, no warranty, no holder; tag upper-cased', () => {
    const { body, errors } = buildAssetRequest(values, false);
    expect(errors).toEqual({});
    expect(body).toMatchObject({ assetTag: 'AST-0200', licenseSeats: 10, licenseExpires: '2027-01-10', warrantyExpires: null, purchaseCost: 1200.5, currency: 'INR', departmentId: null, ciId: null });
    expect(body).not.toHaveProperty('assignedToId');
  });

  it('validates seats, cost and dates; on update clears links with 0 and never sends the tag', () => {
    expect(buildAssetRequest({ ...values, licenseSeats: '0', purchaseCost: '-1', currency: 'RUPEE' }, false).errors).toMatchObject({ licenseSeats: expect.any(String), purchaseCost: expect.any(String), currency: expect.any(String) });
    expect(buildAssetRequest({ ...values, assetType: 'LAPTOP', warrantyExpires: '2025-01-01' }, false).errors.warrantyExpires).toBe('Cannot end before the purchase date');
    const update = buildAssetRequest({ ...values, assetType: 'LAPTOP', licenseSeats: '' }, true).body;
    expect(update).not.toHaveProperty('assetTag');
    expect(update).toMatchObject({ departmentId: 0, ciId: 0, licenseSeats: null, warrantyExpires: '2027-01-01' });
  });
});
