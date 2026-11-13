#!/usr/bin/env python3
"""V158: operator worklist from the REDACTED V143 preflight JSON result only.

No raw Compose config, credentials, target evidence, approvals or external calls.
Every output is a non-authorizing NO-GO report. Standard library only.
"""
from __future__ import annotations

import argparse
import json
import sys
from typing import Any

from check_production_gate_v143 import CONTROL_GROUPS, SHA, safety_checks

# Displayed actions are constants, never copied from potentially hostile input.
COMPOSE_HINTS = {
    "PG18_PRIVATE_UNEXPOSED": ("G3", "DBA", "Review private PostgreSQL 18 image and port"),
    "REDIS_NO_PUBLIC_PORT": ("G2", "Infrastructure", "Confirm Redis has no public listener"),
    "CLAMD_NO_PUBLIC_PORT": ("G2", "Security", "Confirm clamd has no public listener"),
    "PRIVATE_AV_HOST_CONFIGURED": ("G2", "Security", "Verify private clamd and live EICAR separately"),
    "SECURE_SESSION_AND_DEMO_OFF": ("G2", "Security", "Require Secure Session and Demo disabled"),
    "SELLER_OAUTH_AND_WRITES_DISABLED": ("G2", "Security", "Keep Seller OAuth, remote writes and sync off"),
    "NEXT_RUNTIME_LOCAL_AND_ROLLBACK_PRESENT": ("G2", "Infrastructure", "Check Next loopback and Vite rollback"),
    "SPRING_API_PRIVATE_LOOPBACK": ("G2", "Infrastructure", "Confirm Spring API loopback binding"),
    "NO_SECRET_IN_FRONTEND_SERVICE": ("G2", "Security", "Verify no credentials in frontend service"),
    "DB_BOOTSTRAP_SECRET_NOT_PLACEHOLDER": ("G2", "Security", "Independently verify external vault and rotation"),
}
EVIDENCE_HINTS = {
    "product_scope_owner": ("Product owner", "Approve pilot marketplace, SKU, FBA/FBM scope and DEC policies"),
    "operations_workflow_owner": ("Operations owner", "Review redacted real procurement, FBA and inventory journeys"),
    "finance_rules_owner": ("Finance owner", "Verify currency, historical cost, refunds and settlements"),
    "https_trusted_proxy_live": ("Infrastructure", "Test target TLS chain, trusted proxy and rotation"),
    "session_iam_mfa_review": ("Security", "Test target Session, CSRF, IAM/MFA and cookies"),
    "secrets_kms_rotation_review": ("Security", "Review target KMS/vault, least privilege and rotation"),
    "private_clamd_eicar_signature_test": ("Security", "Test private clamd signature, EICAR and fail-closed outages"),
    "tenant_privilege_review": ("Security", "Audit target roles, tenant isolation and log privacy"),
    "next_proxy_csrf_and_rollback_test": ("Infrastructure", "Test target Next login, refresh, CSRF and Vite rollback"),
    "encrypted_offsite_physical_backup_target": ("DBA", "Review encrypted offsite PG18 base backup/WAL repository"),
    "production_wal_archiving_pitr_restore": ("DBA", "Restore target WAL to isolated instances at distinct times"),
    "measured_operator_approved_rpo_rto": ("Release owner", "Measure and approve target RPO/RTO"),
    "pg18_disk_wal_backup_alerting": ("On-call owner", "Test live disk, WAL, archive and backup alerts"),
    "external_pager_delivered_and_acknowledged": ("On-call owner", "Prove real external alert, retry, failover and human ACK"),
    "signed_rollback_and_release_change": ("Release owner", "Approve signed rollback and release change externally"),
}
GATE_GROUPS = {
    "G1_BUSINESS": "G1",
    "G2_SECURITY_ARCHITECTURE": "G2",
    "G3_OPERATIONS_RELEASE": "G3",
}
REVIEW_HINTS = {
    "TARGET_CANDIDATE_IDENTITY": ("G1/G2/G3", "Release owner", "Bind evidence metadata to reviewed main SHA"),
    "INDEPENDENT_GATE_REVIEWERS": ("G1/G2/G3", "Release owner", "Arrange separate gate leads and independent reviewers"),
    "NO_AUTOMATED_RELEASE_APPROVAL": ("G1/G2/G3", "Release owner", "Never assert permission in metadata"),
}
EXPECTED_COMPOSE = frozenset(safety_checks({}))
EXPECTED_EVIDENCE = frozenset([
    *REVIEW_HINTS,
    *(code for codes in CONTROL_GROUPS.values() for code in codes),
])

def _bool_checks(value: Any, expected: frozenset[str]) -> bool:
    return (isinstance(value, dict) and set(value) == expected
            and all(type(v) is bool for v in value.values()))

