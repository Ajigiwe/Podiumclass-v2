# Scaling & load balancing — PodiumClass

What is in place, what to turn on as traffic grows, and what will not be fixed by
adding servers. Every number here is measured (see `loadtest/RESULTS.md`) or
labelled a projection.

---

## 1. What actually made it slow

The API was not CPU-bound. It was bound by two things the stack could fix without
touching a single endpoint:

| Problem | Effect (200-student class, 50 req/s offered) |
|---|---|
| `CONN_MAX_AGE = 0` — a new Postgres connection per request | 30 of 50 rps served, p50 **5,306 ms**, p99 10,172 ms |
| gunicorn `--workers 3` with no threads | 3 concurrent requests for the whole API |

With persistent connections the same code, same database and same box served
**50 of 50 rps at p50 33 ms**. A 4-second poll answered in 5–10 seconds is what
"lagging" looked like: the client is permanently behind, so it polls again
immediately and the queue never drains.

Both are now fixed in code, not in a runbook:

- `backend/config/settings.py` — `CONN_MAX_AGE` defaults to 60s on Postgres with
  `CONN_HEALTH_CHECKS` on (override with `DB_CONN_MAX_AGE`).
- `backend/gunicorn.conf.py` — threaded workers, `2 x CPU + 1` processes by
  default (capped at 8), 4 threads each, worker recycling, access-log timings.
  Docker, Render and Compose all read this one file.

---

## 2. Topology

```
                    ┌──────────────────────────────┐
   browser / phone  │  Caddy :443  (TLS, LB)       │
                    └───────┬───────────────┬──────┘
                            │               │
             /api /admin    │               │  everything else
             /health        │               │  (+ /static from volume)
                            ▼               ▼
                  ┌─────────────────┐   ┌──────────────┐
                  │ backend × N     │   │ frontend     │
                  │ gunicorn gthread│   │ nginx (SPA)  │
                  └────────┬────────┘   └──────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
   ┌─────────┐      ┌────────────┐     ┌──────────────┐
   │ Postgres│      │ LiveKit    │     │ shared disk  │
   │ (one)   │      │ (one SFU)  │     │ uploads/MP4s │
   └─────────┘      └────────────┘     └──────────────┘
```

Only the API is replicated. That is because it is the only tier where copies are
interchangeable: the API is stateless (sessions and JWT state live in Postgres,
files on the shared volume), so any request can land on any replica.

`/static/` stays on nginx on purpose. Routing admin CSS through Python would put
static file reads on the same worker pool the classroom polling needs.

---

## 3. Turning it on

### Docker / VPS

```bash
# three API replicas behind Caddy
BACKEND_REPLICAS=3 ./deploy/deploy.sh

# or directly
docker compose --env-file deploy/.env -f docker-compose.prod.yml up -d --scale backend=3
```

Migrations do **not** run inside the replicas. A one-shot `migrate` service owns
`migrate` + `collectstatic`, and each backend waits for it to exit successfully
(`service_completed_successfully`) before starting. Without that, two replicas
booting together both run `migrate` against the same schema and one of them dies.

Verify the spread:

```bash
docker compose -f docker-compose.prod.yml ps backend          # N containers
docker compose -f docker-compose.prod.yml logs caddy | grep health
for i in $(seq 10); do curl -s -o /dev/null -w '%{http_code} ' https://<app-domain>/health/live; done
```

### Render

`WEB_CONCURRENCY` controls processes; raise the instance size first. Render's
free plan (0.1 CPU, 512MB) is pinned to 2 workers because each costs ~80–100MB.
Horizontal scaling on Render is instance count, not a knob in `render.yaml`.

### Sizing the pool

Constraints, in the order they bind:

1. **Memory** — `workers × ~100MB ≤ RAM − 200MB`. 2GB VPS → 4 workers, not 8.
2. **Postgres connections** — `workers × threads × replicas` must stay well under
   `max_connections` (default 100). 3 replicas × 3 workers × 4 threads = 36, fine.
   Above ~80, put PgBouncer in front and set `DB_CONN_MAX_AGE=0`.
3. **CPU** — `2 × cores + 1` is a ceiling for processes, not a target.

---

## 4. Capacity, honestly

Each 200-student class polling every 4s is ~50 req/s of *sustained* API traffic.

| Load | Configuration | Expectation |
|---|---|---|
| 1 class (200 students) | 1 replica, 4 threads, persistent conns | measured 50/50 rps, p50 33 ms |
| 2–4 classes | 2–3 replicas | projection: ~150 rps of the same mix |
| 10+ classes | 4+ replicas, PgBouncer, 4+ cores | needs its own load test before promising numbers |

These are HTTP numbers. They say nothing about media, which is the other half of
the load and is not solved by any of this.

---

## 5. What load balancing does not fix

- **The classroom grid.** The client-side fixes are in: the grid draws at most
  12 tiles (6 on mobile) plus a "+N others" tile, and roster updates mutate the
  tile already on screen instead of rebuilding the array. The 2 px / 24 ms
  figures in `loadtest/RESULTS.md` are the "before" baseline, not current
  behaviour. What remains is bandwidth: every tile with video is a separate
  decode, so hundreds of *publishing* students is still a media-capacity
  question, not a layout one.
- **Media bandwidth.** An SFU is not horizontally scalable per room: a second
  LiveKit instance splits *rooms* across boxes, it does not share one room's
  media graph. Raise capacity with the UDP range (`50000-50100`) and bandwidth,
  and use publish-on-invite / audience mode for lecture-hall sizes.
- **One Postgres.** Reads and writes both hit the same database. When it becomes
  the limit, the order is: indexes and query plans, then a read replica for the
  polling endpoints, then PgBouncer. Not more API replicas.
- **A sleeping instance.** Render's free plan sleeps after ~15 minutes; no amount
  of balancing hides a 30–60s cold start. Both clients retry once for that reason.

---

## 6. Re-measuring

```bash
# throwaway database, seeded students
docker run -d --name podium_loadtest_db -e POSTGRES_PASSWORD=loadtest \
  -e POSTGRES_USER=podium -e POSTGRES_DB=podium -p 5433:5432 postgres:16-alpine
cd backend
USE_POSTGRES=true DATABASE_URL="postgresql://podium:loadtest@localhost:5433/podium" \
  python manage.py migrate
USE_POSTGRES=true DATABASE_URL="postgresql://podium:loadtest@localhost:5433/podium" \
  python scripts/loadtest_seed.py --students 200

# the API exactly as shipped (gunicorn.conf.py), then probe it
gunicorn config.wsgi:application --config gunicorn.conf.py --bind 127.0.0.1:8001 &
python ../loadtest/rate_probe.py --rps 50 --seconds 20
python ../loadtest/capacity_probe.py --mode join --procs 8 --threads 25
```

Compare against the baseline in `loadtest/RESULTS.md`; do not accept a "faster"
claim without a run, and note the database latency of the box you measured on —
connection churn is cheap against a local socket and expensive against a remote
managed database, which is where these settings matter most.
