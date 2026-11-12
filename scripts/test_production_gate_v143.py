#!/usr/bin/env python3
"""V143 stdlib-only negative gate tests. No real tokens, Docker, network, DB."""
import copy
from datetime import datetime, timedelta, timezone
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from check_production_gate_v143 import (
    ALL_CODES,CONTROL_GROUPS,inspect,private_dns_hostname,
    evidence_checks,safety_checks,
)

SHA="9"*40
NOW=datetime(2026,11,12,12,0,tzinfo=timezone.utc)
TS="2026-11-12T11:00:00Z"
def compose():
    secret="ONLY_FAKE_MOCK_VALUE_123456"
    return {"services":{
        "postgres":{"image":"postgres:18-alpine",
            "environment":{"POSTGRES_PASSWORD":secret}},
        "redis":{"profiles":["optional-cache"]},
        "server":{"ports":[{"host_ip":"127.0.0.1","published":"8080"}],
            "environment":{
                "DB_PASSWORD":secret,
                "MERCHIVRA_EVIDENCE_AV_REQUIRED":"true",
                "MERCHIVRA_EVIDENCE_AV_HOST":"clamd.internal",
                "MERCHIVRA_SESSION_COOKIE_SECURE":"true",
                "MERCHIVRA_DEMO_ENABLED":"false",
                **{k:"false" for k in (
                    "MERCHIVRA_SPAPI_ENABLED",
                    "MERCHIVRA_SPAPI_OAUTH_ENABLED",
                    "MERCHIVRA_SPAPI_PRIVATE_ENABLED",
                    "MERCHIVRA_SPAPI_ALLOW_WRITES",
                    "MERCHIVRA_SPAPI_VERIFICATION_ENABLED",
                    "MERCHIVRA_ORDERS_SYNC_ENABLED",
                    "MERCHIVRA_ORDERS_SYNC_LIVE_APPROVED")}}},
        "web":{"ports":[{"host_ip":"127.0.0.1","published":"3000"}]},
        "next-web":{"ports":[{"host_ip":"127.0.0.1","published":"3001"}],
                    "healthcheck":{"test":["CMD","node"]},
                    "environment":{"NODE_ENV":"production"}},
    }}
def evidence():
    reviewers={
        "G1_BUSINESS":"g1-lead",
        "G2_SECURITY_ARCHITECTURE":"g2-lead",
        "G3_OPERATIONS_RELEASE":"g3-lead",
    }
    controls={}
    for group,codes in CONTROL_GROUPS.items():
        for i,code in enumerate(codes):
            controls[code]={
                "source":"operator_attested_target",
                "status":"verified",
                "evidenceSha256":format(len(controls)+1000, "064x"),
                "testedAt":TS,
                "attestedBy":f"{group.lower()}-operator-{i}",
            }
    return {"schemaVersion":1,"environment":"production",
            "candidateSha":SHA,"source":"operator_attested_target",
            "productionApproved":False,"deploymentPermitted":False,
            "gateReviewers":reviewers,"controls":controls}

