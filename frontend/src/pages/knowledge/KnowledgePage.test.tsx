import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { ArticleSummary, KnowledgeStats, Role, RunbookDto } from '../../lib/types';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import KnowledgePage from './KnowledgePage';

function article(overrides: Partial<ArticleSummary> = {}): ArticleSummary {
  return {
    id: 1001,
    number: 'KB-1001',
    title: 'Payroll application troubleshooting guide',
    summary: 'How to fix payroll access problems.',
    articleType: 'GUIDE',
    category: 'APPLICATION',
    serviceId: 1,
    serviceName: 'Payroll',
    tags: ['payroll'],
    status: 'PUBLISHED',
    authorName: 'Rajesh Iyer',
    viewCount: 1200,
    helpfulCount: 10,
    notHelpfulCount: 1,
    averageRating: 4.8,
    ratingCount: 212,
    readMinutes: 12,
    publishedAt: '2026-10-01T10:00:00Z',
    updatedAt: '2026-11-01T10:00:00Z',
    score: null,
    demo: false,
    ...overrides,
  };
}

const stats: KnowledgeStats = {
  published: 300,
  drafts: 4,
  totalViews: 9000,
  resolutionRate: 40,
  countsByType: { GUIDE: 94, SOLUTION: 100, KNOWN_ERROR: 42, FAQ: 36 },
  runbookCount: 68,
};

const page = (content: ArticleSummary[]) => ({ content, page: 0, size: 10, totalElements: content.length, totalPages: 1 });

const runbook = { id: 11, title: 'Payroll service restart', serviceName: 'Payroll', steps: [{ order: 1, title: 'a', instruction: 'b', requiresApproval: false }], requiresApproval: true, estimatedMinutes: 25 } as RunbookDto;

function setup() {
  mockApi({
    'GET /knowledge/stats': json(stats),
    'GET /services': json([]),
    'GET /knowledge': (config) => {
      const params = config.params as Record<string, string>;
      if (params.sort === 'popular') return json(page([article(), article({ id: 1002, number: 'KB-1002', title: 'VPN connection troubleshooting', averageRating: 4.7, ratingCount: 189, readMinutes: 8 })]));
      if (params.q) return json(page([article({ score: 0.82 })]));
      return json(page([article({ id: 1003, number: 'KB-1003', title: 'Resetting MFA', status: 'DRAFT', averageRating: null, ratingCount: 0, readMinutes: 5 })]));
    },
    'GET /runbooks': json([runbook]),
  });
}

function renderPage(role: Role = 'SUPPORT_AGENT', route = '/knowledge') {
  return renderWithProviders(<KnowledgePage mode="all" />, { route, auth: makeAuth(makeUser(role)) });
}

describe('KnowledgePage', () => {
  it('shows the header, category tiles with counts and popular articles with ratings and read time', async () => {
    setup();
    renderPage();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Knowledge Base');
    expect(screen.getByText('Centralized knowledge for faster resolution')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Create Article/ })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Search articles, guides, runbooks' })).toHaveAttribute('placeholder', 'Search articles, guides, runbooks…');

    const tiles = screen.getByRole('group', { name: 'Knowledge categories' });
    // Incidents = SOLUTION + KNOWN_ERROR.
    expect(await within(tiles).findByText('142 articles')).toBeInTheDocument();
    expect(within(tiles).getByRole('link', { name: /Runbooks/ })).toHaveAttribute('href', '/runbooks');
    expect(within(tiles).getByText('68 runbooks')).toBeInTheDocument();
    expect(within(tiles).getByText('94 articles')).toBeInTheDocument();
    expect(within(tiles).getByText('36 articles')).toBeInTheDocument();

    const popular = await screen.findByRole('list', { name: 'Popular articles' });
    const first = within(popular).getByRole('link', { name: 'Payroll application troubleshooting guide' });
    expect(first).toHaveAttribute('href', '/knowledge/1001');
    expect(within(first).getByLabelText('Rated 4.8 out of 5 from 212 ratings')).toBeInTheDocument();
    expect(first).toHaveTextContent('12 min read');
    const popularRequest = requests.find((r) => r.url === '/knowledge' && (r.params as Record<string, string>).sort === 'popular');
    expect(popularRequest?.params).toMatchObject({ sort: 'popular', status: 'PUBLISHED' });
  });

  it('staff see unpublished articles with their status badge', async () => {
    setup();
    renderPage();
    const list = await screen.findByRole('list', { name: 'Articles' });
    expect(within(list).getByRole('link', { name: 'Resetting MFA (Draft)' })).toHaveTextContent('Draft');
  });

  it('a category tile filters the article list by type', async () => {
    setup();
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /Incidents/ }));
    await waitFor(() =>
      expect(requests.some((r) => r.url === '/knowledge' && (r.params as Record<string, string>).type === 'SOLUTION,KNOWN_ERROR')).toBe(true),
    );
    expect(screen.getByRole('button', { name: /Incidents/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('heading', { name: 'Incident solutions & known errors' })).toBeInTheDocument();
    // Popular articles give way to the filtered list.
    expect(screen.queryByRole('list', { name: 'Popular articles' })).not.toBeInTheDocument();
  });

  it('searching also lists matching runbooks as a separate group', async () => {
    setup();
    renderPage('SUPPORT_AGENT', '/knowledge?q=payroll');
    const runbooks = await screen.findByRole('region', { name: 'Runbooks' });
    expect(await within(runbooks).findByRole('link', { name: 'Runbook Payroll service restart' })).toHaveAttribute('href', '/runbooks/11');
    expect(await screen.findByRole('heading', { name: 'Articles matching “payroll”' })).toBeInTheDocument();
    expect(requests.find((r) => r.url === '/runbooks')?.params).toMatchObject({ search: 'payroll' });
  });

  it('employees cannot create articles, see the runbooks tile or search runbooks', async () => {
    setup();
    renderPage('EMPLOYEE', '/knowledge?q=payroll');
    expect(await screen.findByRole('heading', { name: 'Articles matching “payroll”' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Create Article/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Runbooks/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Runbooks' })).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Status' })).not.toBeInTheDocument();
    expect(requests.some((r) => r.url === '/runbooks')).toBe(false);
  });
});
