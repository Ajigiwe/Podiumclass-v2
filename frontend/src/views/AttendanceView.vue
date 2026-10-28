<template>
  <div class="flex-1 px-5 py-6 lg:px-10 lg:py-8 max-w-6xl mx-auto w-full space-y-8 animate-fade-in">
    <!-- Header Row -->
    <div class="flex flex-col sm:flex-row sm:items-end justify-between gap-4">
      <div>
        <h1 class="text-2xl lg:text-3xl font-bold text-[#0D0D1A] dark:text-white tracking-tight">
          {{ isStudent ? 'My Attendance' : 'Attendance' }}
        </h1>
        <p class="text-sm text-[#8888A8] font-medium mt-1">
          {{ isStudent
            ? 'Every class you attended, how long you stayed, and the checks you answered — kept even after a class leaves your dashboard.'
            : 'Your class registers: who attended, when they joined and left, and the checks they passed.' }}
        </p>
      </div>

      <div class="flex items-center gap-3">
        <button
          @click="refreshData"
          :disabled="isSyncing"
          class="px-5 py-2.5 bg-[#1845D4] text-white text-[13px] font-bold rounded-lg hover:bg-[#0F2FA8] transition-all flex items-center gap-2 shadow-lg shadow-blue-600/10 cursor-pointer disabled:opacity-70"
        >
          <i :class="['fas fa-sync-alt text-xs', { 'animate-spin': isSyncing }]"></i>
          {{ isSyncing ? 'Refreshing...' : 'Refresh' }}
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
          <span class="text-[11px] font-bold text-[#8888A8] uppercase tracking-widest">
            {{ isStudent ? 'Verified present' : 'Total verified' }}
          </span>
        </div>
        <div class="text-3xl font-bold text-[#0D0D1A] dark:text-white tracking-tight tabular-nums">{{ totalVerified }}</div>
        <p class="text-[11px] text-[#8888A8] mt-1">
          {{ isStudent ? 'Classes where your checks counted' : 'Confirmed check-ins across your classes' }}
        </p>
      </div>

      <div class="bg-white dark:bg-slate-900 rounded-xl p-5 border border-[#DDE0F0] dark:border-slate-800 stat-glow-emerald">
        <div class="flex items-center gap-3 mb-2">
          <div class="w-10 h-10 rounded-lg bg-emerald-50 dark:bg-emerald-900/20 flex items-center justify-center text-emerald-600">
            <i class="fas fa-hourglass-half text-sm"></i>
          </div>
          <span class="text-[11px] font-bold text-[#8888A8] uppercase tracking-widest">
            {{ isStudent ? 'Time in class' : 'Students tracked' }}
          </span>
        </div>
        <div class="text-3xl font-bold text-emerald-600 tracking-tight tabular-nums">
          {{ isStudent ? formatSpan(totalStayMinutes) : totalStudents }}
        </div>
        <p class="text-[11px] text-[#8888A8] mt-1">
          {{ isStudent ? 'Sum of every class you attended' : 'Across every register below' }}
        </p>
      </div>

      <div class="bg-white dark:bg-slate-900 rounded-xl p-5 border border-[#DDE0F0] dark:border-slate-800 stat-glow-green">
        <div class="flex items-center gap-3 mb-2">
          <div class="w-10 h-10 rounded-lg bg-green-50 dark:bg-green-900/20 flex items-center justify-center text-green-600">
            <i class="fas fa-shield-alt text-sm"></i>
          </div>
          <span class="text-[11px] font-bold text-[#8888A8] uppercase tracking-widest">
            {{ isStudent ? 'Classes attended' : 'Class registers' }}
          </span>
        </div>
        <div class="text-3xl font-bold text-[#0D0D1A] dark:text-white tracking-tight tabular-nums">{{ rows.length }}</div>
        <p class="text-[11px] text-[#8888A8] mt-1">
          {{ isStudent ? 'Including classes removed from your dashboard' : 'Every class you have run' }}
        </p>
      </div>
    </div>

    <!-- Attendance Table Card -->
    <div class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl overflow-hidden shadow-sm">
      <div class="px-6 py-4 border-b border-[#DDE0F0] dark:border-slate-800 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <h3 class="font-bold text-[13px] text-[#0D0D1A] dark:text-white">
          {{ isStudent ? 'Classes you attended' : 'Your class registers' }}
        </h3>
        <input
          v-model="searchQuery"
          placeholder="Filter by course or title..."
          class="px-3 py-1.5 bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 rounded-lg text-xs outline-none focus:border-[#1845D4] sm:w-64"
        />
      </div>

      <div v-if="isLoading" class="py-14 text-center text-[#8888A8] text-[11px] font-bold uppercase tracking-widest">
        Loading records...
      </div>
      <div v-else-if="filteredList.length === 0" class="py-14 text-center text-[#8888A8] text-[11px] font-bold uppercase tracking-widest px-6">
        {{ isStudent ? 'No attendance records yet — join a class to start one' : 'No class registers yet' }}
      </div>

      <!-- Students: what they attended, never a doorway back into the room. -->
      <div v-else-if="isStudent" class="overflow-x-auto">
        <table class="w-full text-left border-collapse min-w-[760px]">
          <thead class="bg-[#F5F6FA] dark:bg-slate-800/50 border-b border-[#DDE0F0] dark:border-slate-800">
            <tr class="text-[10px] font-bold text-[#8888A8] uppercase tracking-[0.1em]">
              <th class="px-6 py-3.5">Course</th>
              <th class="px-6 py-3.5">Lecture</th>
              <th class="px-6 py-3.5">Date</th>
              <th class="px-6 py-3.5">Time in class</th>
              <th class="px-6 py-3.5">Checks</th>
              <th class="px-6 py-3.5">Status</th>
              <th class="px-6 py-3.5 text-right">Details</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-[#DDE0F0] dark:divide-slate-800">
            <tr
              v-for="item in filteredList"
              :key="item.id || item.sessionId"
              class="text-xs hover:bg-[#F8F9FC] dark:hover:bg-slate-800/40 transition-colors"
            >
              <td class="px-6 py-4 font-bold text-[#1845D4]">{{ item.course || '—' }}</td>
              <td class="px-6 py-4 font-semibold text-[#0D0D1A] dark:text-white">
                {{ item.title }}
                <span v-if="item.isDeleted" class="ml-1.5 text-[9px] font-bold uppercase tracking-wider text-[#8888A8]">archived</span>
              </td>
              <td class="px-6 py-4 text-[#444460] dark:text-slate-400">{{ formatDate(item.createdAt) }}</td>
              <td class="px-6 py-4 font-bold text-[#0D0D1A] dark:text-white tabular-nums">{{ formatSpan(item.stayMinutes) }}</td>
              <td class="px-6 py-4 font-mono text-[11px] text-[#8888A8]">
                {{ item.verificationsCompleted }}/{{ item.verificationsSent }}
              </td>
              <td class="px-6 py-4">
                <span
                  :class="[
                    'px-2 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider',
                    item.present
                      ? 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600'
                      : 'bg-amber-50 dark:bg-amber-900/20 text-amber-600'
                  ]"
                >
                  {{ item.present ? 'Present' : 'Short' }}
                </span>
              </td>
              <td class="px-6 py-4 text-right">
                <button
                  @click="openStudentRecord(item)"
                  class="px-3 py-1.5 bg-[#F5F6FA] dark:bg-slate-800 text-[#0D0D1A] dark:text-slate-200 text-[11px] font-bold rounded-lg hover:bg-[#E8EEFF] hover:text-[#1845D4] transition-colors cursor-pointer"
                >
                  Details
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Hosts: one row per class, opening onto the register. -->
      <div v-else class="overflow-x-auto">
        <table class="w-full text-left border-collapse min-w-[760px]">
          <thead class="bg-[#F5F6FA] dark:bg-slate-800/50 border-b border-[#DDE0F0] dark:border-slate-800">
            <tr class="text-[10px] font-bold text-[#8888A8] uppercase tracking-[0.1em]">
              <th class="px-6 py-3.5">Course</th>
              <th class="px-6 py-3.5">Lecture</th>
              <th class="px-6 py-3.5">Date</th>
              <th class="px-6 py-3.5">Code</th>
              <th class="px-6 py-3.5">Present</th>
              <th class="px-6 py-3.5">Status</th>
              <th class="px-6 py-3.5 text-right">Register</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-[#DDE0F0] dark:divide-slate-800">
            <tr
              v-for="item in filteredList"
              :key="item.id"
              class="text-xs hover:bg-[#F8F9FC] dark:hover:bg-slate-800/40 transition-colors"
            >
              <td class="px-6 py-4 font-bold text-[#1845D4]">{{ item.course || '—' }}</td>
              <td class="px-6 py-4 font-semibold text-[#0D0D1A] dark:text-white">
                {{ item.title }}
                <span v-if="item.isDeleted" class="ml-1.5 text-[9px] font-bold uppercase tracking-wider text-[#8888A8]">deleted</span>
              </td>
              <td class="px-6 py-4 text-[#444460] dark:text-slate-400">{{ formatDate(item.startedAt || item.createdAt) }}</td>
              <td class="px-6 py-4 font-mono text-[11px] text-[#8888A8]">{{ item.meetingCode || '—' }}</td>
              <td class="px-6 py-4">
                <span class="px-2 py-1 rounded-full text-[10px] font-bold bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600">
                  {{ item.presentCount ?? 0 }} / {{ item.attendeeCount ?? 0 }}
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
              <td class="px-6 py-4 text-right">
                <button
                  @click="openRoster(item)"
                  :disabled="isLoadingRoster"
                  class="px-3 py-1.5 bg-[#F5F6FA] dark:bg-slate-800 text-[#0D0D1A] dark:text-slate-200 text-[11px] font-bold rounded-lg hover:bg-[#E8EEFF] hover:text-[#1845D4] transition-colors cursor-pointer disabled:opacity-60"
                >
                  View register
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Student: class details. Deliberately data only -- there is no button
         back into a finished classroom. -->
    <div
      v-if="detailRecord"
      class="fixed inset-0 z-[700] flex items-center justify-center p-4 animate-fade-in"
      @click.self="detailRecord = null"
    >
      <div class="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" />
      <div class="relative w-full max-w-lg bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-2xl shadow-2xl overflow-hidden">
        <div class="px-6 py-5 border-b border-[#DDE0F0] dark:border-slate-800 flex items-start justify-between gap-4">
          <div class="min-w-0">
            <p class="text-[10px] font-black uppercase tracking-[0.2em] text-[#1845D4]">{{ detailRecord.course || 'Class' }}</p>
            <h2 class="text-lg font-bold text-[#0D0D1A] dark:text-white leading-tight mt-1 truncate">{{ detailRecord.title }}</h2>
            <p class="text-[11px] text-[#8888A8] mt-1">Hosted by {{ detailRecord.lecturerName || 'your host' }}</p>
          </div>
          <button
            @click="detailRecord = null"
            class="p-2 text-slate-500 hover:text-slate-900 dark:hover:text-white bg-slate-100 dark:bg-slate-800 rounded-xl cursor-pointer"
            aria-label="Close"
          >
            <i class="fas fa-times"></i>
          </button>
        </div>

        <div class="p-6 space-y-4">
          <div class="grid grid-cols-2 gap-3">
            <div class="rounded-xl bg-[#F5F6FA] dark:bg-slate-800/60 p-3.5">
              <p class="text-[9px] font-black uppercase tracking-[0.15em] text-[#8888A8]">Date</p>
              <p class="text-[13px] font-bold text-[#0D0D1A] dark:text-white mt-1">{{ formatDate(detailRecord.startedAt || detailRecord.createdAt) }}</p>
            </div>
            <div class="rounded-xl bg-[#F5F6FA] dark:bg-slate-800/60 p-3.5">
              <p class="text-[9px] font-black uppercase tracking-[0.15em] text-[#8888A8]">Class ran</p>
              <p class="text-[13px] font-bold text-[#0D0D1A] dark:text-white mt-1">{{ formatSpan(detailRecord.actualDurationMinutes) }}</p>
            </div>
            <div class="rounded-xl bg-[#F5F6FA] dark:bg-slate-800/60 p-3.5">
              <p class="text-[9px] font-black uppercase tracking-[0.15em] text-[#8888A8]">You joined</p>
              <p class="text-[13px] font-bold text-[#0D0D1A] dark:text-white mt-1">{{ formatClock(detailRecord.joinedAt) }}</p>
            </div>
            <div class="rounded-xl bg-[#F5F6FA] dark:bg-slate-800/60 p-3.5">
              <p class="text-[9px] font-black uppercase tracking-[0.15em] text-[#8888A8]">You left</p>
              <p class="text-[13px] font-bold text-[#0D0D1A] dark:text-white mt-1">{{ detailRecord.leftAt ? formatClock(detailRecord.leftAt) : 'At the end' }}</p>
            </div>
          </div>

          <div class="rounded-xl border border-[#DDE0F0] dark:border-slate-800 p-4 flex items-center justify-between">
            <div>
              <p class="text-[9px] font-black uppercase tracking-[0.15em] text-[#8888A8]">Time in class</p>
              <p class="text-xl font-bold text-[#0D0D1A] dark:text-white mt-0.5">{{ formatSpan(detailRecord.stayMinutes) }}</p>
            </div>
            <div class="text-right">
              <p class="text-[9px] font-black uppercase tracking-[0.15em] text-[#8888A8]">Presence checks</p>
              <p class="text-xl font-bold text-[#0D0D1A] dark:text-white mt-0.5">
                {{ detailRecord.verificationsCompleted }}<span class="text-[#8888A8] text-sm"> / {{ detailRecord.verificationsSent }}</span>
              </p>
            </div>
          </div>

          <p class="text-[11px] text-[#8888A8] leading-relaxed">
            This is a record of a finished class. It is kept on your account even if the class is removed
            from your dashboard, and it cannot be re-entered.
          </p>
        </div>
      </div>
    </div>

    <!-- Host: the register itself. -->
    <div
      v-if="rosterSession"
      class="fixed inset-0 z-[700] flex items-center justify-center p-4 animate-fade-in"
      @click.self="closeRoster"
    >
      <div class="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" />
      <div class="relative w-full max-w-3xl bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[88vh]">
        <div class="px-6 py-5 border-b border-[#DDE0F0] dark:border-slate-800 flex items-start justify-between gap-4">
          <div class="min-w-0">
            <p class="text-[10px] font-black uppercase tracking-[0.2em] text-[#1845D4]">{{ rosterSession.course || 'Class register' }}</p>
            <h2 class="text-lg font-bold text-[#0D0D1A] dark:text-white leading-tight mt-1 truncate">{{ rosterSession.title }}</h2>
            <p class="text-[11px] text-[#8888A8] mt-1">
              {{ formatDate(rosterSession.startedAt || rosterSession.createdAt) }}
              &bull; {{ roster?.summary?.present ?? 0 }} of {{ roster?.summary?.total ?? 0 }} present
            </p>
          </div>
          <div class="flex items-center gap-2">
            <button
              @click="downloadRoster('csv')"
              :disabled="isExporting"
              class="px-3 py-2 bg-[#F5F6FA] dark:bg-slate-800 text-[#0D0D1A] dark:text-slate-200 text-[11px] font-bold rounded-lg hover:bg-[#E8EEFF] hover:text-[#1845D4] transition-colors cursor-pointer disabled:opacity-60"
              title="Download this register as CSV"
            >
              <i class="fas fa-file-csv mr-1"></i> CSV
            </button>
            <button
              @click="downloadRoster('xlsx')"
              :disabled="isExporting"
              class="px-3 py-2 bg-[#F5F6FA] dark:bg-slate-800 text-[#0D0D1A] dark:text-slate-200 text-[11px] font-bold rounded-lg hover:bg-[#E8EEFF] hover:text-[#1845D4] transition-colors cursor-pointer disabled:opacity-60"
              title="Download this register as an Excel workbook"
            >
              <i class="fas fa-file-excel mr-1"></i> Excel
            </button>
            <button
              @click="closeRoster"
              class="p-2 text-slate-500 hover:text-slate-900 dark:hover:text-white bg-slate-100 dark:bg-slate-800 rounded-xl cursor-pointer"
              aria-label="Close"
            >
              <i class="fas fa-times"></i>
            </button>
          </div>
        </div>

        <div class="flex-1 overflow-y-auto">
          <div v-if="isLoadingRoster" class="py-16 text-center text-[#8888A8] text-[11px] font-bold uppercase tracking-widest">
            Loading register...
          </div>
          <div v-else-if="!roster?.records?.length" class="py-16 text-center text-[#8888A8] text-[11px] font-bold uppercase tracking-widest">
            Nobody joined this class
          </div>
          <table v-else class="w-full text-left border-collapse">
            <thead class="sticky top-0 bg-[#F5F6FA] dark:bg-slate-800 border-b border-[#DDE0F0] dark:border-slate-800">
              <tr class="text-[10px] font-bold text-[#8888A8] uppercase tracking-[0.1em]">
                <th class="px-5 py-3">Student</th>
                <th class="px-4 py-3">Joined</th>
                <th class="px-4 py-3">Left</th>
                <th class="px-4 py-3">Time</th>
                <th class="px-4 py-3">Checks</th>
                <th class="px-4 py-3">Status</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-[#DDE0F0] dark:divide-slate-800">
              <tr v-for="r in roster.records" :key="r.studentId" class="text-xs">
                <td class="px-5 py-3.5">
                  <p class="font-bold text-[#0D0D1A] dark:text-white">{{ r.studentName || 'Student' }}</p>
                  <p class="text-[10px] text-[#8888A8] font-mono">{{ r.indexNumber || '—' }}</p>
                </td>
                <td class="px-4 py-3.5 text-[#444460] dark:text-slate-400 tabular-nums">{{ formatClock(r.joinedAt) }}</td>
                <td class="px-4 py-3.5 text-[#444460] dark:text-slate-400 tabular-nums">{{ r.leftAt ? formatClock(r.leftAt) : '—' }}</td>
                <td class="px-4 py-3.5 font-bold text-[#0D0D1A] dark:text-white tabular-nums">{{ formatSpan(r.stayMinutes) }}</td>
                <td class="px-4 py-3.5 font-mono text-[11px] text-[#8888A8]">
                  {{ r.verificationsCompleted }}/{{ r.verificationsSent }}
                </td>
                <td class="px-4 py-3.5">
                  <span
                    :class="[
                      'px-2 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider',
                      r.present ? 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600' : 'bg-amber-50 dark:bg-amber-900/20 text-amber-600'
                    ]"
                  >
                    {{ r.present ? 'Present' : 'Short' }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="px-6 py-3 border-t border-[#DDE0F0] dark:border-slate-800 text-[10px] text-[#8888A8]">
          "Checks" is answers out of checks sent. A student counts as present when they answered at least
          half of the checks in the class, or when no check was ever sent.
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { api, apiClient } from '../api/client';
import { useAuthStore } from '../stores/auth';
import { toast } from '../stores/toast';

const authStore = useAuthStore();

const isStudent = computed(() => authStore.user?.role === 'student');

// Two different questions, two different sources.
//
// A lecturer wants the class-wide picture (how many of this room verified), and
// reads it from the class list. A student wants their own record, which only
// exists per student: it comes from /attendance/mine/. Reading the class list
// for a student used to be the only option, and the number it showed --
// the session's participantCount -- was never written by anything, so every
// student's attendance tab said zero however many checks they had answered.
const rows = ref<any[]>([]);
const sessions = ref<any[]>([]);
const isSyncing = ref(false);
const isLoading = ref(false);
const searchQuery = ref('');

const detailRecord = ref<any | null>(null);
const rosterSession = ref<any | null>(null);
const roster = ref<any | null>(null);
const isLoadingRoster = ref(false);
const isExporting = ref(false);

const totalVerified = computed(() => {
  if (isStudent.value) return rows.value.filter((r) => r.present).length;
  return sessions.value.reduce((sum, s) => sum + (Number(s.presentCount) || 0), 0);
});

const totalStayMinutes = computed(() =>
  rows.value.reduce((sum, r) => sum + (Number(r.stayMinutes) || 0), 0)
);

const totalStudents = computed(() =>
  sessions.value.reduce((sum, s) => sum + (Number(s.attendeeCount) || 0), 0)
);

const filteredList = computed(() => {
  const q = searchQuery.value.trim().toLowerCase();
  if (!q) return rows.value;
  return rows.value.filter(
    (s) =>
      (s.course && s.course.toLowerCase().includes(q)) ||
      (s.title && s.title.toLowerCase().includes(q))
  );
});

function formatDate(value?: string | null) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

function formatClock(value?: string | null) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
}

/** Minutes -> "1h 05m" / "12m", and a dash when the number is unknown. */
function formatSpan(minutes?: number | null) {
  if (minutes === null || minutes === undefined || Number.isNaN(Number(minutes))) return '—';
  const total = Math.max(0, Math.round(Number(minutes)));
  if (total < 60) return `${total}m`;
  const hours = Math.floor(total / 60);
  const rest = total % 60;
  return rest ? `${hours}h ${String(rest).padStart(2, '0')}m` : `${hours}h`;
}

function openStudentRecord(record: any) {
  detailRecord.value = record;
}

async function openRoster(session: any) {
  rosterSession.value = session;
  roster.value = null;
  isLoadingRoster.value = true;
  try {
    const payload: any = await api.getDocument<any>(
      `/api/v1/attendance/sessions/${session.id}/`
    );
    roster.value = payload ?? { session, summary: {}, records: [] };
  } catch (err: any) {
    toast.error('Could not load the register', err?.message || 'Please try again.');
    rosterSession.value = null;
  } finally {
    isLoadingRoster.value = false;
  }
}

function closeRoster() {
  rosterSession.value = null;
  roster.value = null;
}

async function downloadRoster(format: 'csv' | 'xlsx') {
  if (!rosterSession.value) return;
  isExporting.value = true;
  try {
    const res = await apiClient.get(
      `/api/v1/attendance/sessions/${rosterSession.value.id}/export`,
      { params: { format }, responseType: 'blob' }
    );
    const disposition = String(res.headers['content-disposition'] || '');
    const named = /filename="?([^";]+)"?/i.exec(disposition);
    const fallback = `attendance-${rosterSession.value.id}.${format}`;
    const filename = named?.[1] || fallback;

    const url = URL.createObjectURL(res.data as Blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    // Give the browser a beat to start the download before dropping the blob.
    window.setTimeout(() => URL.revokeObjectURL(url), 4000);
    toast.success(`Register downloaded as ${format.toUpperCase()}`);
  } catch (err: any) {
    toast.error('Export failed', err?.message || 'Could not build that file.');
  } finally {
    isExporting.value = false;
  }
}

async function loadData() {
  isLoading.value = true;
  try {
    if (isStudent.value) {
      const res = await api.getDocuments<any>('/api/v1/attendance/mine/');
      // A class still running is not a record yet: it appears here once it
      // ends, with the time this student actually spent in it. Showing live
      // rooms on this page was what made it look like a second class list.
      rows.value = (res.documents || [])
        .filter((r: any) => (r.status || '').toLowerCase() !== 'active')
        .map((r: any) => ({ ...r, key: r.id }));
    } else {
      // includeDeleted: removing a class from the dashboard must not remove its
      // register. Scoped to the classes this person runs -- the institution-wide
      // timetable is the dashboard's business, not this page's.
      const res = await api.getDocuments<any>(
        '/api/v1/classrooms/sessions/',
        { includeDeleted: true }
      );
      const all = res.documents || [];
      const mine = authStore.user?.role === 'admin'
        ? all
        : all.filter((s: any) => s.hostId === authStore.user?.id);
      sessions.value = mine;
      rows.value = mine.map((s: any) => ({ ...s, key: s.id }));
    }
  } catch (err: any) {
    toast.error('Could not load attendance', err?.message || 'Please try again.');
  } finally {
    isLoading.value = false;
  }
}

async function refreshData() {
  isSyncing.value = true;
  try {
    await loadData();
  } finally {
    isSyncing.value = false;
  }
}

onMounted(loadData);
</script>
