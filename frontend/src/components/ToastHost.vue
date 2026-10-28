<template>
  <!-- One stack for the whole app. Teleported to <body> so a transformed
       parent (every view animates in) cannot offset it. -->
  <Teleport to="body">
    <div
      class="fixed z-[9999] bottom-4 left-4 right-4 sm:left-auto sm:right-6 sm:bottom-6 sm:w-[380px]
             flex flex-col gap-2.5 pointer-events-none"
      role="status"
      aria-live="polite"
    >
      <TransitionGroup name="toast">
        <div
          v-for="t in toasts"
          :key="t.id"
          class="toast-card pointer-events-auto relative overflow-hidden rounded-xl border border-[#E2E6F3]
                 bg-white/95 dark:bg-slate-900/95 backdrop-blur-md shadow-[0_12px_32px_-8px_rgba(13,13,26,0.24)]
                 dark:border-slate-700 pl-4 pr-2.5 py-3 flex items-start gap-3"
        >
          <!-- Accent rail: the kind is readable before the text is. -->
          <span class="absolute inset-y-0 left-0 w-1" :class="railClass(t.kind)"></span>

          <span
            class="mt-0.5 w-7 h-7 rounded-full flex items-center justify-center shrink-0"
            :class="iconWrapClass(t.kind)"
          >
            <i :class="[iconClass(t.kind), 'text-[11px]']"></i>
          </span>

          <div class="min-w-0 flex-1 pt-0.5">
            <p class="text-[12.5px] font-bold leading-snug text-[#0D0D1A] dark:text-white break-words">
              {{ t.title }}
            </p>
            <p
              v-if="t.message"
              class="text-[11.5px] leading-snug text-[#6B7280] dark:text-slate-400 mt-0.5 break-all line-clamp-3"
            >
              {{ t.message }}
            </p>
          </div>

          <button
            type="button"
            class="shrink-0 w-6 h-6 rounded-lg flex items-center justify-center text-[#A3A9BC]
                   hover:text-[#0D0D1A] dark:hover:text-white hover:bg-[#F2F4FA] dark:hover:bg-slate-800
                   transition-colors cursor-pointer"
            aria-label="Dismiss"
            @click="dismiss(t.id)"
          >
            <i class="fas fa-times text-[10px]"></i>
          </button>

          <!-- The bar is the countdown; a toast that vanishes with no warning
               feels like a glitch. -->
          <span
            class="toast-timer absolute bottom-0 left-0 h-[2px]"
            :class="railClass(t.kind)"
            :style="{ animationDuration: `${t.timeout}ms` }"
          ></span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { toasts, dismiss, type ToastKind } from '../stores/toast';

function railClass(kind: ToastKind): string {
  if (kind === 'success') return 'bg-emerald-500';
  if (kind === 'error') return 'bg-rose-500';
  return 'bg-[#1845D4]';
}

function iconWrapClass(kind: ToastKind): string {
  if (kind === 'success') return 'bg-emerald-500/12 text-emerald-600 dark:text-emerald-400';
  if (kind === 'error') return 'bg-rose-500/12 text-rose-600 dark:text-rose-400';
  return 'bg-blue-500/12 text-[#1845D4] dark:text-blue-400';
}

function iconClass(kind: ToastKind): string {
  if (kind === 'success') return 'fas fa-check';
  if (kind === 'error') return 'fas fa-exclamation';
  return 'fas fa-info';
}
</script>

<style scoped>
.toast-timer {
  width: 100%;
  transform-origin: left center;
  animation-name: toastDrain;
  animation-timing-function: linear;
  animation-fill-mode: forwards;
  opacity: 0.6;
}

@keyframes toastDrain {
  from {
    transform: scaleX(1);
  }
  to {
    transform: scaleX(0);
  }
}

.toast-enter-active {
  transition: opacity 0.26s cubic-bezier(0.16, 1, 0.3, 1), transform 0.26s cubic-bezier(0.16, 1, 0.3, 1);
}

.toast-leave-active {
  transition: opacity 0.18s ease-in, transform 0.18s ease-in;
  position: absolute;
  width: 100%;
}

.toast-move {
  transition: transform 0.22s cubic-bezier(0.16, 1, 0.3, 1);
}

.toast-enter-from {
  opacity: 0;
  transform: translateY(10px) scale(0.97);
}

.toast-leave-to {
  opacity: 0;
  transform: translateX(16px) scale(0.97);
}
</style>
