# PodiumClass — Complete Stack & Project Reference

One document covering every layer of the system: what it is, what it is built
with, how the pieces talk, how it is deployed, and what it does not do yet.

---

## 1. What this is

PodiumClass is a virtual classroom / learning-management system for universities.
A lecturer opens a class, students join with a code or link, and the class runs
as a live WebRTC room with attendance verification, moderation, screen sharing,
a whiteboard, chat, shared materials, and recordings.

Two clients sit on one API:

| Client | Where | Entry point |
| --- | --- | --- |
| **Web app** (SPA + PWA) | Browser, installable | `frontend/` |
| **Android app** (native) | Phone | `android/` |

Both speak the same REST API and join the same LiveKit rooms, so a lecture can
be half desktop and half mobile with no separate backend.

### Roles

| Role | Can do |
| --- | --- |
| `student` | Join by code/link, wait in the lobby, answer attendance checks, chat, ask for mic/camera |
| `lecturer` | Everything a student can, plus create/end classes, moderate, record, share materials, run the whiteboard, read attendance |
| `admin` | Everything a lecturer can, plus user management, class-creation grants, platform settings and feature flags |

An admin can additionally grant `can_create_classes` to any individual account
without changing its role — the flag is additive and revocable.

---

## 2. Repository map

```
lite-lms/
├── backend/                 Django 6 REST API (the only server)
│   ├── config/              settings.py, urls.py, wsgi/asgi
│   ├── apps/
│   │   ├── accounts/        User model, Google sign-in, JWT, avatars, admin user API
│   │   ├── classrooms/      Session, co-hosts, participants, permissions, chat, whiteboard, LiveKit tokens
│   │   ├── attendance/      Register, verification rounds, exports, external (Laravel) API
│   │   ├── storage/         Recordings (egress), shared materials, file streaming
│   │   ├── billing/         Wallet + transactions
│   │   └── core/            Settings registry, feature flags, health, error views, LiveKit URL resolution
│   ├── scripts/             Dev utilities (load-test seeding, auth smoke test)
│   ├── Dockerfile           gunicorn image for the VPS stack
│   └── entrypoint.sh        migrate → collectstatic → gunicorn
├── frontend/                Vue 3 SPA (Vite), installable PWA
│   ├── src/views/           Landing, Login, Dashboard, Classroom, Attendance, History, Wallet, Profile, Admin, About
│   ├── src/components/      Whiteboard, moderation panel, settings panel, toast host
│   ├── src/stores/          Pinia: auth, classroom, features, toast
│   ├── src/lib/             API helpers, clipboard, analytics, presence
│   ├── public/              manifest.json, sw.js, icons, developer photo
│   ├── nginx.conf           Static image + same-origin /api reverse proxy
│   └── Dockerfile           Node build → nginx
├── android/                 Kotlin + Jetpack Compose app
│   └── app/src/main/java/online/podiumclass/app/
│       ├── data/api/        Retrofit service, models, auth + cold-start interceptors
│       ├── data/livekit/    LiveKitRoomManager — the room, tracks, data packets
│       ├── data/notify/     Notification channels and posts
│       ├── data/recording/  On-device screen recorder (foreground service)
│       ├── data/session/    In-class foreground service (class connection anchor)
│       ├── data/storage/    DataStore: token, profile, theme, API URL
│       └── ui/              MainActivity (NavHost) + screens, theme, components
├── deploy/                  Production plumbing
│   ├── deploy.sh            Idempotent stack bring-up / update
│   ├── Caddyfile            TLS + reverse proxy for the VPS
│   ├── livekit.yaml.template / egress.yaml.template
│   ├── .env.example         Every production variable, documented
│   ├── publish_frontend.py  Build + upload the SPA to a panel host over WinSCP/.NET SSH
│   ├── render-config.py     Generate render.yaml env from a local .env
│   └── backup.sh            Database + uploads + recordings snapshot
├── docker-compose.prod.yml  Full VPS stack (db, redis, livekit, egress, backend, frontend, caddy)
├── docker-compose.yml       Local dev stack (vite + runserver)
├── render.yaml              Render blueprint: API web service + static site
├── loadtest/                Measured capacity work (see §11)
├── scripts/                 Local LiveKit / egress / Redis launchers (PowerShell)
├── DEPLOY_VPS.md            Step-by-step VPS runbook
├── CHANGELOG.md             What changed, and why
└── PROJECT_STACK.md         This file
```

