#!/usr/bin/env python3
"""V143 fail-closed, READ-ONLY production candidate preflight.

Never deploys, authorizes Amazon, makes network calls, reads private customer
files, writes a DB or grants go-live. docker compose config JSON may contain
secrets; report only fixed check codes and booleans, never input field values.
Evidence is OPERATOR-PROVIDED METADATA, not independently authenticated proof.
Even a perfectly filled form cannot turn productionApproved to true.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import ipaddress
import json
import re
from pathlib import Path
import sys
from typing import Any

SHA = re.compile(r"[a-f0-9]{40}\Z")
DIGEST = re.compile(r"[a-f0-9]{64}\Z")
REVIEWER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{2,79}\Z")
AV_HOST = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}\Z")
UTC = timezone.utc

# Operator references are opaque SHA-256 fingerprints, NEVER evidence files,
# raw backup snapshots, token material, contact addresses or real customer data.
CONTROL_GROUPS = {
    "G1_BUSINESS": (
        "product_scope_owner",
        "operations_workflow_owner",
        "finance_rules_owner",
    ),
    "G2_SECURITY_ARCHITECTURE": (
        "https_trusted_proxy_live",
        "session_iam_mfa_review",
        "secrets_kms_rotation_review",
        "private_clamd_eicar_signature_test",
        "tenant_privilege_review",
        "next_proxy_csrf_and_rollback_test",
    ),
    "G3_OPERATIONS_RELEASE": (
        "encrypted_offsite_physical_backup_target",
        "production_wal_archiving_pitr_restore",
        "measured_operator_approved_rpo_rto",
        "pg18_disk_wal_backup_alerting",
        "external_pager_delivered_and_acknowledged",
        "signed_rollback_and_release_change",
    ),
}
ALL_CODES = tuple(x for v in CONTROL_GROUPS.values() for x in v)
REQUIRED_DISABLED = (
    "MERCHIVRA_SPAPI_ENABLED",
    "MERCHIVRA_SPAPI_OAUTH_ENABLED",
    "MERCHIVRA_SPAPI_PRIVATE_ENABLED",
    "MERCHIVRA_SPAPI_ALLOW_WRITES",
    "MERCHIVRA_SPAPI_VERIFICATION_ENABLED",
    "MERCHIVRA_ORDERS_SYNC_ENABLED",
    "MERCHIVRA_ORDERS_SYNC_LIVE_APPROVED",
)
NONPRODUCTION_PASSWORD_MARKERS = (
    "please_change", "ci_disposable", "test_only", "replace_me",
    "example", "changeme", "password123", "devonly",
)

def timestamp(value: Any, now: datetime, days: int) -> bool:
    if not isinstance(value,str) or len(value)>40:
        return False
    try:
        moment=datetime.fromisoformat(value.replace("Z","+00:00"))
    except ValueError:
        return False
    if moment.tzinfo is None or moment.utcoffset() is None:
        return False
    moment=moment.astimezone(UTC)
    return now-timedelta(days=days)<=moment<=now+timedelta(minutes=5)

def private_dns_hostname(value: Any) -> bool:
    if not isinstance(value,str) or not value or not AV_HOST.fullmatch(value):
        return False
    if value.lower() in ("localhost","0.0.0.0","example.com",
                         "public.example.com","clamav.example.com"):
        return False
    if ".." in value or value.startswith("-") or value.endswith("-"):
        return False
    try:
        address=ipaddress.ip_address(value)
        # Even valid RFC1918/loopback IPs are a manual networking decision.
        # Reject raw IPs in config; expected private resolvable service DNS.
        return False
    except ValueError:
        pass
    return all(0<len(part)<=63 and not part.startswith("-")
               and not part.endswith("-") for part in value.split("."))

def hidden_password_configured(value: Any) -> bool:
    # Check presence only, never log values or hashes.
    if not isinstance(value,str) or len(value)<16:
        return False
    lowered=value.lower()
    return not any(marker in lowered for marker in NONPRODUCTION_PASSWORD_MARKERS)

def loopback_ports(service: Any) -> bool:
    if not isinstance(service,dict):
        return False
    ports=service.get("ports")
    return isinstance(ports,list) and bool(ports) and all(
        isinstance(port,dict) and port.get("host_ip")=="127.0.0.1"
        and isinstance(port.get("published"),(str,int))
        for port in ports
    )

def safety_checks(compose: dict[str,Any]) -> dict[str,bool]:
    services=compose.get("services",{}) if isinstance(compose,dict) else {}
    if not isinstance(services,dict):services={}
    server=services.get("server",{})
    pg=services.get("postgres",{})
    redis=services.get("redis",{})
    nextweb=services.get("next-web",{})
    web=services.get("web",{})
    env=server.get("environment",{}) if isinstance(server,dict) else {}
    if not isinstance(env,dict):env={}
    nextenv=nextweb.get("environment",{}) if isinstance(nextweb,dict) else {}
    if not isinstance(nextenv,dict):nextenv={}
    pgenv=pg.get("environment",{}) if isinstance(pg,dict) else {}
    if not isinstance(pgenv,dict):pgenv={}
    # Compose may produce numeric 8080 without string; only check host bind.
    def no_public_ports(item: Any) -> bool:
        return isinstance(item,dict) and not item.get("ports")
    image=pg.get("image","") if isinstance(pg,dict) else ""
    av=env.get("MERCHIVRA_EVIDENCE_AV_HOST")
    return {
        "PG18_PRIVATE_UNEXPOSED":
            isinstance(image,str) and image.startswith("postgres:18-")
            and no_public_ports(pg),
        "REDIS_NO_PUBLIC_PORT":no_public_ports(redis),
        "CLAMD_NO_PUBLIC_PORT":
            "clamav" not in services or no_public_ports(services.get("clamav")),
        "PRIVATE_AV_HOST_CONFIGURED":private_dns_hostname(av)
            and str(env.get("MERCHIVRA_EVIDENCE_AV_REQUIRED","")).lower()=="true",
        "SECURE_SESSION_AND_DEMO_OFF":
            str(env.get("MERCHIVRA_SESSION_COOKIE_SECURE","")).lower()=="true"
            and str(env.get("MERCHIVRA_DEMO_ENABLED","")).lower()=="false",
        "SELLER_OAUTH_AND_WRITES_DISABLED":
            all(str(env.get(key,"")).lower()=="false" for key in REQUIRED_DISABLED),
        "NEXT_RUNTIME_LOCAL_AND_ROLLBACK_PRESENT":
            loopback_ports(nextweb) and loopback_ports(web)
            and isinstance(nextweb.get("healthcheck"),dict)
            and nextenv.get("NODE_ENV")=="production",
        "SPRING_API_PRIVATE_LOOPBACK":loopback_ports(server),
        "NO_SECRET_IN_FRONTEND_SERVICE":
            all(not (str(key).startswith("DB_")
                     or "SECRET" in str(key).upper()
                     or "PASSWORD" in str(key).upper()
                     or "TOKEN" in str(key).upper())
                for key in nextenv),
        "DB_BOOTSTRAP_SECRET_NOT_PLACEHOLDER":
            hidden_password_configured(env.get("DB_PASSWORD"))
            and hidden_password_configured(pgenv.get("POSTGRES_PASSWORD"))
            and env.get("DB_PASSWORD")==pgenv.get("POSTGRES_PASSWORD"),
    }

def evidence_checks(evidence: Any, expected_sha: str,
                    now: datetime) -> dict[str,bool]:
    checks={
        "TARGET_CANDIDATE_IDENTITY":False,
        "INDEPENDENT_GATE_REVIEWERS":False,
        "NO_AUTOMATED_RELEASE_APPROVAL":False,
        **{code:False for code in ALL_CODES},
    }
    if not isinstance(evidence,dict):
        return checks
    checks["TARGET_CANDIDATE_IDENTITY"]=(
        evidence.get("schemaVersion")==1
        and evidence.get("environment")=="production"
        and evidence.get("candidateSha")==expected_sha
        and evidence.get("source")=="operator_attested_target"
    )
    # Deliberately refuse a JSON file that tries to authorize any deployment.
    checks["NO_AUTOMATED_RELEASE_APPROVAL"]=(
        evidence.get("productionApproved") is False
        and evidence.get("deploymentPermitted") is False
    )
    controls=evidence.get("controls")
    reviews=evidence.get("gateReviewers")
    if not isinstance(controls,dict) or not isinstance(reviews,dict):
        return checks
    leads=[]
    for group,codes in CONTROL_GROUPS.items():
        reviewer=reviews.get(group)
        if isinstance(reviewer,str) and REVIEWER.fullmatch(reviewer):
            leads.append(reviewer)
        for code in codes:
            record=controls.get(code)
            if not isinstance(record,dict):continue
            days=7 if group=="G3_OPERATIONS_RELEASE" else 30
            reviewer_id=record.get("attestedBy")
            checks[code]=(
                record.get("status")=="verified"
                and isinstance(record.get("evidenceSha256"),str)
                and bool(DIGEST.fullmatch(record["evidenceSha256"]))
                and record["evidenceSha256"]!="0"*64
                and isinstance(reviewer_id,str)
                and bool(REVIEWER.fullmatch(reviewer_id))
                and reviewer_id!=reviewer
                and timestamp(record.get("testedAt"),now,days)
                and record.get("source")=="operator_attested_target"
            )
    # A lead for each gate must be distinct. The individual control attestant
    # cannot be their own group's lead. Neither is a verified signature.
    checks["INDEPENDENT_GATE_REVIEWERS"]=(
        len(leads)==len(CONTROL_GROUPS) and len(set(leads))==len(leads))
    return checks

def inspect(compose: Any,evidence: Any,sha: str,now: datetime) -> dict[str,Any]:
    if not isinstance(sha,str) or not SHA.fullmatch(sha):
        raise ValueError("invalid_candidate_sha")
    if now.tzinfo is None:
        raise ValueError("naive_current_time")
    compose_checks=safety_checks(compose)
    attestations=evidence_checks(evidence,sha,now)
    missing_config=sorted(k for k,v in compose_checks.items() if not v)
    missing_evidence=sorted(k for k,v in attestations.items() if not v)
    eligible=not missing_config and not missing_evidence
    return {
        "schemaVersion":1,
        "candidateSha":sha,
        "status":"AWAITING_OUT_OF_BAND_G1_G2_G3_APPROVAL" if eligible else "BLOCKED",
        "productionApproved":False,
        "deploymentPermitted":False,
        "externalApprovalCryptographicallyVerified":False,
        "checks":{"compose":compose_checks,"operatorEvidence":attestations},
        "blockedChecks":{"compose":missing_config,"operatorEvidence":missing_evidence},
        "requiredHumanDecision":"G1_G2_G3_SEPARATE_SIGNATURES_AND_TARGET_REVALIDATION",
        "operatorEvidenceIsSelfAttested":True,
        "automaticReleasePossible":False,
    }

def bounded_json(content: str,max_bytes: int) -> Any:
    if len(content.encode("utf-8"))>max_bytes:
        raise ValueError("input_exceeds_safe_json_limit")
    try:return json.loads(content)
    except json.JSONDecodeError as exc:
        raise ValueError("malformed_json") from exc

def cli() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-stdin",action="store_true",required=True,
                        help="Read docker compose config --format json from stdin; NEVER log secrets")
    parser.add_argument("--evidence-json",help="Optional operator metadata only, no raw evidence or credentials")
    parser.add_argument("--candidate-sha",required=True)
    args=parser.parse_args()
    try:
        if not SHA.fullmatch(args.candidate_sha):
            raise ValueError("invalid_candidate_sha")
        source=sys.stdin.buffer.read(1024*1024+1)
        if len(source)>1024*1024:
            raise ValueError("compose_json_too_large")
        config=bounded_json(source.decode("utf-8"),1024*1024)
        evidence=None
        if args.evidence_json is not None:
            f=Path(args.evidence_json)
            if f.stat().st_size>256*1024:
                raise ValueError("attestation_metadata_too_large")
            evidence=bounded_json(f.read_text(encoding="utf-8"),256*1024)
        result=inspect(config,evidence,args.candidate_sha,datetime.now(UTC))
        # An incomplete audit is exit 2. Even a complete self-attested file
        # cannot cause a deployment; exit 3 specifically means NEED HUMAN.
        exit_code=3 if result["status"].startswith("AWAITING") else 2
    except (OSError,UnicodeError,TypeError,ValueError) as error:
        code=str(error) if isinstance(error,ValueError) else "input_not_available"
        # Only stable errors we authored; never surface exception payloads.
        if code not in ("invalid_candidate_sha","malformed_json",
                        "input_exceeds_safe_json_limit","compose_json_too_large",
                        "attestation_metadata_too_large","input_not_available"):
            code="invalid_input"
        result={"schemaVersion":1,"status":"BLOCKED",
                "productionApproved":False,"deploymentPermitted":False,
                "errorCode":code}
        exit_code=2
    print(json.dumps(result,sort_keys=True,separators=(",",":")))
    return exit_code

if __name__=="__main__":
    raise SystemExit(cli())
