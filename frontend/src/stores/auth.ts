import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { api } from '../api/client';
import { identifyUser, resetAnalytics } from '../lib/analytics';

export interface User {
  id: string;
  email: string;
  fullName: string;
  role: 'student' | 'lecturer' | 'admin';
  photoUrl?: string;
  indexNumber?: string;
  walletBalance: number; // in pesewas
  university?: string;
  bio?: string;
  isVerified?: boolean;
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null);
  const token = ref<string | null>(localStorage.getItem('podium_token'));
  const isLoading = ref(false);

  const isAuthenticated = computed(() => !!user.value || !!token.value);
  const isLecturer = computed(() => user.value?.role === 'lecturer');
  const isAdmin = computed(() => user.value?.role === 'admin');
  const formattedBalance = computed(() => {
    if (!user.value) return 'GHS 0.00';
    return `GHS ${(user.value.walletBalance / 100).toFixed(2)}`;
  });

  async function fetchCurrentUser() {
    isLoading.value = true;
    try {
      const data = await api.getDocument<User>('/api/v1/accounts/me/');
      user.value = data;
      // Every page load re-resolves the account: tie it to its PostHog person
      // here so a refresh (or a second device) continues the same funnel.
      identifyUser(data);
      return data;
    } catch (err: any) {
      // A 401 means the token is missing/expired — clear it so the router
      // redirects to login instead of rendering as a phantom guest account.
      if (err?.status === 401) {
        token.value = null;
        user.value = null;
        localStorage.removeItem('podium_token');
      }
      throw err;
    } finally {
      isLoading.value = false;
    }
  }

  function logout() {
    user.value = null;
    token.value = null;
    localStorage.removeItem('podium_token');
    // Do not carry this person's distinct id into the next sign-in.
    resetAnalytics();
  }

  return {
    user,
    token,
    isLoading,
    isAuthenticated,
    isLecturer,
    isAdmin,
    formattedBalance,
    fetchCurrentUser,
    logout,
  };
});