---

## 3. Stack at a glance

| Layer | Technology | Version |
| --- | --- | --- |
| API framework | Django + Django REST Framework | Django ≥ 6.0, DRF ≥ 3.15 |
| ASGI/WSGI server | Gunicorn (2 workers × 4 threads on Render) | ≥ 22 |
| Database (prod) | PostgreSQL | 16 |
| Database (dev) | SQLite | bundled |
| DB driver | psycopg 3 | ≥ 3.2 |
| Tokens | PyJWT (HS256, self-contained) | ≥ 2.8 |
| Cross-origin | django-cors-headers | ≥ 4.3 |
| Static (PaaS) | WhiteNoise | ≥ 6.7 |
| Google sign-in verify | google-auth | ≥ 2.30 |
| Media server | LiveKit SFU | `livekit/livekit-server:v1.13.10` |
| Recording worker | LiveKit Egress | `livekit/egress:v1.13.0` |
| Cache / egress assist | Redis | 7-alpine |
| Web framework | Vue 3 (Composition API) + Pinia + vue-router | 3.5 / 4 / 5 |
| Build tool | Vite + vue-tsc + TypeScript | 8.3 / 3.3 / 6.0 |
| Web media | `livekit-client` | 2.23 |
| Web PWA | Hand-written service worker + manifest | — |
| Web analytics | PostHog (optional, off without a key) | 1.438 |
| Web TLS/proxy (VPS) | Caddy 2, nginx (static image) | 2-alpine |
| Mobile language | Kotlin | 2.0.21 |
| Mobile UI | Jetpack Compose + Material 3 | Compose BOM 2024.10.01 |
| Mobile build | AGP / Gradle version catalog | 8.7.2 |
| Mobile media | `io.livekit:livekit-android` | 2.8.1 |
| Mobile HTTP | Retrofit + OkHttp + kotlinx.serialization | 2.11 / 4.12 / 1.7.3 |
| Mobile storage | DataStore Preferences | 1.1.1 |
| Mobile auth | Credential Manager + Google ID | 1.3.0 / 1.1.1 |
| Android targets | `minSdk 26`, `targetSdk 35`, `compileSdk 35`, versionName **2.1.1** (code 3) | — |

---

## 4. Backend (Django)

### 4.1 Settings and environment

`config/settings.py` is environment-driven and fails loudly in production when
something is missing: `DJANGO_DEBUG` unset defaults to **False**, and with debug
off it refuses to start without `ALLOWED_HOSTS`, `CORS_ALLOWED_ORIGINS`,
`CSRF_TRUSTED_ORIGINS` and a real `DJANGO_SECRET_KEY`.

Notable behaviours:

- **Development convenience:** with `DJANGO_DEBUG=True`, `ALLOWED_HOSTS` gets
  `*` and loopback/private-LAN origins (10.x, 172.16-31.x, 192.168.x, `*.local`)
  on any port are accepted, so a phone can hit the dev machine's LAN IP without
  editing anything.
- **Proxy awareness:** `DJANGO_BEHIND_PROXY` enables `SECURE_PROXY_SSL_HEADER`
  for Render/Caddy; `DJANGO_SECURE_SSL_REDIRECT` and `DJANGO_SECURE_HSTS_SECONDS`
  are opt-in.
- **WhiteNoise** is inserted after `SecurityMiddleware` only when debug is off —
  on the VPS nginx answers `/static/` first, on Render nothing else would.
- **Storage roots** are configurable and default to repo-local folders:
  `RECORDINGS_ROOT` (`.livekit/recordings`), `MATERIALS_ROOT`
  (`.podium/materials`), `AVATARS_ROOT` (`.podium/avatars`).
- **Upload ceilings:** `MATERIAL_MAX_BYTES` (100 MB), `AVATAR_MAX_BYTES` (5 MB),
  `MAX_UPLOAD_BYTES`.
- **JWT:** `PODIUM_JWT_SECRET`, `PODIUM_JWT_TTL_DAYS` (7), `PODIUM_JWT_ISSUER`
  (`podium-lms`).
- **LiveKit:** `LIVEKIT_API_URL`, `LIVEKIT_URL` (what clients are told),
  `LIVEKIT_EGRESS_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`.
