#!/usr/bin/env python3
"""V151 offline unit tests: no DNS, target traffic or credentials."""
import contextlib
import io
import json
import os
import ssl
import unittest
from unittest.mock import Mock, patch

import check_next_target_ingress_v151 as target

HOST = "erp.private.internal"
PRIVATE_CSRF = "synthetic-secret-csrf-NOT-OUTPUT"
PRIVATE_COOKIE = "synthetic-session-value-NOT-OUTPUT"

def observation(status, body=b"", cookies=()):
    return target.Observation(
        status, tuple(("Set-Cookie", cookie) for cookie in cookies), body
    )

def ping_probe():
    return observation(200, b'{"service":"merchivra","status":"ok","stage":"M0"}')

def next_probe():
    return target.Observation(200, (("Content-Type", "text/html; charset=utf-8"),),
        b'<html><title>Merchivra ERP</title><script src="/_next/static/chunks/main.js"></script></html>')

def secure_probe():
    return observation(
        200,
        json.dumps({"headerName": "X-CSRF-TOKEN", "token": PRIVATE_CSRF}).encode(),
        (f"JSESSIONID={PRIVATE_COOKIE}; Path=/; Secure; HttpOnly; SameSite=Strict",),
    ).__class__(200, (
        ("Set-Cookie", f"JSESSIONID={PRIVATE_COOKIE}; Path=/; Secure; HttpOnly; SameSite=Strict"),
        ("Cache-Control", "no-store"),
    ), json.dumps({"headerName": "X-CSRF-TOKEN", "token": PRIVATE_CSRF}).encode())

