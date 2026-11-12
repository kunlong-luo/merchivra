#!/usr/bin/env python3
"""V142 real Alertmanager -> isolated local webhook, NEVER external delivery.

Runs in disposable python:3.13-alpine on Docker --internal network. Only a
fixed fake alert name/status is persisted, with no original payload, labels
from real users, credentials or external URL.
"""
from datetime import datetime,timedelta,timezone
from http.server import BaseHTTPRequestHandler,HTTPServer
import json
import os
from pathlib import Path
import sys
from urllib.request import Request,urlopen

ALERTNAME="MerchivraV142SyntheticCritical"
STATUS="firing"
TARGET="http://v142-alert:9093"
SINK=Path("/deliveries/received.ndjson")

class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            length=int(self.headers.get("Content-Length","-1"))
            if self.path!="/alerts" or not 0<length<=65536:
                self.send_error(400)
                return
            source=json.loads(self.rfile.read(length))
            # Alertmanager v2 webhook payload: verify real grouping/receiver.
            if source.get("receiver")!="ci-local-webhook" or not isinstance(
                    source.get("alerts"),list):
                self.send_error(422)
                return
            matching=[a for a in source["alerts"]
                      if a.get("labels",{}).get("alertname")==ALERTNAME
                      and a.get("status")==STATUS]
            if not matching:
                self.send_error(422)
                return
            evidence={
                "source":"real_alertmanager_v142_local",
                "receiver":"ci-local-webhook",
                "alertname":ALERTNAME,
                "status":STATUS,
                "alertCount":len(matching),
                "externalRecipientVerified":False,
                "productionApproved":False
            }
            # No caller-supplied freeform text, tenant, URL, token, or PII.
            SINK.parent.mkdir(parents=True,exist_ok=True)
            with open(SINK,"a",encoding="utf-8") as file:
                file.write(json.dumps(evidence,separators=(",",":"))+"\n")
            self.send_response(204)
            self.end_headers()
        except (OSError,ValueError,TypeError,AttributeError,KeyError):
            self.send_error(422)

    def log_message(self,*_args):
        pass # no actual raw webhook data in container logs.

def ping():
    with urlopen(TARGET+"/-/ready",timeout=4) as response:
        if response.status!=200:
            raise RuntimeError("alertmanager_not_ready")

def send():
    now=datetime.now(timezone.utc)
    alert={
        "labels":{"alertname":ALERTNAME,"severity":"critical",
                  "instance":"v142-ci-disposable-only"},
        "annotations":{"summary":"Synthetic local V142 route test only"},
        "startsAt":(now-timedelta(seconds=5)).isoformat(),
        "endsAt":(now+timedelta(minutes=2)).isoformat()
    }
    request=Request(TARGET+"/api/v2/alerts",
         data=json.dumps([alert]).encode("utf-8"),
         headers={"Content-Type":"application/json"},
         method="POST")
    with urlopen(request,timeout=7) as response:
        if response.status not in (200,202):
            raise RuntimeError("alertmanager_did_not_accept_synthetic_alert")

def verify():
    if not SINK.is_file():
        raise RuntimeError("alertmanager_webhook_not_received")
    lines=SINK.read_text(encoding="utf-8").splitlines()
    if not 0<len(lines)<=50:
        raise RuntimeError("unexpected_webhook_receipt_count")
    for line in lines:
        item=json.loads(line)
        if (item.get("source")=="real_alertmanager_v142_local"
            and item.get("receiver")=="ci-local-webhook"
            and item.get("alertname")==ALERTNAME
            and item.get("status")==STATUS
            and item.get("externalRecipientVerified") is False
            and item.get("productionApproved") is False):
            print("PASS V142 real Alertmanager webhook: synthetic firing alert "
                  "routed to disposable private recipient, no external receiver")
            return
    raise RuntimeError("requested_alert_not_delivered")

if __name__=="__main__":
    if len(sys.argv)!=2:
        raise SystemExit("expected: serve | ping | send | verify")
    action=sys.argv[1]
    if action=="serve":
        HTTPServer(("0.0.0.0",8088),Receiver).serve_forever()
    elif action=="ping":
        ping()
    elif action=="send":
        send()
    elif action=="verify":
        verify()
    else:
        raise SystemExit("invalid_action")