- **Attendance API:** `ATTENDANCE_API_KEY` (service-to-service key),
  `ATTENDANCE_API_RATE_LIMIT` (120/min).

### 4.2 Apps and models

All primary keys are prefixed strings (`usr_…`, `ses_…`) or UUIDs, generated in
code, not auto-increment — ids appear in URLs, tokens and LiveKit identities.

| App | Models | Purpose |
| --- | --- | --- |
| `accounts` | `User` | Custom user (email login, role, `can_create_classes`, `status`, photo/avatar, wallet balance, `index_number`, `university`) |
| `classrooms` | `Session`, `CoHost`, `Participant`, `ParticipantPermission`, `PermissionRequest`, `ChatMessage` | The class itself: meeting code, lifecycle status, moderation flags (`auto_approve_mic`, `auto_approve_camera`, `is_muted_all`), attendance settings, host presence, whiteboard JSON |
| `attendance` | `Attendance`, `AttendanceLog`, `Verification`, `VerificationResponse` | Per-student presence, join/leave times, verification rounds and answers |
| `storage` | `Recording`, `SessionMaterial` | Egress recordings and host-shared files |
| `billing` | `Transaction` (wallet) | Top-ups, deductions, ledger |
| `core` | `SystemSetting` | Key/value platform settings + feature flags |

Attendance consequences worth knowing:

- `PRESENT_THRESHOLD = 50` — a student counts present at ≥50 % of the checks
  they were sent. One constant, used by the serializer, roster summary and
  session annotations.
- `Session.participant_count` is a **derived** column, recounted by `post_save`
  / `post_delete` signals on `Attendance` — not incremented ad hoc. Anything
  that reads "verified" numbers reads this.
- Checking is **server-scheduled** (`run_attendance_checker` management command,
  plus `verification/active` polling), so checks keep firing when no moderator's
  browser tab is awake.

### 4.3 API surface

Everything lives under `/api/v1/`. Responses use a **Document envelope**:

```json
{ "document": { ... } }          // one object
{ "documents": [ ... ], "total": 12 }   // collections
```

The external attendance API keeps its own flat `{"data": …}` shape because a
separate Laravel service consumes it.

**Classrooms** — `/api/v1/classrooms/`

| Method + path | Purpose |
| --- | --- |
| `GET/POST /sessions/` | List (role-scoped) / create (host rights required) |
| `GET/PATCH/DELETE /sessions/<id>/` | Detail, moderate/patch, soft-delete (keeps history) |
| `POST /sessions/<id>/token/` | Mint a LiveKit JWT + resolved media URL |
| `POST /sessions/<id>/end/` | Host closes the class (clears the room, gates re-entry) |
| `GET/POST /sessions/<id>/messages/` | Chat log |
| `GET /sessions/<id>/permissions/` | Per-medium policy + this user's grants |
| `GET/POST /sessions/<id>/permissions/requests/` | Permission inbox / ask |
| `PATCH /sessions/<id>/permissions/requests/<rid>/` | Approve or reject |
| `GET/POST /sessions/<id>/cohosts/`, `DELETE …/<user_id>/` | Co-host management |
| `GET/PUT /sessions/<id>/whiteboard/` | Persisted board (`{open, strokes, updatedAt}`) |
| `GET/POST /sessions/<id>/materials/` | List / upload shared documents |
| `GET /sessions/<id>/materials/<mid>/file/` | Stream a material |
| `DELETE /sessions/<id>/materials/<mid>/` | Remove a material |
| `GET/POST /sessions/<id>/recordings/` | List / start a server-side recording |
| `GET /sessions/<id>/recordings/<rid>/` | Poll status (`starting → active → processing → completed/failed`) |
| `POST /sessions/<id>/recordings/<rid>/stop/` | Stop recording (idempotent) |
| `GET /sessions/<id>/recordings/<rid>/file/` | Stream/download the MP4 |
| `GET /recordings/` | Every recording the caller hosts, for the Records shelf |

**Accounts** — `/api/v1/accounts/`

| Path | Purpose |
| --- | --- |
| `GET/PATCH /me/` | Profile; PATCH also self-service changes |
| `PUT /me/photo/` | Upload a profile picture |
| `GET /avatar/<user_id>/` | Public avatar image (`<img>` cannot send a bearer token) |
| `POST /google/`, `/login/` | Google ID-token (or access-token) sign-in → Podium JWT |
| `GET /users/`, `/users/<id>/` | Admin user list and per-user edit (role, status, grants) |

