#!/usr/bin/env python3
"""V122 static fail-closed review of opt-in Next Node deployment.

With --compose, read effective docker-compose JSON from stdin. Never connects
to databases, injects credentials or deploys an internet-accessible service.
"""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
parse = argparse.ArgumentParser()
parse.add_argument("--compose", action="store_true")
parse.add_argument("--production", action="store_true")
args = parse.parse_args()

node = (ROOT / "apps/web/Dockerfile.next").read_text()
ignore = (ROOT / "apps/web/.dockerignore").read_text().splitlines()
overlay = (ROOT / "deploy/compose.next.yaml").read_text()
next_config = (ROOT / "apps/web/next.config.ts").read_text()
vite = (ROOT / "apps/web/Dockerfile").read_text()
for exact in (
    "FROM node:24-alpine AS deps",
    "FROM node:24-alpine AS runtime",
    "npm ci --no-audit --no-fund",
    "npm run build:next",
    ".next/standalone",
    ".next/static",
    "USER nextjs",
    'CMD ["node", "server.js"]',
):
    assert exact in node, f"Unsafe/invalid Next runtime: missing {exact}"
assert "FROM nginx:alpine" in vite, "Vite rollback path was deleted"
assert "MERCHIVRA_API_INTERNAL_URL=http://server:8080" in node
assert "MERCHIVRA_API_INTERNAL_URL" in next_config
assert "output:'standalone'" in next_config
assert "source:'/api/:path*'" in next_config
assert not any(secret in overlay for secret in (
    "MERCHIVRA_LWA_CLIENT_SECRET",
    "MERCHIVRA_SPAPI_OAUTH_ENCRYPTION_KEY_BASE64",
    "MERCHIVRA_DB_PASSWORD=",
))
for private in ("node_modules",".next",".env",".env.*","*.pem","*.key"):
    assert private in ignore, f"Next build context missing private-file guard: {private}"
assert "127.0.0.1:" in overlay
assert "http://server:8080" in overlay

if args.compose:
    data = json.load(sys.stdin)
    services = data["services"]
    assert {"postgres","server","web","next-web"} <= services.keys()
    next_web = services["next-web"]
    assert next_web["build"]["dockerfile"] == "Dockerfile.next"
    assert next_web["build"]["args"]["MERCHIVRA_API_INTERNAL_URL"] == "http://server:8080"
    assert next_web["environment"]["NODE_ENV"] == "production"
    assert next_web["environment"]["PORT"] == "3000"
    assert next_web["environment"]["HOSTNAME"] == "0.0.0.0"
    assert set(next_web["environment"]).isdisjoint({
        "MERCHIVRA_DB_PASSWORD", "MERCHIVRA_LWA_CLIENT_SECRET",
        "MERCHIVRA_SPAPI_OAUTH_ENCRYPTION_KEY_BASE64"
    }), "Next container must not receive backend secrets"
    ports = next_web.get("ports",[])
    assert len(ports) == 1
    assert ports[0]["host_ip"] == "127.0.0.1", "Next is internet-exposed"
    assert str(ports[0]["target"]) == "3000"
    assert services["web"]["build"]["context"], "Lost Vite rollback"
    assert services["postgres"]["image"].startswith("postgres:18-")
    if args.production:
        env = services["server"]["environment"]
        for var in (
            "MERCHIVRA_DEMO_ENABLED",
            "MERCHIVRA_SPAPI_ENABLED",
            "MERCHIVRA_SPAPI_OAUTH_ENABLED",
            "MERCHIVRA_SPAPI_ALLOW_WRITES",
        ):
            assert env.get(var,"").lower() == "false", f"Unsafe {var}"
        assert env["MERCHIVRA_SESSION_COOKIE_SECURE"] == "true"

print("PASS V122: nonroot Node standalone, private API proxy, safe rollback "
      "and loopback-only Next container")
