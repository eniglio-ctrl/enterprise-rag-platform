#!/usr/bin/env bash
# docs/ROADMAP.md item #18. Restores a pg_dumpall backup (see
# backup-postgres.sh) into ANY running Postgres container, not necessarily
# this project's own docker-compose one - the whole point of a pg_dumpall
# backup is that it's self-sufficient (it recreates roles/databases/
# extensions from nothing), so this script takes a container name rather
# than assuming `docker compose exec` against the project's own postgres
# service, letting it run against a genuinely fresh throwaway container for
# a real disaster-recovery drill (see ADR 0044) as well as against the
# project's own postgres service for a routine restore. Runs `psql` inside
# the target container (via `docker exec`) rather than requiring a local
# psql client on the host.
#
# Usage: scripts/restore-postgres.sh <backup-file> [container-name] [superuser]
set -euo pipefail

BACKUP_FILE="$1"
CONTAINER="${2:-enterprise-rag-platform-postgres-1}"
SUPERUSER="${3:-postgres}"

if [ ! -f "$BACKUP_FILE" ]; then
    echo "Backup file not found: $BACKUP_FILE" >&2
    exit 1
fi

docker exec -i "$CONTAINER" psql -U "$SUPERUSER" -d postgres -v ON_ERROR_STOP=0 < "$BACKUP_FILE"

echo "Restore complete from $BACKUP_FILE into container $CONTAINER"
