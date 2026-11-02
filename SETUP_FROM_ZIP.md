# PodiumClass — Setup from this package

Everything needed to run the project on a fresh Windows machine: the Django API,
the Vue SPA, the Android app source, the local LiveKit media server with its
config, and the credential file.

> **This package contains live secrets.** `.env.local`, `.livekit/livekit.local.yaml`
> and `.livekit/egress.local.yaml` hold the LiveKit key/secret, the Django secret
> key, the JWT signing secret and the attendance API key. Keep the zip private,
> never commit it, and never paste its contents into a chat, an issue or a repo.
> Values are deliberately **not** repeated in this document — read them from the
> files.

Authoritative reference for how the system is built: `PROJECT_STACK.md`.
`README.md` covers the feature set. `DEPLOY_VPS.md` is the Docker/VPS runbook.

---

## 1. What is in the package

```
podium-lms/
├── SETUP_FROM_ZIP.md        this file
├── README.md                product + architecture overview
├── PROJECT_STACK.md         complete technical reference (read this second)
├── CHANGELOG.md             what changed and why
├── DEPLOY_VPS.md            Docker Compose / VPS runbook
├── .env.local               ★ secrets for local development
├── .env.example             documented copy with placeholder values
├── docker-compose.yml       local dev stack (optional)
├── docker-compose.prod.yml  full VPS stack (db, redis, livekit, egress, caddy)
├── render.yaml              Render blueprint (current hosted API + web)
├── backend/                 Django 6 REST API
├── frontend/                Vue 3 SPA (Vite) + PWA
├── android/                 Kotlin + Jetpack Compose app
├── deploy/                  Caddyfile, compose templates, publish scripts
├── scripts/                 start_livekit.ps1, start_egress.ps1, start_redis.ps1
└── .livekit/
    ├── livekit-server.exe   local SFU binary (v1.13.10)
    ├── livekit.local.yaml   ★ server config — holds the API key/secret
    └── egress.local.yaml    ★ worker config — holds the key, ws_url, Redis
```

Deliberately **not** included (regenerate or copy from your own backup):

| Left out | Why | How to get it back |
|---|---|---|
| `node_modules/`, `backend/.venv/` | rebuildable | install steps below |
| `frontend/dist/`, `staticfiles/` | build output | `npm run build` |
| `db.sqlite3` | local dev data | `manage.py migrate` creates a fresh one |
| `android/keystore*`, `*.jks` | release signing identity | restore from your backup before publishing an update |
| `.fpanel-creds`, `.fpanel-token` | hosting panel credentials | copy from the old machine if you need `deploy/publish_frontend.py` |
| `.podium/`, `.livekit/recordings/` | uploaded files and recorded MP4s | copy if you need the existing data |
| `.git/` | history | clone the repo instead if you want git |
| `MASTER_SETUP_GUIDE.md`, `SETUP_ON_NEW_PC.md`, `MIGRATION_VUE_DJANGO.md` | describe the *previous* Next.js/Prisma build and contradict this codebase | still in git history |

---

## 2. Prerequisites

| Tool | Version | Needed for | Check |
|---|---|---|---|
| Python | 3.12 | API | `python --version` |
| Node.js | 20 LTS or newer (22 tested) | SPA | `node -v` |
| npm | ships with Node | SPA | `npm -v` |
| Git | any | optional | `git --version` |
| Docker Desktop | current, WSL2 backend | recordings only (Redis + Egress) | `docker info` |
| JDK 17 + Android SDK | AGP 8.7.2 / compileSdk 35 | Android build only | `./gradlew -v` |

Windows is the assumed host (the helper scripts are PowerShell). Every step
below also works on macOS/Linux with the equivalent shell commands.

---

## 3. Run it — five steps

Open **three** terminals from the folder that contains `backend/`, `frontend/`
and `.env.local` (the package root).

