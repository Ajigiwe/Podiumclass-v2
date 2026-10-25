# Podium LMS — Master Setup & Integration Guide
> **Audience:** Developers, DevOps Engineers, and the **Laravel / Backend Integration Team**.  
> **Platform Version:** Podium v0.1.0 (Next.js 16, React 19, PostgreSQL 16 + Prisma, LiveKit WebRTC, Paystack).  
> **Last Updated:** October 2026.

---

## 📑 Table of Contents
1. [Architecture Overview & Topology](#1-architecture-overview--topology)
2. [Podium Platform Setup (Self-Hosting & Local Dev)](#2-podium-platform-setup)
   - [Prerequisites](#21-prerequisites)
   - [Local Quick-Start](#22-local-quick-start-5-minutes)
   - [Production VPS / Docker Deployment](#23-production-vps--docker-deployment)
3. [Master Environment Variables Reference](#3-master-environment-variables-reference)
4. [Laravel Integration Guide (Pulling Attendance Data)](#4-laravel-integration-guide)
   - [How the Integration Works](#41-how-the-integration-works)
   - [Laravel Configuration (`.env` & `config/services.php`)](#42-laravel-configuration)
   - [Podium Service Class (`app/Services/PodiumService.php`)](#43-podium-service-class)
   - [Laravel Migration for Attendance Records](#44-laravel-database-schema)
   - [Scheduled Sync Command (`SyncPodiumAttendance.php`)](#45-scheduled-sync-command)
5. [Attendance API (v1) Reference](#5-attendance-api-v1-reference)
6. [LiveKit Real-Time Media Server](#6-livekit-real-time-media-server)
7. [Troubleshooting & Verification](#7-troubleshooting--verification)

---

## 1. Architecture Overview & Topology

```
+----------------------------------------------------------------------------------------+
|                                     PODIUM PLATFORM                                    |
|                                                                                        |
|   +--------------------------+        +--------------------------+                     |
|   |   Static Web Frontends   |        |    Next.js App Router    |                     |
|   |  (HTML5 / Tailwind CSS)  | <----> |  (Classroom, REST APIs,  | <----> PostgreSQL   |
|   | /dashboard.html, /admin  |        |    Auth via NextAuth)    |         (Prisma 6)  |
|   +--------------------------+        +--------------------------+                     |
|                                                    ^                                   |
|   +--------------------------+                     |                                   |
|   |   LiveKit SFU (WebRTC)   |                     |                                   |
|   | (Audio/Video/Screenshare)|                     |                                   |
|   +--------------------------+                     |                                   |
+----------------------------------------------------|-----------------------------------+
                                                     |
                                   HTTPS / Bearer API Key
                                   (GET /api/v1/attendance/*)
                                                     |
                                                     v
                                    +----------------------------------+
                                    |       YOUR LARAVEL APP(S)        |
                                    |   - Student Information System   |
                                    |   - University Registrar Portal  |
                                    |   - Scheduled Attendance Sync    |
                                    +----------------------------------+
```

### Key Principles for the Integration Team:
1. **Attendance is stored in Podium's PostgreSQL database**, not LiveKit.
2. **Laravel communicates exclusively via REST API** using a secure Bearer API Key (`ATTENDANCE_API_KEY`).
3. **No LiveKit SDK or WebRTC tokens are required on the Laravel side.**

---

## 2. Podium Platform Setup

### 2.1 Prerequisites
- **Node.js**: `v20 LTS` or newer (`v24.20.0` tested).
- **Package Manager**: `npm v10+` (ships with Node).
- **Database**: PostgreSQL 16 (local installation or Docker).
- **Docker & Docker Compose**: Optional for local dev; recommended for production.

---

### 2.2 Local Quick-Start (5 Minutes)

```bash
# 1. Navigate to the project directory
cd lite-lms

# 2. Configure environment
cp postgres.env.example postgres.env

# 3. Start PostgreSQL with Docker (if not running a local native Postgres instance)
docker compose -f docker-compose.postgres.yml up -d postgres

# 4. Install dependencies
npm ci

# 5. Generate Prisma Client and apply migrations
npm run db:generate
npm run db:deploy

# 6. Start the development server
npm run dev
```

The application is now accessible at **`http://localhost:3000`**.

---

### 2.3 Production VPS / Docker Deployment

The live production deployment is managed via Docker Compose (`docker-compose.yml`) on an Ubuntu VPS with host networking:

```bash
# Production deploy workflow
cd /opt/podium
git pull origin main
docker compose build lite-lms
docker compose up -d lite-lms
```

To run migrations in production:
```bash
npm run db:deploy
```

Verify service readiness:
```bash
curl -I http://localhost:3000/api/v2/health
```

---

## 3. Master Environment Variables Reference

Place these in `lite-lms/.env.local` (and `/opt/podium/.env.local` on the server).

```dotenv
# ==============================================================================
# DATABASE (PostgreSQL / Prisma)
# ==============================================================================
DATABASE_URL="postgresql://podium:<DB_PASSWORD>@127.0.0.1:5432/podium?schema=public"

# ==============================================================================
# AUTHENTICATION (NextAuth v4 — Google OAuth)
# ==============================================================================
NEXTAUTH_URL="https://podiumclass.online"          # Use http://localhost:3000 in local dev
NEXTAUTH_SECRET="<RANDOM_64_CHAR_HEX_OR_BASE64>"  # Signs session JWT cookies
AUTH_SECRET="<SAME_VALUE_AS_NEXTAUTH_SECRET>"
ADMIN_EMAIL="admin@yourinstitution.edu"           # Promoted to 'admin' role upon first sign-in
GOOGLE_CLIENT_ID="<GOOGLE_OAUTH_CLIENT_ID>"
GOOGLE_CLIENT_SECRET="<GOOGLE_OAUTH_CLIENT_SECRET>"
AUTH_DEV_LOGIN=false                              # Set to false in production

# ==============================================================================
# EXTERNAL ATTENDANCE API (Used by the Laravel Team)
# ==============================================================================
# Server-to-server secret key protecting /api/v1/attendance/* endpoints
ATTENDANCE_API_KEY="<RANDOM_32_CHAR_HEX>"         # Generate: openssl rand -hex 32
ATTENDANCE_API_RATE_LIMIT=120                     # Requests per minute allowed per key

# ==============================================================================
# APPLICATION URL
# ==============================================================================
NEXT_PUBLIC_APP_URL="https://podiumclass.online"  # Build-time inlined for links/emails

# ==============================================================================
# LIVEKIT WEBRTC SFU (Self-hosted video infrastructure)
# ==============================================================================
NEXT_PUBLIC_LIVEKIT_URL="wss://livekit.podiumclass.online"
LIVEKIT_API_URL="http://localhost:7880"           # Internal HTTP signaling port
LIVEKIT_API_KEY="APIpodium909942c6"               # Must match /opt/livekit/livekit.yaml
LIVEKIT_API_SECRET="<MATCHING_SECRET_FROM_YAML>"

# ==============================================================================
# OBJECT STORAGE (MinIO / S3 Compatible)
# ==============================================================================
MINIO_ENDPOINT="storage.podiumclass.online"
MINIO_ACCESS_KEY="<MINIO_ACCESS_KEY>"
MINIO_SECRET_KEY="<MINIO_SECRET_KEY>"
MINIO_PUBLIC_BASE="https://storage.podiumclass.online"

# ==============================================================================
# PAYMENTS (Paystack Ghana Mobile Money)
# ==============================================================================
NEXT_PUBLIC_PAYSTACK_PUBLIC_KEY="pk_live_..."
PAYSTACK_SECRET_KEY="sk_live_..."

# ==============================================================================
# EMAILS (Resend API)
# ==============================================================================
RESEND_API_KEY="re_..."

# ==============================================================================
# BACKGROUND CRON / REFUNDS
# ==============================================================================
CRON_SECRET="<RANDOM_24_CHAR_HEX>"                # Protects /api/wallet/refund
```

---

## 4. Laravel Integration Guide

This section is written specifically for the **Laravel development team** connecting student information systems, LMS portals, or registrar databases to Podium.

### 4.1 How the Integration Works

When a lecturer hosts a lecture on Podium:
1. Students join and verify presence during class.
2. When the class ends, the session status updates to `ended` with calculated timestamps (`startedAt`, `endedAt`, `durationMinutes`).
3. Your Laravel application polls Podium's Attendance API periodically (e.g. every 10–30 minutes or nightly) to pull finished classes and record student attendance.

---

### 4.2 Laravel Configuration

#### In your Laravel `.env`:
```dotenv
PODIUM_BASE_URL="https://podiumclass.online"
PODIUM_API_KEY="<THE_ATTENDANCE_API_KEY_FROM_PODIUM>"
```

#### In `config/services.php`:
```php
'podium' => [
    'base_url' => env('PODIUM_BASE_URL', 'https://podiumclass.online'),
    'api_key'  => env('PODIUM_API_KEY'),
    'timeout'  => 30, // seconds
],
```

---

### 4.3 Podium Service Class

Create `app/Services/PodiumService.php` in your Laravel app:

```php
<?php

namespace App\Services;

use Illuminate\Support\Facades\Http;
use Illuminate\Http\Client\Response;
use RuntimeException;

class PodiumService
{
    protected string $baseUrl;
    protected string $apiKey;
    protected int $timeout;

    public function __construct()
    {
        $this->baseUrl = rtrim(config('services.podium.base_url'), '/');
        $this->apiKey  = config('services.podium.api_key');
        $this->timeout = config('services.podium.timeout', 30);

        if (empty($this->apiKey)) {
            throw new RuntimeException("PODIUM_API_KEY is not configured in Laravel .env");
        }
    }

    /**
     * Get an authorized HTTP client instance.
     */
    protected function client()
    {
        return Http::baseUrl($this->baseUrl . '/api/v1/attendance')
            ->withToken($this->apiKey)
            ->timeout($this->timeout)
            ->acceptJson();
    }

    /**
     * Discover sessions.
     *
     * @param array $params ['status' => 'ended', 'from' => 'YYYY-MM-DD', 'course' => 'MATH101', 'limit' => 50]
     * @return array
     */
    public function getSessions(array $params = []): array
    {
        $response = $this->client()->get('/sessions', $params);

        $this->handleErrors($response);
        return $response->json('data', []);
    }

    /**
     * Get the full student roster and attendance summary for a specific session.
     *
     * @param string $sessionId
     * @return array ['session' => [...], 'summary' => [...], 'records' => [...]]
     */
    public function getSessionRoster(string $sessionId): array
    {
        $response = $this->client()->get("/sessions/{$sessionId}", [
            'include' => 'records',
        ]);

        $this->handleErrors($response);
        return $response->json('data', []);
    }

    /**
     * Bulk export attendance across a date window.
     *
     * @param string $fromDate YYYY-MM-DD
     * @param string $toDate   YYYY-MM-DD
     * @return array
     */
    public function exportAttendance(string $fromDate, string $toDate): array
    {
        $response = $this->client()->get('/export', [
            'from'   => $fromDate,
            'to'     => $toDate,
            'format' => 'json',
        ]);

        $this->handleErrors($response);
        return $response->json('data', []);
    }

    /**
     * Handle non-2xx responses.
     */
    protected function handleErrors(Response $response): void
    {
        if ($response->failed()) {
            $error = $response->json('error');
            $code    = $error['code'] ?? 'UNKNOWN_ERROR';
            $message = $error['message'] ?? $response->body();

            throw new RuntimeException("Podium API Error [{$response->status()} - {$code}]: {$message}");
        }
    }
}
```

---

### 4.4 Laravel Database Schema

Create a migration in Laravel to store the ingested attendance data:

```bash
php artisan make:migration create_podium_attendance_tables
```

```php
<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::create('podium_synced_sessions', function (Blueprint $table) {
            $table->string('session_id')->primary();
            $table->string('title')->nullable();
            $table->string('course_code')->nullable()->index();
            $table->string('program')->nullable();
            $table->string('lecturer_name')->nullable();
            $table->timestamp('started_at')->nullable();
            $table->timestamp('ended_at')->nullable();
            $table->integer('duration_minutes')->nullable();
            $table->integer('total_participants')->default(0);
            $table->integer('total_present')->default(0);
            $table->timestamp('synced_at')->useCurrent();
            $table->timestamps();
        });

        Schema::create('podium_attendance_records', function (Blueprint $table) {
            $table->id();
            $table->string('session_id')->index();
            $table->string('student_id')->index();
            $table->string('student_name')->nullable();
            $table->string('index_number')->nullable()->index();
            $table->boolean('joined')->default(false);
            $table->boolean('present')->default(false); // verificationPercentage >= 50%
            $table->integer('stay_minutes')->nullable();
            $table->integer('verification_percentage')->default(0);
            $table->timestamp('joined_at')->nullable();
            $table->timestamp('left_at')->nullable();
            $table->timestamps();

            $table->unique(['session_id', 'student_id']);
            $table->foreign('session_id')
                  ->references('session_id')
                  ->on('podium_synced_sessions')
                  ->onDelete('cascade');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('podium_attendance_records');
        Schema::dropIfExists('podium_synced_sessions');
    }
};
```

---

### 4.5 Scheduled Sync Command

Create an Artisan console command to automatically pull attendance:

```bash
php artisan make:command SyncPodiumAttendance
```

```php
<?php

namespace App\Console\Commands;

use Illuminate\Console\Command;
use App\Services\PodiumService;
use Illuminate\Support\Facades\DB;
use Carbon\Carbon;

class SyncPodiumAttendance extends Command
{
    protected $signature = 'podium:sync-attendance {--days=1 : Number of past days to check}';
    protected $description = 'Pulls attendance records from recently finished Podium classes';

    public function handle(PodiumService $podium): int
    {
        $days = (int) $this->option('days');
        $fromDate = Carbon::now()->subDays($days)->toDateString();
        $this->info("Scanning finished Podium classes since {$fromDate}...");

        // 1. Discover all finished sessions in window
        $sessions = $podium->getSessions([
            'status' => 'ended',
            'from'   => $fromDate,
            'limit'  => 100,
        ]);

        $this->info("Found " . count($sessions) . " ended session(s).");

        foreach ($sessions as $sessionMeta) {
            $sessionId = $sessionMeta['sessionId'];

            // Skip if already synced
            $alreadySynced = DB::table('podium_synced_sessions')
                ->where('session_id', $sessionId)
                ->exists();

            if ($alreadySynced) {
                $this->line("Skipping session {$sessionId} (already synced).");
                continue;
            }

            $this->info("Fetching roster for session: {$sessionMeta['title']} ({$sessionId})...");
            $rosterData = $podium->getSessionRoster($sessionId);

            $summary = $rosterData['summary'] ?? [];
            $records = $rosterData['records'] ?? [];

            DB::transaction(function () use ($sessionMeta, $summary, $records, $sessionId) {
                // Upsert session
                DB::table('podium_synced_sessions')->updateOrInsert(
                    ['session_id' => $sessionId],
                    [
                        'title'              => $sessionMeta['title'] ?? 'Untitled',
                        'course_code'        => $sessionMeta['course'] ?? null,
                        'program'            => $sessionMeta['program'] ?? null,
                        'lecturer_name'      => $sessionMeta['lecturerName'] ?? null,
                        'started_at'         => $sessionMeta['startedAt'] ?? null,
                        'ended_at'           => $sessionMeta['endedAt'] ?? null,
                        'duration_minutes'   => $sessionMeta['durationMinutes'] ?? null,
                        'total_participants' => $summary['total'] ?? count($records),
                        'total_present'      => $summary['present'] ?? 0,
                        'synced_at'          => now(),
                        'updated_at'         => now(),
                    ]
                );

                // Insert student records
                foreach ($records as $record) {
                    DB::table('podium_attendance_records')->updateOrInsert(
                        [
                            'session_id' => $sessionId,
                            'student_id' => $record['studentId'],
                        ],
                        [
                            'student_name'            => $record['studentName'] ?? null,
                            'index_number'            => $record['indexNumber'] ?? null,
                            'joined'                  => $record['joined'] ?? false,
                            'present'                 => $record['present'] ?? false,
                            'stay_minutes'            => $record['stayMinutes'] ?? null,
                            'verification_percentage' => $record['verificationPercentage'] ?? 0,
                            'joined_at'               => $record['joinedAt'] ?? null,
                            'left_at'                 => $record['leftAt'] ?? null,
                            'updated_at'              => now(),
                        ]
                    );
                }
            });

            $this->info("Saved " . count($records) . " student record(s) for {$sessionMeta['title']}.");
        }

        $this->info("Attendance synchronization complete.");
        return Command::SUCCESS;
    }
}
```

#### Schedule it in Laravel (`routes/console.php` or `app/Console/Kernel.php`):
```php
use Illuminate\Support\Facades\Schedule;

// Run every 30 minutes
Schedule::command('podium:sync-attendance --days=2')->everyThirtyMinutes();
```

---

## 5. Attendance API (v1) Reference

### Authentication Header
Every request must include your API key:
```http
Authorization: Bearer <ATTENDANCE_API_KEY>
```
*(or `X-API-Key: <ATTENDANCE_API_KEY>`)*

### Key Endpoints

| Endpoint | Method | Purpose | Key Parameters |
|---|---|---|---|
| `/api/v1/attendance/sessions` | `GET` | Discover classes | `status=ended`, `from=YYYY-MM-DD`, `to=YYYY-MM-DD`, `course=MATH101`, `limit=50`, `cursor=...` |
| `/api/v1/attendance/sessions/{sessionId}` | `GET` | Full class roster | `include=records` (default) or `include=summary` |
| `/api/v1/attendance/students/{studentId}` | `GET` | Single student history | `from=...`, `to=...`, `limit=50` |
| `/api/v1/attendance/export` | `GET` | Bulk export | `from=YYYY-MM-DD`, `to=YYYY-MM-DD`, `format=json` or `csv` |

### Attendance Metric Rules
- **`joined` (boolean)**: The student successfully connected to the classroom.
- **`present` (boolean)**: The student achieved **$\ge 50\%$** on random pop-up presence checks during the class.
- **`stayMinutes` (integer)**: Calculated stay time in minutes. If a student loses connection without cleanly disconnecting, the session's official `endedAt` timestamp is used as the upper bound.
- **`indexNumber`**: Populated with the university student ID / index number for SIS mapping.

---

## 6. LiveKit Real-Time Media Server

If the Laravel team is **hosting** or managing the servers, keep these LiveKit requirements in mind:

1. **Dockerized Instance**: LiveKit runs on the VPS in Docker using `/opt/livekit/livekit.yaml`.
2. **Ports**:
   - `7880` (HTTP API / WebSocket Signaling — reverse-proxied by Nginx via SSL at `wss://livekit.podiumclass.online`).
   - `7881` (TCP RTC fallback).
   - `UDP 50000–60000` (WebRTC media track packets — **must be open in VPS firewall**).
3. **Secret Matching**: `LIVEKIT_API_KEY` and `LIVEKIT_API_SECRET` in `.env.local` must match `keys:` in `/opt/livekit/livekit.yaml`.
4. **Recording Egress**: Handled by the `livekit-egress` container writing MP4 recordings to `/var/recordings` (owned by user id 1001).

---

## 7. Troubleshooting & Verification

### 1. Test Attendance API from Terminal
```bash
# Verify authentication and list sessions
curl -i "http://localhost:3000/api/v1/attendance/sessions?limit=5" \
     -H "Authorization: Bearer <ATTENDANCE_API_KEY>"
```

Expected response: `HTTP/1.1 200 OK` with JSON array and pagination headers.

### 2. Run Automated API Smoke Tests
Podium comes with a pre-built verification suite:
```bash
cd lite-lms
npm run verify:attendance-api
```

### 3. Check Database Health
```bash
cd lite-lms
npm run verify:db
```

### 4. Common HTTP Codes
- **`401 MISSING_API_KEY / INVALID_API_KEY`**: Key header missing or wrong.
- **`404 SESSION_NOT_FOUND`**: Invalid `sessionId`.
- **`429 RATE_LIMITED`**: Exceeded 120 req/minute (check `X-RateLimit-Reset` header).
- **`503 STORAGE_UNAVAILABLE`**: Database connection failure or missing `DATABASE_URL`.