**Attendance** — `/api/v1/attendance/`

| Path | Purpose |
| --- | --- |
| `GET /sessions`, `/sessions/<id>/` | Register for a class / session totals |
| `POST /join` | Record presence at join |
| `GET /mine` | A student's own history (survives class deletion) |
| `POST /verification/trigger` | Start a check round |
| `POST /verification/respond` | Answer a check |
| `GET /verification/active` | Latest check + next scheduled one |
| `GET /sessions/<id>/export` | Host's CSV/XLSX download for one class |
| `GET /students/<id>`, `GET /export` | External API (service key only) |

**Core** — `/api/v1/core/`

| Path | Purpose |
| --- | --- |
| `GET /features/` | Public feature flags for UI gating |
| `GET /settings/` | Admin settings groups + flags |
| `PATCH /settings/<key>/`, `POST /features/<key>/` | Update one setting / toggle one flag |

**Billing** — `/api/v1/billing/`: `GET /transactions/`, `POST /topup/`,
`POST /deduct/`.

**Health** (unauthenticated, outside `/api/v1/` so no envelope):
`/health`, `/health/live`, `/health/ready` — both with and without a trailing
slash, because a monitor polling `/health` must not eat a 301. `/health`
reports whether LiveKit credentials are configured, which is what the mobile
client's "cannot reach the server" message keys off.

### 4.4 Authentication

Two paths, both resolving to the same `User`:

1. **Browser / mobile:** `Authorization: Bearer <jwt>` — real HS256 JWT carrying
   `sub`, `role`, `iat`, `exp`. Not an opaque identifier.
2. **External service:** `X-API-Key` (or Bearer) with `ATTENDANCE_API_KEY`. This
   authenticates the *service*, never a user; views that accept it must handle
   `request.user` being anonymous and read the target user from validated
   parameters.

Token types are distinct so a service key cannot be replayed as a user token or
vice versa.

**Google sign-in (web):** `vue3-google-signin` popup → ID token → `POST
/api/v1/accounts/google/` → verified with `google-auth` → Podium JWT. The client
id comes from `VITE_GOOGLE_CLIENT_ID` with a built-in fallback, and
`VITE_GOOGLE_ALLOWED_ORIGINS` lets the app explain an `origin_mismatch` in its
own words instead of a popup that silently dies.

**Google sign-in (Android):** Credential Manager (`GetGoogleIdOption`) opens the
native account picker; the ID token goes to the same endpoint. There is a web
OAuth fallback dialog (`GoogleOAuthDialog.kt`) for devices where the picker is
unavailable.

### 4.5 LiveKit tokens and URL resolution

`POST /sessions/<id>/token/` issues a room-scoped JWT (identity, name, role,
`roomJoin`, `canPublish`/`canSubscribe` per role, TTL) and returns both the token
and the URL the client should connect to.

`apps/core/livekit_url.py` exists because every client SDK turns a bad URL into
the same unhelpful message (`expected url scheme 'https' but no scheme was found
for pending`). It repairs or rejects:

- placeholders (`pending`, `tbd`, `changeme`, …),
- bare hostnames without a scheme,
- the dev default `ws://localhost:7880` on a deployed server,
- `http://`/`ws://` on a TLS page (mixed content).

Anything it cannot fix is reported as "not configured", so the client shows one
clear sentence instead of a stack trace.

### 4.6 Errors and responses

Production error pages are JSON (`apps/core/errors.py`), never Django's HTML
ones. Uploads and streams carry explicit content types; file endpoints are
proxy-safe (X-Accel / direct streaming depending on the deployment).

---

## 5. Web app (Vue 3)

### 5.1 Structure

- **Router** (`src/router/index.ts`): **hash history**
  (`createWebHashHistory`) so refresh and shared links work on hosts without
  rewrite rules, and in an installed PWA.
  Routes: `/welcome` (landing), `/`, `/about`, `/login`, `/classroom/:id`,
  `/attendance`, `/wallet`, `/history`, `/profile`, `/admin`.
- **Stores** (Pinia): `auth` (token, user, persistence), `classroom` (the whole
  live room: participants, tracks, moderation, chat, materials, whiteboard,
  recording state), `features` (server flag gating), `toast`.
- **API client** (`src/api/client.ts`): axios instance, base URL from
  `VITE_API_URL`, bearer injection, Document-envelope unwrapping, and a single
  place where a 401 clears the session.
