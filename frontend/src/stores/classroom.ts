import { defineStore } from 'pinia';
import { ref, shallowRef, nextTick } from 'vue';
import {
  Room,
  RoomEvent,
  ConnectionState,
  Track,
  VideoPresets,
  type RemoteParticipant,
  type RemoteVideoTrack,
} from 'livekit-client';
import { api } from '../api/client';

export interface ClassroomSession {
  id: string;
  title: string;
  meetingCode: string;
  status: string;
  is_active: boolean;
  is_free: boolean;
  price: number;
  currency: string;
  lecturerName: string;
  program?: string;
  course?: string;
  participantCount?: number;
}

export interface ChatMsg {
  id: string;
  sender: string;
  message: string;
  role: string;
  time: string;
}

export interface ParticipantTile {
  identity: string;
  name: string;
  role: string;
  isLocal: boolean;
  isSpeaking: boolean;
  hasVideo: boolean;
  hasScreenShare: boolean;
  isMicOn: boolean;
  isHandRaised: boolean;
}
export type MediaKind = 'mic' | 'camera' | 'screen';
export type PermissionMode = 'free' | 'approval';

export interface PendingPermissionRequest {
  id: string;
  participantId: string;
  participantName: string;
  requestType: MediaKind;
  status: 'pending' | 'approved' | 'rejected';
}

interface DataMessage {
  type:
    | 'chat'
    | 'reaction'
    | 'verification'
    | 'hand'
    | 'moderation'
    | 'permission_request'
    | 'permission_response'
    | 'cohost';
  [key: string]: any;
}

const MEDIA_LABELS: Record<MediaKind, string> = {
  mic: 'microphone',
  camera: 'camera',
  screen: 'screen share',
};

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

const nowTime = () =>
  new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

