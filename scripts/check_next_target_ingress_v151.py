#!/usr/bin/env python3
"""V151/V152: operator-only target TLS/Next ingress observations, not a release gate.

No authentication, Seller/Ads, writes, or deployment. Four fixed anonymous
GET routes plus a deliberately invalid Host on /api/v1/ping. This can create
only an anonymous CSRF session in the target Spring Session store. DO NOT run
against a target without authorization. No network is used by tests or CI.
Only fixed check codes and booleans reach stdout; never print response bodies,
tokens, cookies, hostnames, certificate contents, or exception messages.
A valid observation exits 3 (human approval REQUIRED), never 0.
"""
from __future__ import annotations

import argparse
import http.client
import ipaddress
import json
import os
import re
import ssl
import sys
from dataclasses import dataclass
from urllib.parse import urlsplit

HOST = re.compile(r"(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\Z")
SHA = re.compile(r"[a-f0-9]{40}\Z")
CHECKS = (
    "TLS_CHAIN_AND_HOSTNAME_VERIFIED",
    "NEXT_INGRESS_PING_200",
    "SPRING_PING_IDENTITY",
    "SPRING_PING_JSON_CONTENT_TYPE",
    "NEXT_APP_ROUTER_HTML",
    "ANONYMOUS_ME_401",
    "ANONYMOUS_CSRF_200_VALID",
    "ANONYMOUS_CSRF_JSON_CONTENT_TYPE",
    "ANONYMOUS_CSRF_NO_STORE",
    "SESSION_SECURE_HTTPONLY_SAMESITE_STRICT",
    "INVALID_HOST_REJECTED",
)
REJECT_SUFFIXES = (".example", ".invalid", ".test", ".localhost", ".local",
                   ".example.com", ".example.net", ".example.org")

@dataclass(frozen=True)
class Observation:
    status: int
    headers: tuple[tuple[str, str], ...]
    body: bytes

def parse_origin(value: str) -> str:
    if not isinstance(value, str) or len(value) > 253:
        raise ValueError("invalid_origin")
    try:
        u = urlsplit(value)
        host = u.hostname
        port = u.port
    except ValueError as exc:
        raise ValueError("invalid_origin") from exc
    if (u.scheme != "https" or not host or u.username is not None
            or u.password is not None or u.query or u.fragment
            or u.path not in ("", "/") or port not in (None, 443)
            or not HOST.fullmatch(host) or host.endswith(REJECT_SUFFIXES)
            or host in ("example.com", "example.net", "example.org")):
        raise ValueError("invalid_origin")
    try:
        ipaddress.ip_address(host)
    except ValueError:
        return host
    raise ValueError("invalid_origin")

def request_https(host: str, path: str, context: ssl.SSLContext,
                  forged_host: bool = False) -> Observation:
    # Fixed HTTPS host, port and routes. No redirects, Cookie, bearer
    # credentials, arbitrary headers or user-supplied path; never POST.
    if path not in ("/api/v1/ping", "/api/v1/auth/me", "/api/v1/auth/csrf", "/orders"):
        raise ValueError("invalid_fixed_probe_path")
    conn = http.client.HTTPSConnection(host=host, port=443, context=context, timeout=5)
    try:
        conn.request(
            "GET", path,
            headers={"Host": "untrusted.invalid" if forged_host else host,
                     "Accept": "text/html" if path == "/orders" else "application/json",
                     "Connection": "close"},
        )
        response = conn.getresponse()
        # The one approved Next HTML GET can be larger than the API JSON.
        max_bytes = 262144 if path == "/orders" else 16384
        body = response.read(max_bytes + 1)
        if len(body) > max_bytes:
            raise ValueError("response_limit_exceeded")
        return Observation(response.status, tuple(response.getheaders()), body)
    finally:
        conn.close()

def json_media_valid(headers: tuple[tuple[str, str], ...]) -> bool:
    """V159: reject ambiguous, absent or non-JSON responses at fixed API GETs.

    Media type is checked without recording headers, body, or target data.
    Exactly one Content-Type must be application/json; charset is permitted.
    """
    values = [v for k, v in headers if k.lower() == "content-type"]
    return (len(values) == 1
            and values[0].split(";", 1)[0].strip().lower() == "application/json")


def no_store(headers: tuple[tuple[str, str], ...]) -> bool:
    return any(key.lower() == "cache-control"
               and any(item.strip().split("=", 1)[0] == "no-store"
                       for item in value.lower().split(","))
               for key, value in headers)


def spring_ping_valid(body: bytes) -> bool:
    try:
        data = json.loads(body)
    except (ValueError, UnicodeError):
        return False
    return (isinstance(data, dict)
            and data.get("service") == "merchivra"
            and data.get("status") == "ok"
            and data.get("stage") == "M0")


def next_html_valid(body: bytes, headers: tuple[tuple[str, str], ...]) -> bool:
    # This discriminates Next's real App Router HTML from Vite's /assets/
    # fallback, static Nginx 200, a generic splash page or an API mock.
    return (any(k.lower() == "content-type"
                and v.lower().split(";", 1)[0].strip() == "text/html"
                for k, v in headers)
            and b"Merchivra ERP" in body
            and b"/_next/static/" in body)


def session_flags(headers: tuple[tuple[str, str], ...]) -> bool:
    cookies = []
    for key, value in headers:
        if key.lower() != "set-cookie":
            continue
        parts = [p.strip() for p in value.split(";")]
        if parts and parts[0].split("=", 1)[0] == "JSESSIONID":
            cookies.append(parts)
    if (len(cookies) != 1 or "=" not in cookies[0][0]
            or not cookies[0][0].split("=", 1)[1]):
        return False
    attrs = {p.lower() for p in cookies[0][1:]}
    return ("secure" in attrs and "httponly" in attrs
            and "samesite=strict" in attrs and "path=/" in attrs)

