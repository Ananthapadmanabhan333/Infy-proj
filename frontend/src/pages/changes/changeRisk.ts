import type { ChangeRisk, ChangeType, RiskFactor, RiskQuestion } from '../../lib/itsmTypes';

/** Mirrors `change/ChangeRisk.java` (score 0–4 LOW, 5–9 MEDIUM, ≥ 10 HIGH) so the wizard can show a live score. */
export const MEDIUM_FROM = 5;
export const HIGH_FROM = 10;

export function riskLevel(score: number): ChangeRisk {
  return score >= HIGH_FROM ? 'HIGH' : score >= MEDIUM_FROM ? 'MEDIUM' : 'LOW';
}

export interface LiveRiskInput {
  type: ChangeType;
  answers: Record<string, string>;
  questions: RiskQuestion[];
  /** Tiers of the affected services. */
  serviceTiers: number[];
  plannedStart: Date | null;
}

/**
 * Live estimate of the server's assessment. The server adds the failed-change history of the services (and is
 * authoritative; business hours use the organisation time zone, here the browser's).
 */
export function liveRisk({ type, answers, questions, serviceTiers, plannedStart }: LiveRiskInput): { risk: ChangeRisk; score: number; factors: RiskFactor[] } {
  if (type === 'STANDARD') return { risk: 'LOW', score: 0, factors: [{ key: 'standard', label: 'Pre-approved standard change (template)', points: 0, source: 'COMPUTED' }] };
  const factors: RiskFactor[] = [];
  questions.forEach((question) => {
    const answer = answers[question.key];
    const option = (answer && question.options.find((candidate) => candidate.value === answer)) || question.options[1] || question.options[0];
    if (option && option.points > 0) factors.push({ key: question.key, label: `${question.text}: ${option.label}${answer ? '' : ' (not answered yet)'}`, points: option.points, source: 'ANSWER' });
  });
  const tier = serviceTiers.length ? Math.min(...serviceTiers) : 3;
  if (tier === 1) factors.push({ key: 'tier', label: 'Affects a tier-1 (business-critical) service', points: 3, source: 'COMPUTED' });
  else if (tier === 2) factors.push({ key: 'tier', label: 'Affects a tier-2 service', points: 1, source: 'COMPUTED' });
  if (serviceTiers.length > 2) factors.push({ key: 'breadth', label: `Affects ${serviceTiers.length} services`, points: 2, source: 'COMPUTED' });
  if (plannedStart) {
    const day = plannedStart.getDay();
    const hour = plannedStart.getHours();
    if (day !== 0 && day !== 6 && hour >= 9 && hour < 18) factors.push({ key: 'businessHours', label: 'Planned during business hours', points: 1, source: 'COMPUTED' });
  }
  if (type === 'EMERGENCY') factors.push({ key: 'emergency', label: 'Emergency change (expedited, less time to test)', points: 2, source: 'COMPUTED' });
  const score = factors.reduce((sum, factor) => sum + factor.points, 0);
  return { risk: riskLevel(score), score, factors };
}

/** True when the two windows overlap. */
export function overlaps(aStart: Date, aEnd: Date, bStart: Date, bEnd: Date): boolean {
  return aStart < bEnd && bStart < aEnd;
}
