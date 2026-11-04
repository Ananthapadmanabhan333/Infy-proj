import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiErrorBody, json, mockApi } from '../../test/mockApi';
import { makeAuth, renderWithProviders } from '../../test/utils';
import DocsPage, { MODULES } from './DocsPage';
import { availableNavLeaves } from '../../lib/nav';
import PrivacyPage from './PrivacyPage';
import SupportPage from './SupportPage';
import TermsPage from './TermsPage';

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});

const guest = () => ({ auth: makeAuth(null) });

describe('public pages', () => {
  it('DocsPage documents getting started, the eleven roles, every available sidebar module and AI transparency', () => {
    renderWithProviders(<DocsPage />, guest());
    expect(screen.getByRole('heading', { level: 1, name: 'SA product overview' })).toBeInTheDocument();
    for (const id of ['getting-started', 'roles', 'modules', 'ai-transparency', 'security']) {
      expect(document.getElementById(id)).not.toBeNull();
    }
    const [roles, modules] = screen.getAllByRole('table');
    expect(within(roles).getAllByRole('row')).toHaveLength(1 + 11);
    expect(within(modules).getAllByRole('row')).toHaveLength(1 + availableNavLeaves().length);
    // Modules whose page is not built yet (available: false) are not documented as features.
    expect(MODULES.map(([, route]) => route)).toEqual(availableNavLeaves().map((leaf) => leaf.to));
    expect(screen.getByText(/fallback output is never presented as AI-generated/)).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Sign in' })[0]).toHaveAttribute('href', '/login');
    for (const link of screen.getAllByRole('link', { name: 'Request access' })) expect(link).toHaveAttribute('href', '/register');
    expect(document.title).toBe('SA | Documentation');
  });

  it('SupportPage shows the organisation and support e-mail from /api/public/info', async () => {
    mockApi({ 'GET /public/info': json({ organisationName: 'Acme Corp', supportEmail: 'it-help@acme.test', version: '2.1.0' }) });
    renderWithProviders(<SupportPage />, guest());
    expect(await screen.findAllByText('Acme Corp')).not.toHaveLength(0);
    expect(screen.getByRole('link', { name: 'it-help@acme.test' })).toHaveAttribute('href', 'mailto:it-help@acme.test');
    expect(screen.getByRole('link', { name: /E-mail support/ })).toHaveAttribute('href', 'mailto:it-help@acme.test');
    expect(screen.getByRole('link', { name: 'Forgot password' })).toHaveAttribute('href', '/forgot-password');
    // Footer "Contact" (/support#contact) lands on this section; the status card links to the public status page.
    expect(document.getElementById('contact')).toHaveAccessibleName(/Contact your administrators/);
    expect(screen.getByRole('link', { name: 'View the status page' })).toHaveAttribute('href', '/status');
    expect(document.title).toBe('SA | Support');
  });

  it('SupportPage explains when no support address is published', async () => {
    mockApi({ 'GET /public/info': json({ organisationName: 'Acme Corp', supportEmail: null, version: '2.1.0' }) });
    renderWithProviders(<SupportPage />, guest());
    expect(await screen.findByText(/Not published/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /E-mail support/ })).not.toBeInTheDocument();
  });

  it('SupportPage falls back gracefully when /api/public/info is missing (404)', async () => {
    mockApi({ 'GET /public/info': { status: 404, data: apiErrorBody(404, 'Not found') } });
    renderWithProviders(<SupportPage />, guest());
    expect(await screen.findByText(/Contact details are not available from this deployment right now/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Request access' })).toHaveAttribute('href', '/register');
  });

  it('PrivacyPage describes what is stored, the single HttpOnly cookie and the absence of tracking', () => {
    renderWithProviders(<PrivacyPage />, guest());
    expect(screen.getByRole('heading', { level: 1, name: 'Privacy Policy' })).toBeInTheDocument();
    for (const heading of ['Accounts and roles', 'Operational records', 'Audit log', 'AI interaction log']) {
      expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument();
    }
    expect(screen.getByText(/Exactly one cookie:/).closest('li')).toHaveTextContent('/api/auth');
    expect(screen.getByText(/No tracking or advertising cookies, and no third-party analytics/)).toBeInTheDocument();
    expect(screen.getByText(/responsibility of the organisation operating/)).toBeInTheDocument();
  });

  it('TermsPage is clearly marked as a template to review and replace', () => {
    renderWithProviders(<TermsPage />, guest());
    expect(screen.getByRole('heading', { level: 1, name: 'Terms and Conditions' })).toBeInTheDocument();
    const notice = screen.getByRole('note', { name: 'Template notice' });
    expect(notice).toHaveTextContent('Template — not yet adopted.');
    expect(notice).toHaveTextContent('must review it');
  });

  it('every public page uses the shared layout (navbar, main, footer)', () => {
    for (const Page of [DocsPage, SupportPage, PrivacyPage, TermsPage]) {
      const { unmount } = renderWithProviders(<Page />, guest());
      expect(screen.getByRole('banner')).toBeInTheDocument();
      expect(screen.getByRole('main')).toBeInTheDocument();
      expect(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'Privacy Policy' })).toHaveAttribute('href', '/privacy');
      unmount();
    }
  });
});
