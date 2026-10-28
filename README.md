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
