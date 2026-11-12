#!/usr/bin/env python3
"""V141 stdlib-only non-network, fail-closed operations probe unit tests."""
import datetime as dt
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
import pg18_ops_probe_v141 as probe

NOW=dt.datetime(2026,11,12,10,0,tzinfo=dt.timezone.utc)
def ready():
    db={
        "pg_version_num":180006,"database_bytes":101_000_000,
        "wal_directory_bytes":16_777_216,"archive_mode":"on","wal_level":"replica",
        "archived_count":54,"failed_archive_count":0,
        "last_archived_at":None,"last_failed_at":None,
        "replication_slot_count":0,"largest_slot_retained_bytes":0,
    }
    disk={"source":"postgres18_local_pgdata",
          "totalBytes":20_000_000_000,"availableBytes":10_000_000_000,
          "walDataBytes":16_777_216}
    backup={"source":"operator_attested_restore",
            "archiveSha256":"a"*64,"archiveBytes":50_000_000,
            "backupCompletedAt":"2026-11-12T08:00:00Z",
            "restoreVerifiedAt":"2026-11-12T09:30:00Z",
            "restoredTables":131,"restoreVerified":True,"schemaVerified":True}
    limits={"diskFreeWarnPercent":20,"diskFreeCriticalPercent":10,
            "walWarnBytes":536_870_912,"walCriticalBytes":2_147_483_648,
            "backupMaxAgeSeconds":86400,"restoreMaxAgeSeconds":86400}
    return db,disk,backup,limits

