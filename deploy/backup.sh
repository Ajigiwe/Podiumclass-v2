#!/usr/bin/env bash
#
# Back up the database and everything users uploaded or recorded.
#
#   sudo ./deploy/backup.sh [/path/to/backups]
#
# Default target is /var/backups/podium, keeping the last 7 runs. A backup that
# lives on the same disk as the data protects against a bad migration or an
# accidental delete, not against losing the VPS -- copy it off the box (see
# DEPLOY_VPS.md).
set -euo pipefail

cd "$(dirname "$0")/.."
ENV_FILE="deploy/.env"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f docker-compose.prod.yml)
TARGET="${1:-/var/backups/podium}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "$TARGET"

echo "Dumping the database..."
# The credentials are expanded inside the container, which already has them in
# its environment: the host does not need to parse deploy/.env for this.
"${COMPOSE[@]}" exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    | gzip -9 > "$TARGET/db-${STAMP}.sql.gz"

# Uploads and recordings are the part a database dump cannot replace. Streaming
# through tar avoids needing twice the free space a plain copy would.
echo "Archiving uploads and recordings..."
tar -czf "$TARGET/files-${STAMP}.tar.gz" data/materials data/avatars data/recordings

# Unreadable or zero-byte archives are worse than none: they look successful
# until the day they are needed.
for file in "$TARGET/db-${STAMP}.sql.gz" "$TARGET/files-${STAMP}.tar.gz"; do
    if [ ! -s "$file" ] || ! gzip -t "$file"; then
        echo "Backup verification failed for $file" >&2
        exit 1
    fi
done

echo "Pruning backups older than the last 7..."
ls -1t "$TARGET"/db-*.sql.gz 2>/dev/null | tail -n +8 | xargs -r rm -f
ls -1t "$TARGET"/files-*.tar.gz 2>/dev/null | tail -n +8 | xargs -r rm -f

echo "Backup complete:"
ls -lh "$TARGET" | tail -n 5