- **Classroom** (`src/views/ClassroomView.vue`) is the largest surface: stage
  layout with a capped tile grid, roster/others tile for large rooms, whiteboard
  overlay, materials drawer, chat, moderation panel, recording controls, the
  host's End-class control, and reconnection handling.

### 5.2 PWA

- `public/manifest.json` — name, maskable icons, standalone display, theme.
- `public/sw.js` — deliberately conservative:
  - navigations **network-first**, so a deploy is picked up on the next visit
    instead of serving a stale `index.html` that points at deleted assets;
  - `/api/` is **never** cached (stale attendance is worse than an error);
  - hashed `/assets/` is immutable and served from cache;
  - a small app shell is cached so the app still opens offline after first use.
- Registered only in production (`import.meta.env.PROD`).

### 5.3 Build

```
npm run build      # vue-tsc -b && vite build
npm run dev        # vite dev server on :3000, /api proxied to :8000
```

Dev proxy targets `http://127.0.0.1:8000`; the axios default follows
`VITE_API_URL`.

### 5.4 Landing and About

`/welcome` is a public, feature-led landing page (no technical jargon, no
role-by-role capability tables). `/about` carries the product story, the
developer credit (Emmanuel Ajigiwe Aio) and the support contact
**0550599755** (WhatsApp), which is also the Android About screen's contact.

---

## 6. Android app

### 6.1 Toolchain

- Kotlin 2.0.21, AGP 8.7.2, Gradle version catalog (`gradle/libs.versions.toml`).
- `minSdk 26` (Android 8.0), `targetSdk`/`compileSdk 35`, Java 17 toolchain.
- Release signing reads `android/keystore.properties` (git-ignored). Absent, it
  builds an *unsigned* release rather than silently shipping a debug-signed APK.
- `BuildConfig.DEFAULT_API_URL` is compiled in
  (`https://podium-api-r6sz.onrender.com` by default). There is no in-app server
  field: a phone can never be left pointing at a dead host by a typo. Override
  for local work with `-PpodiumApiUrl=http://192.168.x.x:8000`.
- Version: `versionName 2.1.1`, `versionCode 3` — bump **both** for any build
  handed to anyone.

### 6.2 Packages

| Package | Responsibility |
| --- | --- |
| `data/api` | `PodiumApiService` (Retrofit), `Models.kt` (`@Serializable` mirrors of the Document contract), `AuthInterceptor`, `ColdStartRetryInterceptor` (Render free tier cold starts), `ApiClient` |
| `data/livekit` | `LiveKitRoomManager` — the Room, participants, tracks, data packets (`chat`, `hand`, `moderation`, `permission_request/response`, `reaction`, verification), reconnect, media errors, audio routing |
| `data/notify` | `PodiumNotifications` — channels (`podium_classes`, `podium_recordings`, `podium_class_live`), class-ended/removed/recording posts, the pinned live-class notification |
| `data/recording` | `ScreenRecordService` — on-device screen recording to `Movies/Podium/`, `LocalRecords` shelf |
| `data/session` | `ClassSessionService` — the foreground service that anchors a live class (see §6.4) |
| `data/storage` | `TokenStorage` — DataStore: token, user id/name/role/email, theme mode, onboarded flag, API URL |
| `ui` | `MainActivity` (single NavHost), theme, logo, motion helpers |
| `ui/screens` | splash, onboarding, login, dashboard (+ records, share dialog), classroom (stage, control bar, chat, materials, moderation, verification, participants, host attendance), attendance (student + host), admin, profile, about |

### 6.3 Navigation

```
splash → (dashboard | onboarding → login)
dashboard → classroom/{sessionId}, attendance, admin, profile, about
```

Transitions are short and directional: forward drifts in from the right and
fades; back returns with a soft scale. Room entry is a scale-through-door, not a
slide. All splash/onboarding animation respects the system
`remove_animations`/`reduce_motion` scale (0 → static frames).

`onUserLeaveHint` enters picture-in-picture while in a classroom, and the
classroom renders a compact layout (active video/profile tile, not just the
control bar) when `isInPip`.

### 6.4 The class connection and foreground service

**Problem.** A WebRTC room that lives only in a ViewModel dies with the
activity and can be frozen or killed once the app is backgrounded — audio stops
and the "class in progress" notification disappears with the process.

