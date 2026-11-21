# V125 — A3 PG18 供应商催办分页超时修复

Date: 2026-11-09. Synthetic test only; no customer/production data.

## V124 full milestone finding

[V124 A3 full run #42623297952](https://github.com/kunlong-luo/merchivra/actions/runs/42623297952), main SHA `6760ebd6956f59bfd117e25175460f2e5e07d497`:

- **Passed**: Vite + Next Chromium user-journey/screenshots; full Java 25 Gradle; frontend/Next production build and HTTP route smoke; Compose + Nginx + PostgreSQL 18 + Spring Session and CSRF; earlier PG18 Flyway and V65–V96 scripts, including V119 full delivery facts and V120 supplier trace.
- **Failed**: PG18 actual HTTP `scripts/smoke_supplier_finance_settlement_pages_v97.py:130` timed out after **70 seconds** at `GET /api/v1/purchasing/supplier-performance/followups/page?filter=ALL&limit=50`, after creating 225 synthetic suppliers and 180 finding records on top of previous synthetic scenarios. This was not a bad browser selector or an HTTP assertion; database query-plan/lock evidence is needed before production sizing.

## Code change: page IDs before expensive aggregates

The V97 route previously built a single `FROM erp_purchase_followup_finding f` query joined to order/SKU/supplier tables with **two per-line LATERAL SUMs** against all ASN and purchase receipt source rows, only then applying `ORDER BY state-priority,changed_at,id LIMIT 51`. That shape may make expensive evidence aggregation happen for more rows than necessary when data grows.

V125 changes only the **read-only** `findingsPage` path in `PurchaseFollowupService`. PostgreSQL now first finds `limit+1` page IDs through a `WITH page_ids AS MATERIALIZED` keyset `(priority,changed_at,id)`, bound to Session tenant and filter. The detailed query joins **only the page IDs** to source tables and does the same unchanged ASN/receipt LATERAL quantity sums. Both inner page and detail query keep explicit tenant checks. Existing `findings()` legacy first-100 read and all manual scan, audit, status change, CSRF and input validation logic are not altered. Page response, keyset cursor shape and UI remain stable.

Added JUnit SQL-structure tests for 50-row initial and filtered cursor pages, asserting the page limit precedes expensive joins, rank, tenant/filter scopes and limit+1. This is a **query optimization hypothesis** until the full PG18 milestone verifies real behavior; do not infer performance from Mockito.

## Gate

Update `.github/workflows/milestone-validation.yml` to run one full A3 synthetic PG18/HTTP/Chromium/Next/Compose suite on the merged main SHA, with milestone label `V125-A3-followup-keyset-PG18-release-gate`. A green fast PR CI alone is insufficient. If the request still times out, capture PostgreSQL EXPLAIN/lock state and fix the underlying database index/plan, rather than raising Python's 70-second timeout or skipping the script. All Amazon OAuth/SP-API production authorization and external writes remain disabled; live deployment and local Seller authorization still require [Issue #63](https://github.com/kunlong-luo/merchivra/issues/63) and the user's approval.
