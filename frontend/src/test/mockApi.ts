import { AxiosError, AxiosHeaders, type AxiosAdapter, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { api } from '../lib/api';

export interface MockReply {
  status: number;
  data?: unknown;
  headers?: Record<string, string>;
  delayMs?: number;
}

export type MockHandler = (config: InternalAxiosRequestConfig) => MockReply | Promise<MockReply>;

export interface RecordedRequest {
  method: string;
  url: string;
  params: unknown;
  body: unknown;
  headers: AxiosHeaders;
}

export const requests: RecordedRequest[] = [];

function parseBody(data: unknown): unknown {
  if (typeof data !== 'string') return data;
  try {
    return JSON.parse(data);
  } catch {
    return data;
  }
}

/**
 * Replaces the axios adapter. Routes are keyed "METHOD /path" (path without the
 * /api base). Unmatched requests fail like an unreachable network.
 */
export function mockApi(routes: Record<string, MockHandler | MockReply>): void {
  const adapter: AxiosAdapter = async (config) => {
    const method = (config.method ?? 'get').toUpperCase();
    const url = (config.url ?? '').split('?')[0];
    const headers = AxiosHeaders.from(config.headers);
    requests.push({ method, url, params: config.params, body: parseBody(config.data), headers });
    const route = routes[`${method} ${url}`];
    if (!route) throw new AxiosError('Network Error', 'ERR_NETWORK', config);
    const reply = typeof route === 'function' ? await route(config) : route;
    if (reply.delayMs) await new Promise((resolve) => setTimeout(resolve, reply.delayMs));
    const response: AxiosResponse = {
      data: reply.data ?? null,
      status: reply.status,
      statusText: String(reply.status),
      headers: reply.headers ?? {},
      config,
      request: {},
    };
    const ok = config.validateStatus ? config.validateStatus(reply.status) : reply.status < 400;
    if (!ok) {
      throw new AxiosError(`Request failed with status code ${reply.status}`, reply.status >= 500 ? 'ERR_BAD_RESPONSE' : 'ERR_BAD_REQUEST', config, {}, response);
    }
    return response;
  };
  api.defaults.adapter = adapter;
}

export function resetMockApi(): void {
  requests.length = 0;
  mockApi({});
}

export const json = (data: unknown, status = 200): MockReply => ({ status, data });

export function apiErrorBody(status: number, message: string, fieldErrors?: { field: string; message: string }[]) {
  return { status, error: 'Error', message, path: '/api/test', timestamp: '2026-11-03T10:00:00Z', fieldErrors };
}
