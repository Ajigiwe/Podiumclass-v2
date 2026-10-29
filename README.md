# PodiumClass v2 🎓

> Next-Generation Virtual Classroom & Presence Verification Platform built with **Vue 3**, **Django 6 REST Framework**, **PostgreSQL 16**, and **LiveKit SFU**.

[![Vue 3](https://img.shields.io/badge/Vue-3.5-4FC08D?style=for-the-badge&logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![Django](https://img.shields.io/badge/Django-6.0-092E20?style=for-the-badge&logo=django&logoColor=white)](https://www.djangoproject.com/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![LiveKit](https://img.shields.io/badge/LiveKit-WebRTC%20SFU-00E599?style=for-the-badge&logo=webrtc&logoColor=white)](https://livekit.io/)

---

## 🌟 Key Highlights

- **Anti-Cheat Automated Attendance Checker**:
  - Pre-class configuration during session creation (frequency, prompt duration).
  - Dynamic in-class reconfiguration (increase checks, change duration, modify intervals, pause/resume).
  - Teleported responsive modal with Web Audio synthesizer chimes and haptic feedback.
  - Real-time attendance percentage tracking and roster logging in PostgreSQL.
- **Google Meet-Style Video Stage**:
  - Dynamic responsive grid layouts with 1-click Fill Screen / Spotlight pinning.
  - Fit Video (preserve aspect ratio) vs Fill Video (edge-to-edge crop) controls.
- **Multitasking Picture-in-Picture (PiP)**:
  - Native OS Picture-in-Picture for background multitasking.
  - In-app draggable floating video tile with mouse and touch support.
- **Ergonomic Floating Control Bar**:
  - Fast-access microphone, camera, and screen share toggles.
  - Merged sub-popups for hand-raising and floating emoji reactions.
- **Real-Time Persistent Chat**:
  - LiveKit real-time data packets + PostgreSQL database history + `localStorage` recovery.
- **Standardized Document Object REST API**:
  - Strict `{ "document": ... }` and `{ "documents": [ ... ] }` envelope conventions.
  - Dual-use authentication for Vue SPA and external Laravel attendance service integrations.

---

## 🏗️ Architecture

```
                                  +-----------------------------+
                                  |     Vue 3 SPA (Vite/Pinia)  |
                                  |    LiveKit Client SDK       |
                                  +--------------+--------------+
                                                 |
                                    Document Object REST API
                                                 |
                                                 v
                                  +-----------------------------+
                                  |        Django 6 / DRF       |
                                  |     PodiumAuthentication    |
                                  +-------+-------------+-------+
                                          |             |
                       +------------------+             +------------------+
                       |                                                   |
                       v                                                   v
            +----------------------+                            +----------------------+
            |    PostgreSQL 16     |                            |   LiveKit Cloud SFU  |
            |  Users, Attendance,  |                            |   WebRTC Media Stage |
            |  Sessions & Messages |                            |   & Data Packets     |
            +----------------------+                            +----------------------+
```

---

## 🚀 Getting Started

### Prerequisites
- **Python**: 3.11+
- **Node.js**: 20+ and npm
- **PostgreSQL**: 16+ running locally or in Docker
- **LiveKit Server**: Running on `localhost:7880` (or LiveKit Cloud)

### 1. Backend Setup
```bash
cd backend
python -m venv .venv
.\.venv\Scripts\activate          # On Windows (source .venv/bin/activate on Linux/macOS)
pip install -r requirements.txt
python manage.py migrate
python manage.py runserver 0.0.0.0:8000
```

### 2. Frontend Setup
```bash
cd frontend
npm install
npm run dev                       # Starts Vite server on http://localhost:3000
```

Google sign-in only works from an origin the OAuth client has registered as an
**Authorized JavaScript origin** (in Google Cloud Console). The app checks this
before opening the popup and explains it in-place if it does not match. Override
the client or the registered origins without touching source:

```bash
VITE_GOOGLE_CLIENT_ID=...                       # defaults to the built-in client
VITE_GOOGLE_ALLOWED_ORIGINS=http://localhost:3000,http://192.168.1.20:3000
```

### 3. Production Build & Typecheck
```bash
cd frontend
npm run build                     # Runs vue-tsc typechecker & vite build
```

### 4. Deploying to the VPS
Production runs a separate, self-contained stack — Caddy (automatic TLS), nginx-served SPA, gunicorn, PostgreSQL, Redis, LiveKit and the recording worker:

```bash
cp deploy/.env.example deploy/.env      # fill in the secrets and hostnames
sudo bash deploy/deploy.sh
```

See **[DEPLOY_VPS.md](DEPLOY_VPS.md)** for server preparation, DNS, ports, verification, updates, backups and troubleshooting. The development `docker-compose.yml` is unchanged and is not used on the server.

### 5. Publishing the web build to fPanel

`minatoflash82.ifnotus.space` is served from the fPanel hosting home, whose document root is owned by `root` — SCP/SFTP and the account terminal cannot write to it. fPanel's File Manager runs with root privileges, so deploys go through its file API:

```bash
# Provide credentials once per shell (both are read from the environment and
# are never written to disk). FPANEL_TOKEN also works and skips the login call.
export FPANEL_EMAIL="you@example.com"
export FPANEL_PASSWORD="..."

python deploy/publish_frontend.py            # build, package, upload, extract, verify
python deploy/publish_frontend.py --dry-run  # build + package only
```

The script builds with `npm run build`, zips `frontend/dist` with `index.html` at the archive root, uploads it to the account's `tmp/`, extracts it into the document root, removes the archive and then fetches the live site plus the bundle `index.html` references — so a non-zero exit means the deploy did not land. Old content-hashed bundles are left in place so browsers holding a stale `index.html` still find their JS; add `--prune` to clear `assets/` and `css/` first.

The host answers unregistered paths itself — `/login` redirects to the IFNOTUS portal and any other unknown path returns `204` with an empty body, and static hosting there has no rewrite-to-`index.html`. The web app therefore uses **hash history** (`/#/classroom/<code>`): the browser only ever requests `/`, so invite links and refreshes work regardless of what the server does with paths. `classroomInviteLink()` in `src/lib/links.ts` builds those links through the router, so they follow the history mode instead of hard-coding it.

Two operational notes: the host sends no `Cache-Control`, so browsers may hold a previous `index.html` briefly after a deploy (old hashed bundles are kept for exactly that reason); and production builds need `VITE_API_URL` and `VITE_GOOGLE_ALLOWED_ORIGINS`, which `publish_frontend.py` supplies and verifies are inlined before publishing.

### 6. Building a signed Android APK

```bash
cd android
./gradlew installDebug      # debug build on a connected phone
./gradlew assembleRelease   # signed release APK
```

The release APK lands at `app/build/outputs/apk/release/app-release.apk`. Signing credentials live in `android/keystore.properties`, pointing at `android/keystore/podium-release.jks`; both are git-ignored (`*.jks`, `android/keystore/`). **Back the keystore up somewhere durable** — Android will not update an installed app whose signing key differs, so losing it means every existing install has to be uninstalled before it can be replaced.

Without `keystore.properties` the release build still compiles but produces an *unsigned* APK, which cannot be installed. That is deliberate: a fresh clone fails visibly instead of shipping an APK signed with somebody's debug key.

Certificates differ by key, and Google sign-in's native account picker is tied to them. Register both fingerprints as **Android** OAuth clients (package `online.podiumclass.app`) in the same Google Cloud project as the web client:

| Build | SHA-1 |
|---|---|
| Debug (`~/.android/debug.keystore`) | `D0:45:AF:73:D7:BB:65:94:B9:13:94:53:CF:56:E0:8A:19:8F:66:C0` |
| Release (`android/keystore/podium-release.jks`) | `D5:AD:F1:01:19:0E:43:34:EC:3F:80:B1:59:02:38:8F:C6:A9:FE:F0` |

Bump `versionCode` in `android/app/build.gradle.kts` before each public release; Android refuses an APK with a version it has already installed. Local testing against a development server uses `./gradlew assembleDebug -PpodiumApiUrl=http://192.168.1.10:8000` — there is no in-app server setting.

---

## 📑 Recent Changes & Progress
See [CHANGELOG.md](CHANGELOG.md) for full release notes, automated attendance checker specifications, and stage UX refinements.

---

## 🔒 Security & Anti-Cheat
- Dual-use endpoint authentication rejects client-forged IDs.
- User identity is strictly resolved from verified server-side JWT tokens.
- Attendance response validation enforces real-time window expirations directly in PostgreSQL.

---

## 📄 License
Proprietary & Confidential &copy; PodiumClass.
