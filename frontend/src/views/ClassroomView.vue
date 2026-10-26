<template>
  <div class="fixed inset-0 z-50 bg-[#0a0a0a] text-white flex flex-col font-sans select-none overflow-hidden">
    <!-- ==================== STATE 1: LOADING STATE ==================== -->
    <div v-if="isLoading" class="min-h-screen flex items-center justify-center bg-[#0a0a0a]">
      <div class="flex flex-col items-center gap-3">
        <div class="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin"></div>
        <p class="text-xs font-mono text-slate-400 uppercase tracking-widest">Connecting to PostgreSQL & LiveKit...</p>
      </div>
    </div>

    <!-- ============ STATE 1b: CONNECTION FAILED (honest, no fake fallback) ============ -->
    <div
      v-else-if="classroomStore.hasFailed"
      class="min-h-screen bg-[#0a0a0a] flex items-center justify-center p-6 animate-fade-in"
    >
      <div class="max-w-md w-full bg-[#121217] border border-white/10 rounded-2xl p-8 text-center space-y-6 shadow-2xl">
        <div class="w-16 h-16 bg-red-500/10 text-red-500 rounded-2xl flex items-center justify-center text-2xl mx-auto border border-red-500/20">
          <i class="fas fa-wifi-slash"></i>
        </div>
        <div class="space-y-2">
          <p class="text-[10px] font-bold text-red-400 uppercase tracking-[0.3em]">Connection Failed</p>
          <h2 class="text-2xl font-black text-white tracking-tight">Could Not Join Class</h2>
          <p class="text-xs text-slate-400 leading-relaxed">
            {{ classroomStore.errorMessage || 'The LiveKit server is unreachable.' }}
          </p>
        </div>
        <div class="flex flex-col sm:flex-row gap-3 pt-2">
          <button
            @click="retryJoin"
            class="flex-1 py-3 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-bold uppercase tracking-widest transition-all shadow-lg shadow-indigo-600/20 cursor-pointer"
          >
            Retry Connection
          </button>
          <router-link
            to="/"
            class="flex-1 py-3 bg-white/5 hover:bg-white/10 text-slate-300 rounded-xl text-xs font-bold uppercase tracking-widest text-center border border-white/10 transition-all"
          >
            Return Home
          </router-link>
        </div>
      </div>
    </div>

    <!-- ==================== STATE 2: INSUFFICIENT BALANCE SCREEN ==================== -->
    <div
      v-else-if="insufficientBalance"
      class="min-h-screen bg-[#0a0a0a] flex items-center justify-center p-6 animate-fade-in"
    >
      <div class="max-w-md w-full bg-[#121217] border border-white/10 rounded-2xl p-8 text-center space-y-6 shadow-2xl">
        <div class="w-16 h-16 bg-red-500/10 text-red-500 rounded-2xl flex items-center justify-center text-2xl mx-auto border border-red-500/20">
          <i class="fas fa-coins"></i>
        </div>
        <div class="space-y-2">
          <p class="text-[10px] font-bold text-red-400 uppercase tracking-[0.3em]">Access Fee Required</p>
          <h2 class="text-2xl font-black text-white tracking-tight">Insufficient Balance</h2>
          <p class="text-xs text-slate-400 leading-relaxed">
            You need GHS {{ (insufficientBalance.needed / 100).toFixed(2) }} to enter this classroom.
            Your current balance is GHS {{ (insufficientBalance.have / 100).toFixed(2) }}.
          </p>
        </div>
        <div class="flex flex-col sm:flex-row gap-3 pt-2">
          <router-link
            to="/wallet"
            class="flex-1 py-3 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-bold uppercase tracking-widest text-center transition-all shadow-lg shadow-indigo-600/20"
          >
            Top Up Wallet
          </router-link>
          <router-link
            to="/"
            class="flex-1 py-3 bg-white/5 hover:bg-white/10 text-slate-300 rounded-xl text-xs font-bold uppercase tracking-widest text-center border border-white/10 transition-all"
          >
            Return Home
          </router-link>
        </div>
      </div>
    </div>

    <!-- ==================== STATE 3: LOBBY / SCHEDULED WAIT SCREEN ==================== -->
    <div
      v-else-if="isWaitingLobby"
      class="min-h-screen bg-[#0a0a0a] flex items-center justify-center p-6 animate-fade-in"
    >
      <div class="max-w-md w-full space-y-8 text-center">
        <div class="space-y-4">
          <div class="w-20 h-20 bg-[#121217] rounded-2xl flex items-center justify-center mx-auto border border-white/10 text-indigo-400 shadow-xl">
            <i class="fas fa-clock text-3xl animate-pulse"></i>
          </div>
          <div class="space-y-1.5">
            <p class="text-[10px] font-bold text-slate-400 uppercase tracking-[0.4em]">
              {{ isScheduledFuture ? 'Upcoming Session' : 'Lobby Access' }}
            </p>
            <h1 class="text-2xl sm:text-3xl font-black text-white tracking-tight">
              {{ isScheduledFuture ? 'Starting Soon' : 'Awaiting Faculty Host' }}
            </h1>
          </div>
        </div>

        <div class="bg-[#121217] rounded-2xl p-6 border border-white/10 text-left space-y-4 shadow-xl">
          <div class="space-y-1">
            <p class="text-[10px] font-bold text-indigo-400 uppercase tracking-widest">Active Classroom</p>
            <h2 class="text-lg font-bold text-white leading-snug">{{ sessionData?.title || 'Classroom Session' }}</h2>
          </div>
          <div class="flex flex-wrap items-center gap-4 pt-4 border-t border-white/5 text-[10px] font-bold text-slate-400 uppercase tracking-widest">
            <span class="flex items-center gap-1.5"><i class="fas fa-laptop-code text-indigo-400"></i> Virtual Environment</span>
            <span class="flex items-center gap-1.5"><i class="fas fa-user-tie text-emerald-400"></i> {{ sessionData?.lecturerName || 'Faculty Host' }}</span>
          </div>
        </div>

        <div class="flex flex-col gap-3">
          <!-- If Lecturer is viewing scheduled session: Option to start class now -->
          <button
            v-if="isLecturer"
            @click="activateSessionNow"
            :disabled="isActivating"
            class="w-full py-3.5 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl text-xs font-bold uppercase tracking-widest shadow-lg shadow-emerald-600/20 transition-all cursor-pointer"
          >
            {{ isActivating ? 'Opening Classroom...' : 'Start Class Now' }}
          </button>
          <button
            v-else
            @click="checkSessionStatus"
            class="w-full py-3.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-bold uppercase tracking-widest shadow-lg shadow-indigo-600/20 transition-all cursor-pointer"
          >
            Check In Again
          </button>
          <router-link
            to="/"
            class="w-full py-3.5 bg-white/5 hover:bg-white/10 text-slate-400 hover:text-white rounded-xl text-xs font-bold uppercase tracking-widest border border-white/5 transition-all text-center"
          >
            Return to Dashboard
          </router-link>
        </div>
      </div>
    </div>

    <!-- ==================== STATE 4: LIVE WORKSPACE ==================== -->
    <template v-else>
      <!-- Classroom Header in Natural Flex Flow -->
      <header class="h-14 sm:h-16 shrink-0 z-40 flex items-center bg-black border-b border-white/[0.08]">
        <div class="w-full px-3 sm:px-6 flex items-center justify-between gap-2 sm:gap-4">
          <!-- Left: Title & Live Meta -->
          <div class="flex items-center gap-3 min-w-0 flex-1">
            <router-link
              to="/"
              class="p-2 -ml-2 text-slate-400 hover:text-white transition-colors"
              title="Return to Dashboard"
            >
              <i class="fas fa-home w-4 h-4 text-sm"></i>
            </router-link>

            <div class="min-w-0">
              <h1 class="text-[13px] sm:text-base font-bold text-white truncate leading-tight max-w-[140px] xs:max-w-[200px] sm:max-w-md">
                {{ sessionData?.title || 'Podium Classroom' }}
              </h1>
              <div class="flex items-center gap-2 mt-0.5">
                <span class="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse"></span>
                <span class="text-[9px] font-black uppercase tracking-widest text-slate-400">
                  {{ sessionData?.course || 'LIVE' }}
                </span>
                <span class="text-slate-600 text-[10px]">&bull;</span>
                <span class="text-[10px] text-slate-400 font-mono">{{ sessionTimer }}</span>
              </div>
            </div>
          </div>

          <!-- Right: Action Pills -->
          <div class="flex items-center gap-2 sm:gap-3">
            <!-- Copy Meeting Code Pill -->
            <div
              @click="copyCode"
              class="hidden sm:flex items-center gap-1.5 bg-white/5 hover:bg-white/10 px-3 py-1.5 rounded-xl border border-white/5 cursor-pointer text-xs transition-colors"
              title="Click to copy meeting code"
            >
              <span class="text-slate-400 text-[10px] font-bold uppercase tracking-wider">Code:</span>
              <span class="font-mono text-indigo-400 font-bold">{{ sessionData?.meetingCode || meetingCode }}</span>
              <i class="fas fa-copy text-[10px] text-slate-500 ml-1"></i>
            </div>

            <!-- Share & Invite Modal Trigger -->
            <button
              @click="showShareModal = true"
              class="hidden sm:flex h-8 sm:h-9 px-2.5 sm:px-3 text-[10px] font-bold uppercase tracking-widest text-slate-300 bg-white/5 hover:bg-white/10 border border-white/10 rounded-xl transition-all items-center gap-1.5 cursor-pointer"
              title="Share Invite"
            >
              <i class="fas fa-share-alt text-xs"></i>
              <span>Invite</span>
            </button>

            <!-- Materials Modal Trigger -->
            <button
              @click="showMaterialsModal = true"
              class="h-8 sm:h-9 px-2.5 sm:px-3 text-[10px] font-bold uppercase tracking-widest text-slate-300 bg-white/5 hover:bg-white/10 border border-white/10 rounded-xl transition-all flex items-center gap-1.5 cursor-pointer"
              title="Course Materials"
            >
              <i class="fas fa-folder text-xs"></i>
              <span class="hidden sm:inline">Materials</span>
            </button>

            <!-- Replay shelf: this session's recordings, for everyone in class -->
            <button
              v-if="recordingsEnabled"
              @click="openRecordings"
              class="h-8 sm:h-9 px-2.5 sm:px-3 text-[10px] font-bold uppercase tracking-widest text-slate-300 bg-white/5 hover:bg-white/10 border border-white/10 rounded-xl transition-all flex items-center gap-1.5 cursor-pointer"
              title="Session recordings"
            >
              <i
                class="fas fa-record-vinyl text-xs"
                :class="classroomStore.isRecording ? 'text-red-400 animate-pulse' : ''"
              ></i>
              <span class="hidden sm:inline">Recordings</span>
              <span
                v-if="classroomStore.isRecording"
                class="px-1.5 py-0.5 rounded text-[8px] bg-red-500/20 text-red-300 border border-red-500/30 tabular-nums"
              >
                {{ recTimer }}
              </span>
            </button>

            <!-- Lecturer: Automated Attendance Checker Manager -->
            <button
              v-if="canModerate"
              @click="showAttendanceManagerModal = true; loadAttendanceRoster();"
              class="h-8 sm:h-9 px-2.5 sm:px-3 text-[9px] sm:text-[10px] font-black uppercase tracking-widest text-emerald-300 bg-emerald-950/60 hover:bg-emerald-900 border border-emerald-500/30 rounded-xl transition-all flex items-center gap-1.5 cursor-pointer shadow-lg shadow-emerald-950/40"
              title="Automated Attendance Checker Manager & Live Settings"
            >
              <i class="fas fa-shield-alt text-emerald-400"></i>
              <span class="hidden sm:inline">Attendance</span>
              <span
                v-if="autoCheckerActive && checksCompleted < totalTargetChecks"
                class="px-1.5 py-0.5 rounded text-[8px] bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 tabular-nums animate-pulse"
              >
                Auto: {{ checksCompleted }}/{{ totalTargetChecks }}
              </span>
              <span
                v-else-if="checksCompleted >= totalTargetChecks"
                class="px-1.5 py-0.5 rounded text-[8px] bg-indigo-500/20 text-indigo-300 border border-indigo-500/30 tabular-nums"
              >
                Done ({{ checksCompleted }}/{{ totalTargetChecks }})
              </span>
              <span
                v-else
                class="px-1.5 py-0.5 rounded text-[8px] bg-amber-500/20 text-amber-300 border border-amber-500/30 tabular-nums"
              >
                Paused
              </span>
            </button>

            <!-- People Button with Pill Badge -->
            <button
              @click="showParticipants = !showParticipants; if (showParticipants) showChat = false;"
              class="relative h-8 sm:h-9 px-2 sm:px-3 text-[9px] sm:text-[10px] font-black uppercase tracking-widest text-white bg-indigo-600 hover:bg-indigo-500 rounded-xl transition-all flex items-center gap-1.5 sm:gap-2 shadow-lg shadow-indigo-600/20 border border-indigo-400/20 cursor-pointer"
            >
              <i class="fas fa-users text-xs"></i>
              <span class="hidden xs:inline">People</span>
              <span class="bg-white/20 px-1 py-0.5 rounded text-[8px] sm:text-[10px] tabular-nums">
                {{ classroomStore.participants.length }}
              </span>
            </button>
          </div>
        </div>
      </header>

      <!-- Main Video Stage Canvas with Generous Breathing Room -->
      <main class="flex-1 min-h-0 flex gap-3 sm:gap-4 relative overflow-hidden bg-black p-2.5 sm:p-4 pb-20 sm:pb-24">
        <div
          :class="[
            'flex-1 min-h-0 min-w-0 rounded-2xl border border-white/10 overflow-hidden flex flex-col relative shadow-2xl transition-all',
            (showChat || showParticipants || layoutMode === 'fill') ? 'w-auto' : 'w-full max-w-6xl mx-auto'
          ]"
          style="background-color: #121217;"
        >
            <!-- Overlay Header with Speaker Info & Stage Controls -->
            <div class="absolute top-4 left-4 right-4 z-20 flex items-center justify-between pointer-events-none">
              <div class="flex items-center gap-2 pointer-events-auto">
                <span class="bg-black/80 backdrop-blur-md px-3 py-1.5 rounded-full text-xs font-semibold flex items-center gap-2 border border-white/10">
                  <span
                    class="w-2 h-2 rounded-full"
                    :class="classroomStore.isMicEnabled ? 'bg-emerald-500 animate-pulse' : 'bg-slate-500'"
                  ></span>
                  {{ authStore.user?.fullName || 'User' }} ({{ isLecturer ? 'Host' : classroomStore.isCohost ? 'Co-host' : 'Participant' }})
                </span>
                <!-- Server-side recording in progress -->
                <span
                  v-if="classroomStore.isRecording"
                  class="bg-red-600/95 backdrop-blur-md px-2.5 py-1 rounded-full text-[10px] font-black uppercase tracking-widest text-white flex items-center gap-1.5 border border-red-400/40"
                  title="This session is being recorded on the server"
                >
                  <span class="w-2 h-2 rounded-full bg-white animate-pulse"></span>
                  REC {{ recTimer }}
                </span>
                <span
                  v-if="classroomStore.isScreenSharing"
                  class="bg-indigo-600/90 backdrop-blur-md px-2.5 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider text-white border border-indigo-400/30"
                >
                  Screen Share Active
                </span>
                <span
                  v-if="classroomStore.isHandRaised"
                  class="bg-amber-500/90 backdrop-blur-md px-2.5 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider text-white flex items-center gap-1.5"
                >
                  <i class="fas fa-hand-paper text-xs"></i> Hand Raised
                </span>
              </div>

              <!-- Stage Controls (Grid Toggle, Fit/Fill Crop Toggle, PiP) -->
              <div class="flex items-center gap-2 pointer-events-auto">
                <!-- Layout Mode: Grid vs Fill Screen -->
                <button
                  @click="toggleLayoutMode"
                  class="bg-black/80 backdrop-blur-md hover:bg-white/10 text-slate-300 hover:text-white px-2.5 sm:px-3 py-1.5 rounded-full text-xs font-semibold flex items-center gap-1.5 border border-white/10 cursor-pointer transition-colors shadow-lg"
                  :title="layoutMode === 'fill' ? 'Switch to Grid View (Meet style)' : 'Switch to Fill Screen / Spotlight (Meet style)'"
                >
                  <i :class="['text-xs text-indigo-400', layoutMode === 'fill' ? 'fas fa-th-large' : 'fas fa-expand']"></i>
                  <span class="hidden xs:inline">{{ layoutMode === 'fill' ? 'Grid View' : 'Fill Screen' }}</span>
                </button>

                <!-- Video Fit: Crop / Fill vs Contain -->
                <button
                  @click="toggleVideoFit"
                  class="bg-black/80 backdrop-blur-md hover:bg-white/10 text-slate-300 hover:text-white px-2.5 sm:px-3 py-1.5 rounded-full text-xs font-semibold flex items-center gap-1.5 border border-white/10 cursor-pointer transition-colors shadow-lg"
                  :title="videoFitMode === 'cover' ? 'Fit video inside frame (preserve ratio)' : 'Fill frame (crop black borders)'"
                >
                  <i :class="['text-xs text-indigo-400', videoFitMode === 'cover' ? 'fas fa-compress-arrows-alt' : 'fas fa-crop-alt']"></i>
                  <span class="hidden sm:inline">{{ videoFitMode === 'cover' ? 'Fit Video' : 'Fill Video' }}</span>
                </button>

                <!-- Stage Picture-in-Picture Button -->
                <button
                  @click="togglePictureInPicture"
                  class="bg-black/80 backdrop-blur-md hover:bg-white/10 text-slate-300 hover:text-white px-2.5 sm:px-3 py-1.5 rounded-full text-xs font-semibold flex items-center gap-1.5 border border-white/10 cursor-pointer transition-colors shadow-lg"
                  title="Picture-in-Picture: float class window"
                >
                  <i class="fas fa-clone text-xs text-indigo-400"></i>
                  <span class="hidden xs:inline">PiP</span>
                </button>
              </div>
            </div>

            <!-- Floating Reactions Overlay (Matching lite-lms ReactionOverlay) -->
            <div class="absolute inset-0 pointer-events-none overflow-hidden z-[90]">
              <div
                v-for="r in classroomStore.activeReactions"
                :key="r.id"
                class="absolute bottom-24 text-4xl select-none animate-float-up"
                :style="{ left: `${r.x}%` }"
              >
                {{ r.emoji }}
              </div>
            </div>

            <!-- ==================== FILL SCREEN / SPOTLIGHT VIEW (Meet style) ==================== -->
            <div v-if="layoutMode === 'fill'" class="w-full h-full relative flex flex-col justify-between overflow-hidden">
              <!-- Spotlight Main Video (fills entire stage) -->
              <div class="flex-1 w-full h-full relative overflow-hidden bg-black flex items-center justify-center">
                <video
                  v-if="(spotlightParticipant?.isLocal && (classroomStore.isCameraEnabled || classroomStore.isScreenSharing)) || spotlightParticipant?.hasScreenShare || spotlightParticipant?.hasVideo"
                  :ref="(el) => setVideo(spotlightParticipant?.identity || '', el)"
                  :data-identity="spotlightParticipant?.identity"
                  autoplay
                  playsinline
                  muted
                  autoPictureInPicture
                  :class="['w-full h-full bg-black transition-all', videoFitMode === 'cover' ? 'object-cover' : 'object-contain']"
                ></video>

                <!-- Avatar fallback if spotlight has no video -->
                <div v-else class="w-full h-full flex flex-col items-center justify-center gap-3 p-4">
                  <div
                    class="w-24 h-24 sm:w-32 sm:h-32 rounded-3xl bg-[#1845D4] text-white flex items-center justify-center font-bold text-4xl sm:text-5xl shadow-2xl border border-white/10"
                  >
                    {{ (spotlightParticipant?.name || 'U').charAt(0) }}
                  </div>
                  <p class="text-sm font-bold text-slate-200">{{ spotlightParticipant?.name || 'Participant' }}</p>
                </div>

                <!-- Spotlight Tile Footer Overlay -->
                <div class="absolute bottom-3 left-3 sm:left-4 right-3 sm:right-4 z-10 flex items-center justify-between pointer-events-none">
                  <div class="bg-black/80 backdrop-blur-md px-3 py-1.5 rounded-full text-xs font-semibold flex items-center gap-2 border border-white/10 pointer-events-auto">
                    <span
                      class="w-2 h-2 rounded-full"
                      :class="spotlightParticipant?.isSpeaking ? 'bg-emerald-500 animate-pulse' : 'bg-slate-500'"
                    ></span>
                    <span class="text-white font-bold">{{ spotlightParticipant?.name }}{{ spotlightParticipant?.isLocal ? ' (You)' : '' }}</span>
                    <span
                      v-if="spotlightParticipant?.role === 'lecturer' || spotlightParticipant?.role === 'admin'"
                      class="px-1.5 py-0.5 rounded text-[8px] font-black uppercase tracking-wider bg-amber-500/90 text-white"
                    >Host</span>
                    <span
                      v-if="spotlightParticipant?.hasScreenShare"
                      class="px-1.5 py-0.5 rounded text-[8px] font-black uppercase tracking-wider bg-indigo-600/90 text-white"
                    >Presenting</span>
                    <i
                      :class="[
                        'fas text-xs ml-1',
                        spotlightParticipant?.isMicOn ? 'fa-microphone text-emerald-400' : 'fa-microphone-slash text-red-400'
                      ]"
                    ></i>
                  </div>

                  <div class="flex items-center gap-2 pointer-events-auto">
                    <button
                      @click="toggleVideoFit"
                      class="bg-black/80 backdrop-blur-md hover:bg-white/10 text-slate-300 hover:text-white px-2.5 py-1.5 rounded-full text-xs font-semibold flex items-center gap-1.5 border border-white/10 cursor-pointer transition-colors shadow-lg"
                      :title="videoFitMode === 'cover' ? 'Fit video in frame' : 'Fill entire screen'"
                    >
                      <i :class="['text-xs text-indigo-400', videoFitMode === 'cover' ? 'fas fa-compress-arrows-alt' : 'fas fa-crop-alt']"></i>
                      <span class="hidden sm:inline">{{ videoFitMode === 'cover' ? 'Fit' : 'Fill' }}</span>
                    </button>
                    <button
                      @click="layoutMode = 'grid'; pinnedParticipantId = null"
                      class="bg-black/80 backdrop-blur-md hover:bg-white/10 text-slate-300 hover:text-white px-3 py-1.5 rounded-full text-xs font-semibold flex items-center gap-1.5 border border-white/10 cursor-pointer transition-colors shadow-lg"
                      title="Return to balanced grid view"
                    >
                      <i class="fas fa-th-large text-xs text-indigo-400"></i>
                      <span>Grid View</span>
                    </button>
                  </div>
                </div>
              </div>

              <!-- Floating Filmstrip of other participants (Google Meet style) -->
              <div
                v-if="otherParticipants.length > 0"
                class="absolute bottom-16 sm:bottom-4 right-3 sm:right-4 z-30 flex items-center gap-2 max-w-[calc(100%-1.5rem)] sm:max-w-md overflow-x-auto p-1.5 bg-black/75 backdrop-blur-md rounded-2xl border border-white/10 shadow-2xl"
              >
                <div
                  v-for="p in otherParticipants"
                  :key="p.identity"
                  @click="pinParticipant(p.identity)"
                  class="relative w-24 h-16 sm:w-28 sm:h-20 rounded-xl overflow-hidden bg-black border border-white/10 hover:border-indigo-500 transition-all cursor-pointer shrink-0 group"
                  :title="`Click to spotlight ${p.name}`"
                >
                  <video
                    v-if="(p.isLocal && (classroomStore.isCameraEnabled || classroomStore.isScreenSharing)) || p.hasScreenShare || p.hasVideo"
                    :ref="(el) => setVideo(p.identity, el)"
                    :data-identity="p.identity"
                    autoplay
                    playsinline
                    muted
                    :class="['w-full h-full bg-black', videoFitMode === 'cover' ? 'object-cover' : 'object-contain']"
                  ></video>
                  <div v-else class="w-full h-full flex items-center justify-center bg-slate-900">
                    <span class="w-7 h-7 rounded-lg bg-[#1845D4] text-white flex items-center justify-center font-bold text-xs">
                      {{ (p.name || 'U').charAt(0) }}
                    </span>
                  </div>
                  <div class="absolute bottom-0 inset-x-0 px-1 py-0.5 bg-black/80 text-[8px] font-bold text-white truncate flex items-center justify-between">
                    <span class="truncate">{{ p.name }}</span>
                    <i :class="['fas text-[7px]', p.isMicOn ? 'fa-microphone text-emerald-400' : 'fa-microphone-slash text-red-400']"></i>
                  </div>
                  <div class="absolute inset-0 bg-indigo-600/25 opacity-0 group-hover:opacity-100 flex items-center justify-center transition-opacity">
                    <i class="fas fa-thumbtack text-white text-xs drop-shadow"></i>
                  </div>
                </div>
              </div>
            </div>

            <!-- ==================== BALANCED GRID VIEW (Meet style) ==================== -->
            <div v-else class="w-full h-full p-3 sm:p-4 overflow-y-auto">
              <div
                class="grid gap-3 h-full auto-rows-fr"
                :class="gridClass"
              >
                <div
                  v-for="p in classroomStore.participants"
                  :key="p.identity"
                  class="relative rounded-xl overflow-hidden bg-black border transition-all group"
                  :class="p.isSpeaking ? 'border-emerald-400/70 shadow-lg shadow-emerald-500/10' : 'border-white/[0.06]'"
                >
                  <!-- Tile top controls: Pin / Spotlight button (Meet style) -->
                  <div class="absolute top-2 right-2 z-10 flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                    <button
                      @click.stop="pinParticipant(p.identity)"
                      class="p-1.5 rounded-lg bg-black/75 hover:bg-indigo-600 text-white border border-white/10 transition-colors shadow-md cursor-pointer"
                      :title="pinnedParticipantId === p.identity ? 'Unpin' : 'Pin to Fill Screen'"
                    >
                      <i class="fas fa-thumbtack text-[10px]"></i>
                    </button>
                  </div>

                  <!-- Video feed (camera or screen share) -->
                  <video
                    v-if="(p.isLocal && (classroomStore.isCameraEnabled || classroomStore.isScreenSharing)) || p.hasScreenShare || p.hasVideo"
                    :ref="(el) => setVideo(p.identity, el)"
                    :data-identity="p.identity"
                    autoplay
                    playsinline
                    muted
                    autoPictureInPicture
                    :class="['w-full h-full bg-black transition-all', videoFitMode === 'cover' ? 'object-cover' : 'object-contain']"
                  ></video>

                  <!-- Avatar fallback when no video -->
                  <div v-else class="w-full h-full flex flex-col items-center justify-center gap-3 p-4">
                    <div
                      class="w-16 h-16 sm:w-20 sm:h-20 rounded-2xl bg-[#1845D4] text-white flex items-center justify-center font-bold text-2xl sm:text-3xl shadow-2xl border border-white/10"
                    >
                      {{ (p.name || 'U').charAt(0) }}
                    </div>
                    <p class="text-xs font-bold text-slate-300 text-center truncate max-w-full">{{ p.name }}</p>
                  </div>

                  <!-- Tile footer: name, role, mic/hand indicators -->
                  <div class="absolute bottom-0 inset-x-0 p-2 flex items-center justify-between gap-2 bg-gradient-to-t from-black/80 to-transparent">
                    <div class="flex items-center gap-1.5 min-w-0">
                      <span class="text-[10px] font-bold text-white truncate">
                        {{ p.name }}{{ p.isLocal ? ' (You)' : '' }}
                      </span>
                      <span
                        v-if="p.role === 'lecturer' || p.role === 'admin'"
                        class="px-1.5 py-0.5 rounded text-[8px] font-black uppercase tracking-wider bg-amber-500/90 text-white shrink-0"
                      >Host</span>
                      <span
                        v-else-if="isCoHostRow(p)"
                        class="px-1.5 py-0.5 rounded text-[8px] font-black uppercase tracking-wider bg-violet-500/90 text-white shrink-0"
                      >Co-host</span>
                      <span
                        v-if="p.isHandRaised"
                        class="text-amber-400 text-xs shrink-0"
                        title="Hand raised"
                      >✋</span>
                    </div>
                    <i
                      :class="[
                        'fas text-xs shrink-0',
                        p.isMicOn ? 'fa-microphone text-emerald-400' : 'fa-microphone-slash text-red-400'
                      ]"
                    ></i>
                  </div>

                  <!-- Screen-share badge -->
                  <span
                    v-if="p.hasScreenShare"
                    class="absolute top-2 left-2 px-2 py-0.5 rounded bg-indigo-600/90 text-[9px] font-black uppercase tracking-wider text-white"
                  >Presenting</span>
                </div>
              </div>
            </div>
          </div>

        <!-- Desktop Docked Chat Panel -->
        <aside
          v-if="showChat && !isMobile"
          class="w-80 md:w-96 border border-white/10 rounded-2xl flex flex-col shrink-0 shadow-2xl h-full overflow-hidden"
          style="background-color: #121217;"
        >
          <div class="p-4 border-b border-white/[0.08] flex items-center justify-between shrink-0">
            <div class="flex items-center gap-2.5">
              <div class="w-7 h-7 rounded-lg bg-indigo-600/20 flex items-center justify-center text-indigo-400">
                <i class="fas fa-comment-alt text-xs"></i>
              </div>
              <div>
                <h3 class="text-xs font-bold text-white">Classroom Chat</h3>
                <p class="text-[9px] text-slate-400 font-bold uppercase tracking-wider">
                  {{ classroomStore.chatMessages.length }} messages
                </p>
              </div>
            </div>
            <button @click="showChat = false" class="p-1.5 text-slate-400 hover:text-white hover:bg-white/5 rounded-lg transition-colors cursor-pointer">
              <i class="fas fa-times text-xs"></i>
            </button>
          </div>

          <div ref="desktopChatScrollRef" class="flex-1 p-4 overflow-y-auto space-y-3">
            <div v-if="classroomStore.chatMessages.length === 0" class="h-full flex flex-col items-center justify-center text-center p-4 text-slate-500 space-y-1.5">
              <i class="fas fa-comments text-2xl text-slate-600 mb-1"></i>
              <p class="text-xs font-bold text-slate-400">No messages yet</p>
              <p class="text-[10px] text-slate-500">Say hello to the class!</p>
            </div>
            <div
              v-for="msg in classroomStore.chatMessages"
              :key="msg.id"
              class="space-y-1"
            >
              <div class="flex items-center justify-between text-[11px] text-slate-400">
                <span class="font-bold text-slate-300">{{ msg.sender }}</span>
                <span>{{ msg.time }}</span>
              </div>
              <div class="p-2.5 rounded-xl bg-white/5 border border-white/5 text-xs text-slate-200">
                {{ msg.message }}
              </div>
            </div>
          </div>

          <form @submit.prevent="sendMessage" class="p-3 border-t border-white/[0.08] flex gap-2 shrink-0">
            <input
              v-model="newChatMessage"
              placeholder="Type a message..."
              class="flex-1 bg-white/5 border border-white/10 rounded-xl px-3 py-2 text-xs text-white outline-none focus:border-indigo-500"
            />
            <button
              type="submit"
              class="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-bold transition-all cursor-pointer"
            >
              Send
            </button>
          </form>
        </aside>

        <!-- Desktop Docked Participants Panel -->
        <aside
          v-if="showParticipants && !isMobile"
          class="w-80 md:w-96 border border-white/10 rounded-2xl flex flex-col shrink-0 shadow-2xl h-full overflow-hidden"
          style="background-color: #121217;"
        >
          <div class="p-4 border-b border-white/[0.08] flex items-center justify-between shrink-0">
            <div>
              <h3 class="text-xs font-bold text-white flex items-center gap-2">
                <i class="fas fa-users text-xs text-indigo-400"></i>
                Participants
              </h3>
              <p class="text-[9px] text-slate-400 font-bold uppercase tracking-widest mt-0.5">
                {{ classroomStore.participants.length }} in room
              </p>
            </div>
            <button @click="showParticipants = false" class="p-1.5 text-slate-400 hover:text-white hover:bg-white/5 rounded-lg transition-colors cursor-pointer">
              <i class="fas fa-times text-xs"></i>
            </button>
          </div>

          <!-- Moderator Controls Toolbar -->
          <RoomModeration v-if="canModerate" />

          <div class="flex-1 p-4 overflow-y-auto space-y-2">
            <div
              v-for="p in classroomStore.participants"
              :key="p.identity"
              class="p-3 rounded-xl bg-white/5 border border-white/5 flex items-center justify-between"
            >
              <div class="flex items-center gap-2.5 min-w-0">
                <div class="w-8 h-8 rounded-lg bg-[#1845D4] flex items-center justify-center font-bold text-xs text-white shrink-0">
                  {{ p.name.charAt(0) }}
                </div>
                <div class="min-w-0">
                  <p class="text-xs font-bold text-slate-200 truncate">
                    {{ p.name }}<span v-if="p.isLocal" class="text-slate-500 font-medium"> (you)</span>
                  </p>
                  <p class="text-[10px] font-medium uppercase tracking-wider" :class="rosterRoleClass(p)">
                    {{ rosterRoleLabel(p, 'Lecturer') }}
                    <span v-if="p.isHandRaised" class="text-amber-400 ml-1">&#9995; raised</span>
                  </p>
                </div>
              </div>
              <div class="flex items-center gap-2 shrink-0">
                <button
                  v-if="canAppointCohost(p)"
                  @click="toggleCohost(p)"
                  class="p-1.5 rounded-lg transition-all cursor-pointer"
                  :class="isCoHostRow(p) ? 'text-violet-400 hover:bg-violet-500/15' : 'text-slate-500 hover:text-violet-300 hover:bg-white/5'"
                  :title="isCoHostRow(p) ? 'Remove as co-host' : 'Make co-host'"
                >
                  <i class="fas text-[10px]" :class="isCoHostRow(p) ? 'fa-user-minus' : 'fa-user-plus'"></i>
                </button>
                <i :class="['fas text-xs', p.isMicOn ? 'fa-microphone text-emerald-400' : 'fa-microphone-slash text-slate-600']"></i>
                <i v-if="p.isSpeaking" class="fas fa-audio-lines text-emerald-400 text-xs"></i>
              </div>
            </div>
          </div>
        </aside>
      </main>

      <!-- Floating Draggable PiP Video Window (Desktop & Mobile) -->
      <Teleport to="body">
        <div
          v-if="isFloatingPipActive"
          class="fixed z-[10000] select-none rounded-2xl overflow-hidden shadow-2xl border border-white/20 bg-[#121217] flex flex-col"
          :style="{
            left: `${pipPos.x}px`,
            top: `${pipPos.y}px`,
            width: isMobile ? '230px' : '320px',
            height: isMobile ? '150px' : '205px',
            touchAction: 'none'
          }"
        >
          <!-- Draggable Header Handle -->
          <div
            @mousedown="onPipMouseDown"
            @touchstart="onPipTouchStart"
            class="h-7 px-2.5 bg-black/90 border-b border-white/10 flex items-center justify-between cursor-move shrink-0"
          >
            <div class="flex items-center gap-1.5 text-slate-300 min-w-0">
              <i class="fas fa-grip-vertical text-[10px] text-slate-500 shrink-0"></i>
              <span class="text-[10px] font-bold text-white truncate max-w-[130px]">
                {{ activeParticipant?.name || 'Classroom Feed' }}
              </span>
            </div>
            <div class="flex items-center gap-1 shrink-0">
              <button
                @click.stop="triggerNativePip"
                class="p-1 text-slate-400 hover:text-white transition-colors cursor-pointer"
                title="Pop out outside browser"
              >
                <i class="fas fa-external-link-alt text-[9px]"></i>
              </button>
              <button
                @click.stop="isFloatingPipActive = false"
                class="p-1 text-slate-400 hover:text-white transition-colors cursor-pointer"
                title="Close floating box"
              >
                <i class="fas fa-times text-[10px]"></i>
              </button>
            </div>
          </div>

          <!-- Video Canvas / Feed -->
          <div class="flex-1 min-h-0 bg-black relative flex items-center justify-center overflow-hidden">
            <video
              ref="pipVideoRef"
              autoplay
              playsinline
              muted
              class="w-full h-full object-contain bg-black"
            ></video>
            <div v-if="!hasActivePipVideo" class="absolute inset-0 flex flex-col items-center justify-center gap-1 p-2 bg-[#121217]">
              <div class="w-9 h-9 rounded-xl bg-[#1845D4] text-white flex items-center justify-center font-bold text-sm shadow-lg">
                {{ (activeParticipant?.name || 'C').charAt(0) }}
              </div>
              <p class="text-[9px] font-bold text-slate-300 truncate max-w-full text-center">
                {{ activeParticipant?.name || 'Classroom' }}
              </p>
            </div>
          </div>
        </div>
      </Teleport>

      <!-- ==================== MOBILE CHAT WITH INTEGRATED COMPACT PIP VIDEO ==================== -->
      <Teleport to="body">
        <div
          v-if="showChat && isMobile"
          class="fixed inset-0 flex flex-col z-[9999]"
          style="background-color: #0c0d12;"
        >
          <!-- Top Section: Compact Live Classroom Video PiP Stage (36vh) -->
          <div
            v-if="!isFloatingPipActive"
            class="h-[36vh] w-full bg-black relative border-b border-white/10 shrink-0 overflow-hidden flex flex-col"
          >
            <!-- PiP Video Toolbar -->
            <div class="absolute top-2 left-2 right-2 z-20 flex items-center justify-between pointer-events-auto">
              <span class="bg-black/80 backdrop-blur-md px-2.5 py-1 rounded-full text-[10px] font-bold text-white flex items-center gap-1.5 border border-white/10">
                <span class="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse"></span>
                {{ activeParticipant?.name || 'Live Class' }}
              </span>
              <div class="flex items-center gap-1.5">
                <button
                  @click="isFloatingPipActive = true"
                  class="bg-black/80 backdrop-blur-md p-1.5 rounded-lg text-slate-300 hover:text-white border border-white/10 text-xs cursor-pointer"
                  title="Make Floating Draggable PiP"
                >
                  <i class="fas fa-window-restore text-[10px]"></i>
                </button>
                <button
                  @click="triggerNativePip"
                  class="bg-black/80 backdrop-blur-md p-1.5 rounded-lg text-slate-300 hover:text-white border border-white/10 text-xs cursor-pointer"
                  title="Pop out outside browser"
                >
                  <i class="fas fa-external-link-alt text-[10px]"></i>
                </button>
                <button
                  @click="showChat = false"
                  class="bg-black/80 backdrop-blur-md p-1.5 rounded-lg text-slate-300 hover:text-white border border-white/10 text-xs cursor-pointer"
                  title="Close Chat"
                >
                  <i class="fas fa-times text-[10px]"></i>
                </button>
              </div>
            </div>

            <!-- Video Player -->
            <div class="w-full h-full flex items-center justify-center bg-black">
              <video
                ref="mobilePipVideoRef"
                autoplay
                playsinline
                muted
                class="w-full h-full object-contain"
              ></video>
              <div v-if="!hasActivePipVideo" class="flex flex-col items-center justify-center gap-2">
                <div class="w-14 h-14 rounded-2xl bg-[#1845D4] text-white flex items-center justify-center font-bold text-xl shadow-xl">
                  {{ (activeParticipant?.name || 'C').charAt(0) }}
                </div>
                <p class="text-xs font-bold text-slate-300">{{ activeParticipant?.name || 'Classroom Stream' }}</p>
              </div>
            </div>
          </div>

          <!-- Bottom Section: Chat Header, Messages, and Keyboard Input -->
          <div class="flex-1 min-h-0 flex flex-col bg-[#0c0d12]">
            <div
              class="h-12 px-4 border-b border-white/10 flex items-center justify-between shrink-0 shadow-sm"
              style="background-color: #14151f;"
            >
              <div class="flex items-center gap-2.5">
                <div class="w-7 h-7 rounded-lg bg-indigo-600/20 border border-indigo-500/30 flex items-center justify-center text-indigo-400">
                  <i class="fas fa-comment-alt text-xs"></i>
                </div>
                <div>
                  <h2 class="text-xs font-black text-white tracking-wide">Classroom Chat</h2>
                  <p class="text-[9px] text-slate-400 font-bold uppercase tracking-widest">
                    {{ classroomStore.chatMessages.length }} messages
                  </p>
                </div>
              </div>
              <div class="flex items-center gap-2">
                <button
                  v-if="isFloatingPipActive"
                  @click="isFloatingPipActive = false"
                  class="px-2 py-1 text-[10px] font-bold text-indigo-400 bg-indigo-500/10 rounded-lg border border-indigo-500/20 cursor-pointer"
                >
                  Dock Video
                </button>
                <button
                  @click="showChat = false"
                  class="w-8 h-8 rounded-lg bg-white/10 hover:bg-white/20 border border-white/10 flex items-center justify-center text-white transition-colors cursor-pointer"
                  title="Close Chat"
                >
                  <i class="fas fa-times text-xs"></i>
                </button>
              </div>
            </div>

            <!-- Messages List -->
            <div
              ref="mobileChatScrollRef"
              class="flex-1 p-3.5 overflow-y-auto space-y-2.5"
              style="background-color: #0c0d12;"
            >
              <div
                v-if="classroomStore.chatMessages.length === 0"
                class="h-full flex flex-col items-center justify-center text-center p-4 text-slate-500 space-y-1.5"
              >
                <i class="fas fa-comments text-2xl text-slate-600"></i>
                <p class="text-xs font-bold text-slate-400">No messages yet</p>
                <p class="text-[10px] text-slate-500">Send a message to class</p>
              </div>
              <div
                v-for="msg in classroomStore.chatMessages"
                :key="msg.id"
                class="space-y-1"
              >
                <div class="flex items-center justify-between text-[10px] text-slate-400">
                  <span class="font-bold text-slate-300">{{ msg.sender }}</span>
                  <span>{{ msg.time }}</span>
                </div>
                <div class="p-2.5 rounded-xl bg-white/5 border border-white/10 text-xs text-white leading-relaxed shadow-sm">
                  {{ msg.message }}
                </div>
              </div>
            </div>

            <!-- Input Bar -->
            <form
              @submit.prevent="sendMessage"
              class="p-3 border-t border-white/10 flex items-center gap-2 shrink-0"
              style="background-color: #14151f; padding-bottom: max(0.75rem, env(safe-area-inset-bottom));"
            >
              <input
                v-model="newChatMessage"
                placeholder="Type a message..."
                class="flex-1 bg-white/5 border border-white/10 rounded-xl px-3 py-2 text-xs text-white placeholder-slate-500 outline-none focus:border-indigo-500 transition-all"
              />
              <button
                type="submit"
                class="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-bold transition-all shrink-0 cursor-pointer shadow-lg shadow-indigo-600/30"
              >
                Send
              </button>
            </form>
          </div>
        </div>
      </Teleport>

      <!-- Mobile Participants Full-Screen Modal -->
      <Teleport to="body">
        <div
          v-if="showParticipants && isMobile"
          class="fixed inset-0 flex flex-col"
          style="background-color: #0c0d12; z-index: 9999;"
        >
          <!-- Header -->
          <div
            class="h-16 px-4 border-b border-white/10 flex items-center justify-between shrink-0 shadow-lg"
            style="background-color: #14151f;"
          >
            <div class="flex items-center gap-3">
              <div class="w-9 h-9 rounded-xl bg-indigo-600/20 border border-indigo-500/30 flex items-center justify-center text-indigo-400">
                <i class="fas fa-users text-sm"></i>
              </div>
              <div>
                <h2 class="text-sm font-black text-white tracking-wide">Participants</h2>
                <p class="text-[10px] text-slate-400 font-bold uppercase tracking-widest">
                  {{ classroomStore.participants.length }} in room
                </p>
              </div>
            </div>
            <button
              @click="showParticipants = false"
              class="w-9 h-9 rounded-xl bg-white/10 hover:bg-white/20 border border-white/10 flex items-center justify-center text-white transition-colors cursor-pointer"
              title="Close Participants"
            >
              <i class="fas fa-times text-base"></i>
            </button>
          </div>

          <!-- Moderator Controls Toolbar -->
          <RoomModeration v-if="canModerate" />

          <!-- Participants List -->
          <div
            class="flex-1 p-4 overflow-y-auto space-y-2.5"
            style="background-color: #0c0d12;"
          >
            <div
              v-for="p in classroomStore.participants"
              :key="p.identity"
              class="p-3.5 rounded-2xl bg-white/5 border border-white/10 flex items-center justify-between"
            >
              <div class="flex items-center gap-3 min-w-0">
                <div class="w-9 h-9 rounded-xl bg-[#1845D4] flex items-center justify-center font-bold text-xs text-white shrink-0">
                  {{ p.name.charAt(0) }}
                </div>
                <div class="min-w-0">
                  <p class="text-xs font-bold text-slate-200 truncate">
                    {{ p.name }}<span v-if="p.isLocal" class="text-slate-500 font-medium"> (you)</span>
                  </p>
                  <p
                    class="text-[10px] font-medium uppercase tracking-wider mt-0.5"
                    :class="rosterRoleClass(p)"
                  >
                    {{ rosterRoleLabel(p, 'Host') }}
                    <span v-if="p.isHandRaised" class="text-amber-400 ml-1">&#9995; raised</span>
                  </p>
                </div>
              </div>
              <div class="flex items-center gap-2.5 shrink-0">
                <button
                  v-if="canAppointCohost(p)"
                  @click="toggleCohost(p)"
                  class="p-1.5 rounded-lg transition-all cursor-pointer"
                  :class="isCoHostRow(p) ? 'text-violet-400 hover:bg-violet-500/15' : 'text-slate-500 hover:text-violet-300 hover:bg-white/5'"
                  :title="isCoHostRow(p) ? 'Remove as co-host' : 'Make co-host'"
                >
                  <i class="fas text-[10px]" :class="isCoHostRow(p) ? 'fa-user-minus' : 'fa-user-plus'"></i>
                </button>
                <i :class="['fas text-xs', p.isMicOn ? 'fa-microphone text-emerald-400' : 'fa-microphone-slash text-slate-600']"></i>
                <i v-if="p.isSpeaking" class="fas fa-audio-lines text-emerald-400 text-xs"></i>
              </div>
            </div>
          </div>
        </div>
      </Teleport>

      <!-- ==================== STUDENT ATTENDANCE VERIFICATION MODAL ==================== -->
      <Teleport to="body">
        <div
          v-if="activeVerification"
          class="fixed inset-0 z-[99999] flex items-center justify-center p-4 bg-black/85 backdrop-blur-md animate-fade-in"
        >
          <div
            class="relative bg-[#0F172A] border-2 rounded-2xl p-6 sm:p-7 max-w-sm w-full shadow-2xl animate-zoom-in transition-all"
            :class="verificationSecondsRemaining < 10 ? 'border-red-500 shadow-red-500/20' : 'border-blue-500/40 shadow-blue-500/20'"
          >
            <div class="text-center mb-5">
              <div class="relative w-16 h-16 mx-auto mb-3">
                <div
                  class="absolute inset-0 rounded-full"
                  :class="verificationSecondsRemaining < 10 ? 'bg-red-500/25 animate-ping' : 'bg-blue-500/20 animate-ping'"
                />
                <div
                  class="relative w-16 h-16 rounded-full flex items-center justify-center border text-2xl shadow-inner transition-colors"
                  :class="verificationSecondsRemaining < 10 ? 'bg-red-600/30 border-red-500/50 text-red-400' : 'bg-blue-600/30 border-blue-500/40 text-blue-400'"
                >
                  <i class="fas fa-shield-alt"></i>
                </div>
              </div>
              <h2 class="text-xl font-black text-white mb-1 tracking-tight">Attendance Check</h2>
              <p class="text-gray-300 text-xs font-semibold">
                Verification Check <span class="text-blue-400 font-bold">#{{ activeVerification.verificationNumber || 1 }}</span>
              </p>
            </div>

            <!-- Countdown Timer & Progress Bar -->
            <div class="bg-white/5 border border-white/10 rounded-xl p-4 mb-5">
              <div class="flex items-center justify-between mb-1.5">
                <span class="text-[9px] font-black text-gray-400 uppercase tracking-widest">Time Remaining</span>
                <span class="text-[9px] font-bold text-gray-400">Respond before timeout</span>
              </div>
              <div class="flex items-center gap-2">
                <i :class="['fas fa-clock text-sm', verificationSecondsRemaining < 10 ? 'text-red-500 animate-bounce' : 'text-blue-400']"></i>
                <span :class="['text-3xl font-black tabular-nums tracking-tighter font-mono', verificationSecondsRemaining < 10 ? 'text-red-500' : 'text-white']">
                  0:{{ verificationSecondsRemaining.toString().padStart(2, '0') }}
                </span>
              </div>
              <div class="mt-3 h-2 w-full bg-white/10 rounded-full overflow-hidden">
                <div
                  :class="['h-full transition-all duration-1000 rounded-full', verificationSecondsRemaining < 10 ? 'bg-red-500' : 'bg-blue-500']"
                  :style="{ width: `${(verificationSecondsRemaining / (activeVerification.timeLimitSeconds || 30)) * 100}%` }"
                />
              </div>
            </div>

            <!-- Action Button: I AM PRESENT -->
            <button
              type="button"
              @click="submitVerificationResponse"
              :disabled="isSubmittingVerification || verificationSuccess"
              :class="[
                'w-full font-black py-4 rounded-xl transition-all active:scale-95 flex items-center justify-center gap-2 text-sm uppercase tracking-wider text-white shadow-xl cursor-pointer',
                verificationSuccess
                  ? 'bg-emerald-600 border-2 border-emerald-500 shadow-emerald-600/30'
                  : verificationSecondsRemaining < 10
                  ? 'bg-red-600 hover:bg-red-700 border-2 border-red-500 shadow-red-600/30'
                  : 'bg-blue-600 hover:bg-blue-700 border-2 border-blue-500 shadow-blue-600/30'
              ]"
            >
              <i :class="verificationSuccess ? 'fas fa-check-circle text-lg' : 'fas fa-hand-pointer text-base'"></i>
              <span>{{ verificationSuccess ? 'PRESENT RECORDED!' : (isSubmittingVerification ? 'LOGGING IN DATABASE...' : 'I AM PRESENT') }}</span>
            </button>

            <p class="mt-4 text-center text-[10px] text-gray-400 font-bold uppercase tracking-widest opacity-80">
              Podium Anti-Cheat & Attendance Protection Active
            </p>
          </div>
        </div>
      </Teleport>

      <!-- ==================== HOST ATTENDANCE CHECKER CONFIGURATION MODAL ==================== -->
      <Teleport to="body">
        <div
          v-if="showAttendanceManagerModal && canModerate"
          class="fixed inset-0 z-[1000] flex items-center justify-center p-3 sm:p-4 bg-black/80 backdrop-blur-md animate-fade-in"
          @click.self="showAttendanceManagerModal = false"
        >
          <div
            class="relative w-full max-w-lg bg-[#121217] border border-white/10 rounded-2xl flex flex-col max-h-[90vh] shadow-2xl overflow-hidden"
            style="background-color: #121217;"
          >
            <!-- Modal Header -->
            <div class="p-4 sm:p-5 border-b border-white/10 flex items-center justify-between shrink-0 bg-white/[0.02]">
              <div class="flex items-center gap-3">
                <div class="w-10 h-10 rounded-xl bg-emerald-500/20 border border-emerald-500/30 flex items-center justify-center text-emerald-400">
                  <i class="fas fa-shield-alt text-base"></i>
                </div>
                <div>
                  <h2 class="text-sm sm:text-base font-black text-white tracking-wide">Automated Attendance Checker</h2>
                  <p class="text-[10px] text-slate-400 font-bold uppercase tracking-wider">
                    Anti-Cheat Verification Manager &bull; {{ sessionData?.title || 'Classroom' }}
                  </p>
                </div>
              </div>
              <button
                @click="showAttendanceManagerModal = false"
                class="w-8 h-8 rounded-lg bg-white/5 hover:bg-white/10 text-slate-400 hover:text-white flex items-center justify-center transition-colors cursor-pointer"
              >
                <i class="fas fa-times text-xs"></i>
              </button>
            </div>

            <div class="p-4 sm:p-6 overflow-y-auto space-y-5">
              <!-- Live Status Banner -->
              <div class="p-4 rounded-xl border border-white/10 bg-gradient-to-r from-emerald-950/40 via-indigo-950/20 to-transparent space-y-3">
                <div class="flex items-center justify-between">
                  <div class="flex items-center gap-2">
                    <span
                      class="w-2.5 h-2.5 rounded-full"
                      :class="autoCheckerActive ? 'bg-emerald-500 animate-pulse' : 'bg-amber-500'"
                    ></span>
                    <span class="text-xs font-bold text-white uppercase tracking-wider">
                      {{ autoCheckerActive ? 'Auto-Checker: Active' : 'Auto-Checker: Paused' }}
                    </span>
                  </div>
                  <button
                    @click="toggleAutoChecker"
                    :class="[
                      'px-2.5 py-1 rounded-lg text-[10px] font-bold uppercase tracking-wider transition-all cursor-pointer',
                      autoCheckerActive
                        ? 'bg-amber-500/20 text-amber-300 hover:bg-amber-500/30 border border-amber-500/30'
                        : 'bg-emerald-500/20 text-emerald-300 hover:bg-emerald-500/30 border border-emerald-500/30'
                    ]"
                  >
                    {{ autoCheckerActive ? 'Pause Checker' : 'Resume Checker' }}
                  </button>
                </div>

                <!-- Progress Tracker -->
                <div class="space-y-1.5">
                  <div class="flex items-center justify-between text-[11px] text-slate-300 font-bold">
                    <span>Progress: {{ checksCompleted }} of {{ totalTargetChecks }} checks sent</span>
                    <span v-if="autoCheckerActive && checksCompleted < totalTargetChecks" class="text-emerald-400 font-mono">
                      Next check in: {{ formatTimer(nextCheckCountdownSeconds) }}
                    </span>
                    <span v-else-if="checksCompleted >= totalTargetChecks" class="text-indigo-400">
                      All target checks completed
                    </span>
                  </div>
                  <div class="w-full h-2 bg-white/10 rounded-full overflow-hidden">
                    <div
                      class="h-full bg-emerald-500 transition-all duration-500"
                      :style="{ width: `${Math.min(100, (checksCompleted / Math.max(1, totalTargetChecks)) * 100)}%` }"
                    ></div>
                  </div>
                </div>

                <!-- Immediate Trigger Button -->
                <div class="pt-2 flex items-center gap-2">
                  <button
                    @click="triggerAttendanceCheck(checkDurationSeconds)"
                    :disabled="isTriggeringVerification"
                    class="flex-1 py-2.5 px-3 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl text-xs font-bold transition-all flex items-center justify-center gap-2 shadow-lg shadow-emerald-600/30 cursor-pointer disabled:opacity-50"
                  >
                    <i class="fas fa-play text-xs"></i>
                    <span>{{ isTriggeringVerification ? 'Broadcasting...' : 'Trigger Instant Check Now' }}</span>
                  </button>
                </div>
              </div>

              <!-- In-Class Reconfiguration Form -->
              <div class="space-y-4 p-4 rounded-xl border border-white/10 bg-white/[0.02]">
                <div class="flex items-center justify-between">
                  <h3 class="text-xs font-black text-white uppercase tracking-wider flex items-center gap-2">
                    <i class="fas fa-sliders-h text-indigo-400"></i>
                    In-Class Configuration
                  </h3>
                  <span class="text-[9px] text-slate-400 uppercase tracking-widest font-bold">Reconfigure on the fly</span>
                </div>

                <!-- Number of checks (Increase / Add more checks) -->
                <div>
                  <div class="flex items-center justify-between mb-1.5">
                    <label class="text-[11px] font-bold text-slate-300">
                      Total Verification Checks
                    </label>
                    <span class="text-[10px] text-indigo-400 font-bold">
                      Target: {{ totalTargetChecks }} checks
                    </span>
                  </div>
                  <div class="flex items-center gap-2">
                    <input
                      type="number"
                      min="1"
                      max="30"
                      v-model.number="totalTargetChecks"
                      class="flex-1 px-3 py-2 bg-white/5 border border-white/10 rounded-xl text-xs font-bold text-white outline-none focus:border-indigo-500"
                    />
                    <!-- Quick increment buttons -->
                    <button
                      @click="totalTargetChecks += 1"
                      class="px-2.5 py-2 bg-white/10 hover:bg-white/20 text-white rounded-xl text-xs font-bold transition-colors cursor-pointer"
                      title="Add 1 more check"
                    >
                      +1 Check
                    </button>
                    <button
                      @click="totalTargetChecks += 2"
                      class="px-2.5 py-2 bg-white/10 hover:bg-white/20 text-white rounded-xl text-xs font-bold transition-colors cursor-pointer"
                      title="Add 2 more checks"
                    >
                      +2 Checks
                    </button>
                  </div>
                  <p class="text-[10px] text-slate-400 mt-1">
                    Increase this number to schedule more verification checks during the active session.
                  </p>
                </div>

                <!-- Check Timeout / Modal Duration -->
                <div>
                  <div class="flex items-center justify-between mb-1.5">
                    <label class="text-[11px] font-bold text-slate-300">
                      Modal Duration (Seconds it stays up for)
                    </label>
                    <span class="text-[10px] text-indigo-400 font-bold font-mono">
                      {{ checkDurationSeconds }}s per prompt
                    </span>
                  </div>
                  <div class="flex items-center gap-2">
                    <input
                      type="number"
                      min="10"
                      max="180"
                      step="5"
                      v-model.number="checkDurationSeconds"
                      class="flex-1 px-3 py-2 bg-white/5 border border-white/10 rounded-xl text-xs font-bold text-white outline-none focus:border-indigo-500"
                    />
                    <!-- Quick duration pills -->
                    <button
                      v-for="sec in [15, 30, 45, 60]"
                      :key="sec"
                      @click="checkDurationSeconds = sec"
                      :class="[
                        'px-2.5 py-2 rounded-xl text-xs font-bold transition-colors cursor-pointer',
                        checkDurationSeconds === sec ? 'bg-indigo-600 text-white' : 'bg-white/10 text-slate-300 hover:bg-white/20'
                      ]"
                    >
                      {{ sec }}s
                    </button>
                  </div>
                  <p class="text-[10px] text-slate-400 mt-1">
                    How long the prompt remains active on students' screens before expiring.
                  </p>
                </div>

                <!-- Interval between automatic checks -->
                <div>
                  <div class="flex items-center justify-between mb-1.5">
                    <label class="text-[11px] font-bold text-slate-300">
                      Interval Between Checks (Minutes)
                    </label>
                    <span class="text-[10px] text-indigo-400 font-bold font-mono">
                      Every {{ checkIntervalMinutes }} mins
                    </span>
                  </div>
                  <div class="flex items-center gap-2">
                    <input
                      type="number"
                      min="2"
                      max="60"
                      v-model.number="checkIntervalMinutes"
                      class="flex-1 px-3 py-2 bg-white/5 border border-white/10 rounded-xl text-xs font-bold text-white outline-none focus:border-indigo-500"
                    />
                    <button
                      v-for="mins in [5, 10, 15, 20]"
                      :key="mins"
                      @click="checkIntervalMinutes = mins; nextCheckCountdownSeconds = mins * 60"
                      :class="[
                        'px-2.5 py-2 rounded-xl text-xs font-bold transition-colors cursor-pointer',
                        checkIntervalMinutes === mins ? 'bg-indigo-600 text-white' : 'bg-white/10 text-slate-300 hover:bg-white/20'
                      ]"
                    >
                      {{ mins }}m
                    </button>
                  </div>
                </div>

                <!-- Save and sync settings -->
                <div class="pt-2">
                  <button
                    @click="saveAttendanceSettings"
                    :disabled="isSavingSettings"
                    class="w-full py-2.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-bold uppercase tracking-wider transition-all flex items-center justify-center gap-2 cursor-pointer shadow-lg shadow-indigo-600/30"
                  >
                    <i class="fas fa-save text-xs"></i>
                    <span>{{ isSavingSettings ? 'Saving Settings...' : 'Apply & Save Settings' }}</span>
                  </button>
                </div>
              </div>

              <!-- Live Attendance Verification Logs / Roster -->
              <div class="p-4 rounded-xl border border-white/10 bg-white/[0.02] space-y-3">
                <div class="flex items-center justify-between">
                  <h3 class="text-xs font-black text-white uppercase tracking-wider flex items-center gap-2">
                    <i class="fas fa-users-check text-emerald-400"></i>
                    Live Verification Roster
                  </h3>
                  <button
                    @click="loadAttendanceRoster"
                    class="text-[10px] text-indigo-400 hover:text-indigo-300 font-bold flex items-center gap-1 cursor-pointer"
                  >
                    <i class="fas fa-sync-alt" :class="{ 'fa-spin': isLoadingRoster }"></i> Refresh
                  </button>
                </div>

                <div class="grid grid-cols-3 gap-2">
                  <div class="p-2.5 bg-white/5 rounded-xl text-center border border-white/5">
                    <p class="text-[9px] text-slate-400 uppercase tracking-widest font-bold">In Room</p>
                    <p class="text-base font-black text-white mt-0.5">{{ classroomStore.participants.length }}</p>
                  </div>
                  <div class="p-2.5 bg-emerald-500/10 rounded-xl text-center border border-emerald-500/20">
                    <p class="text-[9px] text-emerald-400 uppercase tracking-widest font-bold">Verified</p>
                    <p class="text-base font-black text-emerald-300 mt-0.5">{{ attendanceSummary.present || 0 }}</p>
                  </div>
                  <div class="p-2.5 bg-indigo-500/10 rounded-xl text-center border border-indigo-500/20">
                    <p class="text-[9px] text-indigo-400 uppercase tracking-widest font-bold">Avg Score</p>
                    <p class="text-base font-black text-indigo-300 mt-0.5">{{ attendanceSummary.averageVerificationPercentage || 0 }}%</p>
                  </div>
                </div>

                <!-- Student List (Compact) -->
                <div v-if="attendanceRecords.length > 0" class="max-h-40 overflow-y-auto space-y-1.5 pt-2">
                  <div
                    v-for="rec in attendanceRecords"
                    :key="rec.studentId"
                    class="p-2 rounded-lg bg-white/5 border border-white/5 flex items-center justify-between text-xs"
                  >
                    <div class="flex items-center gap-2 min-w-0">
                      <span class="w-6 h-6 rounded-lg bg-[#1845D4] text-white flex items-center justify-center font-bold text-[10px] shrink-0">
                        {{ rec.studentName.charAt(0) }}
                      </span>
                      <span class="text-slate-200 font-bold truncate">{{ rec.studentName }}</span>
                    </div>
                    <div class="flex items-center gap-2 shrink-0">
                      <span class="text-[10px] font-mono text-slate-400">
                        {{ rec.verificationsCompleted }}/{{ rec.verificationsSent }}
                      </span>
                      <span
                        class="px-1.5 py-0.5 rounded text-[8px] font-black uppercase"
                        :class="rec.present ? 'bg-emerald-500/20 text-emerald-300' : 'bg-red-500/20 text-red-300'"
                      >
                        {{ rec.present ? 'Present' : 'Pending' }}
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </Teleport>

      <!-- Course Materials Modal (Matching lite-lms MaterialsModal) -->
      <div
        v-if="showMaterialsModal"
        class="fixed inset-0 z-[600] flex items-center justify-center p-4 animate-fade-in"
        @click.self="showMaterialsModal = false"
      >
        <div class="absolute inset-0 bg-black/90 backdrop-blur-md" />
        <div class="relative w-full max-w-lg bg-[#121217] border border-white/10 rounded-2xl flex flex-col max-h-[85vh] shadow-2xl overflow-hidden">
          <div class="p-6 border-b border-white/5 flex items-center justify-between">
            <div>
              <h2 class="text-base font-black text-white uppercase tracking-tight">Learning Materials</h2>
              <p class="text-[10px] text-slate-400 mt-0.5 uppercase tracking-widest font-bold">
                {{ courseMaterials.length }} Shared Resources
              </p>
            </div>
            <div class="flex items-center gap-2">
              <button
                v-if="canModerate"
                @click="showMaterialForm = !showMaterialForm; materialError = null"
                class="p-2 text-slate-400 hover:text-indigo-400 bg-white/5 rounded-xl transition-all cursor-pointer"
                title="Share a resource"
              >
                <i class="fas fa-plus"></i>
              </button>
              <button @click="showMaterialsModal = false" class="p-2 text-slate-400 hover:text-white bg-white/5 rounded-xl transition-all cursor-pointer">
                <i class="fas fa-times"></i>
              </button>
            </div>
          </div>

          <!-- Host: share a link with the class -->
          <form
            v-if="showMaterialForm"
            class="px-6 py-4 border-b border-white/5 bg-white/[0.02] space-y-2"
            @submit.prevent="shareMaterial"
          >
            <input
              v-model="materialDraft.title"
              class="w-full bg-white/5 border border-white/10 rounded-xl px-3 py-2 text-xs text-white placeholder-slate-500 outline-none focus:border-indigo-500 transition-all"
              placeholder="Title (e.g. Week 5 Slides)"
              maxlength="255"
            />
            <input
              v-model="materialDraft.url"
              type="url"
              class="w-full bg-white/5 border border-white/10 rounded-xl px-3 py-2 text-xs text-white placeholder-slate-500 outline-none focus:border-indigo-500 transition-all"
              placeholder="https://..."
            />
            <div class="flex items-center gap-2">
              <select
                v-model="materialDraft.type"
                class="bg-white/5 border border-white/10 rounded-xl px-3 py-2 text-xs text-white outline-none focus:border-indigo-500 transition-all cursor-pointer"
              >
                <option value="link">Link</option>
                <option value="pdf">PDF</option>
                <option value="slides">Slides</option>
                <option value="video">Video</option>
                <option value="doc">Document</option>
              </select>
              <button
                type="submit"
                :disabled="isSharingMaterial"
                class="ml-auto px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-60 text-white rounded-xl text-xs font-bold transition-all cursor-pointer"
              >
                {{ isSharingMaterial ? 'Sharing...' : 'Share' }}
              </button>
            </div>
          </form>

          <p v-if="materialError" class="px-6 pt-3 text-[11px] font-bold text-red-400">
            {{ materialError }}
          </p>

          <div class="p-6 overflow-y-auto space-y-3">
            <div
              v-for="mat in courseMaterials"
              :key="mat.id"
              class="flex items-center justify-between p-3.5 bg-white/5 hover:bg-white/[0.08] border border-white/5 rounded-xl transition-all"
            >
              <div class="flex items-center gap-3 min-w-0 flex-1">
                <div class="w-10 h-10 bg-slate-800 rounded-xl flex items-center justify-center shrink-0 border border-white/5 text-indigo-400">
                  <i :class="mat.icon"></i>
                </div>
                <div class="min-w-0 flex-1">
                  <p class="font-bold text-xs text-white truncate" :title="mat.title">{{ mat.title }}</p>
                  <p class="text-[9px] font-bold text-slate-400 uppercase tracking-wider mt-0.5">{{ mat.size }} &bull; {{ mat.type }}</p>
                </div>
              </div>
              <a
                :href="mat.url"
                target="_blank"
                rel="noopener noreferrer"
                class="ml-3 px-3 py-1.5 bg-white/10 hover:bg-indigo-600 text-slate-200 hover:text-white rounded-lg text-xs font-bold transition-all"
              >
                Open
              </a>
              <button
                v-if="canModerate"
                @click="removeMaterial(mat.id)"
                class="ml-2 p-1.5 text-slate-500 hover:text-red-400 bg-white/5 rounded-lg transition-all cursor-pointer"
                title="Remove for everyone"
              >
                <i class="fas fa-trash text-[10px]"></i>
              </button>
            </div>
            <p
              v-if="!courseMaterials.length"
              class="text-[11px] text-slate-500 text-center py-8 uppercase tracking-widest font-bold"
            >
              No resources shared yet
            </p>
          </div>
        </div>
      </div>

      <!-- Session Recordings: the replay shelf. Every frame here was written
           by the server (LiveKit Egress), and playback streams from the API. -->
      <div
        v-if="showRecordingsModal"
        class="fixed inset-0 z-[600] flex items-center justify-center p-4 animate-fade-in"
        @click.self="showRecordingsModal = false"
      >
        <div class="absolute inset-0 bg-black/90 backdrop-blur-md" />
        <div class="relative w-full max-w-2xl bg-[#121217] border border-white/10 rounded-2xl flex flex-col max-h-[85vh] shadow-2xl overflow-hidden">
          <div class="p-6 border-b border-white/5 flex items-center justify-between">
            <div>
              <h2 class="text-base font-black text-white uppercase tracking-tight">Session Recordings</h2>
              <p class="text-[10px] text-slate-400 mt-0.5 uppercase tracking-widest font-bold">
                {{ classroomStore.recordings.length }} Saved
                <span v-if="classroomStore.isRecording" class="text-red-400">
                  &bull; Recording {{ recTimer }}
                </span>
              </p>
            </div>
            <div class="flex items-center gap-2">
              <button
                @click="classroomStore.loadRecordings()"
                class="p-2 text-slate-400 hover:text-indigo-400 bg-white/5 rounded-xl transition-all cursor-pointer"
                title="Refresh"
              >
                <i class="fas fa-sync-alt"></i>
              </button>
              <button
                @click="showRecordingsModal = false"
                class="p-2 text-slate-400 hover:text-white bg-white/5 rounded-xl transition-all cursor-pointer"
              >
                <i class="fas fa-times"></i>
              </button>
            </div>
          </div>

          <p v-if="classroomStore.recordingError" class="px-6 pt-3 text-[11px] font-bold text-red-400">
            {{ classroomStore.recordingError }}
          </p>

          <div class="p-6 overflow-y-auto space-y-4">
            <div
              v-for="rec in classroomStore.recordings"
              :key="rec.id"
              class="p-4 bg-white/5 border border-white/5 rounded-xl space-y-3"
            >
              <div class="flex items-center justify-between gap-3">
                <div class="min-w-0">
                  <p class="font-bold text-xs text-white truncate" :title="rec.title">{{ rec.title }}</p>
                  <p class="text-[9px] font-bold text-slate-400 uppercase tracking-wider mt-0.5">
                    {{ formatWhen(rec.startedAt) }}
                    <template v-if="rec.durationSeconds != null"> &bull; {{ formatDuration(rec.durationSeconds) }}</template>
                    <template v-if="rec.sizeBytes"> &bull; {{ formatSize(rec.sizeBytes) }}</template>
                  </p>
                </div>
                <span
                  class="px-2 py-1 rounded-lg text-[9px] font-black uppercase tracking-widest border shrink-0"
                  :class="recordingStatusChip(rec).cls"
                >
                  {{ recordingStatusChip(rec).text }}
                </span>
              </div>

              <video
                v-if="rec.canPlay && rec.fileUrl"
                :src="rec.fileUrl"
                controls
                preload="metadata"
                class="w-full rounded-xl bg-black border border-white/10 max-h-[40vh]"
              ></video>
              <p
                v-else-if="rec.status === 'failed' || rec.status === 'aborted'"
                class="text-[11px] text-red-400 font-bold"
              >
                {{ rec.error || 'The recording did not finish.' }}
              </p>
              <p v-else class="text-[11px] text-slate-500 font-bold uppercase tracking-wider">
                {{ rec.isLive ? 'Still recording\u2026' : 'Finalising the video file\u2026' }}
              </p>
            </div>

            <p
              v-if="!classroomStore.recordings.length"
              class="text-[11px] text-slate-500 text-center py-8 uppercase tracking-widest font-bold"
            >
              No recordings for this session yet
            </p>
          </div>
        </div>
      </div>

      <!-- Share Invite Modal (Matching lite-lms ShareInviteModal) -->
      <div
        v-if="showShareModal"
        class="fixed inset-0 z-[600] flex items-center justify-center p-4 animate-fade-in"
        @click.self="showShareModal = false"
      >
        <div class="absolute inset-0 bg-black/90 backdrop-blur-md" />
        <div class="relative w-full max-w-sm bg-[#121217] border border-white/10 rounded-2xl p-6 sm:p-7 shadow-2xl space-y-5">
          <div class="flex items-center justify-between pb-3 border-b border-white/10">
            <div>
              <h2 class="text-base font-black text-white">Invite Students</h2>
              <p class="text-[10px] text-slate-400 mt-0.5 font-bold uppercase tracking-widest">Share access code</p>
            </div>
            <button @click="showShareModal = false" class="p-2 text-slate-400 hover:text-white bg-white/5 rounded-xl transition-all cursor-pointer">
              <i class="fas fa-times"></i>
            </button>
          </div>

          <div class="space-y-4">
            <div class="p-4 bg-indigo-600/10 border border-indigo-500/20 rounded-xl text-center">
              <p class="text-[9px] font-black text-indigo-400 uppercase tracking-[0.3em] mb-2">Meeting Code</p>
              <div class="flex items-center justify-center gap-3">
                <span class="text-2xl font-black text-white tracking-[0.2em] font-mono">{{ sessionData?.meetingCode || meetingCode }}</span>
                <button @click="copyCode" class="p-2 bg-indigo-600 hover:bg-indigo-500 rounded-lg transition-all cursor-pointer">
                  <i class="fas fa-copy text-xs text-white"></i>
                </button>
              </div>
            </div>

            <div class="p-4 bg-white/5 border border-white/5 rounded-xl space-y-1.5">
              <p class="text-[9px] font-black text-slate-400 uppercase tracking-[0.3em]">Full Link</p>
              <div class="flex items-center gap-2">
                <span class="text-xs text-slate-300 truncate font-mono flex-1">{{ classroomUrl }}</span>
                <button @click="copyUrl" class="px-3 py-1 bg-white/10 hover:bg-white/20 text-xs rounded-lg transition-all cursor-pointer">
                  Copy
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- What is closed to this participant, and how to get it open -->
      <div
        v-if="lockedBanner"
        class="fixed bottom-[4.75rem] sm:bottom-20 left-1/2 -translate-x-1/2 z-[200] max-w-[92vw] flex items-center gap-2 px-3 py-2 rounded-xl bg-amber-500/15 border border-amber-400/40 text-amber-100 shadow-xl backdrop-blur-sm"
      >
        <i class="fas fa-lock text-[11px] text-amber-300 shrink-0"></i>
        <span class="text-[11px] font-semibold">{{ lockedBanner }}</span>
      </div>

      <!-- Floating Control Bar (Matching CustomControlBar.tsx) -->
      <div class="fixed bottom-3 sm:bottom-4 left-1/2 -translate-x-1/2 z-[200] flex items-center gap-1.5 p-1.5 bg-neutral-900/95 backdrop-blur-sm border border-white/[0.08] rounded-2xl max-w-[98vw] shadow-2xl">
        <!-- Mic Toggle & Device Chevron -->
        <div class="flex items-center">
          <button
            @click="toggleMicTrack"
            :class="[
              'relative h-10 w-10 sm:h-11 sm:w-11 flex items-center justify-center rounded-xl transition-all duration-200 cursor-pointer',
              classroomStore.isMicEnabled ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/20' : 'text-slate-400 hover:bg-white/5',
              !classroomStore.canUse('mic') ? 'ring-1 ring-amber-400/50' : ''
            ]"
            :title="micTitle"
          >
            <i :class="['fas', classroomStore.isMicEnabled ? 'fa-microphone text-sm' : 'fa-microphone-slash text-sm text-red-400']"></i>
            <i
              v-if="!classroomStore.canUse('mic')"
              class="fas fa-lock absolute -top-0.5 -right-0.5 text-[8px] text-amber-400 bg-neutral-900 rounded-full p-[2px]"
            ></i>
          </button>
        </div>

        <span class="w-px h-5 bg-white/10"></span>

        <!-- Camera Toggle -->
        <div class="flex items-center">
          <button
            @click="toggleCameraTrack"
            :class="[
              'relative h-10 w-10 sm:h-11 sm:w-11 flex items-center justify-center rounded-xl transition-all duration-200 cursor-pointer',
              classroomStore.isCameraEnabled ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/20' : 'text-slate-400 hover:bg-white/5',
              !classroomStore.canUse('camera') ? 'ring-1 ring-amber-400/50' : ''
            ]"
            :title="cameraTitle"
          >
            <i :class="['fas', classroomStore.isCameraEnabled ? 'fa-video text-sm' : 'fa-video-slash text-sm text-red-400']"></i>
            <i
              v-if="!classroomStore.canUse('camera')"
              class="fas fa-lock absolute -top-0.5 -right-0.5 text-[8px] text-amber-400 bg-neutral-900 rounded-full p-[2px]"
            ></i>
          </button>
        </div>

        <span class="w-px h-5 bg-white/10"></span>

        <!-- Screen Share Toggle -->
        <button
          @click="toggleScreenShareTrack"
          :class="[
            'relative h-10 w-10 sm:h-11 sm:w-11 flex items-center justify-center rounded-xl transition-all duration-200 cursor-pointer',
            classroomStore.isScreenSharing ? 'bg-emerald-600/30 text-emerald-400 ring-1 ring-emerald-500/40' : 'text-slate-400 hover:bg-white/5',
            !classroomStore.canUse('screen') ? 'ring-1 ring-amber-400/50' : ''
          ]"
          :title="screenTitle"
        >
          <i class="fas fa-desktop text-sm"></i>
          <i
            v-if="!classroomStore.canUse('screen')"
            class="fas fa-lock absolute -top-0.5 -right-0.5 text-[8px] text-amber-400 bg-neutral-900 rounded-full p-[2px]"
          ></i>
        </button>

        <!-- Record: server-side MP4, only when the feature is on -->
        <template v-if="canRecord">
          <span class="w-px h-5 bg-white/10"></span>
          <button
            @click="toggleRecording"
            :class="[
              'h-10 w-10 sm:h-11 sm:w-11 relative flex items-center justify-center rounded-xl transition-all duration-200 cursor-pointer',
              classroomStore.isRecording
                ? 'bg-red-600 text-white shadow-lg shadow-red-600/30'
                : 'text-slate-400 hover:bg-white/5 hover:text-red-400'
            ]"
            :title="classroomStore.isRecording ? 'Stop recording' : 'Record this session'"
          >
            <i v-if="classroomStore.isRecording" class="fas fa-stop text-sm"></i>
            <i v-else class="fas fa-circle text-[11px] text-red-500"></i>
            <span
              v-if="classroomStore.isRecording"
              class="absolute -top-1 -right-1 w-2.5 h-2.5 rounded-full bg-red-400 border-2 border-neutral-900 animate-pulse"
            ></span>
          </button>
        </template>

        <!-- Merged Reactions & Raise Hand (Google Meet style) -->
        <div class="relative">
          <button
            @click="showReactions = !showReactions"
            :class="[
              'h-10 w-10 sm:h-11 sm:w-11 relative flex items-center justify-center rounded-xl transition-all duration-200 cursor-pointer',
              hasRaisedHand ? 'bg-amber-500 text-white shadow-lg shadow-amber-500/30' : (showReactions ? 'bg-slate-700 text-white' : 'text-slate-400 hover:bg-white/5')
            ]"
            :title="hasRaisedHand ? 'Hand Raised (click for reactions & hand options)' : 'Reactions & Raise Hand'"
          >
            <i :class="hasRaisedHand ? 'fas fa-hand-paper text-sm' : 'far fa-smile text-sm'"></i>
            <span
              v-if="hasRaisedHand"
              class="absolute -top-1 -right-1 w-2.5 h-2.5 rounded-full bg-amber-400 border-2 border-neutral-900"
            ></span>
          </button>

          <!-- Merged Sub Pop-up (Raise Hand + Emoji Reactions) -->
          <div
            v-if="showReactions"
            class="absolute left-1/2 -translate-x-1/2 p-2.5 bg-[#121217] border border-white/15 rounded-2xl flex flex-col gap-2 shadow-2xl z-[300] min-w-[260px]"
            style="bottom: calc(100% + 14px);"
          >
            <!-- Hand Raise / Lower Toggle -->
            <button
              @click="toggleRaiseHand"
              :class="[
                'w-full py-2 px-3 rounded-xl flex items-center justify-center gap-2 text-xs font-bold transition-all cursor-pointer',
                hasRaisedHand
                  ? 'bg-amber-500 hover:bg-amber-600 text-white shadow-lg shadow-amber-500/25'
                  : 'bg-white/5 hover:bg-white/10 text-slate-200 hover:text-white border border-white/5'
              ]"
            >
              <i class="fas fa-hand-paper" :class="hasRaisedHand ? 'text-white' : 'text-amber-400'"></i>
              <span>{{ hasRaisedHand ? 'Lower Hand' : 'Raise Hand' }}</span>
            </button>

            <div class="h-px bg-white/10"></div>

            <!-- Emoji Reactions Row -->
            <div class="flex items-center justify-between gap-1">
              <button
                v-for="emoji in emojis"
                :key="emoji"
                @click="triggerReaction(emoji)"
                class="text-xl p-1.5 hover:scale-125 transition-transform cursor-pointer rounded-lg hover:bg-white/5"
              >
                {{ emoji }}
              </button>
            </div>
          </div>
        </div>

        <!-- Chat Toggle -->
        <button
          @click="showChat = !showChat; if (showChat) showParticipants = false;"
          :class="[
            'h-10 w-10 sm:h-11 sm:w-11 flex items-center justify-center rounded-xl transition-all duration-200 cursor-pointer',
            showChat ? 'bg-indigo-600 text-white' : 'text-slate-400 hover:bg-white/5'
          ]"
          title="Toggle Chat"
        >
          <i class="fas fa-comment-alt text-sm"></i>
        </button>

        <span class="w-px h-5 bg-white/10"></span>

        <!-- Leave Button -->
        <button
          @click="confirmLeave"
          class="h-10 w-10 sm:h-11 sm:w-11 flex items-center justify-center rounded-xl bg-red-500/20 hover:bg-red-500 text-red-400 hover:text-white transition-all duration-200 cursor-pointer"
          title="Leave Classroom"
        >
          <i class="fas fa-phone-slash text-sm"></i>
        </button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useClassroomStore, type MediaKind } from '../stores/classroom';
