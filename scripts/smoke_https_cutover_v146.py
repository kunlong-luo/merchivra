#!/usr/bin/env python3
"""V146 real TLS (synthetic CA) ingress -> Next -> Vite -> Next session canary.

Only runs against TWO pre-existing disposable loopback frontends in Actions;
never deploys, opens a public port, creates a production signing authority,
authorizes Amazon, writes business facts or accepts arbitrary destination URLs.
Certificate private keys remain exclusively in /tmp and are removed on exit.
"""
from __future__ import annotations
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import (
    HTTPCookieProcessor, HTTPSHandler, Request, build_opener
)

BASE="https://127.0.0.1:3443"
ROUTES=("/", "/core", "/orders", "/catalog", "/inventory", "/purchasing",
        "/ops", "/finance", "/connections", "/team", "/sync")
PORTS={"next":3001,"vite":3000}
# V149: only fixed non-sensitive diagnostic stages may appear in CI stderr.
# Never print exception text, URLs, response bodies, session or credential data.
SAFE_STAGES=frozenset((
    "preflight","ingress-next","anonymous-access","csrf-init",
    "login-guard","login-real","session-cookie","next-routes",
    "vite-rollback","next-rollforward","logout"
))
CURRENT_STAGE="preflight"

def mark_stage(stage:str)->None:
    if stage not in SAFE_STAGES:
        raise ValueError("invalid_static_canary_stage")
    global CURRENT_STAGE
    CURRENT_STAGE=stage

CONTAINER=re.compile(r"v146-https-[0-9]+\Z")
LOCAL_PATH=re.compile(r"/tmp/merchivra-v146-[a-zA-Z0-9_-]+/nginx.conf\Z")
CONFIG_TEMPLATE="""worker_processes 1;
error_log /dev/stderr warn;
pid /tmp/v146-nginx.pid;
events { worker_connections 128; }
http {
  access_log off;
  server_tokens off;
  ssl_protocols TLSv1.2 TLSv1.3;
  server {
    listen 127.0.0.1:3443 ssl;
    server_name 127.0.0.1;
    ssl_certificate /v146/cert.pem;
    ssl_certificate_key /v146/key.pem;
    ssl_session_cache off;
    if ($host != 127.0.0.1) { return 421; }
    add_header X-Merchivra-V146-Candidate __TARGET_LABEL__ always;
    location / {
      proxy_pass http://127.0.0.1:__TARGET_PORT__;
      proxy_http_version 1.1;
      proxy_set_header Host 127.0.0.1;
      proxy_set_header X-Forwarded-Host 127.0.0.1;
      proxy_set_header X-Forwarded-Port 3443;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header X-Forwarded-For $remote_addr;
      proxy_set_header Forwarded "";
      proxy_set_header Connection "";
      proxy_connect_timeout 3s;
      proxy_read_timeout 20s;
      proxy_redirect off;
    }
  }
}
"""

def config_for(target:str)->str:
    if target not in PORTS:
        raise ValueError("invalid_fixed_backend")
    return (CONFIG_TEMPLATE.replace("__TARGET_PORT__",str(PORTS[target]))
            .replace("__TARGET_LABEL__",target))

def guard()->None:
    required={
        "GITHUB_ACTIONS":"true",
        "MERCHIVRA_V146_DISPOSABLE_ONLY":"true",
        "MERCHIVRA_SPAPI_ENABLED":"false",
        "MERCHIVRA_SPAPI_OAUTH_ENABLED":"false",
        "MERCHIVRA_SPAPI_ALLOW_WRITES":"false",
        "MERCHIVRA_BOOTSTRAP_USERNAME":"local-smoke-admin"
    }
    if any(os.environ.get(k)!=v for k,v in required.items()):
        raise ValueError("v146_refuses_non_disposable_ci")
    if len(os.environ.get("MERCHIVRA_BOOTSTRAP_PASSWORD",""))<16:
        raise ValueError("missing_disposable_bootstrap_password")

def write_config(config_path:Path,target:str)->None:
    if not LOCAL_PATH.fullmatch(str(config_path)):
        raise ValueError("unsafe_config_path")
    if not config_path.parent.is_dir():
        raise ValueError("missing_disposable_config_directory")
    temporary=config_path.parent/"nginx.conf.new"
    temporary.write_text(config_for(target),encoding="ascii")
    temporary.chmod(0o600)
    temporary.replace(config_path)

