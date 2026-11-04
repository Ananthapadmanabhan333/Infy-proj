import type { ReactNode } from 'react';
import { act, renderHook, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastProvider } from '../components/Toast';
import { setAccessToken } from '../lib/api';
import {
  createSseParser,
  POLL_INTERVAL_MS,
  reconnectDelay,
  STREAM_SAFETY_POLL_MS,
  UNAVAILABLE_RETRY_MS,
  useNotificationStream,
} from './useNotificationStream';

function wrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <QueryClientProvider client={queryClient}>
          <ToastProvider>{children}</ToastProvider>
        </QueryClientProvider>
      </MemoryRouter>
    );
  };
}

/** Minimal streaming Response whose body yields the given SSE chunks, then stays open. */
function streamResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  let index = 0;
  const reader = {
    read: () =>
      index < chunks.length
        ? Promise.resolve({ value: encoder.encode(chunks[index++]), done: false })
        : new Promise<{ value: undefined; done: true }>(() => undefined),
  };
  return { ok: true, status: 200, body: { getReader: () => reader } } as unknown as Response;
}

describe('createSseParser', () => {
  it('parses events split across chunks and ignores comments', () => {
    const parse = createSseParser();
    expect(parse(': keep-alive\n\nevent: ping\ndata: {}\n\nevent: notifi')).toEqual([{ event: 'ping', data: '{}', id: undefined }]);
    expect(parse('cation\ndata: {"id":1,\ndata: "title":"x"}\nid: 7\n\n')).toEqual([{ event: 'notification', data: '{"id":1,\n"title":"x"}', id: '7' }]);
  });

  it('handles CRLF line endings', () => {
    const parse = createSseParser();
    expect(parse('event: ping\r\ndata: 1\r\n\r\n')).toEqual([{ event: 'ping', data: '1', id: undefined }]);
  });
});

describe('reconnectDelay', () => {
  it('backs off exponentially up to a minute', () => {
    expect(reconnectDelay(1)).toBe(1_000);
    expect(reconnectDelay(2)).toBe(2_000);
    expect(reconnectDelay(4)).toBe(10_000);
    expect(reconnectDelay(50)).toBe(60_000);
  });
});

describe('useNotificationStream', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    setAccessToken('test-access-token');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('falls back to 30 s polling, quietly, when the stream cannot be reached', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    vi.stubGlobal('fetch', fetchMock);
    const consoleError = vi.spyOn(console, 'error');
    const consoleWarn = vi.spyOn(console, 'warn');

    const { result, unmount } = renderHook(() => useNotificationStream(), { wrapper: wrapper(queryClient) });
    await waitFor(() => expect(result.current.status).toBe('polling'));
    expect(result.current.pollInterval).toBe(POLL_INTERVAL_MS);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/notifications/stream');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer test-access-token');
    expect(consoleError).not.toHaveBeenCalled();
    expect(consoleWarn).not.toHaveBeenCalled();
    unmount();
  });

  it('retries with backoff after a network failure', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    vi.stubGlobal('fetch', fetchMock);
    const { result, unmount } = renderHook(() => useNotificationStream(), { wrapper: wrapper(queryClient) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.status).toBe('polling');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    unmount();
  });

  it('only retries every 5 minutes while the endpoint is not deployed (404)', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 404, body: null } as unknown as Response);
    vi.stubGlobal('fetch', fetchMock);
    const { result, unmount } = renderHook(() => useNotificationStream(), { wrapper: wrapper(queryClient) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.status).toBe('polling');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNAVAILABLE_RETRY_MS - 1_000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    unmount();
  });

  it('invalidates notification queries and toasts high-severity events while connected', async () => {
    const notification = {
      id: 9,
      type: 'SLA_BREACHED',
      title: 'SLA breached',
      message: 'INC-7846 passed its resolution target',
      link: '/incidents/46',
      severity: 'CRITICAL',
      read: false,
      createdAt: '2026-11-03T10:00:00Z',
    };
    const info = { ...notification, id: 10, title: 'Comment added', severity: 'INFO' };
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(streamResponse([`event: ping\ndata: {}\n\n`, `event: notification\ndata: ${JSON.stringify(info)}\n\n`, `event: notification\ndata: ${JSON.stringify(notification)}\n\n`])),
    );
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    const received: string[] = [];

    const { result, unmount } = renderHook(() => useNotificationStream({ onNotification: (n) => received.push(n.title) }), { wrapper: wrapper(queryClient) });
    await waitFor(() => expect(result.current.status).toBe('open'));
    expect(result.current.pollInterval).toBe(STREAM_SAFETY_POLL_MS);
    await waitFor(() => expect(received).toEqual(['Comment added', 'SLA breached']));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['notifications'] });
    // Only the CRITICAL one raises a toast.
    expect(await screen.findByText('SLA breached — INC-7846 passed its resolution target')).toBeInTheDocument();
    expect(screen.queryByText(/Comment added/)).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open' })).toHaveAttribute('href', '/incidents/46');
    unmount();
  });

  it('stays idle when disabled', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useNotificationStream({ enabled: false }), { wrapper: wrapper(queryClient) });
    expect(result.current.status).toBe('idle');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
