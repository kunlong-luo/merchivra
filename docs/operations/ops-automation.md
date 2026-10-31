# OPS-001 · Tenant-scoped operational automation (V10)

**Scope:** advisory-only work queue. Real tenant-scoped Core data may include both
`MOCK` and `UNVERIFIED` records. The engine does not trust either source for
automatic Amazon writes or financial posting. It never accesses Seller tokens,
buyer PII, addresses, inbound real API calls or shipping labels.

## Endpoints (authenticated OWNER Session)

- `GET /api/v1/operations/findings?filter=ACTIVE|ALL` — max 100 findings, filtered by *server-derived* tenant.
- `GET /api/v1/operations/summary` — total counts by OPEN, ACKNOWLEDGED, DONE and CLEARED.
- `POST /api/v1/operations/scan` — manual tenant scan, requires CSRF; returns run ID and match / cleared counts.
- `POST /api/v1/operations/findings/{uuid}/state` — JSON `{"state":"ACKNOWLEDGED"}`, `DONE`, or `OPEN` only, requires CSRF.
  Unknown/foreign id yields 404, already cleared yields 409, invalid state yields 400.

## Rules and truth

| Rule code | Condition | Severity |
|---|---|---|
| UNMAPPED_LISTING | Listing mapping_status UNMAPPED/CONFLICT | WARNING |
| INVENTORY_UNKNOWN | Latest SKU FBA observation UNKNOWN/STALE/PARTIAL, or fulfillable NULL | WARNING |
| LOW_STOCK | Latest SKU FBA observation exactly OBSERVED, non-NULL fulfillable <= threshold | CRITICAL |
| REFUND_REVIEW | Order refund_status PARTIAL or FULL | INFO |

The default low-stock threshold is **3**. `0` is a valid observed quantity; unknown is
**not** 0. No predicted restock date, estimated profit or Amazon refund
settlement is inferred. Source metadata remains MOCK vs UNVERIFIED.

A unique DB key `(tenant_id, seller_marketplace_id, rule_code, source_key)`
prevents duplicate findings; scans serialize on tenant row `FOR UPDATE`.
If the condition persists, ACKNOWLEDGED and DONE survive subsequent scans.
If the condition disappears, the finding moves to CLEARED. If it reappears,
CLEARED reopens as OPEN. Human changes are audited with actor ID and before/after
states; scans are audited as MANUAL or SCHEDULED. No raw credentials are logged.

## Configuration

```dotenv
MERCHIVRA_OPS_SCHEDULER_ENABLED=false
MERCHIVRA_OPS_SCAN_INTERVAL_MS=900000
MERCHIVRA_OPS_LOW_STOCK_THRESHOLD=3
```

Manual scanning is always available to OWNER regardless of the scheduler flag.
Enabling the scheduler runs the same transaction for active tenants, using no
Amazon network access. Set an interval suitable for DB volume; this first
iteration uses set-based full tenant scans without per-tenant rate quota.
Do not enable recurring scanning at large scale without measuring DB load.

## Testing and remaining gaps

Unit tests cover tenant scope, invalid transitions, idempotent DONE and
source/CSRF browser contract. The opt-in Compose mock chain additionally
runs `scripts/smoke_ops.py` after the existing persistent orders replay to
exercise actual PG + authenticated HTTP + CSRF and repeated scans. It must pass
in CI before calling this feature verified.

Not yet implemented: per-account rule customization, task assignment and SLA,
notification deliveries, background Amazon Orders worker, multi-role workforce,
live Seller role evaluation or real Amazon APIs. This is an **operations
triage queue**, not a complete peer ERP replacement.
