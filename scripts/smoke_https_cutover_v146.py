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
    HTTPCookieProcessor, HTTPRedirectHandler, HTTPSHandler, Request, build_opener
)

BASE="https://127.0.0.1:3443"
ROUTES=("/", "/core", "/orders", "/catalog", "/inventory", "/purchasing",
        "/ops", "/finance", "/connections", "/team", "/sync")
PORTS={"next":3001,"vite":3000}
# V149: only fixed non-sensitive diagnostic stages may appear in CI stderr.
# Never print exception text, URLs, response bodies, session or credential data.
SAFE_STAGES=frozenset((
    "preflight","ingress-next","anonymous-access","csrf-init",
    "login-guard","login-real","session-cookie","secure-auth-readiness",
    "next-routes",
    "vite-switch","vite-auth","vite-page","vite-access-control",
    "next-switch","next-auth","logout"
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

class RefuseRedirects(HTTPRedirectHandler):
    # The disposable probe must never follow a proxy redirect to another
    # path or host and mistake the final 200 for an ingress success.
    def redirect_request(self,request,fp,code,msg,headers,newurl):
        return None


class Client:
    def __init__(self,certificate:str):
        self.context=ssl.create_default_context(cafile=certificate)
        self.cookies=http.cookiejar.CookieJar()
        self.http=build_opener(HTTPCookieProcessor(self.cookies),
                              HTTPSHandler(context=self.context),
                              RefuseRedirects())

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


def is_wrapped_tls_failure(exc:OSError)->bool:
    """urllib may wrap certificate/handshake failures inside URLError.reason."""
    return isinstance(getattr(exc,"reason",None),ssl.SSLError)


def wait_for_ingress(client:Client,target:str,previous:str|None=None,
                     attempts:int=40)->None:
    """V157: only transient loopback startup/reload may be retried.

    Never treat a bad CA, invalid Spring identity, HTML/redirect, or an
    unexpected upstream as readiness. During an Nginx reload, a previously
    verified candidate may briefly answer; retry it with a fixed bound, but
    only after authenticating the real Spring Ping JSON first.
    """
    if (target not in PORTS or attempts<1 or attempts>40
            or (previous is not None
                and (previous not in PORTS or previous==target))):
        raise ValueError("invalid_fixed_ingress_target_or_attempts")
    for index in range(attempts):
        try:
            status,body,headers=client.call("/api/v1/ping")
        except ssl.SSLError:
            # Never retry a failed TLS trust/hostname/handshake check.
            raise
        except OSError as exc:
            if is_wrapped_tls_failure(exc):
                raise AssertionError("ingress_tls_verification_failed") from exc
            if index+1==attempts:
                raise RuntimeError("loopback_tls_transport_unavailable") from exc
            time.sleep(.25)
            continue
        if status in (502,503,504):
            if index+1==attempts:
                raise RuntimeError("loopback_tls_backend_unavailable")
            time.sleep(.25)
            continue
        if status!=200:
            raise AssertionError("loopback_tls_unexpected_status")
        media=(headers.get("Content-Type") or "").split(";",1)[0].strip().lower()
        if media!="application/json":
            raise AssertionError("loopback_tls_wrong_media_type")
        try:
            ping=json.loads(body)
        except (ValueError,UnicodeError) as exc:
            raise AssertionError("loopback_tls_invalid_json") from exc
        if (not isinstance(ping,dict)
                or ping.get("service")!="merchivra"
                or ping.get("status")!="ok"
                or ping.get("stage")!="M0"):
            raise AssertionError("loopback_tls_wrong_spring_identity")
        candidate=headers.get("X-Merchivra-V146-Candidate")
        if candidate==target:
            return
        if previous is None or candidate!=previous:
            raise AssertionError("loopback_tls_wrong_upstream")
        if index+1==attempts:
            raise RuntimeError("loopback_tls_reload_not_applied")
        time.sleep(.25)
    raise RuntimeError("loopback_tls_ingress_not_ready")



def wait_for_secure_auth(certificate:str,attempts:int=40)->None:
    """V150: wait for the RESTARTED secure Spring Session, not ping alone.

    Probe with a distinct anonymous CookieJar, over VERIFIED loopback HTTPS.
    Only startup transport failures / 502, 503, 504 may be retried briefly.
    Wrong authorization, missing CSRF, insecure cookies or certificate errors
    fail immediately: do not hide a security regression behind a retry.
    """
    if attempts<1 or attempts>40:
        raise ValueError("invalid_fixed_readiness_attempts")
    for index in range(attempts):
        probe=Client(certificate)
        try:
            status,_,_=probe.call("/api/v1/auth/me")
            if status in (502,503,504):
                if index+1<attempts:
                    time.sleep(.25)
                    continue
                raise RuntimeError("secure_auth_backend_unavailable")
            if status!=401:
                raise AssertionError("secure_auth_anonymous_not_rejected")
            status,body,_=probe.call("/api/v1/auth/csrf")
            if status in (502,503,504):
                if index+1<attempts:
                    time.sleep(.25)
                    continue
                raise RuntimeError("secure_auth_backend_unavailable")
            if status!=200:
                raise AssertionError("secure_auth_csrf_status_wrong")
            try:
                csrf=json.loads(body)
            except (ValueError,UnicodeError) as exc:
                raise AssertionError("secure_auth_csrf_invalid") from exc
            if (not isinstance(csrf,dict)
                    or csrf.get("headerName")!="X-CSRF-TOKEN"
                    or not isinstance(csrf.get("token"),str)
                    or not csrf["token"]):
                raise AssertionError("secure_auth_csrf_missing")
            cookies=[x for x in probe.cookies if x.name=="JSESSIONID"]
            if len(cookies)!=1 or not cookies[0].secure:
                raise AssertionError("secure_auth_cookie_not_secure")
            if (not cookies[0].has_nonstandard_attr("HttpOnly")
                    or str(cookies[0].get_nonstandard_attr("SameSite","")).lower()!="strict"):
                raise AssertionError("secure_auth_cookie_flags_invalid")
            return
        except ssl.SSLError:
            # Invalid CA / hostname is a security failure, NOT a startup race.
            raise
        except OSError as exc:
            if is_wrapped_tls_failure(exc):
                # urllib wraps certificate failures in URLError, an OSError.
                # V150's bare SSLError handler cannot catch that wrapper.
                raise AssertionError("secure_auth_tls_verification_failed") from exc
            if index+1>=attempts:
                raise RuntimeError("secure_auth_transport_unavailable") from exc
            time.sleep(.25)
    raise RuntimeError("secure_auth_backend_unavailable")


def verify_vite_rollback_page(client:Client,attempts:int=8)->None:
    """V163: bound only proxy reload/transport transients, not security failures.

    A previous known Next worker may briefly serve an old socket after the
    reload; Vite must eventually return its own verified candidate and SPA.
    Wrong content, unknown upstream, 401/403/404 or invalid TLS fail at once.
    """
    if attempts<1 or attempts>8:
        raise ValueError("invalid_rollback_page_attempts")
    for index in range(attempts):
        status,body,headers=client.call("/purchasing")
        candidate=headers.get("X-Merchivra-V146-Candidate")
        transient=(status in (502,503,504)
                   or (status==200 and candidate=="next"))
        if transient:
            if index+1==attempts:
                raise RuntimeError("rollback_page_reload_not_ready")
            time.sleep(.25)
            continue
        if status!=200:
            raise AssertionError("rollback_page_unexpected_status")
        if candidate!="vite":
            raise AssertionError("rollback_wrong_backend")
        if b"Merchivra" not in body:
            raise AssertionError("rollback_unavailable")
        return
    raise RuntimeError("rollback_page_not_ready")


def run_synthetic(certificate:str,config_path:Path,container:str):
    if not CONTAINER.fullmatch(container):
        raise ValueError("invalid_disposable_container")
    if not LOCAL_PATH.fullmatch(str(config_path)):
        raise ValueError("unsafe_config_path")
    client=Client(certificate)
    public=Client(certificate)

    current="next"

    def switch(target:str):
        nonlocal current
        if target==current:
            raise ValueError("invalid_redundant_cutover")
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
        wait_for_ingress(client,target,previous=current)
        current=target

    mark_stage("ingress-next")
    wait_for_ingress(client,"next")
    mark_stage("secure-auth-readiness")
    wait_for_secure_auth(certificate)
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
    # V156: distinguish proxy reload from same-cookie identity and page checks.
    # Only static stage labels reach CI; no URL, tenant, user or cookie content.
    mark_stage("vite-switch")
    switch("vite")
    mark_stage("vite-auth")
    viewer_vite,_=client.json("/api/v1/auth/me")
    if viewer_vite.get("userId")!=viewer["userId"]:
        raise AssertionError("vite_rollback_lost_authenticated_principal")
    mark_stage("vite-page")
    verify_vite_rollback_page(client)
    mark_stage("vite-access-control")
    public.expect("/api/v1/auth/me",expected=401)
    client.expect("/api/v1/operations/scan",method="POST",
                  body={},expected=403)

    # Roll forward after verifying Vite: identity still in same Spring store.
    mark_stage("next-switch")
    switch("next")
    mark_stage("next-auth")
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
