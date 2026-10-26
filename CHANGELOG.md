# PodiumClass v2: Progress & Recent Changes Log

## Overview
PodiumClass v2 is an enterprise-grade virtual classroom and learning management system decoupled into a **Django 6 REST API**, **PostgreSQL 16** persistence layer, **Vue 3 (Composition API) + Pinia** frontend, and **LiveKit WebRTC SFU** media engine.

---

## Recent Major Milestones & Enhancements

### 0. Native Android Companion App (Kotlin & Jetpack Compose)
- **Native Android Project Architecture (`android/`)**:
  - Built with **Kotlin 2.0+**, **Jetpack Compose (Material 3)**, and **Gradle Version Catalog** (`libs.versions.toml`).
  - Native **`io.livekit:livekit-android:2.8.+`** integration for WebRTC SFU streaming and data channel packets.
  - **Retrofit 2 + Kotlinx Serialization** client adhering strictly to the Document Object contract (`{ "document": ... }`).
  - **DataStore** JWT credential persistence and runtime API endpoint configuration.
- **Classroom Moderation & Permissions Architecture**:
  - **Room Moderation Dialog (`RoomModerationModal.kt`)**: Host and Co-Hosts can toggle global Mute All / Unmute All, configure Smart Mic auto-approval, set per-medium policy (Ask vs Free for Mic, Camera, Screen), manage the live permission requests inbox, appoint/remove co-hosts, and end sessions.
  - **Smart Mic Auto-Approval**: Microphone requests automatically answered by server when enabled, turning a student ask into an immediate live microphone.
  - **Media Locks & Permission Requests**:
    - When media policy requires approval, student mic and camera buttons display lock indicators.
    - Double-tap or flakiness protected idempotent permission requests (`POST /api/v1/classrooms/sessions/<id>/permissions/requests/`).
    - Immediate visual feedback with floating animated notification banners.
  - **Co-Host Parity & Badging**:
    - Co-hosts are appointed and removed via `/api/v1/classrooms/sessions/<id>/cohosts/`.
    - Co-hosts obtain full moderator privileges while session authority remains safeguarded with the session owner.
    - Stage video tiles display distinct cyan `CO-HOST` badges alongside existing amber `HOST` badges.
  - **LiveKit Data Channel Packet Handling**:
    - Handles incoming `moderation` packets with immediate local mute enforcement on `isMutedAll`.
    - Handles `permission_request` inbox refreshes and `permission_response` auto-enabling of granted media.
    - Durable state resync on WebRTC reconnection (`RoomEvent.Reconnected`).
- **Dual-Role Live Classroom (`ClassroomScreen.kt`)**:
  - Meet-style dynamic grid & 1-click spotlight pinning.
  - Native Android **Picture-in-Picture (`PiP`)** allowing background video/audio when switching apps.
  - Floating control bar with mic, camera, camera flip, and upward-anchored reactions & hand-raising sub-popup.
  - Real-time in-class chat drawer.
- **Mobile Anti-Cheat Attendance Checker**:
  - **Host Manager Modal (`HostAttendanceManagerModal.kt`)**: Real-time check count increment, prompt duration pills, interval adjuster, pause/resume, instant trigger, and live student roster.
  - **Student Verification Dialog (`StudentVerificationDialog.kt`)**: Teleported non-dismissible dialog with synthesized audio chime, device vibration, countdown timer with urgency color-shifting, and instant "I AM PRESENT" submission to PostgreSQL.
- **Dashboard & Session Creation (`DashboardScreen.kt`)**:
  - Active session discovery and class creation dialog with Automated Attendance Checker settings.

### 1. Automated Attendance Checker & Anti-Cheat Verification System
- **Pre-Class Session Configuration (`DashboardView.vue` & Django Backend)**:
  - Added Automated Attendance Checker settings directly in the "Create Class" modal.
  - Hosts configure:
    - **Checker Enable/Disable Toggle**.
    - **Target Verification Checks** (`verificationCount`, default 3).
    - **Prompt Timeout Duration** (`timeLimitSeconds`, default 30s).
  - Saved to PostgreSQL `Session.verification_count` and `Session.auto_attendance_settings` JSONField.
