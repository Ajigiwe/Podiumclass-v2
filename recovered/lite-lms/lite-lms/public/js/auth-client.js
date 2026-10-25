// Cookie-based auth client for the static pages.
//
// The pages were originally written against the Firebase Auth web SDK. This
// module keeps the small surface they use (auth.currentUser, getIdToken,
// onAuthStateChanged, signOut) while the actual session is a PostgreSQL-backed
// NextAuth cookie.

const SESSION_URL = '/api/auth/session';
const TOKEN_URL = '/api/auth/token';
const TOKEN_REFRESH_MARGIN_MS = 60_000;

let currentUser = null;
let sessionPromise = null;
let tokenPromise = null;
let tokenCache = { token: null, expiresAt: 0 };
const listeners = new Set();

function mapSessionUser(sessionUser) {
  if (!sessionUser || !sessionUser.id) return null;
  if (sessionUser.status && sessionUser.status !== 'active') return null;

  return {
    uid: sessionUser.id,
    email: sessionUser.email || '',
    displayName: sessionUser.name || (sessionUser.email ? sessionUser.email.split('@')[0] : ''),
    photoURL: sessionUser.image || null,
    role: sessionUser.role || 'student',
    status: sessionUser.status || 'active',
    emailVerified: true,
    providerData: [{ providerId: 'google.com' }],
    getIdToken: (forceRefresh = false) => fetchAppToken(forceRefresh),
  };
}

async function loadSession() {
  try {
    const response = await fetch(SESSION_URL, {
      credentials: 'include',
      cache: 'no-store',
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) return null;
    const data = await response.json();
    return mapSessionUser(data && data.user);
  } catch (error) {
    console.error('[auth] session lookup failed', error);
    return null;
  }
}

async function fetchAppToken(forceRefresh = false) {
  const now = Date.now();
  if (!forceRefresh && tokenCache.token && tokenCache.expiresAt > now + TOKEN_REFRESH_MARGIN_MS) {
    return tokenCache.token;
  }
  if (!forceRefresh && tokenPromise) return tokenPromise;

  tokenPromise = (async () => {
    const response = await fetch(TOKEN_URL, {
      credentials: 'include',
      cache: 'no-store',
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) {
      tokenCache = { token: null, expiresAt: 0 };
      throw new Error('Your session has expired. Please sign in again.');
    }
    const data = await response.json();
    tokenCache = {
      token: data.token,
      expiresAt: Number(data.expiresAt) || 0,
    };
    return data.token;
  })().finally(() => {
    tokenPromise = null;
  });

  return tokenPromise;
}

/**
 * Supports both Firebase-style `onAuthStateChanged(auth, cb)` and
 * `onAuthStateChanged(cb)`.
 */
export function onAuthStateChanged(authOrCallback, maybeCallback) {
  const callback =
    typeof authOrCallback === 'function' ? authOrCallback : maybeCallback;

  if (typeof callback !== 'function') return () => {};

  listeners.add(callback);

  (async () => {
    if (currentUser) {
      try {
        callback(currentUser);
      } catch (error) {
        console.error('[auth] listener failed', error);
      }
      return;
    }

    if (!sessionPromise) {
      sessionPromise = loadSession().finally(() => {
        sessionPromise = null;
      });
    }

    const user = await sessionPromise;
    currentUser = user;

    try {
      callback(user);
    } catch (error) {
      console.error('[auth] listener failed', error);
    }
  })();

  return () => listeners.delete(callback);
}

function notify(nextUser) {
  currentUser = nextUser;
  listeners.forEach((listener) => {
    try {
      listener(nextUser);
    } catch (error) {
      console.error('[auth] listener failed', error);
    }
  });
}

function appendField(form, name, value) {
  const input = document.createElement('input');
  input.type = 'hidden';
  input.name = name;
  input.value = value;
  form.appendChild(input);
}

async function submitSignIn(action, fields, callbackUrl) {
  const csrfResponse = await fetch('/api/auth/csrf', {
    credentials: 'include',
    cache: 'no-store',
    headers: { Accept: 'application/json' },
  });

  if (!csrfResponse.ok) {
    throw new Error('Could not start sign-in. Please try again.');
  }

  const { csrfToken } = await csrfResponse.json();
  if (!csrfToken) throw new Error('Could not start sign-in. Please try again.');

  const form = document.createElement('form');
  form.method = 'POST';
  form.action = action;
  form.style.display = 'none';

  appendField(form, 'csrfToken', csrfToken);
  appendField(form, 'callbackUrl', new URL(callbackUrl, window.location.href).toString());
  Object.entries(fields || {}).forEach(([name, value]) => appendField(form, name, String(value)));

  document.body.appendChild(form);
  form.submit();

  // The browser is navigating to Google (or the NextAuth callback). Never
  // resolve, so callers cannot accidentally overwrite the navigation.
  return new Promise(() => {});
}

export function signInWithGoogle(callbackUrl = '/dashboard.html') {
  return submitSignIn('/api/auth/signin/google', {}, callbackUrl);
}

export function signInWithDev(email, name = 'Local Tester', callbackUrl = '/dashboard.html') {
  return submitSignIn('/api/auth/callback/dev', { email, name }, callbackUrl);
}

export async function getAuthProviders() {
  try {
    const response = await fetch('/api/auth/providers', {
      cache: 'no-store',
      headers: { Accept: 'application/json' },
    });
    return response.ok ? await response.json() : {};
  } catch {
    return {};
  }
}

export async function signOut() {
  try {
    const csrfResponse = await fetch('/api/auth/csrf', {
      credentials: 'include',
      cache: 'no-store',
      headers: { Accept: 'application/json' },
    });
    const { csrfToken } = csrfResponse.ok ? await csrfResponse.json() : {};

    if (csrfToken) {
      await fetch('/api/auth/signout', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          csrfToken,
          callbackUrl: '/auth/login.html',
        }),
      });
    }
  } catch (error) {
    console.error('[auth] sign-out failed', error);
  }

  tokenCache = { token: null, expiresAt: 0 };
  notify(null);
  window.location.href = '/auth/login.html';
}

export const auth = {
  get currentUser() {
    return currentUser;
  },
  onAuthStateChanged(callback) {
    return onAuthStateChanged(callback);
  },
};

export async function getSession() {
  if (currentUser) return currentUser;
  if (!sessionPromise) {
    sessionPromise = loadSession().finally(() => {
      sessionPromise = null;
    });
  }
  currentUser = await sessionPromise;
  return currentUser;
}
