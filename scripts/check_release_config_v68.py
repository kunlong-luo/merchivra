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
    "MERCHIVRA_EVIDENCE_AV_REQUIRED":"true",
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

assert "MERCHIVRA_EVIDENCE_AV_HOST" in env, "V137 private clamd hostname must be configurable"
port=env.get("MERCHIVRA_EVIDENCE_AV_PORT","3310")
assert str(port).isdigit() and 1<=int(port)<=65535, "Invalid clamd internal TCP port"
# An absent host is an intentional *runtime* startup blocker until operations
# provisions a private scanner. Do not invent external ingress or a public port.
assert not services.get("clamav",{}).get("ports"), "ClamAV must never publish an external TCP port"
print("PASS V68/V137 Compose: PG18, Demo/Seller off, secure Session, internal AV required, loopback-only ingress")