- **In-Class Dynamic Reconfiguration & Host Manager Modal (`ClassroomView.vue`)**:
  - Real-time status header badge displaying live state (`Auto: X/Y`, `Done`, or `Paused`).
  - Interactive Host Modal allowing the lecturer to:
    - **Increase/Add Target Checks** dynamically on the fly (`+1 Check`, `+2 Checks`, or custom input).
    - **Adjust Modal Display Duration** (15s, 30s, 45s, 60s presets or custom seconds).
    - **Adjust Check Intervals** (5m, 10m, 15m, 20m presets or custom minutes).
    - **Pause and Resume** the automated checker engine at any point.
    - **Trigger Instant Checks** on demand with broadcast to all students.
    - **Persist Settings** to database via `PATCH /api/v1/classrooms/sessions/<sessionId>/`.
    - **View Live Attendance Roster**: Displays verified participant counts, average class attendance percentage, and real-time response records.
- **Automated Verification Engine**:
  - Background countdown timer runs throughout the session, automatically triggering checks at configured intervals without requiring manual host actions.
- **Student Verification Modal**:
  - Teleported to `document.body` with `z-[99999]` preventing z-index clipping or occlusion by video tiles and control bars.
  - **Dual Alert Feedback**: Synthesizes an audible alert tone via `Web Audio API` and triggers device vibration via `navigator.vibrate`.
  - **Animated Visual Countdown**: Large digital timer accompanied by a dynamic progress bar that shifts from Blue to urgent flashing Red when under 10 seconds.
  - **Anti-Cheat Response Action**: Full-width high-contrast "I AM PRESENT" button with instant visual confirmation ("PRESENT RECORDED!") before smooth exit.

---

### 2. Video Stage Canvas & Layout Modes (Google Meet Style)
- **Grid vs. Fill Screen (Spotlight / Pin)**:
  - Added one-click layout toggle between dynamic responsive grid view and single-participant spotlight view.
  - Participant video tiles support pinning to expand into the spotlight stage.
- **Aspect Ratio Control**:
  - Toggle between **Fit Video** (preserve natural camera/screen aspect ratio) and **Fill Video** (edge-to-edge crop without black borders).
- **Responsive Stage Geometry**:
  - Clean flex flow ensuring mobile screens maintain natural height without pushing the stage off-screen or cramping video feeds.

---

### 3. Picture-in-Picture (PiP) Multitasking
- **Native OS Picture-in-Picture**:
  - Integrates the browser's native `requestPictureInPicture` API, enabling students and faculty to float classroom video on top of any application when switching tabs or desktop windows.
- **In-App Floating Draggable Video Window**:
  - Smooth mouse and touch drag support for floating video tile inside the app when reviewing chat, course materials, or participant rosters.

---

### 4. Ergonomic Control Bar & Sub-Popups
- **Streamlined Control Bar**:
  - Clean, uncluttered layout matching Google Meet ergonomics.
  - Merged Emoji reactions and Raise Hand actions into an intuitive sub-popup.
  - Anchored strictly above the control bar (`style="bottom: calc(100% + 14px);"`) preventing downwards overflow or clipping.

---

### 5. Persistent Chat System
- **Dual-Layer Chat Architecture**:
  - Instant local delivery via LiveKit data packets.
  - Server-side persistence in PostgreSQL (`/api/v1/classrooms/sessions/<id>/messages/`).
  - Local caching in `localStorage` for instant message restoration on page refresh.
  - Separate mobile modal and desktop side-drawer chat layouts with auto-scrolling to latest messages.
- **Server-history loading fix**:
  - The renderer wraps a list as `{ "documents": [...] }`, but the loader read it with `getDocument()`, which returns the envelope itself — `Array.isArray()` then failed and PostgreSQL history silently never appeared after a refresh. It now uses the collection helper, so a message sent in another tab or on another device shows up.

---

### 6. Architecture & Quality Engineering
- **Strict Document Object REST Pattern**:
  - Standardized JSON responses enveloped under `{ "document": { ... } }` and `{ "documents": [ ... ] }`.
