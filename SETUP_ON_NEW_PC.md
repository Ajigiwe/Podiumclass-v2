# Setup on a New PC — lite-lms (Podium)

Everything you need is in this folder: `lite-lms.zip` (the code) and `.env.local` (the secrets).

> Last refreshed: **Sep 12, 2026** (commit `5d40288`). Also in the repo: **`RUNBOOK.md`** —
> the VPS operations guide (deploy, env vars, recording pipeline, troubleshooting).
> For migrating the *VPS*, `project.tar.gz` in the repo serves the same role via `deploy_vps.ps1`.

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Node.js | 20 LTS or newer | `node -v` |
| npm | ships with Node | `npm -v` |
| Git | optional (nice for cloning/pushing) | `git --version` |

> Docker is only needed for the optional production route (see below).

## Quick Start (5 minutes)

```bash
# 1. Extract the zip
unzip lite-lms.zip            # creates a lite-lms/ folder (repo root files)
cd lite-lms                   # or the folder the zip extracted to

# 2. Move the secrets file into the project
mv ../.env.local .env.local   # adjust path if the folder layout differs

# 3. Install dependencies (~1–2 min)
npm install

# 4. Run the dev server
npm run dev
```

Open **http://localhost:3000** — the site should be live. ✅

## Environment variables

`.env.local` must sit in the project root. It should contain these keys
(**names only listed here — your file already has the real values**):

- **Firebase (client):** `NEXT_PUBLIC_FIREBASE_API_KEY`, `NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN`, `NEXT_PUBLIC_FIREBASE_PROJECT_ID`, `NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET`, `NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID`, `NEXT_PUBLIC_FIREBASE_APP_ID`, `NEXT_PUBLIC_FIREBASE_MEASUREMENT_ID`
- **Firebase (admin/server):** `FIREBASE_ADMIN_PROJECT_ID`, `FIREBASE_ADMIN_CLIENT_EMAIL`, `FIREBASE_ADMIN_PRIVATE_KEY`
- **LiveKit:** `NEXT_PUBLIC_LIVEKIT_URL`, `LIVEKIT_API_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`
- **Paystack:** `NEXT_PUBLIC_PAYSTACK_PUBLIC_KEY`, `PAYSTACK_SECRET_KEY`
- **Minio (storage):** `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MINIO_PUBLIC_BASE`
- **Email:** `RESEND_API_KEY`
- **Attendance API (external systems):** `ATTENDANCE_API_KEY` — server-to-server
  key for `/api/v1/attendance/*` (generate with `openssl rand -hex 32`). Optional:
  `ATTENDANCE_API_KEYS` (comma-separated rotation list) and
  `ATTENDANCE_API_RATE_LIMIT` (default 120/min). See `lite-lms/ATTENDANCE_API.md`.
- **App URL:** `NEXT_PUBLIC_APP_URL` — **must be `https://podiumclass.online` in
  production configs.** A localhost value breaks alert-email links and Paystack
  callbacks, and because it is `NEXT_PUBLIC_*` it is baked at build time (fixing
  it requires an image rebuild, not just a restart).
- **Recording egress:** `EGRESS_TEMPLATE_BASE_URL` (production:
  `http://localhost:3000/recorder/index.html` on the VPS; leave unset in dev) and
  `EGRESS_BASE_PATH` (default `/var/recordings`)
- **Wallet refund cron:** `CRON_SECRET` — shared secret protecting the refund
  cron endpoint (`/api/wallet/refund`); generate one with `openssl rand -hex 24`.
- **LiveKit egress auth:** the values in `LIVEKIT_API_KEY/SECRET` must match the
  `keys:` block in the VPS's `/opt/livekit/livekit.yaml` (and `egress.yaml`) — a
  mismatch causes 401 "Session Severed" for everyone. See `RUNBOOK.md` §3.

If any are missing, the app may boot but the affected feature (video, payments, uploads, email) will fail.

**Legacy** (safe to ignore; the server monitor they fed was removed — no code reads them anymore):

- `PROMETHEUS_URL`, `PROMETHEUS_USER`, `PROMETHEUS_PASS`

> One-off ops scripts also read flags from the environment (e.g. `APPLY=1 node scripts/reconcile-all-wallets.js` makes the wallet reconcile script actually write instead of dry-run) — not needed to run the app.

## Production build (optional)

```bash
npm run build
npm start          # serves on port 3000
```

Or with Docker (matches how the VPS deploys — reads `.env.local` automatically):

```bash
docker compose up --build
```

The Docker build also regenerates the static Tailwind CSS for `public/*.html`
(`npm run build:css:static`) — added Sep 12, see `RUNBOOK.md` §6.

## Tests (optional)

```bash
npm test                       # unit tests (Vitest)
npx playwright install         # one-time: browser binaries for e2e
npm run test:e2e               # e2e tests (Playwright)

# If the Playwright CDN is unreachable, use the installed Chrome/Edge instead:
$env:PLAYWRIGHT_CHANNEL = "chrome"   # PowerShell
npx playwright test --project=chromium
```

API checks (dev server running):

```bash
npm run verify:api             # every route rejects anonymous/bad input without 5xx
npm run verify:attendance-api  # external attendance API end-to-end
```

## Troubleshooting

- **`next: command not found` / weird build errors** → almost always Node < 20. Upgrade Node and re-run `npm install`.
- **Port 3000 busy** → `npm run dev -- -p 3001`.
- **Firebase/LiveKit errors at runtime** → check the matching keys in `.env.local`; restart `npm run dev` after edits.
- **Classroom connects then drops / 401 in console** → LiveKit API key mismatch with the server; see `RUNBOOK.md` §4.
- **Recording does nothing** → check the template URL and `/var/recordings` permissions; see `RUNBOOK.md` §5.
- **Windows** → run commands in Git Bash, PowerShell, or WSL; all work fine.

## Moving it elsewhere again?

`node_modules` and `.next` are machine-specific — delete them, keep the source + `.env.local`, and repeat steps 3–4.
