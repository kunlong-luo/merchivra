# V132 — V49 source/destination batch action history: true keyset, no first-200 cap

Baseline: main `3cb3c1835895d355e29ed90a369ed4cfe4389f53`, [V131 A3 #42633271089](https://github.com/kunlong-luo/merchivra/actions/runs/42633271089) 7/7 required jobs success. This is a **new release candidate**, not yet A3-accepted until its exact merged main SHA is tested.

## P0 defect and correction

V49 `GET /api/v1/purchasing/lot-lifecycle/positions/{positionId}/history` returned the most recent 200 actions, even though both the V49 source-side actions and destination-side transfers are immutable history. The V49 React audit and the V50 count-observation evidence picker incorrectly treated that limited array as the full available source record. Older actions were inaccessible to the operators despite being preserved in PostgreSQL.

V132 adds `GET .../history/page?limit=50&cursor=...`, returning `{items,nextCursor,hasMore,pageSize}` with a bounded 1–100 limit and stable `created_at DESC,id DESC` keyset. Cursor is bound to the **authenticated tenant and exact position**, and malformed timestamps, UUIDs, limits and other position/tenant scopes return HTTP 400 before running history SQL. The position itself must exist in the authenticated tenant or the server returns 404. Missing and malformed cursors never cause a fallback to an unbounded scan; API responses are `Cache-Control: no-store`.

The page query keeps BOTH immutable source and destination transfer evidence:
`source_position_id=? OR destination_position_id=?`. V49 already indexed source recent actions; Flyway V85 adds a destination-position recent index. No V48 putaway, V49 transfer, V50 reconciliation, signed stock quantity or source lineage is rewritten.

The old `/history` list remains for compatible API clients and is **explicitly legacy/truncated**. React V49 audit and V50 pending-count observation selector now navigate page-by-page with existing `CursorPageControls` and Orval-generated OpenAPI 3.1 client; responses have Zod bounded page and cursor consistency checks. A page displays **at most 50 items**, never a claimed full total. Query keys include tenant/position/cursor to isolate cached records; selection resets on position/page change, and independent Owner/CSRF/409 stock controls are unaffected.

## Evidence

- JUnit/Mockito `ReleasedLotHistoryPageV132Test` verifies 225 mixed source/destination actions across five cursor pages (50/50/50/50/25), duplicate-free, incorrect tenant/position, invalid cursor timestamps and UUIDs, invalid 0/101 limits and absent source.
- Vitest checks 225 client records over five pages and rejects inconsistent cursors/invalid path UUID.
- In the existing disposable PG18 `full_suite`, V95 creates 225 nonposting V49 count-observation actions for one real tenant+position; `scripts/smoke_lot_history_v132.py` then logs in as the synthetic Owner, calls the actual Spring HTTP `/history/page`, traverses all 225, checks no-store, legacy 200 compatibility, wrong position cursor 400, missing position 404 and bad limit/cursor 400. Flyway V85 and the index are verified against actual PostgreSQL 18. No real Amazon or warehouse writes are enabled.

## Intentionally still open

- V48 `/released-lots/warehouses/{warehouseId}/positions` still has `LIMIT 300`, affecting all-position search/selection and prefill; separate pagination and exact item selection are needed.
- V49 `/warehouses/{warehouseId}/expiry-alerts` has a `LIMIT 300`. Further pagination and large-tenant query plans/EXPLAIN are not covered by this narrow V132.
- A cursor is scoped and validated, not cryptographically signed and not an immutable historical snapshot under concurrent inserts; operators must refresh to see newly added actions.
- V51/V52 private evidence retention/scanning and production HTTPS/secret storage/IAM/rollback, Amazon Seller/Ads local OAuth and manual production approval remain independent P0 gates.

Do not mark V132 green until the complete exact-SHA milestone and main CI succeed; do not call synthetic A3 success a production deployment.
