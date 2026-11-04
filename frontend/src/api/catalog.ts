import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson, putJson } from '../lib/api';
import type { ApproverRole, CatalogCategoryDto, CatalogItemDto, FormField, Role } from '../lib/types';
import { STALE } from './common';

export interface CatalogCategoryBody {
  code: string;
  name: string;
  description: string | null;
  icon: string | null;
}

export interface CatalogItemBody {
  categoryId: number;
  name: string;
  description: string | null;
  requiresApproval: boolean;
  approverRole: ApproverRole | null;
  fulfillmentHours: number;
  fulfillmentDepartmentId: number;
  formFields: FormField[];
  active: boolean;
  /** R3: link a shared form definition (wins over formFields); null keeps the item's own form. */
  formDefinitionId?: number | null;
  /** R3 request-type permissions (empty = everyone). */
  allowedRoles?: Role[];
  allowedDepartmentIds?: number[];
}

export function useCatalogCategories() {
  return useQuery({
    queryKey: ['catalog', 'categories'],
    queryFn: () => getJson<CatalogCategoryDto[]>('/catalog/categories'),
    staleTime: STALE.reference,
  });
}

export function useCatalogItems(params: { categoryId?: number | null; search?: string; includeInactive?: boolean }, enabled = true) {
  return useQuery({
    queryKey: ['catalog', 'items', params],
    queryFn: () =>
      getJson<CatalogItemDto[]>('/catalog/items', {
        categoryId: params.categoryId ?? undefined,
        search: params.search,
        includeInactive: params.includeInactive || undefined,
      }),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useSaveCatalogCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: CatalogCategoryBody }) =>
      id ? putJson<CatalogCategoryDto>(`/catalog/categories/${id}`, body) : postJson<CatalogCategoryDto>('/catalog/categories', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['catalog'] }),
  });
}

export function useSaveCatalogItem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: CatalogItemBody }) =>
      id ? putJson<CatalogItemDto>(`/catalog/items/${id}`, body) : postJson<CatalogItemDto>('/catalog/items', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['catalog'] }),
  });
}
