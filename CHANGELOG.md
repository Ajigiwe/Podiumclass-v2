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

**Not built yet (decision needed)**: class **recording** (the `recordings` feature flag and `SessionMaterial`/`Recording` scaffolding exist, but there are no start/stop endpoints or player UI) and **co-hosting** (`CoHost` / `Session.backup_mod_id` are unused).
