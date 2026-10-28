import { ref } from 'vue';

/**
 * In-app toasts.
 *
 * These replace `window.alert`: a browser dialog blocks the page, is branded by
 * the browser ("localhost:3000 says"), cannot be styled, and freezes the class
 * for everyone in it while a WebRTC room is on screen. A toast is the same
 * message, drawn by the app, without stealing focus.
 */
export type ToastKind = 'success' | 'error' | 'info';

export interface Toast {
  id: number;
  kind: ToastKind;
  title: string;
  message?: string;
  /** How long it stays up, in ms. */
  timeout: number;
}

// Four at a time is enough to read; anything older is dropped rather than
// stacked into a wall of cards.
const MAX_TOASTS = 4;

export const toasts = ref<Toast[]>([]);
let nextId = 1;

function push(kind: ToastKind, title: string, message?: string, timeout = 4200): number {
  const id = nextId++;
  toasts.value = [...toasts.value, { id, kind, title, message, timeout }].slice(-MAX_TOASTS);
  if (timeout > 0) {
    window.setTimeout(() => dismiss(id), timeout);
  }
  return id;
}

export function dismiss(id: number) {
  toasts.value = toasts.value.filter((t) => t.id !== id);
}

/** The message a thrown value should show: server text without the class name. */
export function errorText(err: unknown, fallback: string): string {
  const raw = err instanceof Error ? err.message : typeof err === 'string' ? err : '';
  const text = (raw || '').trim();
  if (!text) return fallback;
  // FastAPI/Django errors arrive as "Error: ..." often enough to be noise.
  return text.replace(/^Error:\s*/i, '');
}

export const toast = {
  success: (title: string, message?: string, timeout?: number) => push('success', title, message, timeout),
  error: (title: string, message?: string, timeout?: number) => push('error', title, message, timeout),
  info: (title: string, message?: string, timeout?: number) => push('info', title, message, timeout),
  dismiss,
};
