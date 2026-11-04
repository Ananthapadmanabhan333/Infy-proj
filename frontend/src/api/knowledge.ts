import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type {
  ArticleDetail,
  ArticleFeedbackRequest,
  ArticleRequest,
  ArticleSort,
  ArticleSummary,
  ArticleVersionDto,
  KnowledgeStats,
  PageResponse,
} from '../lib/types';
import { STALE } from './common';

export interface KnowledgeListParams {
  q?: string;
  type?: string;
  category?: string;
  serviceId?: string;
  status?: string;
  /** §9 popular | recent | rating. */
  sort?: ArticleSort | '';
  page?: number;
  size?: number;
}

/** Refreshes lists and stats but keeps cached article details (GET detail increments the view counter). */
function invalidateKnowledgeLists(queryClient: QueryClient) {
  return queryClient.invalidateQueries({ queryKey: ['knowledge'], predicate: (q) => q.queryKey[1] !== 'detail' });
}

export function useArticles(params: KnowledgeListParams, enabled = true) {
  return useQuery({
    queryKey: ['knowledge', 'list', params],
    queryFn: () => getJson<PageResponse<ArticleSummary>>('/knowledge', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.standard,
    enabled,
  });
}

/** GET /knowledge/{id} increments the view counter, so it is fetched once per visit. */
export function useArticle(id: number) {
  return useQuery({
    queryKey: ['knowledge', 'detail', id],
    queryFn: () => getJson<ArticleDetail>(`/knowledge/${id}`),
    enabled: Number.isFinite(id) && id > 0,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });
}

export function useKnowledgeStats() {
  return useQuery({
    queryKey: ['knowledge', 'stats'],
    queryFn: () => getJson<KnowledgeStats>('/knowledge/stats'),
    staleTime: STALE.standard,
  });
}

export function useSaveArticle() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: ArticleRequest }) =>
      id ? putJson<ArticleDetail>(`/knowledge/${id}`, body) : postJson<ArticleDetail>('/knowledge', body),
    onSuccess: (article) => {
      queryClient.setQueryData(['knowledge', 'detail', article.id], article);
      return invalidateKnowledgeLists(queryClient);
    },
  });
}

/**
 * §9 publishing workflow: staff `submit` drafts for review; KB_PUBLISHERS `publish` and `archive`.
 * Each returns the updated article.
 */
export function useArticleWorkflow(id: number) {
  const queryClient = useQueryClient();
  const onSuccess = (article: ArticleDetail | null) => {
    if (article && typeof article === 'object' && 'id' in article) queryClient.setQueryData(['knowledge', 'detail', id], article);
    return Promise.all([invalidateKnowledgeLists(queryClient), queryClient.invalidateQueries({ queryKey: ['knowledge', 'versions', id] })]);
  };
  const submit = useMutation({ mutationFn: () => postJson<ArticleDetail | null>(`/knowledge/${id}/submit`), onSuccess });
  const publish = useMutation({ mutationFn: () => postJson<ArticleDetail | null>(`/knowledge/${id}/publish`), onSuccess });
  const archive = useMutation({ mutationFn: () => postJson<ArticleDetail | null>(`/knowledge/${id}/archive`), onSuccess });
  return { submit, publish, archive };
}

/** §9 KB_DELETERS only; incident references are cleared server side (audited). */
export function useDeleteArticle() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson(`/knowledge/${id}`),
    onSuccess: (_data, id) => {
      queryClient.removeQueries({ queryKey: ['knowledge', 'detail', id] });
      return invalidateKnowledgeLists(queryClient);
    },
  });
}

/** §9 version history, newest first. */
export function useArticleVersions(id: number, enabled = true) {
  return useQuery({
    queryKey: ['knowledge', 'versions', id],
    queryFn: () => getJson<ArticleVersionDto[]>(`/knowledge/${id}/versions`),
    enabled: enabled && Number.isFinite(id) && id > 0,
    staleTime: STALE.standard,
  });
}

/** §9 KB_PUBLISHERS: restore a stored version (creates a new version). */
export function useRestoreArticleVersion(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (version: number) => postJson<ArticleDetail>(`/knowledge/${id}/versions/${version}/restore`),
    onSuccess: (article) => {
      if (article && typeof article === 'object' && 'id' in article) queryClient.setQueryData(['knowledge', 'detail', id], article);
      return Promise.all([invalidateKnowledgeLists(queryClient), queryClient.invalidateQueries({ queryKey: ['knowledge', 'versions', id] })]);
    },
  });
}

/** §9 star rating 1–5 (one per user per article, updatable). */
export function useRateArticle(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ArticleFeedbackRequest) => postJson<ArticleDetail | null>(`/knowledge/${id}/feedback`, body),
    onSuccess: (article) => {
      if (article && typeof article === 'object' && 'id' in article) {
        queryClient.setQueryData(['knowledge', 'detail', id], article);
      }
      return invalidateKnowledgeLists(queryClient);
    },
  });
}
