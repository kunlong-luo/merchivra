#!/usr/bin/env python3
"""V160 deterministic no-Docker/no-credentials tests."""
from __future__ import annotations

import contextlib
import io
import json
import os
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import check_target_image_ids_v160 as gate

SHA = "a" * 40
IDS = {
    "server": "sha256:" + "1" * 64,
    "next-web": "sha256:" + "2" * 64,
    "web": "sha256:" + "3" * 64,
}
NAMES = {"server": "v160_server_1",
         "next-web": "v160_next-web_1",
         "web": "v160_web_1"}
PRIVATE = "FAKE-PRIVATE-DO-NOT-ECHO-ANYWHERE"

def fake_runner(commands=None, failures=()):
    calls = []
    def run(argv, **kwargs):
        calls.append(argv)
        assert argv[:3] in (
            ["docker", "container", "inspect"], ["docker", "image", "inspect"])
        assert argv[3] == "--format"
        assert kwargs["capture_output"] is True
        assert kwargs["text"] is True
        assert kwargs["timeout"] == 5
        assert kwargs["check"] is False
        scope, template, obj = argv[1], argv[4], argv[5]
        if (scope, template, obj) in failures:
            return SimpleNamespace(returncode=1, stdout="", stderr=PRIVATE)
        if scope == "container":
            service = next((k for k,v in NAMES.items() if v == obj),None)
            if service is None:
                return SimpleNamespace(returncode=1, stdout="", stderr=PRIVATE)
            result = (IDS[service] if template == gate.SELECTORS["IMAGE_ID_MATCH"][1]
                      else service if template == gate.SELECTORS["COMPOSE_SERVICE_MATCH"][1]
                      else PRIVATE)
        else:
            result = (SHA if template == gate.SELECTORS["OCI_REVISION_MATCH"][1]
                      else gate.SOURCE if template == gate.SELECTORS["OCI_SOURCE_MATCH"][1]
                      else PRIVATE)
        return SimpleNamespace(returncode=0, stdout=result+"\n", stderr=PRIVATE)
    return run, calls

def arguments():
    args = ["--operator-authorized-target","--candidate-sha",SHA]
    for s in gate.SERVICES:
        args.extend(["--container-"+s,NAMES[s],
                     "--expected-"+s+"-image-id",IDS[s]])
    return args

