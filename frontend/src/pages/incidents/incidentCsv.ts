import { PRIORITY_LABELS, STATUS_LABELS } from '../../lib/constants';
import { titleCase } from '../../lib/format';
import type { IncidentSummary } from '../../lib/types';

/** Columns of the "Export selected" CSV, built client-side from the rows already loaded in the register. */
const COLUMNS: { header: string; value: (incident: IncidentSummary) => string | number | null | undefined }[] = [
  { header: 'Number', value: (incident) => incident.number },
  { header: 'Title', value: (incident) => incident.title },
  { header: 'Priority', value: (incident) => PRIORITY_LABELS[incident.priority] ?? titleCase(incident.priority) },
  { header: 'Status', value: (incident) => STATUS_LABELS[incident.status] ?? titleCase(incident.status) },
  { header: 'Category', value: (incident) => titleCase(incident.category) },
  { header: 'Service', value: (incident) => incident.serviceName },
  { header: 'Team', value: (incident) => incident.departmentName },
  { header: 'Assignee', value: (incident) => incident.assigneeName },
  { header: 'Requester', value: (incident) => incident.requesterName },
  { header: 'Parent', value: (incident) => incident.parentNumber },
  { header: 'Major', value: (incident) => (incident.major ? 'Yes' : 'No') },
  { header: 'SLA policy', value: (incident) => incident.sla?.policyName },
  { header: 'SLA health', value: (incident) => (incident.sla ? titleCase(incident.sla.health) : null) },
  { header: 'SLA resolution due', value: (incident) => incident.sla?.resolutionDueAt },
  { header: 'Created', value: (incident) => incident.createdAt },
  { header: 'Updated', value: (incident) => incident.updatedAt },
  { header: 'Resolved', value: (incident) => incident.resolvedAt },
];

/** RFC 4180 quoting; cells that a spreadsheet would treat as a formula are prefixed with an apostrophe. */
export function csvCell(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return '';
  let text = String(value);
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

export function incidentsToCsv(incidents: IncidentSummary[]): string {
  const lines = [COLUMNS.map((column) => csvCell(column.header)).join(',')];
  incidents.forEach((incident) => lines.push(COLUMNS.map((column) => csvCell(column.value(incident))).join(',')));
  return `${lines.join('\r\n')}\r\n`;
}

/** Saves the CSV through a temporary object URL (UTF-8 with BOM so spreadsheets keep accents). */
export function downloadIncidentsCsv(incidents: IncidentSummary[], fileName = `incidents-selected-${new Date().toISOString().slice(0, 10)}.csv`): void {
  const blob = new Blob(['﻿', incidentsToCsv(incidents)], { type: 'text/csv;charset=utf-8' });
  const href = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = href;
  anchor.download = fileName;
  anchor.rel = 'noopener';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(href), 1000);
}
