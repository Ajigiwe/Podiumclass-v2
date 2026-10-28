# Load test 1 — 200 students in one class

First pass: one class, one host, 200 students. Two questions, measured separately.

- **Backend:** what 200 students joining and polling does to the API.
- **Classroom view:** what 200 participant tiles do to the browser.

## Method

- Postgres 16 in a throwaway container on `localhost:5433` (`podium_loadtest_db`),
  so the dev `db.sqlite3` was never touched.
- 200 synthetic students (`loadtest+NNN@podium.test`) with real signed tokens, one
  active class (`loadtest-200`), seeded by `backend/scripts/loadtest_seed.py`.
- The student path is exactly what `ClassroomView.vue` calls:
  `GET /classrooms/sessions/{id}/` → `POST .../token/` → `POST /attendance/join/`,
  then `GET /attendance/verification/active/` every **4000 ms** (`CHECK_POLL_MS`).
- The API ran under waitress (16 threads, connection limit 1000) — a stand-in for
  gunicorn, not the dev server.
- The view was measured in Chromium at **1280×800** against the real Vue app, with
  the store driven directly (200 `ParticipantTile` objects). No LiveKit server was
  running, so media decode is out of scope; the DOM/layout/reactive cost is not.

Client-side caveat worth knowing: **200 Python threads in one process cap at
~50 req/s on their own**. Every number below comes from a client spread over
8 processes, or from an open-loop generator at a fixed rate (see
`capacity_probe.py`, `rate_probe.py`).

## Classroom view: it does not survive 200

| Measurement (1280×800) | 200 participants |
| --- | --- |
| Grid columns | 4 |
| Tile size | **271 × 2 px** |
| Grid height / container | 671 / 703 px — **nothing scrolls** |
| DOM nodes | 2,095 (~10 per tile) |
| `<video>` elements | 100 (one per tile with video) |
| Mount → paint, 200 tiles | 307 ms |
| One participant state change | **24.2 ms** |
| Frames over 32 ms during 60 updates | 25 / 60 (worst 50 ms) |

The grid is `h-full auto-rows-fr`, so rows divide the *container* height rather
than growing it. 200 participants in 4 columns is 50 rows, so each tile is
2 px: not slow, structurally unusable. Roughly 20 participants is where tiles
still get ~100 px; past ~40 the room is already a smear.

The 24 ms per update is because the store rebuilds the whole array on any change
(`participants.value = participants.value.map(...)` — see `classroom.ts:683,867,1000,1494`)
and every tile is keyed into that array, so one person's mic change diffs 200
tiles. At ~40 updates/s the main thread is saturated before anything else happens.

The spotlight/filmstrip view has the same shape: one chip per *other* participant,
so 199 chips in a horizontal scroller.

## Backend: 50 rps is the demand, and the shipped config serves 30

Steady state, offered exactly what one class needs (50 req/s), open loop:

| Config | Achieved | p50 | p95 | p99 |
| --- | --- | --- | --- | --- |
| Shipped (`CONN_MAX_AGE = 0`) | **29.8 of 50 rps** | 5,306 ms | 9,824 ms | 10,172 ms |
| `CONN_MAX_AGE = 60` + health checks | **50.0 of 50 rps** | 33 ms | 491 ms | 701 ms |

A 4-second poll answered in 5–10 seconds means the client is permanently behind
and effectively polls without pause, which then feeds the queue.

200 simultaneous joins (600 requests: session + token + attendance row):

| Config | Session | Token | Attendance join |
| --- | --- | --- | --- |
| Shipped | p50 5,998 ms | p50 6,196 ms | p50 5,954 ms |
| `CONN_MAX_AGE = 60` | p50 2,259 ms | p50 2,088 ms | p50 2,066 ms |

0 errors in all runs — the queue absorbs the spike, it does not drop joins.

### Why

Django's default `CONN_MAX_AGE = 0` closes the Postgres connection after every
request, so each request pays a fresh TCP connect + auth. Measured on this box
(Windows → Docker NAT):

| 16 threads, trivial `SELECT 1` | Throughput | p50 |
| --- | --- | --- |
| New connection per query | 55–67 rps | ~250 ms |
| Persistent connection | 784–801 rps | 3–7 ms |

16 threads / 0.25 s ≈ 64 rps ceiling, which is exactly what the API plateaued at.
The endpoint work itself is small: 4 queries for the token, 7 for the poll, 8 for
the attendance join.

**Caveat:** ~250 ms per connection is this machine's Docker NAT, not what a Linux
host with a local or adjacent Postgres pays. The *structural* problem (a
connection per request) is real everywhere, but its size scales with DB latency —
remote/managed Postgres with TLS is where it turns into a hard ceiling.

## What to fix, in order of payoff

1. **Cap the grid.** Show a fixed number of tiles (12–16) plus an "N others" tile,
   and give hosts a participant list. An uncapped, non-scrolling grid cannot hold
   a lecture hall at any font size.
2. **Stop re-rendering every tile per update.** Mutate the one participant in
   place instead of rebuilding the array, and virtualise the tiles that are off
   screen.
3. **`CONN_MAX_AGE = 60` + `CONN_HEALTH_CHECKS = True`**, and give gunicorn more
   than 3 sync workers (3 workers = 3 concurrent requests; see
   `docker-compose.yml`, which ships `--workers 3`).
4. **Take the waiting-room off `GET /sessions/{id}/`.** It is the heaviest call
   in the join burst and 200 waiting students poll it every 4s; a small status
   endpoint (or a LiveKit data packet) costs a fraction of it.
5. **LiveKit sizing.** 200 participants publishing video in one room is not a
   configuration to plan around; audience/publish-on-invite is.

## Not covered by this run

- LiveKit signalling, TURN/ICE and media bandwidth with 200 real endpoints.
- The Android client under the same room size.
- Attendance check *firing* with 200 students answering at once (the write path).
- Postgres without Docker NAT, i.e. production-plausible connection latency.

## Re-running

```bash
# 1. throwaway database
docker run -d --name podium_loadtest_db -e POSTGRES_PASSWORD=loadtest \
  -e POSTGRES_USER=podium -e POSTGRES_DB=podium -p 5433:5432 postgres:16-alpine

cd backend
export USE_POSTGRES=true DATABASE_URL="postgresql://podium:loadtest@localhost:5433/podium"
python manage.py migrate
python scripts/loadtest_seed.py --students 200

# 2. API as configured, and with persistent connections
python -m waitress --listen=127.0.0.1:8001 --threads=16 --connection-limit=1000 config.wsgi:application
PYTHONPATH=../loadtest DJANGO_SETTINGS_MODULE=settings_loadtest \
  python -m waitress --listen=127.0.0.1:8002 --threads=16 --connection-limit=1000 config.wsgi:application

# 3. measurements
python loadtest/rate_probe.py --rps 50 --seconds 20
python loadtest/capacity_probe.py --mode join --procs 8 --threads 25

# 4. clean up
docker rm -f podium_loadtest_db
```
