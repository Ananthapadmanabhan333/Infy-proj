import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ShieldCheck, Timer } from 'lucide-react';
import { describe, expect, it } from 'vitest';
import { deltaTone, KpiCard } from './KpiCard';

describe('<KpiCard />', () => {
  it('renders the icon tile, label, value and an up delta coloured as good when higher is better', () => {
    const { container } = render(<KpiCard icon={<ShieldCheck />} label="SLA Compliance" value="96.4%" delta={2.1} deltaUnit="pp" higherIsBetter tone="success" />);
    expect(screen.getByText('SLA Compliance')).toBeInTheDocument();
    expect(screen.getByText('96.4%')).toBeInTheDocument();
    const delta = screen.getByTestId('kpi-delta');
    expect(delta).toHaveTextContent('up 2.1pp');
    expect(delta).toHaveClass('good');
    expect(container.querySelector('.kpi')).toHaveClass('success');
    expect(container.querySelector('.kpi-icon svg')).not.toBeNull();
  });

  it('colours an increase as bad for "higher is worse" metrics and a decrease as good', () => {
    const { rerender } = render(<KpiCard icon={<Timer />} label="MTTR" value="28 min" delta={18} higherIsBetter={false} />);
    expect(screen.getByTestId('kpi-delta')).toHaveClass('bad');
    expect(screen.getByTestId('kpi-delta')).toHaveTextContent('18%');
    rerender(<KpiCard icon={<Timer />} label="MTTR" value="28 min" delta={-18} higherIsBetter={false} />);
    expect(screen.getByTestId('kpi-delta')).toHaveClass('good');
    expect(screen.getByTestId('kpi-delta')).toHaveTextContent('down 18%');
  });

  it('hides the delta when there is no comparison', () => {
    const { rerender } = render(<KpiCard icon={<Timer />} label="MTTR" value="—" delta={null} />);
    expect(screen.queryByTestId('kpi-delta')).not.toBeInTheDocument();
    rerender(<KpiCard icon={<Timer />} label="MTTR" value="—" delta="—" />);
    expect(screen.queryByTestId('kpi-delta')).not.toBeInTheDocument();
  });

  it('accepts a preformatted delta with an explicit tone (Release 1 API)', () => {
    render(<KpiCard icon={<Timer />} label="Created" value="12" delta="↑ 12%" deltaTone="bad" deltaLabel="vs previous 7 days" />);
    const delta = screen.getByTestId('kpi-delta');
    expect(delta).toHaveTextContent('↑ 12%');
    expect(delta).toHaveClass('bad');
    expect(delta).toHaveAttribute('title', 'vs previous 7 days');
  });

  it('can be a link to the detailed view', () => {
    render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <KpiCard icon={<Timer />} label="At Risk" value={34} to="/sla-monitor?health=AT_RISK" />
      </MemoryRouter>,
    );
    expect(screen.getByRole('link', { name: 'At Risk: 34' })).toHaveAttribute('href', '/sla-monitor?health=AT_RISK');
  });

  it('maps legacy tones onto the spec palette', () => {
    const { container } = render(<KpiCard icon={<Timer />} label="Paused" value={2} tone="blue" />);
    expect(container.querySelector('.kpi')).toHaveClass('neutral');
  });
});

describe('deltaTone', () => {
  it('is neutral for zero or missing values', () => {
    expect(deltaTone(0, true)).toBe('neutral');
    expect(deltaTone(null, false)).toBe('neutral');
    expect(deltaTone(Number.NaN, false)).toBe('neutral');
  });
});
