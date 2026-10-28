<template>
  <div class="auth-page">
    <!-- Clean Dark Overlay -->
    <div class="auth-backdrop"></div>

    <!-- Login Container -->
    <div class="auth-container">
      <!-- Podium Brand Header (Exact lite-lms match) -->
      <div class="brand-header">
        <router-link to="/" class="brand-link">
          <div class="brand-badge">
            <i class="fas fa-graduation-cap"></i>
          </div>
          <span class="brand-title">Podium</span>
        </router-link>
      </div>

      <!-- Clean Auth Card (No gradients, no AI slop, razor-sharp contrast) -->
      <div class="auth-card">
        <h1 class="card-heading">Welcome back</h1>
        <p class="card-subheading">Sign in to access your workspace</p>

        <!-- Error Feedback Banner -->
        <div v-if="errorMessage" class="error-alert">
          <i class="fas fa-circle-exclamation error-icon"></i>
          <span>{{ errorMessage }}</span>
        </div>

        <!-- Clean Native Google Sign-In Button (Only opens on click, never on refresh) -->
        <button
          type="button"
          id="google-signin-btn"
          @click="startGoogleLogin"
          :disabled="isLoading"
          class="google-btn"
        >
          <svg class="google-svg" viewBox="0 0 24 24">
            <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" />
            <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" />
            <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" />
            <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" />
          </svg>
          <span class="google-btn-text">
            {{ isLoading ? 'Signing in...' : 'Continue with Google' }}
          </span>
        </button>

        <!-- Clean Footnote -->
        <div class="card-footer">
          <span>Institutional Access</span>
          <span class="footer-dot">&bull;</span>
          <span class="footer-brand">Podium Cloud</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import { api } from '../api/client';
import { captureEvent, identifyUser } from '../lib/analytics';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const isLoading = ref(false);
const errorMessage = ref<string | null>(null);

/**
 * The OAuth client this build signs in with.
 *
 * Read from env so a deployment (or a second environment) can point at its own
 * client without editing source. The literal is the project's existing client,
 * so an unconfigured checkout behaves exactly as before.
 */
const GOOGLE_CLIENT_ID =
  (import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined)?.trim() ||
  '148808386481-qp65l50jmuabfte437aafmh85esth944.apps.googleusercontent.com';

/**
 * Origins this OAuth client has registered as "Authorized JavaScript origins".
 *
 * Google Identity Services checks the *page's* origin, and answers an
 * unregistered one with `origin_mismatch` -- rendered inside Google's own
 * popup, where this app cannot read it. Checking the origin here means the
 * user gets an explanation in our UI, in place, instead of a dead popup that
 * looks like the button did nothing.
 *
 * Override with VITE_GOOGLE_ALLOWED_ORIGINS when the same client also serves
 * other hosts (a LAN address for phone testing, a staging domain, ...).
 */
const GOOGLE_ALLOWED_ORIGINS = (
  (import.meta.env.VITE_GOOGLE_ALLOWED_ORIGINS as string | undefined) ||
  'http://localhost:3000,http://127.0.0.1:3000'
)
  .split(',')
  .map((o) => o.trim().replace(/\/$/, ''))
  .filter(Boolean);

/** True when this page's origin is not registered for the OAuth client. */
function originUnregistered(): string | null {
  if (typeof window === 'undefined') return null;
  const origin = window.location.origin.replace(/\/$/, '');
  return GOOGLE_ALLOWED_ORIGINS.includes(origin) ? null : origin;
}

/**
 * Turn a GIS error into something a person can act on.
 *
 * `popup_closed` is ambiguous: it is what a user's own cancel looks like, and
 * it is also how a Google-side block arrives -- Google draws "Access blocked:
 * origin_mismatch" in the popup and the only way out is closing it. So the
 * message names that likely cause rather than shrugging.
 */
function describeGoogleError(code: string): string {
  const origin = typeof window !== 'undefined' ? window.location.origin : '';
  if (code === 'access_denied') return 'Google sign-in was cancelled.';
  if (code === 'popup_closed') {
    return `The Google window closed before sign-in finished. If it showed “Access blocked” or origin_mismatch, then ${origin} is not registered for this OAuth client: add it under Google Cloud Console → APIs & Services → Credentials → Authorized JavaScript origins.`;
  }
  if (code === 'popup_failed_to_open') {
    return 'The sign-in window was blocked. Allow pop-ups for this site and try again.';
  }
  return `Google sign-in failed (${code || 'unknown error'}). Please try again.`;
}

function loadGsiClient(): Promise<void> {
  return new Promise((resolve) => {
    if ((window as any).google?.accounts?.oauth2) {
      return resolve();
    }
    const existingScript = document.getElementById('gsi-client-script');
    if (existingScript) {
      existingScript.onload = () => resolve();
      return;
    }
    const script = document.createElement('script');
    script.id = 'gsi-client-script';
    script.src = 'https://accounts.google.com/gsi/client';
    script.async = true;
    script.defer = true;
    script.onload = () => resolve();
    document.head.appendChild(script);
  });
}

// Warm the GSI client while the page is idle so the sign-in click does not have
// to. Awaiting a script load inside the handler spends the user gesture, and a
// popup opened after that is blocked -- which read as "sign-in does nothing".
onMounted(() => {
  void loadGsiClient();
});

