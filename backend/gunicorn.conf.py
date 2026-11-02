"""Gunicorn configuration for the Podium API.

One file, read by all three deployment routes (the Docker image, Render and a
scaled Compose stack), so worker sizing cannot drift between them. Every number
is an environment variable with the same name in the docs.

Why threads and not just more processes:

Django here is I/O-bound -- a request waits on Postgres, on LiveKit's HTTP API
or on a file copy, and holds a worker slot while it does. `gthread` lets one
process serve several such requests at once. `sync` workers, which is what the
image used to run (`--workers 3`, no threads), allow exactly one request per
process, so 200 students joining were queued behind three slots.

Sizing rules of thumb, in the order that actually binds:

  * memory -- each worker is roughly 80-100MB resident, so
    `workers x 100MB` must fit the container with room for gunicorn itself.
    A 512MB instance gets 2 workers, not 2xCPU+1.
  * database -- each worker opens up to `CONN_MAX_AGE`-lifetime connections, one
    per thread at most. `workers x threads` must stay well under Postgres
    `max_connections` (100 by default), leaving room for the admin, cron jobs
    and psql. 3 x 4 = 12 is comfortable; 16 x 8 = 128 is not.
  * CPU -- `2 x cores + 1` is the ceiling for processes, not a target. Past that
    the OS spends its time context-switching between processes waiting on the
    same database.

Tuning for real load is a measurement, not a guess: `loadtest/RESULTS.md` has
the method and `scripts/` has the client-side probes.

    WEB_CONCURRENCY       number of processes        (default: 2 x CPU + 1, max 8)
    GUNICORN_THREADS      threads per process        (default: 4)
    GUNICORN_TIMEOUT      hard request timeout, s    (default: 60)
    GUNICORN_KEEPALIVE    keep-alive seconds         (default: 5)
    GUNICORN_MAX_REQUESTS requests per process       (default: 2000, jittered)
    PORT                  bind port                  (default: 8000)
"""

import os


def _int(name, default):
    """Environment override, falling back to `default` when unset or blank."""
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    return int(raw)


# --- process model ---------------------------------------------------------

_cores = os.cpu_count() or 1
workers = _int('WEB_CONCURRENCY', min(2 * _cores + 1, 8))
threads = _int('GUNICORN_THREADS', 4)
worker_class = 'gthread'

# A worker that leaks slowly is invisible until the box runs out of memory, and
# then every request 500s at once. Recycling on a request count bounds that to
# one slow request per worker per cycle. The jitter keeps workers from
# recycling in lockstep and dropping the whole pool's caches together.
max_requests = _int('GUNICORN_MAX_REQUESTS', 2000)
max_requests_jitter = 200

# --- network ---------------------------------------------------------------

# Render passes the port to bind; Docker and a VPS use the default.
# A PORT of 0 (set-but-empty environments, some test harnesses) would bind a
# random free port: the proxy in front would then reach nothing, and the failure
# looks like "the API is down" rather than "the API is on another port".
_port = _int('PORT', 8000)
bind = f"0.0.0.0:{_port if _port > 0 else 8000}"

# Caddy/nginx terminate TLS, so gunicorn only ever speaks plain HTTP to the
# proxy on the same host or compose network. Long enough to reuse a keep-alive
# connection across a burst of polling requests, short enough that a recycled
# worker is not held open by idle clients.
keepalive = _int('GUNICORN_KEEPALIVE', 5)

# Uploads (100MB materials) and recording downloads are streamed through this
# process; the default 30s would cut a slow phone mid-download.
timeout = _int('GUNICORN_TIMEOUT', 60)
graceful_timeout = 30

# Trust X-Forwarded-* only from the proxy in front of us. The container is not
# reachable from outside the compose network, and Django already decides what to
# trust via DJANGO_BEHIND_PROXY.
forwarded_allow_ips = '*'

# --- logging ---------------------------------------------------------------

# Both to stdout: Docker and Render collect it, and a log file inside the
# container is lost on the next deploy.
accesslog = '-'
errorlog = '-'
loglevel = os.getenv('GUNICORN_LOG_LEVEL', 'info').lower()

# Access logs are the only per-request timing the stack has. Keeping the
# response time and the forwarded client address makes a slow endpoint
# identifiable from `docker compose logs backend` without adding APM.
access_log_format = '%(h)s %(l)s %(u)s %(t)s "%(r)s" %(s)s %(b)s %(L)ss "%(f)s" "%(a)s"'