class V141OpsProbeTests(unittest.TestCase):
    def test_production_metrics_green_are_not_release_approval(self):
        report,code=probe.evaluate(*ready(),NOW,"production")
        self.assertEqual(code,3)
        self.assertEqual(report["status"],"OBSERVED_NOT_APPROVED")
        self.assertFalse(report["productionApproved"])
        self.assertFalse(report["alertReceiverVerified"])
        self.assertIn("merchivra_pg18_ops_production_approved 0",probe.prometheus(report))
        self.assertNotIn("merchivra",json.dumps(report).replace("merchivra_pg18_",""))
    def test_disposable_restore_archive_mode_off_is_explicitly_not_pitr(self):
        db,disk,backup,limits=ready()
        db["archive_mode"]="off"
        backup["source"]="ci_disposable_v68_verified"
        report,code=probe.evaluate(db,disk,backup,limits,NOW,"disposable")
        self.assertEqual(code,0)
        self.assertEqual(report["status"],"BLOCKED")
        self.assertFalse(report["productionApproved"])
        self.assertIn("PITR_WAL_ARCHIVING",
            [x["code"] for x in report["checks"] if x["status"]=="BLOCKED"])
        self.assertIn("merchivra_pg18_ops_critical 1",probe.prometheus(report))
    def test_invalid_physical_disk_source_rejected_even_with_passing_sql(self):
        db,disk,backup,limits=ready()
        disk["source"]="external_runner_not_postgres"
        with self.assertRaisesRegex(ValueError,"disk_not_measured_inside_postgres"):
            probe.evaluate(db,disk,backup,limits,NOW,"production")
    def test_fs_warning_and_critical_gates(self):
        db,disk,backup,limits=ready()
        disk["availableBytes"]=3_000_000_000
        report,code=probe.evaluate(db,disk,backup,limits,NOW,"production")
        self.assertEqual((report["status"],code),("WARNING",1))
        disk["availableBytes"]=2_000_000_000
        report,code=probe.evaluate(db,disk,backup,limits,NOW,"production")
        self.assertEqual((report["status"],code),("BLOCKED",2))
    def test_wal_spike_and_retained_slot_are_actionable_but_no_sql_writes(self):
        db,disk,backup,limits=ready()
        db["wal_directory_bytes"]=2_147_483_648
        report,code=probe.evaluate(db,disk,backup,limits,NOW,"production")
        self.assertEqual((report["status"],code),("BLOCKED",2))
        db["wal_directory_bytes"]=16_777_216
        db["replication_slot_count"]=1
        db["largest_slot_retained_bytes"]=2_147_483_648
        report,code=probe.evaluate(db,disk,backup,limits,NOW,"production")
        self.assertEqual((report["status"],code),("BLOCKED",2))
    def test_restore_and_backup_expiry_do_not_pretend_pitr(self):
        db,disk,backup,limits=ready()
        backup["backupCompletedAt"]="2026-11-09T08:00:00Z"
        backup["restoreVerifiedAt"]="2026-11-10T09:30:00Z"
        report,code=probe.evaluate(db,disk,backup,limits,NOW,"production")
        self.assertEqual((report["status"],code),("BLOCKED",2))
        flagged={x["code"] for x in report["checks"] if x["status"]=="CRITICAL"}
        self.assertIn("BACKUP_FRESHNESS",flagged)
        self.assertIn("RESTORE_REHEARSAL",flagged)
    def test_forged_future_or_missing_backup_does_not_pass(self):
        for change in [{"archiveSha256":"bad"},
                       {"archiveBytes":0},{"restoredTables":0},
                       {"restoreVerified":False},{"schemaVerified":False},
                       {"backupCompletedAt":"2026-11-13T10:00:00Z"},
                       {"restoreVerifiedAt":"2026-11-10T10:00:00Z"}]:
            db,disk,backup,limits=ready()
            backup.update(change)
            with self.subTest(change=change),self.assertRaises(ValueError):
                probe.evaluate(db,disk,backup,limits,NOW,"production")
    def test_invalid_thresholds_fail_closed(self):
        for patch in [{"diskFreeWarnPercent":10,"diskFreeCriticalPercent":20},
                      {"walCriticalBytes":536870912},
                      {"restoreMaxAgeSeconds":0}]:
            db,disk,backup,limits=ready()
            limits.update(patch)
            with self.subTest(patch=patch),self.assertRaises(ValueError):
                probe.evaluate(db,disk,backup,limits,NOW,"production")
    def test_wrong_postgres_version_rejected(self):
        db,disk,backup,limits=ready()
        db["pg_version_num"]=170018
        with self.assertRaisesRegex(ValueError,"postgres18_required"):
            probe.evaluate(db,disk,backup,limits,NOW,"production")
    def test_never_claims_unknown_collector_is_healthy_in_prometheus(self):
        report={"schemaVersion":1,"mode":"production","status":"UNKNOWN",
                "productionApproved":False}
        result=probe.prometheus(report)
        self.assertIn("merchivra_pg18_ops_observation_known 0",result)
        self.assertIn("merchivra_pg18_ops_critical 1",result)
        self.assertNotIn("observed_at_seconds",result)
    def test_textfile_atomic_output_contains_only_expected_numeric_metrics(self):
        report,_=probe.evaluate(*ready(),NOW,"production")
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/"pg18.prom"
            probe.atomic_text(str(p),probe.prometheus(report))
            self.assertIn("merchivra_pg18_database_bytes 101000000",
                          p.read_text())
            self.assertEqual(p.stat().st_mode&0o777,0o600)
            self.assertNotIn("password",p.read_text())
    def test_disposable_cli_without_explicit_ci_gate_refuses_before_connecting(self):
        with tempfile.TemporaryDirectory() as directory:
            disk=Path(directory)/"disk.json"
            backup=Path(directory)/"backup.json"
            disk.write_text(json.dumps(ready()[1]))
            backup.write_text(json.dumps(ready()[2]))
            env={**os.environ,"GITHUB_ACTIONS":"false"}
            r=subprocess.run([sys.executable,str(Path(__file__).parent/
                "pg18_ops_probe_v141.py"),
                "--mode","disposable","--disk-json",str(disk),
                "--backup-json",str(backup),
                "--disk-warn-percent","20","--disk-critical-percent","10",
                "--wal-warn-bytes","536870912","--wal-critical-bytes","2147483648",
                "--backup-max-age-hours","24","--restore-max-age-hours","24"],
                capture_output=True,text=True,env=env,timeout=8)
            self.assertEqual(r.returncode,3)
            report=json.loads(r.stdout)
            self.assertEqual(report["status"],"UNKNOWN")
            self.assertEqual(report["errorCode"],"disposable_gate_refused")

if __name__=="__main__":
    unittest.main()
