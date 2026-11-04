import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, getJson, postJson, putJson } from '../lib/api';
import type {
  CatalogFormDto,
  FormDetail,
  FormRequestBody,
  FormSummary,
  FormVersionDetail,
  FormVersionDto,
  RequestAttachmentDto,
  StagedUpload,
} from '../lib/itsmTypes';
import type { PageResponse } from '../lib/types';
import { STALE } from './common';

/** Dynamic form builder (docs/API.md "Dynamic form builder"): readers list, form editors save. */
export interface FormListParams {
  search?: string;
  template?: boolean;
  active?: boolean;
  page?: number;
  size?: number;
  sort?: string;
}

export const formKeys = {
  all: ['forms'] as const,
  list: (params: FormListParams) => ['forms', 'list', params] as const,
  detail: (id: number) => ['forms', 'detail', id] as const,
  versions: (id: number) => ['forms', 'detail', id, 'versions'] as const,
  version: (id: number, version: number) => ['forms', 'detail', id, 'versions', version] as const,
  catalogForm: (itemId: number) => ['catalog', 'items', itemId, 'form'] as const,
};

export function useForms(params: FormListParams = {}, enabled = true) {
  return useQuery({
    queryKey: formKeys.list(params),
    queryFn: () => getJson<PageResponse<FormSummary>>('/forms', { size: 100, ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.standard,
    enabled,
  });
}

export function useFormDetail(id: number | null) {
  return useQuery({
    queryKey: formKeys.detail(id ?? 0),
    queryFn: () => getJson<FormDetail>(`/forms/${id}`),
    staleTime: STALE.standard,
    enabled: Boolean(id),
  });
}

export function useFormVersions(id: number | null) {
  return useQuery({
    queryKey: formKeys.versions(id ?? 0),
    queryFn: () => getJson<FormVersionDto[]>(`/forms/${id}/versions`),
    staleTime: STALE.standard,
    enabled: Boolean(id),
  });
}

export function useFormVersion(id: number | null, version: number | null) {
  return useQuery({
    queryKey: formKeys.version(id ?? 0, version ?? 0),
    queryFn: () => getJson<FormVersionDetail>(`/forms/${id}/versions/${version}`),
    staleTime: STALE.reference,
    enabled: Boolean(id && version),
  });
}

export function useSaveForm() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: FormRequestBody }) => (id ? putJson<FormDetail>(`/forms/${id}`, body) : postJson<FormDetail>('/forms', body)),
    onSuccess: (form) =>
      Promise.all([
        queryClient.setQueryData(formKeys.detail(form.id), form),
        queryClient.invalidateQueries({ queryKey: formKeys.all }),
        queryClient.invalidateQueries({ queryKey: ['catalog'] }),
      ]),
  });
}

/** Dry-run validation of preview data against the form's current version (`400 fieldErrors` when invalid). */
export function useValidateFormPreview(id: number | null) {
  return useMutation({
    mutationFn: (formData: Record<string, unknown>) => postJson<{ valid: boolean; formData: Record<string, unknown> }>(`/forms/${id}/validate`, { formData }),
  });
}

/** The form a requester fills in for a catalogue item (current version) and whether they may submit it. */
export function useCatalogItemForm(itemId: number | null) {
  return useQuery({
    queryKey: formKeys.catalogForm(itemId ?? 0),
    queryFn: () => getJson<CatalogFormDto>(`/catalog/items/${itemId}/form`),
    staleTime: STALE.standard,
    enabled: Boolean(itemId),
  });
}

/** Stages a file for a file field (`multipart file`, ≤ 5 MB); the returned id is submitted as the field value. */
export function useStageUpload() {
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData();
      form.append('file', file);
      return api.post<StagedUpload>('/service-requests/uploads', form).then((response) => response.data);
    },
  });
}

export function useRequestAttachments(requestId: number | null, enabled = true) {
  return useQuery({
    queryKey: ['service-requests', 'detail', requestId, 'attachments'],
    queryFn: () => getJson<RequestAttachmentDto[]>(`/service-requests/${requestId}/attachments`),
    staleTime: STALE.standard,
    enabled: enabled && Boolean(requestId),
  });
}