import { useAuthStore } from '../stores/auth';
import { useFeaturesStore } from '../stores/features';
import { api } from '../api/client';
import RoomModeration from '../components/RoomModeration.vue';

const route = useRoute();
const router = useRouter();
const classroomStore = useClassroomStore();
const authStore = useAuthStore();
const featuresStore = useFeaturesStore();

const sessionId = route.params.id as string;
const sessionData = ref<any>(null);
const meetingCode = ref('POD-' + sessionId.slice(0, 6).toUpperCase());

// Core View & Modal States (Declared first to avoid TDZ ReferenceError in watchers)
const isLoading = ref(true);
const isWaitingLobby = ref(false);
const isScheduledFuture = ref(false);
const isActivating = ref(false);
const insufficientBalance = ref<{ needed: number; have: number } | null>(null);

const showChat = ref(false);
const showParticipants = ref(false);
const showMaterialsModal = ref(false);
const showRecordingsModal = ref(false);
const showShareModal = ref(false);
const showReactions = ref(false);

const newChatMessage = ref('');

// --- permission-gated device controls -----------------------------------
// The room's moderation state lives in the store (backed by the session row).
// These wrappers decide whether a click toggles the device or asks the host.
function guarded(kind: MediaKind, toggle: () => Promise<void>) {
  if (!classroomStore.canUse(kind)) {
    void classroomStore.requestPermission(kind);
    return;
  }
  void toggle();
}

