<template>
  <div class="flex-1 px-5 py-6 lg:px-10 lg:py-8 max-w-6xl mx-auto w-full space-y-8 animate-fade-in">
    <!-- Header Row -->
    <div class="flex flex-col sm:flex-row sm:items-end justify-between gap-4">
      <div>
        <h1 class="text-2xl lg:text-3xl font-bold text-[#0D0D1A] dark:text-white tracking-tight">
          Good <span class="text-[#1845D4]">{{ greetingTime }}</span>,
          <span>{{ authStore.user?.fullName || 'there' }}</span>
        </h1>
        <p class="text-sm text-[#8888A8] font-medium mt-1">{{ currentDateStr }}</p>
      </div>

      <button
        @click="showCreateModal = true"
        class="px-5 py-2.5 bg-[#1845D4] text-white text-[13px] font-bold rounded-lg hover:bg-[#0F2FA8] transition-all flex items-center gap-2 shadow-lg shadow-blue-600/10 cursor-pointer"
      >
        <i class="fas fa-plus text-xs"></i> New class
      </button>
    </div>

    <!-- Quick Join (compact, above stats) -->
    <div class="bg-white dark:bg-slate-900 rounded-xl px-5 py-4 border border-[#DDE0F0] dark:border-slate-800 flex flex-col sm:flex-row sm:items-center gap-3">
      <p class="text-[11px] font-bold text-[#8888A8] uppercase tracking-widest whitespace-nowrap">Join a class</p>
      <form @submit.prevent="joinByCode" class="flex gap-2 flex-1">
        <input
          v-model="quickMeetingCode"
          type="text"
          placeholder="Enter meeting code..."
          class="flex-1 min-w-0 px-4 py-2.5 bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 rounded-lg text-[13px] font-medium outline-none focus:border-[#1845D4] focus:ring-2 focus:ring-blue-600/10 transition-all"
          autocomplete="off"
        />
        <button
          type="submit"
          class="px-5 py-2.5 bg-[#1845D4] text-white rounded-lg text-[13px] font-bold hover:bg-[#0F2FA8] transition-all shadow-md shadow-blue-600/10 shrink-0 cursor-pointer"
        >
          Join
        </button>
      </form>
    </div>

    <!-- Live Now Banner (if sessions are active) -->
    <div v-if="liveSessions.length > 0">
      <div
        v-for="session in liveSessions.slice(0, 1)"
        :key="session.id"
        class="flex items-center gap-3 bg-white dark:bg-slate-900 border border-red-200 dark:border-red-500/25 border-l-4 border-l-red-500 rounded-xl px-4 py-3 shadow-sm"
      >
        <span class="relative flex w-2 h-2 shrink-0">
          <span class="absolute inline-flex w-full h-full rounded-full bg-red-400 opacity-60 animate-ping"></span>
          <span class="relative inline-flex w-2 h-2 rounded-full bg-red-500"></span>
        </span>
        <div class="flex-1 min-w-0">
          <p class="text-[9px] font-black text-red-600 dark:text-red-400 uppercase tracking-[0.2em]">Live now</p>
          <p class="text-[13px] font-bold text-[#0D0D1A] dark:text-white truncate leading-snug">
            {{ session.title }} — {{ session.course || 'General' }}
          </p>
        </div>
        <router-link
          :to="`/classroom/${session.id}`"
          class="shrink-0 px-4 py-2 rounded-lg bg-[#1845D4] text-white text-[10px] font-black uppercase tracking-widest hover:bg-[#0F2FA8] transition-all"
        >
          Join
        </router-link>
      </div>
    </div>

    <!-- Stat Cards -->
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-4">
      <!-- Enrolled -->
      <div class="bg-white dark:bg-slate-900 rounded-xl p-5 border border-[#DDE0F0] dark:border-slate-800 stat-glow-blue cursor-pointer card-hover group">
        <div class="flex items-center gap-3 mb-3">
          <div class="w-10 h-10 rounded-lg bg-blue-50 dark:bg-blue-900/20 flex items-center justify-center text-[#1845D4]">
            <i class="fas fa-book-open text-sm"></i>
          </div>
          <span class="text-[11px] font-bold text-[#8888A8] uppercase tracking-widest">Enrolled</span>
        </div>
        <div class="text-3xl font-bold text-[#0D0D1A] dark:text-white tracking-tight tabular-nums">
          {{ sessions.length }}
        </div>
      </div>

      <!-- Hosted -->
      <div class="bg-white dark:bg-slate-900 rounded-xl p-5 border border-[#DDE0F0] dark:border-slate-800 stat-glow-amber cursor-pointer card-hover group">
        <div class="flex items-center gap-3 mb-3">
          <div class="w-10 h-10 rounded-lg bg-amber-50 dark:bg-amber-900/20 flex items-center justify-center text-amber-600">
            <i class="fas fa-chalkboard-teacher text-sm"></i>
          </div>
          <span class="text-[11px] font-bold text-[#8888A8] uppercase tracking-widest">Hosted</span>
        </div>
        <div class="text-3xl font-bold text-[#0D0D1A] dark:text-white tracking-tight tabular-nums">
          {{ hostedCount }}
        </div>
      </div>

      <!-- Wallet Balance -->
      <router-link
        to="/wallet"
        class="bg-white dark:bg-slate-900 rounded-xl p-5 border border-[#DDE0F0] dark:border-slate-800 stat-glow-green cursor-pointer card-hover group block"
      >
        <div class="flex items-center gap-3 mb-3">
          <div class="w-10 h-10 rounded-lg bg-green-50 dark:bg-green-900/20 flex items-center justify-center text-green-600">
            <i class="fas fa-wallet text-sm"></i>
          </div>
          <span class="text-[11px] font-bold text-[#8888A8] uppercase tracking-widest">Wallet</span>
          <span class="ml-auto w-7 h-7 rounded-lg flex items-center justify-center text-[#8888A8] hover:bg-green-50 hover:text-green-600 transition-all">
            <i class="fas fa-sync-alt text-[10px]"></i>
          </span>
        </div>
        <div class="text-2xl font-bold text-[#0D0D1A] dark:text-white tracking-tight tabular-nums">
          {{ authStore.formattedBalance }}
        </div>
        <p class="text-[11px] text-green-600 font-semibold mt-2 opacity-0 group-hover:opacity-100 transition-all">
          Top up <i class="fas fa-arrow-right text-[9px]"></i>
        </p>
      </router-link>

      <!-- Sessions -->
      <div class="bg-white dark:bg-slate-900 rounded-xl p-5 border border-[#DDE0F0] dark:border-slate-800 stat-glow-emerald">
        <div class="flex items-center gap-3 mb-3">
          <div class="w-10 h-10 rounded-lg bg-emerald-50 dark:bg-emerald-900/20 flex items-center justify-center text-emerald-600">
            <i class="fas fa-clipboard-check text-sm"></i>
          </div>
          <span class="text-[11px] font-bold text-[#8888A8] uppercase tracking-widest">Sessions</span>
        </div>
        <div class="text-3xl font-bold text-[#0D0D1A] dark:text-white tracking-tight tabular-nums">
          {{ sessions.length }}
        </div>
      </div>
    </div>

    <!-- Quick Access / Jump Back In -->
    <div v-if="sessions.length > 0" class="space-y-4">
      <div class="flex items-center gap-3">
        <span class="w-2 h-2 bg-red-500 rounded-full live-pulse"></span>
        <h2 class="text-[12px] font-bold text-[#0D0D1A] dark:text-white uppercase tracking-widest">Jump Back In</h2>
        <div class="h-px flex-1 bg-[#DDE0F0] dark:bg-slate-800"></div>
      </div>
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div
          v-for="session in sessions.slice(0, 4)"
          :key="session.id"
          @click="enterClassroom(session)"
          class="group bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-lg p-5 flex items-center justify-between hover:border-[#1845D4] transition-all cursor-pointer"
        >
          <div class="flex items-center gap-4 flex-1 min-w-0">
            <div
              :class="[
                'w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 transition-all group-hover:bg-[#1845D4] group-hover:text-white',
                session.status === 'active' ? 'bg-blue-50 dark:bg-blue-900/20 text-[#1845D4]' : 'bg-[#F5F6FA] dark:bg-slate-800 text-[#8888A8]'
              ]"
            >
              <i :class="['fas', session.status === 'active' ? 'fa-video animate-pulse' : 'fa-graduation-cap', 'text-sm']"></i>
            </div>
            <div class="flex-1 min-w-0">
              <h4 class="text-[14px] font-bold text-[#0D0D1A] dark:text-white truncate group-hover:text-[#1845D4] transition-colors">
                {{ session.title }}
              </h4>
              <p class="text-[10px] font-bold text-[#8888A8] uppercase tracking-widest mt-0.5">
                {{ session.lecturerName || 'Faculty Lecturer' }}
              </p>
            </div>
          </div>
          <div class="ml-4">
            <span
              v-if="session.status === 'active'"
              class="px-2 py-1 bg-[#1845D4] text-white text-[8px] font-black uppercase tracking-[0.2em] rounded shadow-lg shadow-blue-600/20"
            >
              Live
            </span>
            <i v-else class="fas fa-chevron-right text-[#DDE0F0] group-hover:text-[#1845D4] transition-all"></i>
          </div>
        </div>
      </div>
    </div>

    <!-- Action Cards -->
    <div class="grid sm:grid-cols-2 gap-4">
      <div
        @click="showCreateModal = true"
        class="bg-white dark:bg-slate-900 rounded-xl p-6 border border-[#DDE0F0] dark:border-slate-800 card-hover cursor-pointer group"
      >
        <div class="w-11 h-11 bg-[#1845D4] rounded-xl flex items-center justify-center mb-4 text-white shadow-lg shadow-blue-600/20 group-hover:scale-110 transition-transform">
          <i class="fas fa-plus text-lg"></i>
        </div>
        <h3 class="text-base font-bold text-[#0D0D1A] dark:text-white mb-1">Create a class</h3>
        <p class="text-[13px] text-[#8888A8] leading-relaxed">Set up a new classroom session and invite students.</p>
        <span class="inline-block mt-4 text-[13px] font-semibold text-[#1845D4]">
          Get started <i class="fas fa-arrow-right text-[10px] ml-1 group-hover:ml-2 transition-all"></i>
        </span>
      </div>

      <router-link
        to="/attendance"
        class="bg-white dark:bg-slate-900 rounded-xl p-6 border border-[#DDE0F0] dark:border-slate-800 card-hover cursor-pointer group block"
      >
        <div class="w-11 h-11 bg-gradient-to-br from-purple-50 to-blue-50 dark:from-purple-900/20 dark:to-blue-900/20 rounded-xl flex items-center justify-center mb-4 text-purple-600 dark:text-purple-400 group-hover:scale-110 transition-transform">
          <i class="fas fa-clipboard-check text-lg"></i>
        </div>
        <h3 class="text-base font-bold text-[#0D0D1A] dark:text-white mb-1">Biometric Attendance</h3>
        <p class="text-[13px] text-[#8888A8] leading-relaxed">Inspect check-in telemetry and verify institutional records.</p>
        <span class="inline-block mt-4 text-[13px] font-semibold text-purple-600 dark:text-purple-400">
          Inspect Attendance <i class="fas fa-arrow-right text-[10px] ml-1 group-hover:ml-2 transition-all"></i>
        </span>
      </router-link>
    </div>

    <!-- My Classes (inline list) -->
    <div class="space-y-4">
      <div class="flex items-center justify-between">
        <h2 class="text-[12px] font-bold text-[#0D0D1A] dark:text-white uppercase tracking-widest">My Classes</h2>
        <div class="flex gap-1 bg-[#DDE0F0]/50 dark:bg-slate-800 rounded-lg p-0.5">
          <button
            @click="activeRecordTab = 'enrolled'"
            :class="[
              'px-3 py-1.5 rounded-md text-[11px] font-bold transition-all cursor-pointer',
              activeRecordTab === 'enrolled' ? 'text-[#1845D4] bg-white dark:bg-slate-900 shadow-sm' : 'text-[#8888A8] hover:text-[#0D0D1A] dark:hover:text-white'
            ]"
          >
            Enrolled
          </button>
          <button
            @click="activeRecordTab = 'hosted'"
            :class="[
              'px-3 py-1.5 rounded-md text-[11px] font-bold transition-all cursor-pointer',
              activeRecordTab === 'hosted' ? 'text-[#1845D4] bg-white dark:bg-slate-900 shadow-sm' : 'text-[#8888A8] hover:text-[#0D0D1A] dark:hover:text-white'
            ]"
          >
            Hosted
          </button>
        </div>
      </div>

      <div class="space-y-3">
        <div
          v-for="session in filteredSessions"
          :key="session.id"
          @click="enterClassroom(session)"
          class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl px-5 py-4 flex items-center justify-between card-hover cursor-pointer"
        >
          <div class="flex items-center gap-3 min-w-0 flex-1">
            <div
              :class="[
                'w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0',
                session.status === 'active' ? 'bg-blue-50 dark:bg-blue-900/20 text-[#1845D4]' : 'bg-[#F5F6FA] dark:bg-slate-800 text-[#8888A8]'
              ]"
            >
              <i :class="['fas', session.status === 'active' ? 'fa-video' : 'fa-graduation-cap', 'text-sm']"></i>
            </div>
            <div class="min-w-0 flex-1">
              <h4 class="text-[13px] font-semibold text-[#0D0D1A] dark:text-white truncate">
                {{ session.title }}
              </h4>
              <p class="text-[11px] text-[#8888A8] font-medium">
                {{ session.course || 'General' }} • {{ session.lecturerName || 'Faculty' }}
              </p>
            </div>
          </div>

          <div class="flex items-center gap-3 ml-3 flex-shrink-0">
            <span
              v-if="session.status === 'active'"
              class="text-[10px] font-bold text-[#1845D4] bg-blue-50 dark:bg-blue-900/20 px-2 py-1 rounded-full uppercase tracking-widest"
            >
              Live
            </span>
            <span
              v-else
              class="text-[10px] font-bold text-[#8888A8] bg-[#F5F6FA] dark:bg-slate-800 px-2 py-1 rounded-full uppercase tracking-widest"
            >
              Scheduled
            </span>
            <i class="fas fa-chevron-right text-[#DDE0F0] text-xs"></i>
          </div>
        </div>
      </div>
    </div>

    <!-- Modal: New Class -->
    <div
      v-if="showCreateModal"
      class="fixed inset-0 bg-[#0D0D1A]/50 backdrop-blur-sm z-50 flex items-center justify-center p-4"
      @click.self="showCreateModal = false"
    >
      <div class="bg-white dark:bg-slate-900 rounded-2xl max-w-lg w-full p-6 space-y-6 border border-[#DDE0F0] dark:border-slate-800 shadow-2xl">
        <div class="flex items-center justify-between pb-4 border-b border-[#DDE0F0] dark:border-slate-800">
          <h3 class="text-lg font-bold text-[#0D0D1A] dark:text-white">Create a new class</h3>
          <button @click="showCreateModal = false" class="text-[#8888A8] hover:text-[#0D0D1A]">
            <i class="fas fa-times"></i>
          </button>
        </div>

        <form @submit.prevent="createSession" class="space-y-4">
          <div>
            <label class="block text-[11px] font-bold text-[#8888A8] uppercase tracking-wider mb-1">
              Class Title
            </label>
            <input
              v-model="newSession.title"
              required
              class="w-full px-4 py-2.5 bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 rounded-lg text-[13px] outline-none focus:border-[#1845D4]"
              placeholder="e.g. CS302 Advanced Algorithms"
            />
          </div>

          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-[11px] font-bold text-[#8888A8] uppercase tracking-wider mb-1">
                Course Code
              </label>
              <input
                v-model="newSession.course"
                class="w-full px-4 py-2.5 bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 rounded-lg text-[13px] outline-none focus:border-[#1845D4]"
                placeholder="CS302"
              />
            </div>

            <div>
              <label class="block text-[11px] font-bold text-[#8888A8] uppercase tracking-wider mb-1">
                Lecturer Name
              </label>
              <input
                v-model="newSession.lecturerName"
                class="w-full px-4 py-2.5 bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 rounded-lg text-[13px] outline-none focus:border-[#1845D4]"
                placeholder="Dr. Kwame Asante"
              />
            </div>
          </div>

          <!-- Automated Attendance Checker Settings -->
          <div class="p-4 bg-indigo-50/70 dark:bg-indigo-950/30 border border-indigo-100 dark:border-indigo-900/50 rounded-xl space-y-3">
            <div class="flex items-center justify-between">
              <div class="flex items-center gap-2.5">
                <div class="w-7 h-7 rounded-lg bg-indigo-600/15 flex items-center justify-center text-indigo-600 dark:text-indigo-400">
                  <i class="fas fa-shield-alt text-xs"></i>
                </div>
                <div>
                  <h4 class="text-xs font-bold text-[#0D0D1A] dark:text-white">Automated Attendance Checker</h4>
                  <p class="text-[10px] text-slate-500">Auto-triggers verification modals for students</p>
                </div>
              </div>
              <label class="relative inline-flex items-center cursor-pointer">
                <input type="checkbox" v-model="newSession.autoAttendanceEnabled" class="sr-only peer">
                <div class="w-8 h-4 bg-slate-300 dark:bg-slate-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-3 after:w-3 after:transition-all peer-checked:bg-indigo-600"></div>
              </label>
            </div>

            <div v-if="newSession.autoAttendanceEnabled" class="grid grid-cols-2 gap-3 pt-2.5 border-t border-indigo-100 dark:border-indigo-900/40">
              <div>
                <label class="block text-[10px] font-bold text-[#8888A8] uppercase tracking-wider mb-1">
                  Check Frequency
                </label>
                <div class="flex items-center gap-2">
                  <input
                    type="number"
                    min="1"
                    max="20"
                    v-model.number="newSession.verificationCount"
                    class="w-full px-3 py-2 bg-white dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 rounded-lg text-xs font-bold outline-none focus:border-[#1845D4]"
                  />
                  <span class="text-[11px] font-bold text-slate-400">times</span>
                </div>
                <p class="text-[9px] text-slate-400 mt-1">Number of checks during class</p>
              </div>

              <div>
                <label class="block text-[10px] font-bold text-[#8888A8] uppercase tracking-wider mb-1">
                  Modal Timeout
                </label>
                <div class="flex items-center gap-2">
                  <input
                    type="number"
                    min="10"
                    max="180"
                    step="5"
                    v-model.number="newSession.timeLimitSeconds"
                    class="w-full px-3 py-2 bg-white dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 rounded-lg text-xs font-bold outline-none focus:border-[#1845D4]"
                  />
                  <span class="text-[11px] font-bold text-slate-400">sec</span>
                </div>
                <p class="text-[9px] text-slate-400 mt-1">Seconds popup stays up</p>
              </div>
            </div>
          </div>

          <div class="flex justify-end gap-3 pt-4 border-t border-[#DDE0F0] dark:border-slate-800">
            <button
              type="button"
              @click="showCreateModal = false"
              class="px-4 py-2 text-[13px] font-bold text-[#8888A8] hover:text-[#0D0D1A]"
            >
              Cancel
            </button>
            <button
              type="submit"
              class="px-6 py-2.5 bg-[#1845D4] text-white rounded-lg text-[13px] font-bold hover:bg-[#0F2FA8] transition-all cursor-pointer"
              :disabled="isSubmitting"
            >
              {{ isSubmitting ? 'Creating...' : 'Create Class' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api/client';
import { useAuthStore } from '../stores/auth';

const router = useRouter();
const authStore = useAuthStore();

const sessions = ref<any[]>([]);
const quickMeetingCode = ref('');
const activeRecordTab = ref<'enrolled' | 'hosted'>('enrolled');
const showCreateModal = ref(false);
const isSubmitting = ref(false);

const newSession = ref({
  title: '',
  course: '',
  lecturerName: authStore.user?.fullName || '',
  autoAttendanceEnabled: true,
  verificationCount: 3,
  timeLimitSeconds: 30,
});

const liveSessions = computed(() => sessions.value.filter((s) => s.status === 'active'));
// "Hosted" means sessions this user actually owns. The previous expression
// returned a literal 1 for every non-lecturer, which is why the admin dashboard
// always claimed one hosted class even with an empty database.
const hostedCount = computed(
  () => sessions.value.filter((s) => s.hostId && s.hostId === authStore.user?.id).length
);

const filteredSessions = computed(() => sessions.value);

const greetingTime = computed(() => {
  const hour = new Date().getHours();
  if (hour < 12) return 'morning';
  if (hour < 17) return 'afternoon';
  return 'evening';
});

const currentDateStr = computed(() => {
  return new Date().toLocaleDateString('en-US', {
    weekday: 'long',
    month: 'long',
    day: 'numeric',
  });
});

async function loadSessions() {
  try {
    const res = await api.getDocuments<any>('/api/v1/classrooms/sessions/');
    sessions.value = res.documents || [];
  } catch (e) {
    console.error('Failed to load sessions', e);
  }
}

async function createSession() {
  isSubmitting.value = true;
  try {
    const doc = await api.postDocument<any>('/api/v1/classrooms/sessions/', newSession.value);
    showCreateModal.value = false;
    await loadSessions();
    router.push(`/classroom/${doc.id}`);
  } catch (err) {
    alert('Failed to create session');
  } finally {
    isSubmitting.value = false;
  }
}

function enterClassroom(session: any) {
  router.push(`/classroom/${session.id}`);
}

function joinByCode() {
  const code = quickMeetingCode.value.trim();
  if (!code) return;
  const match = sessions.value.find((s) => s.meetingCode?.toLowerCase() === code.toLowerCase());
  if (match) {
    router.push(`/classroom/${match.id}`);
  } else {
    router.push(`/classroom/${code}`);
  }
}

onMounted(loadSessions);
</script>
