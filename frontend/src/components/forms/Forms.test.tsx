import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { RequestForm } from '../servicedesk/RequestForm';
import { powerBi } from '../servicedesk/fixtures.test-utils';
import { initialValues, matchesShowIf, resolveDate, serverFieldErrors, toFormData, validateDynamic, visibleKeys } from '../../lib/dynamicForm';
import type { CatalogItemDto, FormField } from '../../lib/types';
import { apiErrorBody, json, mockApi, requests } from '../../test/mockApi';
import { makeFormDetail, pageOf } from '../../test/itsmFixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import { FormBuilder } from './FormBuilder';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

const FIELDS: FormField[] = [
  { key: 'purpose', label: 'Purpose', type: 'select', required: true, options: ['Audit', 'Other'] },
  { key: 'otherReason', label: 'Other reason', type: 'text', required: true, showIf: { field: 'purpose', op: 'EQUALS', value: 'Other' } },
  { key: 'detail', label: 'Detail', type: 'text', required: false, showIf: { field: 'otherReason', op: 'NOT_EMPTY' } },
  { key: 'systems', label: 'Systems', type: 'multiselect', required: true, options: ['SAP', 'Workday', 'ADP'], max: 2 },
  { key: 'seats', label: 'Seats', type: 'number', required: false, min: 1, max: 10 },
  { key: 'contact', label: 'Contact', type: 'email', required: false },
  { key: 'agree', label: 'I agree', type: 'checkbox', required: true },
];

describe('dynamic form logic (mirrors FormValidator)', () => {
  it('evaluates showIf chains and drops hidden fields from formData', () => {
    const values = { ...initialValues(FIELDS), purpose: 'Audit', otherReason: 'leftover', systems: ['SAP'], agree: true };
    expect([...visibleKeys(FIELDS, values)]).toEqual(['purpose', 'systems', 'seats', 'contact', 'agree']);
    expect(toFormData(FIELDS, values)).toEqual({ purpose: 'Audit', systems: ['SAP'], agree: true });
    const other = { ...values, purpose: 'other', otherReason: 'Payroll audit', seats: '3' };
    expect(visibleKeys(FIELDS, other).has('detail')).toBe(true);
    expect(toFormData(FIELDS, other)).toMatchObject({ purpose: 'other', otherReason: 'Payroll audit', seats: 3 });
    expect(matchesShowIf({ field: 'x', op: 'IN', value: 'a, b' }, 'B')).toBe(true);
    expect(matchesShowIf({ field: 'x', op: 'NOT_EMPTY' }, false)).toBe(false);
  });

  it('validates with the server wording', () => {
    const errors = validateDynamic(FIELDS, { ...initialValues(FIELDS), purpose: 'Other', systems: ['SAP', 'Workday', 'ADP'], seats: '20', contact: 'nope' });
    expect(errors).toEqual({
      otherReason: 'Other reason is required',
      systems: 'Systems allows at most 2 selections',
      seats: 'Seats must be at most 10',
      contact: 'Contact must be a valid e-mail address',
      agree: 'I agree must be confirmed',
    });
    expect(resolveDate('today+2', new Date(2026, 10, 4))).toBe('2026-11-06');
    expect(serverFieldErrors(FIELDS, [{ field: 'formData.seats', message: "'Seats' must be at least 1" }], 'x')).toEqual({ seats: "'Seats' must be at least 1" });
  });
});

describe('RequestForm (form definitions)', () => {
  const item: CatalogItemDto = { ...powerBi, id: 21, name: 'Payroll data extract', formFields: [], formDefinitionId: 61, formCode: 'PAYROLL-EXTRACT', formVersion: 2 };

  it('renders the current form version with conditional fields, multi-select and uploads, and maps server errors', async () => {
    let attempts = 0;
    mockApi({
      'GET /catalog/items/21/form': json({
        catalogItemId: 21,
        catalogItemName: 'Payroll data extract',
        formDefinitionId: 61,
        formVersion: 2,
        canSubmit: true,
        fields: [
          { key: 'purpose', label: 'Purpose', type: 'select', required: true, options: ['Audit', 'Other'] },
          { key: 'otherReason', label: 'Other reason', type: 'text', required: true, showIf: { field: 'purpose', op: 'EQUALS', value: 'Other' } },
          { key: 'systems', label: 'Systems', type: 'multiselect', required: true, options: ['SAP', 'Workday'] },
          { key: 'evidence', label: 'Evidence', type: 'file', required: false, accept: ['pdf'], max: 2 },
        ],
      }),
      'POST /service-requests/uploads': json({ id: 301, fileName: 'scope.pdf', contentType: 'application/pdf', sizeBytes: 2048, createdAt: '2026-11-04T08:00:00Z' }, 201),
      'POST /service-requests': () => {
        attempts += 1;
        return attempts === 1
          ? { status: 400, data: apiErrorBody(400, "'Other reason' is too long", [{ field: 'formData.otherReason', message: "'Other reason' is too long" }]) }
          : { status: 201, data: { id: 9, number: 'REQ-4000', status: 'PENDING_APPROVAL' } };
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<RequestForm item={item} onCreated={() => undefined} onCancel={() => undefined} />, { auth: makeAuth(makeUser('EMPLOYEE')) });

    expect(await screen.findByText('Form v2')).toBeInTheDocument();
    expect(screen.queryByLabelText(/Other reason/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Submit request/ }));
    expect(await screen.findByText('Purpose is required')).toBeInTheDocument();
    expect(screen.getByText('Systems is required')).toBeInTheDocument();
    expect(attempts).toBe(0);

    await user.selectOptions(screen.getByLabelText(/Purpose/), 'Other');
    await user.type(await screen.findByLabelText(/Other reason/), 'Year-end audit');
    await user.click(screen.getByRole('checkbox', { name: 'SAP' }));
    await user.upload(screen.getByLabelText(/Evidence/), new File(['%PDF'], 'scope.pdf', { type: 'application/pdf' }));
    expect(await screen.findByText('scope.pdf')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Submit request/ }));
    expect(await screen.findByText("'Other reason' is too long")).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Submit request/ }));
    await waitFor(() => expect(attempts).toBe(2));
    expect(last('POST', '/service-requests')?.body).toMatchObject({
      catalogItemId: 21,
      formData: { purpose: 'Other', otherReason: 'Year-end audit', systems: ['SAP'], evidence: [301] },
    });
  });

  it('explains when the request type is restricted', async () => {
    mockApi({ 'GET /catalog/items/21/form': json({ catalogItemId: 21, catalogItemName: 'x', formDefinitionId: 61, formVersion: 2, canSubmit: false, fields: [] }) });
    renderWithProviders(<RequestForm item={item} onCreated={() => undefined} onCancel={() => undefined} />, { auth: makeAuth(makeUser('EMPLOYEE')) });
    expect(await screen.findByText(/You cannot submit this request type/)).toBeInTheDocument();
  });
});