def csrf_valid(body: bytes) -> bool:
    try:
        data = json.loads(body)
    except (ValueError, UnicodeError):
        return False
    return (isinstance(data, dict)
            and data.get("headerName") == "X-CSRF-TOKEN"
            and isinstance(data.get("token"), str)
            and 1 <= len(data["token"]) <= 512)

def report(checks: dict[str, bool], error: str | None = None) -> dict:
    result = {
        "schemaVersion": 1,
        "mode": "OPERATOR_ONLY_READ_ONLY_TARGET_OBSERVATION",
        "status": "AWAITING_INDEPENDENT_HUMAN_G2_G3_REVIEW"
        if all(checks.values()) else "BLOCKED",
        "checks": checks,
        "productionApproved": False,
        "deploymentPermitted": False,
        "g1g2g3Signed": False,
        "externalApprovalCryptographicallyVerified": False,
        "realSellerAdsAuthorized": False,
        "credentialsSent": False,
        "automatedDeploymentPossible": False,
        # A claimed repository SHA is NOT a verified deployed image digest.
        "targetDeploymentShaIndependentlyVerified": False,
    }
    if error is not None:
        result["errorCode"] = error
    return result

def inspect(host: str, fetch, tls_validated: bool) -> dict:
    """Injectable transport for offline tests; no network unless CLI calls it."""
    checks = {code: False for code in CHECKS}
    try:
        ping = fetch(host, "/api/v1/ping", False)
        checks["TLS_CHAIN_AND_HOSTNAME_VERIFIED"] = tls_validated
        checks["NEXT_INGRESS_PING_200"] = ping.status == 200
        checks["SPRING_PING_JSON_CONTENT_TYPE"] = (
            ping.status == 200 and json_media_valid(ping.headers))
        checks["SPRING_PING_IDENTITY"] = (
            checks["SPRING_PING_JSON_CONTENT_TYPE"]
            and spring_ping_valid(ping.body))
        if not checks["SPRING_PING_IDENTITY"]:
            return report(checks)
        # /api can work identically through Vite and Next; verify the actual
        # App Router response before asserting a target Next cutover.
        next_page = fetch(host, "/orders", False)
        checks["NEXT_APP_ROUTER_HTML"] = (next_page.status == 200
                                          and next_html_valid(next_page.body,
                                                              next_page.headers))
        if not checks["NEXT_APP_ROUTER_HTML"]:
            return report(checks)
        me = fetch(host, "/api/v1/auth/me", False)
        checks["ANONYMOUS_ME_401"] = me.status == 401
        if not checks["ANONYMOUS_ME_401"]:
            return report(checks)
        csrf = fetch(host, "/api/v1/auth/csrf", False)
        checks["ANONYMOUS_CSRF_JSON_CONTENT_TYPE"] = (
            csrf.status == 200 and json_media_valid(csrf.headers))
        checks["ANONYMOUS_CSRF_200_VALID"] = (
            checks["ANONYMOUS_CSRF_JSON_CONTENT_TYPE"] and csrf_valid(csrf.body))
        checks["ANONYMOUS_CSRF_NO_STORE"] = (csrf.status == 200 and no_store(csrf.headers))
        checks["SESSION_SECURE_HTTPONLY_SAMESITE_STRICT"] = (
            csrf.status == 200 and session_flags(csrf.headers))
        if not (checks["ANONYMOUS_CSRF_200_VALID"]
                and checks["ANONYMOUS_CSRF_NO_STORE"]
                and checks["SESSION_SECURE_HTTPONLY_SAMESITE_STRICT"]):
            return report(checks)
        host_probe = fetch(host, "/api/v1/ping", True)
        checks["INVALID_HOST_REJECTED"] = host_probe.status in (400, 403, 404, 421)
        return report(checks)
    except (OSError, ssl.SSLError, http.client.HTTPException):
        return report(checks, "TLS_OR_TRANSPORT_FAILED")
    except (UnicodeError, ValueError, TypeError):
        return report(checks, "INVALID_TARGET_RESPONSE")

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", required=True,
                        help="Explicit HTTPS origin of your OWN reviewed target, port 443 only")
    parser.add_argument("--candidate-sha", required=True,
                        help="Operator-claimed reviewed main commit SHA (NOT verified deployed image)")
    parser.add_argument("--operator-authorized-target", action="store_true",
                        help="Confirm authority to read-only probe this actual target")
    parser.add_argument("--ca-file", help="Optional trusted private CA PEM file, never uploaded")
    args = parser.parse_args(argv)
    try:
        if (os.getenv("GITHUB_ACTIONS") == "true"
                or not args.operator_authorized_target):
            raise ValueError("unauthorized_target_check")
        if (not SHA.fullmatch(args.candidate_sha)
                or args.candidate_sha == "0" * 40):
            raise ValueError("invalid_candidate_sha")
        host = parse_origin(args.origin)
        context = ssl.create_default_context(cafile=args.ca_file)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        result = inspect(
            host, lambda h, p, forged: request_https(h, p, context, forged),
            tls_validated=True,
        )
        # Only an operator assertion. The probe does NOT read a deployed
        # container digest, signed provenance or real main SHA from target.
        result["operatorClaimedCandidateSha"] = args.candidate_sha
    except (OSError, ssl.SSLError, ValueError, TypeError):
        # Never print path or content of --ca-file or --origin.
        result = report({code: False for code in CHECKS}, "PRECONDITIONS_NOT_MET")
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    # Non-zero even if all observations pass: no script may issue GO-LIVE.
    return 3 if result["status"].startswith("AWAITING") else 2

if __name__ == "__main__":
    raise SystemExit(main())
