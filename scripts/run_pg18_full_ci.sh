#!/usr/bin/env bash
# V251: extracted from CI's original PG18 run scalar; preserve all test commands.
# CI-only disposable DB; never invoke against a local or production service.
set -euo pipefail
for key in GITHUB_ACTIONS GITHUB_REPOSITORY GITHUB_REF DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD PGPASSWORD MERCHIVRA_SPAPI_ENABLED MERCHIVRA_SPAPI_OAUTH_ENABLED MERCHIVRA_BOOTSTRAP_USERNAME MERCHIVRA_BOOTSTRAP_PASSWORD; do
  case "$key" in
    GITHUB_ACTIONS) expected=true ;;
    GITHUB_REPOSITORY) expected=kunlong-luo/merchivra ;;
    GITHUB_REF) expected=refs/heads/main ;;
    DB_HOST) expected=127.0.0.1 ;;
    DB_PORT) expected=5432 ;;
    DB_NAME|DB_USER) expected=merchivra ;;
    DB_PASSWORD|PGPASSWORD) expected=ci_disposable_database_password ;;
    MERCHIVRA_SPAPI_ENABLED|MERCHIVRA_SPAPI_OAUTH_ENABLED) expected=false ;;
    MERCHIVRA_BOOTSTRAP_USERNAME) expected=v65-ci-owner ;;
    MERCHIVRA_BOOTSTRAP_PASSWORD) expected=ci-disposable-v65-only-secret-0000 ;;
  esac
  if [[ "${!key-}" != "$expected" ]]; then
    printf 'PG18 full CI requires exact disposable environment: %s\n' "$key" >&2
    exit 1
  fi
done
[[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]] || exit 1
[[ "${PG18_SERVICE_CONTAINER_ID:-}" =~ ^[0-9a-f]{64}$ ]] || { echo "Missing disposable PG18 service container ID" >&2; exit 1; }
# Resolve repository root, independent of the caller's working directory.
cd "$(dirname "$0")/.."

set -euo pipefail
./apps/server/gradlew -p apps/server --no-daemon --console plain bootRun \
  > /tmp/merchivra-flyway.log 2>&1 &
app_pid=$!
trap 'kill "$app_pid" 2>/dev/null || true' EXIT
ready=0
for i in $(seq 1 150); do
  if curl -fsS http://127.0.0.1:8080/api/v1/ping >/dev/null 2>&1; then
    ready=1
    break
  fi
  if ! kill -0 "$app_pid" 2>/dev/null; then
    echo "Spring Boot startup process exited before health check"
    tail -150 /tmp/merchivra-flyway.log
    exit 1
  fi
  sleep 2
done
if [ "$ready" != 1 ]; then
  echo "Spring Boot / Flyway failed to become ready"
  tail -150 /tmp/merchivra-flyway.log
  exit 1
fi
major=$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SHOW server_version_num")
test "${major:0:2}" = "18"
migration=$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc \
  "SELECT count(*) FROM flyway_schema_history WHERE version IN ('46','47','48','49','50','51','52') AND success")
test "$migration" = "7"
psql -h 127.0.0.1 -U merchivra -d merchivra -v ON_ERROR_STOP=1 -Atc \
  "SELECT count(*) FROM erp_local_lot_disposition_proof_file WHERE size_bytes > 2097152"
psql -h 127.0.0.1 -U merchivra -d merchivra -v ON_ERROR_STOP=1 -Atc \
  "SELECT count(*) FROM erp_lot_supplier_return_trace"
pgcount() { psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "$1"; }
for version in 84 85 86 87 88 89 90; do
  test "$(pgcount "SELECT count(*) FROM flyway_schema_history WHERE version='$version' AND success")" = 1
done
for index in \
  fba_purchase_link_history_page_v90 \
  erp_purchase_order_candidate_page_v90 \
  erp_purchase_asn_candidate_page_v90 \
  erp_lot_proof_capacity_threshold_audit_recent_idx \
  erp_lot_proof_gov_audit_recent_idx \
  erp_local_lot_position_expiry_page_idx \
  erp_local_lot_position_warehouse_recent_idx \
  erp_local_lot_position_receipt_root_idx \
  erp_local_lot_action_destination_recent_idx; do
  test "$(pgcount "SELECT count(*) FROM pg_indexes WHERE indexname='$index'")" = 1
