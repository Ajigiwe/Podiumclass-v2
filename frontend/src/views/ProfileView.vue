<template>
  <div class="flex-1 p-6 lg:p-10 max-w-6xl mx-auto w-full space-y-10 animate-fade-in">
    <h1 class="text-2xl font-bold text-[#0D0D1A] dark:text-white tracking-tight">Account Settings</h1>

    <!-- First-time setup prompt -->
    <div
      v-if="isFirstTimeSetup"
      class="rounded-xl border border-[#1845D4]/30 bg-[#E8EEFF] dark:bg-blue-900/20 px-5 py-4 flex items-start gap-3"
    >
      <i class="fas fa-circle-info text-[#1845D4] mt-0.5"></i>
      <div>
        <p class="text-[13px] font-bold text-[#0D0D1A] dark:text-white">Finish setting up your profile</p>
        <p class="text-[12px] text-[#444460] dark:text-slate-400 mt-0.5">
          Add your index number so your lecturers can match you to attendance records.
        </p>
      </div>
    </div>

    <div class="grid grid-cols-1 md:grid-cols-3 gap-10">
      <!-- Left Column: Profile Card -->
      <div class="space-y-6">
        <div class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl p-8 sm:p-10 text-center space-y-8 shadow-sm">
          <div class="relative inline-block group">
            <div
              v-if="authStore.user?.photoUrl"
              class="w-24 h-24 rounded-full overflow-hidden shadow-xl shadow-blue-600/10 mx-auto border-2 border-[#1845D4]"
            >
              <img :src="authStore.user.photoUrl" class="w-full h-full object-cover" />
            </div>
            <div
              v-else
              class="w-24 h-24 bg-[#1845D4] rounded-full flex items-center justify-center text-white text-4xl font-bold overflow-hidden shadow-xl shadow-blue-600/10 mx-auto"
            >
              {{ (authStore.user?.fullName || 'U').charAt(0) }}
            </div>
            <label
              class="absolute bottom-0 right-0 bg-white dark:bg-slate-950 border border-[#DDE0F0] dark:border-slate-800 text-[#444460] dark:text-slate-400 w-8 h-8 flex items-center justify-center rounded-full shadow-lg cursor-pointer hover:border-[#1845D4] hover:text-[#1845D4] transition-all"
            >
              <i class="fas fa-camera text-xs"></i>
              <input type="file" class="hidden" accept="image/*" />
            </label>
          </div>

          <div>
            <h2 class="text-xl font-bold text-[#0D0D1A] dark:text-white tracking-tight leading-none mb-3">
              {{ authStore.user?.fullName || 'Podium User' }}
            </h2>
            <div class="inline-block text-[9px] font-black text-[#1845D4] dark:text-blue-400 uppercase tracking-[0.2em] bg-[#E8EEFF] dark:bg-blue-900/20 px-4 py-1.5 rounded-full">
              {{ authStore.user?.role || 'Student' }}
            </div>
            <p class="text-[13px] text-[#8888A8] font-medium mt-3 font-mono">
              {{ authStore.user?.email || '—' }}
            </p>
          </div>
        </div>

        <!-- Verification Badge Card -->
        <div class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl p-6 sm:p-8 space-y-4 shadow-sm">
          <div class="flex items-center justify-between">
            <h3 class="text-[10px] font-black text-[#0D0D1A] dark:text-slate-100 uppercase tracking-[0.2em]">Institutional Status</h3>
            <div
              :class="[
                'text-[9px] font-bold uppercase tracking-widest flex items-center gap-1',
                authStore.user?.isVerified
                  ? 'text-emerald-600 dark:text-emerald-400'
                  : 'text-[#8888A8]'
              ]"
            >
              <i :class="['fas', authStore.user?.isVerified ? 'fa-check-circle' : 'fa-clock']"></i>
              {{ authStore.user?.isVerified ? 'Verified' : 'Pending' }}
            </div>
          </div>
          <p class="text-[12px] text-[#444460] dark:text-slate-400 font-medium leading-relaxed">
            Verified institutional accounts can host live lectures, manage attendance registries, and access recordings.
          </p>
        </div>
      </div>

      <!-- Right Column: Settings Tabs -->
      <div class="md:col-span-2 space-y-8">
        <div class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl overflow-hidden shadow-sm">
          <div class="px-8 py-6 border-b border-[#DDE0F0] dark:border-slate-800">
            <nav class="flex gap-8">
              <button
                @click="activeTab = 'personal'"
                :class="[
                  'text-[11px] font-black uppercase tracking-[0.2em] transition-all py-2 border-b-2 cursor-pointer',
                  activeTab === 'personal' ? 'border-[#1845D4] text-[#1845D4]' : 'border-transparent text-[#8888A8] hover:text-[#0D0D1A] dark:hover:text-white'
                ]"
              >
                Profile Details
              </button>
              <button
                @click="activeTab = 'security'"
                :class="[
                  'text-[11px] font-black uppercase tracking-[0.2em] transition-all py-2 border-b-2 cursor-pointer',
                  activeTab === 'security' ? 'border-[#1845D4] text-[#1845D4]' : 'border-transparent text-[#8888A8] hover:text-[#0D0D1A] dark:hover:text-white'
                ]"
              >
                Security & OAuth
              </button>
            </nav>
          </div>

          <div class="p-8 sm:p-10">
            <!-- Personal Info Tab -->
            <form v-if="activeTab === 'personal'" @submit.prevent="updateProfile" class="space-y-6">
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-6">
                <div class="space-y-1.5">
                  <label class="text-[13px] font-bold text-[#0D0D1A] dark:text-slate-100">Full Name</label>
                  <input
                    v-model="profileForm.fullName"
                    type="text"
                    class="w-full px-4 py-2.5 bg-[#F5F6FA] dark:bg-slate-950 border border-[#DDE0F0] dark:border-slate-800 rounded-md outline-none text-[14px] font-medium focus:border-[#1845D4] transition-all"
                  />
                </div>
                <div class="space-y-1.5">
                  <label class="text-[13px] font-bold text-[#0D0D1A] dark:text-slate-100">Primary Email</label>
                  <input
                    :value="authStore.user?.email"
                    type="email"
                    disabled
                    class="w-full px-4 py-2.5 bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-md outline-none text-[14px] font-medium opacity-60 cursor-not-allowed font-mono"
                  />
                </div>
              </div>

              <div class="grid grid-cols-1 sm:grid-cols-2 gap-6">
                <div class="space-y-1.5">
                  <label class="text-[13px] font-bold text-[#0D0D1A] dark:text-slate-100">Index Number</label>
                  <input
                    v-model="profileForm.indexNumber"
                    type="text"
                    placeholder="e.g. 10982341"
                    class="w-full px-4 py-2.5 bg-[#F5F6FA] dark:bg-slate-950 border border-[#DDE0F0] dark:border-slate-800 rounded-md outline-none text-[14px] font-medium focus:border-[#1845D4] transition-all"
                  />
                </div>
                <div class="space-y-1.5">
                  <label class="text-[13px] font-bold text-[#0D0D1A] dark:text-slate-100">University / Campus</label>
                  <input
                    v-model="profileForm.university"
                    type="text"
                    placeholder="e.g. University of Ghana"
                    class="w-full px-4 py-2.5 bg-[#F5F6FA] dark:bg-slate-950 border border-[#DDE0F0] dark:border-slate-800 rounded-md outline-none text-[14px] font-medium focus:border-[#1845D4] transition-all"
                  />
                </div>
              </div>

              <div class="space-y-1.5">
                <label class="text-[13px] font-bold text-[#0D0D1A] dark:text-slate-100">Professional Bio</label>
                <textarea
                  v-model="profileForm.bio"
                  rows="4"
                  class="w-full px-4 py-3 bg-[#F5F6FA] dark:bg-slate-950 border border-[#DDE0F0] dark:border-slate-800 rounded-md outline-none text-[14px] font-medium focus:border-[#1845D4] transition-all resize-none"
                  placeholder="Tell us about your academic department or role..."
                ></textarea>
              </div>

              <div class="flex items-center justify-between pt-4 border-t border-[#DDE0F0] dark:border-slate-800">
                <span v-if="saveSuccess" class="text-xs font-bold text-emerald-600 dark:text-emerald-400">
                  <i class="fas fa-check mr-1"></i> Changes saved successfully
                </span>
                <span v-else-if="saveError" class="text-xs font-bold text-red-600 dark:text-red-400">
                  <i class="fas fa-circle-exclamation mr-1"></i> {{ saveError }}
                </span>
                <span v-else></span>
                <button
                  type="submit"
                  :disabled="isSaving"
                  class="px-8 py-2.5 bg-[#1845D4] text-white text-[10px] font-black uppercase tracking-[0.2em] rounded-md hover:bg-[#0F2FA8] transition-all active:scale-95 shadow-lg shadow-blue-600/10 cursor-pointer"
                >
                  {{ isSaving ? 'Saving...' : (isFirstTimeSetup ? 'Complete Setup' : 'Update Profile') }}
                </button>
              </div>
            </form>

            <!-- Security Tab -->
            <div v-if="activeTab === 'security'" class="space-y-8 animate-fade-in">
              <div class="space-y-6 divide-y divide-[#DDE0F0] dark:divide-slate-800">
                <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-6">
                  <div class="flex items-center gap-4">
                    <div class="w-12 h-12 bg-blue-50 dark:bg-blue-900/20 text-[#1845D4] rounded-xl flex items-center justify-center text-lg flex-shrink-0">
                      <i class="fab fa-google"></i>
                    </div>
                    <div>
                      <p class="text-[14px] font-bold text-[#0D0D1A] dark:text-white">Google Single Sign-On</p>
                      <p class="text-[12px] text-[#8888A8] font-mono mt-0.5">{{ authStore.user?.email }}</p>
                    </div>
                  </div>
                  <span class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600">
                    <i class="fas fa-check-circle text-[9px]"></i> Connected & Verified
                  </span>
                </div>

                <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pt-6">
                  <div class="flex items-center gap-4">
                    <div class="w-12 h-12 bg-purple-50 dark:bg-purple-900/20 text-purple-600 rounded-xl flex items-center justify-center text-lg flex-shrink-0">
                      <i class="fas fa-shield-alt"></i>
                    </div>
                    <div>
                      <p class="text-[14px] font-bold text-[#0D0D1A] dark:text-white">Session Security Token</p>
                      <p class="text-[12px] text-[#8888A8] font-medium mt-0.5">Strict HTTP token authorization active</p>
                    </div>
                  </div>
                  <button
                    type="button"
                    @click="authStore.logout"
                    class="px-4 py-2 bg-red-50 dark:bg-red-900/20 text-red-600 text-xs font-bold rounded-lg hover:bg-red-100 transition-colors"
                  >
                    Revoke & Sign Out
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore, type User } from '../stores/auth';
import { api } from '../api/client';