class TargetImageEvidenceV160Tests(unittest.TestCase):
    def test_three_immutable_ids_and_revision_are_checked_but_never_signed(self):
        runner, calls=fake_runner()
        result=gate.inspect(SHA,NAMES,IDS,runner)
        self.assertEqual(result["status"],
                         "AWAITING_INDEPENDENT_ARTIFACT_AND_G1_G2_G3_REVIEW")
        self.assertTrue(all(result["checks"].values()))
        self.assertEqual(len(result["checks"]),12)
        self.assertEqual(len(calls),12)
        self.assertEqual(result["operatorClaimedCandidateSha"],SHA)
        for key in ("productionApproved","deploymentPermitted",
                    "imageSignatureCryptographicallyVerified",
                    "operatorExpectedImageIdsIndependentlyVerified",
                    "actualRegistryManifestDigestVerified","automaticReleasePossible",
                    "realSellerAdsAuthorized"):
            self.assertFalse(result[key],key)
        output=json.dumps(result)
        for secret in (PRIVATE,*IDS.values(),*NAMES.values()):
            self.assertNotIn(secret,output)
        self.assertTrue(all(cmd[3]=="--format" for cmd in calls))
        self.assertTrue(all(".Config.Env" not in cmd[4] and
                            "{{json .}}" not in cmd[4] for cmd in calls))

    def test_wrong_image_id_blocks_without_querying_unexpected_image(self):
        expected=dict(IDS)
        expected["next-web"]="sha256:"+"f"*64
        runner,calls=fake_runner()
        result=gate.inspect(SHA,NAMES,expected,runner)
        self.assertEqual(result["status"],"BLOCKED")
        self.assertFalse(result["checks"]["NEXT_WEB_IMAGE_ID_MATCH"])
        self.assertFalse(result["checks"]["NEXT_WEB_OCI_REVISION_MATCH"])
        self.assertFalse(any(cmd[1]=="image" and cmd[-1]==IDS["next-web"]
                             for cmd in calls))
        self.assertFalse(result["productionApproved"])

    def test_wrong_or_missing_oci_labels_never_authorize(self):
        for category in ("revision","source"):
            runner,calls=fake_runner()
            label = (gate.SELECTORS["OCI_REVISION_MATCH"][1] if category=="revision"
                     else gate.SELECTORS["OCI_SOURCE_MATCH"][1])
            def changed(argv,**kwargs):
                r=runner(argv,**kwargs)
                if argv[1]=="image" and argv[4]==label and argv[-1]==IDS["server"]:
                    return SimpleNamespace(returncode=0,stdout="untrusted\n",stderr=PRIVATE)
                return r
            report=gate.inspect(SHA,NAMES,IDS,changed)
            self.assertEqual(report["status"],"BLOCKED")
            self.assertFalse(report["checks"]["SERVER_OCI_"+category.upper()+"_MATCH"])
            self.assertFalse(report["deploymentPermitted"])

    def test_compose_service_label_mismatch_rejected(self):
        runner,calls=fake_runner()
        def changed(argv,**kwargs):
            r=runner(argv,**kwargs)
            if argv[1]=="container" and argv[4]==gate.SELECTORS["COMPOSE_SERVICE_MATCH"][1]:
                return SimpleNamespace(returncode=0,stdout="wrong-service\n",stderr=PRIVATE)
            return r
        result=gate.inspect(SHA,NAMES,IDS,changed)
        self.assertEqual(result["status"],"BLOCKED")
        self.assertTrue(all(not result["checks"][s.upper().replace("-","_")+"_COMPOSE_SERVICE_MATCH"]
                            for s in gate.SERVICES))
        self.assertEqual(len([c for c in calls if c[1]=="image"]),0)

    def test_subprocess_failures_never_echo_sensitive_stderr(self):
        runner,calls=fake_runner()
        def fails(argv,**kwargs):
            if argv[-1]==NAMES["server"]:
                return SimpleNamespace(returncode=1,stdout="",stderr=PRIVATE)
            return runner(argv,**kwargs)
        result=gate.inspect(SHA,NAMES,IDS,fails)
        self.assertEqual(result["status"],"BLOCKED")
        self.assertFalse(result["checks"]["SERVER_IMAGE_ID_MATCH"])
        self.assertNotIn(PRIVATE,json.dumps(result))

    def test_invalid_expectations_rejected_before_docker_call(self):
        for sha,names,expected in (
            ("0"*40,NAMES,IDS),("INVALID",NAMES,IDS),
            (SHA,{**NAMES,"server":"--evil"},IDS),
            (SHA,{**NAMES,"server":NAMES["next-web"]},IDS),
            (SHA,NAMES,{**IDS,"server":"sha256:"+"x"*64}),
            (SHA,NAMES,{**IDS,"server":IDS["web"]}),
        ):
            runner,calls=fake_runner()
            with self.subTest(sha=sha),self.assertRaises(ValueError):
                gate.inspect(sha,names,expected,runner)
            self.assertEqual(calls,[])

    def test_operator_and_ci_guards_do_not_touch_docker(self):
        for args,env in (
            (arguments()[1:],{}),
            (arguments(),{"GITHUB_ACTIONS":"true"}),
        ):
            runner,calls=fake_runner()
            output=io.StringIO()
            with patch.dict(os.environ,env,clear=True),contextlib.redirect_stdout(output):
                status=gate.main(args,runner)
            self.assertEqual(status,2)
            self.assertFalse(calls)
            data=json.loads(output.getvalue())
            self.assertEqual(data["errorCode"],"PRECONDITIONS_NOT_MET")
            self.assertFalse(data["productionApproved"])
            self.assertNotIn(PRIVATE,output.getvalue())

    def test_authorized_operator_observation_exits_three_not_zero(self):
        runner,calls=fake_runner()
        out=io.StringIO()
        with patch.dict(os.environ,{},clear=True),contextlib.redirect_stdout(out):
            code=gate.main(arguments(),runner)
        self.assertEqual(code,3)
        self.assertEqual(len(calls),12)
        data=json.loads(out.getvalue())
        self.assertFalse(data["productionApproved"])
        self.assertFalse(data["deploymentPermitted"])
        self.assertFalse(data["imageSignatureCryptographicallyVerified"])

    def test_runtime_image_labels_and_compose_args_are_wired_for_all_services(self):
        root=Path(__file__).resolve().parents[1]
        for path in ("apps/server/Dockerfile","apps/web/Dockerfile.next",
                     "apps/web/Dockerfile"):
            with self.subTest(path=path):
                source=(root/path).read_text()
                self.assertIn("ARG MERCHIVRA_BUILD_REVISION",source)
                self.assertIn("org.opencontainers.image.revision",source)
                self.assertIn("org.opencontainers.image.source",source)
        for path in ("deploy/compose.yaml","deploy/compose.next.yaml"):
            with self.subTest(path=path):
                source=(root/path).read_text()
                expected=2 if path.endswith("compose.yaml") else 1
                self.assertEqual(source.count("MERCHIVRA_BUILD_REVISION:"),expected)

if __name__ == "__main__":
    unittest.main()