async function startGoogleLogin() {
  errorMessage.value = null;

  // Refuse before opening anything Google-side when we already know this
  // origin is not registered: the popup would only show Google's error page.
  const blockedOrigin = originUnregistered();
  if (blockedOrigin) {
    isLoading.value = false;
    errorMessage.value = `Google sign-in is unavailable from ${blockedOrigin}. This origin is not registered for the OAuth client, so Google would reject it. Add it under Google Cloud Console → APIs & Services → Credentials → Authorized JavaScript origins${GOOGLE_ALLOWED_ORIGINS[0] ? `, or open the app at ${GOOGLE_ALLOWED_ORIGINS[0]}` : ''}.`;
    return;
  }

  isLoading.value = true;

  try {
    if (!(window as any).google?.accounts?.oauth2) await loadGsiClient();

    const client = (window as any).google.accounts.oauth2.initTokenClient({
      client_id: GOOGLE_CLIENT_ID,
      scope: 'email profile openid',
      // A popup, not a redirect: the page we are on stays put, so the intended
      // destination survives the round trip to Google.
      ux_mode: 'popup',
      // Popup-level failures (blocked pop-up, closed window) arrive here, not
      // through the token callback.
      error_callback: (err: any) => {
        isLoading.value = false;
        errorMessage.value = describeGoogleError(err?.type || 'unknown');
      },
      callback: async (tokenResponse: any) => {
        if (tokenResponse.error) {
          isLoading.value = false;
          errorMessage.value = describeGoogleError(tokenResponse.error);
          return;
        }

        try {
          const data = await api.postDocument<{ user: any; token: string }>('/api/v1/accounts/google/', {
            access_token: tokenResponse.access_token,
          });

          authStore.user = data.user;
          authStore.token = data.token;
          localStorage.setItem('podium_token', data.token);
          // The first step of the journey we care about: who signed in, with
          // which role, and whether onboarding still owes an index number.
          identifyUser(data.user);
          captureEvent('user_signed_in', {
            method: 'google',
            role: data.user?.role || 'student',
            needs_profile_setup: needsProfileSetup(data.user),
          });
          const redirect = (route.query.redirect as string) || (needsProfileSetup(data.user) ? '/profile?setup=1' : '/');
          router.push(redirect);
        } catch (err: any) {
          errorMessage.value = err?.message || 'Authentication failed. Please verify credentials.';
        } finally {
          isLoading.value = false;
        }
      },
    });

    // Explicit user trigger - opens the popup only on click, never on load.
    // This runs in the same task as the click whenever the client is warm.
    client.requestAccessToken({ prompt: 'select_account' });
  } catch (err: any) {
    isLoading.value = false;
    errorMessage.value = 'Failed to initialize Google Authentication client.';
  }
}
function needsProfileSetup(user: any): boolean {
  return user?.role === 'student' && !user?.indexNumber;
}
</script>

<style scoped>
/* Main Page Container */
.auth-page {
  min-height: 100vh;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  position: relative;
  background-image: url('/s4.jpg');
  background-size: cover;
  background-position: center;
  user-select: none;
  box-sizing: border-box;
}

/* Deep Solid Dark Overlay - Crisp contrast */
.auth-backdrop {
  position: absolute;
  inset: 0;
  background-color: rgba(9, 11, 17, 0.78);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

/* Auth Center Box */
.auth-container {
  position: relative;
  z-index: 10;
  width: 100%;
  max-width: 360px;
}

/* Brand Header */
.brand-header {
  text-align: center;
  margin-bottom: 1.5rem;
}

.brand-link {
  display: inline-flex;
  align-items: center;
  gap: 0.65rem;
  text-decoration: none;
}

.brand-badge {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background-color: #1845D4;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #FFFFFF;
  font-size: 1.1rem;
  box-shadow: 0 4px 12px rgba(24, 69, 212, 0.35);
}

.brand-title {
  font-family: 'Outfit', sans-serif;
  font-size: 1.65rem;
  font-weight: 800;
  color: #FFFFFF;
  letter-spacing: -0.03em;
}

/* Enhanced Clean Card - No gradients, razor-sharp solid finish */
.auth-card {
  background-color: rgba(17, 21, 31, 0.90);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  padding: 2rem 1.75rem;
  box-shadow: 0 20px 40px -15px rgba(0, 0, 0, 0.7), 0 0 0 1px rgba(255, 255, 255, 0.04);
  text-align: center;
  box-sizing: border-box;
}

.card-heading {
  font-size: 1.35rem;
  font-weight: 700;
  color: #FFFFFF;
  margin: 0 0 0.35rem 0;
  letter-spacing: -0.02em;
}

.card-subheading {
  font-size: 0.8125rem;
  color: #94A3B8;
  margin: 0 0 1.5rem 0;
  font-weight: 400;
}

/* Error Banner */
.error-alert {
  margin-bottom: 1rem;
  border-radius: 8px;
  background-color: rgba(239, 68, 68, 0.15);
  border: 1px solid rgba(239, 68, 68, 0.3);
  padding: 0.65rem 0.85rem;
  display: flex;
  /* Top-aligned, because a registration message runs to several lines and a
     centred icon would float in the middle of it. */
  align-items: flex-start;
  gap: 0.5rem;
  font-size: 0.75rem;
  line-height: 1.45;
  color: #FCA5A5;
  text-align: left;
}

.error-icon {
  flex-shrink: 0;
  margin-top: 1px;
}

/* Clean Full-Width Native Google Button */
.google-btn {
  width: 100%;
  height: 46px;
  background-color: #FFFFFF;
  color: #1F2937;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.25);
  margin-bottom: 1.5rem;
}

.google-btn:hover:not(:disabled) {
  background-color: #F8FAFC;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.35);
}

.google-btn:active:not(:disabled) {
  transform: scale(0.99);
}

.google-btn:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}

.google-svg {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.google-btn-text {
  letter-spacing: -0.01em;
}

/* Clean Institutional Footer */
.card-footer {
  font-size: 0.75rem;
  color: #64748B;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.4rem;
}

.footer-dot {
  opacity: 0.5;
}

.footer-brand {
  color: #E2E8F0;
  font-weight: 600;
}
</style>
