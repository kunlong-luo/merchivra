#!/usr/bin/env bash
# V146: synthetic local CA + actual Nginx TLS -> Next + Vite rollback + Spring.
# Absolutely no production host, custom public address or real secrets.
set -Eeuo pipefail
# Unset secrets must refuse cleanly under bash -u rather than expand an
# unset length before reaching the explicit CI-only safety check.
synthetic_password="${MERCHIVRA_BOOTSTRAP_PASSWORD:-}"
if [[ "${GITHUB_ACTIONS:-}" != true ||
      "${MERCHIVRA_V146_DISPOSABLE_ONLY:-}" != true ||
      "${MERCHIVRA_SPAPI_ENABLED:-}" != false ||
      "${MERCHIVRA_SPAPI_OAUTH_ENABLED:-}" != false ||
      "${MERCHIVRA_SPAPI_ALLOW_WRITES:-}" != false ||
      "${MERCHIVRA_BOOTSTRAP_USERNAME:-}" != local-smoke-admin ||
      ${#synthetic_password} -lt 16 ]]; then
  echo 'V146 HTTPS smoke REFUSED: disposable CI synthetic identity required' >&2
  exit 2
fi
for command in docker openssl python3 mktemp; do
  command -v "$command" >/dev/null || {
    echo 'V146 required local test dependency unavailable' >&2
    exit 2
  }
done
# Guaranteed to bind ONLY 127.0.0.1, never an Internet-facing service.
dir="$(mktemp -d /tmp/merchivra-v146-XXXXXXXX)"
container="v146-https-$$"
cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf -- "$dir"
}
trap cleanup EXIT
umask 077
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj '/CN=localhost' \
  -addext 'subjectAltName=IP:127.0.0.1,DNS:localhost' \
  -keyout "$dir/key.pem" -out "$dir/cert.pem" >/dev/null 2>&1
test -s "$dir/key.pem"
test -s "$dir/cert.pem"
python3 scripts/smoke_https_cutover_v146.py initial-config \
  --config "$dir/nginx.conf"
# Override the stock image entrypoint config with an isolated, restricted
# one: only one 127.0.0.1 TLS listener, NO port 80/default public listener.
docker run -d --name "$container" --network host \
  -v "$dir:/v146:ro" \
  nginx:alpine nginx -c /v146/nginx.conf -g 'daemon off;' >/dev/null
# Confirm actual process listen config parses before making HTTPS requests.
docker exec "$container" nginx -t -c /v146/nginx.conf >/dev/null 2>&1 || {
  echo 'V146 Nginx localhost-only TLS configuration invalid' >&2
  exit 1
}
python3 scripts/smoke_https_cutover_v146.py verify \
  --config "$dir/nginx.conf" --cert "$dir/cert.pem" \
  --container "$container"