### 3.1 Media server (terminal 1)

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start_livekit.ps1
```

Starts `.livekit/livekit-server.exe` with `.livekit/livekit.local.yaml` and
listens on **ws://localhost:7880**. Safe to re-run: it detects port 7880 and
exits. Live audio/video needs nothing else — Redis and Egress are only for
server-side recording.

### 3.2 API (terminal 2)

```powershell
cd backend
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
.venv\Scripts\python manage.py migrate
.venv\Scripts\python manage.py runserver 0.0.0.0:8000
```

Check it is alive: <http://127.0.0.1:8000/health> should report the database as
reachable, and `/health/ready` should pass (it also fails if migrations are
pending).

`config/settings.py` loads the first env file it finds, in this order:

1. `<root>/lite-lms/.env.local`
2. `backend/.env`
3. `<root>/.env.local`  ← the packaged file, correct for this layout

So `.env.local` must stay in the package root, next to `backend/`.

Create the first administrator, either by shell:

```powershell
.venv\Scripts\python manage.py createsuperuser
```

or non-interactively by setting `DJANGO_ADMIN_EMAIL` / `DJANGO_ADMIN_PASSWORD`
and running `.venv\Scripts\python manage.py ensure_admin` (that is how the hosted
API bootstraps its admin when no shell exists).

### 3.3 Web app (terminal 3)

```powershell
cd frontend
npm install
npm run dev
```

Open <http://localhost:3000>. Vite proxies `/api` to `http://127.0.0.1:8000`, so
no frontend env file is required. To point at a different API, create
`frontend/.env` with `VITE_API_URL=http://127.0.0.1:8000` (Vite inlines `VITE_*`
at build time — a change needs a restart).

Sign in with Google. The OAuth client is baked into the source with a fallback,
and `VITE_GOOGLE_ALLOWED_ORIGINS` (default `http://localhost:3000`) keeps the
button honest: on an origin Google does not know it explains `origin_mismatch`
instead of dying silently. If you use a different port, add that origin to
`CORS_ALLOWED_ORIGINS` in `.env.local` **and** to the Google client's authorized
origins.

### 3.4 Recording, optionally (terminal 3 again)

Only if you need server-side MP4s. Both scripts need Docker Desktop running.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start_redis.ps1
powershell -ExecutionPolicy Bypass -File scripts\start_egress.ps1
```

Redis is the bus LiveKit uses to reach the Egress worker. Without it the
classroom works normally and every recording request fails closed with a clear
"recording unavailable" message rather than pretending to record. Finished MP4s
land in `.livekit\recordings\` and are streamed back by the API.

### 3.5 Android, optionally

```powershell
cd android
./gradlew :app:compileDebugKotlin          # quick sanity check
./gradlew assembleDebug -PpodiumApiUrl=http://192.168.1.10:8000
```

`BuildConfig.DEFAULT_API_URL` is compiled in — there is deliberately no in-app
server field, so a typo cannot leave a phone pointed at a dead host. Use your
machine's LAN IP (not `localhost`) so the phone can reach the API; with
`DJANGO_DEBUG=True` the backend already accepts private-LAN origins on any port.
Bump **both** `versionName` and `versionCode` in `android/app/build.gradle.kts`
for any build you hand to someone.

---

## 4. Credentials — what is in `.env.local`, and where each is used

Names only; values live in the file.

| Variable | Used by | Notes |
|---|---|---|
| `DJANGO_SECRET_KEY` | Django | sessions/CSRF; a real value is mandatory with debug off |
| `DJANGO_DEBUG` | Django | `True` for local work; unset defaults to `False` |
| `ALLOWED_HOSTS`, `CORS_ALLOWED_ORIGINS` | Django | required when debug is off |
| `LIVEKIT_URL` | clients | what the browser/phone connects to: `ws://localhost:7880` locally |
| `LIVEKIT_API_URL` | API | HTTP origin of the same server (`http://localhost:7880`) |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | API + Egress | **must match the `keys:` block in `.livekit/livekit.local.yaml`** |
| `LIVEKIT_EGRESS_URL` | API | defaults to `LIVEKIT_API_URL`; recording calls go through the server |
| `PODIUM_JWT_SECRET` | API | signs the app's own session tokens — **not present** in the packaged `.env.local`; the API then generates one per boot, so every restart signs users out. Add a fixed value if logins should survive a restart |
| `ATTENDANCE_API_KEY` | API | service-to-service key for the external attendance endpoints |
| `RECORDINGS_ROOT`, `MATERIALS_ROOT`, `AVATARS_ROOT` | API | where files land; defaults are repo-local folders |
| `USE_POSTGRES`, `DATABASE_URL` | API | leave `False`/empty to use SQLite locally |

