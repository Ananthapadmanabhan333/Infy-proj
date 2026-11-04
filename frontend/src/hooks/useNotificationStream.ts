import { createContext, useContext, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { notificationKeys } from '../api/notifications';
import { useToast } from '../components/Toast';
import { API_BASE_URL, getAccessToken, refreshSession } from '../lib/api';
import { TOAST_SEVERITIES } from '../lib/constants';
import type { NotificationDto } from '../lib/types';

/**
 * Real-time notifications (spec §12): `GET /api/notifications/stream` is Server-Sent Events with a
 * Bearer header, so it is read with `fetch` streaming (EventSource cannot send headers).
 *
 * - `notification` events invalidate the notification queries and raise a toast for CRITICAL/WARNING.
 * - `ping` (every 25 s) keeps a heartbeat watchdog alive; silence for 60 s reconnects.
 * - On failure it reconnects with exponential backoff and the UI polls every 30 s meanwhile.
 *   A 404/405/501 (endpoint not deployed) only retries every 5 minutes. Nothing is logged.
 */

export type StreamStatus = 'idle' | 'connecting' | 'open' | 'polling';

export interface SseMessage {
  event: string;
  data: string;
  id?: string;
}

/** Poll interval for the unread badge while the stream is down / up. */
export const POLL_INTERVAL_MS = 30_000;
export const STREAM_SAFETY_POLL_MS = 5 * 60_000;
export const RECONNECT_DELAYS_MS = [1_000, 2_000, 5_000, 10_000, 30_000, 60_000];
export const UNAVAILABLE_RETRY_MS = 5 * 60_000;
export const HEARTBEAT_TIMEOUT_MS = 60_000;

/** Incremental SSE parser: feed decoded text chunks, get back the complete messages. */
export function createSseParser(): (chunk: string) => SseMessage[] {
  let buffer = '';
  return (chunk: string) => {
    buffer = (buffer + chunk).replace(/\r\n?/g, '\n');
    const messages: SseMessage[] = [];
    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      let event = 'message';
      let id: string | undefined;
      const data: string[] = [];
      block.split('\n').forEach((line) => {
        if (!line || line.startsWith(':')) return;
        const colon = line.indexOf(':');
        const field = colon === -1 ? line : line.slice(0, colon);
        let value = colon === -1 ? '' : line.slice(colon + 1);
        if (value.startsWith(' ')) value = value.slice(1);
        if (field === 'event') event = value;
        else if (field === 'data') data.push(value);
        else if (field === 'id') id = value;
      });
      if (data.length || event !== 'message') messages.push({ event, data: data.join('\n'), id });
      boundary = buffer.indexOf('\n\n');
    }
    return messages;
  };
}

export function reconnectDelay(failures: number): number {
  return RECONNECT_DELAYS_MS[Math.min(Math.max(failures - 1, 0), RECONNECT_DELAYS_MS.length - 1)];
}

function streamUrl(): string {
  return `${API_BASE_URL.replace(/\/$/, '')}/notifications/stream`;
}

function parseNotification(data: string): NotificationDto | null {
  try {
    const value = JSON.parse(data) as NotificationDto;
    return value && typeof value === 'object' && typeof value.title === 'string' ? value : null;
  } catch {
    return null;
  }
}

class StreamHttpError extends Error {
  constructor(readonly status: number) {
    super(`Stream request failed (${status})`);
  }
}

interface StreamOptions {
  enabled?: boolean;
  /** Called for every notification received (after the queries are invalidated). */
  onNotification?: (notification: NotificationDto) => void;
}

export interface NotificationStreamState {
  status: StreamStatus;
  /** Use as `refetchInterval` for notification queries. */
  pollInterval: number;
}

