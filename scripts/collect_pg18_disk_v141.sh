#!/bin/sh
# V141: run INSIDE the actual PostgreSQL 18 container/host to measure the
# PGDATA filesystem. Shell-only: postgres:18-alpine has df/du but no Python.
# Invoke from the operator: docker compose ... exec -T postgres sh -s \
#   < scripts/collect_pg18_disk_v141.sh > /secure-runtime/pg-disk.json
# No network, SQL, writes, credentials, table data or custom binaries.
set -eu
: "${PGDATA:?PGDATA must be the PostgreSQL server data directory}"
case "$PGDATA" in
  /*) ;;
  *) echo 'V141 refused non-absolute PGDATA' >&2; exit 2 ;;
esac
test -d "$PGDATA/pg_wal" || { echo 'V141 missing PGDATA/pg_wal' >&2; exit 2; }
test "$(cat "$PGDATA/PG_VERSION")" = 18 || {
  echo 'V141 requires physical PostgreSQL 18 PGDATA' >&2; exit 2;
}
# Filesystem free blocks and WAL directory consumption measured IN the actual
# PG container/mount. These are not application data counts or a backup proof.
sizes="$(LC_ALL=C df -Pk "$PGDATA" | awk 'NR==2{print $2, $4}')"
set -- $sizes
test "$#" -eq 2 || { echo 'V141 cannot read filesystem capacity' >&2; exit 2; }
total_kib="$1"; available_kib="$2"
wal_kib="$(LC_ALL=C du -sk "$PGDATA/pg_wal" | awk '{print $1}')"
case "$total_kib:$available_kib:$wal_kib" in
  *[!0-9:]*|:*|*::*|*:) echo 'V141 nonnumeric capacity' >&2; exit 2 ;;
esac
test "$total_kib" -gt 0
test "$available_kib" -ge 0
test "$available_kib" -le "$total_kib"
test "$wal_kib" -ge 0
printf '{"source":"postgres18_local_pgdata","totalBytes":%s,"availableBytes":%s,"walDataBytes":%s}\n' \
  "$((total_kib * 1024))" "$((available_kib * 1024))" "$((wal_kib * 1024))"