A key/secret mismatch between `.env.local` and `livekit.local.yaml` is the single
most common local failure: tokens are signed with one pair and rejected by a
server holding the other, which surfaces as a generic "could not connect" in the
classroom.

---

## 5. Ports

| Port | Process |
|---|---|
| 3000 | Vite dev server (web app) |
| 8000 | Django (`runserver`) |
| 7880 | LiveKit SFU (HTTP + WebSocket) |
| 7881 / 7882 | LiveKit TURN/TCP + UDP (media transport) |
| 6379 | Redis (recording bus only) |

---

## 6. Useful commands

```powershell
# backend tests (~200 tests: accounts, classrooms, attendance, storage, core)
cd backend; .venv\Scripts\python manage.py test

# type-check the SPA
cd frontend; npx vue-tsc -b

# production build of the SPA (output: frontend/dist)
cd frontend; npm run build

# attendance checker — the exact command a cron entry should run
cd backend; .venv\Scripts\python manage.py run_attendance_checker --once

# seed/refresh admin-editable platform settings
cd backend; .venv\Scripts\python manage.py seed_admin_settings
```

---

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Classroom says live media is not configured | `LIVEKIT_URL` is a placeholder or unreachable | set `ws://localhost:7880` and start `scripts/start_livekit.ps1` |
| "Could not connect" in a class, server logs show 401 | key/secret mismatch | make `.env.local` and `livekit.local.yaml` agree |
| Recording returns 503 `RECORDING_UNAVAILABLE` | Redis or the Egress worker is down | run `start_redis.ps1`, then `start_egress.ps1` |
| Recording stops with "nothing to record" | nobody published audio/video | turn on the mic or camera, then record |
| Sign-in button does nothing | origin not registered with Google | add the origin to the Google client and to `VITE_GOOGLE_ALLOWED_ORIGINS` |
| API 400 "DisallowedHost" | host missing from `ALLOWED_HOSTS` with debug off | add the host, or use `DJANGO_DEBUG=True` locally |
| Phone cannot reach the API | `localhost` in the build | rebuild with `-PpodiumApiUrl=http://<LAN-IP>:8000` |
| `vite: not found` during build | production-only install | `npm ci --include=dev` |

---

## 8. Deploying (short version)

Three routes exist; the code supports all three without changes.

1. **Render** — `render.yaml` defines `podium-api` (gunicorn + Postgres) and
   `podium-web` (static build of `frontend/dist`). Free instances sleep, so both
   clients retry a cold connection once. The attendance cron block is commented
   out because cron needs a paid instance — uncomment it once billing is set up.
2. **VPS with Docker** — `docker-compose.prod.yml` runs db, redis, livekit,
   egress, backend, frontend and Caddy; `DEPLOY_VPS.md` is the runbook and
   `deploy/deploy.sh` validates `.env` before starting anything.
3. **Shared panel host (IFNOTUS)** — `frontend/dist` goes into `public_html`;
   the API runs as a panel *Application* under `/apps/<slug>/` with a start
   command of `gunicorn -b 127.0.0.1:{port} config.wsgi:application`, and Postgres
   comes from the panel at `127.0.0.1:5432`. Two constraints to respect:
   no SFU/TURN runs on that account (point `LIVEKIT_URL` at an external LiveKit
   server, or live rooms stay off), and server-side recording writes MP4s to a
   local `RECORDINGS_ROOT` that a cloud egress worker cannot reach — recording
   stays on the VPS/Docker route unless the output is changed to object storage.
   Behind the `/apps/<slug>/` prefix, absolute URLs need `FORCE_SCRIPT_NAME` set,
   otherwise avatars, materials and recording links come back without the prefix.

Never place production secrets in git. On every hosted route they belong in the
platform's environment settings (Render env vars, the panel's application
environment, or `deploy/.env` on the VPS), not in this package.
