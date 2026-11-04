import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import type { ArticleDetail, ArticleStatus, Role } from '../../lib/types';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import { ArticleEditor } from './ArticleEditor';
import ArticlePage from './ArticlePage';

function detail(status: ArticleStatus, overrides: Partial<ArticleDetail> = {}): ArticleDetail {
  return {
    id: 1001,
    number: 'KB-1001',
    title: 'Payroll application troubleshooting guide',
    summary: 'How to fix payroll access problems.',
    articleType: 'GUIDE',
    category: 'APPLICATION',
    serviceId: null,
    serviceName: null,
    tags: ['payroll', 'database'],
    status,
    authorName: 'Rajesh Iyer',
    viewCount: 1200,
    helpfulCount: 10,
    notHelpfulCount: 1,
    averageRating: 4.5,
    ratingCount: 20,
    readMinutes: 12,
    publishedAt: null,
    updatedAt: '2026-11-01T10:00:00Z',
    score: null,
    demo: false,
    content: '## Resolution\n\n1. Check the database pool.',
    linkedIncidentCount: 3,
    ...overrides,
  };
}

const versions = [
  { version: 2, title: 'Payroll application troubleshooting guide', summary: '', editorName: 'Rajesh Iyer', changeNote: 'Added DB pool check', createdAt: '2026-11-01T10:00:00Z' },
  { version: 1, title: 'Payroll troubleshooting', summary: '', editorName: 'Priya Raman', changeNote: 'Initial version', createdAt: '2026-10-01T10:00:00Z' },
];

function setup(status: ArticleStatus, extra: Parameters<typeof mockApi>[0] = {}) {
  mockApi({
    'GET /knowledge/1001': json(detail(status)),
    'GET /knowledge/1001/versions': json(versions),
    'GET /services': json([]),
    ...extra,
  });
}

function renderPage(role: Role) {
  return renderWithProviders(
    <Routes>
      <Route path="/knowledge/:id" element={<ArticlePage />} />
      <Route path="/knowledge" element={<p>Knowledge list</p>} />
    </Routes>,
    { route: '/knowledge/1001', auth: makeAuth(makeUser(role)) },
  );
}

describe('ArticlePage', () => {
  it('renders content, metadata, tags, linked incidents and versions', async () => {
    setup('PUBLISHED');
    renderPage('SUPPORT_AGENT');
    expect(await screen.findByRole('heading', { level: 1, name: 'Payroll application troubleshooting guide' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Resolution' })).toBeInTheDocument();
    expect(screen.getByLabelText('Tags')).toHaveTextContent('#payroll');
    expect(screen.getByText('Linked incidents').nextElementSibling).toHaveTextContent('3');
    const versionPanel = await screen.findByRole('region', { name: 'Versions' });
    expect(await within(versionPanel).findByText('“Added DB pool check”')).toBeInTheDocument();
    // Agents are not publishers: no restore, publish, archive or delete.
    expect(within(versionPanel).queryByRole('button', { name: /Restore/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Publish/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Archive/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Delete/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Edit/ })).toBeInTheDocument();
  });

  it('staff submit a draft for review', async () => {
    setup('DRAFT', { 'POST /knowledge/1001/submit': json(detail('IN_REVIEW')) });
    renderPage('SUPPORT_AGENT');
    const user = userEvent.setup();
    expect(await screen.findByText(/Draft — visible to staff only/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Publish/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Submit for review/ }));
    await waitFor(() => expect(requests.some((r) => r.method === 'POST' && r.url === '/knowledge/1001/submit')).toBe(true));
    expect(await screen.findByText(/In review — waiting for a knowledge publisher/)).toBeInTheDocument();
  });

  it('publishers publish an article in review and can restore older versions', async () => {
    setup('IN_REVIEW', {
      'POST /knowledge/1001/publish': json(detail('PUBLISHED')),
      'POST /knowledge/1001/versions/1/restore': json(detail('IN_REVIEW')),
    });
    renderPage('IT_MANAGER');
    const user = userEvent.setup();
    const publish = await screen.findByRole('button', { name: /Publish/ });
    expect(publish).toHaveClass('gold-btn');
    expect(screen.queryByRole('button', { name: /Delete/ })).not.toBeInTheDocument();
    await user.click(publish);
    await waitFor(() => expect(requests.some((r) => r.method === 'POST' && r.url === '/knowledge/1001/publish')).toBe(true));

    const versionPanel = screen.getByRole('region', { name: 'Versions' });
    // The current (latest) version cannot be restored.
    expect(within(versionPanel).queryByRole('button', { name: 'Restore version 2' })).not.toBeInTheDocument();
    await user.click(await within(versionPanel).findByRole('button', { name: 'Restore version 1' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Restore version' }));
    await waitFor(() => expect(requests.some((r) => r.method === 'POST' && r.url === '/knowledge/1001/versions/1/restore')).toBe(true));
  });

  it('publishers archive a published article after confirming', async () => {
    setup('PUBLISHED', { 'POST /knowledge/1001/archive': json(detail('ARCHIVED')) });
    renderPage('INCIDENT_MANAGER');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /Archive/ }));
    const dialog = screen.getByRole('dialog', { name: 'Archive KB-1001' });
    await user.click(within(dialog).getByRole('button', { name: 'Archive article' }));
    await waitFor(() => expect(requests.some((r) => r.method === 'POST' && r.url === '/knowledge/1001/archive')).toBe(true));
  });

  it('KB deleters delete an article after confirming and return to the list', async () => {
    setup('PUBLISHED', { 'DELETE /knowledge/1001': { status: 204 } });
    renderPage('IT_ADMIN');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /Delete/ }));
    const dialog = screen.getByRole('dialog', { name: 'Delete KB-1001' });
    expect(dialog).toHaveTextContent('3 incidents citing it will lose the reference');
    await user.click(within(dialog).getByRole('button', { name: 'Delete article' }));
    expect(await screen.findByText('Knowledge list')).toBeInTheDocument();
    expect(requests.some((r) => r.method === 'DELETE' && r.url === '/knowledge/1001')).toBe(true);
  });

  it('employees read and rate, without any workflow action or version history', async () => {
    setup('PUBLISHED', { 'POST /knowledge/1001/feedback': json(detail('PUBLISHED', { ratingCount: 21 })) });
    renderPage('EMPLOYEE');
    const user = userEvent.setup();
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit|Publish|Archive|Delete|Submit/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Versions' })).not.toBeInTheDocument();
    await user.click(within(screen.getByRole('radiogroup', { name: 'Your rating' })).getByRole('radio', { name: '4 stars' }));
    await waitFor(() => expect(requests.find((r) => r.url === '/knowledge/1001/feedback')?.body).toEqual({ rating: 4 }));
    expect(await screen.findByText(/You rated this 4 out of 5/)).toBeInTheDocument();
  });
});