const authStore = useAuthStore();
const route = useRoute();
const activeTab = ref<'personal' | 'security'>('personal');
const isSaving = ref(false);
const saveSuccess = ref(false);
const saveError = ref<string | null>(null);

// First-time setup: Google supplies only a name and email, so a student must
// add an index number before the rest of the app is reachable.
const isFirstTimeSetup = computed(
  () => route.query.setup === '1' || (authStore.user?.role === 'student' && !authStore.user?.indexNumber)
);

const profileForm = ref({
  fullName: '',
  indexNumber: '',
  university: '',
  bio: '',
});

function initForm() {
  if (!authStore.user) return;
  profileForm.value.fullName = authStore.user.fullName || '';
  profileForm.value.indexNumber = authStore.user.indexNumber || '';
  profileForm.value.university = authStore.user.university || '';
  profileForm.value.bio = authStore.user.bio || '';
}

async function updateProfile() {
  isSaving.value = true;
  saveSuccess.value = false;
  saveError.value = null;
  try {
    // Persist to the server. This previously mutated the local store only, so
    // every edit was silently discarded on the next page load.
    const updated = await api.patchDocument<User>('/api/v1/accounts/me/', {
      fullName: profileForm.value.fullName,
      indexNumber: profileForm.value.indexNumber,
      university: profileForm.value.university,
      bio: profileForm.value.bio,
    });
    authStore.user = updated;
    initForm();
    saveSuccess.value = true;
    setTimeout(() => { saveSuccess.value = false; }, 3000);
  } catch (err: any) {
    saveError.value = err?.message || 'Could not save your profile. Please try again.';
  } finally {
    isSaving.value = false;
  }
}

onMounted(() => {
  initForm();
});
</script>
