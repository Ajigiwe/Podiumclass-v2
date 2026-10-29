<template>
  <div class="settings-panel space-y-8">
    <!-- Loading / error states -->
    <div v-if="loading" class="py-16 text-center text-xs text-[#8888A8] font-semibold">
      <i class="fas fa-circle-notch fa-spin mr-2"></i> Loading configuration…
    </div>
    <div v-else-if="loadError" class="p-5 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-900/40 rounded-2xl text-xs font-semibold text-red-600 dark:text-red-400">
      <i class="fas fa-triangle-exclamation mr-2"></i>{{ loadError }}
    </div>

    <template v-else>
      <!-- ============ FEATURE MODULE ============ -->
      <section class="space-y-4">
        <div class="flex items-center justify-between">
          <div>
            <h3 class="text-base font-bold text-[#0D0D1A] dark:text-white">Feature Toggles</h3>
            <p class="text-xs text-[#8888A8] mt-0.5">
              Turn platform capabilities on or off. Changes take effect immediately and persist across the API.
            </p>
          </div>
          <span class="text-[10px] font-black text-[#8888A8] uppercase tracking-widest">
            {{ enabledCount }} / {{ features.length }} enabled
          </span>
        </div>

        <div v-for="group in featureGroups" :key="group.key" class="space-y-3">
          <p class="px-1 text-[10px] font-black text-[#8888A8] uppercase tracking-[0.15em] flex items-center gap-2">
            <i :class="group.icon"></i> {{ group.label }}
          </p>
          <div class="grid lg:grid-cols-2 gap-3">
            <div
              v-for="feat in featuresByGroup[group.key]"
              :key="feat.key"
              class="flex items-start gap-4 p-4 bg-white dark:bg-slate-900 border rounded-2xl transition-colors"
              :class="feat.enabled
                ? 'border-[#1845D4]/30 shadow-sm'
                : 'border-[#DDE0F0] dark:border-slate-800'"
            >
              <div
                class="w-9 h-9 rounded-lg flex items-center justify-center shrink-0"
                :class="feat.enabled ? 'bg-[#1845D4]/10 text-[#1845D4]' : 'bg-[#F5F6FA] dark:bg-slate-800 text-[#8888A8]'"
              >
                <i :class="feat.icon"></i>
              </div>
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2">
                  <p class="text-[13px] font-bold text-[#0D0D1A] dark:text-white">{{ feat.label }}</p>
                  <span
                    v-if="feat.critical"
                    class="px-1.5 py-0.5 rounded text-[8px] font-black uppercase tracking-wider"
                    :class="feat.enabled
                      ? 'bg-amber-50 dark:bg-amber-900/20 text-amber-600'
                      : 'bg-red-50 dark:bg-red-900/20 text-red-600'"
                  >
                    {{ feat.enabled ? 'Core' : 'Off' }}
                  </span>
                </div>
                <p class="text-[11px] text-[#8888A8] mt-1 leading-relaxed">{{ feat.description }}</p>
              </div>

              <!-- Toggle switch -->
              <button
                role="switch"
                :aria-checked="feat.enabled"
                :aria-label="`Toggle ${feat.label}`"
                @click="toggleFeature(feat)"
                :disabled="busyKeys.has(feat.key)"
                class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors shrink-0 cursor-pointer disabled:opacity-50 disabled:cursor-wait"
                :class="feat.enabled ? 'bg-[#1845D4]' : 'bg-slate-200 dark:bg-slate-700'"
              >
                <span
                  class="inline-block h-4 w-4 transform rounded-full bg-white transition-transform shadow"
                  :class="feat.enabled ? 'translate-x-6' : 'translate-x-1'"
                />
              </button>
            </div>
          </div>
        </div>
      </section>

      <!-- ============ CONFIGURATION MODULE ============ -->
      <section class="space-y-5">
        <div>
          <h3 class="text-base font-bold text-[#0D0D1A] dark:text-white">Platform Configuration</h3>
          <p class="text-xs text-[#8888A8] mt-0.5">
            Credentials and limits. Fields marked ENV are set on the server environment and shown here
            for reference. Secrets are never returned to the browser.
          </p>
        </div>

        <div v-for="group in settingGroups" :key="group.key" class="p-5 bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-2xl space-y-4">
          <h4 class="text-sm font-bold text-[#0D0D1A] dark:text-white flex items-center gap-2">
            <i :class="group.icon" class="text-[#1845D4]"></i> {{ group.label }}
          </h4>
          <p class="text-[11px] text-[#8888A8] -mt-2">{{ group.description }}</p>

          <div class="grid sm:grid-cols-2 gap-4">
            <div
              v-for="setting in settingsByGroup[group.key]"
              :key="setting.key"
              class="space-y-1"
            >
              <label class="flex items-center gap-1.5 text-[11px] font-bold text-[#8888A8] uppercase tracking-wider">
                {{ setting.label }}
                <span v-if="setting.secret" class="text-[8px] px-1 py-0.5 rounded bg-amber-50 dark:bg-amber-900/20 text-amber-600 font-black">SECRET</span>
                <span v-if="setting.readOnly" class="text-[8px] px-1 py-0.5 rounded bg-slate-100 dark:bg-slate-800 text-slate-500 font-black">ENV</span>
              </label>

              <!-- Secret field: masked, blank = leave unchanged -->
              <input
                v-if="setting.secret"
                type="password"
                :value="drafts[setting.key]"
                :disabled="setting.readOnly"
                @input="setDraft(setting.key, ($event.target as HTMLInputElement).value)"
                :placeholder="setting.isSet ? '••••••••  (unchanged if blank)' : 'Not set'"
                class="w-full px-3 py-2 bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 rounded-lg text-xs font-mono outline-none focus:border-[#1845D4] disabled:opacity-60 disabled:cursor-not-allowed"
              />

              <!-- Integer field -->
              <input
                v-else-if="setting.type === 'integer'"
                type="number"
                :value="drafts[setting.key]"
                :disabled="setting.readOnly"
                @input="setDraft(setting.key, ($event.target as HTMLInputElement).value)"
                class="w-full px-3 py-2 bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 rounded-lg text-xs font-mono outline-none focus:border-[#1845D4] disabled:opacity-60 disabled:cursor-not-allowed"
              />

              <!-- Default text/email/url field -->
              <input
                v-else
                :type="setting.type === 'email' ? 'email' : setting.type === 'url' ? 'url' : 'text'"
                :value="drafts[setting.key]"
                :disabled="setting.readOnly"
                @input="setDraft(setting.key, ($event.target as HTMLInputElement).value)"
                class="w-full px-3 py-2 bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 rounded-lg text-xs font-mono outline-none focus:border-[#1845D4] disabled:opacity-60 disabled:cursor-not-allowed"
              />

              <p v-if="setting.help" class="text-[10px] text-[#8888A8] leading-snug">{{ setting.help }}</p>

              <div class="flex items-center gap-2 pt-0.5">
                <!-- Read-only: nothing to save. The server would refuse it, so
                     offering a button would only produce a failed request. -->
                <span
                  v-if="setting.readOnly"
                  class="px-2.5 py-1 rounded text-[10px] font-bold uppercase tracking-wider text-[#8888A8] bg-[#F5F6FA] dark:bg-slate-800"
                >
                  <i class="fas fa-lock mr-1"></i> Set on the server
                </span>
                <button
                  v-else
                  @click="saveSetting(setting)"
                  :disabled="!isDirty(setting) || busyKeys.has(setting.key)"
                  class="px-2.5 py-1 rounded text-[10px] font-bold uppercase tracking-wider transition-all cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
                  :class="isDirty(setting)
                    ? 'bg-[#1845D4] text-white hover:bg-[#0F2FA8]'
                    : 'bg-[#F5F6FA] dark:bg-slate-800 text-[#8888A8]'"
                >
                  <i v-if="busyKeys.has(setting.key)" class="fas fa-circle-notch fa-spin"></i>
                  <i v-else-if="savedKeys.has(setting.key)" class="fas fa-check"></i>
                  <span v-else>{{ isDirty(setting) ? 'Save' : 'Saved' }}</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue';
