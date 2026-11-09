# V130 — V48 FIFO/FEFO pick false insufficient-stock caused by 500-row source cap

Date: 2026-11-09. Baseline: [V129 A3 #42630908321](https://github.com/kunlong-luo/merchivra/actions/runs/42630908321) all jobs success for main `819244d0a73f62c0336f7bd27366b7875b5dea13`.

## Actual defect

`ReleasedLotStockService.candidates` used `LIMIT 500` for BOTH a read-only pick preview and the actual `POST /released-lots/picks` transaction. A SKU with 501+ distinct valid single-unit positions could be physically/accountably available, but the write would consider only 500 rows and return a misleading `409 Insufficient non-expired positioned lots`. React also summed at most the first 500 preview positions as if this were the total, disabling otherwise-valid picks.

## Correctness-preserving fix

- The backend keeps the **read-only** preview intentionally capped at 500 rows, but the write uses `LIMIT :requestedQuantity` (JDBC bind parameter). This is sufficient because every selected position has `remaining_quantity > 0`, so a request for Q units never needs more than Q eligible positions. There is no fixed page threshold in the transactional write. The existing tenant/warehouse/SKU source scope, FIFO/FEFO sort, non-expired guard, tenant+warehouse lock, snapshot check, immutable pick source records, signed V40 OUTBOUND and same-transaction rollback remain unchanged.
- React distinguishes **fewer than 500** results (complete and safe to sum) from **500** results (possibly incomplete). For possibly incomplete previews, the displayed quantity is an explicitly labeled lower bound, and an Owner can submit a candidate up to the **exact warehouse signed balance**; the Spring POST itself re-evaluates eligible lot quantities and refuses any insufficient/stale attempt. This avoids treating unknown omitted positions as zero without ever promising those unknown units exist.
- Java tests exercise a 501-position single-unit FEFO pick through all 501 position reservations and signed outbound; Vitest asserts UI bound logic for 499 vs 500 and unknown global balance.
- Ordinary PR fast CI remains the development gate. One full exact-main-SHA A3 (Java 25, PG18/Flyway/HTTP, Chromium, Next, Compose, backup/restore) must succeed after merge before this candidate is considered synthetic-accepted.

## Not addressed by this narrow fix

- `GET /released-lots/warehouses/{warehouseId}/positions` still has `LIMIT 300` and V49 history/expiry uses other fixed read bounds. Those remain separate P0 *read and selection* issues for paging; never claim full source history from these old endpoints.
- Large-tenant query-plan/latency measurements, real operational scan evidence and write-heavy concurrency are not established by a 501-item synthetic unit test.
- This is **local** inventory only, not FBA available-to-promise, Amazon submission or external 3PL fulfillment.

Keep Issue #62 / #63 open until all other release gates, production HTTPS/IAM/secrets/rollback, and maintainer approval are met.
