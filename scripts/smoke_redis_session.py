#!/usr/bin/env python3
"""Local-only Redis Session smoke: cookie survives Spring restart; Redis failure denies auth."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener

p = argparse.ArgumentParser()
p.add_argument("--stage", choices=("login", "persisted", "outage"), required=True)
p.add_argument("--cookies", default="/tmp/merchivra-ci-redis-cookies.txt")
a = p.parse_args()
base = "http://127.0.0.1:3000"
jar = http.cookiejar.MozillaCookieJar(a.cookies)
if a.stage != "login":
    jar.load(ignore_discard=True, ignore_expires=True)
opener = build_opener(HTTPCookieProcessor(jar))

def call(url, data=None, csrf=None):
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        headers["X-CSRF-TOKEN"] = csrf
    req = Request(base + url, data=data, headers=headers, method="POST" if data else "GET")
    try:
        with opener.open(req, timeout=12) as response:
            code, body = response.status, response.read()
    except HTTPError as error:
        code, body = error.code, error.read()
    except (TimeoutError, URLError, ConnectionError):
        return 0, {}
    try:
        value = json.loads(body)
    except (ValueError, UnicodeError):
        value = {}
    return code, value

if a.stage == "login":
    code, csrf = call("/api/v1/auth/csrf")
    assert code == 200, ("csrf", code)
    user = os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"]
    password = os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]
    code, _ = call("/api/v1/auth/login",
        urlencode({"username": user, "password": password}).encode(), csrf["token"])
    assert code == 200, ("login", code)
    code, me = call("/api/v1/auth/me")
    assert code == 200 and me.get("isOwner"), ("me", code)
    assert any(c.name in ("JSESSIONID", "SESSION") for c in jar), "Missing Session cookie"
    # Restrict the disposable cookie and identity files before writing.
    os.umask(0o077)
    jar.save(ignore_discard=True, ignore_expires=True)
    identity = {k: me[k] for k in ("userId", "activeTenantId")}
    Path(a.cookies + ".identity").write_text(json.dumps(identity), encoding="utf-8")
    print("PASS Redis-backed local login and CSRF; cookie persisted for test")
elif a.stage == "persisted":
    code, me = call("/api/v1/auth/me")
    assert code == 200 and me.get("isOwner"), ("Redis session missing after backend restart", code)
    expected = json.loads(Path(a.cookies + ".identity").read_text(encoding="utf-8"))
    assert {k: me[k] for k in expected} == expected, "Session identity changed"
    print("PASS same authenticated session identity after Java backend restart")
else:
    code, _ = call("/api/v1/auth/me")
    # Require an actual denial/error response, not transport failure or an
    # unrelated redirect/404. The workflow also proves anonymous ping works.
    assert code in (401, 403, 500, 502, 503, 504), ("Expected explicit Redis outage denial", code)
    assert not (isinstance(_, dict) and _.get("isOwner")), "Outage returned authorized identity"
    print("PASS Redis unavailable: prior authenticated cookie does not receive authorized data")
