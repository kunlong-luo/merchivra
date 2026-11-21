# V136 — actual PG18 large-tenant V135 A3 failure and index-first source page fix

Date: 2026-11-11. Starting baseline: `main` `3b1f0d112cc8af679ed217714435c716c4982521`.

## Failure evidence — do not call V135 green

- [V135 full exact-SHA A3 #43009121081](https://github.com/kunlong-luo/merchivra/actions/runs/43009121081): **PostgreSQL 18 real Flyway/HTTP job FAILED** after the V95/V132/V133/V134 scripts all passed their existing HTTP correctness checks. The new V135 12,000-position / 6,000-action `EXPLAIN ANALYZE BUFFERS` gate completed its SQL transaction but rejected the first warehouse-position page: `('position_start','large-tenant access index was not chosen',['erp_local_stock_bin_tenant_id_id_key','erp_receipt_quality_batch_pkey'])`. The intended V86 `erp_local_lot_position_warehouse_recent_idx` was not selected for this path.
- Java 25, React/Vite/Next builds, Vite+Next Chromium and Compose static jobs completed successfully, but the run is **not accepted**. A separate green run on the new immutable main SHA is mandatory.
- The fact that 225/325-row V132–V134 pages were correct did **not** establish acceptable query plans at 12k rows. Retain the failing A3 as a positive example of a useful P0 performance gate, rather than removing the failure assertion or falsely treating the latest code as validated.

## Actual root cause and remediation

The V133 warehouse source `positionsPage` and V134 `expiryPage` were originally SQL joins across `erp_local_lot_position`, receipt QC batch and stock bin, followed by `ORDER BY ... LIMIT 51`. PostgreSQL could optimize the entire joined query with a large position scan/sort even though the positions directory had a matching page index.

- **V136** explicitly selects tenant-and-warehouse position IDs and required columns in a `WITH selected AS MATERIALIZED` CTE, applies the original cursor predicate, original stable `created_at DESC,id DESC` or `expiry_date ASC,id ASC` ordering and `LIMIT 51` (bound 1–100+1) **before** the immutable QC and stock-bin joins. The outer query joins at most 51 items and reapplies the same stable order. The V86 and V87 indexes remain unchanged; no speculative new migration.
- The V135 benchmark now runs **the same CTE-first SQL** as the actual Spring endpoints, including first/deep keysets. It prints sanitized query plan nodes, actual row count, index selections and buffer counts **before** enforcing the index/seq-scan/latency gate. The 12k/6k read-only transaction and automatic rollback are preserved.
- Mockito/JUnit tests for V133 and V134 assert the CTE exists, the limit precedes the JOIN and cursor predicates/ordering remain enforced. 50+1 packing, no-store, Owner tenant binding, Zod/Orval, frontend navigation and all signed-stock mutation controls are unchanged.
- V132 immutable action history `source OR destination` and exact receipt allocation are not rewritten without evidence; their original plan checks stay enabled and may expose a separate bottleneck on the new full A3.

## Required evidence for acceptance

PR fast Java 25/React/Next/Compose static checks must all pass. After merge, the milestone workflow reruns **the entire A3** on the exact merged main SHA: real PG18 12k+6k explain/IO benchmark and V95/V132–V134 HTTP, Chromium, Next standalone, Compose session/CSRF and backup/restore. If another plan fails, do not lower guardrails without a grounded new performance strategy.

**Not production permission:** synthetic CI query cost is not a production p95, does not model concurrent stock locks, cold caches, bloat, production TLS/secret storage/IAM, real backup/PITR and rollback. V51/V52 evidence scanning/retention and local user-authorized Amazon Seller/Ads OAuth remain open in #62/#63.
