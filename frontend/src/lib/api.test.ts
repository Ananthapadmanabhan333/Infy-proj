import { describe, expect, it, vi } from 'vitest';
import { json, mockApi, requests, apiErrorBody } from '../test/mockApi';
import { api, applyFieldErrors, buildLoginUrl, getAccessToken, isAuthEndpoint, setAccessToken, setSessionExpiredHandler, toApiError } from './api';
import type { LoginResponse } from './types';

const session = (token: string): LoginResponse => ({
  accessToken: token,
  expiresIn: 900,
  user: {
    id: 1,
    email: 'agent@nexus360.test',
    fullName: 'Agent Smith',
    role: 'SUPPORT_AGENT',
    departmentId: 2,
    departmentName: 'Network Operations',
    jobTitle: null,
    mustChangePassword: false,
    demo: false,
  },
});

describe('api client', () => {
  it('sends the in-memory bearer token', async () => {
    setAccessToken('abc');
    mockApi({ 'GET /services': json([]) });
    await api.get('/services');
    expect(requests[0].headers.get('Authorization')).toBe('Bearer abc');
  });

  it('performs a single-flight refresh for concurrent 401s and retries each request once', async () => {
    setAccessToken('expired');
    let refreshCalls = 0;
    const authorised = (header: unknown) => header === 'Bearer fresh';
    mockApi({
      'GET /incidents': (config) => (authorised(config.headers.Authorization) ? json({ ok: 'incidents' }) : json(apiErrorBody(401, 'Expired'), 401)),
      'GET /services': (config) => (authorised(config.headers.Authorization) ? json({ ok: 'services' }) : json(apiErrorBody(401, 'Expired'), 401)),
      'POST /auth/refresh': () => {
        refreshCalls += 1;
        return { status: 200, data: session('fresh'), delayMs: 20 };
      },
    });

    const [incidents, services] = await Promise.all([api.get('/incidents'), api.get('/services')]);

    expect(incidents.data).toEqual({ ok: 'incidents' });
    expect(services.data).toEqual({ ok: 'services' });
    expect(refreshCalls).toBe(1);
    expect(getAccessToken()).toBe('fresh');
    const refresh = requests.find((request) => request.url === '/auth/refresh');
    expect(refresh?.headers.get('X-Requested-With')).toBe('nexus360');
    // two original attempts + one refresh + two retries
    expect(requests.filter((request) => request.url !== '/auth/refresh')).toHaveLength(4);
  });

  it('clears the session and redirects to /login?next=…&reason=expired when the refresh fails', async () => {
    window.history.pushState({}, '', '/incidents/12?tab=sla');
    const onExpired = vi.fn();
    setSessionExpiredHandler(onExpired);
    setAccessToken('expired');
    mockApi({
      'GET /incidents/12': json(apiErrorBody(401, 'Expired'), 401),
      'POST /auth/refresh': json(apiErrorBody(401, 'Refresh token revoked'), 401),
    });

    await expect(api.get('/incidents/12')).rejects.toMatchObject({ status: 401 });
    expect(onExpired).toHaveBeenCalledTimes(1);
    expect(onExpired).toHaveBeenCalledWith('/login?next=%2Fincidents%2F12%3Ftab%3Dsla&reason=expired');
    expect(getAccessToken()).toBeNull();
    window.history.pushState({}, '', '/');
  });

  it('redirects when the retried request is still unauthorised', async () => {
    const onExpired = vi.fn();
    setSessionExpiredHandler(onExpired);
    mockApi({
      'GET /audit-logs': json(apiErrorBody(401, 'Nope'), 401),
      'POST /auth/refresh': json(session('fresh')),
    });
    await expect(api.get('/audit-logs')).rejects.toMatchObject({ status: 401 });
    expect(requests.filter((request) => request.url === '/audit-logs')).toHaveLength(2);
    expect(onExpired).toHaveBeenCalledTimes(1);
  });

  it('never refreshes for auth endpoints such as /auth/login', async () => {
    const onExpired = vi.fn();
    setSessionExpiredHandler(onExpired);
    mockApi({ 'POST /auth/login': json(apiErrorBody(401, 'Invalid email or password'), 401) });
    await expect(api.post('/auth/login', { email: 'a@b.c', password: 'x' })).rejects.toMatchObject({
      status: 401,
      message: 'Invalid email or password',
    });
    expect(requests.some((request) => request.url === '/auth/refresh')).toBe(false);
    expect(onExpired).not.toHaveBeenCalled();
  });

  it('normalises errors and maps fieldErrors onto form fields', async () => {
    mockApi({
      'POST /incidents': json(apiErrorBody(400, 'Validation failed', [{ field: 'title', message: 'Title must be at least 5 characters' }]), 400),
    });
    const error = await api.post('/incidents', {}).catch((e: unknown) => e);
    const apiError = toApiError(error);
    expect(apiError.status).toBe(400);
    expect(apiError.message).toBe('Validation failed');
    const setError = vi.fn();
    expect(applyFieldErrors(error, setError)).toBe(true);
    expect(setError).toHaveBeenCalledWith('title', { type: 'server', message: 'Title must be at least 5 characters' });
  });

  it('reports network failures with a readable message', async () => {
    mockApi({});
    const error = toApiError(await api.get('/health').catch((e: unknown) => e));
    expect(error.status).toBe(0);
    expect(error.message).toMatch(/unable to reach/i);
  });

  it('adds Retry-After guidance to 429 responses', async () => {
    mockApi({ 'GET /search': { status: 429, data: { message: 'Too many requests.' }, headers: { 'retry-after': '30' } } });
    const error = toApiError(await api.get('/search').catch((e: unknown) => e));
    expect(error.retryAfterSeconds).toBe(30);
    expect(error.message).toBe('Too many requests. Try again in 30 seconds.');
  });

  it('builds the expiry login URL without a next for the landing and account pages', () => {
    window.history.pushState({}, '', '/');
    expect(buildLoginUrl()).toBe('/login?reason=expired');
    window.history.pushState({}, '', '/reset-password?token=secret');
    expect(buildLoginUrl()).toBe('/login?reason=expired');
    window.history.pushState({}, '', '/users?tab=access-requests');
    expect(buildLoginUrl()).toBe('/login?next=%2Fusers%3Ftab%3Daccess-requests&reason=expired');
    window.history.pushState({}, '', '/');
  });

  it('recognises auth endpoints regardless of base path', () => {
    expect(isAuthEndpoint('/auth/refresh')).toBe(true);
    expect(isAuthEndpoint('/api/auth/login')).toBe(true);
    expect(isAuthEndpoint('/auth/me')).toBe(false);
    expect(isAuthEndpoint('/incidents')).toBe(false);
  });
});