- **External Attendance API Compatibility**:
  - Dual-use authentication (`PodiumAuthentication`) supporting both user JWT tokens and server-to-server Laravel service keys.
- **Zero TypeScript / Vue Compiler Errors**:
  - Full type safety verified across the frontend bundle with `vue-tsc -b && vite build`.

---

### 7. Course Materials — the storage app is wired up
- **What was half built**: `apps.storage` was registered (with a migration) but had no views and no routes, so `GET /api/v1/classrooms/sessions/<id>/materials/` returned 404 and the classroom's Learning Materials modal was permanently empty.
- **Endpoints** (any signed-in user may read; host, lecturer or admin may share):
  - `GET    /api/v1/classrooms/sessions/<id>/materials/` → `{ "documents": [ ... ] }`
  - `POST   /api/v1/classrooms/sessions/<id>/materials/` → `{ "document": { ... } }`
  - `DELETE /api/v1/classrooms/sessions/<id>/materials/<material_id>/`
- **Validation**: the modal renders `url` straight into an anchor, so links must be absolute `http(s)`; `javascript:` and relative values are rejected with `VALIDATION_ERROR`.
- **UI**: the modal gained an empty state, a host-only share form (`+` in the header) and a per-row remove button. Sizes are formatted server-side (`2.4 MB`), and a legacy MIME type in `file_type` is normalised to the `pdf / video / slides / doc / link` kinds the modal expects.
- **Wipe tool**: `wipe_mock_data` now counts and clears `materials` and `recordings` alongside the rest of the mock data.

---

### 8. Classroom moderation — mute lock + permission requests

**The problem**: the host's "Mute All" only painted a message in student chat — nothing was stored, nothing was enforced, and the `PermissionRequest` / `ParticipantPermission` models were dead code. A locked-down room was advisory only.

- **Durable state** (`Session` is the source of truth, the LiveKit data channel carries live updates):
  - `isMutedAll`, `autoApproveMic` (Smart Mic) and `permissions: { mic, camera, screen }` (`free` | `approval`) are written through `PATCH /api/v1/classrooms/sessions/<id>/` and re-read by anyone joining or refreshing, so the lock survives a reload even if the data packet is lost.
- **Endpoints**:
  - `GET|PATCH /api/v1/classrooms/sessions/<id>/permissions/` → own standing grants; host may grant/revoke.
  - `GET|POST   /api/v1/classrooms/sessions/<id>/permissions/requests/` → host inbox / student ask (idempotent — re-asking an open request does not duplicate it).
  - `PATCH      /api/v1/classrooms/sessions/<id>/permissions/requests/<request_id>/` → approve or reject.
- **Enforcement rule** (single source of truth, mirrored in the client): `allowed(kind) = personalGrant || (kind !== 'mic' || !isMutedAll) && policy[kind] === 'free'`. Moderators are always exempt.
  - **Mute All revokes standing mic grants server-side** — releasing the mute does not hand anyone their microphone back; they have to ask again (or Smart Mic answers for them).
  - **Smart Mic** auto-approves `mic` asks server-side (`reviewed_by: "smart-mic"`) and returns `201` with `status: "approved"` in the same round trip, so the student never waits.