**Design.** `data/session/ClassSessionService` is a foreground service started
when the room connects and stopped when it disconnects:

- It runs with `foregroundServiceType="microphone|camera|dataSync"`. The mask
  is chosen at runtime from what the user actually granted and what the class is
  actually publishing: `microphone` when `RECORD_AUDIO` is held, `camera` only
  while a camera is on air, and `dataSync` when neither applies (a muted
  listener still needs the process kept alive). Android 14 refuses the whole
  promotion if a type's permission is missing, so the service never claims one
  it cannot back up, and falls back to a plain ongoing notification if even that
  fails.
- It owns the **ongoing, silent, non-dismissible notification** with a
  chronometer counting from the moment the class began. Tapping it returns to
  the room. Notification id `2202` — distinct from the recorder's `2201`,
  because ids are app-wide and a host's recording would otherwise replace the
  class notice.
- Being a foreground service is what keeps the process (and therefore the live
  Room, its audio path and its socket) out of the cached/frozen state while the
  user is in another app.
- The service is **not** a second media stack: there is exactly one LiveKit
  `Room`, owned by `LiveKitRoomManager`, and one audio session. Duplicating
  either would fight the app for the microphone.
- Start failures (`ForegroundServiceStartNotAllowedException` from the
  background, or a missing permission) are caught: the class keeps running and
  only the notification degrades.

Remaining boundary, stated plainly: swiping the app off the recents list ends
the process and the service with it (`stopWithTask`), and the room with them.
That is a deliberate *leave*, and it is handled as one — the on-device
recording is closed cleanly so the MP4 remains playable.

### 6.5 On-device recording

`ScreenRecordService` captures the screen (stage, shared material, whiteboard)
rather than compositing individual tracks, so a phone records exactly what the
host is looking at with no server CPU, no egress minutes and no traffic.

- Writes to `Movies/Podium/` via MediaStore on Android 10+ (survives uninstall,
  `IS_PENDING` hides half-written files); on 8/9 it writes to the app's external
  Movies folder, since no storage permission is requested.
- Long edge capped at 1080p, 30 fps, bitrate by resolution, AAC 128 kbps.
- Every step is best-effort: a recorder that never produced a frame deletes the
  empty file instead of leaving a broken entry.
- A recording is closed (not crashed) when the class ends, the room closes, or
  the classroom is left.

### 6.6 Notifications

Channels are created once at process start:

| Channel | Importance | Used for |
| --- | --- | --- |
| `podium_classes` | default | class ended, removed from class |
| `podium_recordings` | default | recording ready / saved on device / failed |
| `podium_class_live` | low, silent, no badge | the pinned in-class reminder (now posted by the foreground service) |

`POST_NOTIFICATIONS` is requested at runtime on Android 13+; every post is
guarded so a refusal degrades to silence rather than a crash.

---

## 7. Media plane

```
Browser / Phone ──HTTPS/JSON──▶ Django (tokens, state, attendance)
        │
        └──WSS (WebRTC)──────▶ LiveKit SFU ──▶ other participants
                                    │
                                    └──Egress──▶ MP4 ──▶ storage app ──▶ Records
```

- **One SFU city.** WebRTC is latency-bound; a single region near the users
  beats any number of distant replicas.
- **Screen share is capped at 1.2 Mbps with simulcast layers**, so a viewer on a
  weak link pulls a lower layer instead of forcing everyone else down. Students
  default to **camera off**; the policy is *ask*, and the host approves (or turns
  on Smart Camera to auto-approve).
- **Bandwidth is the binding constraint, not traffic volume.** 200 students
  watching a 1.2 Mbps share is ~248 Mbps of egress from one port:

  | Host port | Seats at 1.2 Mbps cap | Seats at ~0.5 Mbps slide average |
  | --- | --- | --- |
  | 200 Mbit/s | ~166 (short of one class) | ~400 (2 classes) |
  | 300 Mbit/s | ~250 | ~600 (3 classes) |
  | 1 Gbit/s | ~830 | ~2 000 (10 classes) |

- **CPU is not the constraint** here: forwarding one stream to 200 viewers is
  roughly one core. Hosting choices should be made on port speed.
- **Server-side recordings** use LiveKit Egress writing into
  `RECORDINGS_ROOT`, with status polled through the recordings endpoint; a
  stalled worker surfaces as `failed` with a reason instead of an empty shelf.

