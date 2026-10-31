# V11A · Durable Orders incremental Worker (safe synthetic first)

Status: **code implemented; full CI and Compose evidence is required before marking verified**.

## Scope and Amazon compatibility

Amazon's Orders API v2026-01-01 uses `searchOrders`, `lastUpdatedAfter`,
`lastUpdatedBefore`, `paginationToken`; the pagination token expires after
24 hours. Avoid `includedData=BUYER` / `RECIPIENT` in initial sync to avoid
unnecessary buyer PII. The existing `SpApiReadOperations.searchOrders` and
`SpApiPayloadDecoder.orders2026` are the API/DTO integration seam.

References:
- https://developer-docs.amazon.com/sp-api/docs/orders-api
- https://developer-docs.amazon.com/sp-api/lang-us/docs/orders-api-migration-guide
- https://developer-docs.amazon.com/sp-api/reference/searchorders

**V11A does not yet call Amazon.** It introduces a reliable worker lifecycle
with a strict fixture-only implementation. It will refuse to start if live
SP-API egress is enabled, if Demo is off, or if the account is anything other
than a current-tenant LOCAL/UNCONFIGURED account. It must NOT be repurposed
to process a real verified grant until an independently checked Orders role,
Seller identity consistency and token revocation workflow exist. Merely
having the official SDK installed is not evidence of permission.

## Implemented

- Flyway V11: `orders_sync_job` (one active cursor per tenant + marketplace,
  immutable window during paging), per-page `orders_sync_page_commit`
  with generation for token restart; `sales_order` source schema additionally
  allows `LIVE` for future role-verified reads, but V11A only writes `MOCK`.
- Backend: `GET /api/v1/orders-sync/{mp}`, `POST .../start`, `POST .../step`.
  All derive tenant from Session; POST requires CSRF. No cursor, LWA token,
  arbitrary time window, Seller ID or tenant ID accepted from browser.
- Start initial window: 24h ending 2min before now; after full window
  success, next start overlaps its watermark by 5min. A completed window
  **alone** advances the safe watermark.
- Step: transactionally claim tenant-scoped lease with UUID owner + incremented
  fencing epoch; source fetch occurs outside transaction; then recheck local
  Seller scope and atomically upsert synthetic orders, journal page and advance
  cursor. Stale worker cannot commit after another claim.
- Token: if a pending token is older than 20h, restart the **same window**
  from page 0 with a new generation. Upsert keys prevent duplicates.
- Errors: safe code only, 429/502/503/504/500 -> delayed retry with bounded
  exponential delay; 401/403 and unexpected errors -> PAUSED; 400 for pending
  cursor -> restart window. No raw Amazon response bodies or secrets in logs
  or API responses.
- Optional scheduler: one due page per job, bounded batch (10), disabled by
  default. Mock fixture can page immediately; production operation-specific
  default pacing would conservatively wait at least 180s between requests.
- Core UI: under the optional local test tools disclosure, show the
  Mock-only worker status, safe watermark and manual one-page controls.
  Source authorization and real worker are not exposed in navigation.

```dotenv
MERCHIVRA_ORDERS_SYNC_ENABLED=false
MERCHIVRA_ORDERS_SYNC_SCHEDULER_ENABLED=false
MERCHIVRA_ORDERS_SYNC_SCHEDULER_MS=30000
```

Only disposable local Demo environments may set the first flag to true; in
V11A production environments the setting must remain false. Never enable
`MERCHIVRA_SPAPI_ENABLED` alongside this fixture source.

## Test contract

`scripts/smoke_orders_sync.py` runs after the existing Mock full-chain in
PostgreSQL17+Java25+Nginx Compose. Exercises: 401 unauth, POST without CSRF 403,
OWNER start, idempotent start, page 1 no watermark, page 2 complete with
watermark, repeated step denied, no extra orders, new overlapping window,
foreign marketplace 404 and logout 401.

The regular Java/Vue CI covers isolated source guardrails and web CSRF/status
schema. Browser acceptance is separate. All tests use invented data only.

## Gaps before actual Amazon production enablement

- Independent Orders role approval evidence in vault: currently
  `SpApiSellerGrantVault.loadVerified` has an empty approvedRoles set.
  Do **not** bypass this; build operation-specific role evidence and guard.
- Official typed SDK Orders call must be connected to a trusted worker
  backend with production rate limit/Retry-After, request window validation,
  token expiry and critical revocation tests. Current worker's page logic is
  tested against only the fixed synthetic source.
- Need actual `orderItems` ingestion and provenance-safe business/fulfillment
  interpretation, page replay amid authorized grant rotation, multi-node
  real network failure/retry testing, quotas, backfill and Seller PII retention.
- Need production key management, MFA/RBAC, monitoring, backup restore and
  full production security review. No full Amazon Orders sync is claimed here.


## V11A PostgreSQL restart validation (2026-10-31)

The opt-in Compose regression runs `scripts/smoke_orders_sync.py` in two stages:
first, an authenticated Owner starts a LOCAL/UNCONFIGURED US Marketplace job,
commits page 1, and confirms watermark is still NULL; then the backend container
is actually restarted and a new login checks that the same job/page/lease epoch
persisted. It commits the final page, confirms the safe watermark and no extra
orders, starts the next overlapping window, and verifies foreign ID 404 and
logout 401. This is a **Mock-only** Worker test. Its real run evidence must
come from the completed GitHub Actions workflow, not inferred from code.