function toggleMicTrack() {
  guarded('mic', () => classroomStore.toggleMic());
}

const deviceTitle = (
  kind: MediaKind,
  on: boolean,
  freeLabel: string,
  offLabel: string,
  askLabel: string
) => {
  if (classroomStore.requestedKinds[kind]) return 'Waiting for the host...';
  if (!classroomStore.canUse(kind)) return askLabel;
  return on ? freeLabel : offLabel;
};

const micTitle = computed(() =>
  deviceTitle('mic', classroomStore.isMicEnabled, 'Mute Microphone', 'Unmute Microphone', 'Ask the host to unmute you')
);
const cameraTitle = computed(() =>
  deviceTitle('camera', classroomStore.isCameraEnabled, 'Turn Off Camera', 'Turn On Camera', 'Ask for camera permission')
);
const screenTitle = computed(() =>
  deviceTitle('screen', classroomStore.isScreenSharing, 'Stop sharing', 'Share Screen', 'Ask for screen share permission')
);

/** Media this participant is locked out of right now. */
const lockedKinds = computed<MediaKind[]>(() => classroomStore.lockedKinds());

const lockedBanner = computed(() => {
  const kinds = lockedKinds.value;
  if (!kinds.length) return '';
  const names = kinds.map((k) => (k === 'screen' ? 'screen share' : k));
  const list = names.length > 1 ? `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}` : names[0];
  const waiting = (Object.keys(classroomStore.requestedKinds) as MediaKind[]).filter(
    (k) => classroomStore.requestedKinds[k]
  );
  if (waiting.length) {
    const what = waiting.map((k) => (k === 'screen' ? 'screen share' : k)).join(', ');
    return `Waiting for the host to let you use the ${what}...`;
  }
  if (classroomStore.isMutedAll) {
    return 'The host muted everyone. Click your microphone to ask for permission.';
  }
  return `Your ${list} ${names.length > 1 ? 'are' : 'is'} locked by the host. Click to ask for permission.`;
});

