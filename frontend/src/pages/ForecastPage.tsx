import { Navigate, useSearchParams } from 'react-router-dom';

/**
 * Release 2: the statistical forecast lives in Analytics → Operations ("Incident Trend & Forecast" and
 * "Forecast Outlook", see components/analytics/OperationsTab). Kept as a redirect for old links; the
 * reporting period (`?days=`) is carried over.
 */
export default function ForecastPage() {
  const [params] = useSearchParams();
  const next = new URLSearchParams();
  const days = params.get('days');
  if (days) next.set('days', days);
  const query = next.toString();
  return <Navigate to={`/analytics${query ? `?${query}` : ''}`} replace />;
}