import { api } from '../api/client';
import { useFeaturesStore } from '../stores/features';

// ---- Types (mirror the backend registry payload) ----
interface SettingGroup { key: string; label: string; icon: string; description: string }
interface Setting {
  key: string; group: string; label: string; type: string;
  help: string; secret: boolean; value: string | null; isSet: boolean; updatedAt: string | null;
  // Set for values the runtime reads only from the environment (LiveKit API
  // key/secret): displayed for reference, never writable from here.
  readOnly?: boolean;
}
interface FeatureGroup { key: string; label: string; icon: string; description: string }
interface Feature {
  key: string; group: string; label: string; description: string;
  icon: string; default: boolean; critical: boolean; enabled: boolean;
}

const featuresStore = useFeaturesStore();

const loading = ref(true);
const loadError = ref<string | null>(null);

const settingGroups = ref<SettingGroup[]>([]);
const settings = ref<Setting[]>([]);
const featureGroups = ref<FeatureGroup[]>([]);
const features = ref<Feature[]>([]);

// Per-key busy/saved flags and editable drafts.
const busyKeys = reactive(new Set<string>());
const savedKeys = reactive(new Set<string>());
const drafts = reactive<Record<string, string>>({});

// ---- Derived groupings ----
const settingsByGroup = computed(() => {
  const map: Record<string, Setting[]> = {};
  for (const s of settings.value) (map[s.group] ||= []).push(s);
  return map;
});
const featuresByGroup = computed(() => {
  const map: Record<string, Feature[]> = {};
  for (const f of features.value) (map[f.group] ||= []).push(f);
  return map;
});
const enabledCount = computed(() => features.value.filter((f) => f.enabled).length);

