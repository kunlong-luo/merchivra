#!/usr/bin/env python3
"""V146 offline, deterministic TLS configuration and fail-closed entry tests."""
import os
import ssl
import io
import contextlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock,patch
import smoke_https_cutover_v146 as smoke

FAKE={
    "GITHUB_ACTIONS":"true",
    "MERCHIVRA_V146_DISPOSABLE_ONLY":"true",
    "MERCHIVRA_SPAPI_ENABLED":"false",
    "MERCHIVRA_SPAPI_OAUTH_ENABLED":"false",
    "MERCHIVRA_SPAPI_ALLOW_WRITES":"false",
    "MERCHIVRA_BOOTSTRAP_USERNAME":"local-smoke-admin",
    "MERCHIVRA_BOOTSTRAP_PASSWORD":"synthetic-v146-bootstrap-credential",
}

class V146SafeHttpsTests(unittest.TestCase):
    def test_only_fixed_loopback_tls_port_no_http_listener_and_deny_host_spoof(self):
        for target,port in [("next",3001),("vite",3000)]:
            conf=smoke.config_for(target)
            self.assertIn("listen 127.0.0.1:3443 ssl;",conf)
            self.assertNotIn("listen 80;",conf)
            self.assertNotIn("listen 0.0.0.0",conf)
            self.assertIn(f"proxy_pass http://127.0.0.1:{port};",conf)
            self.assertIn(f"X-Merchivra-V146-Candidate {target}",conf)
            self.assertIn("if ($host != 127.0.0.1) { return 421; }",conf)
            self.assertIn("proxy_set_header X-Forwarded-Proto https;",conf)
            self.assertIn('proxy_set_header Forwarded "";',conf)
            self.assertIn("access_log off;",conf)
            self.assertIn("ssl_protocols TLSv1.2 TLSv1.3;",conf)
            self.assertNotIn("example.com",conf)

    def test_cannot_switch_to_user_specified_host_or_port(self):
        for target in ("", "http://evil.invalid", "production", "next:8443",
                       "0.0.0.0", "127.0.0.1:9999", "443", "../next"):
            with self.subTest(target=target),self.assertRaises(ValueError):
                smoke.config_for(target)

    def test_missing_or_unsafe_ci_environment_refused_even_if_disposable_flag(self):
        for key in FAKE:
            env=FAKE.copy()
            env.pop(key)
            with self.subTest(key=key),patch.dict(os.environ,env,clear=True):
                with self.assertRaisesRegex(ValueError,"refuses|missing"):
                    smoke.guard()
        with patch.dict(os.environ,FAKE,clear=True):
            smoke.guard()
        for key in ("MERCHIVRA_SPAPI_ENABLED",
                    "MERCHIVRA_SPAPI_OAUTH_ENABLED",
                    "MERCHIVRA_SPAPI_ALLOW_WRITES"):
            env=FAKE.copy()
            env[key]="true"
            with self.subTest(key=key),patch.dict(os.environ,env,clear=True):
                with self.assertRaises(ValueError):
                    smoke.guard()

    def test_cannot_touch_arbitrary_config_or_output_secret(self):
        with tempfile.TemporaryDirectory(prefix="merchivra-v146-",dir="/tmp") as d:
            good=Path(d)/"nginx.conf"
            smoke.write_config(good,"next")
            self.assertEqual(good.stat().st_mode&0o777,0o600)
            self.assertIn("127.0.0.1:3001",good.read_text())
            smoke.write_config(good,"vite")
            self.assertIn("127.0.0.1:3000",good.read_text())
            self.assertNotIn("127.0.0.1:3001",good.read_text())
        with self.assertRaisesRegex(ValueError,"unsafe_config_path"):
            smoke.write_config(Path("/etc/nginx/nginx.conf"),"next")
        with self.assertRaisesRegex(ValueError,"unsafe_config_path"):
            smoke.write_config(Path("/tmp/nginx.conf"),"next")

    def test_process_fails_closed_before_docker_or_network_without_ci_guard(self):
        with tempfile.TemporaryDirectory(prefix="merchivra-v146-",dir="/tmp") as d:
            conf=Path(d)/"nginx.conf"
            script=Path(__file__).with_name("smoke_https_cutover_v146.py")
            env={**FAKE,"GITHUB_ACTIONS":"false"}
            run=subprocess.run([sys.executable,str(script),"initial-config",
                                "--config",str(conf)],env=env,
                               capture_output=True,text=True,timeout=8)
            self.assertEqual(run.returncode,2)
            self.assertFalse(conf.exists())
            self.assertNotIn("synthetic-v146-bootstrap-credential",
                             run.stdout+run.stderr)
            self.assertIn("BLOCKED",run.stderr)

    def test_failure_stage_is_static_and_redacts_private_exception_payload(self):
        with self.assertRaisesRegex(ValueError,"invalid_static_canary_stage"):
            smoke.mark_stage("../secret_injected_phase")
        diagnostic=io.StringIO()
        def masked_failure(*_):
            smoke.mark_stage("session-cookie")
            raise AssertionError("synthetic-private-token-NEVER-PRINT")
        with (patch.dict(os.environ,FAKE,clear=True),
              patch.object(sys,"argv",[
                  "smoke_https_cutover_v146.py","verify",
                  "--config","/tmp/merchivra-v146-test/nginx.conf",
                  "--cert","/tmp/merchivra-v146-test/cert.pem",
                  "--container","v146-https-123"]),
              patch.object(smoke,"run_synthetic",side_effect=masked_failure),
              contextlib.redirect_stderr(diagnostic)):
            self.assertEqual(smoke.main(),2)
        self.assertIn("BLOCKED: AssertionError stage=session-cookie",
                      diagnostic.getvalue())
        self.assertNotIn("synthetic-private-token-NEVER-PRINT",
                         diagnostic.getvalue())

    def _secure_probe(self,anonymous_status=401,csrf_status=200,secure=True,
                      httponly=True,samesite="Strict"):
        # Mock only the verified HTTPS transport; never contact live systems.
        cookie=Mock()
        cookie.name="JSESSIONID"
        cookie.secure=secure
        cookie.has_nonstandard_attr.side_effect=lambda key: (
            key=="HttpOnly" and httponly)
        cookie.get_nonstandard_attr.side_effect=lambda key,default: (
            samesite if key=="SameSite" else default)
        probe=Mock()
        probe.cookies=[cookie]
        probe.call.side_effect=[
            (anonymous_status,b"",{}),
            (csrf_status,b'{"token":"fixed-local-csrf","headerName":"X-CSRF-TOKEN"}',{})
        ]
        return probe

    def test_secure_auth_probe_requires_401_csrf_and_strict_cookie(self):
        probe=self._secure_probe()
        with patch.object(smoke,"Client",return_value=probe):
            smoke.wait_for_secure_auth("/synthetic-ca",attempts=1)
        self.assertEqual(probe.call.call_count,2)
        self.assertEqual(probe.call.call_args.args[0],"/api/v1/auth/csrf")

    def test_secure_auth_probe_never_retries_insecure_cookie_or_auth_change(self):
        for problem,probe in [
            ("insecure",self._secure_probe(secure=False)),
            ("missing-http-only",self._secure_probe(httponly=False)),
            ("wrong-samesite",self._secure_probe(samesite="Lax")),
            ("anonymous-granted",self._secure_probe(anonymous_status=200)),
            ("anonymous-forbidden",self._secure_probe(anonymous_status=403)),
            ("csrf-rejected",self._secure_probe(csrf_status=403)),
        ]:
            with (self.subTest(problem=problem),
                  patch.object(smoke,"Client",return_value=probe) as factory,
                  patch.object(smoke.time,"sleep") as sleeper,
                  self.assertRaises(AssertionError)):
                smoke.wait_for_secure_auth("/synthetic-ca",attempts=4)
            factory.assert_called_once()
            sleeper.assert_not_called()

    def test_secure_auth_probe_only_retries_bounded_startup_errors(self):
        unavailable=Mock()
        unavailable.call.return_value=(503,b"synthetic 503 content",{})
        working=self._secure_probe()
        with (patch.object(smoke,"Client",side_effect=[unavailable,working]) as factory,
              patch.object(smoke.time,"sleep") as sleeper):
            smoke.wait_for_secure_auth("/synthetic-ca",attempts=2)
        self.assertEqual(factory.call_count,2)
        sleeper.assert_called_once_with(.25)
        # A permanently unavailable backend must remain a hard failure.
        with (patch.object(smoke,"Client",return_value=unavailable),
              patch.object(smoke.time,"sleep") as sleeper,
              self.assertRaises(RuntimeError)):
            smoke.wait_for_secure_auth("/synthetic-ca",attempts=2)
        sleeper.assert_called_once_with(.25)

    def test_secure_auth_probe_refuses_bad_tls_without_retry(self):
        with (patch.object(smoke,"Client",
                           side_effect=ssl.SSLCertVerificationError("synthetic-bad-ca")) as factory,
              patch.object(smoke.time,"sleep") as sleeper,
              self.assertRaises(ssl.SSLError)):
            smoke.wait_for_secure_auth("/synthetic-ca",attempts=4)
        factory.assert_called_once()
        sleeper.assert_not_called()

    def test_v156_rollback_phase_diagnostics_are_static_and_secret_free(self):
        # The V148 first-attempt AssertionError had no stage, so neither
        # its precise failure point nor root cause can be reconstructed.
        # Future failures distinguish reload, identity, page and security
        # assertions without revealing exception payloads or principals.
        boundaries=(
            ("vite-switch", 'switch("vite")'),
            ("vite-auth", 'viewer_vite,_=client.json("/api/v1/auth/me")'),
            ("vite-page", 'fallback_html,headers=client.expect("/purchasing")'),
            ("vite-access-control", 'public.expect("/api/v1/auth/me",expected=401)'),
            ("next-switch", 'switch("next")'),
            ("next-auth", 'viewer_next,_=client.json("/api/v1/auth/me")'),
        )
        source=Path(__file__).with_name("smoke_https_cutover_v146.py").read_text()
        for stage,operation in boundaries:
            with self.subTest(stage=stage):
                self.assertIn(stage,smoke.SAFE_STAGES)
                self.assertIn('mark_stage("'+stage+'")\n    '+operation,source)
                diagnostic=io.StringIO()
                def masked_failure(*_):
                    smoke.mark_stage(stage)
                    raise AssertionError("synthetic-secret-session-id-never-log")
                with (patch.dict(os.environ,FAKE,clear=True),
                      patch.object(sys,"argv",[
                          "smoke_https_cutover_v146.py","verify",
                          "--config","/tmp/merchivra-v146-test/nginx.conf",
                          "--cert","/tmp/merchivra-v146-test/cert.pem",
                          "--container","v146-https-123"]),
                      patch.object(smoke,"run_synthetic",side_effect=masked_failure),
                      contextlib.redirect_stderr(diagnostic)):
                    self.assertEqual(smoke.main(),2)
                self.assertEqual(
                    diagnostic.getvalue(),
                    "V146 TLS canary BLOCKED: AssertionError stage="+stage+"\n"
                )
                self.assertNotIn("synthetic-secret-session-id",diagnostic.getvalue())

    def test_no_production_claim_or_external_host_in_success_evidence(self):
        source=Path(__file__).with_name("smoke_https_cutover_v146.py").read_text()
        for expected in ('"productionApproved":False',
                         '"externalTlsVerified":False',
                         '"amazonLiveEnabled":False',
                         '"viteRollbackSameSession":True',
                         '"nextRollforwardSameSession":True'):
            self.assertIn(expected,source)

if __name__=="__main__":
    unittest.main()