export function useNotificationStream({ enabled = true, onNotification }: StreamOptions = {}): NotificationStreamState {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [status, setStatus] = useState<StreamStatus>(enabled ? 'connecting' : 'idle');
  const onNotificationRef = useRef(onNotification);
  onNotificationRef.current = onNotification;
  const toastRef = useRef(toast);
  toastRef.current = toast;

  useEffect(() => {
    if (!enabled) {
      setStatus('idle');
      return undefined;
    }
    let disposed = false;
    let failures = 0;
    let controller: AbortController | null = null;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let heartbeatTimer: ReturnType<typeof setTimeout> | null = null;

    const clearHeartbeat = () => {
      if (heartbeatTimer) clearTimeout(heartbeatTimer);
      heartbeatTimer = null;
    };
    const armHeartbeat = () => {
      clearHeartbeat();
      heartbeatTimer = setTimeout(() => controller?.abort(), HEARTBEAT_TIMEOUT_MS);
    };

    const handle = (message: SseMessage) => {
      if (message.event !== 'notification') return; // ping / unknown events only feed the heartbeat
      const notification = parseNotification(message.data);
      void queryClient.invalidateQueries({ queryKey: notificationKeys.all });
      if (!notification) return;
      onNotificationRef.current?.(notification);
      if ((TOAST_SEVERITIES as readonly string[]).includes(notification.severity)) {
        const text = notification.message ? `${notification.title} — ${notification.message}` : notification.title;
        const action = notification.link?.startsWith('/') ? { label: 'Open', to: notification.link } : undefined;
        if (notification.severity === 'CRITICAL') toastRef.current.error(text, { action });
        else toastRef.current.warning(text, { action });
      }
    };

    const schedule = (delay: number) => {
      if (disposed) return;
      setStatus('polling');
      retryTimer = setTimeout(() => void connect(), delay);
    };

    const open = async (token: string | null): Promise<Response> => {
      controller = new AbortController();
      const headers: Record<string, string> = { Accept: 'text/event-stream', 'Cache-Control': 'no-cache' };
      if (token) headers.Authorization = `Bearer ${token}`;
      return fetch(streamUrl(), { headers, credentials: 'include', signal: controller.signal, cache: 'no-store' });
    };

    async function connect(refreshed = false): Promise<void> {
      if (disposed) return;
      setStatus((current) => (current === 'open' ? current : failures ? 'polling' : 'connecting'));
      try {
        if (typeof fetch !== 'function') throw new Error('fetch unavailable');
        const response = await open(getAccessToken());
        if (response.status === 401 && !refreshed) {
          try {
            await refreshSession();
          } catch {
            throw new StreamHttpError(401);
          }
          return connect(true);
        }
        if (!response.ok || !response.body) throw new StreamHttpError(response.status);
        failures = 0;
        setStatus('open');
        // Catch up on anything created while the stream was down.
        void queryClient.invalidateQueries({ queryKey: notificationKeys.all });
        armHeartbeat();
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        const parse = createSseParser();
        for (;;) {
          const { value, done } = await reader.read();
          if (done || disposed) break;
          armHeartbeat();
          parse(decoder.decode(value, { stream: true })).forEach(handle);
        }
        clearHeartbeat();
        if (!disposed) {
          failures += 1;
          schedule(reconnectDelay(failures));
        }
      } catch (error) {
        clearHeartbeat();
        if (disposed) return;
        failures += 1;
        const unavailable = error instanceof StreamHttpError && [404, 405, 501].includes(error.status);
        if (error instanceof StreamHttpError && error.status === 401) {
          // Session could not be refreshed; polling continues and the API layer handles the expiry.
          schedule(UNAVAILABLE_RETRY_MS);
          return;
        }
        schedule(unavailable ? UNAVAILABLE_RETRY_MS : reconnectDelay(failures));
      }
      return undefined;
    }

    void connect();
    return () => {
      disposed = true;
      clearHeartbeat();
      if (retryTimer) clearTimeout(retryTimer);
      controller?.abort();
    };
  }, [enabled, queryClient]);

  return { status, pollInterval: status === 'open' ? STREAM_SAFETY_POLL_MS : POLL_INTERVAL_MS };
}

/** Shares the shell's single stream state with the bell / notifications page. */
export const NotificationStreamContext = createContext<NotificationStreamState>({ status: 'idle', pollInterval: POLL_INTERVAL_MS });

export function useNotificationStreamState(): NotificationStreamState {
  return useContext(NotificationStreamContext);
}