done
test "$(pgcount "SELECT count(*) FROM pg_constraint WHERE conname='erp_lot_proof_file_tenant_proof_id_unique'")" = 1
psql -h 127.0.0.1 -U merchivra -d merchivra -v ON_ERROR_STOP=1 -Atc "SELECT count(*) FROM erp_local_lot_proof_file_download_audit"
curl -fsS http://127.0.0.1:8080/v3/api-docs/finance-profits > /tmp/merchivra-live-finance-openapi.json
node scripts/normalize-finance-openapi.mjs /tmp/merchivra-live-finance-openapi.json /tmp/merchivra-normalized-finance-openapi.json
cmp apps/web/openapi/finance-profits.json /tmp/merchivra-normalized-finance-openapi.json
curl -fsS http://127.0.0.1:8080/v3/api-docs/fba-inbound > /tmp/merchivra-live-fba-openapi.json
node scripts/normalize-fba-openapi.mjs /tmp/merchivra-live-fba-openapi.json /tmp/merchivra-normalized-fba-openapi.json
diff -u apps/web/openapi/fba-inbound.json /tmp/merchivra-normalized-fba-openapi.json || true
cmp apps/web/openapi/fba-inbound.json /tmp/merchivra-normalized-fba-openapi.json
cp /tmp/merchivra-normalized-fba-openapi.json apps/web/openapi/fba-inbound.json
(
  cd apps/web
  pnpm install --frozen-lockfile
  pnpm run api:generate:fba
)
test -s apps/web/src/generated/fba-inbound.ts
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='63' AND success")" = "1"
curl -fsS http://127.0.0.1:8080/v3/api-docs/ads-reports > /tmp/ads-live.json
node scripts/normalize-ads-openapi.mjs /tmp/ads-live.json /tmp/ads-verified.json
cmp apps/web/openapi/ads-reports.json /tmp/ads-verified.json
cp /tmp/ads-verified.json apps/web/openapi/ads-reports.json
(
  cd apps/web
  pnpm run api:generate:ads
)
test -s apps/web/src/generated/ads-reports.ts
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT count(*) FROM ads_report_reviewed_cost")" = "0"
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT count(*) FROM ads_profile_reference")" = "0"
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='64' AND success")" = "1"
curl -fsS http://127.0.0.1:8080/v3/api-docs/operations-v67 > /tmp/ops-live.json
node scripts/normalize-ops-openapi.mjs /tmp/ops-live.json /tmp/ops-normalized.json
cmp apps/web/openapi/operations-v67.json /tmp/ops-normalized.json
cp /tmp/ops-normalized.json apps/web/openapi/operations-v67.json
(
  cd apps/web
  pnpm run api:generate:ops
)
test -s apps/web/src/generated/operations-v67.ts
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='65' AND success")" = "1"
python3 scripts/smoke_fba_inbound.py
psql -h 127.0.0.1 -U merchivra -d merchivra -v ON_ERROR_STOP=1 -f scripts/smoke_ads_schema.sql
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='66' AND success")" = "1"
python3 scripts/smoke_ops_v67.py
python3 scripts/smoke_ops_v69.py
python3 scripts/smoke_replenishment_v70.py
python3 scripts/smoke_replenishment_supplier_v71.py
python3 scripts/smoke_purchase_delivery_v72.py
python3 scripts/smoke_supplier_performance_v73.py
python3 scripts/smoke_replenishment_priority_v74.py
python3 scripts/smoke_fba_purchase_links_v75.py
python3 scripts/smoke_fba_purchase_reconciliation_v76.py
python3 scripts/smoke_fba_purchase_exceptions_v77.py
python3 scripts/smoke_fba_purchase_ops_v78.py
python3 scripts/smoke_fba_purchase_assignment_v79.py
python3 scripts/smoke_exception_insights_v80.py
python3 scripts/smoke_exception_history_v81.py
python3 scripts/smoke_exception_workflow_v82.py
python3 scripts/smoke_fba_exception_preflight_v83.py
python3 scripts/smoke_fba_owner_audit_v84.py
python3 scripts/smoke_fba_ops_capability_v85.py
python3 scripts/smoke_core_orders_pagination_v86.py
python3 scripts/smoke_core_listings_pagination_v87.py
python3 scripts/smoke_core_fba_inventory_page_v88.py
python3 scripts/smoke_business_source_quality_v89.py
python3 scripts/smoke_cross_module_full_store_v90.py
python3 scripts/smoke_fba_catalog_purchasing_full_selection_v91.py
python3 scripts/smoke_fba_local_history_supplier_sku_v92.py
python3 scripts/smoke_paged_local_purchasing_v92.py
python3 scripts/smoke_purchase_orders_page_v110.py
python3 scripts/smoke_supplier_pages_v111.py
python3 scripts/smoke_warehouse_ledger_pages_v112.py
python3 scripts/smoke_warehouse_read_pages_v113.py
python3 scripts/smoke_warehouse_directory_v114.py
python3 scripts/smoke_receipt_operational_pages_v93.py
python3 scripts/smoke_quality_returns_findings_v94.py
python3 scripts/smoke_ops_lot_pagination_v95.py
python3 scripts/smoke_lot_history_v132.py
python3 scripts/smoke_released_lot_positions_v133.py
python3 scripts/smoke_lot_expiry_alerts_v134.py
MERCHIVRA_V135_DISPOSABLE_ONLY=true python3 scripts/check_lot_query_plans_v135.py
python3 scripts/smoke_proof_governance_v138.py
python3 scripts/smoke_proof_monitor_v140.py
python3 scripts/smoke_supplier_case_pages_v96.py
python3 scripts/smoke_supplier_finance_settlement_pages_v97.py
python3 scripts/smoke_finance_audit_pages_v98.py
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT count(*) FROM flyway_schema_history WHERE version='91' AND success")" = "1"
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT count(*) FROM flyway_schema_history WHERE version='92' AND success")" = "1"
MERCHIVRA_V164_DISPOSABLE_ONLY=true python3 scripts/smoke_cost_allocation_v164.py
MERCHIVRA_V192_DISPOSABLE_ONLY=true python3 scripts/smoke_v192_profit_sku_conservation.py
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT count(*) FROM flyway_schema_history WHERE version='103' AND success")" = "1"
psql -h 127.0.0.1 -U merchivra -d merchivra -v ON_ERROR_STOP=1 \
  -f scripts/smoke_profit_snapshot_guards_v193.sql
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT count(*) FROM flyway_schema_history WHERE version='104' AND success")" = "1"
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT count(*) FROM flyway_schema_history WHERE version='105' AND success")" = "1"
psql -h 127.0.0.1 -U merchivra -d merchivra -v ON_ERROR_STOP=1 \
  -f scripts/smoke_profit_capture_v194.sql
