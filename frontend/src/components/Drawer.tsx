import { useId, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { useDialog } from './useDialog';

interface DrawerProps {
  open: boolean;
  onClose: () => void;
  /** Small uppercase label in the drawer header (e.g. "REQUEST DETAILS"). */
  label: string;
  title: ReactNode;
  /** Muted line under the title (e.g. "REQ-3921 • Access Management"). */
  subtitle?: ReactNode;
  children: ReactNode;
  /** Sticky action bar at the bottom (buttons). */
  footer?: ReactNode;
  wide?: boolean;
}

/** Right-hand slide-over panel with focus trap, Escape to close and scroll lock. */
export function Drawer({ open, onClose, label, title, subtitle, children, footer, wide }: DrawerProps) {
  const ref = useRef<HTMLElement>(null);
  const titleId = useId();
  useDialog(open, onClose, ref);
  if (!open) return null;
  return createPortal(
    <div
      className="drawer-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <aside ref={ref} className={`detail-drawer ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
        <div className="drawer-top">
          <span>{label}</span>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="Close panel" data-focus-skip>
            <X size={18} />
          </button>
        </div>
        <h2 id={titleId}>{title}</h2>
        {subtitle && <p className="drawer-subtitle">{subtitle}</p>}
        {children}
        {footer && <div className="drawer-foot">{footer}</div>}
      </aside>
    </div>,
    document.body,
  );
}
