#!/usr/bin/env bash
# V142 true PostgreSQL 18 WAL archive + recovery_target_time replay test.
# Starts TWO NEW disposable postgres:18-alpine containers with no host port
# and restores only an invented marker table. NEVER uses production PGDATA.
set -Eeuo pipefail
if [[ "${GITHUB_ACTIONS:-}" != true ||
      "${MERCHIVRA_V142_DISPOSABLE_ONLY:-}" != true ||
      "${DB_HOST:-}" != 127.0.0.1 ||
      "${DB_NAME:-}" != merchivra ||
      "${DB_PASSWORD:-}" != ci_disposable_database_password ||
      "${MERCHIVRA_DEMO_ENABLED:-}" != true ||
      "${MERCHIVRA_SPAPI_ENABLED:-}" != false ||
      "${MERCHIVRA_SPAPI_OAUTH_ENABLED:-}" != false ||
      "${MERCHIVRA_SPAPI_ALLOW_WRITES:-false}" != false ]]; then
  echo 'V142 PITR REFUSED: requires disposable GitHub PG18 and no real Amazon' >&2
  exit 2
fi
for utility in docker mktemp date timeout; do
  command -v "$utility" >/dev/null || { echo "Missing $utility" >&2; exit 2; }
done
dir="$(mktemp -d /tmp/merchivra-v142-pitr-XXXXXXXX)"
src="v142-pitr-src-$$"
dst="v142-pitr-restore-$$"
cleanup() {
  docker rm -f -v "$dst" "$src" >/dev/null 2>&1 || true
  rm -rf -- "$dir"
}
trap cleanup EXIT
mkdir -p "$dir/data" "$dir/archive"
# No published ports; the only SQL commands are inside temporary containers.
docker run -d --name "$src" \
  -e POSTGRES_PASSWORD=ci_disposable_v142_pitr_only \
  -v "$dir/data:/backupdata" -v "$dir/archive:/archive" \
  postgres:18-alpine \
  -c wal_level=replica -c archive_mode=on \
  -c 'archive_command=cp %p /archive/%f' \
  -c archive_timeout=1s > /dev/null
docker exec -u root "$src" sh -c \
  'chown postgres:postgres /archive /backupdata && chmod 700 /archive /backupdata'
sql_source() {
  docker exec -u postgres "$src" psql -X -q -A -t -v ON_ERROR_STOP=1 \
    -U postgres -d postgres -c "$1"
}
for _ in $(seq 1 50); do
  if sql_source 'SELECT 1' >/dev/null 2>&1; then break; fi
  sleep 1
done
test "$(sql_source 'SHOW server_version_num' | cut -c1-2)" = 18
sql_source "CREATE TABLE public.v142_pitr_marker(
 id INTEGER PRIMARY KEY, marker VARCHAR(60) NOT NULL)" >/dev/null
sql_source "INSERT INTO public.v142_pitr_marker VALUES (1,'pre-backup')" >/dev/null
# The PG18 streaming physical basebackup carries checkpoint WAL, but not
# later marker commits. This is NOT pg_dump or an in-place database restore.
docker exec -u postgres "$src" pg_basebackup \
  -h 127.0.0.1 -U postgres -D /backupdata/18/docker \
  -Fp -Xs -c fast > /dev/null
test -s "$dir/data/18/docker/PG_VERSION"
test "$(cat "$dir/data/18/docker/PG_VERSION")" = 18

sql_source "INSERT INTO public.v142_pitr_marker VALUES (2,'before-target')" >/dev/null
# Server clock *after commit 2* is our actual requested recovery_target_time.
target="$(sql_source 'SELECT clock_timestamp()')"
[[ "$target" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]] ]] ||
  { echo 'V142 server timestamp unavailable' >&2; exit 1; }
sleep 3
sql_source "INSERT INTO public.v142_pitr_marker VALUES (3,'after-target')" >/dev/null
test "$(sql_source 'SELECT count(*) FROM public.v142_pitr_marker')" = 3
# Explicitly close the segment carrying marker 3 and wait until archiver
# copied it to an actual private local WAL archive directory.
last_wal="$(sql_source 'SELECT pg_walfile_name(pg_switch_wal())')"
[[ "$last_wal" =~ ^[0-9A-F]{24}$ ]] || { echo 'Invalid WAL filename' >&2; exit 1; }
archived=false
for _ in $(seq 1 50); do
  if [[ -s "$dir/archive/$last_wal" ]]; then archived=true; break; fi
  sleep 1
done
[[ "$archived" == true ]] || {
  echo 'V142 did not archive the segment with after-target marker' >&2
  exit 1
}
# PostgreSQL recovery reads archived WAL and pauses at an actual timestamp.
# pg_wal_replay_resume is NOT invoked and source data is NEVER modified.
cat >> "$dir/data/18/docker/postgresql.auto.conf" <<EOF
restore_command = 'cp /archive/%f %p'
recovery_target_time = '$target'
recovery_target_action = 'pause'
recovery_target_inclusive = 'true'
EOF
touch "$dir/data/18/docker/recovery.signal"
docker run -d --name "$dst" --network none \
  -e POSTGRES_PASSWORD=ci_disposable_v142_pitr_only \
  -v "$dir/data:/var/lib/postgresql" -v "$dir/archive:/archive:ro" \
  postgres:18-alpine > /dev/null
sql_restored() {
  docker exec -u postgres "$dst" psql -X -q -A -t -v ON_ERROR_STOP=1 \
    -U postgres -d postgres -c "$1"
}
recovered=false
for _ in $(seq 1 90); do
  state="$(sql_restored "SELECT count(*)::text || ':' ||
      pg_is_in_recovery()::text || ':' ||
      pg_get_wal_replay_pause_state()::text
      FROM public.v142_pitr_marker" 2>/dev/null || true)"
  if [[ "$state" == '2:true:paused' ||
        "$state" == '2:t:paused' ]]; then
    recovered=true
    break
  fi
  if [[ "$state" == 3:* ]]; then
    echo 'V142 PITR ERROR: recovered after excluded target record' >&2
    exit 1
  fi
  sleep 1
done
if [[ "$recovered" != true ]]; then
  echo 'V142 PG18 did not pause at requested recovery target' >&2
  docker logs "$dst" 2>&1 | tail -70 >&2 || true
  exit 1
fi
test "$(sql_restored "SELECT string_agg(marker,',' ORDER BY id)
  FROM public.v142_pitr_marker")" = 'pre-backup,before-target'
test "$(sql_restored "SELECT count(*) FROM public.v142_pitr_marker
  WHERE marker='after-target'")" = 0
if [[ -n "${MERCHIVRA_V142_PITR_EVIDENCE_FILE:-}" ]]; then
  case "$MERCHIVRA_V142_PITR_EVIDENCE_FILE" in
    /tmp/merchivra-v142-pitr-*.json) ;;
    *) echo 'Unsafe PITR evidence output path' >&2; exit 2 ;;
  esac
  umask 077
  printf '{"mode":"disposable","postgresMajor":18,"archivedWalSegment":true,"physicalBasebackup":true,"recoveryTargetPaused":true,"includedCommittedBeforeTarget":true,"excludedCommittedAfterTarget":true,"productionPitrApproved":false}\n' \
    > "$MERCHIVRA_V142_PITR_EVIDENCE_FILE"
fi
echo 'PASS V142 PG18 REAL PITR: physical basebackup, archived post-backup WAL, time-target replay PAUSED, pre-target marker retained, post-target marker excluded; disposable only'