// Mobile detection (screens narrower than 768px use mobile full-screen modal with PiP)
const isMobile = ref(typeof window !== 'undefined' ? window.innerWidth < 768 : false);
function onWindowResize() {
  isMobile.value = window.innerWidth < 768;
}

// Picture-in-Picture State (Desktop & Mobile)
const isFloatingPipActive = ref(false);
const isNativePipActive = ref(false);
const pipPos = ref({ x: typeof window !== 'undefined' ? Math.max(16, window.innerWidth - 340) : 20, y: 75 });
const isDraggingPip = ref(false);
const pipDragStart = { x: 0, y: 0, startX: 0, startY: 0 };
const pipVideoRef = ref<HTMLVideoElement | null>(null);
const mobilePipVideoRef = ref<HTMLVideoElement | null>(null);
const desktopChatScrollRef = ref<HTMLElement | null>(null);
const mobileChatScrollRef = ref<HTMLElement | null>(null);

function scrollChatToBottom() {
  nextTick(() => {
    if (desktopChatScrollRef.value) {
      desktopChatScrollRef.value.scrollTop = desktopChatScrollRef.value.scrollHeight;
    }
    if (mobileChatScrollRef.value) {
      mobileChatScrollRef.value.scrollTop = mobileChatScrollRef.value.scrollHeight;
    }
  });
}

