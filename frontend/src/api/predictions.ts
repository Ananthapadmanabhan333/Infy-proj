import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson } from '../lib/api';
import type {
  CapacityForecastDto,
  ClassificationPredictionDto,
  EvaluateModelRequest,
  IncidentPredictionsDto,
  ModelPerformanceDto,
  PredictionJobDto,
  PredictionMetaDto,
  PredictionModelDto,
  PredictionModelStatus,
  PredictionModelType,
  PredictionRecordDto,
  ReconcileOutcomesResult,
  ServiceHealthForecastDto,
  TrainModelRequest,
} from '../lib/r4Types';
import type { PageResponse } from '../lib/types';
import { STALE } from './common';

/** SA Predictive Intelligence (`/api/predictions/**`): READERS read; PREDICTION_ADMINS train, evaluate, activate, retire, reconcile. */
export const predictionKeys = {
  all: ['predictions'] as const,
  meta: ['predictions', 'meta'] as const,
  models: (params: ModelListParams) => ['predictions', 'models', params] as const,
  model: (id: number) => ['predictions', 'models', id] as const,
  performance: (id: number) => ['predictions', 'models', id, 'performance'] as const,
  jobs: (params: JobListParams) => ['predictions', 'jobs', params] as const,
  records: (params: RecordListParams) => ['predictions', 'records', params] as const,
  incident: (incidentId: number, includeSimulated: boolean) => ['predictions', 'incident', incidentId, includeSimulated] as const,
  incidentSla: (incidentId: number, includeSimulated: boolean) => ['predictions', 'incident', incidentId, 'sla', includeSimulated] as const,
  incidentRecurrence: (incidentId: number, includeSimulated: boolean) => ['predictions', 'incident', incidentId, 'recurrence', includeSimulated] as const,
  serviceHealth: (serviceId: number, horizonDays: number, includeSimulated: boolean) =>
    ['predictions', 'service-health', serviceId, horizonDays, includeSimulated] as const,
  capacity: (params: CapacityParams) => ['predictions', 'capacity', params] as const,
};

export interface ModelListParams {
  type?: PredictionModelType | '';
  status?: PredictionModelStatus | '';
  includeSimulated?: boolean;
}

export interface JobListParams {
  type?: PredictionModelType | '';
  limit?: number;
}

export interface RecordListParams {
  type?: PredictionModelType | '';
  modelId?: number;
  subjectType?: string;
  subjectId?: number;
  outcomeStatus?: string;
  includeSimulated?: boolean;
  page?: number;
  size?: number;
}

export interface CapacityParams {
  serviceId?: number;
  ciId?: number;
  seriesId?: number;
  metric?: string;
  threshold?: number;
  horizonHours?: number;
  includeSimulated?: boolean;
}

export function usePredictionMeta() {
  return useQuery({
    queryKey: predictionKeys.meta,
    queryFn: () => getJson<PredictionMetaDto>('/predictions/meta'),
    staleTime: STALE.reference,
  });
}

export function usePredictionModels(params: ModelListParams = {}) {
  return useQuery({
    queryKey: predictionKeys.models(params),
    queryFn: () =>
      getJson<PredictionModelDto[]>('/predictions/models', {
        type: params.type || undefined,
        status: params.status || undefined,
        includeSimulated: params.includeSimulated ?? false,
      }),
    placeholderData: keepPreviousData,
  });
}

export function usePredictionModel(id: number | null) {
  return useQuery({
    queryKey: predictionKeys.model(id ?? 0),
    queryFn: () => getJson<PredictionModelDto>(`/predictions/models/${id}`),
    enabled: Boolean(id && id > 0),
  });
}

export function useModelPerformance(id: number | null) {
  return useQuery({
    queryKey: predictionKeys.performance(id ?? 0),
    queryFn: () => getJson<ModelPerformanceDto>(`/predictions/models/${id}/performance`),
    enabled: Boolean(id && id > 0),
  });
}

export function usePredictionJobs(params: JobListParams = {}) {
  return useQuery({
    queryKey: predictionKeys.jobs(params),
    queryFn: () =>
      getJson<PredictionJobDto[]>('/predictions/jobs', {
        type: params.type || undefined,
        limit: params.limit ?? 50,
      }),
    placeholderData: keepPreviousData,
  });
}

export function usePredictionRecords(params: RecordListParams = {}) {
  return useQuery({
    queryKey: predictionKeys.records(params),
    queryFn: () =>
      getJson<PageResponse<PredictionRecordDto>>('/predictions/records', {
        type: params.type || undefined,
        modelId: params.modelId,
        subjectType: params.subjectType || undefined,
        subjectId: params.subjectId,
        outcomeStatus: params.outcomeStatus || undefined,
        includeSimulated: params.includeSimulated ?? false,
        page: params.page ?? 0,
        size: params.size ?? 20,
      }),
    placeholderData: keepPreviousData,
  });
}

export function useIncidentPredictions(incidentId: number | null, includeSimulated = false) {
  return useQuery({
    queryKey: predictionKeys.incident(incidentId ?? 0, includeSimulated),
    queryFn: () => getJson<IncidentPredictionsDto>(`/predictions/incidents/${incidentId}`, { includeSimulated }),
    enabled: Boolean(incidentId && incidentId > 0),
  });
}

export function useIncidentSlaPrediction(incidentId: number | null, includeSimulated = false) {
  return useQuery({
    queryKey: predictionKeys.incidentSla(incidentId ?? 0, includeSimulated),
    queryFn: () => getJson<ClassificationPredictionDto>(`/predictions/incidents/${incidentId}/sla-breach`, { includeSimulated }),
    enabled: Boolean(incidentId && incidentId > 0),
  });
}

export function useServiceHealthForecast(serviceId: number | null, horizonDays = 7, includeSimulated = false) {
  return useQuery({
    queryKey: predictionKeys.serviceHealth(serviceId ?? 0, horizonDays, includeSimulated),
    queryFn: () => getJson<ServiceHealthForecastDto>(`/predictions/services/${serviceId}/health`, { horizonDays, includeSimulated }),
    enabled: Boolean(serviceId && serviceId > 0),
  });
}

export function useCapacityForecast(params: CapacityParams, enabled = true) {
  return useQuery({
    queryKey: predictionKeys.capacity(params),
    queryFn: () =>
      getJson<CapacityForecastDto>('/predictions/capacity', {
        serviceId: params.serviceId,
        ciId: params.ciId,
        seriesId: params.seriesId,
        metric: params.metric || undefined,
        threshold: params.threshold,
        horizonHours: params.horizonHours ?? 48,
        includeSimulated: params.includeSimulated ?? false,
      }),
    enabled,
  });
}

export function useTrainModel() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: TrainModelRequest) => postJson<PredictionModelDto>('/predictions/models/train', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: predictionKeys.all });
    },
  });
}

export function useEvaluateModel() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body?: EvaluateModelRequest }) =>
      postJson<PredictionJobDto>(`/predictions/models/${id}/evaluate`, body ?? {}),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: predictionKeys.all });
    },
  });
}

export function useActivateModel() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postJson<PredictionModelDto>(`/predictions/models/${id}/activate`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: predictionKeys.all });
    },
  });
}

export function useRetireModel() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postJson<PredictionModelDto>(`/predictions/models/${id}/retire`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: predictionKeys.all });
    },
  });
}

export function useReconcileOutcomes() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => postJson<ReconcileOutcomesResult>('/predictions/outcomes/reconcile'),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: predictionKeys.all });
    },
  });
}