MERCHIVRA_V198_DISPOSABLE_ONLY=true python3 scripts/smoke_settlement_source_identity_v198.py
MERCHIVRA_V200_DISPOSABLE_ONLY=true python3 scripts/smoke_order_cost_ambiguity_v200.py
MERCHIVRA_V201_DISPOSABLE_ONLY=true python3 scripts/smoke_refund_cross_event_quantity_v201.py
MERCHIVRA_V202_DISPOSABLE_ONLY=true python3 scripts/smoke_local_lot_cost_provenance_v202.py
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT count(*) FROM flyway_schema_history WHERE version='106' AND success")" = "1"
MERCHIVRA_V203_DISPOSABLE_ONLY=true python3 scripts/smoke_sale_lot_assignment_v203.py
MERCHIVRA_V248_DISPOSABLE_ONLY=true python3 scripts/smoke_sale_lot_range_race_v248.py
MERCHIVRA_V204_DISPOSABLE_ONLY=true python3 scripts/smoke_signed_sale_dispatch_v204.py
MERCHIVRA_V205_DISPOSABLE_ONLY=true python3 scripts/smoke_dispatch_custody_dossier_v205.py
MERCHIVRA_V206_DISPOSABLE_ONLY=true python3 scripts/smoke_custody_original_content_v206.py
MERCHIVRA_V207_DISPOSABLE_ONLY=true python3 scripts/smoke_custody_original_governance_v207.py
MERCHIVRA_V208_DISPOSABLE_ONLY=true python3 scripts/smoke_custody_claimed_issuer_signatures_v208.py
MERCHIVRA_V210_DISPOSABLE_ONLY=true python3 scripts/smoke_refund_order_conservation_v210.py
MERCHIVRA_V211_DISPOSABLE_ONLY=true python3 scripts/smoke_period_refund_profit_guard_v211.py
MERCHIVRA_V212_DISPOSABLE_ONLY=true python3 scripts/smoke_refund_batch_lineage_v212.py
MERCHIVRA_V213_DISPOSABLE_ONLY=true python3 scripts/smoke_refund_global_receipt_capacity_v213.py
MERCHIVRA_V215_DISPOSABLE_ONLY=true python3 scripts/smoke_receipt_cost_allocation_v215.py
MERCHIVRA_V216_DISPOSABLE_ONLY=true python3 scripts/smoke_receipt_cost_integrity_v216.py
v212_order_base=http://127.0.0.1:8080/api/v1/finance/marketplaces/00000000-0000-4000-8000-000000000204/orders/00000000-0000-4000-8000-000000000202
v212_anon=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  ${v212_order_base}/refund-batch-lineage-readiness)
test "$v212_anon" = "401"
v213_anon=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  ${v212_order_base}/refund-original-receipt-capacity)
test "$v213_anon" = "401"
v211_profit_guard_anon=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  http://127.0.0.1:8080/api/v1/finance/marketplaces/00000000-0000-4000-8000-000000000204/period-refund-profit-guard/00000000-0000-4000-8000-000000000202)
test "$v211_profit_guard_anon" = "401"
issuer_operator_trust_anon=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  ${v212_order_base}/custody-external-trust-readiness)
test "$issuer_operator_trust_anon" = "401"
issuer_anon=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  http://127.0.0.1:8080/api/v1/finance/custody-issuer-keys)
test "$issuer_anon" = "401"
issuer_post_anon=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST \
  http://127.0.0.1:8080/api/v1/finance/custody-issuer-keys)