watch(
  [() => classroomStore.chatMessages.length, () => showChat.value],
  () => {
    scrollChatToBottom();
  }
);

// Grid vs Fill Screen Layout (Google Meet style)
const layoutMode = ref<'grid' | 'fill'>('grid');
const videoFitMode = ref<'cover' | 'contain'>('cover');
const pinnedParticipantId = ref<string | null>(null);

const spotlightParticipant = computed(() => {
  if (pinnedParticipantId.value) {
    const found = classroomStore.participants.find(p => p.identity === pinnedParticipantId.value);
    if (found) return found;
  }
  return activeParticipant.value || classroomStore.participants[0] || null;
});

const otherParticipants = computed(() => {
  if (!spotlightParticipant.value) return [];
  return classroomStore.participants.filter(p => p.identity !== spotlightParticipant.value?.identity);
});

function toggleLayoutMode() {
  layoutMode.value = layoutMode.value === 'grid' ? 'fill' : 'grid';
  if (layoutMode.value === 'fill' && !pinnedParticipantId.value && activeParticipant.value) {
    pinnedParticipantId.value = activeParticipant.value.identity;
  }
}

function pinParticipant(identity: string) {
  if (pinnedParticipantId.value === identity && layoutMode.value === 'fill') {
    pinnedParticipantId.value = null;
    layoutMode.value = 'grid';
  } else {
    pinnedParticipantId.value = identity;
    layoutMode.value = 'fill';
  }
}

