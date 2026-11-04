import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Pagination, pageWindow } from './Pagination';

describe('pageWindow', () => {
  it('lists every page when there are few', () => {
    expect(pageWindow(0, 4)).toEqual([0, 1, 2, 3]);
    expect(pageWindow(0, 0)).toEqual([]);
  });

  it('shows the first five pages and the last one at the start ("1 2 3 4 5 … 250")', () => {
    expect(pageWindow(0, 250)).toEqual([0, 1, 2, 3, 4, 'gap', 249]);
  });

  it('centres the window on the current page with gaps on both sides', () => {
    expect(pageWindow(50, 250)).toEqual([0, 'gap', 48, 49, 50, 51, 52, 'gap', 249]);
    expect(pageWindow(249, 250)).toEqual([0, 'gap', 245, 246, 247, 248, 249]);
  });
});

describe('<Pagination />', () => {
  it('renders "Showing x–y of N" with numbered pages', () => {
    render(<Pagination page={0} totalPages={250} totalElements={1248} size={5} onPageChange={() => undefined} />);
    expect(screen.getByText('Showing 1–5 of 1,248')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Page 1' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: 'Page 5' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Page 250' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Page 6' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
  });

  it('appends the optional label and clamps the last page range', () => {
    render(<Pagination page={2} totalPages={3} totalElements={57} size={20} onPageChange={() => undefined} label="incidents" />);
    expect(screen.getByText('Showing 41–57 of 57 incidents')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  });

  it('changes page from the numbers and the arrows', async () => {
    const onPageChange = vi.fn();
    const user = userEvent.setup();
    render(<Pagination page={1} totalPages={4} totalElements={80} size={20} onPageChange={onPageChange} />);
    await user.click(screen.getByRole('button', { name: 'Page 4' }));
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await user.click(screen.getByRole('button', { name: 'Previous page' }));
    expect(onPageChange.mock.calls).toEqual([[3], [2], [0]]);
  });

  it('renders nothing without records and hides the pager for a single page', () => {
    const { container, rerender } = render(<Pagination page={0} totalPages={0} totalElements={0} size={20} onPageChange={() => undefined} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<Pagination page={0} totalPages={1} totalElements={3} size={20} onPageChange={() => undefined} />);
    expect(screen.getByText('Showing 1–3 of 3')).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Pagination' })).not.toBeInTheDocument();
  });
});
