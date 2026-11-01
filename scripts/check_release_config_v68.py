#!/usr/bin/env python3
"""V68 fail-closed Compose config audit, without booting an internet-facing stack.

Read 'docker compose config --format json' from stdin. Requires production overlay
and optional-cache profile to expose the actual effective defaults.
"""
import json
import sys

config=json.load(sys.stdin)
services=config.get("services",{})
assert {"postgres","server","web","redis"} <= set(services), "Unexpected bundled services"
env=services["server"]["environment"]
required={
    "MERCHIVRA_DEMO_ENABLED":"false",
    "MERCHIVRA_SESSION_COOKIE_SECURE":"true",
    "MERCHIVRA_SPAPI_ENABLED":"false",
    "MERCHIVRA_SPAPI_OAUTH_ENABLED":"false",
    "MERCHIVRA_SPAPI_PRIVATE_ENABLED":"false",
    "MERCHIVRA_SPAPI_ALLOW_WRITES":"false",
    "MERCHIVRA_SPAPI_VERIFICATION_ENABLED":"false",
    "MERCHIVRA_ORDERS_SYNC_ENABLED":"false",
    "MERCHIVRA_ORDERS_SYNC_LIVE_APPROVED":"false",
}
for key,expected in required.items():
    assert str(env.get(key,"")).lower()==expected, f"Production overlay default unsafe: {key}"

assert services["postgres"]["image"].startswith("postgres:18-"),"Postgres 18 mandatory"
assert not services["postgres"].get("ports"),"Database cannot bind public ports"
assert not services["redis"].get("ports"),"Redis cannot bind public ports"
redis_command=services["redis"].get("command",[])
assert "noeviction" in str(redis_command),"Optional Redis must never evict Session data"
assert "optional-cache" in services["redis"].get("profiles",[]),"Redis must be opt-in"
for service in ("server","web"):
    ports=services[service].get("ports",[])
    assert ports, f"{service} ingress must be explicit and loopback-only"
    for port in ports:
        assert port.get("host_ip")=="127.0.0.1",f"{service} binds a non-loopback host port"

print("PASS V68 Compose: PG18, optional Redis noeviction, production cookie secure, Demo/Seller egress off, loopback only")