function toggleVideoFit() {
  videoFitMode.value = videoFitMode.value === 'cover' ? 'contain' : 'cover';
}

const activeParticipant = computed(() => {
  const list = classroomStore.participants;
  if (!list || list.length === 0) return null;
  // 1. Participant presenting screen
  const presenting = list.find((p) => p.hasScreenShare);
  if (presenting) return presenting;
  // 2. Active speaker
  const speaking = list.find((p) => p.isSpeaking && (p.hasVideo || p.isLocal));
  if (speaking) return speaking;
  // 3. Lecturer or host
  const host = list.find((p) => (p.role === 'lecturer' || p.role === 'admin') && (p.hasVideo || p.isLocal));
  if (host) return host;
  // 4. Any participant with video enabled
  const withVideo = list.find((p) => p.hasVideo || (p.isLocal && (classroomStore.isCameraEnabled || classroomStore.isScreenSharing)));
  if (withVideo) return withVideo;
  return list[0];
});

const hasActivePipVideo = computed(() => {
  const p = activeParticipant.value;
  if (!p) return false;
  if (p.isLocal) return classroomStore.isCameraEnabled || classroomStore.isScreenSharing;
  return p.hasVideo || p.hasScreenShare;
});

function syncPipStreams() {
  const p = activeParticipant.value;
  if (!p) return;
  
  let stream: MediaStream | null = null;
  if (p.isLocal && classroomStore.localStream) {
    stream = classroomStore.localStream;
  } else {
    const mainVideos = Array.from(document.querySelectorAll('main video')) as HTMLVideoElement[];
    const matching = mainVideos.find((v) => (v as any).dataset?.identity === p.identity) || mainVideos[0];
    if (matching && matching.srcObject) {
      stream = matching.srcObject as MediaStream;
    }
  }

  if (stream) {
    if (pipVideoRef.value && pipVideoRef.value.srcObject !== stream) {
      pipVideoRef.value.srcObject = stream;
      pipVideoRef.value.play().catch(() => {});
    }
    if (mobilePipVideoRef.value && mobilePipVideoRef.value.srcObject !== stream) {
      mobilePipVideoRef.value.srcObject = stream;
      mobilePipVideoRef.value.play().catch(() => {});
    }
  }
}

