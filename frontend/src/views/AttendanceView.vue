<template>
  <div class="flex-1 px-5 py-6 lg:px-10 lg:py-8 max-w-6xl mx-auto w-full space-y-8 animate-fade-in">
    <!-- Header Row -->
    <div class="flex flex-col sm:flex-row sm:items-end justify-between gap-4">
      <div>
        <h1 class="text-2xl lg:text-3xl font-bold text-[#0D0D1A] dark:text-white tracking-tight">
          Attendance Engine
        </h1>
        <p class="text-sm text-[#8888A8] font-medium mt-1">
          Automated biometric verification and real-time synchronization with external systems (Laravel API v1).
        </p>
      </div>

      <div class="flex items-center gap-3">
        <button
          @click="refreshData"
          :disabled="isSyncing"
          class="px-5 py-2.5 bg-[#1845D4] text-white text-[13px] font-bold rounded-lg hover:bg-[#0F2FA8] transition-all flex items-center gap-2 shadow-lg shadow-blue-600/10 cursor-pointer"
        >
          <i :class="['fas fa-sync-alt text-xs', { 'animate-spin': isSyncing }]"></i>
          {{ isSyncing ? 'Refreshing...' : 'Refresh' }}
        </button>

        <button
          @click="exportJSON"
          class="px-4 py-2.5 bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 text-[13px] font-bold rounded-lg text-[#0D0D1A] dark:text-white hover:bg-[#F5F6FA] transition-all cursor-pointer shadow-sm"
        >
          <i class="fas fa-download text-xs text-[#8888A8] mr-1"></i> Export JSON
        </button>
      </div>
    </div>

    <!-- Telemetry Stat Cards -->
    <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
      <div class="bg-white dark:bg-slate-900 rounded-xl p-5 border border-[#DDE0F0] dark:border-slate-800 stat-glow-blue">
        <div class="flex items-center gap-3 mb-2">
          <div class="w-10 h-10 rounded-lg bg-blue-50 dark:bg-blue-900/20 flex items-center justify-center text-[#1845D4]">
            <i class="fas fa-clipboard-check text-sm"></i>
          </div>
          <span class="text-[11px] font-bold text-[#8888A8] uppercase tracking-widest">Total Verified</span>
        </div>
        <div class="text-3xl font-bold text-[#0D0D1A] dark:text-white tracking-tight tabular-nums">{{ totalVerified }}</div>
        <p class="text-[11px] text-[#8888A8] mt-1">Check-ins confirmed across sessions</p>
      </div>

      <div class="bg-white dark:bg-slate-900 rounded-xl p-5 border border-[#DDE0F0] dark:border-slate-800 stat-glow-emerald">
        <div class="flex items-center gap-3 mb-2">
          <div class="w-10 h-10 rounded-lg bg-emerald-50 dark:bg-emerald-900/20 flex items-center justify-center text-emerald-600">
            <i class="fas fa-chart-line text-sm"></i>
          </div>
          <span class="text-[11px] font-bold text-[#8888A8] uppercase tracking-widest">Live Sessions</span>
        </div>
        <div class="text-3xl font-bold text-emerald-600 tracking-tight tabular-nums">{{ liveSessions.length }}</div>
        <p class="text-[11px] text-[#8888A8] mt-1">Currently in progress</p>
      </div>

      <div class="bg-white dark:bg-slate-900 rounded-xl p-5 border border-[#DDE0F0] dark:border-slate-800 stat-glow-green">
        <div class="flex items-center gap-3 mb-2">
          <div class="w-10 h-10 rounded-lg bg-green-50 dark:bg-green-900/20 flex items-center justify-center text-green-600">
            <i class="fas fa-shield-alt text-sm"></i>
          </div>
          <span class="text-[11px] font-bold text-[#8888A8] uppercase tracking-widest">Sessions Tracked</span>
        </div>
        <div class="text-3xl font-bold text-[#0D0D1A] dark:text-white tracking-tight tabular-nums">{{ sessions.length }}</div>
        <p class="text-[11px] text-[#8888A8] mt-1">Total recorded sessions</p>
      </div>
    </div>

    <!-- Attendance Table Card -->
    <div class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl overflow-hidden shadow-sm">
      <div class="px-6 py-4 border-b border-[#DDE0F0] dark:border-slate-800 flex items-center justify-between">
        <h3 class="font-bold text-[13px] text-[#0D0D1A] dark:text-white">Active Session Logs</h3>
        <input
          v-model="searchQuery"
          placeholder="Filter courses..."
          class="px-3 py-1.5 bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 rounded-lg text-xs outline-none focus:border-[#1845D4]"
        />
      </div>

      <table class="w-full text-left border-collapse">
        <thead class="bg-[#F5F6FA] dark:bg-slate-800/50 border-b border-[#DDE0F0] dark:border-slate-800">
          <tr class="text-[10px] font-bold text-[#8888A8] uppercase tracking-[0.1em]">
            <th class="px-6 py-3.5">Course</th>
            <th class="px-6 py-3.5">Lecture Title</th>
            <th class="px-6 py-3.5">Code</th>
            <th class="px-6 py-3.5">Lecturer</th>
            <th class="px-6 py-3.5">Attendance</th>
            <th class="px-6 py-3.5">Status</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-[#DDE0F0] dark:divide-slate-800">
          <tr v-for="item in filteredList" :key="item.id" class="text-xs hover:bg-[#F8F9FC] dark:hover:bg-slate-800/40 transition-colors">
            <td class="px-6 py-4 font-bold text-[#1845D4]">{{ item.course || '—' }}</td>
            <td class="px-6 py-4 font-semibold text-[#0D0D1A] dark:text-white">{{ item.title }}</td>
            <td class="px-6 py-4 font-mono text-[11px] text-[#8888A8]">{{ item.meetingCode }}</td>
            <td class="px-6 py-4 text-[#444460] dark:text-slate-400">{{ item.lecturerName || '—' }}</td>
            <td class="px-6 py-4 font-bold text-emerald-600">
              <span class="bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600 px-2 py-1 rounded-full text-[10px]">
                {{ item.participantCount ?? 0 }} Verified
              </span>
            </td>
            <td class="px-6 py-4">
              <span
                :class="[
                  'text-[10px] font-bold px-2 py-0.5 rounded-full uppercase tracking-wider',
                  item.status === 'active' ? 'bg-blue-50 text-[#1845D4]' : 'bg-[#F5F6FA] text-[#8888A8]'
                ]"
              >
                {{ item.status }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { api } from '../api/client';

const sessions = ref<any[]>([]);
const isSyncing = ref(false);
const searchQuery = ref('');

// Real aggregates derived from the session list. These replace a hardcoded
// "1,428 verified / 94.6% rate / +4.2%" panel that showed the same numbers
// regardless of what was actually in the database.
const totalVerified = computed(() =>
  sessions.value.reduce((sum, s) => sum + (Number(s.participantCount) || 0), 0)
);
const liveSessions = computed(() => sessions.value.filter((s) => s.status === 'active'));

const filteredList = computed(() => {
  if (!searchQuery.value.trim()) return sessions.value;
  const q = searchQuery.value.toLowerCase();
  return sessions.value.filter(
    (s) => (s.course && s.course.toLowerCase().includes(q)) || (s.title && s.title.toLowerCase().includes(q))
  );
});

async function loadData() {
  try {
    const res = await api.getDocuments<any>('/api/v1/classrooms/sessions/');
    sessions.value = res.documents || [];
  } catch (e) {
    console.error(e);
  }
}

// Was "Sync to Laravel", which slept for a second and then claimed a
// synchronization that never happened. There is no push-sync endpoint; this
// now simply reloads the data it displays.
async function refreshData() {
  isSyncing.value = true;
  try {
    await loadData();
  } finally {
    isSyncing.value = false;
  }
}

function exportJSON() {
  const jsonStr = JSON.stringify({ documents: sessions.value }, null, 2);
  const blob = new Blob([jsonStr], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `podium-attendance-${Date.now()}.json`;
  a.click();
}

onMounted(loadData);
</script>
