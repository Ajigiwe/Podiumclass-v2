<script setup lang="ts">
import { computed } from 'vue';
import { useClassroomStore, type MediaKind } from '../stores/classroom';

/**
 * The host's moderation controls, shared by the desktop and compact side
 * panels so both always show the same room state.
 *
 * Every control writes through to the session row first (the store does that),
 * then fans out over the LiveKit data channel -- so a refresh on either side
 * comes back to the same locked or open room.
 */
const store = useClassroomStore();

const META: Record<MediaKind, { label: string; icon: string }> = {
  mic: { label: 'Mic', icon: 'fas fa-microphone' },
  camera: { label: 'Camera', icon: 'fas fa-video' },
  screen: { label: 'Screen', icon: 'fas fa-desktop' },
};
const kinds = ['mic', 'camera', 'screen'] as const;

const isMutedAll = computed(() => store.isMutedAll);
const autoApproveMic = computed(() => store.autoApproveMic);
const policy = computed(() => store.permissionPolicy);
const pending = computed(() => store.pendingRequests);

function onToggleMuteAll() {
  void store.setMuteAll(!store.isMutedAll);
}

function onSmartMic(event: Event) {
  const checked = (event.target as HTMLInputElement).checked;
  void store.setAutoApproveMic(checked);
}

function togglePolicy(kind: MediaKind) {
  const next = policy.value[kind] === 'approval' ? 'free' : 'approval';
  void store.setPermissionPolicy(kind, next);
}

function review(requestId: string, approve: boolean) {
  void store.reviewPermissionRequest(requestId, approve);
}
</script>

<template>
  <div class="p-3 border-b border-white/[0.08] bg-white/[0.02] flex flex-col gap-2.5 shrink-0">
    <div class="flex items-center justify-between gap-2">
      <button
        @click="onToggleMuteAll"
        class="px-3 py-1.5 text-[10px] font-bold uppercase tracking-wider bg-white/5 hover:bg-white/10 rounded-lg text-white border border-white/5 transition-all flex items-center gap-1.5 cursor-pointer"
        :title="isMutedAll ? 'Release everyone' : 'Mute everyone and lock their microphones'"
      >
        <i class="fas fa-volume-mute text-xs text-red-400"></i>
        <span>{{ isMutedAll ? 'Unmute All' : 'Mute All' }}</span>
      </button>

      <label
        class="flex items-center gap-2 px-2.5 py-1.5 text-[10px] font-bold uppercase tracking-wider bg-white/5 rounded-lg text-white cursor-pointer"
        title="Answer microphone requests automatically"
      >
        <input type="checkbox" :checked="autoApproveMic" @change="onSmartMic" class="rounded" />
        <span>Smart Mic</span>
      </label>
    </div>

    <!-- Per-medium policy: what the class has to ask for. -->
    <div class="flex items-center flex-wrap gap-1.5">
      <span class="text-[9px] font-black uppercase tracking-widest text-slate-400 w-full">
        Requires approval
      </span>
      <button
        v-for="kind in kinds"
        :key="kind"
        @click="togglePolicy(kind)"
        class="px-2 py-1 text-[9px] font-black uppercase tracking-wider rounded-md border transition-all flex items-center gap-1.5 cursor-pointer"
        :class="
          policy[kind] === 'approval'
            ? 'bg-amber-500/15 border-amber-400/40 text-amber-300'
            : 'bg-white/5 border-white/10 text-slate-300 hover:bg-white/10'
        "
        :title="
          policy[kind] === 'approval'
            ? `${META[kind].label} needs your approval — click to open it`
            : `${META[kind].label} is open to everyone — click to lock it`
        "
      >
        <i :class="[META[kind].icon, 'text-[9px]']"></i>
        {{ policy[kind] === 'approval' ? 'Ask' : 'Free' }}
        <span class="normal-case font-semibold text-slate-400">{{ META[kind].label }}</span>
      </button>
    </div>

    <!-- Requests waiting for an answer. -->
    <div v-if="pending.length" class="space-y-1.5">
      <p class="text-[9px] font-black uppercase tracking-widest text-amber-400">
        {{ pending.length }} request{{ pending.length > 1 ? 's' : '' }} waiting
      </p>
      <div
        v-for="request in pending"
        :key="request.id"
        class="flex items-center justify-between gap-2 p-2 rounded-lg bg-amber-500/10 border border-amber-500/20"
      >
        <div class="min-w-0">
          <p class="text-[11px] font-bold text-white truncate">{{ request.participantName }}</p>
          <p class="text-[10px] text-amber-200/80 capitalize">
            wants the {{ META[request.requestType]?.label.toLowerCase() || request.requestType }}
          </p>
        </div>
        <div class="flex items-center gap-1 shrink-0">
          <button
            @click="review(request.id, true)"
            class="px-2 py-1 text-[9px] font-black uppercase rounded-md bg-emerald-500/20 text-emerald-300 hover:bg-emerald-500/30 transition-colors cursor-pointer"
          >
            Allow
          </button>
          <button
            @click="review(request.id, false)"
            class="px-2 py-1 text-[9px] font-black uppercase rounded-md bg-red-500/20 text-red-300 hover:bg-red-500/30 transition-colors cursor-pointer"
          >
            Deny
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
