import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { AlertTriangle, Check, Info, X } from 'lucide-react';
import { Link } from 'react-router-dom';
import { errorMessage } from '../lib/api';

type ToastKind = 'success' | 'error' | 'info' | 'warning';

export interface ToastAction {
  label: string;
  to?: string;
  onClick?: () => void;
}

interface ToastItem {
  id: number;
  kind: ToastKind;
  message: ReactNode;
  action?: ToastAction;
}

interface ToastOptions {
  action?: ToastAction;
  durationMs?: number;
}

export interface ToastApi {
  success: (message: ReactNode, options?: ToastOptions) => void;
  info: (message: ReactNode, options?: ToastOptions) => void;
  /** Amber toast for warnings (e.g. SLA at risk from the live notification stream). */
  warning: (message: ReactNode, options?: ToastOptions) => void;
  /** Accepts a message or any thrown error (normalised ApiError message is shown). */
  error: (messageOrError: unknown, options?: ToastOptions) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => setItems((current) => current.filter((item) => item.id !== id)), []);

  const push = useCallback(
    (kind: ToastKind, message: ReactNode, options?: ToastOptions) => {
      const id = nextId.current++;
      setItems((current) => [...current.slice(-3), { id, kind, message, action: options?.action }]);
      const duration = options?.durationMs ?? (kind === 'error' ? 7000 : options?.action ? 8000 : 4500);
      window.setTimeout(() => dismiss(id), duration);
    },
    [dismiss],
  );

  const api = useMemo<ToastApi>(
    () => ({
      success: (message, options) => push('success', message, options),
      info: (message, options) => push('info', message, options),
      warning: (message, options) => push('warning', message, options),
      error: (messageOrError, options) => {
        const message =
          typeof messageOrError === 'string' || (typeof messageOrError === 'object' && messageOrError !== null && 'props' in messageOrError)
            ? (messageOrError as ReactNode)
            : errorMessage(messageOrError);
        push('error', message, options);
      },
    }),
    [push],
  );

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toast-stack" role="region" aria-label="Notifications">
        {items.map((item) => (
          <div key={item.id} className={`toast ${item.kind === 'success' ? '' : item.kind}`} role={item.kind === 'error' || item.kind === 'warning' ? 'alert' : 'status'}>
            {item.kind === 'success' && <Check size={16} />}
            {(item.kind === 'error' || item.kind === 'warning') && <AlertTriangle size={16} />}
            {item.kind === 'info' && <Info size={16} />}
            <div>
              {item.message}
              {item.action && (
                <div className="mt-1">
                  {item.action.to ? (
                    <Link to={item.action.to} onClick={() => dismiss(item.id)}>
                      {item.action.label}
                    </Link>
                  ) : (
                    <button
                      type="button"
                      className="link-btn"
                      onClick={() => {
                        item.action?.onClick?.();
                        dismiss(item.id);
                      }}
                    >
                      {item.action.label}
                    </button>
                  )}
                </div>
              )}
            </div>
            <button type="button" className="dismiss" onClick={() => dismiss(item.id)} aria-label="Dismiss notification">
              <X size={14} />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const value = useContext(ToastContext);
  if (!value) throw new Error('useToast must be used inside <ToastProvider>');
  return value;
}
