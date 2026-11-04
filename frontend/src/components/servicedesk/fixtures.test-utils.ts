import type { ApprovalDto, CatalogCategoryDto, CatalogItemDto, ServiceRequestDto } from '../../lib/types';

// Test fixtures shared by the Service Desk tests (imported by *.test.tsx only).

export function makeRequest(overrides: Partial<ServiceRequestDto> = {}): ServiceRequestDto {
  return {
    id: 1,
    number: 'REQ-3921',
    catalogItemId: 11,
    catalogItemName: 'Power BI access',
    categoryName: 'Access Management',
    categoryCode: 'ACCESS',
    title: 'Access to Power BI',
    details: 'Needed for the finance dashboards.',
    formData: { costCentre: 'FIN-01', urgentAccess: true },
    status: 'APPROVED',
    priority: 'HIGH',
    requesterId: 21,
    requesterName: 'Priya Raman',
    departmentId: 3,
    departmentName: 'IT Service Desk',
    assigneeId: null,
    assigneeName: null,
    dueAt: '2026-11-05T10:00:00Z',
    fulfilledAt: null,
    fulfillmentNotes: null,
    createdAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
    updatedAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
    approval: null,
    commentCount: 2,
    demo: false,
    ...overrides,
  };
}

export function makeApproval(overrides: Partial<ApprovalDto> = {}): ApprovalDto {
  return {
    id: 501,
    type: 'SERVICE_REQUEST',
    referenceId: 1,
    referenceNumber: 'REQ-3921',
    title: 'Power BI access for Priya Raman',
    details: 'Access to Power BI',
    requestedByName: 'Priya Raman',
    approverRole: 'IT_MANAGER',
    status: 'PENDING',
    decidedByName: null,
    decisionComment: null,
    decidedAt: null,
    createdAt: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
    payload: null,
    ...overrides,
  };
}

export const categories: CatalogCategoryDto[] = [
  { id: 1, code: 'ACCESS', name: 'Access Management', description: 'Accounts and permissions', icon: 'KeyRound', itemCount: 1 },
  { id: 2, code: 'HARDWARE', name: 'Hardware', description: 'Laptops and peripherals', icon: 'Laptop', itemCount: 1 },
];

export const powerBi: CatalogItemDto = {
  id: 11,
  categoryId: 1,
  categoryName: 'Access Management',
  name: 'Power BI access',
  description: 'Read access to Power BI workspaces',
  requiresApproval: true,
  approverRole: 'IT_MANAGER',
  fulfillmentHours: 8,
  fulfillmentDepartmentId: 3,
  fulfillmentDepartmentName: 'IT Service Desk',
  formFields: [
    { key: 'costCentre', label: 'Cost centre', type: 'text', required: true },
    { key: 'workspace', label: 'Workspace', type: 'select', required: false, options: ['Finance', 'HR'] },
  ],
  active: true,
};

export const laptop: CatalogItemDto = {
  ...powerBi,
  id: 12,
  categoryId: 2,
  categoryName: 'Hardware',
  name: 'New laptop',
  description: 'Standard developer laptop',
  requiresApproval: false,
  approverRole: null,
  formFields: [],
};

export function page<T>(content: T[], overrides: Partial<{ page: number; size: number; totalElements: number; totalPages: number }> = {}) {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, ...overrides };
}
