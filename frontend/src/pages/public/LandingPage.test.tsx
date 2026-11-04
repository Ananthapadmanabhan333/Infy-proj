import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CAPABILITIES } from '../../components/public/content';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import LandingPage from './LandingPage';
import { availableNavLeaves } from '../../lib/nav';
import { ROLES } from '../../lib/constants';

const scrollIntoView = vi.fn();

beforeEach(() => {
  scrollIntoView.mockReset();
  Element.prototype.scrollIntoView = scrollIntoView;
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  mockApi({ 'GET /health': json({ status: 'UP', database: 'UP' }) });
});

afterEach(() => {
  // jsdom has no scrollIntoView; remove the stub again.
  delete (Element.prototype as Partial<Element>).scrollIntoView;
});

function renderLanding(signedIn = false) {
  return renderWithProviders(<LandingPage />, { auth: makeAuth(signedIn ? makeUser('IT_MANAGER') : null) });
}

describe('LandingPage', () => {
  it('renders the reference composition with semantic landmarks', async () => {
    renderLanding();
    expect(screen.getByRole('heading', { level: 1, name: /Complex Operations\.\s*Under Control\./ })).toBeInTheDocument();
    expect(screen.getByText('AI-POWERED IT OPERATIONS PLATFORM')).toBeInTheDocument();
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main');
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveAttribute('href', '#main');
    expect(screen.getByRole('heading', { name: 'How It Works' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Built for Enterprise Reliability' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Checking status');
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('System Operational'));
  });

  it('routes the CTAs to /login for guests', async () => {
    renderLanding();
    const hero = screen.getByRole('region', { name: /Complex Operations/ });
    expect(within(hero).getByRole('link', { name: /Enter Workspace/ })).toHaveAttribute('href', '/login');
    expect(screen.getByRole('link', { name: /Access Workspace/ })).toHaveAttribute('href', '/login');
    expect(screen.getByRole('link', { name: 'Login' })).toHaveAttribute('href', '/login');
    // The lazily loaded explainers repeat the call to action with the same destination.
    const explainers = await screen.findByRole('region', { name: 'One workspace for the whole operations lifecycle' });
    expect(within(explainers).getByRole('link', { name: /Enter Workspace/ })).toHaveAttribute('href', '/login');
  });

  it('routes the CTAs to /dashboard for signed-in visitors', () => {
    renderLanding(true);
    const hero = screen.getByRole('region', { name: /Complex Operations/ });
    expect(within(hero).getByRole('link', { name: /Enter Workspace/ })).toHaveAttribute('href', '/dashboard');
    expect(within(screen.getByRole('banner')).getByRole('link', { name: /Open Workspace/ })).toHaveAttribute('href', '/dashboard');
    expect(screen.queryByRole('link', { name: 'Login' })).not.toBeInTheDocument();
  });

  it('states implemented capabilities only — no customer counts, uptime, resolution times or certifications', () => {
    renderLanding();
    const facts = screen.getByRole('list', { name: 'Platform facts' });
    expect(facts).toHaveTextContent(`${availableNavLeaves().length} modules`);
    expect(ROLES).toHaveLength(11);
    expect(facts).toHaveTextContent('11 roles');
    expect(facts).toHaveTextContent('24×7');
    expect(facts).toHaveTextContent('Append-only');
    const text = document.body.textContent ?? '';
    expect(text).not.toMatch(/SOC[- ]?2|ISO 27001|99\.9|Enterprise Users|< 30 min/);
  });

  it('labels the dashboard preview as illustrative and never calls protected APIs', async () => {
    renderLanding();
    expect(screen.getByText('Demo preview · illustrative data')).toBeInTheDocument();
    expect(screen.getByRole('figure', { name: /Demo preview · illustrative data/ })).toBeInTheDocument();
    await screen.findByText('System Operational');
    expect(requests.map((request) => `${request.method} ${request.url}`)).toEqual(['GET /health']);
  });

  it('has the twelve capability cards of the brief whose scroll targets exist and are scrolled to', async () => {
    const user = userEvent.setup();
    renderLanding();
    const cards = within(screen.getByRole('region', { name: 'Platform capabilities' })).getAllByRole('link');
    expect(cards.map((card) => card.querySelector('b')?.textContent)).toEqual([
      'Incident Management',
      'Service Desk',
      'Problem Management',
      'Change Management',
      'Asset Management',
      'CMDB',
      'SA AI',
      'Workflow Automation',
      'SLA Monitoring',
      'Analytics',
      'Knowledge Base',
      'Runbooks',
    ]);
    expect(CAPABILITIES).toHaveLength(12);

    // The explainers are a lazy chunk; every card's anchor exists once it has loaded.
    expect(await screen.findByRole('heading', { name: 'One workspace for the whole operations lifecycle' })).toBeInTheDocument();
    for (const card of cards) {
      const id = card.getAttribute('href')!.split('#')[1];
      expect(document.getElementById(id), id).not.toBeNull();
    }

    await user.click(cards[3]);
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalled());
    expect(scrollIntoView.mock.contexts.at(-1)).toBe(document.getElementById('change-management'));
    expect(document.getElementById('change-management')).toHaveFocus();
    expect(within(document.getElementById('change-management')!).getByText('/changes')).toBeInTheDocument();
  });

  it('smooth-scrolls Explore Platform to the capabilities and See It In Action to the explainers', async () => {
    const user = userEvent.setup();
    renderLanding();
    await user.click(screen.getByRole('link', { name: /Explore Platform/ }));
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' }));
    expect(scrollIntoView.mock.contexts.at(-1)).toBe(document.getElementById('capabilities'));

    await user.click(screen.getByRole('link', { name: /See It In Action/ }));
    await waitFor(() => expect(scrollIntoView.mock.contexts.at(-1)).toBe(document.getElementById('platform')));
  });

  it('scrolls instantly when the visitor prefers reduced motion', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query.includes('reduce'), media: query, addEventListener() {}, removeEventListener() {} }));
    renderLanding();
    await user.click(screen.getByRole('link', { name: /Explore Platform/ }));
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'auto', block: 'start' }));
    vi.unstubAllGlobals();
  });

  it('links the footer to the real public pages', () => {
    renderLanding();
    const footer = screen.getByRole('contentinfo');
    expect(within(footer).getByRole('link', { name: 'Documentation' })).toHaveAttribute('href', '/docs');
    expect(within(footer).getByRole('link', { name: 'Support' })).toHaveAttribute('href', '/support');
    expect(within(footer).getByRole('link', { name: 'Privacy Policy' })).toHaveAttribute('href', '/privacy');
    expect(within(footer).getByRole('link', { name: 'Terms and Conditions' })).toHaveAttribute('href', '/terms');
    expect(within(footer).getByRole('link', { name: 'How it works' })).toHaveAttribute('href', '/#how-it-works');
    expect(within(footer).getByRole('link', { name: 'SA AI' })).toHaveAttribute('href', '/#sa-ai');
    expect(within(footer).getByRole('link', { name: 'Runbooks' })).toHaveAttribute('href', '/#runbooks');
    expect(within(footer).getByRole('link', { name: 'About SA' })).toHaveAttribute('href', '/#about');
    expect(within(footer).getByRole('link', { name: 'Contact' })).toHaveAttribute('href', '/support#contact');
    expect(within(footer).getByRole('link', { name: 'Service status' })).toHaveAttribute('href', '/status');
    expect(footer).toHaveTextContent('Intelligent Service Management. Unified Operations.');
    expect(footer).toHaveTextContent(`© ${new Date().getFullYear()} SA · Enterprise Operations Platform`);
    expect(footer).not.toHaveTextContent('SA 360');
  });

  it('has an About SA section that the footer link scrolls to', async () => {
    const user = userEvent.setup();
    renderLanding();
    await user.click(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'About SA' }));
    const about = await screen.findByRole('region', { name: 'About SA' });
    await waitFor(() => expect(scrollIntoView.mock.contexts.at(-1)).toBe(about));
    expect(about).toHaveTextContent('Intelligent Service Management. Unified Operations.');
    expect(within(about).getByRole('link', { name: 'Contact' })).toHaveAttribute('href', '/support#contact');
  });

  it('adds service health and an operational activity feed to the illustrative preview', () => {
    renderLanding();
    const preview = screen.getByRole('figure', { name: /Demo preview · illustrative data/ });
    expect(preview).toHaveTextContent('Service Health');
    expect(preview).toHaveTextContent('Operational Activity');
    expect(preview).toHaveAccessibleName(/service health and an operational activity feed/);
    // The preview sidebar mirrors the grouped workspace navigation with the new wordmark.
    expect(preview).toHaveTextContent('Service Management');
    expect(preview.querySelector('svg[data-brand="wordmark"]')).not.toBeNull();
  });

  it('sets the page title and description, and restores them on unmount', () => {
    document.title = 'SA | Enterprise Operations Platform (previous)';
    const { unmount } = renderLanding();
    expect(document.title).toBe('SA | Enterprise Operations Platform');
    const description = document.head.querySelector('meta[name="description"]')?.getAttribute('content');
    expect(description).toMatch(/^Intelligent Service Management\. Unified Operations\./);
    expect(description).toMatch(/problem and change management, the service desk/);
    unmount();
    expect(document.title).toBe('SA | Enterprise Operations Platform (previous)');
  });
});
