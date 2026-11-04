import { Link } from 'react-router-dom';
import type { CiRef } from '../../lib/opsTypes';

/** `affectedCis` names that are not one of the linked CIs (ignoring case) — legacy free text kept as is. */
export function unlinkedCiNames(affectedCis: string[] | null | undefined, cis: CiRef[] | null | undefined): string[] {
  const linked = new Set((cis ?? []).map((ci) => ci.name.toLowerCase()));
  return (affectedCis ?? []).filter((name) => !linked.has(name.toLowerCase()));
}

/** Problem / change "Configuration items": linked CIs open the CMDB; legacy names stay plain text. */
export function AffectedCis({ cis, affectedCis }: { cis?: CiRef[] | null; affectedCis?: string[] | null }) {
  const linked = cis ?? [];
  const other = unlinkedCiNames(affectedCis, linked);
  if (!linked.length && !other.length) return <span className="text-muted">—</span>;
  return (
    <span className="inline-flex flex-wrap items-center gap-x-1.5 gap-y-1">
      {linked.map((ci, index) => (
        <span key={ci.id}>
          <Link to={`/cmdb/${ci.id}`}>{ci.name}</Link>
          {index < linked.length - 1 || other.length ? ',' : ''}
        </span>
      ))}
      {other.length > 0 && <span title="Not linked to a CMDB record">{other.join(', ')}</span>}
    </span>
  );
}
