#!/usr/bin/env python3
"""Exercise the production Redis GCRA Lua script against disposable Redis 8.

No Amazon credentials, outbound SP-API calls, persistent user keys or FLUSHDB.
"""
from __future__ import annotations

import re
import subprocess
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "apps/server/src/main/java/org/merchivra/erp/spapi/RedisSpApiRateLimiter.java"
match = re.search(
    r'OPERATION_GCRA\s*=\s*new DefaultRedisScript<>\("""(.*?)""", Long.class\);',
    SOURCE.read_text(encoding="utf-8"),
    re.DOTALL,
)
if not match:
    raise SystemExit("Cannot identify production SP-API Lua admission script")
SCRIPT = match.group(1)

BASE = [
    "docker", "compose", "--env-file", ".env", "-f", "deploy/compose.yaml",
    "--profile", "optional-cache", "exec", "-T", "redis", "redis-cli", "--raw",
]


def redis(*args: str) -> str:
    output = subprocess.check_output(
        [*BASE, *args], cwd=ROOT, text=True, timeout=15,
    )
    return output.strip()


def eval_script(prefix: str, burst: int) -> int:
    return int(redis(
        "EVAL", SCRIPT, "3",
        prefix + ":next", prefix + ":interval", prefix + ":429",
        "2000", str(burst),
    ))


def verify() -> None:
    prefix = "merchivra:ci:spapi:gcra:" + uuid.uuid4().hex
    # Header absent: never assume more than one token even if configured burst >1.
    default = prefix + ":default"
    assert eval_script(default, 3) == 0
    assert eval_script(default, 3) > 0

    # Learning a rate and explicitly approving burst=3 permits exactly 3
    # immediate requests. The fourth must be refused by the same shared key.
    approved = prefix + ":approved"
    redis("SET", approved + ":interval", "1000", "PX", "10000")
    assert [eval_script(approved, 3) for _ in range(3)] == [0, 0, 0]
    assert eval_script(approved, 3) > 0

    # With no approved burst, the same learned rate permits just one.
    single = prefix + ":single"
    redis("SET", single + ":interval", "1000", "PX", "10000")
    assert eval_script(single, 1) == 0
    assert eval_script(single, 1) > 0

    # An Amazon 429 cooling fence is checked atomically with the token budget.
    fenced = prefix + ":fenced"
    redis("SET", fenced + ":429", "1", "PX", "3000")
    assert eval_script(fenced, 3) >= 1000
    assert redis("EXISTS", fenced + ":next") == "0"

    # Malformed optional metadata falls back to no-burst conservative defaults.
    unknown = prefix + ":invalid"
    redis("SET", unknown + ":interval", "not-a-number", "PX", "10000")
    assert eval_script(unknown, 3) == 0
    assert eval_script(unknown, 3) > 0
    print("SP-API Redis 8 atomic GCRA smoke: PASS")


if __name__ == "__main__":
    verify()
