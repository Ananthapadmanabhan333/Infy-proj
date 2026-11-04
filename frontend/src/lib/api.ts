import axios, { AxiosError, AxiosHeaders, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios';
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import type { ApiError, LoginResponse } from './types';

/** Base URL of the API (always ends with /api). Defaults to the same-origin dev/nginx proxy. */
export const API_BASE_URL: string = import.meta.env.VITE_API_URL || '/api';

/** Origin that serves the API — used to build absolute inbound integration URLs. */
export function apiOrigin(): string {
  try {
    return new URL(API_BASE_URL, window.location.origin).origin;
  } catch {
    return window.location.origin;
  }
}

// ---------------------------------------------------------------------------
// Access token: kept in memory only (never persisted). The refresh token lives in
// an HttpOnly cookie that JavaScript cannot read.
// ---------------------------------------------------------------------------
let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

type SessionListener = (session: LoginResponse) => void;
const sessionListeners = new Set<SessionListener>();

/** Notified whenever a refresh succeeds (boot or silent refresh after a 401). */
export function onSessionRefreshed(listener: SessionListener): () => void {
  sessionListeners.add(listener);
  return () => sessionListeners.delete(listener);
}

/** Called with the login URL (`?next=…&reason=expired`) when the session can no longer be refreshed. */
type SessionExpiredHandler = (loginUrl: string) => void;

const defaultSessionExpiredHandler: SessionExpiredHandler = (loginUrl) => {
  window.location.assign(loginUrl);
};
let sessionExpiredHandler: SessionExpiredHandler = defaultSessionExpiredHandler;

export function setSessionExpiredHandler(handler: SessionExpiredHandler | null): void {
  sessionExpiredHandler = handler ?? defaultSessionExpiredHandler;
}

/** Public account pages: never used as a post-login destination (they may also carry one-time tokens). */
const ACCOUNT_PAGES = ['/login', '/register', '/forgot-password', '/reset-password'];

/** `/login?next=<current path>&reason=expired` (spec §1); the current page is omitted for `/` and account pages. */
export function buildLoginUrl(): string {
  const { pathname, search } = window.location;
  const isAccountPage = ACCOUNT_PAGES.some((page) => pathname === page || pathname.startsWith(`${page}/`));
  const next = `${pathname}${search}`;
  if (isAccountPage || !next || next === '/') return '/login?reason=expired';
  return `/login?next=${encodeURIComponent(next)}&reason=expired`;
}

export function clearSession(): void {
  accessToken = null;
}

// ---------------------------------------------------------------------------
// Axios instance
// ---------------------------------------------------------------------------
export const api = axios.create({
  baseURL: API_BASE_URL,
  withCredentials: true,
  timeout: 30_000,
  headers: { Accept: 'application/json' },
});

/** Auth endpoints never trigger a refresh-and-retry cycle. */
const AUTH_ENDPOINTS = ['/auth/login', '/auth/refresh', '/auth/logout', '/auth/demo-accounts'];

export function isAuthEndpoint(url: string | undefined): boolean {
  if (!url) return false;
  const path = url.replace(/^https?:\/\/[^/]+/, '').replace(/^\/api(?=\/)/, '').split('?')[0];
  return AUTH_ENDPOINTS.some((endpoint) => path === endpoint || path.endsWith(endpoint));
}

interface RetriableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean;
  skipAuthRefresh?: boolean;
}

declare module 'axios' {
  interface AxiosRequestConfig {
    /** Do not attempt a silent token refresh when this request returns 401. */
    skipAuthRefresh?: boolean;
  }
}

api.interceptors.request.use((config) => {
  const headers = AxiosHeaders.from(config.headers);
  if (accessToken && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }
  config.headers = headers;
  return config;
});

let refreshInFlight: Promise<LoginResponse> | null = null;

/**
 * Single-flight refresh: concurrent callers share one POST /auth/refresh so a
 * rotated refresh token is never replayed (replay revokes the whole token family).
 */