// ---- Loading ----
async function load() {
  loading.value = true;
  loadError.value = null;
  try {
    const doc = await api.getDocument<{
      groups: SettingGroup[]; settings: Setting[];
      featureGroups: FeatureGroup[]; features: Feature[];
    }>('/api/v1/core/settings/');
    settingGroups.value = doc?.groups ?? [];
    settings.value = doc?.settings ?? [];
    featureGroups.value = doc?.featureGroups ?? [];
    features.value = doc?.features ?? [];
    // Seed editable drafts from current values.
    for (const s of settings.value) drafts[s.key] = s.value ?? '';
  } catch (err: any) {
    loadError.value = err?.message || 'Could not load settings.';
  } finally {
    loading.value = false;
  }
}

// ---- Mutations ----
function setDraft(key: string, value: string) {
  drafts[key] = value;
}

function isDirty(setting: Setting): boolean {
  if (setting.readOnly) return false;
  return drafts[setting.key] !== (setting.value ?? '');
}

function flashSaved(key: string) {
  savedKeys.add(key);
  setTimeout(() => savedKeys.delete(key), 1600);
}

async function toggleFeature(feat: Feature) {
  const next = !feat.enabled;
  busyKeys.add(feat.key);
  try {
    const updated = await api.patchDocument<Feature>(
      `/api/v1/core/features/${feat.key}/`,
      { enabled: next }
    ) as unknown as Feature;
    // The backend PATCH response returns the updated feature; sync local state.
    const target = features.value.find((f) => f.key === feat.key);
    if (target) target.enabled = updated?.enabled ?? next;
    // Mirror into the global features store so nav gating updates live.
    featuresStore.applyLocal(feat.key, next);
  } catch (err: any) {
    console.error('Feature toggle failed', err);
    // Revert nothing: leave the flag as-is since the server rejected the change.
  } finally {
    busyKeys.delete(feat.key);
  }
}

async function saveSetting(setting: Setting) {
  busyKeys.add(setting.key);
  try {
    const updated = await api.patchDocument<Setting>(
      `/api/v1/core/settings/${setting.key}/`,
      { value: drafts[setting.key] }
    ) as unknown as Setting;
    const target = settings.value.find((s) => s.key === setting.key);
    if (target) {
      target.value = updated?.value ?? drafts[setting.key];
      target.isSet = updated?.isSet ?? target.isSet;
      target.updatedAt = updated?.updatedAt ?? target.updatedAt;
    }
    // A secret left blank keeps its stored value; clear the draft to match.
    if (setting.secret) drafts[setting.key] = target?.value ?? '';
    flashSaved(setting.key);
  } catch (err: any) {
    console.error('Setting save failed', err);
  } finally {
    busyKeys.delete(setting.key);
  }
}

onMounted(load);

// Expose a reload for the parent (e.g. an Admin "Refresh" button).
defineExpose({ reload: load });
</script>