class V143ProductionGateTests(unittest.TestCase):
    def test_clean_static_config_and_all_operator_claims_still_never_release(self):
        report=inspect(compose(),evidence(),SHA,NOW)
        self.assertEqual(report["status"],"AWAITING_OUT_OF_BAND_G1_G2_G3_APPROVAL")
        self.assertFalse(report["deploymentPermitted"])
        self.assertFalse(report["productionApproved"])
        self.assertFalse(report["externalApprovalCryptographicallyVerified"])
        self.assertFalse(report["automaticReleasePossible"])
        self.assertEqual(report["blockedChecks"],{"compose":[],"operatorEvidence":[]})

    def test_no_evidence_blocks_all_gates_even_with_safe_config(self):
        report=inspect(compose(),None,SHA,NOW)
        self.assertEqual(report["status"],"BLOCKED")
        self.assertFalse(report["productionApproved"])
        self.assertEqual(sorted(ALL_CODES),
            sorted(c for c in report["blockedChecks"]["operatorEvidence"]
                   if c in ALL_CODES))

    def test_current_demo_compose_does_not_pass_as_prod(self):
        c=compose()
        c["services"]["server"]["environment"]["MERCHIVRA_DEMO_ENABLED"]="true"
        c["services"]["server"]["environment"]["MERCHIVRA_SESSION_COOKIE_SECURE"]="false"
        report=inspect(c,evidence(),SHA,NOW)
        self.assertEqual(report["status"],"BLOCKED")
        self.assertIn("SECURE_SESSION_AND_DEMO_OFF",
                      report["blockedChecks"]["compose"])

    def test_public_ingress_pg_redis_next_api_are_all_rejected(self):
        for target in ("postgres","redis","server","web","next-web"):
            with self.subTest(service=target):
                c=compose()
                c["services"][target]["ports"]=[
                    {"host_ip":"0.0.0.0","published":"5432"}]
                r=inspect(c,evidence(),SHA,NOW)
                self.assertEqual(r["status"],"BLOCKED")
                self.assertTrue(r["blockedChecks"]["compose"])

    def test_private_av_required_and_bogus_host_or_disabled_scan_fails(self):
        for host in ("",None,"http://example.com","0.0.0.0",
                     "127.0.0.1","example.com","clamav.example.com",
                     "my..clamd","bad-.internal","10.0.0.5"):
            with self.subTest(host=host):
                c=compose()
                c["services"]["server"]["environment"]["MERCHIVRA_EVIDENCE_AV_HOST"]=host
                self.assertIn("PRIVATE_AV_HOST_CONFIGURED",
                    inspect(c,evidence(),SHA,NOW)["blockedChecks"]["compose"])
        self.assertTrue(private_dns_hostname("clamd.internal"))
        c=compose()
        c["services"]["server"]["environment"]["MERCHIVRA_EVIDENCE_AV_REQUIRED"]="false"
        self.assertIn("PRIVATE_AV_HOST_CONFIGURED",
                      inspect(c,evidence(),SHA,NOW)["blockedChecks"]["compose"])

    def test_every_amazon_or_sync_enablement_blocks_production_gate(self):
        for key in ("MERCHIVRA_SPAPI_ENABLED","MERCHIVRA_SPAPI_OAUTH_ENABLED",
                    "MERCHIVRA_SPAPI_PRIVATE_ENABLED",
                    "MERCHIVRA_SPAPI_ALLOW_WRITES",
                    "MERCHIVRA_SPAPI_VERIFICATION_ENABLED",
                    "MERCHIVRA_ORDERS_SYNC_ENABLED",
                    "MERCHIVRA_ORDERS_SYNC_LIVE_APPROVED"):
            with self.subTest(key=key):
                c=compose()
                c["services"]["server"]["environment"][key]="true"
                self.assertIn("SELLER_OAUTH_AND_WRITES_DISABLED",
                    inspect(c,evidence(),SHA,NOW)["blockedChecks"]["compose"])

    def test_placeholder_secret_or_leaked_frontend_env_is_blocked(self):
        for secret in ("please_change_this_before_start",
                       "ci_disposable_not_an_actual_secret","",None,
                       "password1234567890"):
            with self.subTest(secret=secret):
                c=compose()
                c["services"]["server"]["environment"]["DB_PASSWORD"]=secret
                c["services"]["postgres"]["environment"]["POSTGRES_PASSWORD"]=secret
                self.assertIn("DB_BOOTSTRAP_SECRET_NOT_PLACEHOLDER",
                    inspect(c,evidence(),SHA,NOW)["blockedChecks"]["compose"])
        c=compose()
        c["services"]["next-web"]["environment"]["DB_PASSWORD"]="DO_NOT_LEAK"
        self.assertIn("NO_SECRET_IN_FRONTEND_SERVICE",
            inspect(c,evidence(),SHA,NOW)["blockedChecks"]["compose"])

    def test_forged_ci_pitr_alert_or_missing_release_evidence_blocks(self):
        e=evidence()
        for change in (
            {"candidateSha":"b"*40},
            {"environment":"staging"},
            {"source":"ci_disposable_v68_verified"},
            {"productionApproved":True},
            {"deploymentPermitted":True},
        ):
            with self.subTest(change=change):
                wrong=copy.deepcopy(e)
                wrong.update(change)
                self.assertEqual(inspect(compose(),wrong,SHA,NOW)["status"],"BLOCKED")
        for bad in ("ci_disposable_v68_verified","real_alertmanager_v142_local"):
            wrong=copy.deepcopy(e)
            wrong["controls"]["production_wal_archiving_pitr_restore"]["source"]=bad
            self.assertIn("production_wal_archiving_pitr_restore",
                inspect(compose(),wrong,SHA,NOW)["blockedChecks"]["operatorEvidence"])

    def test_replayed_document_digest_or_single_attester_for_entire_gate_rejected(self):
        e=evidence()
        a=e["controls"]["encrypted_offsite_physical_backup_target"]["evidenceSha256"]
        e["controls"]["production_wal_archiving_pitr_restore"]["evidenceSha256"]=a
        r=inspect(compose(),e,SHA,NOW)
        self.assertEqual(r["status"],"BLOCKED")
        self.assertIn("production_wal_archiving_pitr_restore",
                      r["blockedChecks"]["operatorEvidence"])
        e=evidence()
        for code in CONTROL_GROUPS["G3_OPERATIONS_RELEASE"]:
            e["controls"][code]["attestedBy"]="g3-single-operator"
        r=inspect(compose(),e,SHA,NOW)
        self.assertEqual(r["status"],"BLOCKED")
        self.assertIn("external_pager_delivered_and_acknowledged",
                      r["blockedChecks"]["operatorEvidence"])

    def test_expired_or_future_evidence_but_no_default_green(self):
        for ts in ("2026-10-01T12:00:00Z","2026-11-13T12:00:00Z",
                   "2026-11-12T12:00:00","garbage"):
            with self.subTest(ts=ts):
                e=evidence()
                e["controls"]["external_pager_delivered_and_acknowledged"]["testedAt"]=ts
                self.assertIn("external_pager_delivered_and_acknowledged",
                    inspect(compose(),e,SHA,NOW)["blockedChecks"]["operatorEvidence"])

    def test_duplicate_approver_and_self_approval_rejected(self):
        e=evidence()
        e["gateReviewers"]["G3_OPERATIONS_RELEASE"]="g2-lead"
        self.assertIn("INDEPENDENT_GATE_REVIEWERS",
            inspect(compose(),e,SHA,NOW)["blockedChecks"]["operatorEvidence"])
        e=evidence()
        e["controls"]["signed_rollback_and_release_change"]["attestedBy"]="g3-lead"
        self.assertIn("signed_rollback_and_release_change",
            inspect(compose(),e,SHA,NOW)["blockedChecks"]["operatorEvidence"])

    def test_missing_or_invalid_digest_never_passes(self):
        for digest in (None,"0"*64,"a"*63,"not-real"):
            with self.subTest(digest=digest):
                e=evidence()
                e["controls"]["encrypted_offsite_physical_backup_target"]["evidenceSha256"]=digest
                self.assertIn("encrypted_offsite_physical_backup_target",
                    inspect(compose(),e,SHA,NOW)["blockedChecks"]["operatorEvidence"])

    def test_output_never_dumps_secrets_or_reviewer_identity(self):
        c=compose()
        e=evidence()
        s=json.dumps(inspect(c,e,SHA,NOW))
        self.assertNotIn("ONLY_FAKE_MOCK_VALUE_123456",s)
        self.assertNotIn("g1-lead",s)
        self.assertNotIn("g3_operations_release-operator",s)
        self.assertIn('"productionApproved": false',s)

    def test_cli_no_evidence_exits_blocked_and_redacts_compose_credentials(self):
        with tempfile.TemporaryDirectory() as d:
            script=Path(__file__).with_name("check_production_gate_v143.py")
            r=subprocess.run([sys.executable,str(script),"--compose-stdin",
                "--candidate-sha",SHA],
                input=json.dumps(compose()),capture_output=True,text=True,timeout=9)
            self.assertEqual(r.returncode,2)
            self.assertEqual(json.loads(r.stdout)["status"],"BLOCKED")
            self.assertNotIn("ONLY_FAKE_MOCK_VALUE_123456",r.stdout+r.stderr)
            file=Path(d)/"claims.json"
            file.write_text(json.dumps(evidence()))
            r=subprocess.run([sys.executable,str(script),"--compose-stdin",
                "--candidate-sha",SHA,"--evidence-json",str(file)],
                input=json.dumps(compose()),capture_output=True,text=True,timeout=9)
            self.assertEqual(r.returncode,3)
            data=json.loads(r.stdout)
            self.assertEqual(data["status"],"AWAITING_OUT_OF_BAND_G1_G2_G3_APPROVAL")
            self.assertFalse(data["productionApproved"])
            self.assertFalse(data["deploymentPermitted"])

if __name__=="__main__":
    unittest.main()
