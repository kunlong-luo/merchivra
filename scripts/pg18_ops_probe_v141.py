#!/usr/bin/env python3
"""V141 read-only PG18 disk/WAL/backup readiness probe.

Reads fixed pg_catalog SQL, a *container-local* df/du observation and a
separately attested backup+isolated-restore record. Never writes a database,
deletes files, invokes Docker, contacts Amazon or sends external webhooks.
JSON on stdout and optional Prometheus node_exporter textfile are non-sensitive.
No synthetic A3 pass is production sign-off or proof an alert receiver exists.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Any

UTC = dt.timezone.utc
SQL = """
SELECT json_build_object(
 'pg_version_num', current_setting('server_version_num')::integer,
 'database_bytes', pg_database_size(current_database()),
 'wal_directory_bytes', (SELECT COALESCE(SUM(size),0)::bigint FROM pg_ls_waldir()),
 'archive_mode', current_setting('archive_mode'),
 'wal_level', current_setting('wal_level'),
 'archived_count', (SELECT archived_count FROM pg_stat_archiver),
 'failed_archive_count', (SELECT failed_count FROM pg_stat_archiver),
 'last_archived_at', (SELECT last_archived_time FROM pg_stat_archiver),
 'last_failed_at', (SELECT last_failed_time FROM pg_stat_archiver),
 'replication_slot_count', (SELECT COUNT(*) FROM pg_replication_slots),
 'largest_slot_retained_bytes',
   (SELECT COALESCE(MAX(GREATEST(
       pg_wal_lsn_diff(pg_current_wal_lsn(),restart_lsn),0
       )),0)::bigint
    FROM pg_replication_slots)
)
"""
# SQL is constant, read-only and uses pg_catalog only. pg_ls_waldir requires
# suitable explicitly provisioned monitoring privileges (e.g. pg_monitor).
# Lack of privilege is UNKNOWN, never treated as an empty WAL directory.

def integer(value: Any, name: str, minimum: int = 0) -> int:
    if type(value) is not int or value < minimum:
        raise ValueError(f"invalid_{name}")
    return value

def instant(value: Any, name: str, now: dt.datetime) -> dt.datetime:
    if not isinstance(value,str) or len(value)>40:
        raise ValueError(f"invalid_{name}")
    try:
        parsed=dt.datetime.fromisoformat(value.replace("Z","+00:00"))
    except ValueError as error:
        raise ValueError(f"invalid_{name}") from error
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError(f"naive_{name}")
    instant_value=parsed.astimezone(UTC)
    if instant_value>now+dt.timedelta(minutes=5):
        raise ValueError(f"future_{name}")
    return instant_value

def evaluate(db: dict[str,Any], disk: dict[str,Any],
             backup: dict[str,Any], limits: dict[str,Any],
             now: dt.datetime, mode: str) -> tuple[dict[str,Any],int]:
    """Pure deterministic evaluator for CI negative tests and operator review."""
    if mode not in ("disposable","production"):
        raise ValueError("invalid_monitor_mode")
    if not isinstance(db,dict) or not isinstance(disk,dict) or not isinstance(backup,dict):
        raise ValueError("invalid_observation")
    if disk.get("source")!="postgres18_local_pgdata":
        raise ValueError("disk_not_measured_inside_postgres")
    if mode=="disposable" and backup.get("source")!="ci_disposable_v68_verified":
        raise ValueError("not_a_disposable_restore_attestation")
    if mode=="production" and backup.get("source")!="operator_attested_restore":
        raise ValueError("missing_independent_operator_attestation")

    version=integer(db.get("pg_version_num"),"pg_version",180000)
    if version//10000!=18:
        raise ValueError("postgres18_required")
    database=integer(db.get("database_bytes"),"database_bytes")
    wal=integer(db.get("wal_directory_bytes"),"wal_directory_bytes")
    disk_total=integer(disk.get("totalBytes"),"filesystem_bytes",1)
    disk_free=integer(disk.get("availableBytes"),"filesystem_available_bytes")
    filesystem_wal=integer(disk.get("walDataBytes"),"filesystem_wal_bytes")
    if disk_free>disk_total:
        raise ValueError("inconsistent_filesystem_capacity")
    if filesystem_wal<0 or wal<0:
        raise ValueError("negative_wal_bytes")
    archived=integer(db.get("archived_count"),"archive_count")
    failures=integer(db.get("failed_archive_count"),"archive_failures")
    slots=integer(db.get("replication_slot_count"),"replication_slot_count")
    retained=integer(db.get("largest_slot_retained_bytes"),"slot_retention")
    archive_mode=db.get("archive_mode")
    wal_level=db.get("wal_level")
    if archive_mode not in ("off","on","always") or wal_level not in ("minimal","replica","logical"):
        raise ValueError("unexpected_postgres_archive_settings")
    sha=backup.get("archiveSha256")
    if not isinstance(sha,str) or not re.fullmatch("[0-9a-f]{64}",sha):
        raise ValueError("invalid_backup_digest")
    archive_bytes=integer(backup.get("archiveBytes"),"backup_archive_bytes",1)
    table_count=integer(backup.get("restoredTables"),"restored_tables",1)
    if backup.get("restoreVerified") is not True or backup.get("schemaVerified") is not True:
        raise ValueError("restore_attestation_not_verified")
    backup_time=instant(backup.get("backupCompletedAt"),"backup_completed_at",now)
    restore_time=instant(backup.get("restoreVerifiedAt"),"restore_verified_at",now)
    if restore_time+dt.timedelta(minutes=5)<backup_time:
        raise ValueError("restore_predates_backup")
    backup_age=max(0,(now-backup_time).total_seconds())
    restore_age=max(0,(now-restore_time).total_seconds())

    def limit(name: str, minimum: int=1) -> int:
        return integer(limits.get(name),name,minimum)
    disk_warn=limit("diskFreeWarnPercent")
    disk_critical=limit("diskFreeCriticalPercent")
    if not (1<=disk_critical<disk_warn<=90):
        raise ValueError("inconsistent_disk_thresholds")
    wal_warn=limit("walWarnBytes")
    wal_critical=limit("walCriticalBytes")
    if wal_critical<=wal_warn:
        raise ValueError("inconsistent_wal_thresholds")
    backup_max=limit("backupMaxAgeSeconds")
    restore_max=limit("restoreMaxAgeSeconds")
    free_pct=100.0*disk_free/disk_total

    checks=[]
    def check(code: str, state: str, value: int|float|str|None = None):
        checks.append({"code":code,"status":state,
                       **({} if value is None else {"observed":value})})
    check("PG_VERSION_18","PASS")
    check("FS_FREE",
          "CRITICAL" if free_pct<=disk_critical else
          "WARNING" if free_pct<=disk_warn else "PASS",
          round(free_pct,2))
    check("WAL_DIRECTORY",
          "CRITICAL" if wal>=wal_critical else
          "WARNING" if wal>=wal_warn else "PASS",wal)
    check("BACKUP_FRESHNESS",
          "CRITICAL" if backup_age>backup_max else "PASS",
          round(backup_age))
    check("RESTORE_REHEARSAL",
          "CRITICAL" if restore_age>restore_max else "PASS",
          round(restore_age))
    # A custom-format pg_dump/restore is not PITR. CI's default
    # archive_mode=off must remain explicitly blocked from production.
    check("PITR_WAL_ARCHIVING",
          "BLOCKED" if archive_mode=="off" else "REQUIRES_TARGET_VERIFICATION",
          archive_mode)
    if failures>0:
        # Lifetime archive failures are historical, not necessarily active.
        # Without a previously observed counter and real receiver, avoid
        # pretending to know archive recovery or the onset of a new failure.
        check("ARCHIVE_FAILURE_COUNTER","REQUIRES_TARGET_VERIFICATION",failures)
    if slots>0:
        check("REPLICATION_SLOT_RETENTION",
              "CRITICAL" if retained>=wal_critical else
              "WARNING" if retained>=wal_warn else "PASS",retained)
    check("ALERT_DELIVERY",
          "NOT_CONFIGURED" if mode=="disposable" else "UNVERIFIED")
    check("BACKUP_SOURCE",
          "DISPOSABLE_ONLY" if mode=="disposable" else "OPERATOR_ATTESTED_ONLY")
    blocked=any(c["status"] in ("CRITICAL","BLOCKED") for c in checks)
    warning=any(c["status"]=="WARNING" for c in checks)
    # This status describes operational observations, NEVER a release grant.
    status="BLOCKED" if blocked else "WARNING" if warning else "OBSERVED_NOT_APPROVED"
    if mode=="disposable":
        # Do not hide a real disk/WAL/backup/restore regression on CI. The
        # expected PITR block / missing real receiver is documented, not a
        # reason to fail an intentionally disposable sample.
        actual_bad=any(c["status"]=="CRITICAL" for c in checks)
        exit_code=2 if actual_bad else 1 if warning else 0
    else:
        exit_code=2 if blocked else 1 if warning else 3
    report={
        "schemaVersion":1,"mode":mode,"status":status,
        "productionApproved":False,
        "alertReceiverVerified":False,
        "observedAt":now.astimezone(UTC).isoformat().replace("+00:00","Z"),
        "measurements":{
            "databaseBytes":database,"walDirectoryBytes":wal,
            "walFilesystemBytes":filesystem_wal,
            "filesystemTotalBytes":disk_total,"filesystemAvailableBytes":disk_free,
            "archiveCount":archived,"archiveFailedCount":failures,
            "replicationSlotCount":slots,"maxSlotRetainedBytes":retained,
            "backupArchiveBytes":archive_bytes,"backupAgeSeconds":int(backup_age),
            "restoreAgeSeconds":int(restore_age),"restoredTableCount":table_count
        },
        "thresholds":limits,"checks":checks
    }
    return report,exit_code

def sample_database() -> dict[str,Any]:
    host=os.environ.get("DB_HOST","")
    user=os.environ.get("DB_USER","")
    database=os.environ.get("DB_NAME","")
    password=os.environ.get("DB_PASSWORD","")
    if not all((host,user,database,password)):
        raise ValueError("database_monitor_connection_not_configured")
    env={**os.environ,"PGPASSWORD":password,"PGCONNECT_TIMEOUT":"8"}
    port=os.environ.get("DB_PORT","5432")
    if not port.isdecimal() or not 1<=int(port)<=65535:
        raise ValueError("invalid_database_port")
    # No SQL parameters or secrets in the command line. psql credentials are
    # injected only via the transient environment. --no-password blocks prompts.
    command=["psql","-X","-A","-t","-q","-w","-v","ON_ERROR_STOP=1",
             "-h",host,"-p",port,"-U",user,"-d",database,"-c",SQL]
    try:
        result=subprocess.run(command,env=env,text=True,capture_output=True,
                              timeout=18,check=False)
    except (OSError,subprocess.TimeoutExpired) as exc:
        raise ValueError("postgres_monitor_unavailable") from exc
    if result.returncode!=0:
        # Do not print stderr: libpq may reveal database/host/role identifiers.
        raise ValueError("postgres_monitor_query_failed_or_not_authorized")
    try:
        data=json.loads(result.stdout.strip())
    except (json.JSONDecodeError,ValueError) as exc:
        raise ValueError("postgres_monitor_invalid_json") from exc
    if not isinstance(data,dict):
        raise ValueError("postgres_monitor_invalid_response")
    return data

def load_json(path: str) -> dict[str,Any]:
    if not path:
        raise ValueError("required_attestation_or_disk_file_missing")
    source=Path(path)
    if source.stat().st_size>16384:
        raise ValueError("observation_file_too_large")
    data=json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(data,dict):
        raise ValueError("observation_json_not_an_object")
    return data

def prometheus(report: dict[str,Any]) -> str:
    values=report.get("measurements",{})
    result=[
        "# HELP merchivra_pg18_ops_production_approved Set only by independent operations signoff; probe always reports 0",
        "# TYPE merchivra_pg18_ops_production_approved gauge",
        "merchivra_pg18_ops_production_approved 0",
    ]
    keys={
        "databaseBytes":"merchivra_pg18_database_bytes",
        "walDirectoryBytes":"merchivra_pg18_wal_directory_bytes",
        "filesystemTotalBytes":"merchivra_pg18_filesystem_total_bytes",
        "filesystemAvailableBytes":"merchivra_pg18_filesystem_available_bytes",
        "archiveFailedCount":"merchivra_pg18_archive_failed_count",
        "maxSlotRetainedBytes":"merchivra_pg18_slot_retained_bytes",
        "backupAgeSeconds":"merchivra_pg18_backup_age_seconds",
        "restoreAgeSeconds":"merchivra_pg18_restore_age_seconds",
    }
    for key,metric in keys.items():
        value=values.get(key)
        if type(value) is int and value>=0:
            result.extend([f"# TYPE {metric} gauge",f"{metric} {value}"])
    result.extend(["# TYPE merchivra_pg18_ops_critical gauge",
                   f"merchivra_pg18_ops_critical {int(any(c['status'] in ('CRITICAL','BLOCKED') for c in report.get('checks',[])))}",
                   "# TYPE merchivra_pg18_ops_observed_at_seconds gauge",
                   f"merchivra_pg18_ops_observed_at_seconds {int(dt.datetime.fromisoformat(report['observedAt'].replace('Z','+00:00')).timestamp())}"])
    return "\n".join(result)+"\n"

def atomic_text(path: str, body: str):
    target=Path(path)
    if not target.parent.is_dir():
        raise ValueError("prometheus_textfile_directory_missing")
    handle=None
    try:
        with tempfile.NamedTemporaryFile(mode="w",encoding="utf-8",
            prefix=".merchivra-v141-",suffix=".tmp",dir=target.parent,
            delete=False) as handle:
            handle.write(body)
            name=handle.name
        os.chmod(name,0o600)
        os.replace(name,target)
    finally:
        if handle is not None:
            name=handle.name
            if os.path.exists(name):
                os.unlink(name)

def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode",choices=["disposable","production"],required=True)
    parser.add_argument("--disk-json",required=True)
    parser.add_argument("--backup-json",required=True)
    parser.add_argument("--disk-warn-percent",type=int,required=True)
    parser.add_argument("--disk-critical-percent",type=int,required=True)
    parser.add_argument("--wal-warn-bytes",type=int,required=True)
    parser.add_argument("--wal-critical-bytes",type=int,required=True)
    parser.add_argument("--backup-max-age-hours",type=int,required=True)
    parser.add_argument("--restore-max-age-hours",type=int,required=True)
    parser.add_argument("--prometheus-file")
    args=parser.parse_args()
    report={"schemaVersion":1,"mode":args.mode,"status":"UNKNOWN",
            "productionApproved":False,"alertReceiverVerified":False}
    code=3
    try:
        if args.mode=="disposable":
            if not(os.environ.get("GITHUB_ACTIONS")=="true"
                and os.environ.get("MERCHIVRA_DEMO_ENABLED")=="true"
                and os.environ.get("MERCHIVRA_SPAPI_ENABLED")=="false"
                and os.environ.get("MERCHIVRA_SPAPI_OAUTH_ENABLED")=="false"
                and os.environ.get("DB_HOST")=="127.0.0.1"
                and os.environ.get("DB_NAME")=="merchivra"
                and os.environ.get("DB_PASSWORD")=="ci_disposable_database_password"):
                raise ValueError("disposable_gate_refused")
        limits={
            "diskFreeWarnPercent":args.disk_warn_percent,
            "diskFreeCriticalPercent":args.disk_critical_percent,
            "walWarnBytes":args.wal_warn_bytes,
            "walCriticalBytes":args.wal_critical_bytes,
            "backupMaxAgeSeconds":args.backup_max_age_hours*3600,
            "restoreMaxAgeSeconds":args.restore_max_age_hours*3600,
        }
        disk=load_json(args.disk_json)
        backup=load_json(args.backup_json)
        db=sample_database()
        report,code=evaluate(db,disk,backup,limits,dt.datetime.now(UTC),args.mode)
    except (ValueError,OSError,KeyError,TypeError,json.JSONDecodeError) as error:
        # Only stable internal error codes, never dump input or credentials.
        report["errorCode"]=str(error) if isinstance(error,ValueError) else "observation_unavailable"
    if args.prometheus_file:
        try:
            atomic_text(args.prometheus_file,prometheus(report))
        except (OSError,ValueError):
            report["status"]="UNKNOWN"
            report["errorCode"]="prometheus_textfile_write_failed"
            code=3
    print(json.dumps(report,separators=(",",":"),sort_keys=True))
    if args.mode=="disposable" and code==0:
        print("PASS V141 disposable PG18: actual PGDATA df/du + WAL and DB sizes, "
              "fresh isolated custom-format restore attestation; "
              "PITR/alert receiver remain unverified, productionApproved=false",
              file=sys.stderr)
    return code

if __name__=="__main__":
    sys.exit(main())
