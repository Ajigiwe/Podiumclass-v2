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

        <!-- Development / LAN Access for Mobile Testing -->
        <div v-if="isDev" class="pt-4 mt-2 border-t border-white/10 flex flex-col gap-2">
          <p class="text-[9px] font-bold text-slate-400 uppercase tracking-widest text-center">Dev / Mobile Quick Access</p>
          <div class="flex gap-2">
            <button
              type="button"
              @click="handleDevLogin('admin')"
              :disabled="isLoading"
              class="flex-1 py-2 bg-indigo-600/30 hover:bg-indigo-600/50 border border-indigo-500/30 text-indigo-300 rounded-xl text-xs font-bold transition-all cursor-pointer shadow-sm text-center"
            >
              Host (Admin)
            </button>
            <button
              type="button"
              @click="handleDevLogin('student')"
              :disabled="isLoading"
              class="flex-1 py-2 bg-white/5 hover:bg-white/10 border border-white/10 text-slate-300 rounded-xl text-xs font-bold transition-all cursor-pointer shadow-sm text-center"
            >
              Student
            </button>
          </div>
        </div>

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
import { ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import { api } from '../api/client';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const isLoading = ref(false);
const errorMessage = ref<string | null>(null);
const isDev = import.meta.env.DEV;

const GOOGLE_CLIENT_ID = '148808386481-qp65l50jmuabfte437aafmh85esth944.apps.googleusercontent.com';

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

async function startGoogleLogin() {
  isLoading.value = true;
  errorMessage.value = null;

  try {
    await loadGsiClient();

    const client = (window as any).google.accounts.oauth2.initTokenClient({
      client_id: GOOGLE_CLIENT_ID,
      scope: 'email profile openid',
      callback: async (tokenResponse: any) => {
        if (tokenResponse.error) {
          isLoading.value = false;
          errorMessage.value = 'Google sign-in was cancelled or encountered an error.';
          return;
        }

        try {
          const data = await api.postDocument<{ user: any; token: string }>('/api/v1/accounts/google/', {
            access_token: tokenResponse.access_token,
          });

          authStore.user = data.user;
          authStore.token = data.token;
          localStorage.setItem('podium_token', data.token);
          const redirect = (route.query.redirect as string) || (needsProfileSetup(data.user) ? '/profile?setup=1' : '/');
          router.push(redirect);
        } catch (err: any) {
          errorMessage.value = err?.message || 'Authentication failed. Please verify credentials.';
        } finally {
          isLoading.value = false;
        }
      },
    });

    // Explicit user trigger - opens popup only on click, NEVER on page load
    client.requestAccessToken({ prompt: 'select_account' });
  } catch (err: any) {
    isLoading.value = false;
    errorMessage.value = 'Failed to initialize Google Authentication client.';
  }
}

async function handleDevLogin(role: 'admin' | 'student') {
  isLoading.value = true;
  errorMessage.value = null;
  try {
    const data = await api.postDocument<{ user: any; token: string }>('/api/v1/accounts/google/', {
      dev_login: true,
      role,
    });
    authStore.user = data.user;
    authStore.token = data.token;
    localStorage.setItem('podium_token', data.token);
    const redirect = (route.query.redirect as string) || (needsProfileSetup(data.user) ? '/profile?setup=1' : '/');
    router.push(redirect);
  } catch (err: any) {
    errorMessage.value = err?.message || 'Quick login failed.';
  } finally {
    isLoading.value = false;
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
  align-items: center;
  gap: 0.5rem;
  font-size: 0.75rem;
  color: #FCA5A5;
  text-align: left;
}

.error-icon {
  flex-shrink: 0;
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
