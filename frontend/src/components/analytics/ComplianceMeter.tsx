import { PALETTE } from '../../lib/constants';
import { formatPercent } from '../../lib/format';
import './meter.css';

/** Colour for a compliance percentage: ≥95 success, ≥85 gold, below that critical. */
export function complianceColor(value: number | null | undefined): string {
  if (value === null || value === undefined) return 'transparent';
  if (value >= 95) return PALETTE.success;
  if (value >= 85) return PALETTE.gold;
  return PALETTE.critical;
}

/** Small bar + percentage used in compliance table cells. */
export function ComplianceMeter({ value }: { value: number | null | undefined }) {
  return (
    <span className="an-meter">
      <span className="health-track" aria-hidden="true">
        <i style={{ width: `${Math.max(0, Math.min(100, value ?? 0))}%`, background: complianceColor(value) }} />
      </span>
      <b className={value !== null && value !== undefined && value >= 95 ? 'green-text' : value !== null && value !== undefined && value < 85 ? 'red-text' : ''}>
        {formatPercent(value)}
      </b>
    </span>
  );
}
