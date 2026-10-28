#!/usr/bin/env bash
#
# Bring the production stack up, or update it to the current checkout.
#
#   sudo bash deploy/deploy.sh
#
# Safe to run repeatedly. It never deletes data: the Postgres volume, uploads,
# recordings and certificates all live outside the containers, so rebuilding an
# image does not touch them.
set -euo pipefail

cd "$(dirname "$0")/.."
ENV_FILE="deploy/.env"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f docker-compose.prod.yml)

if [ ! -f "$ENV_FILE" ]; then
    echo "Missing $ENV_FILE. Create it first:"
    echo "  cp deploy/.env.example $ENV_FILE && chmod 600 $ENV_FILE"
    exit 1
fi

# --- validate the settings that the stack refuses to boot without -----------
missing=0
require() {
    local value
    # Last assignment wins, matching compose's own parsing of the file.
    value="$(grep -E "^${1}=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- || true)"
    if [ -z "$value" ]; then
        echo "  $1 is empty in $ENV_FILE"
        missing=1
    fi
}
echo "Checking $ENV_FILE"
for name in DJANGO_SECRET_KEY POSTGRES_PASSWORD LIVEKIT_API_KEY LIVEKIT_API_SECRET \
            APP_DOMAIN LIVEKIT_DOMAIN ACME_EMAIL; do
    require "$name"
done
if [ "$missing" -ne 0 ]; then
    echo "Fill in the values above before deploying."
    exit 1
fi

# Load the file into this shell: the config renderer needs these values, and an
# exported value beats --env-file as far as compose is concerned, which is what
# lets the derived settings below reach both the containers and the build.
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

# Derived rather than stored, so APP_ORIGIN can never disagree with APP_DOMAIN.
APP_ORIGIN="${APP_ORIGIN:-https://${APP_DOMAIN}}"
export APP_ORIGIN

# With the SPA and the API on one origin the browser never makes a cross-origin
# request, so CORS is not needed. Naming the app's own origin anyway keeps the
# API usable from a www. alias and stops Django warning about it once per
# worker on every boot.
if [ -z "${CORS_ALLOWED_ORIGINS:-}" ]; then
    export CORS_ALLOWED_ORIGINS="$APP_ORIGIN"
fi

# --- data directories -------------------------------------------------------
# Bind mounts, so they stay visible on the host for backups and inspection.
# The backend runs as uid 10001; without this it cannot write uploads on a
# first boot because Docker creates the directories as root.
for dir in recordings materials avatars; do
    mkdir -p "data/${dir}"
done
# Only the directories: recordings can hold gigabytes, and the backend needs to
# create and delete entries inside them, not to own the files themselves.
if ! chown 10001:10001 data data/recordings data/materials data/avatars 2>/dev/null; then
    echo "Warning: could not chown ./data (run with sudo, or uploads may fail)." >&2
fi

# --- service configs --------------------------------------------------------
# LiveKit and Egress need the API secret in a file; rendering it here keeps the
# secret out of git and out of the compose file.
PYTHON="${PYTHON:-python3}"
if ! command -v "$PYTHON" >/dev/null 2>&1; then
    echo "python3 is required to render deploy/generated/*.yaml." >&2
    exit 1
fi
"$PYTHON" deploy/render-config.py

# --- build and start --------------------------------------------------------
echo "Building images and starting the stack..."
"${COMPOSE[@]}" up -d --build --remove-orphans

# --- wait for it to actually serve -----------------------------------------
echo "Waiting for https://${APP_DOMAIN}/health ..."
# Python, not curl: python3 is already required above, and a minimal server
# image should not need an extra package just to poll one URL.
HEALTH_URL="https://${APP_DOMAIN}/health"
for _ in $(seq 1 30); do
    if "$PYTHON" -c "import sys, urllib.request; sys.exit(0 if urllib.request.urlopen('$HEALTH_URL', timeout=5).status == 200 else 1)" 2>/dev/null; then
        echo
        echo "Deployment is up:"
        "${COMPOSE[@]}" ps
        echo
        echo "  Web:      https://${APP_DOMAIN}"
        echo "  LiveKit:  wss://$(grep -E '^LIVEKIT_DOMAIN=' "$ENV_FILE" | tail -n 1 | cut -d= -f2-)"
        exit 0
    fi
    sleep 5
done

echo
echo "The stack is running but https://${APP_DOMAIN}/health is not answering yet." >&2
echo "First boot can take a minute while Caddy obtains a certificate." >&2
echo "Inspect the cause with:" >&2
echo "  ${COMPOSE[*]} logs --tail=80 caddy backend frontend" >&2
"${COMPOSE[@]}" ps
exit 1
