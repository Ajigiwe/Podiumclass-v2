import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { api } from '../api/client';

/**
 * Feature flags mirrored from the backend (`/api/v1/core/features`).
 *
 * The backend is the source of truth; this store caches the public boolean map
 * so the SPA can hide surfaces that are switched off (e.g. the Wallet nav item
 * when `wallet_payments` is disabled) without a privileged round-trip. Flags
 * are fetched once at boot and after an admin toggles them.
 */
export const useFeaturesStore = defineStore('features', () => {
  const flags = ref<Record<string, boolean>>({});
  const loaded = ref(false);

  // Whether the backend will accept a credential-less POST to
  // /api/v1/accounts/google/. Fails closed: the login page's DEV
  // quick-access buttons stay hidden until the server says otherwise.
  const devLogin = ref(false);

  /** Resolve a flag; assume-enabled only for unknown keys to avoid blanking the UI. */
  function isEnabled(key: string): boolean {
    if (key in flags.value) return flags.value[key];
    return true;
  }

  const walletEnabled = computed(() => isEnabled('wallet_payments'));
  const liveClassroomEnabled = computed(() => isEnabled('classroom_live'));

  async function loadFeatures() {
    try {
      const doc = await api.getDocument<{
        features: Record<string, boolean>;
        devLogin?: boolean;
      }>('/api/v1/core/features/');
      flags.value = doc?.features ?? {};
      devLogin.value = !!doc?.devLogin;
      loaded.value = true;
    } catch (err) {
      // Never block the app on a flag fetch; fail open so nav still renders.
      // devLogin is deliberately NOT failed open: without it the login page
      // simply hides the credential-less buttons.
      console.warn('Could not load feature flags; defaulting to enabled', err);
      loaded.value = true;
    }
  }

  /** Replace local state after an admin toggle so the UI updates immediately. */
  function applyLocal(key: string, enabled: boolean) {
    flags.value = { ...flags.value, [key]: enabled };
  }

  return {
    flags,
    loaded,
    devLogin,
    isEnabled,
    walletEnabled,
    liveClassroomEnabled,
    loadFeatures,
    applyLocal,
  };
});
