# Podium LMS 2.0: Django + Vue 3 + PostgreSQL Migration Report

## 1. Executive Summary & Architecture Overview

The migration transitions Podium LMS from a Next.js 16 fullstack monolith into a high-performance decoupled architecture:
- **Backend**: **Django 6 + Django REST Framework** with strict **Document Object** response contracts, PostgreSQL schema mapping, and JWT LiveKit token generation.
- **Frontend**: **Vue 3 (Composition API) + Pinia + Vue Router + Vite** using rich vanilla CSS design tokens, glassmorphism, and the `livekit-client` WebRTC SDK.
- **Database**: **PostgreSQL 16** with Prisma schema parity (UUID primary keys, indexed query paths, and pesewas integer accounting).
- **External API Continuity**: Seamless `/api/v1/attendance/*` compatibility for the external Laravel integration team.

```
                      +-----------------------------+
                      |   Vue 3 SPA (Vite / Pinia)  |
                      |  LiveKit WebRTC Client SDK  |
                      +--------------+--------------+
                                     |
                          Document Object REST
                                     |
                                     v
                      +-----------------------------+
                      |       Django 6 / DRF        |
                      |    DocumentJSONRenderer     |
                      +-------+-------------+-------+
                              |             |
           +------------------+             +------------------+
           |                                                   |
           v                                                   v
+----------------------+                            +----------------------+
|    PostgreSQL 16     |                            |   LiveKit Cloud SFU  |
|  Attendance & Users  |                            |   WebRTC Media Stage |
+----------------------+                            +----------------------+
```

---

## 2. Document Object API Specification

Every API endpoint strictly envelopes responses inside a top-level document structure.

### Single Entity Response
```json
{
  "document": {
    "id": "ses_9281a4b9c",
    "title": "Advanced Algorithmic Thinking",
    "course": "CS302",
    "status": "active",
    "meetingCode": "POD-ALGO",
    "lecturerName": "Dr. Kwame Asante",
    "participantCount": 42
  }
}
```

### Collection / List Response
```json
{
  "documents": [
    {
      "id": "ses_9281a4b9c",
      "title": "Advanced Algorithmic Thinking",
      "course": "CS302"
    }
  ],
  "pagination": {
    "count": 1,
    "page": 1,
    "totalPages": 1,
    "next": null,
    "previous": null
  }
}
```

### Error Response Envelope
```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Session title is required.",
    "status": 400
  }
}
```

---

## 3. Directory Structure

```
lite-lms/
├── backend/                        # Django 6 + DRF backend
│   ├── apps/
│   │   ├── accounts/               # Custom User model & auth endpoints
│   │   ├── attendance/             # Attendance, biometric logs & verification
│   │   ├── billing/                # Wallet balances & Paystack transactions
│   │   ├── classrooms/             # Sessions, LiveKit token issuance, chat
│   │   ├── core/                   # DocumentJSONRenderer & DocumentPagination
│   │   └── storage/                # MinIO / S3 recording material models
│   ├── config/                     # Django settings, URLs, CORS configuration
│   ├── Dockerfile
│   └── requirements.txt
├── frontend/                       # Vue 3 + TypeScript SPA
│   ├── src/
│   │   ├── api/client.ts           # Document Object Axios interceptor & client
│   │   ├── stores/
│   │   │   ├── auth.ts             # User persona & wallet store
│   │   │   └── classroom.ts        # LiveKit Room connection & WebRTC controls
│   │   ├── views/
│   │   │   ├── DashboardView.vue   # Live lecture halls & quick-join
│   │   │   ├── ClassroomView.vue   # Virtual lecture stage & floating controls
│   │   │   ├── AttendanceView.vue  # Attendance verification & Laravel sync
│   │   │   ├── WalletView.vue      # Pesewas ledger & MoMo top-ups
│   │   │   └── LoginView.vue       # Interactive demo switcher
│   │   ├── App.vue                 # Top navigation & user status pill
│   │   └── style.css               # Vanilla CSS design tokens & glassmorphism
│   ├── Dockerfile
│   └── vite.config.ts
├── docker-compose.yml              # PostgreSQL + Django + Vue stack
└── MASTER_SETUP_GUIDE.md           # Laravel team integration runbook
```

---

## 4. Quickstart Guide

### Option A: Local Development

1. **Start Django Backend**:
   ```bash
   cd backend
   .\.venv\Scripts\python.exe manage.py migrate
   .\.venv\Scripts\python.exe manage.py runserver 0.0.0.0:8000
   ```

2. **Start Vue 3 Frontend**:
   ```bash
   cd frontend
   npm run dev
   ```
   Open `http://localhost:5173` in your browser.

3. **Run Backend Test Suite**:
   ```bash
   cd backend
   .\.venv\Scripts\python.exe manage.py test apps.classrooms.tests apps.attendance.tests
   ```

### Option B: Docker Compose

```bash
docker compose up -d --build
```
- Frontend: `http://localhost:5173`
- Django API: `http://localhost:8000`
- PostgreSQL: `localhost:5432`