describe('ArticleEditor', () => {
  it('has no status picker; a new article is saved as a draft with its change note', async () => {
    const saved = detail('DRAFT');
    mockApi({ 'GET /services': json([]), 'POST /knowledge': json(saved, 201) });
    const onSaved = vi.fn();
    renderWithProviders(<ArticleEditor article={null} onClose={vi.fn()} onSaved={onSaved} />, { auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    const user = userEvent.setup();
    expect(screen.queryByLabelText('Status')).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Title'), 'Payroll troubleshooting');
    await user.type(screen.getByLabelText('Summary'), 'How to fix payroll access problems.');
    await user.selectOptions(screen.getByLabelText('Type'), 'FAQ');
    await user.type(screen.getByLabelText('Tags'), 'payroll, vpn');
    await user.type(screen.getByLabelText('Content'), 'Check the database pool and restart the service.');
    await user.type(screen.getByLabelText('Change note'), 'Initial version');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(saved));
    expect(requests.find((r) => r.method === 'POST' && r.url === '/knowledge')?.body).toEqual({
      title: 'Payroll troubleshooting',
      summary: 'How to fix payroll access problems.',
      content: 'Check the database pool and restart the service.',
      articleType: 'FAQ',
      category: 'APPLICATION',
      serviceId: null,
      tags: ['payroll', 'vpn'],
      status: 'DRAFT',
      changeNote: 'Initial version',
    });
  });

  it('editing never sends a status', async () => {
    const existing = detail('PUBLISHED');
    mockApi({ 'GET /services': json([]), 'PUT /knowledge/1001': json(existing) });
    renderWithProviders(<ArticleEditor article={existing} onClose={vi.fn()} />, { auth: makeAuth(makeUser('IT_MANAGER')) });
    const user = userEvent.setup();
    expect(screen.getByText(/Saving stores a new version/)).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Title'));
    await user.type(screen.getByLabelText('Title'), 'Payroll guide v2');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(requests.some((r) => r.method === 'PUT' && r.url === '/knowledge/1001')).toBe(true));
    const body = requests.find((r) => r.method === 'PUT')?.body as Record<string, unknown>;
    expect(body.title).toBe('Payroll guide v2');
    expect(body).not.toHaveProperty('status');
    expect(body).not.toHaveProperty('changeNote');
  });
});
