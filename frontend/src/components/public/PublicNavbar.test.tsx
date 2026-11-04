import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useHealth } from '../../api/health';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import { PublicNavbar } from './PublicNavbar';
import { platformStatus } from './StatusPill';

vi.mock('../../api/health', () => ({ useHealth: vi.fn() }));

type HealthState = { isPending: boolean; isError: boolean; data?: Record<string, unknown> };

function mockHealth(state: HealthState) {
  vi.mocked(useHealth).mockReturnValue(state as unknown as ReturnType<typeof useHealth>);
}

const UP: HealthState = { isPending: false, isError: false, data: { status: 'UP', database: 'UP' } };

describe('PublicNavbar', () => {
  beforeEach(() => mockHealth(UP));

  it('shows the brand, four menu buttons and the guest actions', () => {
    renderWithProviders(<PublicNavbar />, { auth: makeAuth(null) });
    const home = screen.getByRole('link', { name: 'SA — Enterprise Operations Platform, home' });
    expect(home).toHaveAttribute('href', '/');
    expect(home.querySelector('svg[data-brand="wordmark"]')).not.toBeNull();
    for (const label of ['Platform', 'Solutions', 'Resources', 'Company']) {
      expect(screen.getByRole('button', { name: label })).toHaveAttribute('aria-expanded', 'false');
    }
    expect(screen.getByRole('link', { name: 'Documentation' })).toHaveAttribute('href', '/docs');
    expect(screen.getByRole('link', { name: 'Login' })).toHaveAttribute('href', '/login');
    expect(screen.getByRole('link', { name: /Access Workspace/ })).toHaveAttribute('href', '/login');
    expect(screen.queryByRole('link', { name: /Open Workspace/ })).not.toBeInTheDocument();
  });

  it('opens a dropdown with links to landing sections and public pages; Escape closes it and restores focus', async () => {
    const user = userEvent.setup();
    renderWithProviders(<PublicNavbar />, { auth: makeAuth(null) });
    const platform = screen.getByRole('button', { name: 'Platform' });
    await user.click(platform);
    expect(platform).toHaveAttribute('aria-expanded', 'true');
    const menu = screen.getByRole('list', { name: 'Platform' });
    expect(within(menu).getAllByRole('link')).toHaveLength(12);
    expect(within(menu).getByRole('link', { name: /Change Management/ })).toHaveAttribute('href', '/#change-management');
    expect(within(menu).getByRole('link', { name: /Incident Management/ })).toHaveAttribute('href', '/#incident-management');

    await user.click(within(menu).getByRole('link', { name: /Service Desk/ }));
    expect(platform).toHaveAttribute('aria-expanded', 'false');

    await user.click(screen.getByRole('button', { name: 'Resources' }));
    const resources = screen.getByRole('list', { name: 'Resources' });
    expect(within(resources).getByRole('link', { name: /Documentation/ })).toHaveAttribute('href', '/docs');
    expect(within(resources).getByRole('link', { name: /Roles & access/ })).toHaveAttribute('href', '/docs#roles');
    within(resources).getByRole('link', { name: /Documentation/ }).focus();
    await user.keyboard('{Escape}');
    expect(screen.getByRole('button', { name: 'Resources' })).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByRole('button', { name: 'Resources' })).toHaveFocus();
    expect(screen.queryByRole('list', { name: 'Resources' })).not.toBeInTheDocument();
  });

  it('keeps one menu open at a time and closes on an outside click', async () => {
    const user = userEvent.setup();
    renderWithProviders(<PublicNavbar />, { auth: makeAuth(null) });
    await user.click(screen.getByRole('button', { name: 'Solutions' }));
    await user.click(screen.getByRole('button', { name: 'Company' }));
    expect(screen.getByRole('button', { name: 'Solutions' })).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByRole('button', { name: 'Company' })).toHaveAttribute('aria-expanded', 'true');
    const company = screen.getByRole('list', { name: 'Company' });
    expect(within(company).getByRole('link', { name: /About SA/ })).toHaveAttribute('href', '/#about');
    expect(within(company).getByRole('link', { name: /Contact/ })).toHaveAttribute('href', '/support#contact');
    expect(within(company).getByRole('link', { name: /Privacy Policy/ })).toHaveAttribute('href', '/privacy');
    expect(within(company).getByRole('link', { name: /Terms and Conditions/ })).toHaveAttribute('href', '/terms');

    await user.click(document.body);
    expect(screen.getByRole('button', { name: 'Company' })).toHaveAttribute('aria-expanded', 'false');
  });

  it('is keyboard navigable: ArrowDown opens and focuses the first item, arrows move, End jumps to the last', async () => {
    const user = userEvent.setup();
    renderWithProviders(<PublicNavbar />, { auth: makeAuth(null) });
    screen.getByRole('button', { name: 'Solutions' }).focus();
    await user.keyboard('{ArrowDown}');
    const menu = screen.getByRole('list', { name: 'Solutions' });
    const links = within(menu).getAllByRole('link');
    expect(links[0]).toHaveFocus();
    await user.keyboard('{ArrowDown}');
    expect(links[1]).toHaveFocus();
    await user.keyboard('{ArrowUp}{ArrowUp}');
    expect(links[links.length - 1]).toHaveFocus();
    await user.keyboard('{Home}');
    expect(links[0]).toHaveFocus();
    await user.keyboard('{End}');
    expect(links[links.length - 1]).toHaveFocus();
  });

  it('opens an accessible mobile drawer that traps focus and returns it on Escape', async () => {
    const user = userEvent.setup();
    renderWithProviders(<PublicNavbar />, { auth: makeAuth(null) });
    const toggle = screen.getByRole('button', { name: 'Open menu' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);

    const drawer = screen.getByRole('dialog', { name: 'Site menu' });
    expect(drawer).toHaveAttribute('aria-modal', 'true');
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const close = within(drawer).getByRole('button', { name: 'Close menu' });
    expect(close).toHaveFocus();
    expect(document.body.style.overflow).toBe('hidden');

    // Shift+Tab from the first element wraps to the last one (Access Workspace).
    await user.keyboard('{Shift>}{Tab}{/Shift}');
    expect(within(drawer).getByRole('link', { name: /Access Workspace/ })).toHaveFocus();

    const group = within(drawer).getByRole('button', { name: 'Platform' });
    expect(group).toHaveAttribute('aria-expanded', 'false');
    await user.click(group);
    expect(group).toHaveAttribute('aria-expanded', 'true');
    expect(within(drawer).getByRole('link', { name: 'Knowledge Base' })).toHaveAttribute('href', '/#knowledge-base');
    expect(within(drawer).getByRole('link', { name: 'Documentation' })).toHaveAttribute('href', '/docs');

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(toggle).toHaveFocus();
    expect(document.body.style.overflow).toBe('');
  });

  it('closes the drawer from its close button', async () => {
    const user = userEvent.setup();
    renderWithProviders(<PublicNavbar />, { auth: makeAuth(null) });
    await user.click(screen.getByRole('button', { name: 'Open menu' }));
    await user.click(screen.getByRole('button', { name: 'Close menu' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows Open Workspace and the avatar instead of Login for signed-in visitors', () => {
    renderWithProviders(<PublicNavbar />, { auth: makeAuth(makeUser('SUPPORT_AGENT', { fullName: 'Riya Varma' })) });
    expect(screen.getByRole('link', { name: /Open Workspace/ })).toHaveAttribute('href', '/dashboard');
    expect(screen.getByRole('link', { name: 'Your profile (Riya Varma)' })).toHaveAttribute('href', '/profile');
    expect(screen.queryByRole('link', { name: 'Login' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Access Workspace/ })).not.toBeInTheDocument();
  });
});

describe('status pill', () => {
  it.each<[HealthState, string, string]>([
    [{ isPending: true, isError: false }, 'checking', 'Checking status'],
    [UP, 'operational', 'System Operational'],
    [{ isPending: false, isError: false, data: { status: 'DEGRADED', database: 'DOWN' } }, 'degraded', 'System Degraded'],
    [{ isPending: false, isError: true }, 'unavailable', 'System Unavailable'],
  ])('reflects /api/health (%#)', (state, status, label) => {
    mockHealth(state);
    renderWithProviders(<PublicNavbar />, { auth: makeAuth(null) });
    const pill = screen.getByRole('status');
    expect(pill).toHaveAttribute('data-status', status);
    expect(pill).toHaveTextContent(label);
  });

  it('never assumes "operational" without a successful UP response', () => {
    expect(platformStatus({ isPending: false, isError: false, data: {} })).toBe('degraded');
    expect(platformStatus({ isPending: false, isError: false })).toBe('checking');
    expect(platformStatus({ isPending: false, isError: true, data: { status: 'UP' } })).toBe('unavailable');
    expect(platformStatus({ isPending: false, isError: false, data: { status: 'up' } })).toBe('operational');
  });
});
