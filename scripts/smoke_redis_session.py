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
    jar.save(ignore_discard=True, ignore_expires=True)
    print("PASS Redis-backed local login and CSRF; cookie persisted for test")
elif a.stage == "persisted":
    code, me = call("/api/v1/auth/me")
    assert code == 200 and me.get("isOwner"), ("Redis session missing after backend restart", code)
    print("PASS same authenticated session after Java backend restart")
else:
    code, _ = call("/api/v1/auth/me")
    assert code != 200, "FAILED CLOSED: Redis outage must never authorize a cached or in-memory session"
    print("PASS Redis unavailable: prior authenticated cookie does not receive authorized data")
