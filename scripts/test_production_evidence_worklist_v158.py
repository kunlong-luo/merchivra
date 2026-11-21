#!/usr/bin/env python3
"""V158 no-network, no-secret-leak tests for V143 redacted evidence worklists."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import unittest

from check_production_gate_v143 import ALL_CODES, inspect
from production_evidence_worklist_v158 import (
    COMPOSE_HINTS, EVIDENCE_HINTS, EXPECTED_COMPOSE,
    EXPECTED_EVIDENCE, markdown, validate, worklist,
)
from test_production_gate_v143 import compose, evidence, NOW, SHA


class V158ProductionWorklistTests(unittest.TestCase):
    def gate(self, claims=None):
        return inspect(compose(), claims, SHA, NOW)

    def test_control_codes_match_v143_template_without_new_approvals(self):
        template=json.loads(
            (Path(__file__).resolve().parent.parent
             /"ops/release/v143-production-evidence-template.json").read_text()
        )
        self.assertEqual(set(EVIDENCE_HINTS),set(ALL_CODES))
        self.assertEqual(set(COMPOSE_HINTS),EXPECTED_COMPOSE)
        self.assertEqual(set(template["controls"]),set(EVIDENCE_HINTS))
        self.assertTrue(all(x["status"]=="pending"
                            for x in template["controls"].values()))
        self.assertFalse(template["productionApproved"])
        self.assertFalse(template["deploymentPermitted"])

    def test_no_claims_remains_no_go_and_lists_each_expected_owner(self):
        report=worklist(self.gate(None))
        self.assertEqual(report["candidateSha"],SHA)
        self.assertFalse(report["productionApproved"])
        self.assertFalse(report["deploymentPermitted"])
        self.assertEqual(report["blockedStructuralChecks"],len(EXPECTED_EVIDENCE))
        self.assertEqual(len(report["items"]),
                         len(EXPECTED_EVIDENCE)+len(EXPECTED_COMPOSE))
        self.assertTrue(all(task["externalVerification"]=="REQUIRED"
                            for task in report["items"]))
        for code in EVIDENCE_HINTS:
            item=next(x for x in report["items"] if x["code"]==code)
            self.assertEqual(item["metadataCheck"],"BLOCKED")
        self.assertEqual(report["status"],
                         "NO_GO_REQUIRES_TARGET_EVIDENCE_AND_HUMAN_SIGNATURES")

    def test_complete_self_claims_are_never_real_approval(self):
        candidate=self.gate(evidence())
        self.assertEqual(candidate["status"],
                         "AWAITING_OUT_OF_BAND_G1_G2_G3_APPROVAL")
        report=worklist(candidate)
        self.assertEqual(report["blockedStructuralChecks"],0)
        self.assertFalse(report["productionApproved"])
        self.assertFalse(report["deploymentPermitted"])
        self.assertFalse(report["externalApprovalCryptographicallyVerified"])
        self.assertTrue(all(task["externalVerification"]=="REQUIRED"
                            for task in report["items"]))
        self.assertIn("NO-GO",markdown(report))
        self.assertNotIn("APPROVED FOR DEPLOYMENT",markdown(report))

    def test_malformed_or_forged_gate_reports_fail_closed(self):
        valid=self.gate(evidence())
        changes=(
            ("wrong-sha",("candidateSha","not-a-sha")),
            ("approved",("productionApproved",True)),
            ("deploy-allowed",("deploymentPermitted",True)),
            ("crypto-fake",("externalApprovalCryptographicallyVerified",True)),
            ("auto-release",("automaticReleasePossible",True)),
            ("wrong-status",("status","RELEASE_APPROVED")),
            ("unknown-schema",("schemaVersion",2)),
        )
        for name,(key,value) in changes:
            with self.subTest(name=name):
                modified=copy.deepcopy(valid)
                modified[key]=value
                with self.assertRaises(ValueError):
                    validate(modified)
        for name,modify in (
            ("unrecognized-check",lambda r:r["checks"]["compose"].update({"EVIL":True})),
            ("nonbool-check",lambda r:r["checks"]["operatorEvidence"].update(
                {"finance_rules_owner":1})),
            ("blocked-list-mismatch",lambda r:r["blockedChecks"].update(
                {"compose":["PG18_PRIVATE_UNEXPOSED"]})),
            ("missing-blocked-list",lambda r:r["blockedChecks"].pop("compose")),
        ):
            with self.subTest(name=name):
                modified=copy.deepcopy(valid)
                modify(modified)
                with self.assertRaises(ValueError):
                    validate(modified)

    def test_arbitrary_private_data_in_input_never_appears_in_output(self):
        redacted=self.gate(evidence())
        redacted["rawSecret"]="SYNTHETIC-SECRET-NEVER-PRINT"
        redacted["operatorName"]="SYNTHETIC-PRIVATE-PERSON-NEVER-PRINT"
        redacted["controls"]={"foo":"SYNTHETIC-EVIDENCE-NEVER-PRINT"}
        script=Path(__file__).with_name("production_evidence_worklist_v158.py")
        for format in ("markdown","json"):
            with self.subTest(format=format):
                result=subprocess.run(
                    [sys.executable,str(script),"--format",format],
                    input=json.dumps(redacted),text=True,
                    capture_output=True,timeout=9)
                self.assertEqual(result.returncode,2)
                self.assertIn("NO_GO",result.stdout.replace("-","_"))
                for secret in ("SYNTHETIC-SECRET","SYNTHETIC-PRIVATE-PERSON",
                               "SYNTHETIC-EVIDENCE"):
                    self.assertNotIn(secret,result.stdout+result.stderr)
                self.assertNotIn("deploymentPermitted\": true",result.stdout)

    def test_raw_compose_or_oversize_is_rejected_with_fixed_error_only(self):
        script=Path(__file__).with_name("production_evidence_worklist_v158.py")
        for raw in (
            '{"services":{"server":{"environment":{"DB_PASSWORD":"SECRET_NEVER_PRINT"}}}}',
            "SYNTHETIC-SECRET-NEVER-PRINT"*10000,
            "{ invalid JSON SYNTHETIC-SECRET-NEVER-PRINT",
        ):
            with self.subTest(length=len(raw)):
                result=subprocess.run(
                    [sys.executable,str(script),"--format","json"],
                    input=raw,text=True,capture_output=True,timeout=9)
                self.assertEqual(result.returncode,2)
                report=json.loads(result.stdout)
                self.assertEqual(report["errorCode"],"INVALID_REDACTED_PREFLIGHT")
                self.assertFalse(report["productionApproved"])
                self.assertFalse(report["deploymentPermitted"])
                self.assertNotIn("SECRET_NEVER_PRINT",result.stdout+result.stderr)


if __name__=="__main__":
    unittest.main()