class V151OperatorTargetIngressTests(unittest.TestCase):
    def test_only_explicit_secure_dns_origin_without_credentials_or_paths(self):
        self.assertEqual(target.parse_origin("https://erp.private.internal"), HOST)
        self.assertEqual(target.parse_origin("https://erp.private.internal:443/"), HOST)
        for unsafe in (
            "http://erp.private.internal",
            "https://localhost",
            "https://127.0.0.1",
            "https://example.com",
            "https://erp.example.org",
            "https://erp.invalid",
            "https://erp.local",
            "https://erp.private.internal:8443",
            "https://user:token@erp.private.internal",
            "https://erp.private.internal/signin",
            "https://erp.private.internal/?token=secret",
            "https://erp.private.internal/#fragment",
            "https://erp.private.internal.evil.invalid",
            "https://-bad.private.internal",
            "https://erp.private.internal:bad",
            "",
        ):
            with self.subTest(value=unsafe), self.assertRaises(ValueError):
                target.parse_origin(unsafe)

    def test_five_anonymous_gets_no_credential_and_no_secret_echo(self):
        calls = []
        responses = [
            ping_probe(),
            next_probe(),
            observation(401),
            secure_probe(),
            observation(421),
        ]
        def fetch(host, path, forged):
            calls.append((host, path, forged))
            return responses.pop(0)
        result = target.inspect(HOST, fetch, tls_validated=True)
        self.assertEqual(result["status"], "AWAITING_INDEPENDENT_HUMAN_G2_G3_REVIEW")
        self.assertTrue(all(result["checks"].values()))
        self.assertEqual(calls, [
            (HOST, "/api/v1/ping", False),
            (HOST, "/orders", False),
            (HOST, "/api/v1/auth/me", False),
            (HOST, "/api/v1/auth/csrf", False),
            (HOST, "/api/v1/ping", True),
        ])
        self.assertFalse(result["productionApproved"])
        self.assertFalse(result["deploymentPermitted"])
        self.assertFalse(result["credentialsSent"])
        self.assertFalse(result["realSellerAdsAuthorized"])
        encoded = json.dumps(result)
        for private in (PRIVATE_CSRF, PRIVATE_COOKIE, HOST):
            self.assertNotIn(private, encoded)

    def test_ping_200_must_be_actual_spring_merchivra_identity(self):
        for body in (b"", b"<html>nginx is healthy</html>", b'{}',
                     b'{"status":"ok","service":"other","stage":"M0"}',
                     b'{"status":"ok","service":"merchivra","stage":"MOCK"}',
                     b'{"status":"degraded","service":"merchivra","stage":"M0"}'):
            calls = []
            def fetch(_, path, forged):
                calls.append(path)
                return observation(200, body)
            with self.subTest(body=body):
                result = target.inspect(HOST, fetch, tls_validated=True)
                self.assertEqual(result["status"], "BLOCKED")
                self.assertTrue(result["checks"]["NEXT_INGRESS_PING_200"])
                self.assertFalse(result["checks"]["SPRING_PING_IDENTITY"])
                self.assertEqual(calls, ["/api/v1/ping"])

    def test_vite_spa_and_generic_html_cannot_pass_as_next_app_router(self):
        examples = (
            observation(200, b"<html>Merchivra ERP<script src='/assets/main.js'></script></html>"),
            target.Observation(200, (("Content-Type", "text/html"),),
                               b"<html>Merchivra ERP<script src='/assets/main.js'></script></html>"),
            target.Observation(200, (("Content-Type", "text/html"),),
                               b"<html><script src='/_next/static/main.js'></script></html>"),
            target.Observation(200, (("Content-Type", "application/json"),),
                               next_probe().body),
            target.Observation(302, (("Content-Type", "text/html"),),
                               next_probe().body),
        )
        for page in examples:
            calls = []
            def fetch(_, path, forged):
                calls.append(path)
                return ping_probe() if len(calls) == 1 else page
            with self.subTest(status=page.status, body=page.body):
                result = target.inspect(HOST, fetch, tls_validated=True)
                self.assertEqual(result["status"], "BLOCKED")
                self.assertTrue(result["checks"]["SPRING_PING_IDENTITY"])
                self.assertFalse(result["checks"]["NEXT_APP_ROUTER_HTML"])
                self.assertEqual(calls, ["/api/v1/ping", "/orders"])

    def test_csrf_cannot_be_cached_or_omit_security_header(self):
        insecure=secure_probe()
        without_cache=target.Observation(insecure.status,
            tuple((k,v) for k,v in insecure.headers if k!="Cache-Control"),
            insecure.body)
        with_cache=target.Observation(insecure.status,
            (("Set-Cookie", insecure.headers[0][1]), ("Cache-Control", "public, max-age=60")),
            insecure.body)
        for csrf in (without_cache, with_cache):
            responses=iter((ping_probe(),next_probe(),observation(401),csrf))
            result=target.inspect(HOST,lambda *_:next(responses),tls_validated=True)
            self.assertFalse(result["checks"]["ANONYMOUS_CSRF_NO_STORE"])
            self.assertFalse(result["productionApproved"])
            self.assertEqual(result["status"],"BLOCKED")

    def test_fail_closed_on_missing_anonymous_denial_and_no_further_probing(self):
        for status in (200, 302, 403, 500):
            with self.subTest(status=status):
                calls = []
                def fetch(host, path, forged):
                    calls.append(path)
                    return (ping_probe() if len(calls) == 1 else
                            next_probe() if len(calls) == 2 else
                            observation(status))
                result = target.inspect(HOST, fetch, tls_validated=True)
                self.assertEqual(result["status"], "BLOCKED")
                self.assertFalse(result["checks"]["ANONYMOUS_ME_401"])
                self.assertEqual(calls, ["/api/v1/ping", "/orders",
                                         "/api/v1/auth/me"])

    def test_csrf_and_cookie_must_both_be_safe(self):
        scenarios = (
            observation(403),
            observation(302),
            observation(200, b'{"headerName":"X-CSRF-TOKEN","token":""}'),
            observation(200, b'{no json'),
            observation(200, secure_probe().body),
            observation(200, secure_probe().body,
                        (f"JSESSIONID={PRIVATE_COOKIE}; Path=/; HttpOnly; SameSite=Strict",)),
            observation(200, secure_probe().body,
                        (f"JSESSIONID={PRIVATE_COOKIE}; Path=/; Secure; SameSite=Strict",)),
            observation(200, secure_probe().body,
                        (f"JSESSIONID={PRIVATE_COOKIE}; Path=/; Secure; HttpOnly; SameSite=Lax",)),
        )
        for csrf in scenarios:
            with self.subTest(csrf_status=csrf.status, csrf_headers=csrf.headers):
                responses = iter((ping_probe(), next_probe(), observation(401), csrf))
                result = target.inspect(HOST, lambda *_: next(responses),
                                        tls_validated=True)
                self.assertEqual(result["status"], "BLOCKED")
                self.assertFalse(all(result["checks"].values()))

    def test_invalid_host_must_fail_closed_and_no_redirect_is_followed(self):
        for status in (200, 301, 302, 307, 308, 401, 500):
            with self.subTest(status=status):
                responses = iter((ping_probe(), next_probe(), observation(401),
                                  secure_probe(), observation(status)))
                result = target.inspect(HOST, lambda *_: next(responses),
                                        tls_validated=True)
                self.assertEqual(result["status"], "BLOCKED")
                self.assertFalse(result["checks"]["INVALID_HOST_REJECTED"])

    def test_connection_uses_verified_tls_fixed_host_get_only(self):
        captured = []
        class SyntheticConnection:
            def __init__(self, **kwargs):
                captured.append(("connect", kwargs))
            def request(self, method, path, headers):
                captured.append(("request", method, path, headers))
            def getresponse(self):
                response = Mock()
                response.status = 421
                response.read.return_value = b""
                response.getheaders.return_value = []
                return response
            def close(self):
                captured.append(("closed",))
        tls = ssl.create_default_context()
        with patch.object(target.http.client, "HTTPSConnection",
                          side_effect=lambda **kw: SyntheticConnection(**kw)):
            response = target.request_https(HOST, "/api/v1/ping", tls, True)
        self.assertEqual(response.status, 421)
        self.assertEqual(captured[0][1]["port"], 443)
        self.assertIs(captured[0][1]["context"], tls)
        method, path, headers = captured[1][1:]
        self.assertEqual((method, path), ("GET", "/api/v1/ping"))
        self.assertEqual(headers["Host"], "untrusted.invalid")
        self.assertFalse(any(k.lower() in ("authorization", "cookie") for k in headers))
        self.assertEqual(captured[-1], ("closed",))
        with self.assertRaisesRegex(ValueError, "invalid_fixed_probe_path"):
            target.request_https(HOST, "/api/v1/auth/logout", tls)

    def test_refuses_ci_and_unapproved_operator_before_any_network(self):
        for args, env in (
            (["--origin", f"https://{HOST}"], {}),
            (["--origin", f"https://{HOST}", "--operator-authorized-target"],
             {"GITHUB_ACTIONS": "true"}),
        ):
            buf = io.StringIO()
            with (self.subTest(args=args, env=env),
                  patch.dict(os.environ, env, clear=True),
                  patch.object(target, "request_https") as outbound,
                  contextlib.redirect_stdout(buf)):
                status = target.main(args)
            self.assertEqual(status, 2)
            outbound.assert_not_called()
            result = json.loads(buf.getvalue())
            self.assertEqual(result["status"], "BLOCKED")
            self.assertFalse(result["productionApproved"])
            self.assertNotIn(HOST, buf.getvalue())

    def test_authorized_cli_never_returns_deployment_success(self):
        responses = iter((ping_probe(), next_probe(), observation(401),
                          secure_probe(), observation(400)))
        result_stream = io.StringIO()
        with (patch.dict(os.environ, {}, clear=True),
              patch.object(target, "request_https",
                           side_effect=lambda *_: next(responses)),
              contextlib.redirect_stdout(result_stream)):
            status = target.main(["--origin", f"https://{HOST}",
                                  "--operator-authorized-target"])
        self.assertEqual(status, 3)
        observed = json.loads(result_stream.getvalue())
        self.assertEqual(observed["status"], "AWAITING_INDEPENDENT_HUMAN_G2_G3_REVIEW")
        for secret in (PRIVATE_CSRF, PRIVATE_COOKIE, HOST):
            self.assertNotIn(secret, result_stream.getvalue())
        self.assertFalse(observed["productionApproved"])

if __name__ == "__main__":
    unittest.main()