watch(
  [activeParticipant, () => classroomStore.localStream, () => showChat.value, () => isFloatingPipActive.value],
  () => {
    nextTick(() => {
      syncPipStreams();
    });
  }
);

// Mouse Dragging for in-app floating window
function onPipMouseDown(e: MouseEvent) {
  isDraggingPip.value = true;
  pipDragStart.x = e.clientX;
  pipDragStart.y = e.clientY;
  pipDragStart.startX = pipPos.value.x;
  pipDragStart.startY = pipPos.value.y;
  window.addEventListener('mousemove', onPipMouseMove);
  window.addEventListener('mouseup', onPipMouseUp);
}

function onPipMouseMove(e: MouseEvent) {
  if (!isDraggingPip.value) return;
  const dx = e.clientX - pipDragStart.x;
  const dy = e.clientY - pipDragStart.y;
  pipPos.value.x = Math.max(8, Math.min(window.innerWidth - (isMobile.value ? 240 : 330), pipDragStart.startX + dx));
  pipPos.value.y = Math.max(8, Math.min(window.innerHeight - (isMobile.value ? 160 : 220), pipDragStart.startY + dy));
}

function onPipMouseUp() {
  isDraggingPip.value = false;
  window.removeEventListener('mousemove', onPipMouseMove);
  window.removeEventListener('mouseup', onPipMouseUp);
}

// Touch Dragging for in-app floating window on mobile
function onPipTouchStart(e: TouchEvent) {
  const t = e.touches[0];
  isDraggingPip.value = true;
  pipDragStart.x = t.clientX;
  pipDragStart.y = t.clientY;
  pipDragStart.startX = pipPos.value.x;
  pipDragStart.startY = pipPos.value.y;
  window.addEventListener('touchmove', onPipTouchMove, { passive: false });
  window.addEventListener('touchend', onPipTouchEnd);
}

function onPipTouchMove(e: TouchEvent) {
  if (!isDraggingPip.value) return;
  e.preventDefault();
  const t = e.touches[0];
  const dx = t.clientX - pipDragStart.x;
  const dy = t.clientY - pipDragStart.y;
  pipPos.value.x = Math.max(8, Math.min(window.innerWidth - 240, pipDragStart.startX + dx));
  pipPos.value.y = Math.max(8, Math.min(window.innerHeight - 160, pipDragStart.startY + dy));
}

function onPipTouchEnd() {
  isDraggingPip.value = false;
  window.removeEventListener('touchmove', onPipTouchMove);
  window.removeEventListener('touchend', onPipTouchEnd);
}

// Native OS Picture-in-Picture Trigger
async function triggerNativePip() {
  try {
    if (document.pictureInPictureElement) {
      await document.exitPictureInPicture();
      isNativePipActive.value = false;
      return;
    }
    const videos = Array.from(document.querySelectorAll('video')) as HTMLVideoElement[];
    const candidate =
      pipVideoRef.value ||
      mobilePipVideoRef.value ||
      videos.find((v) => v.srcObject && (v.srcObject as MediaStream).active && v.readyState >= 2) ||
      videos[0];

    if (candidate) {
      if ('requestPictureInPicture' in candidate) {
        await candidate.requestPictureInPicture();
        isNativePipActive.value = true;
      } else if ('webkitSetPresentationMode' in candidate) {
        (candidate as any).webkitSetPresentationMode('picture-in-picture');
        isNativePipActive.value = true;
      } else {
        isFloatingPipActive.value = true;
      }
    } else {
      isFloatingPipActive.value = true;
    }
  } catch (err: any) {
    console.warn('Native PiP could not open, falling back to in-app floating box:', err);
    isFloatingPipActive.value = true;
  }
}

function togglePictureInPicture() {
  if (document.pictureInPictureElement) {
    document.exitPictureInPicture().catch(() => {});
    isNativePipActive.value = false;
    return;
  }
  triggerNativePip();
}

function handleVisibilityChange() {
  if (document.hidden && classroomStore.isConnected && !document.pictureInPictureElement) {
    triggerNativePip().catch(() => {});
  }
}

// Register a participant's <video> element with the store so the real
// LiveKit track gets attached/detached imperatively.
function setVideo(identity: string, el: any) {
  classroomStore.setVideoElement(identity, el as HTMLVideoElement | null);
}

// Responsive grid columns based on how many tiles are rendered.
const gridClass = computed(() => {
  const n = classroomStore.participants.length || 1;
  if (n <= 1) return 'grid-cols-1';
  if (n === 2) return 'grid-cols-1 sm:grid-cols-2';
  if (n <= 4) return 'grid-cols-2';
  if (n <= 9) return 'grid-cols-2 sm:grid-cols-3';
  return 'grid-cols-2 sm:grid-cols-3 lg:grid-cols-4';
});
// Real hand-raise state now lives in the classroom store (driven by LiveKit data messages).
const hasRaisedHand = computed(() => classroomStore.isHandRaised);
const sessionTimer = ref('00:00:00');
let timerInterval: any = null;
let secondsElapsed = 0;

// Emojis matching lite-lms ReactionModal
const emojis = ['👍', '👏', '❤️', '🔥', '🎉', '😂', '😮', '🤔'];

// Attendance Verification States
const showAttendanceManagerModal = ref(false);
const autoCheckerActive = ref(true);
const totalTargetChecks = ref(3);
const checkDurationSeconds = ref(30);
const checkIntervalMinutes = ref(15);
const checksCompleted = ref(0);
const nextCheckCountdownSeconds = ref(900);
const isSavingSettings = ref(false);
const isLoadingRoster = ref(false);
const attendanceSummary = ref<any>({ present: 0, averageVerificationPercentage: 0 });
const attendanceRecords = ref<any[]>([]);
const verificationSuccess = ref(false);
let autoCheckerInterval: any = null;

const activeVerification = ref<any>(null);
const verificationSecondsRemaining = ref(30);
const isTriggeringVerification = ref(false);
const isSubmittingVerification = ref(false);
let countdownInterval: any = null;

function formatTimer(totalSeconds: number) {
  const m = Math.floor(Math.max(0, totalSeconds) / 60);
  const s = Math.max(0, totalSeconds) % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

// Audio chime for attendance verification alerts
function playVerificationChime() {
  try {
    const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
    if (!AudioContextClass) return;
    const ctx = new AudioContextClass();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(587.33, ctx.currentTime);
    osc.frequency.setValueAtTime(880, ctx.currentTime + 0.15);
    gain.gain.setValueAtTime(0.2, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.6);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.6);
  } catch {}
}

const classroomUrl = computed(() => {
  return `${window.location.origin}/classroom/${sessionId}`;
});

const isLecturer = computed(() => {
  return (
    authStore.user?.role === 'admin' ||
    authStore.user?.role === 'lecturer' ||
    sessionData.value?.hostId === authStore.user?.id
  );
});

/**
 * Who is running the room: the session's own host/lecturer/admin, plus anyone
 * they have made a co-host (answered by the server in loadOwnPermissions).
 *
 * Moderation, materials and attendance checks sit here. Starting and ending
 * the class stay on `isLecturer` alone -- a co-host is trusted with the
 * controls, not with the room itself.
 */
const canModerate = computed(() => isLecturer.value || classroomStore.isCohost);

type RosterRow = { identity: string; role?: string; isLocal?: boolean };

const isCoHostRow = (p: RosterRow) => classroomStore.coHostIds.includes(p.identity);

/**
 * Roster role text. Co-host is a per-session appointment, so it comes from the
 * co-hosts endpoint rather than from the participant's LiveKit role -- which
 * still says "student" for someone running the room.
 */
function rosterRoleLabel(p: RosterRow, authority: string) {
  if (p.role === 'lecturer' || p.role === 'admin') return authority;
  return isCoHostRow(p) ? 'Co-host' : 'Student';
}

function rosterRoleClass(p: RosterRow) {
  if (p.role === 'lecturer' || p.role === 'admin') return 'text-amber-400';
  return isCoHostRow(p) ? 'text-violet-400' : 'text-slate-400';
}

/**
 * Only the session's own authority may appoint a co-host, and never to
 * themselves -- the server enforces exactly the same rule, this just keeps the
 * button off rows where it could only fail.
 */
const canAppointCohost = (p: RosterRow) =>
  isLecturer.value &&
  !p.isLocal &&
  p.role !== 'lecturer' &&
  p.role !== 'admin' &&
  p.identity !== sessionData.value?.hostId;

async function toggleCohost(p: RosterRow) {
  if (isCoHostRow(p)) {
    await classroomStore.removeCohost(p.identity);
  } else {
    await classroomStore.addCohost(p.identity);
  }
}

// --- recording ------------------------------------------------------------
//
// The MP4 is written by the server (LiveKit Egress). Everything below only
// asks it to start, stop or report -- this client can no more create a
// recording than it can invent an attendance record.

/** Whoever may moderate the room may record it, but only if recording is on. */
const canRecord = computed(() => canModerate.value && featuresStore.isEnabled('recordings'));

/** Replays are for everyone: writing them down is only worth it if people can watch. */
const recordingsEnabled = computed(() => featuresStore.isEnabled('recordings'));

/** Elapsed time for the REC chip, ticking off the store's live clock. */
const recTimer = computed(() => {
  const total = classroomStore.recordingSeconds;
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
});

async function toggleRecording() {
  if (classroomStore.isRecording) {
    await classroomStore.stopRecording();
  } else {
    await classroomStore.startRecording();
  }
}

function openRecordings() {
  showRecordingsModal.value = true;
  void classroomStore.loadRecordings();
}