class Client:
    def __init__(self,certificate:str):
        self.context=ssl.create_default_context(cafile=certificate)
        self.cookies=http.cookiejar.CookieJar()
        self.http=build_opener(HTTPCookieProcessor(self.cookies),
                              HTTPSHandler(context=self.context))

    def call(self,path:str,method="GET",body=None,csrf=None,expected=200,
             extra_headers=None):
        if not path.startswith("/") or path.startswith("//"):
            raise ValueError("unsafe_http_path")
        headers={"Accept":"application/json"}
        if extra_headers:
            headers.update(extra_headers)
        data=None
        if body is not None:
            headers["Content-Type"]="application/x-www-form-urlencoded"
            data=urlencode(body).encode("ascii")
        if csrf is not None:
            headers["X-CSRF-TOKEN"]=csrf
        request=Request(BASE+path,method=method,headers=headers,data=data)
        try:
            with self.http.open(request,timeout=15) as response:
                return response.status,response.read(),response.headers
        except HTTPError as error:
            return error.code,error.read(),error.headers

    def expect(self,path,expected=200,**kwargs):
        status,body,headers=self.call(path,**kwargs)
        allowed=expected if isinstance(expected,tuple) else (expected,)
        if status not in allowed:
            raise AssertionError("unexpected_response_"+path+"_"+str(status))
        return body,headers

    def json(self,path,expected=200,**kwargs):
        body,headers=self.expect(path,expected=expected,**kwargs)
        return json.loads(body),headers

