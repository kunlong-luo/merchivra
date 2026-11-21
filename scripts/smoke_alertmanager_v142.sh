#!/usr/bin/env bash
# V142 real Alertmanager v2 alert delivery only to an isolated Docker-local
# Python webhook receiver. No external network, published ports or credentials.
set -Eeuo pipefail
if [[ "${GITHUB_ACTIONS:-}" != true ||
      "${MERCHIVRA_V142_DISPOSABLE_ONLY:-}" != true ||
      "${DB_HOST:-}" != 127.0.0.1 ||
      "${DB_NAME:-}" != merchivra ||
      "${DB_PASSWORD:-}" != ci_disposable_database_password ||
      "${MERCHIVRA_DEMO_ENABLED:-}" != true ||
      "${MERCHIVRA_SPAPI_ENABLED:-}" != false ||
      "${MERCHIVRA_SPAPI_OAUTH_ENABLED:-}" != false ||
      "${MERCHIVRA_SPAPI_ALLOW_WRITES:-false}" != false ]]; then
  echo 'V142 real Alertmanager harness REFUSED outside synthetic CI' >&2
  exit 2
fi
dir="$(mktemp -d /tmp/merchivra-v142-alert-XXXXXXXX)"
net="v142-alert-net-$$"
receiver="v142-receiver-$$"
manager="v142-alertmanager-$$"
cleanup() {
  docker rm -f -v "$manager" "$receiver" >/dev/null 2>&1 || true
  docker network rm "$net" >/dev/null 2>&1 || true
  rm -rf -- "$dir"
}
trap cleanup EXIT
mkdir -p "$dir/deliveries"
cat > "$dir/alertmanager.yml" <<'YAML'
global:
  resolve_timeout: 10s
route:
  receiver: ci-local-webhook
  group_by: ['alertname']
  group_wait: 0s
  group_interval: 2s
  repeat_interval: 1m
receivers:
  - name: ci-local-webhook
    webhook_configs:
      - url: http://v142-receiver:8088/alerts
        send_resolved: false
YAML
# Docker's --internal network disallows egress and neither service publishes
# a host port. The receiver persistently stores only six fixed synthetic fields.
docker network create --internal "$net" >/dev/null
docker run -d --name "$receiver" --network "$net" \
  --network-alias v142-receiver \
  -v "$PWD/scripts/v142_alert_loopback.py:/app/loopback.py:ro" \
  -v "$dir/deliveries:/deliveries" \
  python:3.13-alpine python /app/loopback.py serve > /dev/null
docker run -d --name "$manager" --network "$net" \
  --network-alias v142-alert \
  -v "$dir/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  prom/alertmanager:v0.28.1 \
  --config.file=/etc/alertmanager/alertmanager.yml \
  --web.listen-address=:9093 > /dev/null
ready=false
for _ in $(seq 1 50); do
  if docker exec "$receiver" python /app/loopback.py ping >/dev/null 2>&1; then
    ready=true; break
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  echo 'V142 private Alertmanager not ready' >&2
  docker logs "$manager" 2>&1 | tail -65 >&2 || true
  exit 1
fi
# Verify a real Alertmanager v2 API accepted the synthetic alert. A 2xx API
# response alone is NOT delivery; receipt must be observed from the webhook.
docker exec "$receiver" python /app/loopback.py send
delivered=false
for _ in $(seq 1 55); do
  if docker exec "$receiver" python /app/loopback.py verify >/dev/null 2>&1; then
    delivered=true; break
  fi
  sleep 1
done
if [[ "$delivered" != true ]]; then
  echo 'V142 synthetic Alertmanager webhook not delivered' >&2
  docker logs "$manager" 2>&1 | tail -85 >&2 || true
  exit 1
fi
docker exec "$receiver" python /app/loopback.py verify
if [[ -n "${MERCHIVRA_V142_ALERT_EVIDENCE_FILE:-}" ]]; then
  case "$MERCHIVRA_V142_ALERT_EVIDENCE_FILE" in
    /tmp/merchivra-v142-alert-*.json) ;;
    *) echo 'Unsafe V142 webhook evidence path' >&2; exit 2 ;;
  esac
  # Only sanitized local receipt, no raw API payload or customer fields.
  umask 077
  docker exec "$receiver" python -c \
    'import json; p="/deliveries/received.ndjson"; v=json.loads(open(p).readline()); print(json.dumps(v,separators=(",",":")))' \
    > "$MERCHIVRA_V142_ALERT_EVIDENCE_FILE"
fi
echo 'PASS V142 real Alertmanager -> local webhook: accepted synthetic critical and verified receiver delivery; no external/on-call receiver or production approval'