export const useClassroomStore = defineStore('classroom', () => {
  const currentSession = ref<ClassroomSession | null>(null);
  const activeSessionId = ref<string>('');
  const room = shallowRef<Room | null>(null);
  const isConnected = ref(false);
  const isConnecting = ref(false);

  // Real connection lifecycle — no silent fallback.
  const connectionState = ref<ConnectionState | 'idle' | 'failed'>('idle');
  const hasFailed = ref(false);
  const errorMessage = ref<string | null>(null);

  // Local media
  const localStream = shallowRef<MediaStream | null>(null);
  const localAudioStream = shallowRef<MediaStream | null>(null);
  const localScreenStream = shallowRef<MediaStream | null>(null);

  // Media states
  const isCameraEnabled = ref(false);
  const isMicEnabled = ref(false);
  const isScreenSharing = ref(false);
  const cameraSource = ref<'device' | 'synthetic'>('device');

  // Roster + interaction state (driven entirely by LiveKit events)
  const participants = ref<ParticipantTile[]>([]);
  const chatMessages = ref<ChatMsg[]>([]);
  const activeReactions = ref<{ id: string; emoji: string; x: number }[]>([]);
  const incomingVerification = ref<any>(null);
  const raisedHands = ref<{ identity: string; name: string; at: number }[]>([]);
  const isHandRaised = ref(false);

  // --- moderation -------------------------------------------------------
  // The Django session row is the source of truth: it is read once on join and
  // written whenever the host changes something, so a refresh (either side)
  // cannot silently unlock a room. The data channel only carries the live
  // updates, so nobody waits for a reload to see them.
  const isModerator = ref(false);
  const isMutedAll = ref(false);
  const autoApproveMic = ref(false);
  const permissionPolicy = ref<Record<MediaKind, PermissionMode>>({
    mic: 'free',
    camera: 'free',
    screen: 'free',
  });
  // What *this* participant has been personally cleared to use.
  const myPermissions = ref<Record<MediaKind, boolean>>({
    mic: false,
    camera: false,
    screen: false,
  });
  // Requests this participant has already sent and is waiting on.
  const requestedKinds = ref<Record<MediaKind, boolean>>({
    mic: false,
    camera: false,
    screen: false,
  });
  // Host-side inbox of other people's requests.
  const pendingRequests = ref<PendingPermissionRequest[]>([]);
  // Co-hosts: `coHostIds` drives the CO-HOST badge on everyone's roster, and
  // `isCohost` is *this* client's status -- answered by the server at join
  // (GET .../permissions/) and re-read whenever a cohost packet names us. A
  // browser must never be able to promote itself by editing its own state.
  const isCohost = ref(false);
  const coHostIds = ref<string[]>([]);
  // The role we joined with, before any co-host promotion. Kept non-reactive:
  // it is a fact about the join, not something the UI displays or changes.
  let joinRole: 'student' | 'lecturer' = 'student';

  /**
   * Apply one co-host status to this client's idea of who runs the room.
   *
   * Moderator-ness is derived, never stored: host/lecturer by join role, plus
   * an active co-host appointment. Losing the appointment therefore takes the
   * panel and the inbox away with it.
   */
  function applyCohostFlag(active: boolean) {
    isCohost.value = active;
    isModerator.value = joinRole === 'lecturer' || active;
    if (!isModerator.value) pendingRequests.value = [];
  }

  /**
   * DOM registry: identity -> <video> element.
   * The view registers elements via setVideoElement(); we attach/detach the
   * matching LiveKit track here so DOM handling stays imperative & reliable.
   */
  const videoElements = new Map<string, HTMLVideoElement>();

  /**
   * Private (non-reactive) store of each participant's tracks, keyed by
   * identity. Keeping these in a plain Map keeps the LiveKit objects out of
   * Vue's reactivity graph so they're never proxied or deep-observed.
   */
  interface TrackedMedia {
    camera: Track | null;
    screen: Track | null;
    attached: Track | null;
  }
  const mediaByIdentity = new Map<string, TrackedMedia>();

  function mediaFor(identity: string): TrackedMedia {
    let m = mediaByIdentity.get(identity);
    if (!m) {
      m = { camera: null, screen: null, attached: null };
      mediaByIdentity.set(identity, m);
    }
    return m;
  }

  /** Screen share wins over camera when both are present. */
  function pickTrack(m: TrackedMedia): Track | null {
    return m.screen ?? m.camera;
  }

  /** (Re)bind the correct track for this identity onto its registered element. */
  function attachToElement(identity: string) {
    const el = videoElements.get(identity);
    if (!el) return;
    const m = mediaFor(identity);

    const r = room.value;
    const isLocal =
      (r && r.localParticipant.identity === identity) ||
      participants.value.find((p) => p.identity === identity)?.isLocal;

    if (isLocal) {
      // 1. If screen sharing is active
      if (isScreenSharing.value) {
        const streamToAttach =
          localScreenStream.value ||
          ((m.screen as any)?.mediaStreamTrack
            ? new MediaStream([(m.screen as any).mediaStreamTrack])
            : null);
        if (streamToAttach) {
          if (el.srcObject !== streamToAttach) {
            el.srcObject = streamToAttach;
          }
          el.muted = true;
          el.playsInline = true;
          el.autoplay = true;
          el.play().catch(() => {});
          m.attached = m.screen;
          return;
        }
      }

      // 2. If camera is active
      if (isCameraEnabled.value) {
        const streamToAttach =
          localStream.value ||
          ((m.camera as any)?.mediaStreamTrack
            ? new MediaStream([(m.camera as any).mediaStreamTrack])
            : null);
        if (streamToAttach) {
          if (el.srcObject !== streamToAttach) {
            el.srcObject = streamToAttach;
          }
          el.muted = true;
          el.defaultMuted = true;
          el.playsInline = true;
          el.autoplay = true;
          const play = () => {
            el.play().catch(() => {});
          };
          play();
          el.onloadedmetadata = play;
          el.oncanplay = play;
          m.attached = m.camera;
          return;
        }
      }

      // 3. Neither active
      el.srcObject = null;
      if (m.attached) {
        try { m.attached.detach(el); } catch {}
        m.attached = null;
      }
      return;
    }

    // Remote participant
    if (!m) return;
    const target = pickTrack(m);
    if (!target) {
      el.srcObject = null;
      if (m.attached) {
        try { m.attached.detach(el); } catch {}
        m.attached = null;
      }
      return;
    }

    try {
      target.attach(el);
    } catch (e) {
      console.warn('Remote track attach failed, falling back:', e);
      if ((target as any).mediaStreamTrack) {
        el.srcObject = new MediaStream([(target as any).mediaStreamTrack]);
      }
    }
    el.play().catch(() => {});
    m.attached = target;
  }

  /** Detach whatever track is currently bound to this identity's element. */
  function detachFromElement(identity: string) {
    const el = videoElements.get(identity);
    const m = mediaByIdentity.get(identity);
    if (!el || !m) return;
    try {
      m.attached?.detach(el);
    } catch {
      /* element may already be gone */
    }
    m.attached = null;
  }

  /** Called by the view when a tile's <video> mounts/unmounts. */
  function setVideoElement(identity: string, el: HTMLVideoElement | null) {
    if (!el) {
      detachFromElement(identity);
      videoElements.delete(identity);
      return;
    }
    videoElements.set(identity, el);
    attachToElement(identity);
  }

  /** Rebuild the roster from the live room state (local + remote participants). */
  function syncRoster(r: Room, localName: string, localRole: string) {
    const tiles: ParticipantTile[] = [];
    const handSet = new Set(raisedHands.value.map((h) => h.identity));

    const local = r.localParticipant;
    const localMedia = mediaFor(local.identity);

    // Populate localMedia from LiveKit local publications
    let localCamTrack: Track | null = null;
    let localScreenTrack: Track | null = null;
    for (const pub of local.videoTrackPublications.values()) {
      if (pub.source === Track.Source.Camera && pub.track) {
        localCamTrack = pub.track;
      } else if (pub.source === Track.Source.ScreenShare && pub.track) {
        localScreenTrack = pub.track;
      }
    }
    localMedia.camera = localCamTrack;
    localMedia.screen = localScreenTrack;

    const hasLocalCam = !!localCamTrack || isCameraEnabled.value || !!localStream.value;
    const hasLocalScreen = !!localScreenTrack || isScreenSharing.value || !!localScreenStream.value;

    tiles.push({
      identity: local.identity,
      name: localName,
      role: localRole,
      isLocal: true,
      isSpeaking: local.isSpeaking,
      hasVideo: hasLocalCam,
      hasScreenShare: hasLocalScreen,
      isMicOn: isMicEnabled.value,
      isHandRaised: isHandRaised.value,
    });

    r.remoteParticipants.forEach((p: RemoteParticipant) => {
      const meta = parseMetadata(p);
      const m = mediaFor(p.identity);
      m.camera = findVideoTrack(p, Track.Source.Camera);
      m.screen = findVideoTrack(p, Track.Source.ScreenShare);
      tiles.push({
        identity: p.identity,
        name: meta.name || p.name || p.identity,
        role: meta.role || 'student',
        isLocal: false,
        isSpeaking: p.isSpeaking,
        hasVideo: !!m.camera,
        hasScreenShare: !!m.screen,
        isMicOn: hasMicOn(p),
        isHandRaised: handSet.has(p.identity),
      });
    });

    participants.value = tiles;

    // Re-bind any already-mounted <video> elements to the refreshed tracks.
    for (const identity of videoElements.keys()) {
      attachToElement(identity);
    }
  }

  function parseMetadata(p: RemoteParticipant): { name?: string; role?: string } {
    try {
      return p.metadata ? JSON.parse(p.metadata) : {};
    } catch {
      return {};
    }
  }

  function findVideoTrack(p: RemoteParticipant, source: Track.Source): RemoteVideoTrack | null {
    for (const pub of p.videoTrackPublications.values()) {
      if (pub.source === source && pub.track) return pub.track as RemoteVideoTrack;
    }
    return null;
  }

  function hasMicOn(p: RemoteParticipant): boolean {
    for (const pub of p.audioTrackPublications.values()) {
      if (pub.isSubscribed && pub.track) return true;
    }
    return false;
  }

  function updateParticipants() {
    const r = room.value;
    if (!r) return;
    const localName =
      participants.value.find((p) => p.isLocal)?.name || 'You';
    const localRole =
      participants.value.find((p) => p.isLocal)?.role || 'student';
    syncRoster(r, localName, localRole);
  }

  function addReaction(emoji: string) {
    const id = Math.random().toString(36).substring(7);
    const x = Math.floor(Math.random() * 80) + 10;
    activeReactions.value.push({ id, emoji, x });
    setTimeout(() => {
      activeReactions.value = activeReactions.value.filter((r) => r.id !== id);
    }, 2000);
  }

  /** A line in the chat stream that comes from the system, not a person. */
  function pushSystemMessage(message: string) {
    chatMessages.value.push({
      id: `sys_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
      sender: 'Podium',
      message,
      role: 'system',
      time: nowTime(),
    });
  }

  /** Merge a moderation snapshot (REST read or data-channel push). */
  function applyModerationState(state: any) {
    if (typeof state?.isMutedAll === 'boolean') isMutedAll.value = state.isMutedAll;
    if (typeof state?.autoApproveMic === 'boolean') autoApproveMic.value = state.autoApproveMic;
    if (state?.permissions && typeof state.permissions === 'object') {
      permissionPolicy.value = { ...permissionPolicy.value, ...state.permissions };
    }
  }

  /**
   * May this participant use one of their own devices right now?
   *
   * The moderator is never locked out, a personal approval always wins, and
   * Mute All holds the microphone shut for everyone who has not been cleared.
   */
  function canUse(kind: MediaKind): boolean {
    if (isModerator.value) return true;
    if (myPermissions.value[kind]) return true;
    if (kind === 'mic' && isMutedAll.value) return false;
    return permissionPolicy.value[kind] === 'free';
  }

  /** Media that is closed to us right now, for the banner and the button titles. */
  function lockedKinds(): MediaKind[] {
    return (Object.keys(MEDIA_LABELS) as MediaKind[]).filter((k) => !canUse(k));
  }

  /** Cut a device off the moment the room stops allowing it. */
  function enforceMicLock() {
    if (isMicEnabled.value && !canUse('mic')) void toggleMic();
    if (isCameraEnabled.value && !canUse('camera')) void toggleCamera();
    if (isScreenSharing.value && !canUse('screen')) void toggleScreenShare();
  }

  /** Turn a device on (used when the host says yes). */
  async function enableKind(kind: MediaKind) {
    if (kind === 'mic' && !isMicEnabled.value) await toggleMic();
    if (kind === 'camera' && !isCameraEnabled.value) await toggleCamera();
    if (kind === 'screen' && !isScreenSharing.value) await toggleScreenShare();
  }

  async function publish(payload: DataMessage, topic: string) {
    const r = room.value;
    if (!r) return;
    try {
      const bytes = new TextEncoder().encode(JSON.stringify(payload));
      await r.localParticipant.publishData(bytes, { reliable: true, topic });
    } catch (e) {
      console.warn('Data publish failed', e);
    }
  }

  function handleData(payload: Uint8Array, participant?: RemoteParticipant) {
    let msg: DataMessage;
    try {
      msg = JSON.parse(new TextDecoder().decode(payload));
    } catch (e) {
      console.error('Failed to parse data message', e);
      return;
    }

    switch (msg.type) {
      case 'chat': {
        const incomingId = msg.id || `msg_${Date.now()}`;
        if (!chatMessages.value.some((m) => m.id === incomingId)) {
          const item: ChatMsg = {
            id: incomingId,
            sender: msg.sender || participant?.name || 'Unknown',
            message: msg.message,
            role: msg.role || 'student',
            time: msg.time || nowTime(),
          };
          chatMessages.value.push(item);
          if (activeSessionId.value) {
            try {
              localStorage.setItem(
                `podium_chat_${activeSessionId.value}`,
                JSON.stringify(chatMessages.value)
              );
            } catch {}
          }
        }
        break;
      }
      case 'reaction':
        addReaction(msg.emoji);
        break;
      case 'verification':
        incomingVerification.value = msg;
        break;
      case 'hand': {
        const identity = participant?.identity || msg.identity;
        const name = participant?.name || msg.name || 'Student';
        if (msg.raised) {
          if (!raisedHands.value.some((h) => h.identity === identity)) {
            raisedHands.value.push({ identity, name, at: Date.now() });
          }
        } else {
          raisedHands.value = raisedHands.value.filter(
            (h) => h.identity !== identity
          );
        }
        // Keep roster badges in sync.
        participants.value = participants.value.map((p) =>
          p.identity === identity ? { ...p, isHandRaised: !!msg.raised } : p
        );
        break;
      }
      case 'moderation': {
        const wasMuted = isMutedAll.value;
        applyModerationState(msg);
        // Grants are revoked server-side by Mute All: re-read ours so a stale
        // "I may speak" flag cannot outlive the lock.
        void loadOwnPermissions();
        if (isMutedAll.value !== wasMuted) {
          pushSystemMessage(
            isMutedAll.value
              ? 'The host muted all microphones.'
              : 'The host unmuted microphones.'
          );
        }
        enforceMicLock();
        break;
      }
      case 'permission_request':
        // Only the host acts on this, and it refetches the inbox instead of
        // trusting the payload, so the buttons always match the database.
        if (isModerator.value) void loadPendingRequests();
        break;
      case 'cohost': {
        const myIdentity = room.value?.localParticipant.identity;
        // Badges come from the roster endpoint, and our own moderator status
        // is re-read from the server rather than believed from this packet --
        // anyone can publish on this topic, so it is a hint to look, never a
        // grant of authority.
        void loadCoHosts();
        if (msg.userId === myIdentity) void loadOwnPermissions();
        break;
      }
      case 'permission_response': {
        const myIdentity = room.value?.localParticipant.identity;
        if (!msg.target || msg.target !== myIdentity) break;
        const kind = msg.requestType as MediaKind;
        if (!MEDIA_LABELS[kind]) break;
        requestedKinds.value = { ...requestedKinds.value, [kind]: false };
        if (msg.granted) {
          myPermissions.value = { ...myPermissions.value, [kind]: true };
          pushSystemMessage(`The host allowed your ${MEDIA_LABELS[kind]}.`);
          void enableKind(kind);
        } else {
          pushSystemMessage(`The host declined your ${MEDIA_LABELS[kind]} request.`);
        }
        break;
      }
      default:
        chatMessages.value.push({
          id: msg.id || `msg_${Date.now()}`,
          sender: msg.sender || participant?.name || 'Unknown',
          message: msg.message || '',
          role: msg.role || 'student',
          time: msg.time || nowTime(),
        });
    }
  }

  function bindRoomEvents(r: Room, localName: string, localRole: string) {
    r.on(RoomEvent.Connected, () => {
      isConnected.value = true;
      hasFailed.value = false;
      errorMessage.value = null;
      connectionState.value = ConnectionState.Connected;
      syncRoster(r, localName, localRole);
    })
      .on(RoomEvent.Disconnected, () => {
        isConnected.value = false;
        connectionState.value = ConnectionState.Disconnected;
      })
      .on(RoomEvent.Reconnecting, () => {
        connectionState.value = ConnectionState.Reconnecting;
      })
      .on(RoomEvent.Reconnected, () => {
        // LiveKit data packets are fire-and-forget: anything published while
        // this peer was down is gone for good, and no ParticipantConnected
        // fires because our identity never changed. The session row is the
        // source of truth, so re-read it instead of trusting the stale state
        // we went into the drop with.
        void resyncModeration();
      })
      .on(RoomEvent.ConnectionStateChanged, (state: ConnectionState) => {
        connectionState.value = state;
      })
      .on(RoomEvent.ParticipantConnected, () => {
        updateParticipants();
        // A late joiner bootstraps from the session row, but a change can land
        // between their fetch and their join: republish so nobody is left in a
        // room that is out of date. Re-read first -- broadcasting our own
        // cache would let a stale snapshot overwrite what they just fetched.
        if (isModerator.value) void resyncModeration().then(() => publishModeration());
      })
      .on(RoomEvent.ParticipantDisconnected, () => updateParticipants())
      .on(RoomEvent.TrackSubscribed, () => updateParticipants())
      .on(RoomEvent.TrackUnsubscribed, () => updateParticipants())
      .on(RoomEvent.LocalTrackPublished, () => updateParticipants())
      .on(RoomEvent.LocalTrackUnpublished, () => updateParticipants())
      .on(RoomEvent.ActiveSpeakersChanged, () => {
        const speakers = new Set(r.activeSpeakers.map((s) => s.identity));
        participants.value = participants.value.map((p) => ({
          ...p,
          isSpeaking: speakers.has(p.identity),
        }));
      })
      .on(RoomEvent.DataReceived, (payload, participant?: RemoteParticipant) => {
        handleData(payload as Uint8Array, participant);
      });
  }

  async function joinClassroom(
    sessionId: string,
    participantName: string,
    role: 'student' | 'lecturer' = 'student'
  ) {
    isConnecting.value = true;
    hasFailed.value = false;
    errorMessage.value = null;
    connectionState.value = 'idle';
    activeSessionId.value = sessionId;
    // The moderator is exempt from every lock; everyone else starts closed and
    // is opened up by initClassroom() (room policy) and loadOwnPermissions()
    // (their own approvals). joinRole records how we arrived so a later
    // co-host promotion can be revoked without losing sight of a real host.
    joinRole = role;
    isModerator.value = role === 'lecturer';
    void loadChatHistory(sessionId);

    try {
      // 1. Request a LiveKit token from Django.
      const tokenDoc = await api.postDocument<{
        token: string;
        roomName: string;
        identity: string;
        livekitUrl?: string;
      }>('/api/v1/classrooms/token/', {
        roomName: sessionId,
        participantName,
        participantId: undefined,
        role,
      });

      if (!tokenDoc?.token) {
        throw new Error('No LiveKit token issued by the server.');
      }

      let livekitUrl =
        tokenDoc.livekitUrl || 'ws://localhost:7880';
      if (typeof window !== 'undefined' && window.location?.hostname) {
        const clientHost = window.location.hostname;
        if (clientHost && clientHost !== 'localhost' && clientHost !== '127.0.0.1') {
          livekitUrl = livekitUrl.replace('localhost', clientHost).replace('127.0.0.1', clientHost);
        }
      }

      // 2. Create room.
      const newRoom = new Room({
        adaptiveStream: true,
        dynacast: true,
        videoCaptureDefaults: { resolution: VideoPresets.h720.resolution },
      });

      bindRoomEvents(newRoom, participantName, role);
      room.value = newRoom;
      connectionState.value = ConnectionState.Connecting;

      // 3. Connect — generous timeout, but surface failure honestly.
      await withTimeout(newRoom.connect(livekitUrl, tokenDoc.token), 15000);

      isConnected.value = true;
      syncRoster(newRoom, participantName, role);
      // Our own approvals (which also settle whether we run the room), the
      // host's inbox of other people's asks, and the co-host badges.
      void loadOwnPermissions();
      if (isModerator.value) void loadPendingRequests();
      void loadCoHosts();
      return newRoom;
    } catch (e: any) {
      console.error('Classroom connection failed:', e);
      hasFailed.value = true;
      connectionState.value = 'failed';
      errorMessage.value =
        e?.message ||
        'Could not connect to the LiveKit server. Check your connection and try again.';
      return null;
    } finally {
      isConnecting.value = false;
    }
  }

  function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error(`LiveKit connection timed out after ${ms / 1000}s`)),
        ms
      );
      promise
        .then((v) => {
          clearTimeout(timer);
          resolve(v);
        })
        .catch((err) => {
          clearTimeout(timer);
          reject(err);
        });
    });
  }

  function createSyntheticLocalStream(): MediaStream | null {
    try {
      const canvas = document.createElement('canvas');
      canvas.width = 1280;
      canvas.height = 720;
      const ctx = canvas.getContext('2d');
      if (!ctx) return null;

      let isRunning = true;
      let frame = 0;

      const draw = () => {
        if (!isRunning) return;
        frame++;

        // Rich dark blue/indigo gradient background
        const grad = ctx.createLinearGradient(0, 0, 1280, 720);
        grad.addColorStop(0, '#0f172a');
        grad.addColorStop(1, '#312e81');
        ctx.fillStyle = grad;
        ctx.fillRect(0, 0, 1280, 720);

        // Tech grid lines
        ctx.strokeStyle = 'rgba(99, 102, 241, 0.2)';
        ctx.lineWidth = 1;
        for (let x = 0; x < 1280; x += 60) {
          ctx.beginPath();
          ctx.moveTo(x, 0);
          ctx.lineTo(x, 720);
          ctx.stroke();
        }
        for (let y = 0; y < 720; y += 60) {
          ctx.beginPath();
          ctx.moveTo(0, y);
          ctx.lineTo(1280, y);
          ctx.stroke();
        }

        // Animated bouncing radiant orb
        const bounceX = 640 + Math.sin(frame * 0.05) * 360;
        const bounceY = 360 + Math.cos(frame * 0.07) * 160;
        const orbGrad = ctx.createRadialGradient(bounceX, bounceY, 5, bounceX, bounceY, 100);
        orbGrad.addColorStop(0, '#a5b4fc');
        orbGrad.addColorStop(0.4, '#6366f1');
        orbGrad.addColorStop(1, 'transparent');
        ctx.fillStyle = orbGrad;
        ctx.beginPath();
        ctx.arc(bounceX, bounceY, 100, 0, Math.PI * 2);
        ctx.fill();

        // Pulsing radar circles in the center
        const pulse = Math.sin(frame * 0.08) * 18;
        ctx.strokeStyle = '#818cf8';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.arc(640, 360, 130 + pulse, 0, Math.PI * 2);
        ctx.stroke();

        ctx.strokeStyle = 'rgba(129, 140, 248, 0.4)';
        ctx.lineWidth = 1.5;
        ctx.beginPath();
        ctx.arc(640, 360, 170 + pulse * 1.5, 0, Math.PI * 2);
        ctx.stroke();

        // Clean typography
        ctx.fillStyle = '#ffffff';
        ctx.font = 'bold 42px sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('LIVE PODIUM CLASSROOM', 640, 340);

        ctx.fillStyle = '#c7d2fe';
        ctx.font = '600 22px sans-serif';
        ctx.fillText('Virtual HD 720p 30FPS Feed', 640, 385);

        ctx.fillStyle = '#34d399';
        ctx.font = 'bold 16px monospace';
        const now = new Date().toLocaleTimeString();
        ctx.fillText(`FRAME: ${frame}  •  TIME: ${now}  •  STATUS: BROADCASTING`, 640, 430);
      };

      draw();
      // setInterval executes independently of DOM visibility / compositor cycles!
      const timer = setInterval(draw, 33);

      const stream = canvas.captureStream(30);
      const track = stream.getVideoTracks()[0];
      if (track) {
        const origStop = track.stop.bind(track);
        track.stop = () => {
          isRunning = false;
          clearInterval(timer);
          origStop();
        };
      }
      return stream;
    } catch (e) {
      console.warn('Synthetic stream failed', e);
      return null;
    }
  }

  async function switchCameraSource(source: 'device' | 'synthetic') {
    if (cameraSource.value === source) return;
    cameraSource.value = source;
    if (isCameraEnabled.value) {
      // Re-toggle camera with new source
      await toggleCamera();
      await toggleCamera();
    }
  }

  async function toggleCamera() {
    const r = room.value;
    if (isCameraEnabled.value) {
      // Turn OFF
      isCameraEnabled.value = false;
      if (r) {
        for (const pub of r.localParticipant.videoTrackPublications.values()) {
          if (pub.source === Track.Source.Camera && pub.track) {
            try {
              await r.localParticipant.unpublishTrack(pub.track, true);
            } catch {}
          }
        }
        try {
          await r.localParticipant.setCameraEnabled(false);
        } catch {}
      }
      localStream.value?.getTracks().forEach((t) => t.stop());
      localStream.value = null;
      const localMedia = r ? mediaByIdentity.get(r.localParticipant.identity) : null;
      if (localMedia) {
        localMedia.camera = null;
        localMedia.attached = null;
      }
      updateParticipants();
      return;
    }

    // Turn ON
    isCameraEnabled.value = true;
    updateParticipants(); // Instant UI switch to <video>

    let stream: MediaStream | null = null;

    if (cameraSource.value === 'synthetic') {
      stream = createSyntheticLocalStream();
      if (stream) {
        localStream.value = stream;
        const track = stream.getVideoTracks()[0];
        if (track && r) {
          try {
            await r.localParticipant.publishTrack(track, {
              name: 'camera',
              source: Track.Source.Camera,
            });
          } catch (pubErr) {
            console.warn('Publish synthetic camera note:', pubErr);
          }
        }
      }
    } else {
      // Direct physical webcam access
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { width: { ideal: 1280 }, height: { ideal: 720 }, facingMode: 'user' },
        });
        localStream.value = stream;
        const track = stream.getVideoTracks()[0];
        if (track && r) {
          try {
            await r.localParticipant.publishTrack(track, {
              name: 'camera',
              source: Track.Source.Camera,
            });
          } catch (pubErr) {
            console.warn('Publish physical camera track note:', pubErr);
          }
        }
      } catch (mediaErr) {
        console.warn('Physical camera direct access error, falling back to test stream:', mediaErr);
        cameraSource.value = 'synthetic';
        stream = createSyntheticLocalStream();
        if (stream) {
          localStream.value = stream;
          const track = stream.getVideoTracks()[0];
          if (track && r) {
            try {
              await r.localParticipant.publishTrack(track, {
                name: 'camera',
                source: Track.Source.Camera,
              });
            } catch {}
          }
        }
      }
    }

    updateParticipants();
    await nextTick();
    for (const identity of videoElements.keys()) {
      attachToElement(identity);
    }
  }

  async function toggleMic() {
    const r = room.value;
    if (isMicEnabled.value) {
      localAudioStream.value?.getTracks().forEach((t) => t.stop());
      localAudioStream.value = null;
      try {
        await r?.localParticipant.setMicrophoneEnabled(false);
      } catch {}
      isMicEnabled.value = false;
    } else {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        localAudioStream.value = stream;
        isMicEnabled.value = true;
        try {
          await r?.localParticipant.setMicrophoneEnabled(true);
        } catch {}
      } catch (err) {
        console.warn('Microphone access warning:', err);
        errorMessage.value = 'Microphone access was denied.';
      }
    }
    updateParticipants();
  }

  async function toggleScreenShare() {
    const r = room.value;
    if (isScreenSharing.value) {
      localScreenStream.value?.getTracks().forEach((t) => t.stop());
      localScreenStream.value = null;
      try {
        await r?.localParticipant.setScreenShareEnabled(false);
      } catch {}
      isScreenSharing.value = false;
    } else {
      try {
        const stream = await navigator.mediaDevices.getDisplayMedia({ video: true });
        localScreenStream.value = stream;
        isScreenSharing.value = true;
        stream.getVideoTracks()[0]?.addEventListener('ended', () => {
          isScreenSharing.value = false;
          localScreenStream.value = null;
          updateParticipants();
        });
        try {
          await r?.localParticipant.setScreenShareEnabled(true);
        } catch {}
      } catch (err) {
        console.warn('Screen share cancelled or failed:', err);
      }
    }
    updateParticipants();
  }

  async function loadChatHistory(sessionId: string) {
    if (!sessionId) return;
    activeSessionId.value = sessionId;
    // 1. Instant local recovery from localStorage
    try {
      const cached = localStorage.getItem(`podium_chat_${sessionId}`);
      if (cached) {
        const parsed = JSON.parse(cached);
        if (Array.isArray(parsed) && parsed.length > 0) {
          chatMessages.value = parsed;
        }
      }
    } catch {}

    // 2. Fetch full server history from PostgreSQL
    try {
      // The renderer wraps a list as { documents: [...] }. Reading it with
      // getDocument() hands back that envelope, Array.isArray() then fails and
      // the history silently never loads after a refresh.
      const res: any = await api.getDocuments<any>(
        `/api/v1/classrooms/sessions/${sessionId}/messages/`
      );
      const serverMsgs: any[] = Array.isArray(res) ? res : res?.documents ?? [];
      if (Array.isArray(serverMsgs)) {
        const mapped: ChatMsg[] = serverMsgs.map((m: any) => ({
          id: m.id,
          sender: m.userName || m.sender || 'Student',
          message: m.message,
          role: m.userRole || m.role || 'student',
          time: m.timestamp
            ? new Date(m.timestamp).toLocaleTimeString([], {
                hour: '2-digit',
                minute: '2-digit',
              })
            : nowTime(),
        }));
        // Merge with existing, deduplicating by id
        const existingIds = new Set(chatMessages.value.map((m) => m.id));
        const merged = [...chatMessages.value];
        for (const msg of mapped) {
          if (!existingIds.has(msg.id)) {
            merged.push(msg);
            existingIds.add(msg.id);
          }
        }
        chatMessages.value = merged.length > 0 ? merged : mapped;
        try {
          localStorage.setItem(`podium_chat_${sessionId}`, JSON.stringify(chatMessages.value));
        } catch {}
      }
    } catch (e) {
      console.warn('Failed to fetch chat history from server:', e);
    }
  }

  async function sendChatMessage(
    message: string,
    senderName: string,
    role: string,
    sessionId?: string
  ) {
    if (!message.trim()) return;
    const targetSessionId = sessionId || activeSessionId.value;
    const chatMsg: ChatMsg = {
      id: `msg_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
      sender: senderName,
      message: message.trim(),
      role,
      time: nowTime(),
    };
    chatMessages.value.push(chatMsg);
    if (targetSessionId) {
      try {
        localStorage.setItem(
          `podium_chat_${targetSessionId}`,
          JSON.stringify(chatMessages.value)
        );
      } catch {}
    }
    await publish({ type: 'chat', ...chatMsg }, 'chat');

    if (targetSessionId) {
      try {
        await api.postDocument(
          `/api/v1/classrooms/sessions/${targetSessionId}/messages/`,
          {
            id: chatMsg.id,
            message: chatMsg.message,
            sender: senderName,
          }
        );
      } catch (e) {
        console.warn('Failed to save chat message to PostgreSQL:', e);
      }
    }
  }

  async function sendReaction(emoji: string) {
    addReaction(emoji);
    await publish({ type: 'reaction', emoji }, 'reaction');
  }

  async function broadcastVerification(verifData: any) {
    await publish({ type: 'verification', ...verifData }, 'attendance');
  }

  /** Raise/lower own hand and broadcast to the room. */
  async function setHandRaised(raised: boolean, name: string) {
    isHandRaised.value = raised;
    const identity = room.value?.localParticipant.identity || '';
    await publish({ type: 'hand', raised, identity, name }, 'hand');
    participants.value = participants.value.map((p) =>
      p.isLocal ? { ...p, isHandRaised: raised } : p
    );
  }

  /**
   * Persist a moderation change, then push it to everyone in the room.
   *
   * The write comes first: a PATCH that fails must not leave the host's button
   * advertising a lock the database never heard about.
   */
  async function persistModeration(patch: Record<string, unknown>): Promise<boolean> {
    const sid = activeSessionId.value;
    if (!sid) return false;
    try {
      const doc = await api.patchDocument<any>(
        `/api/v1/classrooms/sessions/${sid}/`,
        patch
      );
      applyModerationState(doc);
    } catch (e) {
      console.warn('Could not save moderation change:', e);
      pushSystemMessage('That moderation change could not be saved.');
      return false;
    }
    // The row is what makes this durable, so it has already succeeded. A
    // broadcast that fails (peer briefly offline, LiveKit restarting) only
    // means the others learn about it on their next read -- join, reconnect or
    // the following change -- and must not be reported to the host as a
    // failure of something that was in fact saved.
    try {
      await publishModeration();
    } catch (e) {
      console.warn('Moderation change saved but not announced:', e);
    }
    return true;
  }

  async function publishModeration() {
    await publish(
      {
        type: 'moderation',
        isMutedAll: isMutedAll.value,
        autoApproveMic: autoApproveMic.value,
        permissions: { ...permissionPolicy.value },
      },
      'moderation'
    );
  }

  /** Lecturer: mute (and lock) everyone, or release them again. */
  async function setMuteAll(muted: boolean) {
    const ok = await persistModeration({ isMutedAll: muted });
    if (!ok) return;
    if (muted) {
      // Mute All revokes standing mic approvals server-side too, so mirror it
      // here rather than waiting for the next round trip.
      myPermissions.value = { ...myPermissions.value, mic: false };
      enforceMicLock();
    }
  }

  /** Lecturer: let microphone asks answer themselves (Smart Mic). */
  async function setAutoApproveMic(enabled: boolean) {
    await persistModeration({ autoApproveMic: enabled });
  }

  /** Lecturer: put one medium behind host approval -- or open it again. */
  async function setPermissionPolicy(kind: MediaKind, mode: PermissionMode) {
    await persistModeration({ permissions: { [kind]: mode } });
  }

  /** Read this participant's own standing approvals. */
  async function loadOwnPermissions() {
    const sid = activeSessionId.value;
    if (!sid) return;
    try {
      const doc = await api.getDocument<{
        mic: boolean;
        camera: boolean;
        screen: boolean;
        cohost?: boolean;
      }>(`/api/v1/classrooms/sessions/${sid}/permissions/`);
      myPermissions.value = { mic: !!doc.mic, camera: !!doc.camera, screen: !!doc.screen };
      // The same round trip settles whether we run the room, so a promotion
      // lands (and a demotion takes the panel away) without depending on a
      // data packet that may have been dropped on the way.
      const wasModerator = isModerator.value;
      applyCohostFlag(!!doc.cohost);
      if (!wasModerator && isModerator.value) void loadPendingRequests();
      enforceMicLock();
    } catch (e) {
      console.warn('Could not load permissions:', e);
    }
  }

  /**
   * Re-read moderation state from the session row after a reconnect.
   *
   * The data channel is a live hint layered on top of a durable record; a
   * packet missed during the drop is not resent. Rather than let a peer keep
   * whatever it believed going into the outage (a lock that has since been
   * released, or a lock it never heard about), take both the room's state and
   * our own grants from the API again.
   */
  async function resyncModeration() {
    const sid = activeSessionId.value;
    if (!sid) return;
    try {
      const doc = await api.getDocument<unknown>(`/api/v1/classrooms/sessions/${sid}/`);
      applyModerationState(doc);
    } catch (e) {
      console.warn('Could not re-read moderation state after reconnect:', e);
    }
    await loadOwnPermissions();
    if (isModerator.value) void loadPendingRequests();
    // Roster badges are REST state too: a co-host appointed (or removed)
    // during the outage must not stay wrong after the reconnect.
    void loadCoHosts();
  }

  /** Read the host's inbox of pending requests. */
  async function loadPendingRequests() {
    const sid = activeSessionId.value;
    if (!sid || !isModerator.value) return;
    try {
      const res = await api.getDocuments<PendingPermissionRequest>(
        `/api/v1/classrooms/sessions/${sid}/permissions/requests/`
      );
      pendingRequests.value = res.documents ?? [];
    } catch (e) {
      console.warn('Could not load permission requests:', e);
    }
  }

  /** Everyone this room's host has handed the controls to (roster badges). */
  async function loadCoHosts() {
    const sid = activeSessionId.value;
    if (!sid) return;
    try {
      const res = await api.getDocuments<{ userId: string }>(
        `/api/v1/classrooms/sessions/${sid}/cohosts/`
      );
      coHostIds.value = (res.documents ?? []).map((d) => d.userId);
    } catch (e) {
      console.warn('Could not load co-hosts:', e);
    }
  }

  /**
   * Host: hand one participant the room's controls.
   *
   * The appointment is written first and only then announced, so a broadcast
   * that fails leaves a slightly stale badge rather than a control panel that
   * claims powers the server never granted. The promoted client re-reads its
   * own status instead of taking our word for it.
   */
  async function addCohost(userId: string): Promise<boolean> {
    const sid = activeSessionId.value;
    if (!sid) return false;
    try {
      await api.postDocument(`/api/v1/classrooms/sessions/${sid}/cohosts/`, { userId });
    } catch (e) {
      console.warn('Could not appoint co-host:', e);
      pushSystemMessage('That co-host appointment could not be saved.');
      return false;
    }
    await loadCoHosts();
    await publish({ type: 'cohost', userId, active: true }, 'moderation');
    return true;
  }

  /** Host: take the controls back. */
  async function removeCohost(userId: string): Promise<boolean> {
    const sid = activeSessionId.value;
    if (!sid) return false;
    try {
      await api.deleteDocument(`/api/v1/classrooms/sessions/${sid}/cohosts/${userId}/`);
    } catch (e) {
      console.warn('Could not remove co-host:', e);
      pushSystemMessage('That co-host could not be removed.');
      return false;
    }
    await loadCoHosts();
    await publish({ type: 'cohost', userId, active: false }, 'moderation');
    return true;
  }

  /**
   * Ask the host to use a medium that is closed to us.
   *
   * Smart Mic answers microphone asks on the server, so that path resolves to
   * 'approved' straight away; everything else waits for the host, and the
   * badge stays up until they reply.
   */
  async function requestPermission(kind: MediaKind): Promise<'approved' | 'pending' | 'failed'> {
    const sid = activeSessionId.value;
    if (!sid || requestedKinds.value[kind]) return 'pending';
    requestedKinds.value = { ...requestedKinds.value, [kind]: true };
    try {
      const doc = await api.postDocument<{ status: string }>(
        `/api/v1/classrooms/sessions/${sid}/permissions/requests/`,
        { requestType: kind }
      );
      if (doc.status === 'approved') {
        myPermissions.value = { ...myPermissions.value, [kind]: true };
        requestedKinds.value = { ...requestedKinds.value, [kind]: false };
        pushSystemMessage(`${cap(MEDIA_LABELS[kind])} approved automatically.`);
        await enableKind(kind);
        return 'approved';
      }
      pushSystemMessage(`Asked the host for the ${MEDIA_LABELS[kind]}.`);
      await publish({ type: 'permission_request', requestType: kind }, 'moderation');
      return 'pending';
    } catch (e) {
      console.warn('Permission request failed:', e);
      requestedKinds.value = { ...requestedKinds.value, [kind]: false };
      pushSystemMessage(`The ${MEDIA_LABELS[kind]} request could not be sent.`);
      return 'failed';
    }
  }

  /** Host: answer somebody's request. */
  async function reviewPermissionRequest(requestId: string, approve: boolean) {
    const sid = activeSessionId.value;
    const req = pendingRequests.value.find((r) => r.id === requestId);
    if (!sid || !req) return;
    try {
      await api.patchDocument(
        `/api/v1/classrooms/sessions/${sid}/permissions/requests/${requestId}/`,
        { status: approve ? 'approved' : 'rejected' }
      );
      pendingRequests.value = pendingRequests.value.filter((r) => r.id !== requestId);
      await publish(
        {
          type: 'permission_response',
          target: req.participantId,
          requestType: req.requestType,
          granted: approve,
        },
        'moderation'
      );
      pushSystemMessage(
        approve
          ? `Allowed ${req.participantName} to use their ${MEDIA_LABELS[req.requestType]}.`
          : `Declined ${req.participantName}'s ${MEDIA_LABELS[req.requestType]} request.`
      );
    } catch (e) {
      console.warn('Could not review permission request:', e);
      pushSystemMessage('That answer could not be saved.');
    }
  }

  /** Lecturer: clear all raised hands. */
  async function clearRaisedHands() {
    raisedHands.value.forEach((h) => {
      void publish({ type: 'hand', raised: false, identity: h.identity, name: h.name }, 'hand');
    });
    raisedHands.value = [];
    participants.value = participants.value.map((p) => ({ ...p, isHandRaised: false }));
  }

  async function leaveClassroom() {
    localStream.value?.getTracks().forEach((t) => t.stop());
    localStream.value = null;
    localAudioStream.value?.getTracks().forEach((t) => t.stop());
    localAudioStream.value = null;
    localScreenStream.value?.getTracks().forEach((t) => t.stop());
    localScreenStream.value = null;

    try {
      await room.value?.disconnect();
    } catch {}
    room.value = null;

    videoElements.clear();
    mediaByIdentity.clear();
    isConnected.value = false;
    isCameraEnabled.value = false;
    isMicEnabled.value = false;
    isScreenSharing.value = false;
    connectionState.value = 'idle';
    hasFailed.value = false;
    errorMessage.value = null;
    participants.value = [];
    raisedHands.value = [];
    isHandRaised.value = false;
    isModerator.value = false;
    isCohost.value = false;
    coHostIds.value = [];
    joinRole = 'student';
    isMutedAll.value = false;
    autoApproveMic.value = false;
    permissionPolicy.value = { mic: 'free', camera: 'free', screen: 'free' };
    myPermissions.value = { mic: false, camera: false, screen: false };
    requestedKinds.value = { mic: false, camera: false, screen: false };
    pendingRequests.value = [];
  }

  return {
    currentSession,
    room,
    isConnected,
    isConnecting,
    connectionState,
    hasFailed,
    errorMessage,
    localStream,
    localAudioStream,
    localScreenStream,
    isCameraEnabled,
    isMicEnabled,
    isScreenSharing,
    participants,
    chatMessages,
    activeSessionId,
    loadChatHistory,
    activeReactions,
    incomingVerification,
    raisedHands,
    isHandRaised,
    // moderation
    isModerator,
    isCohost,
    coHostIds,
    isMutedAll,
    autoApproveMic,
    permissionPolicy,
    myPermissions,
    requestedKinds,
    pendingRequests,
    canUse,
    lockedKinds,
    applyModerationState,
    loadOwnPermissions,
    loadPendingRequests,
    loadCoHosts,
    addCohost,
    removeCohost,
    requestPermission,
    reviewPermissionRequest,
    setAutoApproveMic,
    setPermissionPolicy,
    cameraSource,
    switchCameraSource,
    setVideoElement,
    joinClassroom,
    toggleCamera,
    toggleMic,
    toggleScreenShare,
    sendChatMessage,
    sendReaction,
    broadcastVerification,
    setHandRaised,
    setMuteAll,
    clearRaisedHands,
    leaveClassroom,
  };
});
