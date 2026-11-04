import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { makeSla, makeSummary } from '../../pages/incidents/fixtures.test-utils';
import { renderWithProviders } from '../../test/utils';
import { IncidentTable } from './IncidentTable';

const incidents = [
  makeSummary(),
  makeSummary({ id: 2, number: 'INC-7843', title: 'Email synchronization delay', priority: 'MEDIUM', assigneeName: null, status: 'NEW', sla: makeSla({ health: 'BREACHED', remainingSeconds: -300 }) }),
];

describe('IncidentTable', () => {
  it('renders the reference columns with badges, assignee chip and SLA health', () => {
    renderWithProviders(<IncidentTable incidents={incidents} />);
    const table = screen.getByRole('table', { name: 'Incidents' });
    expect(within(table).getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual(['ID', 'Title', 'Priority', 'Service', 'Assignee', 'SLA', 'Status']);
    const second = within(table).getByRole('row', { name: /INC-7843/ });
    expect(within(second).getByText('Medium')).toBeInTheDocument();
    expect(within(second).getByText('Unassigned')).toBeInTheDocument();
    expect(within(second).getByText(/Breached/)).toHaveClass('breached');
    expect(within(second).getByText('New')).toBeInTheDocument();
    // Without onSortChange the headers are plain text (other screens reuse the table read-only).
    expect(within(table).queryByRole('button')).not.toBeInTheDocument();
  });

  it('supports checkbox selection without opening the row, and opens the detail on row click', async () => {
    const onSelectedChange = vi.fn();
    renderWithProviders(
      <Routes>
        <Route path="/" element={<IncidentTable incidents={incidents} columns={['number', 'title', 'status', 'created']} selected={new Set([1])} onSelectedChange={onSelectedChange} onSortChange={vi.fn()} sort={{ key: 'createdAt', dir: 'desc' }} />} />
        <Route path="/incidents/:id" element={<p>Detail page</p>} />
      </Routes>,
    );
    const user = userEvent.setup();
    expect(screen.getByRole('checkbox', { name: /Select INC-7846/ })).toBeChecked();
    await user.click(screen.getByRole('checkbox', { name: /Select INC-7843/ }));
    expect(onSelectedChange).toHaveBeenCalledWith(new Set([1, 2]));
    expect(screen.queryByText('Detail page')).not.toBeInTheDocument();

    expect(screen.getByRole('button', { name: /Created/ })).toBeInTheDocument();
    await user.click(screen.getByRole('row', { name: /INC-7843/ }));
    expect(screen.getByText('Detail page')).toBeInTheDocument();
  });
});
