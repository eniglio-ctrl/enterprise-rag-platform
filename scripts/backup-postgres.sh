#!/usr/bin/env bash
# docs/ROADMAP.md item #18 (docs/PRODUCTION-READINESS-ROADMAP.md Phase 9).
#
# pg_dumpall, not pg_dump: this project's data spans three schemas under one
# role (public: vector_store; auth: users/tenants/invitations; chat:
# conversations/messages) plus the role definitions themselves (including the
# bcrypt-hashed... no, the *database role's own* password hash, unrelated to
# application users' password hashes, which live inside the auth schema and
# are already covered). pg_dump alone only captures a single database's data
# and assumes the target role already exists - useless for restoring into a
# genuinely fresh instance, which is the actual disaster-recovery scenario
# this exists for. pg_dumpall's plain-SQL output recreates roles, databases,
# extensions and schemas from nothing.
set -euo pipefail

OUT_DIR="${1:-backups}"
mkdir -p "$OUT_DIR"

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_FILE="$OUT_DIR/ragplatform-$TIMESTAMP.sql"

DB_USER="${DB_USER:-ragplatform}"

docker compose exec -T postgres pg_dumpall -U "$DB_USER" > "$OUT_FILE"

echo "Backup written to $OUT_FILE ($(du -h "$OUT_FILE" | cut -f1))"
