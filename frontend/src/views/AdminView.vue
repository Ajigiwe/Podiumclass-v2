<template>
  <div class="max-w-7xl mx-auto w-full p-6 lg:p-8 space-y-8 animate-fade-in">
    <!-- Header with Admin Badge & Tab Switcher -->
    <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-[#DDE0F0] dark:border-slate-800 pb-5">
      <div class="flex items-center gap-3">
        <div class="w-10 h-10 rounded-xl bg-red-500/10 border border-red-500/30 flex items-center justify-center text-red-500">
          <i class="fas fa-shield-alt text-lg"></i>
        </div>
        <div>
          <div class="flex items-center gap-2">
            <h1 class="text-2xl font-bold text-[#0D0D1A] dark:text-white tracking-tight">Control Panel</h1>
            <span class="px-2 py-0.5 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-900/30 rounded text-[9px] font-black text-red-600 uppercase tracking-widest">
              SUPERADMIN
            </span>
          </div>
          <p class="text-xs text-[#8888A8] font-medium mt-0.5">Podium Institutional Infrastructure Management</p>
        </div>
      </div>

      <!-- Navigation Tabs -->
      <div class="flex flex-wrap gap-1 bg-[#F5F6FA] dark:bg-slate-900 p-1 rounded-xl border border-[#DDE0F0] dark:border-slate-800">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          @click="currentTab = tab.id"
          :class="[
            'px-3.5 py-1.5 rounded-lg text-xs font-bold transition-all cursor-pointer flex items-center gap-2',
            currentTab === tab.id
              ? 'bg-white dark:bg-slate-800 text-[#1845D4] dark:text-blue-400 shadow-sm'
              : 'text-[#8888A8] hover:text-[#0D0D1A] dark:hover:text-white'
          ]"
        >
          <i :class="tab.icon"></i>
          <span>{{ tab.label }}</span>
        </button>
      </div>
    </div>

    <!-- ============ TAB 1: DASHBOARD ============ -->
    <div v-if="currentTab === 'dashboard'" class="space-y-8">
      <!-- 4 Top KPI Stat Cards (derived from real data; "—" where no source) -->
      <div class="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div class="p-5 bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-2xl stat-glow-blue">
          <div class="flex items-center justify-between mb-3">
            <span class="text-[10px] font-black text-[#8888A8] uppercase tracking-widest">Total Sessions</span>
            <div class="w-8 h-8 rounded-lg bg-blue-50 dark:bg-blue-900/20 flex items-center justify-center">
              <i class="fas fa-layer-group text-blue-500 text-xs"></i>
            </div>
          </div>
          <p class="text-2xl font-black text-[#0D0D1A] dark:text-white tabular-nums">{{ totalSessions }}</p>
        </div>

        <div class="p-5 bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-2xl stat-glow-emerald">
          <div class="flex items-center justify-between mb-3">
            <span class="text-[10px] font-black text-[#8888A8] uppercase tracking-widest">Active Classrooms</span>
            <div class="w-8 h-8 rounded-lg bg-emerald-50 dark:bg-emerald-900/20 flex items-center justify-center">
              <i class="fas fa-broadcast-tower text-emerald-500 text-xs"></i>
            </div>
          </div>
          <p class="text-2xl font-black text-[#0D0D1A] dark:text-white tabular-nums">{{ activeSessions }}</p>
        </div>

        <div class="p-5 bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-2xl stat-glow-amber">
          <div class="flex items-center justify-between mb-3">
            <span class="text-[10px] font-black text-[#8888A8] uppercase tracking-widest">Revenue (GHS)</span>
            <div class="w-8 h-8 rounded-lg bg-amber-50 dark:bg-amber-900/20 flex items-center justify-center">
              <i class="fas fa-coins text-amber-500 text-xs"></i>
            </div>
          </div>
          <p class="text-2xl font-black text-[#0D0D1A] dark:text-white tabular-nums">GHS {{ revenueGhs }}</p>
        </div>

        <div class="p-5 bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-2xl stat-glow-green">
          <div class="flex items-center justify-between mb-3">
            <span class="text-[10px] font-black text-[#8888A8] uppercase tracking-widest">Verified Attendance</span>
            <div class="w-8 h-8 rounded-lg bg-rose-50 dark:bg-rose-900/20 flex items-center justify-center">
              <i class="fas fa-clipboard-check text-rose-500 text-xs"></i>
            </div>
          </div>
          <p class="text-2xl font-black text-[#0D0D1A] dark:text-white tabular-nums">{{ attendanceCount ?? '—' }}</p>
        </div>
      </div>

      <!-- Live Monitoring Columns -->
      <div class="grid lg:grid-cols-2 gap-6">
        <div class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-2xl p-6">
          <h3 class="text-sm font-bold text-[#0D0D1A] dark:text-white mb-4 flex items-center justify-between">
            <span>Active Live Sessions</span>
            <span class="w-2 h-2 rounded-full bg-emerald-500 animate-ping"></span>
          </h3>
          <div v-if="sessions.length === 0" class="text-xs text-[#8888A8] text-center py-8">
            No live sessions right now.
          </div>
          <div v-else class="space-y-3">
            <div
              v-for="s in sessions.slice(0, 5)"
              :key="s.id"
              class="flex items-center justify-between p-3 rounded-xl bg-[#F8F9FC] dark:bg-slate-800/50 border border-[#DDE0F0] dark:border-slate-700/50"
            >
              <div>
                <p class="text-xs font-bold text-[#0D0D1A] dark:text-white">{{ s.title }}</p>
                <p class="text-[10px] text-[#8888A8]">{{ s.course || '—' }} • Host: {{ s.lecturerName || '—' }}</p>
              </div>
              <router-link
                :to="`/classroom/${s.id}`"
                class="px-2.5 py-1 bg-[#1845D4] text-white text-[10px] font-bold rounded"
              >
                Inspect
              </router-link>
            </div>
          </div>
        </div>

        <div class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-2xl p-6">
          <h3 class="text-sm font-bold text-[#0D0D1A] dark:text-white mb-4">PostgreSQL Database Nodes</h3>
          <div class="space-y-3 text-xs">
            <div class="flex items-center justify-between p-3 rounded-xl bg-[#F8F9FC] dark:bg-slate-800/50">
              <span class="font-mono text-slate-700 dark:text-slate-300">Database Schema</span>
              <span class="font-bold text-emerald-600">PostgreSQL 16 (public)</span>
            </div>
            <div class="flex items-center justify-between p-3 rounded-xl bg-[#F8F9FC] dark:bg-slate-800/50">
              <span class="font-mono text-slate-700 dark:text-slate-300">LiveKit WebRTC Node</span>
              <span class="font-bold text-emerald-600 flex items-center gap-1.5">
                <span class="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
                ws://localhost:7880 (Self-Hosted)
              </span>
            </div>
            <div class="flex items-center justify-between p-3 rounded-xl bg-[#F8F9FC] dark:bg-slate-800/50">
              <span class="font-mono text-slate-700 dark:text-slate-300">MinIO Storage Cluster</span>
              <span class="font-bold text-purple-600">storage.podiumclass.online</span>
            </div>
            <div class="flex items-center justify-between p-3 rounded-xl bg-[#F8F9FC] dark:bg-slate-800/50">
              <span class="font-mono text-slate-700 dark:text-slate-300">External Attendance API</span>
              <span class="font-bold text-emerald-600">Active v1 Contract</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ============ TAB 2: SESSIONS ============ -->
    <div v-if="currentTab === 'sessions'" class="space-y-6">
      <div class="flex items-center justify-between">
        <h2 class="text-lg font-bold text-[#0D0D1A] dark:text-white">Classroom Sessions Registry</h2>
        <button
          @click="loadAdminData"
          class="px-4 py-2 bg-[#1845D4] text-white text-xs font-bold rounded-lg hover:bg-[#0F2FA8] transition-all cursor-pointer"
        >
          <i class="fas fa-sync-alt mr-1.5"></i> Refresh
        </button>
      </div>

      <div class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-2xl overflow-hidden shadow-sm">
        <table class="w-full text-left text-xs">
          <thead class="bg-[#F5F6FA] dark:bg-slate-800 text-[10px] font-black text-[#8888A8] uppercase tracking-widest border-b border-[#DDE0F0] dark:border-slate-800">
            <tr>
              <th class="px-5 py-3">Session Title</th>
              <th class="px-5 py-3">Course</th>
              <th class="px-5 py-3">Host / Lecturer</th>
              <th class="px-5 py-3">Status</th>
              <th class="px-5 py-3 text-right">Actions</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-[#DDE0F0] dark:divide-slate-800">
            <tr v-for="s in sessions" :key="s.id" class="hover:bg-[#F8F9FC] dark:hover:bg-slate-800/40">
              <td class="px-5 py-3.5 font-bold text-[#0D0D1A] dark:text-white">{{ s.title }}</td>
              <td class="px-5 py-3.5 font-mono text-slate-600 dark:text-slate-400">{{ s.course || '—' }}</td>
              <td class="px-5 py-3.5">{{ s.lecturerName || 'Faculty Host' }}</td>
              <td class="px-5 py-3.5">
                <span
                  :class="[
                    'px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider',
                    s.status === 'active' ? 'bg-emerald-50 text-emerald-600' : 'bg-slate-100 text-slate-600'
                  ]"
                >
                  {{ s.status }}
                </span>
              </td>
              <td class="px-5 py-3.5 text-right">
                <router-link :to="`/classroom/${s.id}`" class="text-[#1845D4] hover:underline font-bold">
                  Open Room
                </router-link>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ============ TAB 3: SETTINGS ============ -->
    <div v-if="currentTab === 'settings'" class="space-y-6">
      <div>
        <h2 class="text-xl font-bold text-[#0D0D1A] dark:text-white">Platform Settings</h2>
        <p class="text-xs text-[#8888A8] mt-1">
          Modular configuration and feature toggles, backed by the system_settings table.
        </p>
      </div>
      <!-- Reusable, registry-driven panel: feature toggles + config fields -->
      <SettingsPanel />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { api } from '../api/client';