---

## 8. Deployment

### 8.1 Render (current live setup)

`render.yaml` defines three things:

| Service | Type | Details |
| --- | --- | --- |
| `podium-api` | Python web | Frankfurt, free plan. Build: `pip install -r requirements.txt && collectstatic`. Start: `migrate → ensure_admin → gunicorn config.wsgi` (2 workers × 4 threads, 120 s timeout) |
| `podium-web` | Static | Build: `npm ci --include=dev && npm run build`, publish `./dist` |
| — | Env | `DJANGO_DEBUG=False`, `DJANGO_BEHIND_PROXY`, `DJANGO_SECURE_SSL_REDIRECT`, `USE_POSTGRES` + `DATABASE_URL`, CORS/CSRF origins, LiveKit trio, storage roots |

Frontend and API are separate origins here, so `CORS_ALLOWED_ORIGINS` must list
the static site's URL. `deploy/render-config.py` regenerates the blueprint from
a local `.env`.

### 8.2 VPS stack (Docker Compose)

`docker-compose.prod.yml` runs a self-contained production stack:

| Service | Image / build | Notes |
| --- | --- | --- |
| `db` | `postgres:16-alpine` | Named volume; never touched by a redeploy |
| `redis` | `redis:7-alpine` | Cache / egress coordination |
| `livekit` | `livekit/livekit-server:v1.13.10` | Config from `deploy/livekit.yaml.template`, TURN + WS exposed |
| `egress` | `livekit/egress:v1.13.0` | Writes MP4s into the shared recordings volume |
| `backend` | `backend/Dockerfile` | gunicorn; `entrypoint.sh` migrates then serves |
| `frontend` | `frontend/Dockerfile` | Node build → nginx, `/api` proxied same-origin |
| `caddy` | `caddy:2-alpine` | Automatic TLS for `APP_DOMAIN` and `LIVEKIT_DOMAIN` |

Operational details baked in:

- **Log rotation** via the `json-file` driver options at the top of the file.
- **nginx re-resolves the backend** through Docker's DNS (variable-based
  proxy_pass), so recreating the backend container does not 502 the frontend
  until it too is restarted.
- **`deploy/deploy.sh`** validates `deploy/.env` (refuses to boot without
  `DJANGO_SECRET_KEY`, `POSTGRES_PASSWORD`, LiveKit key/secret, both domains and
  the ACME email), then builds and brings the stack up. Safe to re-run; it never
  deletes the volumes.
- **`deploy/backup.sh`** snapshots the database, uploads and recordings.
- Full runbook: `DEPLOY_VPS.md`.

### 8.3 Publishing the web build to a panel host

`deploy/publish_frontend.py` is the repeatable path used when the web app is
served from a shared panel host instead of Render. It builds the SPA, zips it,
and drives the panel's own REST API (upload → unzip into the site root → prune
stale asset folders, keeping the last N so an open tab does not 404 mid-deploy).
The panel's file manager runs as root, which is why a plain `scp` into that
document root cannot work. Auth is a short-lived bearer token, preferably read
from a git-ignored `.fpanel-token` file rather than an exported variable.

### 8.4 Environment reference

Complete, commented template: `.env.example` (app) and `deploy/.env.example`
(compose). Groups:

- **Django:** `DJANGO_DEBUG`, `DJANGO_SECRET_KEY`, `ALLOWED_HOSTS`,
  `CORS_ALLOWED_ORIGINS`, `CSRF_TRUSTED_ORIGINS`, `DJANGO_BEHIND_PROXY`,
  `DJANGO_SECURE_SSL_REDIRECT`, `DJANGO_SECURE_HSTS_SECONDS`
- **Database:** `USE_POSTGRES`, `DATABASE_URL`, `POSTGRES_DB/USER/PASSWORD`
- **Auth:** `PODIUM_JWT_SECRET`, `PODIUM_JWT_TTL_DAYS`, `PODIUM_JWT_ISSUER`,
  `ATTENDANCE_API_KEY`
