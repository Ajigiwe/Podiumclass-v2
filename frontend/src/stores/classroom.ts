import { defineStore } from 'pinia';
import { ref, shallowRef, computed, nextTick } from 'vue';
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
    | 'cohost'
    | 'whiteboard';
  [key: string]: any;
}

/**
 * One drawn segment on the whiteboard.
 *
 * Coordinates are flat and normalized to 0..1 so a stroke looks the same on a
 * 4K monitor and a small laptop, and so a packet stays tiny: a segment is a
 * handful of numbers, not an object graph.
 */
export interface WhiteboardStroke {
  color: string;
  width: number;
  points: number[];
}

/**
 * One server-side recording (LiveKit Egress writes the MP4; this client only
 * asks for it to start or stop and polls for what the worker reports).
 */
interface Recording {
  id: string;
  sessionId: string;
  title: string;
  status: string;
  isLive: boolean;
  canPlay: boolean;
  error: string | null;
  startedAt: string | null;
  endedAt: string | null;
  durationSeconds: number | null;
  sizeBytes: number | null;
  fileUrl: string | null;
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
  // The server's error code when it refuses the join outright (SESSION_ENDED
  // being the one the classroom reacts to differently), so the view can show
  // the right screen instead of a generic "connection failed".
  const joinErrorCode = ref<string | null>(null);

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
    const wasModerator = isModerator.value;
    isCohost.value = active;
    isModerator.value = joinRole === 'lecturer' || active;
    if (!isModerator.value) pendingRequests.value = [];
    // Promoted mid-class: the board was already running when they arrived as a
    // student, and they may now be the one keeping it (a host handover).
    if (!wasModerator && isModerator.value) {
      void loadWhiteboard().then(() => publishWhiteboardSnapshot());
    }
  }

  // --- whiteboard --------------------------------------------------------
  // A teaching aid the host drives and everyone else watches. Strokes travel
  // as one small data packet per segment over the channel the room already
  // has open, and nothing is written to the database: a board is for the
  // moment a class is running, and a snapshot packet covers a late joiner.
  //
  // Only the session's own host identity may drive it. That is not decoration:
  // anyone in the room can publish on the topic by hand, so without the check
  // a student could scribble over the lecture while the host is talking.
  const whiteboardOpen = ref(false);
  const whiteboardStrokes = ref<WhiteboardStroke[]>([]);
  // Bumped when the board is *replaced* (clear, snapshot) rather than
  // extended, so a canvas knows to rebuild instead of drawing on top.
  const whiteboardRevision = ref(0);
  // The user id the server says runs this class; LiveKit identities are user
  // ids, so this is what a packet's sender is compared against.
  const sessionHostId = ref<string | null>(null);
  // Bounds memory and the size of a snapshot. Well past a dense lecture's
  // worth of drawing, and it keeps a snapshot to a handful of packets.
  const WHITEBOARD_MAX_STROKES = 1200;
  const WHITEBOARD_SNAPSHOT_CHUNK = 150;
  // Debounce for writing the board to the session row. A stroke arrives every
  // few frames while the host draws, so one request per frame would be absurd;
  // one every 1.5s of drawing is invisible to the user and cheap on the server.
  let whiteboardSaveTimer: ReturnType<typeof setTimeout> | null = null;
  let whiteboardDirty = false;

  /** Told once per session load, from the session row's hostId. */
  function setSessionHost(userId?: string | null) {
    if (userId) sessionHostId.value = userId;
  }

  /**
   * Did this packet come from somebody the server says runs this class?
   *
   * The session's own host, or a co-host the host appointed. Both identities
   * are answered by the server (the session row and the co-host roster), never
   * by the packet itself: anyone can publish on the topic by hand, so the check
   * has to rest on what the server said, not on what the sender claims.
   */
  function isWhiteboardAuthor(participant?: RemoteParticipant | null): boolean {
    if (!participant) return false;
    const identity = participant.identity;
    if (sessionHostId.value && identity === sessionHostId.value) return true;
    return coHostIds.value.includes(identity);
  }

  function applyWhiteboardAction(msg: any) {
    switch (msg.action) {
      case 'open':
        whiteboardOpen.value = true;
        break;
      case 'close':
        whiteboardOpen.value = false;
        break;
      case 'clear':
        whiteboardStrokes.value = [];
        whiteboardRevision.value++;
        break;
      case 'snapshot': {
        // A long board arrives in chunks: the first replaces what we have, the
        // rest extend it.
        const incoming: WhiteboardStroke[] = Array.isArray(msg.strokes) ? msg.strokes : [];
        if (msg.reset) {
          whiteboardStrokes.value = incoming;
          whiteboardRevision.value++;
        } else if (whiteboardStrokes.value.length < WHITEBOARD_MAX_STROKES) {
          whiteboardStrokes.value.push(...incoming);
        }
        if (incoming.length || msg.reset) whiteboardOpen.value = true;
        break;
      }
      case 'stroke':
        if (msg.stroke && whiteboardStrokes.value.length < WHITEBOARD_MAX_STROKES) {
          whiteboardStrokes.value.push(msg.stroke);
        }
        break;
    }
  }

  /** Host: open a *blank* board for the room. */
  async function openWhiteboard() {
    if (!isModerator.value) return;
    whiteboardStrokes.value = [];
    whiteboardRevision.value++;
    whiteboardOpen.value = true;
    scheduleWhiteboardSave(0);
    await publish({ type: 'whiteboard', action: 'open' }, 'whiteboard');
  }

  async function closeWhiteboard() {
    if (!isModerator.value) return;
    whiteboardOpen.value = false;
    scheduleWhiteboardSave(0);
    await publish({ type: 'whiteboard', action: 'close' }, 'whiteboard');
  }

  async function clearWhiteboard() {
    if (!isModerator.value) return;
    whiteboardStrokes.value = [];
    whiteboardRevision.value++;
    scheduleWhiteboardSave(0);
    await publish({ type: 'whiteboard', action: 'clear' }, 'whiteboard');
  }

  /** Host: one finished segment. Drawn locally at once, published immediately. */
  async function addWhiteboardStroke(stroke: WhiteboardStroke) {
    if (!isModerator.value || !whiteboardOpen.value) return;
    if (whiteboardStrokes.value.length >= WHITEBOARD_MAX_STROKES) return;
    whiteboardStrokes.value.push(stroke);
    scheduleWhiteboardSave();
    // Strokes are lossy on purpose: a dropped segment is a hairline gap that
    // the next packet continues from, while a reliable ordered channel makes
    // every peer wait behind a stalled packet. Snapshots (a whole board, sent
    // once to a late joiner) stay reliable -- losing one of those leaves a
    // hole no later packet can fill.
    await publish({ type: 'whiteboard', action: 'stroke', stroke }, 'whiteboard', false);
  }

  /**
   * Write the board to the session row so it outlives this tab.
   *
   * The strokes used to live only in memory and in the data channel, so a
   * refresh in the middle of a lecture blanked the board (and a host rejoining
   * from another device lost it entirely). The server copy is what a reload
   * restores from; the snapshot packet still hands it to everyone in the room.
   */
  function scheduleWhiteboardSave(delay = 1500) {
    if (!isModerator.value) return;
    whiteboardDirty = true;
    if (whiteboardSaveTimer) clearTimeout(whiteboardSaveTimer);
    whiteboardSaveTimer = setTimeout(() => {
      whiteboardSaveTimer = null;
      void flushWhiteboardSave();
    }, delay);
  }

  async function flushWhiteboardSave(): Promise<void> {
    const sid = activeSessionId.value;
    if (!sid || !isModerator.value || !whiteboardDirty) return;
    whiteboardDirty = false;
    try {
      await api.putDocument(`/api/v1/classrooms/sessions/${sid}/whiteboard/`, {
        open: whiteboardOpen.value,
        strokes: whiteboardStrokes.value,
      });
    } catch (e) {
      // Keep it dirty and try again: a board that could not be saved must not
      // look saved. The next stroke, or this retry, carries the whole board.
      whiteboardDirty = true;
      console.warn('Could not save the whiteboard:', e);
      if (!whiteboardSaveTimer) {
        whiteboardSaveTimer = setTimeout(() => {
          whiteboardSaveTimer = null;
          void flushWhiteboardSave();
        }, 5000);
      }
    }
  }

  /** Restore the board the host was drawing before a reload or a device switch. */
  async function loadWhiteboard(): Promise<void> {
    const sid = activeSessionId.value;
    if (!sid || !isModerator.value) return;
    try {
      const board = await api.getDocument<{ open?: boolean; strokes?: WhiteboardStroke[] }>(
        `/api/v1/classrooms/sessions/${sid}/whiteboard/`
      );
      const strokes = Array.isArray(board?.strokes) ? board.strokes : [];
      if (!strokes.length && !board?.open) return;
      whiteboardStrokes.value = strokes.slice(0, WHITEBOARD_MAX_STROKES);
      whiteboardRevision.value++;
      whiteboardOpen.value = !!board?.open;
    } catch (e) {
      // A missing board is normal for a class that never used one; anything
      // else leaves the board empty rather than blocking the classroom.
      console.warn('Could not restore the whiteboard:', e);
    }
  }

  /** Host: hand the board to whoever just arrived, in bounded packets. */
  async function publishWhiteboardSnapshot() {
    if (!isModerator.value || !whiteboardOpen.value) return;
    const all = whiteboardStrokes.value;
    if (!all.length) {
      await publish({ type: 'whiteboard', action: 'snapshot', strokes: [], reset: true }, 'whiteboard');
      return;
    }
    for (let i = 0; i < all.length; i += WHITEBOARD_SNAPSHOT_CHUNK) {
      await publish(
        {
          type: 'whiteboard',
          action: 'snapshot',
          strokes: all.slice(i, i + WHITEBOARD_SNAPSHOT_CHUNK),
          reset: i === 0,
        },
        'whiteboard'
      );
    }
  }

  // --- recording ---------------------------------------------------------
  // The MP4 is written by the server (LiveKit Egress), so this client only
  // ever holds a *claim* about a recording: what the last status call said.
  // Nothing here can make a recording exist -- that is the point.
  const recordings = ref<Recording[]>([]);
  const activeRecording = ref<Recording | null>(null);
  const recordingError = ref<string | null>(null);
  // Elapsed seconds for the REC timer, ticked only while a recording is live.
  const recordingSeconds = ref(0);
  let recordingPoll: ReturnType<typeof setTimeout> | null = null;
  let recordingClock: ReturnType<typeof setInterval> | null = null;

  const isRecording = computed(() => !!activeRecording.value?.isLive);

  /**
   * Is the recording still running but this tab unable to see the server?
   *
   * Recording is server-side, so a dropped browser connection does not end a
   * take -- the worker keeps writing the room. The badge says "paused" rather
   * than simply disappearing, which used to read as "the recording stopped".
   */
  const isRecordingPaused = computed(
    () =>
      isRecording.value &&
      !isConnected.value &&
      (connectionState.value === ConnectionState.Reconnecting ||
        connectionState.value === ConnectionState.Disconnected)
  );

  //: Where the live recording is remembered between page loads.
  const recordingStorageKey = (sessionId: string) => `podium_recording_${sessionId}`;

  /** Keep the last known live recording for this session, or forget it. */
  function rememberRecording(doc: Recording | null) {
    const sid = activeSessionId.value;
    if (!sid || typeof localStorage === 'undefined') return;
    try {
      if (doc?.id && doc.isLive) {
        localStorage.setItem(recordingStorageKey(sid), JSON.stringify(doc));
      } else {
        localStorage.removeItem(recordingStorageKey(sid));
      }
    } catch {
      /* private mode / quota: the live status call is still the authority */
    }
  }

  /** What this tab last knew, before any request has answered. */
  function recallRecording(): Recording | null {
    const sid = activeSessionId.value;
    if (!sid || typeof localStorage === 'undefined') return null;
    try {
      const raw = localStorage.getItem(recordingStorageKey(sid));
      if (!raw) return null;
      const doc = JSON.parse(raw) as Recording;
      return doc?.id && doc.isLive ? doc : null;
    } catch {
      return null;
    }
  }

  /**
   * Accepted, but the worker's pipeline is not capturing yet.
   *
   * The control shows a spinner over this window, but it stays pressable: the
   * server waits for capture before ending the take, so Stop here is a real
   * stop. (It used to be disabled instead, which read as a dead button for as
   * long as a slow worker took to start.)
   */
  const isRecordingStarting = computed(
    () => (activeRecording.value?.status || '').toLowerCase() === 'starting'
  );

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

  /**
   * Rebuild the roster, at most once per frame burst.
   *
   * Track events arrive in clusters (a camera publishes audio, video and a
   * metadata update within the same tick), and every rebuild replaces the
   * whole participant list, which re-renders every tile. One rebuild per 80ms
   * keeps the tiles in step while turning a burst into a single render.
   */
  let rosterUpdateTimer: ReturnType<typeof setTimeout> | null = null;
  function updateParticipants() {
    if (rosterUpdateTimer !== null) return;
    rosterUpdateTimer = setTimeout(() => {
      rosterUpdateTimer = null;
      const r = room.value;
      if (!r) return;
      const localName =
        participants.value.find((p) => p.isLocal)?.name || 'You';
      const localRole =
        participants.value.find((p) => p.isLocal)?.role || 'student';
      syncRoster(r, localName, localRole);
    }, 80);
  }

  /** Drop a queued roster rebuild (leaving the room, unmounting). */
  function cancelParticipantsUpdate() {
    if (rosterUpdateTimer !== null) {
      clearTimeout(rosterUpdateTimer);
      rosterUpdateTimer = null;
    }
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

  async function publish(payload: DataMessage, topic: string, reliable = true) {
    const r = room.value;
    if (!r) return;
    try {
      const bytes = new TextEncoder().encode(JSON.stringify(payload));
      await r.localParticipant.publishData(bytes, { reliable, topic });
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
        // Keep the roster badge in sync by touching that one tile. Replacing
        // the array here would give every tile a new object, and in a
        // 200-person room one raised hand would re-render all 200.
        const handTile = participants.value.find((p) => p.identity === identity);
        if (handTile) handTile.isHandRaised = !!msg.raised;
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
      case 'whiteboard': {
        // Host-driven only: see the whiteboard block above for why.
        if (!isWhiteboardAuthor(participant)) break;
        applyWhiteboardAction(msg);
        // A student's board is a live view of the host's copy, and the host's
        // copy is the one that gets written down. Never echo a student's view
        // over the board that will be restored from next time.
        if (!isModerator.value) break;
        if (msg.action !== 'stroke') scheduleWhiteboardSave(0);
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
        // A recording keeps running through this tab's outage; re-read it so
        // the badge comes back honest (and the stop button acts on the right
        // id) instead of waiting for the next poll.
        void resumeRecordingState();
        // Strokes drawn during the outage never reached the room.
        void publishWhiteboardSnapshot();
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
        // A late joiner missed every stroke drawn before they arrived.
        void publishWhiteboardSnapshot();
      })
      .on(RoomEvent.ParticipantDisconnected, () => updateParticipants())
      .on(RoomEvent.TrackSubscribed, () => updateParticipants())
      .on(RoomEvent.TrackUnsubscribed, () => updateParticipants())
      .on(RoomEvent.LocalTrackPublished, () => updateParticipants())
      .on(RoomEvent.LocalTrackUnpublished, () => updateParticipants())
      .on(RoomEvent.ActiveSpeakersChanged, () => {
        // Mutate only the tiles whose speaking state actually moved. This event
        // fires continuously in a busy room, so rebuilding the array would hand
        // every tile a new object and re-render the whole grid on each call.
        const speakers = new Set(r.activeSpeakers.map((s) => s.identity));
        for (const p of participants.value) {
          const speaking = speakers.has(p.identity);
          if (speaking !== p.isSpeaking) p.isSpeaking = speaking;
        }
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
    joinErrorCode.value = null;
    connectionState.value = 'idle';
    activeSessionId.value = sessionId;
    // The moderator is exempt from every lock; everyone else starts closed and
    // is opened up by initClassroom() (room policy) and loadOwnPermissions()
    // (their own approvals). joinRole records how we arrived so a later
    // co-host promotion can be revoked without losing sight of a real host.
    joinRole = role;
    isModerator.value = role === 'lecturer';
    void loadChatHistory(sessionId);
    // A take remembered from before the reload: show the REC badge and clock
    // from the first frame, then reconcile with the server below.
    const cachedRecording = recallRecording();
    if (cachedRecording) applyActiveRecording(cachedRecording);

    try {
      // 1. Request a LiveKit token from Django, for this class by id.
      //
      // The session-scoped route is the one that enforces the class's state:
      // it refuses a class the host has ended and reopens it when the host
      // returns. The generic /classrooms/token/ route takes a room name and
      // checks nothing, so going through it left "you cannot rejoin an ended
      // class" resting on client-side checks that a refresh could skip.
      const tokenDoc = await api.postDocument<{
        token: string;
        roomName: string;
        identity: string;
        livekitUrl?: string;
      }>(`/api/v1/classrooms/sessions/${sessionId}/token/`, {
        participantName,
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
      // A recording may already be running (page refresh, co-host joining
      // halfway) -- pick it up so nobody's UI lies about the lecture being
      // unrecorded.
      void resumeRecordingState();
      // Same for the board: restore the host's copy, then hand it to whoever
      // is already in the room.
      if (isModerator.value) {
        void loadWhiteboard().then(() => publishWhiteboardSnapshot());
      }
      return newRoom;
    } catch (e: any) {
      console.error('Classroom connection failed:', e);
      hasFailed.value = true;
      connectionState.value = 'failed';
      joinErrorCode.value = e?.code || null;
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
    const selfTile = participants.value.find((p) => p.isLocal);
    if (selfTile) selfTile.isHandRaised = raised;
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

  // --- recording ---------------------------------------------------------

  function stopRecordingClock() {
    if (recordingClock) {
      clearInterval(recordingClock);
      recordingClock = null;
    }
  }

  function stopRecordingPoll() {
    if (recordingPoll) {
      clearTimeout(recordingPoll);
      recordingPoll = null;
    }
  }

  /**
   * Fold one status answer into this client's view of the live recording.
   *
   * Going live starts a 1s tick for the REC timer and a 5s poll of the status
   * route; finishing stops both and reloads the shelf, because the MP4 the
   * worker just closed is now something the class can watch. Calling this
   * again while live only refreshes the record -- the timers are started once.
   */
  function applyActiveRecording(doc: Recording) {
    const live = !!doc.isLive;
    const wasLive = !!activeRecording.value;

    if (!live) {
      activeRecording.value = null;
      stopRecordingClock();
      stopRecordingPoll();
      rememberRecording(null);
      recordingSeconds.value = 0;
      if (wasLive) {
        // A recording that ends on its own ended badly: say why, in the same
        // banner the classroom already uses for recording news, rather than
        // letting the REC badge simply disappear.
        if (doc.status === 'failed' || doc.status === 'aborted') {
          pushSystemMessage(doc.error || 'The recording did not finish.');
        }
        void loadRecordings();
      }
      return;
    }

    activeRecording.value = doc;
    rememberRecording(doc);
    if (wasLive) return;

    recordingSeconds.value = doc.startedAt
      ? Math.max(0, Math.floor((Date.now() - new Date(doc.startedAt).getTime()) / 1000))
      : 0;
    recordingClock = setInterval(() => {
      recordingSeconds.value += 1;
    }, 1000);
    scheduleRecordingPoll();
  }

  /**
   * Poll the live recording, faster while the pipeline is coming up.
   *
   * Self-scheduling rather than a fixed interval: the row is only interesting
   * for a second or two after start (the control is showing "Starting..."),
   * and then it is a five-second heartbeat for the rest of the lecture.
   */
  function scheduleRecordingPoll(delay = 1200) {
    stopRecordingPoll();
    recordingPoll = setTimeout(async () => {
      const sid = activeSessionId.value;
      const id = activeRecording.value?.id;
      if (!sid || !id) return;
      let failed = false;
      try {
        const fresh = await api.getDocument<Recording>(
          `/api/v1/classrooms/sessions/${sid}/recordings/${id}/`
        );
        applyActiveRecording(fresh);
      } catch {
        // A dropped poll changes nothing: the server's row is still the truth
        // and the next tick catches up. While the browser is offline the poll
        // backs off instead of retrying every tick, so a flaky connection does
        // not spend the whole bandwidth budget on status calls.
        failed = true;
      }
      // Finishing (or leaving) stops the chain; applyActiveRecording has
      // already cleared activeRecording in that case.
      if (activeRecording.value?.id === id) {
        const next = failed
          ? 10000
          : isRecordingStarting.value
            ? 1200
            : 5000;
        scheduleRecordingPoll(next);
      }
    }, delay);
  }

  /**
   * Everything recorded for this session, newest first -- the replay shelf.
   *
   * Answers whether the read actually succeeded: a caller reconciling the live
   * badge must not treat "the network was down" as "nothing is recording".
   */
  async function loadRecordings(): Promise<boolean> {
    const sid = activeSessionId.value;
    if (!sid) return false;
    try {
      const res = await api.getDocuments<Recording>(
        `/api/v1/classrooms/sessions/${sid}/recordings/`
      );
      recordings.value = res.documents ?? [];
      return true;
    } catch (e) {
      // Recordings switched off by an admin is a configuration, not a fault;
      // the button that would call this is hidden in that case anyway.
      if ((e as { code?: string })?.code !== 'FEATURE_DISABLED') {
        console.warn('Could not load recordings:', e);
      }
      return false;
    }
  }

  /**
   * Re-attach to a recording that is already running: a host who refreshes
   * mid-lecture, or a co-host who joins halfway through. Without this the
   * server would keep writing an MP4 that nobody's UI knows about.
   *
   * The cached id is applied *before* this runs, so the REC badge is on screen
   * from the first frame after a reload; this call then reconciles it with the
   * server, and drops it if the take finished while the tab was gone.
   */
  async function resumeRecordingState() {
    const ok = await loadRecordings();
    if (!ok) return;
    const live = recordings.value.find((r) => r.isLive) || null;
    if (live) {
      applyActiveRecording(live);
      return;
    }
    if (activeRecording.value) {
      activeRecording.value = null;
      stopRecordingClock();
      stopRecordingPoll();
      recordingSeconds.value = 0;
      rememberRecording(null);
    }
  }

  /** Ask the server to start recording this room (server-side MP4). */
  async function startRecording(): Promise<boolean> {
    const sid = activeSessionId.value;
    if (!sid || isRecording.value) return false;
    // If the session is no longer active (ended/deleted), there is nothing to
    // record — return early without showing an error in chat.
    const sessionDoc = await api.getDocument<ClassroomSession>(`/api/v1/classrooms/sessions/${sid}/`).catch(() => null);
    if (!sessionDoc?.is_active) return false;
    recordingError.value = null;
    try {
      const doc = await api.postDocument<Recording>(
        `/api/v1/classrooms/sessions/${sid}/recordings/`,
        {}
      );
      applyActiveRecording(doc);
      void loadRecordings();
      pushSystemMessage('Recording started. The video file is written on the server.');
      return true;
    } catch (e) {
      // 503 RECORDING_UNAVAILABLE is the honest answer when the worker is
      // down; the message says so rather than leaving a dead button.
      recordingError.value = (e as { message?: string })?.message || 'Recording could not be started.';
      pushSystemMessage(recordingError.value);
      console.warn('Could not start recording:', e);
      return false;
    }
  }

  /**
   * Finish the recording cleanly. Egress answers "ending" and finalises the
   * MP4 a moment later, so this keeps polling until the file is whole.
   */
  async function stopRecording(): Promise<boolean> {
    const sid = activeSessionId.value;
    const id = activeRecording.value?.id;
    if (!sid || !id) return false;
    recordingError.value = null;
    try {
      const doc = await api.postDocument<Recording>(
        `/api/v1/classrooms/sessions/${sid}/recordings/${id}/stop/`,
        {}
      );
      applyActiveRecording(doc);
      pushSystemMessage('Finishing the recording...');
      return true;
    } catch (e) {
      const err = e as { message?: string; code?: string; status?: number };
      if (err.status === 404 || err.code === 'NOT_FOUND') {
        // The remembered take does not exist any more (cleared database, a
        // deleted class). Forget it, so the control offers Record again
        // rather than a stop that can never work.
        activeRecording.value = null;
        stopRecordingClock();
        stopRecordingPoll();
        recordingSeconds.value = 0;
        rememberRecording(null);
      }
      recordingError.value = err.message || 'The recording could not be stopped.';
      pushSystemMessage(recordingError.value);
      console.warn('Could not stop recording:', e);
      return false;
    }
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
    for (const p of participants.value) {
      if (p.isHandRaised) p.isHandRaised = false;
    }
  }

  async function leaveClassroom() {
    // Anything drawn since the last save belongs on the server before this tab
    // forgets it. The request captures the strokes synchronously, so it is
    // safe to clear the local copy immediately below.
    if (whiteboardDirty) void flushWhiteboardSave();
    if (whiteboardSaveTimer) {
      clearTimeout(whiteboardSaveTimer);
      whiteboardSaveTimer = null;
    }
    cancelParticipantsUpdate();
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
    // Leaving stops watching, never the recording itself: the MP4 belongs to
    // the room, which outlives this tab (it finalises when the room closes).
    stopRecordingClock();
    stopRecordingPoll();
    // The remembered id stays in localStorage on purpose: leaving the page is
    // what a refresh looks like from here, and the next join should pick the
    // same take straight back up.
    activeRecording.value = null;
    recordingSeconds.value = 0;
    recordingError.value = null;
    recordings.value = [];
    isMutedAll.value = false;
    autoApproveMic.value = false;
    permissionPolicy.value = { mic: 'free', camera: 'free', screen: 'free' };
    myPermissions.value = { mic: false, camera: false, screen: false };
    requestedKinds.value = { mic: false, camera: false, screen: false };
    pendingRequests.value = [];
    // The board belongs to the sitting, not to this tab: leaving closes our
    // view of it and drops the strokes with it.
    whiteboardOpen.value = false;
    whiteboardStrokes.value = [];
    whiteboardRevision.value++;
    sessionHostId.value = null;
  }

  return {
    currentSession,
    room,
    isConnected,
    isConnecting,
    connectionState,
    hasFailed,
    errorMessage,
    joinErrorCode,
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
    // whiteboard
    whiteboardOpen,
    whiteboardStrokes,
    whiteboardRevision,
    sessionHostId,
    setSessionHost,
    openWhiteboard,
    closeWhiteboard,
    clearWhiteboard,
    addWhiteboardStroke,
    publishWhiteboardSnapshot,
    loadWhiteboard,
    flushWhiteboardSave,
    recordings,
    activeRecording,
    recordingSeconds,
    recordingError,
    isRecording,
    isRecordingStarting,
    isRecordingPaused,
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
    loadRecordings,
    startRecording,
    stopRecording,
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