function formatDuration(seconds: number | null) {
  if (seconds == null) return '—';
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function formatSize(bytes: number | null) {
  if (bytes == null) return '';
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatWhen(iso: string | null) {
  if (!iso) return '';
  return new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
}

function recordingStatusChip(r: { status: string; error?: string | null }) {
  if (r.status === 'complete') return { text: 'Ready', cls: 'text-emerald-400 bg-emerald-500/10 border-emerald-400/30' };
  if (r.status === 'failed' || r.status === 'aborted') return { text: 'Failed', cls: 'text-red-400 bg-red-500/10 border-red-400/30' };
  return { text: 'Processing', cls: 'text-amber-400 bg-amber-500/10 border-amber-400/30' };
}

// Watch incoming verification broadcasted over LiveKit Data Channel
watch(
  () => classroomStore.incomingVerification,
  (newVerif) => {
    if (newVerif && !isLecturer.value) {
      activeVerification.value = newVerif;
      verificationSecondsRemaining.value = newVerif.timeLimitSeconds || 30;
      verificationSuccess.value = false;
      playVerificationChime();
      try {
        if ('vibrate' in navigator) (navigator as any).vibrate([200, 100, 200]);
      } catch {}
      if (countdownInterval) clearInterval(countdownInterval);
      countdownInterval = setInterval(() => {
        if (verificationSecondsRemaining.value > 0) {
          verificationSecondsRemaining.value--;
        } else {
          activeVerification.value = null;
          clearInterval(countdownInterval);
        }
      }, 1000);
    }
  }
);

// Course materials are stored per session (apps/storage) and shared by the host.
// The renderer wraps a collection as { documents: [...] }, so this has to be
// read with the collection helper -- getDocument() returns the envelope itself
// and the list would stay empty forever.
const courseMaterials = ref<any[]>([]);
const showMaterialForm = ref(false);
const isSharingMaterial = ref(false);
const materialError = ref<string | null>(null);
const materialDraft = ref({ title: '', url: '', type: 'link' });

async function loadMaterials() {
  try {
    const res: any = await api.getDocuments<any>(
      `/api/v1/classrooms/sessions/${sessionId}/materials/`
    );
    courseMaterials.value = Array.isArray(res) ? res : res?.documents ?? [];
  } catch {
    // No access to this session's materials -- show an empty list, never a
    // fabricated one.
    courseMaterials.value = [];
  }
}

// The list is loaded on mount, but a classmate may share something minutes
// later: re-fetch every time the modal opens so nobody stares at a stale list.
watch(showMaterialsModal, (open) => {
  if (open) void loadMaterials();
});

async function shareMaterial() {
  materialError.value = null;
  const title = materialDraft.value.title.trim();
  const url = materialDraft.value.url.trim();
  if (!title || !url) {
    materialError.value = 'Add a title and a link.';
    return;
  }
  isSharingMaterial.value = true;
  try {
    await api.postDocument(`/api/v1/classrooms/sessions/${sessionId}/materials/`, {
      title,
      url,
      type: materialDraft.value.type,
    });
    materialDraft.value = { title: '', url: '', type: 'link' };
    showMaterialForm.value = false;
    await loadMaterials();
  } catch (err: any) {
    materialError.value = err?.message || 'Could not share that resource.';
  } finally {
    isSharingMaterial.value = false;
  }
}

async function removeMaterial(materialId: string) {
  materialError.value = null;
  if (!window.confirm('Remove this resource for everyone in the class?')) return;
  try {
    await api.deleteDocument(
      `/api/v1/classrooms/sessions/${sessionId}/materials/${materialId}/`
    );
    courseMaterials.value = courseMaterials.value.filter((m) => m.id !== materialId);
  } catch (err: any) {
    materialError.value = err?.message || 'Could not remove that resource.';
  }
}

onMounted(() => {
  window.addEventListener('resize', onWindowResize);
  document.addEventListener('visibilitychange', handleVisibilityChange);

  // Start session live elapsed timer immediately so it never freezes
  if (timerInterval) clearInterval(timerInterval);
  timerInterval = setInterval(() => {
    secondsElapsed++;
    const h = String(Math.floor(secondsElapsed / 3600)).padStart(2, '0');
    const m = String(Math.floor((secondsElapsed % 3600) / 60)).padStart(2, '0');
    const s = String(secondsElapsed % 60).padStart(2, '0');
    sessionTimer.value = `${h}:${m}:${s}`;
  }, 1000);

  initClassroom();
  loadMaterials();
  classroomStore.loadChatHistory(sessionId);
});

onUnmounted(async () => {
  window.removeEventListener('resize', onWindowResize);
  document.removeEventListener('visibilitychange', handleVisibilityChange);
  if (timerInterval) clearInterval(timerInterval);
  if (countdownInterval) clearInterval(countdownInterval);
  if (autoCheckerInterval) clearInterval(autoCheckerInterval);
  await classroomStore.leaveClassroom();
});

async function initClassroom() {
  isLoading.value = true;
  try {
    // 1. Fetch real session data from PostgreSQL
    try {
      sessionData.value = await api.getDocument<any>(`/api/v1/classrooms/sessions/${sessionId}/`);
    } catch (err) {
      console.warn('Session detail fetch error, using default params', err);
    }

    // Adopt the room's moderation state before anyone can act on it: a student
    // opening a muted lecture must see the lock the moment the page loads, not
    // only when the host happens to say something.
    if (sessionData.value) {
      classroomStore.applyModerationState(sessionData.value);
    }

    const session = sessionData.value;

    // 2. Scheduled or Lobby Check
    if (session) {
      const now = new Date();
      if (session.status === 'scheduled' && session.scheduledStartTime) {
        const scheduledTime = new Date(session.scheduledStartTime);
        if (scheduledTime > now) {
          isScheduledFuture.value = true;
          isWaitingLobby.value = true;
          isLoading.value = false;
          return;
        }
      }

      if (!session.is_active && !isLecturer.value) {
        isWaitingLobby.value = true;
        isLoading.value = false;
        return;
      }

      // 3. Paid session wallet check
      if (!session.is_free && session.price > 0 && !isLecturer.value) {
        try {
          await api.postDocument('/api/v1/billing/deduct/', { sessionId });
        } catch (err: any) {
          if (err?.status === 402 || err?.code === 'INSUFFICIENT_BALANCE') {
            insufficientBalance.value = {
              needed: session.price,
              have: authStore.user?.walletBalance || 0,
            };
            isLoading.value = false;
            return;
          }
        }
      }
    }

    // 4. Register attendance join in PostgreSQL.
    // Identity is taken from the verified JWT server-side, so the request only
    // carries the session.
    try {
      await api.postDocument('/api/v1/attendance/join/', { sessionId });
    } catch (e) {
      console.error('Attendance join registration failed', e);
    }

    // 5. Connect LiveKit room (real connection; surfaces failure honestly)
    const userName = authStore.user?.fullName || 'Student';
    const userRole = (authStore.user?.role || 'student') as 'student' | 'lecturer' | 'admin';
    await classroomStore.joinClassroom(
      sessionId,
      userName,
      userRole === 'lecturer' || userRole === 'admin' ? 'lecturer' : 'student'
    );

    // 6. Initialize Automated Attendance Checker
    if (session) {
      if (session.verificationCount) {
        totalTargetChecks.value = session.verificationCount;
      }
      if (session.autoAttendanceSettings) {
        const auto = session.autoAttendanceSettings;
        if (auto.checkCount) totalTargetChecks.value = auto.checkCount;
        if (auto.timeLimitSeconds) checkDurationSeconds.value = auto.timeLimitSeconds;
        if (auto.intervalMinutes) checkIntervalMinutes.value = auto.intervalMinutes;
        if (typeof auto.enabled === 'boolean') autoCheckerActive.value = auto.enabled;
      }
      nextCheckCountdownSeconds.value = checkIntervalMinutes.value * 60;
    }

    if (canModerate.value) {
      loadAttendanceRoster();
      startAutoCheckerTimer();
    }
  } finally {
    isLoading.value = false;
  }
}

async function activateSessionNow() {
  isActivating.value = true;
  try {
    isWaitingLobby.value = false;
    await initClassroom();
  } finally {
    isActivating.value = false;
  }
}

async function retryJoin() {
  await classroomStore.leaveClassroom();
  await initClassroom();
}

async function checkSessionStatus() {
  await initClassroom();
}

async function toggleCameraTrack() {
  guarded('camera', () => classroomStore.toggleCamera());
}

async function toggleScreenShareTrack() {
  guarded('screen', () => classroomStore.toggleScreenShare());
}

function toggleRaiseHand() {
  const raised = !classroomStore.isHandRaised;
  classroomStore.setHandRaised(raised, authStore.user?.fullName || 'Student');
}

function triggerReaction(emoji: string) {
  showReactions.value = false;
  classroomStore.sendReaction(emoji);
}

// ==================== AUTOMATED ATTENDANCE CHECKER ENGINE ====================

function startAutoCheckerTimer() {
  if (autoCheckerInterval) clearInterval(autoCheckerInterval);
  autoCheckerInterval = setInterval(async () => {
    // A co-host runs the checks too; the client-side gate matches the server's.
    if (!autoCheckerActive.value || !canModerate.value) return;

    // Count down when there are remaining checks to be triggered
    if (checksCompleted.value < totalTargetChecks.value) {
      if (nextCheckCountdownSeconds.value > 0) {
        nextCheckCountdownSeconds.value--;
      } else {
        // Trigger automatic verification check
        await triggerAttendanceCheck(checkDurationSeconds.value);
        // Reset countdown timer for next automated check
        nextCheckCountdownSeconds.value = checkIntervalMinutes.value * 60;
      }
    }
  }, 1000);
}

function toggleAutoChecker() {
  autoCheckerActive.value = !autoCheckerActive.value;
  if (autoCheckerActive.value && nextCheckCountdownSeconds.value <= 0) {
    nextCheckCountdownSeconds.value = checkIntervalMinutes.value * 60;
  }
}

// Lecturer: Trigger Attendance Check & Broadcast to room via LiveKit
async function triggerAttendanceCheck(seconds?: number) {
  const duration = Number(seconds) || checkDurationSeconds.value || 30;
  isTriggeringVerification.value = true;
  try {
    const doc = await api.postDocument<any>('/api/v1/attendance/verification/trigger/', {
      sessionId,
      timeLimitSeconds: duration,
      triggeredBy: authStore.user?.id || 'host',
    });

    checksCompleted.value = doc.verificationNumber || (checksCompleted.value + 1);

    // Broadcast via LiveKit Data Packet to all students in room
    await classroomStore.broadcastVerification({
      verificationId: doc.verificationId,
      verificationNumber: doc.verificationNumber,
      timeLimitSeconds: doc.timeLimitSeconds || duration,
      totalTargetChecks: totalTargetChecks.value,
    });

    await loadAttendanceRoster();
  } catch (err: any) {
    console.error('Failed to trigger attendance check:', err);
    alert(err?.message || 'Failed to trigger verification check');
  } finally {
    isTriggeringVerification.value = false;
  }
}

// Lecturer: Reconfigure & Save Attendance Checker Settings on the Fly
async function saveAttendanceSettings() {
  isSavingSettings.value = true;
  try {
    await api.patchDocument(`/api/v1/classrooms/sessions/${sessionId}/`, {
      verificationCount: totalTargetChecks.value,
      autoAttendanceSettings: {
        enabled: autoCheckerActive.value,
        checkCount: totalTargetChecks.value,
        timeLimitSeconds: checkDurationSeconds.value,
        intervalMinutes: checkIntervalMinutes.value,
      },
    });
    alert('Attendance checker settings saved and updated!');
  } catch (err: any) {
    console.error('Failed to save settings:', err);
    alert(err?.message || 'Failed to update attendance settings');
  } finally {
    isSavingSettings.value = false;
  }
}

// Lecturer: Load Live Attendance Roster & Summary from PostgreSQL
async function loadAttendanceRoster() {
  isLoadingRoster.value = true;
  try {
    const res = await api.getDocument<any>(`/api/v1/attendance/sessions/${sessionId}/`);
    if (res?.summary) {
      attendanceSummary.value = res.summary;
    }
    if (res?.records) {
      attendanceRecords.value = res.records;
      const maxSent = Math.max(0, ...res.records.map((r: any) => r.verificationsSent || 0));
      if (maxSent > checksCompleted.value) {
        checksCompleted.value = maxSent;
      }
    }
  } catch (err) {
    console.warn('Could not load attendance roster:', err);
  } finally {
    isLoadingRoster.value = false;
  }
}

// Student: Confirm Attendance via Highly Responsive Modal
async function submitVerificationResponse() {
  if (!activeVerification.value) return;
  isSubmittingVerification.value = true;
  try {
    await api.postDocument('/api/v1/attendance/verification/respond/', {
      sessionId,
      verificationId: activeVerification.value.verificationId,
    });
    verificationSuccess.value = true;
    if (countdownInterval) clearInterval(countdownInterval);
    setTimeout(() => {
      activeVerification.value = null;
      verificationSuccess.value = false;
    }, 1200);
  } catch (err: any) {
    alert(err?.message || 'Failed to submit verification');
  } finally {
    isSubmittingVerification.value = false;
  }
}

async function sendMessage() {
  if (!newChatMessage.value.trim()) return;
  const sender = authStore.user?.fullName || 'Student';
  const role = authStore.user?.role || 'student';
  await classroomStore.sendChatMessage(newChatMessage.value, sender, role, sessionId);
  newChatMessage.value = '';
}

function copyCode() {
  const code = sessionData.value?.meetingCode || meetingCode.value;
  navigator.clipboard.writeText(code);
  alert('Meeting code copied: ' + code);
}

function copyUrl() {
  navigator.clipboard.writeText(classroomUrl.value);
  alert('Classroom invite link copied: ' + classroomUrl.value);
}

function confirmLeave() {
  if (confirm('Leave this classroom session?')) {
    leaveClassroom();
  }
}

async function leaveClassroom() {
  await classroomStore.leaveClassroom();
  router.push('/');
}
</script>

<style scoped>
@keyframes floatUp {
  0% { transform: translateY(0) scale(0.6); opacity: 0; }
  15% { transform: translateY(-20px) scale(1.2); opacity: 1; }
  100% { transform: translateY(-220px) scale(1); opacity: 0; }
}

.animate-float-up {
  animation: floatUp 2s ease-out forwards;
}
</style>