def validate(redacted: Any) -> tuple[str, dict[str, bool], dict[str, bool]]:
    """Fail closed on any forged, contradictory or incomplete gate report."""
    if not isinstance(redacted, dict):
        raise ValueError("invalid_redacted_report")
    candidate = redacted.get("candidateSha")
    if (not isinstance(candidate, str) or not SHA.fullmatch(candidate)
            or candidate == "0" * 40):
        raise ValueError("invalid_redacted_report")
    checks, blocked = redacted.get("checks"), redacted.get("blockedChecks")
    if not isinstance(checks, dict) or not isinstance(blocked, dict):
        raise ValueError("invalid_redacted_report")
    compose, evidence = checks.get("compose"), checks.get("operatorEvidence")
    if not (_bool_checks(compose, EXPECTED_COMPOSE)
            and _bool_checks(evidence, EXPECTED_EVIDENCE)):
        raise ValueError("invalid_redacted_report")
    for kind, values in (("compose", compose), ("operatorEvidence", evidence)):
        expected_missing = sorted(k for k, ok in values.items() if not ok)
        if type(blocked.get(kind)) is not list or blocked[kind] != expected_missing:
            raise ValueError("invalid_redacted_report")
    all_claimed = all(compose.values()) and all(evidence.values())
    expected_status = ("AWAITING_OUT_OF_BAND_G1_G2_G3_APPROVAL"
                       if all_claimed else "BLOCKED")
    if (redacted.get("schemaVersion") != 1
            or redacted.get("status") != expected_status
            or redacted.get("productionApproved") is not False
            or redacted.get("deploymentPermitted") is not False
            or redacted.get("externalApprovalCryptographicallyVerified") is not False
            or redacted.get("automaticReleasePossible") is not False
            or redacted.get("operatorEvidenceIsSelfAttested") is not True
            or redacted.get("requiredHumanDecision")
            != "G1_G2_G3_SEPARATE_SIGNATURES_AND_TARGET_REVALIDATION"):
        raise ValueError("invalid_redacted_report")
    return candidate, compose, evidence

def worklist(redacted: Any) -> dict[str, Any]:
    sha, compose, evidence = validate(redacted)
    tasks = []
    def add(gate: str, code: str, owner: str, action: str, claim_ok: bool) -> None:
        tasks.append({
            "gate": gate, "code": code, "ownerRole": owner,
            "requiredProof": action,
            "metadataCheck": "STRUCTURAL_PASS_NOT_TARGET_PROOF" if claim_ok else "BLOCKED",
            "externalVerification": "REQUIRED",
        })
    for code in sorted(COMPOSE_HINTS):
        gate, owner, action = COMPOSE_HINTS[code]
        add(gate, code, owner, action, compose[code])
    for code, (gate, owner, action) in REVIEW_HINTS.items():
        add(gate, code, owner, action, evidence[code])
    for group, codes in CONTROL_GROUPS.items():
        for code in codes:
            owner, action = EVIDENCE_HINTS[code]
            add(GATE_GROUPS[group], code, owner, action, evidence[code])
    return {
        "schemaVersion": 1,
        "candidateSha": sha,
        "status": "NO_GO_REQUIRES_TARGET_EVIDENCE_AND_HUMAN_SIGNATURES",
        "productionApproved": False,
        "deploymentPermitted": False,
        "externalApprovalCryptographicallyVerified": False,
        "source": "redacted_v143_structural_preflight_only",
        "blockedStructuralChecks": sum(not x for x in compose.values())
                                   + sum(not x for x in evidence.values()),
        "requiredOutOfBandReview": "G1_G2_G3_SEPARATE_SIGNATURES_AND_TARGET_REVALIDATION",
        "items": tasks,
    }

def markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Merchivra V158 - production evidence worklist (NO-GO)",
        "",
        "Static worklist derived from V143 redacted metadata checks only.",
        "Not a target infrastructure test, real proof verification or signed approval.",
        "All items need independent target evidence and human sign-off.",
        "Amazon live OAuth, remote reads/writes and deployment remain prohibited.",
        "",
        "Candidate main SHA: " + report["candidateSha"],
        "Blocked structural checks: " + str(report["blockedStructuralChecks"]),
        "",
        "| Gate | Fixed check code | Responsible role | Required target action | Metadata-only state |",
        "| --- | --- | --- | --- | --- |",
    ]
    for item in report["items"]:
        lines.append("| " + " | ".join((
            item["gate"], item["code"], item["ownerRole"],
            item["requiredProof"], item["metadataCheck"],
        )) + " |")
    lines.extend((
        "",
        "Status: NO-GO. Even a structurally complete, self-attested V143 "
        "record does not permit deployment.",
        "Signed evidence must stay in approved private systems, never GitHub or public CI.",
        "",
    ))
    return "\n".join(lines)

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    args = parser.parse_args()
    try:
        raw = sys.stdin.buffer.read(65537)
        if len(raw) > 65536:
            raise ValueError("invalid_redacted_report")
        report = worklist(json.loads(raw.decode("utf-8")))
        print(json.dumps(report, sort_keys=True, separators=(",", ":"))
              if args.format == "json" else markdown(report))
    except (ValueError, TypeError, UnicodeError, OSError):
        # Never echo exception payloads, submitted values, credentials or names.
        print(json.dumps({
            "status": "BLOCKED", "errorCode": "INVALID_REDACTED_PREFLIGHT",
            "productionApproved": False, "deploymentPermitted": False,
        }, separators=(",", ":")))
    return 2  # ALWAYS NO-GO, including if all self-attested metadata looks good.

if __name__ == "__main__":
    raise SystemExit(main())