test "$issuer_post_anon" = "401"
issuer_preview_anon=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  ${v212_order_base}/custody-issuer-integrity)
test "$issuer_preview_anon" = "401"
custody_governance_anon=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  http://127.0.0.1:8080/api/v1/finance/custody-originals/capacity)
test "$custody_governance_anon" = "401"
custody_governance_post_anon=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST \
  ${v212_order_base}/custody-dossiers/00000000-0000-4000-8000-000000000205/originals/OPERATOR_REGISTRY/retention-proposals)
test "$custody_governance_post_anon" = "401"
custody_original_anon=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  ${v212_order_base}/custody-original-integrity)
test "$custody_original_anon" = "401"
custody_original_upload_anon=$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST \
  ${v212_order_base}/custody-dossiers/00000000-0000-4000-8000-000000000205/originals/OPERATOR_REGISTRY)
test "$custody_original_upload_anon" = "401"
custody_anon_code=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  ${v212_order_base}/custody-readiness)
test "$custody_anon_code" = "401"
sale_lot_anon_code=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  ${v212_order_base}/sale-lot-integrity)
test "$sale_lot_anon_code" = "401"
lot_anon_code=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  ${v212_order_base}/lot-provenance)
test "$lot_anon_code" = "401"
capture_anon_code=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  http://127.0.0.1:8080/api/v1/finance/marketplaces/00000000-0000-4000-8000-000000000204/capture-verification/00000000-0000-4000-8000-000000000202)
test "$capture_anon_code" = "401"
MERCHIVRA_V165_DISPOSABLE_ONLY=true python3 scripts/smoke_cost_identity_v165.py
MERCHIVRA_V175_DISPOSABLE_ONLY=true python3 scripts/smoke_wms_qc_v175.py
MERCHIVRA_V175B_DISPOSABLE_ONLY=true python3 scripts/smoke_wms_key_possession_v175b.py
psql -h 127.0.0.1 -U merchivra -d merchivra -v ON_ERROR_STOP=1 \
  -f scripts/smoke_wms_qc_actor_pg18_v175.sql
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT count(*) FROM flyway_schema_history WHERE version='81' AND success")" = "1"
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT count(*) FROM flyway_schema_history WHERE version='80' AND success")" = "1"
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT count(*) FROM flyway_schema_history WHERE version='79' AND success")" = "1"
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='78' AND success")" = "1"
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='77' AND success")" = "1"
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='76' AND success")" = "1"
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='75' AND success")" = "1"
test "$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='74' AND success")" = "1"
v75_migration=$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='73' AND success")
test "$v75_migration" = "1"
v74_migration=$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='71' AND success")
test "$v74_migration" = "1"
v74_origin_migration=$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='72' AND success")
test "$v74_origin_migration" = "1"
v73_migration=$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='70' AND success")
test "$v73_migration" = "1"
v72_migration=$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='69' AND success")
test "$v72_migration" = "1"
v71_migration=$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='68' AND success")
test "$v71_migration" = "1"
v70_migration=$(psql -h 127.0.0.1 -U merchivra -d merchivra -Atc "SELECT COUNT(*) FROM flyway_schema_history WHERE version='67' AND success")
test "$v70_migration" = "1"
MERCHIVRA_V68_DISPOSABLE_ONLY=true python3 scripts/smoke_http_security_v68.py
(
  cd apps/web
  MERCHIVRA_API_INTERNAL_URL=http://127.0.0.1:8080 pnpm run build:next
  MERCHIVRA_API_INTERNAL_URL=http://127.0.0.1:8080 \
    ./node_modules/.bin/next start --hostname 127.0.0.1 --port 3355 \
    >/tmp/merchivra-next-v101-session.log 2>&1 &
  next_pid=$!
  trap 'kill "$next_pid" 2>/dev/null || true' EXIT
  next_ready=0
  for i in $(seq 1 40); do
    if curl -fsS http://127.0.0.1:3355/ >/dev/null 2>&1; then
      next_ready=1
      break
    fi
    if ! kill -0 "$next_pid" 2>/dev/null; then
      tail -100 /tmp/merchivra-next-v101-session.log
      exit 1
    fi
    sleep 1
  done
  test "$next_ready" = 1
  MERCHIVRA_V101_DISPOSABLE_ONLY=true python3 \
    ../../scripts/smoke_next_session_csrf_v101.py
)
v141_attest="$(mktemp /tmp/merchivra-v141-attest-XXXXXX.json)"
v141_disk="$(mktemp /tmp/merchivra-v141-disk-XXXXXX.json)"
MERCHIVRA_V141_ATTESTATION_FILE="$v141_attest" \
  MERCHIVRA_V68_DISPOSABLE_ONLY=true bash scripts/smoke_pg_restore_v68.sh
