# V133 — V48 released lot positions: full server history and exact receipt putaway capacity

Baseline: [V132 A3 #42820965118](https://github.com/kunlong-luo/merchivra/actions/runs/42820965118) **success** for main `f84c331b6ec5acc3c2713f37b853671c836b112e`, with the V132 V49 historical actions keyset, PG18/Flyway/HTTP, Java, Chromium, Compose and backup/restore.

## Two connected P0 correctness defects

- `GET /api/v1/purchasing/released-lots/warehouses/{warehouseId}/positions` returned **only the newest 300**. That list was used by V48 inventory visibility, V49 position selection and V50 position/observation review; old positions were stored but inaccessible through ordinary React workflows.
- More critically, V48 calculated `remainingToPlace = acceptedQuantity - sum(placedQuantity)` from that **same first-300 positions list**. With more than 300 root positions, React might falsely show extra unallocated accepted units, potentially prompting an invalid putaway. The backend `ReleasedLotStockService.putaway` already rechecks the correct tenant+receipt `SUM` under locks and would reject the attempt, but the UI's apparent capacity was false.

## Changes

- New Owner-only, no-store `GET .../warehouses/{warehouseId}/positions/page`: 50 rows by default, 1..100 allowed; stable descending `p.created_at,p.id` keyset, tenant and warehouse bound to decoded cursor. Invalid cursor/bad date/UUID/wrong tenant or warehouse fails with 400 before querying the directory. The old `/positions` array remains for backward compatibility and **must not** be called complete.
- New Owner-only, no-store `GET .../receipts/{receiptId}/allocation`: PostgreSQL directly joins the tenant-bound real V46 receipt and its approved QC to calculate accepted quantity and `SUM(p.placed_quantity)` over **all original root positions** (`origin_position_id IS NULL`). Transferred descendants must not double-consume original QC approval. For a receipt not in `RELEASED`, remaining capacity is **zero** regardless of any historical metadata; for inconsistent released data with root placement greater than accepted, request fails closed with 409. No optimistic count is accepted as a stock write command.
- V48/V49/V50 React workspaces use Orval-generated OpenAPI 3.1 clients, runtime Zod checks, scoped TanStack Query keys and existing `CursorPageControls` to browse all persisted position rows. V48 only enables putaway if the **exact current receipt allocation** has been fetched, matches the warehouse and is still `RELEASED`; unknown/error/loading cannot be interpreted as zero or a positive allowance. POST itself continues to lock tenant and warehouse and recheck exact source and signed warehouse stock.
- Flyway V86 indexes the full warehouse position page and receipt root-only allocation query. No Flyway rewrite, no signed-stock credit, no Amazon access, no DB rollback migration.

## Validation gates

- Java Mockito: 325 positions across seven pages without duplicates, cursor scope/limit/timestamp/UUID rejection, exact root-only allocation of 601 placed units out of 650 accepted units, missing/invalid source rejection.
- Vitest: generated Orval + Zod 325-row page navigation and exact allocation; rejects contradictory released quantities and invalid receipt UUID.
- Real disposable PostgreSQL 18/Spring HTTP: `scripts/smoke_released_lot_positions_v133.py` runs immediately after V95/V132. It injects 325 extra explicitly synthetic local **read-only position fixtures**, verifies that legacy API still returns 300 but new page finds all injected records, checks source-root aggregate beyond 300 and zero capacity for a PENDING_QC receipt, tenant Owner login, anonymous 401, scope/limit cursor 400, unknown receipt 404, `no-store`, then removes only the synthetic fixtures and checks signed stock movement count unchanged. It never inserts released inventory, invokes Amazon or alters real user data.
- Ordinary PR Java/React/Next/Compose fast CI must pass. Then a **single post-merge exact-main-SHA A3** checks all true PG18 HTTP, Flyway V86, Next/Vite Chromium, Compose, Session/CSRF, backup/restore; the earlier V132 pass is not V133 proof.

## Still open

V49 expiry alert directory is still capped at 300 and needs its own full history/paged read; V48 FIFO/FEFO preview stays intentionally bounded at 500 and is **only a lower-bound preview**, while the V130 actual pick covers enough eligible positions for the requested units. Large-tenant SQL EXPLAIN/latency, V51/V52 evidence data protection, real deployment HTTPS/managed keys/IAM/PITR/rollback and user-controlled Seller/Ads authorization remain separate P0 criteria. A successful synthetic A3 does **not** authorize production.
