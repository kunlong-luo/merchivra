#!/usr/bin/env bash
# V68 isolated disposable PostgreSQL 18 custom-format backup / restore.
# NOT a production backup cron job. Refuses remote or non-Demo data.
set -Eeuo pipefail
if [[ "${MERCHIVRA_V68_DISPOSABLE_ONLY:-}" != true ||
      "${DB_HOST:-}" != "127.0.0.1" || "${DB_NAME:-}" != merchivra ||
      "${DB_USER:-}" != merchivra || "${MERCHIVRA_DEMO_ENABLED:-}" != true ||
      "${MERCHIVRA_SPAPI_ENABLED:-}" != false ||
      "${MERCHIVRA_SPAPI_OAUTH_ENABLED:-}" != false ]]; then
  echo 'V68 restore rehearsal REFUSED (requires disposable local Mock-only PG18)' >&2
  exit 2
fi
for utility in psql pg_dump pg_restore createdb dropdb mktemp; do
  command -v "$utility" >/dev/null || { echo "Missing PostgreSQL utility: $utility" >&2; exit 2; }
done
export PGPASSWORD="${DB_PASSWORD:?Disposable DB password required}"
port="${DB_PORT:-5432}"
[[ "$port" =~ ^[0-9]{1,5}$ ]] || { echo "Invalid port" >&2; exit 2; }
opts=(-h "$DB_HOST" -p "$port" -U "$DB_USER")
dir="$(mktemp -d)"
restored_db="merchivra_v68_restore_$$"
cleanup() {
  dropdb "${opts[@]}" --if-exists --force "$restored_db" >/dev/null 2>&1 || true
  rm -rf -- "$dir"
}
trap cleanup EXIT
major="$(psql "${opts[@]}" -d "$DB_NAME" -X -At -v ON_ERROR_STOP=1 -c 'SHOW server_version_num')"
[[ "$major" == 18* ]] || { echo "Must run against PG18" >&2; exit 2; }

pg_dump "${opts[@]}" -d "$DB_NAME" -Fc --no-owner --no-acl \
  -f "$dir/backup.dump"
test -s "$dir/backup.dump"
pg_restore --list "$dir/backup.dump" >/dev/null
createdb "${opts[@]}" --template=template0 "$restored_db"
pg_restore "${opts[@]}" --dbname="$restored_db" --exit-on-error \
  --single-transaction --no-owner --no-privileges "$dir/backup.dump"

tables="$(psql "${opts[@]}" -d "$DB_NAME" -X -At -v ON_ERROR_STOP=1 -c \
 "SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname='public' ORDER BY tablename")"
[[ -n "$tables" ]] || { echo "Empty schema" >&2; exit 1; }
count=0
while IFS= read -r table; do
  [[ "$table" =~ ^[a-z0-9_]+$ ]] || { echo "Unexpected table name" >&2; exit 1; }
  sql="SELECT COUNT(*)::text || ':' ||
       md5(COALESCE(string_agg(to_jsonb(t)::text, '|' ORDER BY to_jsonb(t)::text), ''))
       FROM public.\"$table\" t"
  before="$(psql "${opts[@]}" -d "$DB_NAME" -X -At -v ON_ERROR_STOP=1 -c "$sql")"
  after="$(psql "${opts[@]}" -d "$restored_db" -X -At -v ON_ERROR_STOP=1 -c "$sql")"
  [[ "$before" == "$after" ]] || { echo "Restore content mismatch: $table" >&2; exit 1; }
  count=$((count+1))
done <<< "$tables"
sql="SELECT
  (SELECT COUNT(*) FROM pg_catalog.pg_constraint c
   JOIN pg_catalog.pg_namespace n ON n.oid=c.connamespace WHERE n.nspname='public')::text
  || ':' ||
  (SELECT COUNT(*) FROM pg_catalog.pg_indexes WHERE schemaname='public')::text
  || ':' ||
  (SELECT COUNT(*) FROM flyway_schema_history WHERE success)::text"
before="$(psql "${opts[@]}" -d "$DB_NAME" -X -At -v ON_ERROR_STOP=1 -c "$sql")"
after="$(psql "${opts[@]}" -d "$restored_db" -X -At -v ON_ERROR_STOP=1 -c "$sql")"
[[ "$before" == "$after" ]] || { echo "Flyway / index / constraint metadata mismatch" >&2; exit 1; }
for name in ops_finding_assignment_v65 ops_finding_snooze_v65 ads_reviewed_recent_v64; do
  found="$(psql "${opts[@]}" -d "$restored_db" -X -At -v ON_ERROR_STOP=1 -c \
    "SELECT COUNT(*) FROM pg_catalog.pg_indexes WHERE schemaname='public' AND indexname='$name'")"
  [[ "$found" == 1 ]] || { echo "Missing restored index: $name" >&2; exit 1; }
done
echo "PASS V68: PG18 custom-format archive + isolated atomic restore of $count public tables, full row fingerprints, Flyway history and constraints"