docker exec -i "$PG18_SERVICE_CONTAINER_ID" sh -s \
  < scripts/collect_pg18_disk_v141.sh > "$v141_disk"
python3 scripts/pg18_ops_probe_v141.py --mode disposable \
  --disk-json "$v141_disk" --backup-json "$v141_attest" \
  --disk-warn-percent 20 --disk-critical-percent 10 \
  --wal-warn-bytes 536870912 --wal-critical-bytes 2147483648 \
  --backup-max-age-hours 24 --restore-max-age-hours 24 \
  --prometheus-file /tmp/merchivra-v141-pg18.prom \
  > /tmp/merchivra-v141-ops.json
python3 - <<'PY'
import json
from pathlib import Path
r=json.loads(Path('/tmp/merchivra-v141-ops.json').read_text())
assert r['mode']=='disposable' and r['productionApproved'] is False
assert r['alertReceiverVerified'] is False
assert r['measurements']['databaseBytes']>0
assert r['measurements']['walDirectoryBytes']>0
assert r['measurements']['filesystemAvailableBytes']>0
assert any(c['code']=='PITR_WAL_ARCHIVING' and c['status']=='BLOCKED'
           for c in r['checks']), 'Disposable pg_dump is NOT PITR'
metrics=Path('/tmp/merchivra-v141-pg18.prom').read_text()
assert 'merchivra_pg18_ops_production_approved 0' in metrics
print('PASS V141 disposable local PG18 ops: measured disk/WAL, verified restore, never production approval')
PY
rm -f -- "$v141_attest" "$v141_disk"
v142_pitr="$(mktemp /tmp/merchivra-v142-pitr-XXXXXX.json)"
MERCHIVRA_V142_DISPOSABLE_ONLY=true \
  MERCHIVRA_V142_PITR_EVIDENCE_FILE="$v142_pitr" \
  bash scripts/smoke_pg18_pitr_v142.sh
