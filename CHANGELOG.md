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
