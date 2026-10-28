import posthog from 'posthog-js';

/**
 * Product analytics for the Podium SPA (PostHog).
 *
 * One thin module owns the SDK so the rest of the app only ever calls
 * `captureEvent()` / `identifyUser()` and never talks to PostHog directly.
 * Three rules live here:
 *
 * 1. **No key, no analytics.** Everything is a no-op unless `VITE_POSTHOG_KEY`
 *    is configured, so a developer's checkout behaves exactly as it did before
 *    this integration existed and no build can ship a half-initialised client.
 * 2. **Consent is respected.** A stored "denied" choice (the toggle in Account
 *    Settings) or the browser's Do Not Track / Global Privacy Control signal
 *    keeps the SDK from loading at all -- nothing is queued, nothing is sent.
 * 3. **Nothing sensitive is captured.** No autocapture, no session recording,
 *    no email or name in person properties: a classroom streams other people's
 *    cameras and microphones, and a recording of it is not analytics data.
 */

const POSTHOG_KEY = (import.meta.env.VITE_POSTHOG_KEY as string | undefined)?.trim();
const POSTHOG_HOST =
  (import.meta.env.VITE_POSTHOG_HOST as string | undefined)?.trim() || 'https://us.i.posthog.com';

export type AnalyticsConsent = 'granted' | 'denied';

// Stored in localStorage rather than the person record: it must survive a
// logout, otherwise signing out would look like a fresh grant.
const CONSENT_KEY = 'podium_analytics_consent';

let initialised = false;

function storedConsent(): AnalyticsConsent | null {
  try {
    const value = localStorage.getItem(CONSENT_KEY);
    return value === 'granted' || value === 'denied' ? value : null;
  } catch {
    return null;
  }
}

/** Did the browser send an opt-out signal we are required to honour? */
function browserOptsOut(): boolean {
  if (typeof navigator === 'undefined') return false;
  const dnt =
    navigator.doNotTrack ?? (window as any).doNotTrack ?? (navigator as any).msDoNotTrack;
  return dnt === '1' || dnt === 'yes' || (navigator as any).globalPrivacyControl === true;
}

/**
 * Is capture allowed right now?
 *
 * Analytics is opt-out (the product has no advertising surfaces), but an
 * explicit denial always wins over the default.
 */
function consentGranted(): boolean {
  return storedConsent() !== 'denied' && !browserOptsOut();
}

/** The choice the Account Settings toggle should show. */
export function getAnalyticsConsent(): AnalyticsConsent {
  return storedConsent() === 'denied' ? 'denied' : 'granted';
}

/**
 * Start the SDK once, at boot.
 *
 * Pageviews are captured by the router instead of the SDK's own heuristic,
 * because this is a single-page app where the URL changes without a load.
 */
export function initAnalytics(): void {
  if (initialised || typeof window === 'undefined') return;
  if (!POSTHOG_KEY) {
    if (import.meta.env.DEV) {
      console.info('[analytics] VITE_POSTHOG_KEY is not set; PostHog is disabled.');
    }
    return;
  }
  if (!consentGranted()) return;

  posthog.init(POSTHOG_KEY, {
    api_host: POSTHOG_HOST,
    capture_pageview: false,
    // Named events only: autocapture would ship classroom DOM text, and the
    // events that matter here (sign-in, class joined, attendance verified) are
    // ones we know by name.
    autocapture: false,
    // A person profile is created when somebody signs in, never for an
    // anonymous visitor.
    person_profiles: 'identified_only',
    // Session replay stays off -- a lecture room is other people's media.
    disable_session_recording: true,
    // Honour the browser's opt-out headers as well as our own toggle.
    respect_dnt: true,
  });

  initialised = true;
}

/** Record one product event. Silently does nothing without a key or consent. */
export function captureEvent(event: string, properties: Record<string, unknown> = {}): void {
  if (!initialised) return;
  try {
    posthog.capture(event, properties);
  } catch (err) {
    console.warn('[analytics] could not capture event', event, err);
  }
}

/** Every route change, which is what funnels and retention read. */
export function capturePageview(path: string): void {
  if (!initialised || typeof window === 'undefined') return;
  captureEvent('$pageview', { path, $current_url: window.location.href });
}

/**
 * Attach the signed-in account to its PostHog person.
 *
 * The id is the backend's user id (stable across devices). Deliberately no
 * email, name or photo: those live in Postgres and are not needed to follow a
 * funnel.
 */
export function identifyUser(user: {
  id?: string;
  role?: string;
  isVerified?: boolean;
  university?: string;
  indexNumber?: string;
} | null | undefined): void {
  if (!initialised || !user?.id) return;
  const properties: Record<string, unknown> = {
    role: user.role ?? 'student',
    is_verified: !!user.isVerified,
    // Onboarding is only complete once a student has an index number; keeping
    // it as a property makes that step readable in funnels.
    has_index_number: !!user.indexNumber,
  };
  if (user.university) properties.university = user.university;

  try {
    posthog.identify(user.id, properties);
  } catch (err) {
    console.warn('[analytics] could not identify user', err);
  }
}

/** Forget the person -- on sign-out, so the next login is not merged into it. */
export function resetAnalytics(): void {
  if (!initialised) return;
  try {
    posthog.reset();
  } catch (err) {
    console.warn('[analytics] could not reset analytics session', err);
  }
}

/**
 * Store the user's choice from Account Settings.
 *
 * Granting starts the SDK if it never loaded (it may not have, because the
 * decision was "denied" when the app booted); denying stops capture and drops
 * the cached distinct id.
 */
export function setAnalyticsConsent(consent: AnalyticsConsent): void {
  try {
    localStorage.setItem(CONSENT_KEY, consent);
  } catch {
    /* private mode: the in-memory choice below still applies for this page */
  }

  if (consent === 'granted') {
    initAnalytics();
    if (!initialised) return;
    try {
      posthog.opt_in_capturing();
    } catch (err) {
      console.warn('[analytics] could not opt in', err);
    }
    captureEvent('analytics_consent_granted');
    return;
  }

  if (initialised) {
    try {
      posthog.opt_out_capturing();
      posthog.reset();
    } catch (err) {
      console.warn('[analytics] could not opt out', err);
    }
  }
}