v142_alert="$(mktemp /tmp/merchivra-v142-alert-XXXXXX.json)"
MERCHIVRA_V142_DISPOSABLE_ONLY=true \
  MERCHIVRA_V142_ALERT_EVIDENCE_FILE="$v142_alert" \
  bash scripts/smoke_alertmanager_v142.sh
python3 - "$v142_pitr" "$v142_alert" <<'PY'
import json,sys
from pathlib import Path
p=json.loads(Path(sys.argv[1]).read_text())
a=json.loads(Path(sys.argv[2]).read_text())
assert p['mode']=='disposable' and p['postgresMajor']==18
assert all(p[k] for k in ('archivedWalSegment','physicalBasebackup',
    'recoveryTargetPaused','includedCommittedBeforeTarget',
    'excludedCommittedAfterTarget'))
assert p['productionPitrApproved'] is False
assert a['source']=='real_alertmanager_v142_local'
assert a['status']=='firing' and a['receiver']=='ci-local-webhook'
assert a['externalRecipientVerified'] is False
assert a['productionApproved'] is False
print('PASS V142 A3: real local PG18 timestamp PITR + internal Alertmanager delivery; production blocked')
PY
MERCHIVRA_V219_DISPOSABLE_ONLY=true python3 scripts/smoke_receipt_cost_real_http_v219.py
MERCHIVRA_V220_DISPOSABLE_ONLY=true python3 scripts/smoke_receipt_cost_actual_http_v220.py
MERCHIVRA_V221_DISPOSABLE_ONLY=true python3 scripts/smoke_receipt_read_epoch_v221.py
python3 scripts/smoke_receipt_http_aba_v222.py
MERCHIVRA_V223_DISPOSABLE_ONLY=true python3 scripts/smoke_receipt_epoch_contention_v223.py
MERCHIVRA_V224_DISPOSABLE_ONLY=true python3 scripts/smoke_receipt_epoch_noop_v224.py
MERCHIVRA_V225_DISPOSABLE_ONLY=true MERCHIVRA_V224_DISPOSABLE_ONLY=true python3 scripts/smoke_receipt_epoch_bulk_v225.py
MERCHIVRA_V226_DISPOSABLE_ONLY=true MERCHIVRA_V225_DISPOSABLE_ONLY=true python3 scripts/smoke_cost_source_batch_real_http_v226.py
MERCHIVRA_V227_DISPOSABLE_ONLY=true MERCHIVRA_V226_DISPOSABLE_ONLY=true MERCHIVRA_V225_DISPOSABLE_ONLY=true python3 scripts/smoke_cost_source_batch_scale_v227.py
python3 scripts/check_manual_cost_openapi_v228.py
MERCHIVRA_V229_DISPOSABLE_ONLY=true MERCHIVRA_V226_DISPOSABLE_ONLY=true MERCHIVRA_V225_DISPOSABLE_ONLY=true python3 scripts/smoke_cost_source_audit_integrity_v229.py
MERCHIVRA_V230_DISPOSABLE_ONLY=true MERCHIVRA_V229_DISPOSABLE_ONLY=true MERCHIVRA_V226_DISPOSABLE_ONLY=true MERCHIVRA_V225_DISPOSABLE_ONLY=true python3 scripts/smoke_external_cost_proof_dossier_v230.py
MERCHIVRA_V234_DISPOSABLE_ONLY=true python3 scripts/smoke_reports_auth_revision_v234.py
MERCHIVRA_V236_DISPOSABLE_ONLY=true MERCHIVRA_V250_DISPOSABLE_ONLY=true ./apps/server/gradlew -p apps/server --no-daemon --console plain test --tests 'org.merchivra.erp.finance.SettlementReportScanServiceTest.actualPg18OwnerRestartAndFencedImportRejectStaleAuthorization' --tests 'org.merchivra.erp.finance.ReceiptCostCompositionPg18V250Test.actualPg18ReceiptCostsAndSaleRangesConserveAmounts'
python3 scripts/check_reports_service_junit.py
echo "PostgreSQL 18 / Flyway / actual finance + FBA OpenAPI and local mock chain verified"
kill "$app_pid" 2>/dev/null || true
