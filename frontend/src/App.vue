<template>
  <!-- Fullscreen Standalone View (Login & Live Classroom Stage) -->
  <div v-if="isStandalonePage" class="min-h-screen w-full">
    <router-view />
  </div>

  <!-- Dashboard View with Persistent Left Sidebar matching lite-lms -->
  <div v-else class="min-h-screen flex bg-[#F8F9FC] dark:bg-slate-950 text-[#0D0D1A] dark:text-slate-100 font-sans">
    <!-- Sidebar -->
    <aside
      id="sidebar"
      :class="[
        'w-64 flex-col fixed inset-y-0 bg-white dark:bg-slate-950 border-r border-[#DDE0F0] dark:border-slate-800 z-50 transition-transform duration-300',
        mobileMenuOpen ? 'translate-x-0 flex' : '-translate-x-full lg:translate-x-0 lg:flex hidden'
      ]"
    >
      <!-- Mobile Close Button -->
      <div class="lg:hidden absolute top-4 right-4">
        <button @click="mobileMenuOpen = false" class="p-2 text-[#8888A8] hover:text-[#0D0D1A]">
          <i class="fas fa-times"></i>
        </button>
      </div>

      <!-- Podium Brand Logo -->
      <router-link to="/" class="h-16 px-6 flex items-center gap-2.5 border-b border-[#DDE0F0] dark:border-slate-800">
        <div class="w-8 h-8 bg-[#1845D4] rounded-lg flex items-center justify-center">
          <i class="fas fa-graduation-cap text-white text-sm"></i>
        </div>
        <span class="font-sans font-black text-lg text-[#0D0D1A] dark:text-white tracking-tight">Podium</span>
      </router-link>

      <!-- Sidebar Navigation Links -->
      <nav class="flex-1 px-4 py-6 overflow-y-auto space-y-6">
        <div>
          <p class="px-2 mb-1 text-[10px] font-black text-[#8888A8] uppercase tracking-[0.15em]">Workspace</p>
          <div class="space-y-0.5">
            <router-link
              to="/"
              class="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-[13px] font-semibold transition-all"
              :class="$route.path === '/' ? 'sidebar-active' : 'text-[#444460] dark:text-slate-400 hover:bg-[#F5F6FA] dark:hover:bg-slate-800 hover:text-[#0D0D1A] dark:hover:text-white'"
            >
              <span
                class="nav-icon w-8 h-8 rounded-lg flex items-center justify-center shadow-sm"
                :class="$route.path === '/' ? 'bg-[#1845D4] text-white' : 'bg-blue-50 dark:bg-blue-900/20 text-[#1845D4]'"
              >
                <i class="fas fa-layer-group text-xs"></i>
              </span>
              <span class="flex-1 text-left">Classes</span>
            </router-link>

            <router-link
              to="/attendance"
              class="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-[13px] font-semibold transition-all"
              :class="$route.path === '/attendance' ? 'sidebar-active' : 'text-[#444460] dark:text-slate-400 hover:bg-[#F5F6FA] dark:hover:bg-slate-800 hover:text-[#0D0D1A] dark:hover:text-white'"
            >
              <span
                class="nav-icon w-8 h-8 rounded-lg flex items-center justify-center shadow-sm"
                :class="$route.path === '/attendance' ? 'bg-[#1845D4] text-white' : 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600'"
              >
                <i class="fas fa-clipboard-check text-xs"></i>
              </span>
              <span class="flex-1 text-left">Attendance</span>
            </router-link>

            <router-link
              to="/history"
              class="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-[13px] font-semibold transition-all"
              :class="$route.path === '/history' ? 'sidebar-active' : 'text-[#444460] dark:text-slate-400 hover:bg-[#F5F6FA] dark:hover:bg-slate-800 hover:text-[#0D0D1A] dark:hover:text-white'"
            >
              <span
                class="nav-icon w-8 h-8 rounded-lg flex items-center justify-center shadow-sm"
                :class="$route.path === '/history' ? 'bg-[#1845D4] text-white' : 'bg-purple-50 dark:bg-purple-900/20 text-purple-600'"
              >
                <i class="fas fa-history text-xs"></i>
              </span>
              <span class="flex-1 text-left">History</span>
            </router-link>

            <router-link
              v-if="featuresStore.walletEnabled"
              to="/wallet"
              class="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-[13px] font-semibold transition-all"
              :class="$route.path === '/wallet' ? 'sidebar-active' : 'text-[#444460] dark:text-slate-400 hover:bg-[#F5F6FA] dark:hover:bg-slate-800 hover:text-[#0D0D1A] dark:hover:text-white'"
            >
              <span
                class="nav-icon w-8 h-8 rounded-lg flex items-center justify-center shadow-sm"
                :class="$route.path === '/wallet' ? 'bg-[#1845D4] text-white' : 'bg-amber-50 dark:bg-amber-900/20 text-amber-600'"
              >
                <i class="fas fa-wallet text-xs"></i>
              </span>
              <span class="flex-1 text-left">Transactions</span>
            </router-link>
          </div>
        </div>

        <!-- Admin Control Panel Link (Exact lite-lms match) -->
        <div v-if="authStore.isAdmin">
          <p class="px-2 mb-1 text-[10px] font-black text-red-500 uppercase tracking-[0.15em]">Admin</p>
          <div class="space-y-0.5">
            <router-link
              to="/admin"
              class="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-[13px] font-semibold transition-all"
              :class="$route.path === '/admin' ? 'sidebar-active' : 'text-[#444460] dark:text-slate-400 hover:bg-red-50 dark:hover:bg-red-900/20 hover:text-red-600 dark:hover:text-red-400'"
            >
              <span
                class="nav-icon w-8 h-8 rounded-lg flex items-center justify-center shadow-sm"
                :class="$route.path === '/admin' ? 'bg-[#1845D4] text-white' : 'bg-red-50 dark:bg-red-900/20 text-red-500'"
              >
                <i class="fas fa-shield-alt text-xs"></i>
              </span>
              <span class="flex-1 text-left">Control Panel</span>
            </router-link>
          </div>
        </div>
      </nav>

      <!-- Sidebar Footer (Theme, Profile, Signout) -->
      <div class="p-4 border-t border-[#DDE0F0] dark:border-slate-800 space-y-2">
        <button
          @click="toggleTheme"
          class="w-full flex items-center gap-3 px-3 py-2 rounded-lg text-[12px] font-semibold transition-all text-[#8888A8] hover:bg-[#F5F6FA] dark:hover:bg-slate-800 group cursor-pointer"
        >
          <i :class="isDark ? 'fas fa-sun w-4 h-4' : 'fas fa-moon w-4 h-4'"></i>
          <span>{{ isDark ? 'Light mode' : 'Toggle theme' }}</span>
        </button>

        <router-link
          to="/profile"
          id="user-profile-preview"
          class="flex items-center gap-3 px-3 py-2 rounded-xl hover:bg-[#F5F6FA] dark:hover:bg-slate-800 transition-all group"
        >
          <div class="w-9 h-9 rounded-xl bg-[#1845D4] flex items-center justify-center text-white text-xs font-black shadow-sm shrink-0">
            {{ (authStore.user?.fullName || 'U').charAt(0) }}
          </div>
          <div class="flex-1 min-w-0">
            <p class="text-[13px] font-bold text-[#0D0D1A] dark:text-slate-100 truncate">
              {{ authStore.user?.fullName || 'Podium User' }}
            </p>
            <p class="text-[10px] font-bold text-[#8888A8] dark:text-slate-500 uppercase tracking-widest truncate">
              {{ authStore.user?.role || 'Student' }}
            </p>
          </div>
          <i class="fas fa-chevron-right text-[8px] text-[#8888A8] group-hover:translate-x-0.5 transition-transform"></i>
        </router-link>

        <button
          @click="handleLogout"
          class="w-full flex items-center gap-3 px-3 py-2 rounded-lg text-[12px] font-semibold text-[#8888A8] hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 transition-all cursor-pointer"
        >
          <i class="fas fa-sign-out-alt w-4 h-4"></i>
          <span>Sign out</span>
        </button>
      </div>
    </aside>

    <!-- Sidebar Overlay for Mobile -->
    <div
      v-if="mobileMenuOpen"
      @click="mobileMenuOpen = false"
      class="fixed inset-0 bg-[#0D0D1A]/40 backdrop-blur-sm z-40 lg:hidden"
    ></div>

    <!-- Mobile Top Header Bar -->
    <div class="lg:hidden fixed top-0 left-0 right-0 h-16 bg-white dark:bg-slate-950 border-b border-[#DDE0F0] dark:border-slate-800 flex items-center justify-between px-5 z-40">
      <router-link to="/" class="font-serif font-black text-[#1845D4] text-lg tracking-tighter">Podium</router-link>
      <div class="flex items-center gap-2">
        <button @click="mobileMenuOpen = !mobileMenuOpen" class="p-2 text-[#0D0D1A] dark:text-white bg-[#F5F6FA] dark:bg-slate-800 rounded-lg">
          <i class="fas fa-bars"></i>
        </button>
      </div>
    </div>

    <!-- Main Content Area -->
    <main class="flex-1 flex flex-col min-w-0 lg:pl-64 pt-16 lg:pt-0">
      <router-view />
    </main>
  </div>

  <!-- App-wide toasts: the same feedback on every page, including the
       fullscreen classroom, which renders outside the shell above. -->
  <ToastHost />
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAuthStore } from './stores/auth';
import { useFeaturesStore } from './stores/features';
import ToastHost from './components/ToastHost.vue';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const featuresStore = useFeaturesStore();
const mobileMenuOpen = ref(false);
const isDark = ref(false);

const isStandalonePage = computed(() => {
  // The landing page and login render without the sidebar; the classroom takes
  // the whole viewport.
  return (
    route.path === '/login' ||
    route.path === '/welcome' ||
    route.path === '/about' ||
    route.path.startsWith('/classroom')
  );
});

function toggleTheme() {
  isDark.value = !isDark.value;
  if (isDark.value) {
    document.documentElement.classList.add('dark');
  } else {
    document.documentElement.classList.remove('dark');
  }
}

function handleLogout() {
  authStore.logout();
  router.push('/login');
}

onMounted(async () => {
  // fetchCurrentUser clears the stored token on a 401 and throws; swallow it
  // here so the router guard can redirect to login instead of an unhandled error.
  try {
    await authStore.fetchCurrentUser();
  } catch {
    /* handled: token cleared, router guard redirects */
  }
});
</script>