- **UI (`RoomModeration.vue`, shown to host/lecturer/admin)**: Mute All / Unmute All, the Smart Mic checkbox, per-medium `Free`/`Ask` policy chips, and a live pending-request inbox with Allow / Deny. The student control bar shows a lock badge and an amber ring on locked media, with an amber banner explaining the lock (`"Your mic is locked by the host. Click to ask for permission."` → `"Waiting for the host to let you use the mic..."`).
- **Live verification** (two peers, two origins): lock → banner + lock badge on the student → student asks → request lands in the host inbox (`201`, `reviewed_by` empty) → Allow (`200`, `approved`) → student unlocked with *"The host allowed your microphone."* in chat; Smart Mic → `201 approved / reviewedBy: smart-mic` with no host action; Mute All → banner + chat on the student and **still locked after a full page refresh**. Room reset to `free`/unmuted afterwards and the test rows deleted.
- **Surviving a dropped connection** (found by killing the LiveKit server mid-class):
  - Data packets are fire-and-forget — a moderation change published while a peer is down is simply lost. `RoomEvent.Reconnected` now re-reads the session row (and our own grants, and the host's inbox) instead of keeping whatever the room believed before the outage.
  - The host's "republish when someone joins" used to broadcast its own cache, which could overwrite a fresher state the joiner had just fetched; it now re-reads first and only then publishes.
  - `persistModeration` reports success once the **row** is written: a broadcast that fails because a peer is mid-reconnect no longer shows the host a false *"That moderation change could not be saved."* (verified live with the data channel down).
- **`.gitignore`**: added `android/.gradle/` and `android/local.properties` — machine-local Gradle state and the SDK path were showing up as untracked.

### 9. `dev_login` is now gated by an environment variable

- **Before**: `if settings.DEBUG and request.data.get("dev_login")` — any deployment left on `DEBUG` handed out a fully privileged admin JWT for a bare `POST /api/v1/accounts/google/`.
- **Now**: `DEV_LOGIN_ENABLED = env_bool('DJANGO_DEV_LOGIN', False)` (in `.env.local`), and every point of use checks `DEV_LOGIN_ENABLED and DEBUG`, so production (`DJANGO_DEBUG` off) can never enable it however the switch is set. Boot logs a warning either way.
- **Surface**: `GET /api/v1/core/features/` now also returns `devLogin` (boolean), so the login page shows the "DEV / MOBILE QUICK ACCESS" buttons only when the Vite dev build *and* the backend switch both say yes — it fails closed if the flag fetch fails.
- **Tests**: 7 new cases covering debug-alone, production-with-switch-set, the enabled paths, and the flag exposure. Suite: **161 passing**.

### 10. Co-hosting �?" the room can be run by more than one person

**The problem**: `CoHost` and `Session.backup_mod_id` existed but nothing read them, so a lecturer who wanted help controlling a rowdy class had to hand over their own login.

- **Single source of truth (`apps/classrooms/access.py`)**: three predicates -- `is_session_authority` (host/lecturer/admin of *this* session), `is_cohost`, `can_moderate` -- and everything else derives from them, so the API, the materials view, the attendance view and the UI can never disagree about who runs the room.
- **Co-host = full moderation parity**: Mute All / Unmute All, Smart Mic, media policy, approve/deny requests, share and remove materials, attendance roster and verification trigger. Exactly three powers stay with the session authority: appointing/removing co-hosts, ending the session, and editing non-moderation settings.
- **Endpoints**: `GET|POST /api/v1/classrooms/sessions/<id>/cohosts/`, `DELETE .../cohosts/<user_id>/` (403 for everyone else, 404 rather than 403 for another session's roster so ids don't leak). `GET .../permissions/` now answers `cohost` for the caller.
- **Trust model**: co-host changes are published on the `moderation` topic as `type: 'cohost'` so peers refetch badges, but *this client's own* status is only ever taken from the REST answer -- a browser must not be able to promote itself by editing local state.
- **Frontend**: `canModerate` gates the promote/demote buttons in both roster lists, the CO-HOST badges (roster row, video tile, header suffix), the attendance button and modal, both `<RoomModeration>` panels and the materials share/trash controls. Session-level gates (Start Class Now, session-not-active, ending the class) stay on `isLecturer` alone.
- **Live verification** (two tabs): host appoints �+' badge + panel appear on the student; refresh �+' durable server-answered status; co-host clicks UNMUTE ALL �+' `is_muted_all=False` in the DB and the host's UI follows live; remove �+' panel and label vanish without a reload; the demoted student has zero moderator controls left. It also flushed out a real render bug (a stale Pinia/Vite-HMR store left `coHostIds` undefined, so `isCoHostRow` threw and blocked rendering with 442 console errors) -- a hard reload cleared it; no code change was needed.
- **Tests**: 27 new across `classrooms/tests.py`, `storage/tests.py`, `attendance/tests_auth.py`.

### 11. Recording �?" server-side MP4 via LiveKit Egress

**Decision**: the MP4 is written by the server. The file has to survive the host closing their laptop, and only the server can composite everyone into one video, so there is no browser `MediaRecorder` path anywhere in this project.

- **Endpoints** (storage app, routed from `apps/classrooms/urls.py`, all behind `require_feature("recordings")`):
  - `GET|POST /api/v1/classrooms/sessions/<id>/recordings/` �+' `{ "documents": [...] }` / start
  - `GET    /api/v1/classrooms/sessions/<id>/recordings/<rid>/` �+' status (the UI polls this while live)
  - `POST   .../<rid>/stop/` �+' finish cleanly (idempotent -- stopping twice is not an error)
  - `GET    .../<rid>/file/` �+' streams the MP4 with `Range` (`206`/`416`), because a lecture is watched with a seek bar
- **Fail closed, the same rule payments follow**: feature off �+' `503 FEATURE_DISABLED`; not a moderator (host/lecturer/co-host) �+' 403; session already ended �+' 400; worker unreachable, Redis down, or no `egress_id` in the answer �+' `503 RECORDING_UNAVAILABLE` **and no row is written**; twirp refusal (e.g. room not running) �+' `400 RECORDING_REJECTED`. One recording per session: pressing record again returns the running one instead of starting a second file.
- **Honesty rules for reads**: a live row is refreshed from the worker; if the worker has no memory of the id, a file on disk wins (`complete`) and no file means `failed` with an explicit error; a worker that is down never 503s a *read* (the last known state beats an error page); `fileUrl` is only offered when the MP4 really exists; only a basename is stored and it is re-joined to `RECORDINGS_ROOT` on every read, so a hand-written row can never escape the recordings folder.
- **Topology**: `Django �+' LiveKit server /twirp/livekit.Egress/* (7880) �+' Redis bus �+' egress container �+' /out/recordings = .livekit/recordings`. The server owns the Egress twirp API (confirmed by inspecting `livekit-server.exe`, which contains `/twirp/`, `StartRoomCompositeEgress`, `StopEgress`, `ListEgress`), so the worker exposes no second API port and nothing has to be published for Django to reach it.
- **Infra (ready to run, pending hardware)**: `.livekit/egress.local.yaml` (gitignored, shares the server's API key/secret, `ws_url: ws://host.docker.internal:7880` + `insecure: true`, `redis.address: host.docker.internal:6379`), `redis:` added to `livekit.local.yaml`, and `scripts/start_redis.ps1` / `scripts/start_egress.ps1` mirroring `start_livekit.ps1` (idempotent, and they name exactly what is missing: Docker, Redis, image, config). Egress is pinned to `livekit/egress:v1.13.0` to match `livekit-server 1.13.10`, runs with `--shm-size=1g` (Chrome composites the room grid; the 64 MB default dies mid-lecture) and `--cap-add=SYS_ADMIN`.
- **UI**: record button in the floating control bar (moderator + feature flag), pulsing REC chip with a live elapsed timer in the stage overlay, a Recordings button in the top bar for everyone, and a modal shelf with `<video>` playback. The store polls every 5s while live and **re-attaches after a refresh** -- a host reloading mid-lecture must not believe the lecture is unrecorded -- and leaving the room stops *watching* the recording, never the recording itself.
- **Tests**: 23 new in `apps/storage/tests.py` (`RecordingApiTests`) covering the flag, permissions, start/stop idempotency, both failure classes, poll finalisation, the forgotten-worker fallback, Range/416/malformed-Range, missing files and the path-escape guard. Suite: **211 passing**; `vue-tsc -b` and `vite build` clean.
- **Blocked on hardware**: WSL 3.0.3 installed, but this machine reports `VirtualizationFirmwareEnabled: False` (VT-x disabled in BIOS) so WSL2 cannot create its VM -- Docker dies with `Wsl/Service/RegisterDistro/CreateVm/HCS/HCS_E_HYPERV_NOT_INSTALLED`. Enabling virtualization in firmware (+ reboot) unlocks `start_redis.ps1` �+' `start_egress.ps1` �+' the first real MP4; until then the API answers `503 RECORDING_UNAVAILABLE`, which is the correct behaviour for a machine that cannot record.