export function refreshSession(): Promise<LoginResponse> {
  if (!refreshInFlight) {
    refreshInFlight = api
      .post<LoginResponse>('/auth/refresh', null, {
        headers: { 'X-Requested-With': 'nexus360' },
        skipAuthRefresh: true,
      })
      .then((response) => {
        const session = response.data;
        setAccessToken(session.accessToken);
        sessionListeners.forEach((listener) => listener(session));
        return session;
      })
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

function expireSession(): void {
  clearSession();
  sessionExpiredHandler(buildLoginUrl());
}

api.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (!axios.isAxiosError(error)) return Promise.reject(toApiError(error));
    const config = error.config as RetriableConfig | undefined;
    const status = error.response?.status;

    if (status === 401 && config && !config.skipAuthRefresh && !isAuthEndpoint(config.url)) {
      if (config._retried) {
        // The retried request was still rejected: the session is no longer valid.
        expireSession();
        return Promise.reject(await normaliseAxiosError(error));
      }
      try {
        const session = await refreshSession();
        config._retried = true;
        const headers = AxiosHeaders.from(config.headers);
        headers.set('Authorization', `Bearer ${session.accessToken}`);
        config.headers = headers;
        return api.request(config);
      } catch {
        expireSession();
        return Promise.reject(await normaliseAxiosError(error));
      }
    }
    return Promise.reject(await normaliseAxiosError(error));
  },
);

// ---------------------------------------------------------------------------
// Error normalisation
// ---------------------------------------------------------------------------
export class ApiRequestError extends Error implements ApiError {
  status: number;
  error: string;
  path: string;
  timestamp: string;
  fieldErrors?: { field: string; message: string }[];
  retryAfterSeconds: number | null;

  constructor(init: ApiError & { retryAfterSeconds?: number | null }) {
    super(init.message);
    this.name = 'ApiRequestError';
    this.status = init.status;
    this.error = init.error;
    this.path = init.path;
    this.timestamp = init.timestamp;
    this.fieldErrors = init.fieldErrors;
    this.retryAfterSeconds = init.retryAfterSeconds ?? null;
  }

  get isNetworkError(): boolean {
    return this.status === 0;
  }
}

const STATUS_MESSAGES: Record<number, string> = {
  400: 'The request was not valid. Check the highlighted fields.',
  401: 'Your session has expired. Please sign in again.',
  403: 'You do not have permission to perform this action.',
  404: 'The requested record was not found.',
  409: 'This change conflicts with the current state of the record. Refresh and try again.',
  413: 'The file is too large. The maximum upload size is 5 MB.',
  415: 'This file type is not allowed.',
  423: 'This account is temporarily locked after repeated failed sign-in attempts.',
  429: 'Too many requests. Please wait a moment and try again.',
  500: 'The server encountered an error. Please try again.',
  502: 'The API is temporarily unavailable.',
  503: 'The API is temporarily unavailable.',
};

function isApiErrorBody(value: unknown): value is Partial<ApiError> {
  return typeof value === 'object' && value !== null && ('message' in value || 'error' in value || 'fieldErrors' in value);
}

async function readBody(data: unknown): Promise<unknown> {
  if (typeof Blob !== 'undefined' && data instanceof Blob) {
    try {
      const text = await data.text();
      return text ? JSON.parse(text) : null;
    } catch {
      return null;
    }
  }
  if (typeof data === 'string') {
    try {
      return JSON.parse(data);
    } catch {
      return null;
    }
  }
  return data;
}