- **Media:** `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `LIVEKIT_API_URL`,
  `LIVEKIT_URL`, `LIVEKIT_EGRESS_URL`
- **Storage:** `RECORDINGS_ROOT`, `RECORDINGS_CONTAINER_PATH`, `MATERIALS_ROOT`,
  `AVATARS_ROOT`, `MATERIAL_MAX_BYTES`, `AVATAR_MAX_BYTES`, `MAX_UPLOAD_BYTES`
- **Web:** `VITE_API_URL`, `VITE_GOOGLE_CLIENT_ID`, `VITE_GOOGLE_ALLOWED_ORIGINS`,
  `VITE_POSTHOG_KEY`, `VITE_POSTHOG_HOST`
- **Domains (compose):** `APP_DOMAIN`, `LIVEKIT_DOMAIN`, `ACME_EMAIL`

The LiveKit key/secret are **environment-only** and read-only in the admin
settings screen: a stale seed row used to shadow the real credentials, so the
admin panel now reports what the running process actually has.

---

## 9. Local development

```bash
# backend
cd backend
python -m venv .venv && .venv/Scripts/pip install -r requirements.txt
.venv/Scripts/python manage.py migrate
.venv/Scripts/python manage.py runserver 0.0.0.0:8000

# frontend
cd frontend && npm install && npm run dev     # http://localhost:3000

# media (Windows helpers)
scripts/start_livekit.ps1
scripts/start_egress.ps1
scripts/start_redis.ps1
```

- SQLite by default; set `USE_POSTGRES=True` + `DATABASE_URL` to match prod.
- `make`-free: every task is a documented script or management command.
- Android debug builds accept a LAN URL:
  `./gradlew assembleDebug -PpodiumApiUrl=http://192.168.1.10:8000`.

---

## 10. Testing

| Area | Location | Count |
| --- | --- | --- |
| Accounts (sign-in, admin, disable/enable, grants) | `apps/accounts/tests.py` | 33 |
| Classrooms (lifecycle, moderation, materials, recordings, whiteboard) | `apps/classrooms/tests.py` + `tests_lifecycle.py` | 67 |
| Attendance (auth, export, student history, scheduler) | `apps/attendance/tests_*.py` | 67 |
| Storage (upload, stream, egress states) | `apps/storage/tests.py` | ~40 |
| Core (settings, flags, LiveKit URL) | `apps/core/tests.py` | — |

```
cd backend && .venv/Scripts/python manage.py test
cd frontend && npx vue-tsc -b
cd android && ./gradlew :app:compileDebugKotlin
```

---

## 11. Load testing

`loadtest/` holds the measured work, not guesses (`RESULTS.md` has the full
tables):

- **API:** 200 synthetic students doing the real student path
  (detail → token → join → poll every 4 s), against Postgres in a throwaway
  container, served by waitress as a gunicorn stand-in.
- **Classroom view:** 200 real participant tiles in Chromium at 1280×800
  against the actual Vue app.

Headline findings, and what they changed:

| Finding | Response |
| --- | --- |
| 200 tiles in a fixed-height grid produced tiles **271 × 2 px** | Grid now **caps visible tiles**, adds an "others" tile with a live count, and a full roster sheet — web and Android |
| One participant update re-rendered **every** tile (24 ms each) | Tile inputs are narrowed so an update no longer rebuilds the whole array |
| Python clients cap at ~50 req/s per process | Numbers come from 8 processes or an open-loop generator |

---

## 12. Security notes

- JWTs are signed and type-tagged; service keys can never authenticate a user.
- Admin endpoints re-check authority server-side; UI hiding is never the control.
- A disabled account is enforced at authentication, not just in the admin list.
- Dev-only relaxations (`ALLOWED_HOSTS=*`, LAN CORS) exist **only** when
  `DJANGO_DEBUG=True`; production startup fails without real values.
- The release keystore and `.fpanel-creds` are git-ignored and must stay outside
  the repo and any build artifact.
- Uploads are size-capped per kind; avatars are public by design (an `<img>`
  cannot send a token), materials and recordings are authorized per session.
- No credential is printed to logs; the release build carries no API secret.

---

## 13. Known limits

- **Swiping the app away ends the class** on Android — deliberately. Background
  audio and the notification survive *backgrounding*, not process death.
- **One SFU region.** Cross-region participants get higher latency by design.
- **Render free tier** sleeps; the Android client has a cold-start retry, but the
  first request after idle can take several seconds.
- **Camera policy is ask-by-default.** Students are not visible unless a host
  grants it, so a 200-student room is listeners plus one screen share, not 200
  video streams.
- **No server-side recording on the phone path.** Phone recordings are local
  files; only the host's own device sees them until shared.