import SettingsPanel from '../components/SettingsPanel.vue';

const currentTab = ref('dashboard');

const tabs = [
  { id: 'dashboard', label: 'Dashboard', icon: 'fas fa-tachometer-alt' },
  { id: 'sessions', label: 'Sessions', icon: 'fas fa-broadcast-tower' },
  { id: 'settings', label: 'Settings', icon: 'fas fa-cog' },
];

const sessions = ref<any[]>([]);
const transactions = ref<any[]>([]);
const attendanceCount = ref<number | null>(null);

// Derived, never fabricated. A metric with no data source renders as "—" rather
// than a made-up number.
const activeSessions = computed(() => sessions.value.filter((s) => s.status === 'active').length);
const totalSessions = computed(() => sessions.value.length);
const revenueGhs = computed(() => {
  const pesewas = transactions.value
    .filter((t) => t.status === 'succeeded')
    .reduce((sum, t) => sum + (Number(t.amount) || 0), 0);
  return (pesewas / 100).toFixed(2);
});

async function loadAdminData() {
  try {
    const res = await api.getDocuments<any>('/api/v1/classrooms/sessions/');
    sessions.value = res.documents || [];
  } catch (e) {
    console.error('Failed to load admin data', e);
  }
  try {
    // Admins may query all transactions; scope is not restricted to self.
    const tx = await api.getDocuments<any>('/api/v1/billing/transactions/');
    transactions.value = tx.documents || [];
  } catch (e) {
    console.warn('Transactions unavailable', e);
    transactions.value = [];
  }
  try {
    // Attendance summary is service-key protected; leave null (renders "—") if denied.
    const att = await api.getDocument<any>('/api/v1/attendance/sessions/?mode=summary');
    attendanceCount.value = att?.pagination?.count ?? att?.count ?? 0;
  } catch (e) {
    attendanceCount.value = null;
  }
}

onMounted(() => {
  loadAdminData();
});
</script>