async function normaliseAxiosError(error: AxiosError): Promise<ApiRequestError> {
  const response = error.response;
  if (!response) {
    return new ApiRequestError({
      status: 0,
      error: error.code ?? 'NETWORK_ERROR',
      message:
        error.code === 'ECONNABORTED'
          ? 'The request timed out. Please try again.'
          : 'Unable to reach the SA API. Check your connection and try again.',
      path: error.config?.url ?? '',
      timestamp: new Date().toISOString(),
    });
  }
  const body = await readBody(response.data);
  const retryAfterHeader = response.headers?.['retry-after'];
  const retryAfter = retryAfterHeader != null ? Number.parseInt(String(retryAfterHeader), 10) : NaN;
  const status = response.status;
  const fallback = STATUS_MESSAGES[status] ?? `Request failed (${status}).`;
  const parsed = isApiErrorBody(body) ? body : {};
  let message = typeof parsed.message === 'string' && parsed.message.trim() ? parsed.message : fallback;
  if (status === 429 && Number.isFinite(retryAfter) && !/\d/.test(message)) {
    message = `${message} Try again in ${retryAfter} seconds.`;
  }
  return new ApiRequestError({
    status,
    error: typeof parsed.error === 'string' ? parsed.error : response.statusText || 'Error',
    message,
    path: typeof parsed.path === 'string' ? parsed.path : error.config?.url ?? '',
    timestamp: typeof parsed.timestamp === 'string' ? parsed.timestamp : new Date().toISOString(),
    fieldErrors: Array.isArray(parsed.fieldErrors) ? parsed.fieldErrors : undefined,
    retryAfterSeconds: Number.isFinite(retryAfter) ? retryAfter : null,
  });
}

/** Converts anything thrown by the API layer into an ApiRequestError. */
export function toApiError(error: unknown): ApiRequestError {
  if (error instanceof ApiRequestError) return error;
  if (error instanceof Error) {
    return new ApiRequestError({
      status: 0,
      error: error.name,
      message: error.message || 'Unexpected error',
      path: '',
      timestamp: new Date().toISOString(),
    });
  }
  return new ApiRequestError({
    status: 0,
    error: 'UNKNOWN',
    message: 'Unexpected error',
    path: '',
    timestamp: new Date().toISOString(),
  });
}

export function errorMessage(error: unknown): string {
  return toApiError(error).message;
}

/**
 * Maps backend `fieldErrors` onto react-hook-form fields. Returns true when at
 * least one field error was applied (the caller can then skip a generic toast).
 */
export function applyFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fieldMap: Record<string, string> = {},
): boolean {
  const apiError = toApiError(error);
  if (!apiError.fieldErrors?.length) return false;
  apiError.fieldErrors.forEach(({ field, message }) => {
    const name = (fieldMap[field] ?? field) as Path<T>;
    setError(name, { type: 'server', message });
  });
  return true;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
type ParamValue = string | number | boolean | null | undefined | (string | number)[];

/** Drops empty params and joins arrays as CSV (the API's list convention). */
export function cleanParams(params: Record<string, ParamValue> | undefined): Record<string, string | number | boolean> {
  const result: Record<string, string | number | boolean> = {};
  if (!params) return result;
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    if (Array.isArray(value)) {
      if (value.length) result[key] = value.join(',');
      return;
    }
    result[key] = value;
  });
  return result;
}

export async function getJson<T>(url: string, params?: Record<string, ParamValue>, config?: AxiosRequestConfig): Promise<T> {
  const response = await api.get<T>(url, { ...config, params: cleanParams(params) });
  return response.data;
}

export async function postJson<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const response = await api.post<T>(url, body ?? null, config);
  return response.data;
}

export async function putJson<T>(url: string, body?: unknown): Promise<T> {
  const response = await api.put<T>(url, body ?? null);
  return response.data;
}

export async function deleteJson<T = void>(url: string): Promise<T> {
  const response = await api.delete<T>(url);
  return response.data;
}

function fileNameFromDisposition(header: unknown): string | null {
  if (typeof header !== 'string') return null;
  const utf = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (utf) return decodeURIComponent(utf[1].replace(/"/g, ''));
  const plain = /filename="?([^";]+)"?/i.exec(header);
  return plain ? plain[1] : null;
}

/** Downloads a file through axios (so the bearer token is sent) and saves it. */
export async function downloadFile(url: string, fallbackName: string, params?: Record<string, ParamValue>): Promise<void> {
  const response = await api.get<Blob>(url, { params: cleanParams(params), responseType: 'blob' });
  const name = fileNameFromDisposition(response.headers['content-disposition']) ?? fallbackName;
  const href = URL.createObjectURL(response.data);
  const anchor = document.createElement('a');
  anchor.href = href;
  anchor.download = name;
  anchor.rel = 'noopener';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(href), 1000);
}
