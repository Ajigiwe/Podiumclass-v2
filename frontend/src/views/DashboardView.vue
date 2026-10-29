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

      <!-- Hosting a class is a lecturer action: the server refuses a student's
           create call, so the button is not offered to one either. -->
      <button
        v-if="canHost"
        @click="showCreateModal = true"
        class="px-5 py-2.5 bg-[#1845D4] text-white text-[13px] font-bold rounded-lg hover:bg-[#0F2FA8] transition-all flex items-center gap-2 shadow-lg shadow-blue-600/10 cursor-pointer"
      >
        <i class="fas fa-plus text-xs"></i> New class
      </button>
    </div>

    <!-- A class was just created: the host stays here rather than being pushed
         into an empty room, and the code and link are ready to hand out. -->
    <div
      v-if="justCreated"
      class="bg-white dark:bg-slate-900 rounded-xl px-5 py-4 border border-emerald-200 dark:border-emerald-500/25 border-l-4 border-l-emerald-500 flex flex-col sm:flex-row sm:items-center gap-3"
    >
      <div class="flex-1 min-w-0">
        <p class="text-[10px] font-black text-emerald-600 dark:text-emerald-400 uppercase tracking-[0.2em]">Class created</p>
        <p class="text-[13px] font-bold text-[#0D0D1A] dark:text-white truncate">
          {{ justCreated.title }} is ready. Share the code — it opens when you join the class.
        </p>
      </div>
      <div class="flex items-center gap-2 shrink-0">
        <code class="px-3 py-2 rounded-lg bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 font-mono text-[12px] font-bold text-[#1845D4]">
          {{ justCreated.meetingCode }}
        </code>
        <button
          @click="copyText(justCreated.meetingCode, 'Class code')"
          class="px-3 py-2 rounded-lg bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 text-[12px] font-bold text-[#0D0D1A] dark:text-white hover:bg-[#E8EEFF] transition-all cursor-pointer"
        >
          Copy code
        </button>
        <button
          @click="copyText(classroomLink(justCreated), 'Invite link')"
          class="px-3 py-2 rounded-lg bg-[#1845D4] text-white text-[12px] font-bold hover:bg-[#0F2FA8] transition-all cursor-pointer"
        >
          Copy link
        </button>
        <button
          @click="justCreated = null"
          class="text-[#8888A8] hover:text-[#0D0D1A] dark:hover:text-white px-1 cursor-pointer"
          title="Dismiss"
        >
          <i class="fas fa-times"></i>
        </button>
      </div>
    </div>

    <!-- Quick Join (compact, above stats) -->
    <div class="bg-white dark:bg-slate-900 rounded-xl px-5 py-4 border border-[#DDE0F0] dark:border-slate-800 flex flex-col sm:flex-row sm:items-center gap-3">
      <p class="text-[11px] font-bold text-[#8888A8] uppercase tracking-widest whitespace-nowrap">Join a class</p>
      <form @submit.prevent="joinByCode" class="flex flex-col sm:flex-row gap-2 flex-1">
        <input
          v-model="quickMeetingCode"
          type="text"
          placeholder="Enter meeting code..."
          class="flex-1 min-w-0 px-4 py-2.5 bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 rounded-lg text-[13px] font-medium outline-none focus:border-[#1845D4] focus:ring-2 focus:ring-blue-600/10 transition-all"
          autocomplete="off"
        />
        <button
          type="submit"
          :disabled="isJoining"
          class="px-5 py-2.5 bg-[#1845D4] text-white rounded-lg text-[13px] font-bold hover:bg-[#0F2FA8] transition-all shadow-md shadow-blue-600/10 shrink-0 cursor-pointer disabled:opacity-60"
        >
          {{ isJoining ? 'Looking...' : 'Join' }}
        </button>
      </form>
      <p v-if="joinError" class="text-[11px] font-semibold text-red-600">{{ joinError }}</p>
    </div>

    <!-- Live now: the classes that are running this minute.
         One card per class, in the same language as every other card on the
         page. A live class used to be drawn twice -- a thin red banner above
         and a card in the list below, in two different styles -- and neither
         of them could do the one thing a host needs here: end the class. -->
    <div v-if="liveSessions.length > 0" class="space-y-3">
      <div class="flex items-center gap-2.5">
        <span class="relative flex w-2 h-2 shrink-0">
          <span class="absolute inline-flex w-full h-full rounded-full bg-red-400 opacity-70 animate-ping"></span>
          <span class="relative inline-flex w-2 h-2 rounded-full bg-red-500"></span>
        </span>
        <h2 class="text-[12px] font-bold text-[#0D0D1A] dark:text-white uppercase tracking-widest">Live now</h2>
        <div class="h-px flex-1 bg-[#DDE0F0] dark:bg-slate-800"></div>
      </div>

      <div
        v-for="session in liveSessions"
        :key="session.id"
        @click="enterClassroom(session)"
        class="relative overflow-hidden bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800
               rounded-xl p-4 sm:px-5 card-hover cursor-pointer group"
      >
        <span class="absolute inset-y-0 left-0 w-1 bg-red-500"></span>

        <div class="pl-2 flex flex-col lg:flex-row lg:items-center gap-4">
          <div class="flex items-center gap-3.5 min-w-0 flex-1">
            <div class="w-11 h-11 rounded-xl bg-red-50 dark:bg-red-500/10 text-red-500 flex items-center justify-center shrink-0">
              <i class="fas fa-video text-[15px]"></i>
            </div>
            <div class="min-w-0">
              <div class="flex items-center gap-2 min-w-0">
                <h3 class="text-[15px] font-bold text-[#0D0D1A] dark:text-white truncate group-hover:text-[#1845D4] transition-colors">
                  {{ session.title }}
                </h3>
                <span class="shrink-0 px-1.5 py-0.5 rounded bg-red-500 text-white text-[8px] font-black uppercase tracking-[0.16em]">
                  Live
                </span>
              </div>
              <p class="text-[11px] font-semibold text-[#8888A8] truncate mt-1">
                {{ session.course || 'General' }}<span class="mx-1.5 opacity-40">·</span>{{ session.lecturerName || 'Faculty' }}
              </p>
            </div>
          </div>

          <div class="flex flex-wrap items-center gap-2 lg:justify-end shrink-0">
            <button
              v-if="session.meetingCode"
              @click.stop="copyText(session.meetingCode, 'Class code')"
              class="inline-flex items-center gap-1.5 px-2.5 py-2 rounded-lg bg-[#F5F6FA] dark:bg-slate-800
                     border border-[#DDE0F0] dark:border-slate-700 font-mono text-[11px] font-bold text-[#1845D4]
                     dark:text-blue-400 hover:border-[#1845D4] transition-all cursor-pointer"
              title="Copy the class code"
            >
              {{ session.meetingCode }}
              <i class="fas fa-copy text-[9px] opacity-50"></i>
            </button>

            <button
              v-if="isMyClass(session)"
              @click.stop="copyText(classroomLink(session), 'Invite link')"
              class="px-2.5 py-2 rounded-lg border border-[#DDE0F0] dark:border-slate-700 text-[11px]
                     font-bold text-[#8888A8] hover:text-[#1845D4] hover:border-[#1845D4] transition-all cursor-pointer"
              title="Copy the invite link"
            >
              <i class="fas fa-link text-[9px] mr-1"></i>Invite
            </button>

            <!-- The host's own way out: end it for everyone, from here, without
                 walking into the room first. -->
            <button
              v-if="isMyClass(session)"
              @click.stop="askEndClass(session)"
              class="px-2.5 py-2 rounded-lg border border-red-200 dark:border-red-500/30 text-[11px]
                     font-bold text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-500/10
                     transition-all cursor-pointer"
            >
              <i class="fas fa-stop-circle text-[10px] mr-1"></i>End class
            </button>

            <button
              @click.stop="enterClassroom(session)"
              class="px-4 py-2 rounded-lg bg-[#1845D4] text-white text-[11px] font-bold
                     hover:bg-[#0F2FA8] transition-all cursor-pointer"
            >
              {{ isMyClass(session) ? 'Enter' : 'Join' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Stat Cards -->
    <div class="grid grid-cols-2 gap-4">
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
    </div>

    <!-- Jump back in: the classes that are not live at this moment. Live ones
         live in the section above, so the two lists no longer overlap and the
         heading no longer carries a red "live" dot that meant nothing here. -->
    <div v-if="idleSessions.length > 0" class="space-y-4">
      <div class="flex items-center gap-3">
        <h2 class="text-[12px] font-bold text-[#0D0D1A] dark:text-white uppercase tracking-widest">Jump back in</h2>
        <div class="h-px flex-1 bg-[#DDE0F0] dark:bg-slate-800"></div>
      </div>
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div
          v-for="session in idleSessions"
          :key="session.id"
          @click="enterClassroom(session)"
          class="group bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl p-4
                 flex items-center justify-between hover:border-[#1845D4] transition-all cursor-pointer"
        >
          <div class="flex items-center gap-3.5 flex-1 min-w-0">
            <div class="w-10 h-10 rounded-lg flex items-center justify-center shrink-0 bg-[#F5F6FA]
                        dark:bg-slate-800 text-[#8888A8] group-hover:bg-[#E8EEFF] group-hover:text-[#1845D4] transition-all">
              <i class="fas fa-graduation-cap text-sm"></i>
            </div>
            <div class="flex-1 min-w-0">
              <h4 class="text-[14px] font-bold text-[#0D0D1A] dark:text-white truncate group-hover:text-[#1845D4] transition-colors">
                {{ session.title }}
              </h4>
              <p class="text-[10px] font-bold text-[#8888A8] uppercase tracking-widest mt-0.5 truncate">
                {{ session.course || 'General' }}<span class="mx-1 opacity-40">·</span>{{ session.lecturerName || 'Faculty' }}
              </p>
            </div>
          </div>
          <div class="ml-3 flex items-center gap-2 shrink-0">
            <span
              :class="[
                'px-2 py-0.5 rounded text-[8px] font-black uppercase tracking-[0.16em] border',
                session.status === 'ended'
                  ? 'text-[#8888A8] bg-[#F5F6FA] dark:bg-slate-800 border-[#DDE0F0] dark:border-slate-700'
                  : 'text-[#1845D4] bg-blue-50 dark:bg-blue-900/20 border-blue-100 dark:border-blue-500/20'
              ]"
            >
              {{ session.status === 'ended' ? 'Ended' : 'Scheduled' }}
            </span>
            <i class="fas fa-chevron-right text-[#DDE0F0] group-hover:text-[#1845D4] transition-all text-xs"></i>
          </div>
        </div>
      </div>
    </div>

    <!-- Action Cards -->
    <div class="grid sm:grid-cols-2 gap-4">
      <div
        v-if="canHost"
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

      <div v-if="filteredSessions.length === 0" class="bg-white dark:bg-slate-900 border border-dashed border-[#DDE0F0] dark:border-slate-800 rounded-xl px-5 py-10 text-center">
        <p class="text-[13px] font-semibold text-[#0D0D1A] dark:text-white">
          {{ activeRecordTab === 'hosted' ? 'You are not hosting any classes' : 'You have not joined a class yet' }}
        </p>
        <p class="text-[12px] text-[#8888A8] mt-1">
          {{ activeRecordTab === 'hosted'
            ? 'Classes you create appear here.'
            : 'Enter a meeting code or open an invite link to join one.' }}
        </p>
      </div>

      <div v-else class="space-y-3">
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
              <p class="text-[11px] text-[#8888A8] font-medium flex items-center gap-1.5 min-w-0">
                <span class="truncate">{{ session.course || 'General' }} • {{ session.lecturerName || 'Faculty' }}</span>
                <!-- The code is what people actually share, so it is a control,
                     not a label: one click and it is on the clipboard. -->
                <button
                  v-if="session.meetingCode"
                  @click.stop="copyText(session.meetingCode, 'Class code')"
                  class="shrink-0 inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-[#F5F6FA]
                         dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 font-mono text-[10px]
                         font-bold text-[#1845D4] dark:text-blue-400 hover:border-[#1845D4] hover:bg-[#E8EEFF]
                         dark:hover:bg-slate-700 transition-all cursor-pointer"
                  title="Copy the class code"
                >
                  {{ session.meetingCode }}
                  <i class="fas fa-copy text-[8px] opacity-60"></i>
                </button>
              </p>
            </div>
          </div>

          <div class="flex items-center gap-3 ml-3 flex-shrink-0">
            <!-- The host's own classes: the link is the other thing they hand
                 out, next to the code above. -->
            <button
              v-if="session.hostId === authStore.user?.id && session.meetingCode"
              @click.stop="copyText(classroomLink(session), 'Invite link')"
              class="hidden sm:inline-flex items-center gap-1.5 px-2 py-1 rounded-lg border border-[#DDE0F0] dark:border-slate-700 text-[10px] font-bold text-[#8888A8] hover:text-[#1845D4] hover:border-[#1845D4] transition-all cursor-pointer"
              title="Copy the invite link"
            >
              <i class="fas fa-link text-[9px]"></i> Invite
            </button>
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
              {{ session.status === 'ended' ? 'Ended' : 'Scheduled' }}
            </span>
            <i class="fas fa-chevron-right text-[#DDE0F0] text-xs"></i>
          </div>
        </div>
      </div>
    </div>

    <!-- Records: the host's recordings, with what they can do about them.
         Only hosts have any, so the section is theirs alone. -->
    <div v-if="canHost" class="space-y-4">
      <div class="flex items-center gap-3">
        <h2 class="text-[12px] font-bold text-[#0D0D1A] dark:text-white uppercase tracking-widest">Records</h2>
        <div class="h-px flex-1 bg-[#DDE0F0] dark:bg-slate-800"></div>
        <button
          @click="loadRecordings"
          :disabled="isLoadingRecordings"
          class="text-[11px] font-bold text-[#8888A8] hover:text-[#1845D4] transition-colors cursor-pointer"
        >
          <i :class="['fas fa-sync-alt text-[10px] mr-1', { 'animate-spin': isLoadingRecordings }]"></i>Refresh
        </button>
      </div>

      <p v-if="recordsError" class="text-[11px] font-semibold text-red-500">{{ recordsError }}</p>

      <div v-if="!recordingsEnabled" class="bg-white dark:bg-slate-900 border border-dashed border-[#DDE0F0] dark:border-slate-800 rounded-xl px-5 py-8 text-center">
        <p class="text-[13px] font-semibold text-[#0D0D1A] dark:text-white">Recording is switched off</p>
        <p class="text-[12px] text-[#8888A8] mt-1">An administrator can turn it on in the admin panel.</p>
      </div>
      <div v-else-if="recordings.length === 0" class="bg-white dark:bg-slate-900 border border-dashed border-[#DDE0F0] dark:border-slate-800 rounded-xl px-5 py-8 text-center">
        <p class="text-[13px] font-semibold text-[#0D0D1A] dark:text-white">No recordings yet</p>
        <p class="text-[12px] text-[#8888A8] mt-1">Press record inside a class and its video appears here.</p>
      </div>
      <div v-else class="space-y-3">
        <div
          v-for="rec in visibleRecordings"
          :key="rec.id"
          class="bg-white dark:bg-slate-900 border border-[#DDE0F0] dark:border-slate-800 rounded-xl p-4 space-y-3"
        >
          <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div class="min-w-0">
              <p class="text-[13px] font-bold text-[#0D0D1A] dark:text-white truncate">{{ rec.title }}</p>
              <p class="text-[10px] font-bold text-[#8888A8] uppercase tracking-wider mt-0.5">
                {{ formatWhen(rec.startedAt) }}
                <template v-if="rec.durationSeconds != null"> • {{ formatClock(rec.durationSeconds) }}</template>
                <template v-if="rec.sizeBytes"> • {{ formatBytes(rec.sizeBytes) }}</template>
              </p>
            </div>
            <div class="flex items-center gap-2 shrink-0">
              <span
                :class="[
                  'px-2 py-1 rounded-lg text-[9px] font-black uppercase tracking-widest border',
                  rec.status === 'complete' ? 'text-emerald-600 bg-emerald-500/10 border-emerald-400/30'
                    : rec.status === 'failed' || rec.status === 'aborted' ? 'text-red-500 bg-red-500/10 border-red-400/30'
                    : 'text-amber-600 bg-amber-500/10 border-amber-400/30'
                ]"
              >
                {{ rec.status === 'complete' ? 'Ready' : rec.status === 'failed' || rec.status === 'aborted' ? 'Failed' : 'Processing' }}
              </span>
              <button
                v-if="rec.canPlay"
                @click="playingId = playingId === rec.id ? null : rec.id"
                class="px-3 py-2 rounded-lg bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 text-[11px] font-bold text-[#0D0D1A] dark:text-white hover:bg-[#E8EEFF] transition-all cursor-pointer"
              >
                <i class="fas fa-play text-[9px] mr-1"></i>{{ playingId === rec.id ? 'Hide' : 'Play' }}
              </button>
              <a
                v-if="rec.downloadUrl"
                :href="rec.downloadUrl"
                class="px-3 py-2 rounded-lg bg-[#1845D4] text-white text-[11px] font-bold hover:bg-[#0F2FA8] transition-all cursor-pointer"
              >
                <i class="fas fa-download text-[9px] mr-1"></i>Download
              </a>
              <button
                v-if="rec.downloadUrl"
                @click="copyText(rec.downloadUrl, 'Recording link')"
                class="px-3 py-2 rounded-lg bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 text-[11px] font-bold text-[#0D0D1A] dark:text-white hover:bg-[#E8EEFF] transition-all cursor-pointer"
              >
                <i class="fas fa-share-nodes text-[9px] mr-1"></i>Share
              </button>
            </div>
          </div>
          <video
            v-if="playingId === rec.id && rec.playUrl"
            :src="rec.playUrl"
            controls
            preload="metadata"
            class="w-full rounded-lg bg-black max-h-[45vh] border border-[#DDE0F0] dark:border-slate-800"
          ></video>
          <p v-if="rec.error" class="text-[11px] font-semibold text-red-500">{{ rec.error }}</p>
        </div>

        <button
          v-if="recordings.length > RECORDS_PREVIEW"
          @click="showAllRecords = !showAllRecords"
          class="w-full py-2.5 rounded-lg bg-[#F5F6FA] dark:bg-slate-800 border border-[#DDE0F0] dark:border-slate-700 text-[11px] font-bold text-[#8888A8] hover:text-[#1845D4] transition-all cursor-pointer"
        >
          {{ showAllRecords ? 'Show fewer' : `Show all ${recordings.length} recordings` }}
        </button>
      </div>
    </div>

    <!-- Modal: New Class.
         Teleported to <body>: this page animates in, and an animation that
         ends on `transform: translateY(0)` stays a containing block, which
         shrank `fixed inset-0` to the page column and left the dialog hanging
         at the bottom of the screen. -->
    <Teleport to="body">
    <div
      v-if="showCreateModal"
      class="fixed inset-0 bg-[#0D0D1A]/55 backdrop-blur-sm z-[9000] flex items-center justify-center p-4"
      @click.self="showCreateModal = false"
    >
      <div
        class="bg-white dark:bg-slate-900 rounded-2xl max-w-lg w-full p-6 space-y-6 border border-[#DDE0F0]
               dark:border-slate-800 shadow-2xl max-h-[90vh] overflow-y-auto modal-pop"
      >
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
    </Teleport>

    <!-- Confirm ending a class. A destructive action deserves a dialog the app
         draws, not a browser confirm(): that one blocks the page, is branded by
         the browser, and cannot say what actually happens next. -->
    <Teleport to="body">
      <div
        v-if="endTarget"
        class="fixed inset-0 bg-[#0D0D1A]/55 backdrop-blur-sm z-[9000] flex items-center justify-center p-4"
        @click.self="endTarget = null"
      >
        <div
          class="bg-white dark:bg-slate-900 rounded-2xl max-w-md w-full p-6 space-y-5 border
                 border-[#DDE0F0] dark:border-slate-800 shadow-2xl modal-pop"
        >
          <div class="flex items-start gap-3.5">
            <div class="w-10 h-10 rounded-xl bg-red-50 dark:bg-red-500/10 text-red-600 dark:text-red-400
                        flex items-center justify-center shrink-0">
              <i class="fas fa-stop-circle"></i>
            </div>
            <div class="min-w-0">
              <h3 class="text-[15px] font-bold text-[#0D0D1A] dark:text-white">End this class?</h3>
              <p class="text-[12px] text-[#8888A8] leading-relaxed mt-1">
                <span class="font-bold text-[#0D0D1A] dark:text-white">{{ endTarget.title }}</span>
                closes for everyone — the room is cleared and students cannot come back
                in until you rejoin. Attendance already recorded is kept.
              </p>
            </div>
          </div>

          <div class="flex justify-end gap-3 pt-1">
            <button
              @click="endTarget = null"
              class="px-4 py-2 rounded-lg text-[13px] font-bold text-[#8888A8] hover:text-[#0D0D1A]
                     dark:hover:text-white transition-colors cursor-pointer"
            >
              Cancel
            </button>
            <button
              @click="confirmEndClass"
              :disabled="isEnding"
              class="px-5 py-2.5 rounded-lg bg-red-600 text-white text-[13px] font-bold
                     hover:bg-red-700 transition-all shadow-lg shadow-red-600/20 cursor-pointer
                     disabled:opacity-60 disabled:cursor-not-allowed"
            >
              {{ isEnding ? 'Ending...' : 'End class' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api/client';
import { useAuthStore } from '../stores/auth';
import { useFeaturesStore } from '../stores/features';
import { toast, errorText } from '../stores/toast';
import { copyWithToast } from '../lib/clipboard';
import { classroomInviteLink } from '../lib/links';
import { captureEvent } from '../lib/analytics';

const router = useRouter();
const authStore = useAuthStore();
const featuresStore = useFeaturesStore();

//: How many recordings the dashboard shows before "show all".
const RECORDS_PREVIEW = 4;

const sessions = ref<any[]>([]);
const quickMeetingCode = ref('');
const activeRecordTab = ref<'enrolled' | 'hosted'>('enrolled');
const showCreateModal = ref(false);
const isSubmitting = ref(false);
const isJoining = ref(false);
const joinError = ref('');
// The class just created, if any: shown with its code and invite link instead
// of throwing the host straight into an empty room.
const justCreated = ref<any>(null);
// The class a host has asked to end, and whether the request is in flight.
const endTarget = ref<any>(null);
const isEnding = ref(false);

// --- Records (host only) ---------------------------------------------------
const recordings = ref<any[]>([]);
const isLoadingRecordings = ref(false);
const playingId = ref<string | null>(null);
const showAllRecords = ref(false);
const recordsError = ref('');

// Creating a class is a lecturer/admin action (enforced by the server); a
// student joins by code or invite link only.
const canHost = computed(
  () => authStore.user?.role === 'lecturer' || authStore.user?.role === 'admin'
);

const newSession = ref({
  title: '',
  course: '',
  lecturerName: authStore.user?.fullName || '',
  autoAttendanceEnabled: true,
  verificationCount: 3,
  timeLimitSeconds: 30,
});

const liveSessions = computed(() => sessions.value.filter((s) => s.status === 'active'));
// Everything that is not live: what "Jump back in" offers. The two lists were
// previously the same list, so a live class showed up in both.
const idleSessions = computed(() => sessions.value.filter((s) => s.status !== 'active'));
// The two tabs were decorative: both showed the same list, so "Enrolled"
// counted classes the user hosts and vice versa.
const hostedSessions = computed(
  () => sessions.value.filter((s) => s.hostId && s.hostId === authStore.user?.id)
);
const enrolledSessions = computed(() =>
  sessions.value.filter((s) => !s.hostId || s.hostId !== authStore.user?.id)
);
// Counted from the sessions actually owned, not from a literal: the previous
// expression returned 1 for every non-lecturer, so the dashboard claimed a
// hosted class even with an empty database.
const hostedCount = computed(() => hostedSessions.value.length);

const filteredSessions = computed(() =>
  activeRecordTab.value === 'hosted' ? hostedSessions.value : enrolledSessions.value
);

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
    // The server scopes this list to the caller: a lecturer sees the classes
    // being taught, a student sees the ones they have joined.
    const res = await api.getDocuments<any>('/api/v1/classrooms/sessions/');
    sessions.value = res.documents || [];
  } catch (e) {
    console.error('Failed to load sessions', e);
  }
}

/**
 * Create a class and stay put.
 *
 * It used to jump straight into the room, which was wrong twice over: the host
 * ended up in an empty classroom when all they wanted was to hand out a code,
 * and the class counted as live from the moment the form was submitted. The
 * class now appears on the dashboard with its code and link ready, and it goes
 * live when the host joins it.
 */
async function createSession() {
  isSubmitting.value = true;
  try {
    const doc = await api.postDocument<any>('/api/v1/classrooms/sessions/', newSession.value);
    showCreateModal.value = false;
    justCreated.value = doc;
    captureEvent('class_created', {
      is_free: !!doc?.is_free,
      auto_attendance: !!newSession.value.autoAttendanceEnabled,
      verification_count: newSession.value.verificationCount,
    });
    await loadSessions();
  } catch (err: any) {
    toast.error('Class not created', errorText(err, 'The server refused the request.'));
  } finally {
    isSubmitting.value = false;
  }
}

/** Absolute invite link for a class -- what a host pastes into a chat. */
function classroomLink(session: any): string {
  return classroomInviteLink(router, session.id);
}

/** Copy and report it in a toast, on every copy path in this view. */
async function copyText(text: string, label = 'Link') {
  await copyWithToast(text, label);
}

const recordingsEnabled = computed(() => featuresStore.isEnabled('recordings'));
const visibleRecordings = computed(
  () => (showAllRecords.value ? recordings.value : recordings.value.slice(0, RECORDS_PREVIEW))
);

/**
 * The host's recordings, in one request.
 *
 * Only hosts have this section at all, and the server only returns the classes
 * they run -- so there is nothing to filter here, and a student's dashboard
 * makes no recording call.
 */
async function loadRecordings() {
  if (!canHost.value || !recordingsEnabled.value) return;
  isLoadingRecordings.value = true;
  recordsError.value = '';
  try {
    const res = await api.getDocuments<any>('/api/v1/classrooms/recordings/');
    recordings.value = res.documents || [];
  } catch (err: any) {
    recordsError.value = err?.message || 'Could not load your recordings.';
  } finally {
    isLoadingRecordings.value = false;
  }
}

function formatWhen(value?: string | null): string {
  if (!value) return '—';
  try {
    return new Date(value).toLocaleString('en-US', {
      month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
    });
  } catch {
    return value;
  }
}

function formatClock(seconds: number): string {
  const total = Math.max(0, Math.round(seconds));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

function enterClassroom(session: any) {
  router.push(`/classroom/${session.id}`);
}

/**
 * Whether the signed-in user runs this class.
 *
 * Its host does, and so does an admin: the end endpoint has always allowed an
 * admin to close any class, but the web app had no way to ask for it.
 */
function isMyClass(session: any): boolean {
  if (!session) return false;
  if (authStore.user?.role === 'admin') return true;
  return !!session.hostId && session.hostId === authStore.user?.id;
}

/** Open the confirmation for ending a class, rather than ending it on a click. */
function askEndClass(session: any) {
  endTarget.value = session;
}

/**
 * End the class for everyone.
 *
 * The server does the real work -- it clears the LiveKit room so every
 * participant is disconnected at once, closes the class to re-entry until the
 * host rejoins, and finalises a recording that is still writing. Nothing is
 * deleted: attendance and history stay readable afterwards.
 */
async function confirmEndClass() {
  const session = endTarget.value;
  if (!session || isEnding.value) return;
  isEnding.value = true;
  try {
    await api.postDocument(`/api/v1/classrooms/sessions/${session.id}/end/`, {});
    endTarget.value = null;
    toast.success(
      'Class ended',
      `${session.title} was closed and everyone was removed. Rejoin to open it again.`
    );
    await loadSessions();
    void loadRecordings();
  } catch (err: any) {
    toast.error('Could not end the class', errorText(err, 'The server refused the request.'));
  } finally {
    isEnding.value = false;
  }
}

/**
 * Join by meeting code.
 *
 * The code is resolved by the server, not by scanning the list on screen: a
 * student's list only contains classes they are already in, so looking the
 * code up locally could never find the class they were invited to. The lookup
 * also accepts an invite link or a raw session id (both of which the classroom
 * route can open directly), which keeps a pasted link working.
 */
async function joinByCode() {
  const raw = quickMeetingCode.value.trim();
  if (!raw || isJoining.value) return;
  joinError.value = '';

  const linked = extractSessionFromLink(raw);
  if (linked) {
    quickMeetingCode.value = '';
    router.push(`/classroom/${linked}`);
    return;
  }

  isJoining.value = true;
  try {
    const res = await api.getDocuments<any>('/api/v1/classrooms/sessions/', { meetingCode: raw });
    const match = (res.documents || [])[0];
    if (match) {
      quickMeetingCode.value = '';
      router.push(`/classroom/${match.id}`);
    } else {
      joinError.value = `No class found with the code "${raw}". Check it and try again.`;
    }
  } catch (err: any) {
    joinError.value = err?.message || 'Could not look up that class.';
  } finally {
    isJoining.value = false;
  }
}

/** Pull the session out of a pasted invite link, if that is what this is. */
function extractSessionFromLink(value: string): string | null {
  try {
    const url = new URL(value, window.location.origin);
    // Invite links carry the classroom in the hash (`/#/classroom/<id>`) under
    // hash history, and in the path (`/classroom/<id>`) under web history.
    // Searching both means a link copied before or after a routing change, or
    // pasted from the Android share sheet, still resolves.
    const match = `${url.pathname}${url.hash}`.match(/\/classroom\/([^/?#&]+)/);
    if (match) return decodeURIComponent(match[1]);
  } catch {
    /* not a URL */
  }
  return null;
}

onMounted(async () => {
  await loadSessions();
  // Features are public and cached in the store; the Records section needs
  // them to know whether recording exists at all.
  if (!featuresStore.loaded) {
    await featuresStore.loadFeatures();
  }
  void loadRecordings();
});
</script>
