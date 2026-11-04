import { useEffect, useRef, type RefObject } from 'react';

// Stack of open dialogs so nested dialogs (confirm over drawer) only let the
// top-most one react to Escape / Tab.
const dialogStack: symbol[] = [];

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

function focusableIn(node: HTMLElement): HTMLElement[] {
  return Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE));
}

/** Focus management, focus trap, Escape-to-close and scroll lock for modal surfaces. */
export function useDialog(open: boolean, onClose: () => void, containerRef: RefObject<HTMLElement>, dismissible = true) {
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const dismissibleRef = useRef(dismissible);
  dismissibleRef.current = dismissible;

  useEffect(() => {
    if (!open) return undefined;
    const token = Symbol('dialog');
    dialogStack.push(token);
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const node = containerRef.current;

    if (node) {
      const preferred =
        node.querySelector<HTMLElement>('[data-autofocus]') ??
        node.querySelector<HTMLElement>('input:not([disabled]):not([type="hidden"]):not([type="checkbox"]), select:not([disabled]), textarea:not([disabled])') ??
        focusableIn(node).find((el) => !el.hasAttribute('data-focus-skip')) ??
        node;
      preferred.focus({ preventScroll: true });
    }

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    const onKeyDown = (event: KeyboardEvent) => {
      if (dialogStack[dialogStack.length - 1] !== token || !node) return;
      if (event.key === 'Escape') {
        if (dismissibleRef.current) {
          event.preventDefault();
          onCloseRef.current();
        }
        return;
      }
      if (event.key === 'Tab') {
        const items = focusableIn(node);
        if (!items.length) {
          event.preventDefault();
          return;
        }
        const first = items[0];
        const last = items[items.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener('keydown', onKeyDown);

    return () => {
      document.removeEventListener('keydown', onKeyDown);
      const index = dialogStack.indexOf(token);
      if (index >= 0) dialogStack.splice(index, 1);
      if (!dialogStack.length) document.body.style.overflow = previousOverflow;
      if (previouslyFocused && document.contains(previouslyFocused)) previouslyFocused.focus({ preventScroll: true });
    };
  }, [open, containerRef]);
}
