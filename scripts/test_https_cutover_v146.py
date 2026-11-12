#!/usr/bin/env python3
"""V146 offline, deterministic TLS configuration and fail-closed entry tests."""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
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