describe('FormBuilder', () => {
  it('edits fields with a conditional rule, previews them live and saves a new version', async () => {
    const form = makeFormDetail();
    mockApi({
      'GET /forms': json(pageOf([{ id: 61, code: form.code, name: form.name, description: null, template: false, active: true, currentVersion: 2, fieldCount: 2, catalogItemCount: 1, demo: true, updatedAt: form.updatedAt }])),
      'GET /forms/61': json(form),
      'GET /forms/61/versions': json([
        { version: 2, fieldCount: 2, changeNote: 'Conditional reason', createdByName: 'Ada Lovelace', createdAt: '2026-11-01T08:00:00Z' },
        { version: 1, fieldCount: 1, changeNote: 'Initial version', createdByName: 'Ada Lovelace', createdAt: '2026-10-01T08:00:00Z' },
      ]),
      'PUT /forms/61': (config) => json({ ...form, currentVersion: 3, ...JSON.parse(String(config.data)) }),
    });
    const user = userEvent.setup();
    renderWithProviders(<FormBuilder canEdit />, { auth: makeAuth(makeUser('IT_MANAGER')) });

    const preview = await screen.findByRole('form', { name: 'Form preview' });
    expect(within(preview).queryByLabelText(/Other reason/)).not.toBeInTheDocument();
    await user.selectOptions(within(preview).getByLabelText(/Purpose/), 'Other');
    expect(await within(preview).findByLabelText(/Other reason/)).toBeInTheDocument();

    const save = screen.getByRole('button', { name: /Save/ });
    expect(save).toBeDisabled();
    await user.click(screen.getByRole('button', { name: /Add field/ }));
    const card = screen.getByRole('group', { name: /Field 3/ });
    await user.type(within(card).getByLabelText(/^Label/), 'Systems');
    await user.selectOptions(within(card).getByLabelText('Type'), 'multiselect');
    await user.type(within(card).getByLabelText(/Options/), 'SAP, Workday');
    await user.type(screen.getByLabelText('Change note'), 'Add systems');
    await user.click(screen.getByRole('button', { name: /Save new version/ }));

    await waitFor(() => expect(last('PUT', '/forms/61')).toBeTruthy());
    const body = last('PUT', '/forms/61')?.body as { fields: FormField[]; changeNote: string; code: string };
    expect(body.code).toBe('PAYROLL-EXTRACT');
    expect(body.changeNote).toBe('Add systems');
    expect(body.fields[1]).toEqual({ key: 'otherReason', label: 'Other reason', type: 'text', required: true, showIf: { field: 'purpose', op: 'EQUALS', value: 'Other' } });
    expect(body.fields[2]).toEqual({ key: 'field3', label: 'Systems', type: 'multiselect', required: false, options: ['SAP', 'Workday'] });
  });

  it('flags definition problems before saving', async () => {
    const form = makeFormDetail({ fields: [{ key: 'a', label: 'A', type: 'text', required: false }] });
    mockApi({ 'GET /forms': json(pageOf([{ id: 61, code: form.code, name: form.name, description: null, template: false, active: true, currentVersion: 2, fieldCount: 1, catalogItemCount: 1, demo: true, updatedAt: form.updatedAt }])), 'GET /forms/61': json(form), 'GET /forms/61/versions': json([]) });
    const user = userEvent.setup();
    renderWithProviders(<FormBuilder canEdit />, { auth: makeAuth(makeUser('SYSTEM_ADMIN')) });
    await user.click(await screen.findByRole('button', { name: /Add field/ }));
    const card = screen.getByRole('group', { name: /Field 2/ });
    await user.clear(within(card).getByLabelText(/^Key/));
    await user.type(within(card).getByLabelText(/^Key/), 'a');
    expect(within(card).getByRole('alert')).toHaveTextContent('Key “a” is used twice');
    expect(screen.getByRole('button', { name: /Save new version/ })).toBeDisabled();
  });
});
