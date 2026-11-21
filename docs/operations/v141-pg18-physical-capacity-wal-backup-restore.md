# V141 — PostgreSQL 18 physical disk, WAL, verified restore and alerting

Baseline: [V140 main A3 #43186755050](https://github.com/kunlong-luo/merchivra/actions/runs/43186755050), 7/7 success at fa14564c2674b411c45739e118cbce6ed10eee5f. V140's Owner evidence capacity is original attachment size only, **not** actual PostgreSQL filesystem, WAL, backup/PITR or physical storage.

## New tools: read-only and fail closed

- **scripts/collect_pg18_disk_v141.sh**: must execute INSIDE the PostgreSQL server container/host. Confirms PGDATA/PG_VERSION is 18, then obtains actual local filesystem free/total bytes via df and WAL-directory disk bytes via du. JSON contains numeric observations only, never file contents or credentials.
- **scripts/pg18_ops_probe_v141.py**: fixed, read-only pg_catalog SQL through psql for pg_database_size, pg_ls_waldir segment bytes, pg_stat_archiver, PostgreSQL version and pg_replication_slots retained WAL. Requires independently collected matching physical disk JSON, attested backup/restore metadata, explicit operator thresholds and monitor privileges. Missing permissions/metrics -> UNKNOWN and exit 3, not NORMAL. JSON stdout has no tenant/customer identifiers or secrets. Optional atomic Prometheus node_exporter textfile (0600).
- **scripts/smoke_pg_restore_v68.sh**: previously completed isolated disposable custom-format restore of all public tables, row fingerprints, Flyway/index/constraint checks. V141 additionally produces CI-only attestation AFTER success, containing archive SHA-256, byte size, backup/restore timestamps, restored table count and verification flags. No database dump is made available as an artifact.
- **ops/monitoring/merchivra-pg18-v141.rules.yml**: OPTIONAL Prometheus alert rules for missing/stale/unknown probes and warning/critical flags. Rules are not live until configured and successfully tested in a real exporter, Prometheus and Alertmanager deployment. There is NO default email/webhook receiver or secret in Git.

The probe distinguishes database logical bytes, WAL physical segment bytes, replication-slot retention and actual filesystem free space. PostgreSQL archive_mode=off is **BLOCKED for PITR** even after a pg_dump restore. A historical nonzero archiver failure counter is marked REQUIRES_TARGET_VERIFICATION, not claimed to represent a current failure without a baseline. It cannot prove target disk and database identities match across independent collectors: the operator must verify that.

## Operator execution (after separate target review)

Collect filesystem data on the same PostgreSQL server. For a permitted bundled Compose target (do not start an unapproved production stack):

```sh
docker compose --env-file .env -f deploy/compose.yaml \
  -f deploy/compose.production.yaml exec -T postgres sh -s \
  < scripts/collect_pg18_disk_v141.sh > /secure-ops/pg18-disk.json
```

Provide an independent, secure backup/PITR system such as pgBackRest or WAL-G, an encrypted backup destination, backup verification and a successful ISOLATED restore rehearsal. The external backup operator supplies the following *attestation structure*, not actual original files or backup blobs:

```json
{
  "source": "operator_attested_restore",
  "archiveBytes": 123456789,
  "archiveSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "backupCompletedAt": "2026-11-12T00:00:00Z",
  "restoreVerifiedAt": "2026-11-12T01:00:00Z",
  "restoredTables": 100,
  "restoreVerified": true,
  "schemaVerified": true
}
```

These fields are a *self-attestation*, not independent cryptographic proof that production is restorable. Never set verified flags without a real verified restore. The numbers/hash above are fictional. In the operator's **already-provisioned and approved** environment, inject DB_HOST, DB_PORT, DB_NAME, DB_USER and DB_PASSWORD through a secret manager (never command-line arguments); run the read-only probe:

```sh
python3 scripts/pg18_ops_probe_v141.py --mode production \
  --disk-json /secure-ops/pg18-disk.json \
  --backup-json /secure-ops/pg18-backup-restore.json \
  --disk-warn-percent 20 --disk-critical-percent 10 \
  --wal-warn-bytes 536870912 --wal-critical-bytes 2147483648 \
  --backup-max-age-hours 24 --restore-max-age-hours 168 \
  --prometheus-file /secure-ops/node-textfile/merchivra-pg18.prom
```

All threshold numbers here are **illustrations, not production defaults**. Actual limits need explicit independent approval against usable disk, WAL throughput, backup rate and business RPO/RTO. Exit: 1 warning, 2 critical/PITR-block, 3 unknown/unverified. Production can never receive a readiness approval from this script alone; the report always says productionApproved=false and alertReceiverVerified=false, even if readings are within configured thresholds.

To make alerts REAL, arrange access to the mode-0600 Prometheus file for node_exporter textfile collector (same service identity or a narrowly scoped ACL; never world-readable secrets). Deploy node_exporter and Prometheus, load the supplied rules, configure Alertmanager with the REAL secret-managed pager/email/incident receiver, and do a controlled, acknowledged end-to-end test of warning, critical, unknown and stale messages. No external notification is sent by this code or the GitHub A3 synthetic test. Production operations must monitor backup destination, WAL archive destination, host disk, replica lag, database latency, lock waits and restore RPO separately.

## Failure triage

1. UNKNOWN/stale: missing same-host PGDATA observation, DB monitor rights, psql/connection or backup attestation is a blocker, NOT a healthy zero. Investigate permissions, service identity, monitoring scheduler and actual external route.
2. Disk free WARNING/CRITICAL: inspect actual PGDATA mount, WAL, indexes/TOAST and backup destination. Do not delete private V52 original evidence, WAL files, snapshots or audits to clear capacity.
3. WAL/slot growth: inspect archive shipping, replication consumers, restart LSN and retention pressure with reviewed DBA actions. No automatic WAL truncation is provided.
4. Backup age/restore age failures or archive_mode off: block production PITR readiness. pg_dump custom-format restore is not WAL replay or point-in-time recovery.
5. Alert delivery unverified: keep production gate open; get operator acknowledgement via a separately tested configured receiver.

## CI and release limits

Fast PR CI exercises stdlib unit tests for disk and WAL thresholds, malformed/future/expired backup evidence, misleading host FS, wrong PG major, unavailable collector, unknown Prometheus values and no production approval; syntax-check shell scripts. On the merged SHA, a single full A3 uses an actual **disposable PG18 service container**, executes the already-existing isolated archive restore and every-table fingerprint comparison, creates an attestation, runs df/du inside the real PGDATA container and runs read-only PostgreSQL catalog probes. The 7-day downloadable synthetic artifact includes only numeric JSON and textfile metrics, NEVER a dump, credentials, tokens or customer data. CI's known archive_mode=off is deliberately reported BLOCKED for PITR even though the **disposable** probe is allowed to exit 0 for a correct monitored sample. The full Java/React/Next/Chromium/Compose and V140 HTTP checks remain in the same milestone.

This is not an automatic backup scheduler, PITR installation, live monitoring agent, active notification route, production RPO/RTO promise, or production authorization. No Amazon Seller/Ads real auth/writes and no original file deletion are activated. Keep [issue #62](https://github.com/kunlong-luo/merchivra/issues/62) and [issue #63](https://github.com/kunlong-luo/merchivra/issues/63) OPEN until real target disk/backup/WAL/on-call monitoring, HTTPS/IAM, ClamAV signature/update tests, legal holds and G1–G3 manual signoff have passed.
