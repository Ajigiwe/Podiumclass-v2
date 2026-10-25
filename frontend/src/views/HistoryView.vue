<template>
  <div class="flex-1 p-6 lg:p-10 max-w-4xl mx-auto w-full space-y-8 animate-fade-in">
    <!-- Header -->
    <div class="flex flex-col md:flex-row md:items-end justify-between gap-6 pb-2">
      <div>
        <h1 class="text-3xl font-sans font-bold text-[#0D0D1A] dark:text-slate-100 tracking-tighter">
          History
        </h1>
        <p class="text-[13px] text-[#8888A8] font-medium mt-1">Review your academic records</p>
      </div>
      <div class="flex w-full md:w-auto p-1 bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-lg shadow-sm">
        <button
          @click="activeTab = 'joined'"
          :class="[
            'flex-1 md:flex-none px-4 py-2 text-[10px] font-bold uppercase tracking-[0.15em] rounded transition-all cursor-pointer',
            activeTab === 'joined' ? 'bg-[#1845D4] text-white shadow-sm' : 'text-[#8888A8] hover:text-[#0D0D1A] dark:hover:text-slate-100'
          ]"
        >
          Classes Joined
        </button>
        <button
          @click="activeTab = 'hosted'"
          :class="[
            'flex-1 md:flex-none px-4 py-2 text-[10px] font-bold uppercase tracking-[0.15em] rounded transition-all cursor-pointer',
            activeTab === 'hosted' ? 'bg-[#1845D4] text-white shadow-sm' : 'text-[#8888A8] hover:text-[#0D0D1A] dark:hover:text-slate-100'
          ]"
        >
          Classes Hosted
        </button>
      </div>
    </div>

    <!-- Stat Card -->
    <div class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-lg p-6 shadow-sm inline-block min-w-[240px]">
      <div class="text-[11px] font-bold text-[#8888A8] uppercase tracking-[0.08em] mb-2">Total Records</div>
      <div class="flex items-center justify-between gap-12">
        <div class="text-3xl font-sans font-bold text-[#0D0D1A] dark:text-slate-100 tracking-tight tabular-nums">
          {{ displayList.length }}
        </div>
        <i class="fas fa-history text-[#1845D4] text-xl"></i>
      </div>
    </div>

    <!-- History List -->
    <div class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-lg overflow-hidden shadow-sm">
      <div v-if="isLoading" class="py-16 text-center text-[#8888A8] text-[11px] font-bold uppercase tracking-widest italic">
        Loading records...
      </div>
      <div v-else-if="displayList.length === 0" class="py-16 text-center text-[#8888A8] text-[11px] font-bold uppercase tracking-widest">
        No records found
      </div>
      <div v-else class="divide-y divide-[#DDE0F0] dark:divide-slate-800">
        <div
          v-for="record in displayList"
          :key="record.id"
          class="p-5 flex flex-col sm:flex-row sm:items-center justify-between gap-4 hover:bg-[#F8F9FC] dark:hover:bg-slate-800/50 transition-colors"
        >
          <div class="flex items-start gap-3.5">
            <div class="w-10 h-10 rounded-lg bg-blue-50 dark:bg-blue-900/20 text-[#1845D4] flex items-center justify-center flex-shrink-0 font-bold text-sm">
              <i class="fas fa-chalkboard-teacher"></i>
            </div>
            <div>
              <h3 class="text-sm font-bold text-[#0D0D1A] dark:text-white leading-tight">
                {{ record.title }}
              </h3>
              <p class="text-xs text-[#8888A8] mt-1 font-medium">
                {{ record.course || '—' }} &bull; {{ record.lecturerName || '—' }}
              </p>
              <div class="flex items-center gap-2 mt-2 text-[10px] text-[#8888A8] font-mono">
                <span><i class="far fa-calendar mr-1"></i>{{ formatDate(record.createdAt) }}</span>
                <span>&bull;</span>
                <span><i class="far fa-clock mr-1"></i>{{ formatDuration(record.durationMinutes) }}</span>
              </div>
            </div>
          </div>

          <div class="flex items-center gap-3 self-end sm:self-center">
            <span
              :class="[
                'inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider',
                record.status === 'active'
                  ? 'bg-blue-50 dark:bg-blue-900/20 text-[#1845D4] dark:text-blue-400'
                  : 'bg-[#F5F6FA] dark:bg-slate-800 text-[#8888A8]'
              ]"
            >
              <i :class="['fas', record.status === 'active' ? 'fa-video' : 'fa-clock', 'text-[9px]']"></i>
              {{ record.status || 'scheduled' }}
            </span>
            <router-link
              :to="`/classroom/${record.id}`"
              class="px-3.5 py-1.5 bg-[#F5F6FA] dark:bg-slate-800 text-[#0D0D1A] dark:text-slate-200 text-xs font-semibold rounded-lg hover:bg-[#E8EEFF] hover:text-[#1845D4] transition-colors"
            >
              Review
            </router-link>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { api } from '../api/client';
import { useAuthStore } from '../stores/auth';

const authStore = useAuthStore();
const activeTab = ref<'joined' | 'hosted'>('joined');
const records = ref<any[]>([]);
const isLoading = ref(false);

const displayList = computed(() => {
  if (activeTab.value === 'hosted') {
    return records.value.filter(
      (r) => r.hostId === authStore.user?.id || r.lecturerName === authStore.user?.fullName
    );
  }
  return records.value;
});

function formatDate(dateStr?: string) {
  if (!dateStr) return '—';
  try {
    return new Date(dateStr).toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric'
    });
  } catch {
    return dateStr;
  }
}

function formatDuration(minutes?: number) {
  return minutes ? `${minutes} mins` : '—';
}

async function loadHistory() {
  isLoading.value = true;
  try {
    const res = await api.getDocuments<any>('/api/v1/classrooms/sessions/');
    records.value = res.documents || [];
  } catch (err) {
    console.error('History load error', err);
  } finally {
    isLoading.value = false;
  }
}

onMounted(loadHistory);
</script>
