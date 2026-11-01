/**
 * The "you are still in a class" reminder for the browser.
 *
 * A web page cannot pin anything to the desktop the way Android pins an
 * ongoing notification, so the tab carries the reminder: while the class is
 * open the title becomes a running clock — `● 12:34 · Biology 101 — Podium` —
 * which stays visible in the tab strip, the window switcher and the taskbar
 * after the user switches to something else.
 *
 * When the tab is hidden *and* notifications were already allowed, one system
 * notification is posted for the class. It is silent, it replaces itself
 * instead of stacking, and it is closed as soon as the tab is looked at again
 * or the class ends. Nothing is ever requested here: a permission prompt on
 * entering a class would be an interruption at the worst possible moment, so a
 * browser that has never granted notifications simply gets the tab title.
 */

/** Shown when the class has no title yet (the session row can lag the join). */
const FALLBACK_TITLE = 'Class in progress';

const NOTIFICATION_TAG = 'podium-live-class';

let ticker: number | null = null;
let startedAt = 0;
let label = FALLBACK_TITLE;
let defaultTitle: string | null = null;
let notification: Notification | null = null;
let listening = false;

/** `12:34`, or `1:02:34` past the hour — the same shape the class header uses. */
function clock(totalSeconds: number): string {
  const safe = Math.max(0, totalSeconds);
  const h = Math.floor(safe / 3600);
  const m = Math.floor((safe % 3600) / 60);
  const s = safe % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

function paintTitle(): void {
  const elapsed = Math.floor((Date.now() - startedAt) / 1000);
  document.title = `● ${clock(elapsed)} · ${label} — Podium`;
}

function hideNotification(): void {
  if (!notification) return;
  try {
    notification.close();
  } catch {
    /* already gone */
  }
  notification = null;
}

function showNotification(): void {
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
  hideNotification();
  try {
    const started = new Date(startedAt).toLocaleTimeString([], {
      hour: '2-digit',
      minute: '2-digit',
    });
    notification = new Notification(`In class — ${label}`, {
      body: `This class is still running (started ${started}). Tap to return to it.`,
      tag: NOTIFICATION_TAG,
      icon: '/icon-192x192.png',
      silent: true,
      requireInteraction: true,
    } as NotificationOptions);
    notification.onclick = () => {
      window.focus();
      hideNotification();
    };
  } catch {
    // Some browsers only allow notifications through a service worker; the
    // tab title still carries the reminder, so this is not worth surfacing.
  }
}

function onVisibilityChange(): void {
  if (ticker === null) return;
  if (document.hidden) showNotification();
  else hideNotification();
}

/**
 * Begin the reminder. Safe to call again — a second call only refreshes the
 * name and leaves the clock running, so a re-render or a late session load
 * does not restart it.
 */
export function startLiveClassPresence(title?: string | null): void {
  label = (title || '').trim() || FALLBACK_TITLE;

  if (ticker !== null) {
    paintTitle();
    return;
  }

  defaultTitle = document.title;
  startedAt = Date.now();
  paintTitle();
  ticker = window.setInterval(paintTitle, 1000);

  if (!listening) {
    document.addEventListener('visibilitychange', onVisibilityChange);
    listening = true;
  }
  if (document.hidden) showNotification();
}

/** The class was renamed, or its row arrived after the join. */
export function updateLiveClassTitle(title?: string | null): void {
  if (ticker === null) return;
  label = (title || '').trim() || FALLBACK_TITLE;
  paintTitle();
}

/**
 * Remove the reminder: the class ended, the user left, or the screen closed.
 * Restores the title the app had before, so leaving a class never leaves a
 * stale `● 00:04` in the user's tab strip.
 */
export function stopLiveClassPresence(): void {
  if (ticker !== null) {
    window.clearInterval(ticker);
    ticker = null;
  }
  hideNotification();
  if (listening) {
    document.removeEventListener('visibilitychange', onVisibilityChange);
    listening = false;
  }
  if (defaultTitle !== null) {
    document.title = defaultTitle;
    defaultTitle = null;
  }
}
