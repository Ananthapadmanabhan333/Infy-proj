import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { apiErrorBody, json, mockApi, requests } from '../../test/mockApi';
import { pageOf } from '../../test/itsmFixtures';
import { makePirDetail, makePirSummary } from '../../test/responseFixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { PirDetail } from '../../lib/responseTypes';
import type { Role } from '../../lib/types';
import ReviewDetailPage from './ReviewDetailPage';
import ReviewsPage from './ReviewsPage';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

function routes(review: PirDetail = makePirDetail(), overrides: Record<string, ReturnType<typeof json>> = {}) {
  mockApi({
    'GET /reviews/6': json(review),
    'PUT /reviews/6': (config) => json({ ...review, ...JSON.parse(String(config.data)) }),
    'POST /reviews/6/timeline/rebuild': json({ ...review, timelineBuiltAt: '2026-11-04T07:00:00Z' }),
    'POST /reviews/6/submit': json({ ...review, status: 'IN_REVIEW', approvalId: 77 }),
    'POST /reviews/6/withdraw': json({ ...review, status: 'DRAFT' }),
    'POST /reviews/6/actions': json(review.actions[0], 201),
    'GET /reviews/6/pdf': { status: 200, data: new Blob(['%PDF']), headers: { 'content-disposition': 'attachment; filename="PIR-006.pdf"' } },
    'GET /users/assignable': json([]),
    ...overrides,
  });
}

function renderDetail(role: Role = 'INCIDENT_MANAGER') {
  return renderWithProviders(
    <Routes>
      <Route path="/reviews/:id" element={<ReviewDetailPage />} />
    </Routes>,
    { route: '/reviews/6', auth: makeAuth(makeUser(role)) },
  );
}

describe('Post-incident reviews list', () => {
  it('lists reviews with incident and major incident numbers', async () => {
    mockApi({ 'GET /reviews': json(pageOf([makePirSummary()])) });
    renderWithProviders(<ReviewsPage />, { route: '/reviews', auth: makeAuth(makeUser('AUDITOR')) });
    expect(await screen.findByText('PIR-006')).toBeInTheDocument();
    expect(screen.getByText('MI-002')).toBeInTheDocument();
    expect(screen.getByText('1 open / 1')).toBeInTheDocument();
    expect(last('GET', '/reviews')?.params).toMatchObject({ status: 'DRAFT,IN_REVIEW,REJECTED' });
  });
});

describe('Post-incident review detail', () => {
  it('edits the analysis sections and contributing factors', async () => {
    routes();
    renderDetail();
    const impact = await screen.findByLabelText(/^Impact/);
    await userEvent.type(impact, '2,400 employees');
    await userEvent.type(screen.getByLabelText('Contributing factors'), 'No pool alert{Enter}');
    await userEvent.click(screen.getByRole('button', { name: 'Remove Batch started before the cache warmed up' }));
    await userEvent.click(screen.getByRole('button', { name: /^Save$/ }));
    await waitFor(() => expect(last('PUT', '/reviews/6')).toBeTruthy());
    expect(last('PUT', '/reviews/6')?.body).toMatchObject({ impact: '2,400 employees', contributingFactors: ['No pool alert'], rootCause: 'Connection pool exhausted by the batch' });
  });

  it('shows the metrics panel and the auto timeline; rebuilding asks for confirmation', async () => {
    routes();
    renderDetail();
    expect(await screen.findByText('Time to detect')).toBeInTheDocument();
    expect(screen.getByText('1 h 35 m')).toBeInTheDocument();
    expect(screen.getByText('Missed')).toBeInTheDocument();
    expect(screen.getByText('Alert fired: Payroll API 5xx')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Rebuild/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Rebuild the timeline?' });
    expect(last('POST', '/reviews/6/timeline/rebuild')).toBeUndefined();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rebuild timeline' }));
    await waitFor(() => expect(last('POST', '/reviews/6/timeline/rebuild')).toBeTruthy());
  });

  it('submits for approval after confirmation and lists missing items from the API', async () => {
    routes(makePirDetail(), {
      'POST /reviews/6/submit': json(apiErrorBody(400, 'Validation failed', [{ field: 'impact', message: 'is required before submitting' }]), 400),
    });
    renderDetail();
    expect(await screen.findByRole('list', { name: 'Ready to submit' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Submit for approval/ }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Submit' }));
    expect(await within(dialog).findByText(/Impact: is required before submitting/)).toBeInTheDocument();
  });

  it('is read-only while in review and links to the approvals inbox for approvers', async () => {
    routes(makePirDetail({ status: 'IN_REVIEW', approvalId: 77, approvalStatus: 'PENDING', submittedAt: '2026-11-04T07:00:00Z' }));
    renderDetail('IT_MANAGER');
    expect(await screen.findByRole('link', { name: /Decide in the approvals inbox/ })).toHaveAttribute('href', '/approvals?approval=77');
    expect(screen.queryByRole('button', { name: /^Save$/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Submit for approval/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Rebuild/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add action/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Withdraw/ }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Withdraw' }));
    await waitFor(() => expect(last('POST', '/reviews/6/withdraw')).toBeTruthy());
  });

  it('shows the reviewer comment when rejected and allows resubmission', async () => {
    routes(makePirDetail({ status: 'REJECTED', reviewComment: 'Add the detection gaps', approvalId: 77, approvalStatus: 'REJECTED' }));
    renderDetail();
    expect((await screen.findAllByText(/Add the detection gaps/)).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: /Resubmit for approval/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /View the approval/ })).toHaveAttribute('href', '/approvals?approval=77');
  });

  it('downloads the PDF; without edit rights the analysis is read-only', async () => {
    routes(makePirDetail({ canEdit: false, canSubmit: false }));
    const createObjectURL = (URL.createObjectURL = (() => 'blob:pir') as typeof URL.createObjectURL);
    URL.revokeObjectURL = () => undefined;
    renderDetail('AUDITOR');
    await userEvent.click(await screen.findByRole('button', { name: /PDF/ }));
    await waitFor(() => expect(last('GET', '/reviews/6/pdf')).toBeTruthy());
    expect(createObjectURL).toBeDefined();
    expect(screen.queryByRole('button', { name: /Submit for approval/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Save$/ })).not.toBeInTheDocument();
    expect(screen.getByText('Connection pool exhausted by the batch')).toBeInTheDocument();
  });
});
