#!/usr/bin/env python3
"""V160 operator-authorized read-only inspection of three running ERP images.

Only fixed Docker --format selectors are allowed. Never calls Docker from CI
or before explicit authorization and validated expected IDs/revision.
No container env, logs, files, credentials, registry pulls, deploy or writes.
A local image ID/OCI revision match is NOT independently signed provenance.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess

SHA = re.compile("[0-9a-f]{40}")
IMAGE_ID = re.compile("sha256:[0-9a-f]{64}")
CONTAINER = re.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,100}")
SOURCE = "https://github.com/kunlong-luo/merchivra"
SERVICES = ("server", "next-web", "web")
SELECTORS = {
    "IMAGE_ID_MATCH": ("container", "{{.Image}}"),
    "COMPOSE_SERVICE_MATCH": ("container", '{{index .Config.Labels "com.docker.compose.service"}}'),
    "OCI_REVISION_MATCH": ("image", '{{index .Config.Labels "org.opencontainers.image.revision"}}'),
    "OCI_SOURCE_MATCH": ("image", '{{index .Config.Labels "org.opencontainers.image.source"}}'),
}

def _checks() -> dict[str, bool]:
    return {service.upper().replace("-", "_") + "_" + kind: False
            for service in SERVICES for kind in SELECTORS}

def report(checks: dict[str, bool], error: str | None = None,
           candidate: str | None = None) -> dict:
    result = {
        "schemaVersion": 1,
        "mode": "AUTHORIZED_READ_ONLY_TARGET_DOCKER_METADATA",
        "status": ("AWAITING_INDEPENDENT_ARTIFACT_AND_G1_G2_G3_REVIEW"
                   if all(checks.values()) else "BLOCKED"),
        "checks": checks,
        "productionApproved": False,
        "deploymentPermitted": False,
        "imageSignatureCryptographicallyVerified": False,
        "operatorExpectedImageIdsIndependentlyVerified": False,
        "actualRegistryManifestDigestVerified": False,
        "automaticReleasePossible": False,
        "realSellerAdsAuthorized": False,
    }
    if candidate is not None:
        result["operatorClaimedCandidateSha"] = candidate
    if error is not None:
        result["errorCode"] = error
    return result

def fixed_query(kind: str, field: str, object_id: str, runner=subprocess.run) -> str:
    """Read ONE fixed Docker metadata field; never retrieve full inspect JSON."""
    scope, template = SELECTORS[field]
    if kind != scope:
        raise ValueError("invalid_selector_scope")
    try:
        r = runner(
            ["docker", kind, "inspect", "--format", template, object_id],
            capture_output=True, text=True, timeout=5, check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError("docker_metadata_unavailable") from None
    if (r.returncode != 0 or not isinstance(r.stdout, str)
            or len(r.stdout) > 200):
        raise ValueError("docker_metadata_unavailable")
    return r.stdout.strip()

def inspect(candidate: str, names: dict[str, str],
            expected: dict[str, str], runner=subprocess.run) -> dict:
    """Injectable, fixed read-only Docker metadata query for offline tests."""
    checks = _checks()
    if (not SHA.fullmatch(candidate) or candidate == "0" * 40
            or set(names) != set(SERVICES)
            or set(expected) != set(SERVICES)
            or any(not isinstance(names[s], str)
                   or not CONTAINER.fullmatch(names[s])
                   or not isinstance(expected[s], str)
                   or not IMAGE_ID.fullmatch(expected[s])
                   for s in SERVICES)
            or len(set(names.values())) != len(SERVICES)
            or len(set(expected.values())) != len(SERVICES)):
        raise ValueError("invalid_operator_artifact_inputs")
    for service in SERVICES:
        prefix = service.upper().replace("-", "_") + "_"
        try:
            actual = fixed_query("container", "IMAGE_ID_MATCH", names[service], runner)
            checks[prefix + "IMAGE_ID_MATCH"] = (actual == expected[service])
            label = fixed_query("container", "COMPOSE_SERVICE_MATCH", names[service], runner)
            checks[prefix + "COMPOSE_SERVICE_MATCH"] = (label == service)
            if not (checks[prefix + "IMAGE_ID_MATCH"]
                    and checks[prefix + "COMPOSE_SERVICE_MATCH"]):
                continue  # Reject before querying an unexpected image ID.
            revision = fixed_query("image", "OCI_REVISION_MATCH", actual, runner)
            checks[prefix + "OCI_REVISION_MATCH"] = (revision == candidate)
            source = fixed_query("image", "OCI_SOURCE_MATCH", actual, runner)
            checks[prefix + "OCI_SOURCE_MATCH"] = (source == SOURCE)
        except ValueError:
            # Docker errors, secret-bearing stderr, context names, and raw
            # metadata must never appear in the operator report.
            continue
    return report(checks, candidate=candidate)

def main(argv=None, runner=subprocess.run) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--operator-authorized-target", action="store_true")
    parser.add_argument("--candidate-sha", required=True)
    for service in SERVICES:
        arg = service.replace("-", "_")
        parser.add_argument("--container-" + service, required=True)
        parser.add_argument("--expected-" + service + "-image-id", required=True)
    args = parser.parse_args(argv)
    checks = _checks()
    try:
        if (os.getenv("GITHUB_ACTIONS", "").lower() == "true"
                or not args.operator_authorized_target):
            raise ValueError("UNAUTHORIZED_TARGET_CHECK")
        names = {s: getattr(args, "container_" + s.replace("-", "_"))
                 for s in SERVICES}
        expected = {s: getattr(args, "expected_" + s.replace("-", "_") + "_image_id")
                    for s in SERVICES}
        result = inspect(args.candidate_sha, names, expected, runner)
    except (ValueError, TypeError):
        result = report(checks, "PRECONDITIONS_NOT_MET")
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 3 if result["status"].startswith("AWAITING") else 2

if __name__ == "__main__":
    raise SystemExit(main())
