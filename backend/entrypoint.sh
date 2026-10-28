#!/bin/sh
# Container start-up: wait for Postgres, migrate, collect static, then hand off
# to gunicorn (the CMD).
#
# Every step is idempotent, so a restart is safe. Migrations run before the
# workers come up: serving a request against a half-migrated schema produces
# 500s that look like application bugs.
set -eu

if [ "${USE_POSTGRES:-false}" = "true" ]; then
    echo "[start] waiting for the database..."
    python - <<'PY'
import os, sys, time
import psycopg

url = os.environ.get("DATABASE_URL", "")
deadline = time.time() + 60
last = None
while time.time() < deadline:
    try:
        with psycopg.connect(url, connect_timeout=3):
            print("[start] database is up")
            sys.exit(0)
    except Exception as exc:  # noqa: BLE001 - any failure means "not ready yet"
        last = exc
        time.sleep(2)
print(f"[start] database never became reachable: {last}", file=sys.stderr)
sys.exit(1)
PY
fi

if [ "${RUN_MIGRATIONS:-true}" = "true" ]; then
    echo "[start] applying migrations..."
    python manage.py migrate --noinput
fi

if [ "${RUN_COLLECTSTATIC:-true}" = "true" ]; then
    echo "[start] collecting static files..."
    # Shared with the frontend container, which serves /static/ from nginx.
    python manage.py collectstatic --noinput
fi

exec "$@"