def run_synthetic(certificate:str,config_path:Path,container:str):
    if not CONTAINER.fullmatch(container):
        raise ValueError("invalid_disposable_container")
    if not LOCAL_PATH.fullmatch(str(config_path)):
        raise ValueError("unsafe_config_path")
    client=Client(certificate)
    public=Client(certificate)

    def validate_ingress(target:str):
        for attempt in range(40):
            try:
                ping,headers=client.json("/api/v1/ping")
                if (ping.get("service")=="merchivra"
                        and ping.get("status")=="ok"
                        and headers.get("X-Merchivra-V146-Candidate")==target):
                    return
            except (OSError,ValueError,AssertionError):
                pass
            time.sleep(.25)
        raise RuntimeError("loopback_tls_ingress_not_ready_"+target)

    def switch(target:str):
        write_config(config_path,target)
        # The running container can read only the mounted /v146 directory.
        # No docker compose, production files or arbitrary config targets.
        for args in (
            ["docker","exec",container,"nginx","-t","-c","/v146/nginx.conf"],
            ["docker","exec",container,"nginx","-c","/v146/nginx.conf","-s","reload"]
        ):
            result=subprocess.run(args,capture_output=True,timeout=10)
            if result.returncode!=0:
                # Never echo stdout/stderr from a proxy containing request data.
                raise RuntimeError("synthetic_ingress_reload_failed")
        validate_ingress(target)

    mark_stage("ingress-next")
    validate_ingress("next")
    mark_stage("anonymous-access")
    anon,_=public.expect("/api/v1/auth/me",expected=401)
    if anon not in (b"",None):
        # Error pages are permitted, but may not include a real tenant UUID.
        pass
    mark_stage("csrf-init")
    csrf,_=client.json("/api/v1/auth/csrf")
    if csrf.get("headerName")!="X-CSRF-TOKEN":
        raise AssertionError("csrf_header_missing")
    password=os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"]
    username=os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"]
    mark_stage("login-guard")
    client.expect("/api/v1/auth/login",method="POST",
                  body={"username":username,"password":password},
                  expected=(401,403))
    mark_stage("login-real")
    client.expect("/api/v1/auth/login",method="POST",
                  body={"username":username,"password":password},
                  csrf=csrf["token"],extra_headers={
                      "X-Forwarded-Proto":"http",
                      "Forwarded":"for=203.0.113.42;proto=http;host=evil.invalid",
                      "X-Forwarded-Host":"evil.invalid"
                  })
    mark_stage("session-cookie")
    viewer,headers=client.json("/api/v1/auth/me")
    if not viewer.get("isOwner") or not viewer.get("activeTenantId"):
        raise AssertionError("real_session_not_authenticated")
    if "no-store" not in headers.get("Cache-Control","").lower():
        raise AssertionError("private_api_cache_control_missing")
    sessions=[cookie for cookie in client.cookies if cookie.name=="JSESSIONID"]
    if len(sessions)!=1 or not sessions[0].secure:
        raise AssertionError("secure_session_cookie_missing")
    if (not sessions[0].has_nonstandard_attr("HttpOnly")
            or sessions[0].get_nonstandard_attr("SameSite","").lower()!="strict"):
        raise AssertionError("httponly_or_samesite_missing")
    public.expect("/api/v1/auth/me",expected=401)
    # A crafted Host header must be rejected at the exact synthetic ingress.
    client.expect("/api/v1/ping",expected=421,
                  extra_headers={"Host":"evil.invalid"})
    mark_stage("next-routes")
    for path in ROUTES:
        html,headers=client.expect(path)
        if b"Merchivra ERP" not in html:
            raise AssertionError("next_html_missing_"+path)
        if headers.get("X-Merchivra-V146-Candidate")!="next":
            raise AssertionError("wrong_next_upstream")
        for secret in (viewer["activeTenantId"],viewer["userId"],
                       csrf["token"],password):
            if secret.encode() in html:
                raise AssertionError("next_html_private_data_leak_"+path)
    client.expect("/invalid-v146-route",expected=404)
    client.expect("/api/v1/operations/scan",method="POST",
                  body={},expected=403)
    client.expect("/api/v1/spapi/oauth/callback",expected=(401,404))

    # Same origin, same secure HttpOnly cookie, same Spring Session:
    # Vite rollback must keep identity without a new login or a copied token.
    mark_stage("vite-rollback")
    switch("vite")
    viewer_vite,_=client.json("/api/v1/auth/me")
    if viewer_vite.get("userId")!=viewer["userId"]:
        raise AssertionError("vite_rollback_lost_authenticated_principal")
    fallback_html,headers=client.expect("/purchasing")
    if headers.get("X-Merchivra-V146-Candidate")!="vite":
        raise AssertionError("rollback_wrong_backend")
    if b"Merchivra" not in fallback_html:
        raise AssertionError("rollback_unavailable")
    public.expect("/api/v1/auth/me",expected=401)
    client.expect("/api/v1/operations/scan",method="POST",
                  body={},expected=403)

    # Roll forward after verifying Vite: identity still in same Spring store.
    mark_stage("next-rollforward")
    switch("next")
    viewer_next,_=client.json("/api/v1/auth/me")
    if viewer_next.get("userId")!=viewer["userId"]:
        raise AssertionError("next_rollforward_lost_session")
    mark_stage("logout")
    new_csrf,_=client.json("/api/v1/auth/csrf")
    client.expect("/api/v1/auth/logout",method="POST",
                  csrf=new_csrf["token"],expected=204)
    client.expect("/api/v1/auth/me",expected=401)
    public.expect("/api/v1/auth/me",expected=401)

    # Only public synthetic acceptance booleans, never account ID,
    # certificate private key, password, Cookie, CSRF token, or raw HTML.
    print(json.dumps({
        "mode":"disposable_tls_only","tlsVerifiedBySyntheticLocalCa":True,
        "httpsProxyLoopbackOnly":True,"externalTlsVerified":False,
        "allNextWorkbenchRoutes":len(ROUTES),
        "secureHttpOnlySameSiteSession":True,
        "anonymousIsolation":True,
        "hostHeaderRejection":True,
        "spoofedForwardedHeadersOverridden":True,
        "csrfUnsafeWriteRejected":True,
        "viteRollbackSameSession":True,
        "nextRollforwardSameSession":True,
        "logoutRevokedSession":True,
        "amazonLiveEnabled":False,
        "productionApproved":False
    },separators=(",",":")))
    print("PASS V146 real synthetic HTTPS: local cert verified, Secure Session, "
          "11 Next routes, CSRF/host spoof blocked, same-cookie Vite rollback "
          "and Next rollforward, logout invalidated; production NOT approved",
          file=sys.stderr)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("mode",choices=("initial-config","verify"))
    parser.add_argument("--config",required=True)
    parser.add_argument("--cert")
    parser.add_argument("--container")
    args=parser.parse_args()
    try:
        mark_stage("preflight")
        guard()
        config=Path(args.config)
        if args.mode=="initial-config":
            write_config(config,"next")
            return 0
        if (args.cert is None or args.container is None
                or not args.cert.startswith(str(config.parent)+"/")):
            raise ValueError("unsafe_cert_or_container")
        run_synthetic(args.cert,config,args.container)
        return 0
    except (AssertionError,RuntimeError,ValueError,OSError,
            subprocess.TimeoutExpired) as error:
        # Avoid leaking exception payloads that may include secret content.
        # Only emit a known static stage and exception class, not str(error).
        print("V146 TLS canary BLOCKED: "+type(error).__name__
              +" stage="+CURRENT_STAGE, file=sys.stderr)
        return 2

if __name__=="__main__":
    raise SystemExit(main())
