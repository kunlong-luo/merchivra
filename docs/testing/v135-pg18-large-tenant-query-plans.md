# V135 — PostgreSQL 18 large-tenant lot query-plan and buffer regression gate

Baseline: `main` `fa9c08c4012016765e61f5bc198d4fe04e570bd2`, [V134 full A3 #42931141836](https://github.com/kunlong-luo/merchivra/actions/runs/42931141836) success (7/7), [main CI #42931141727](https://github.com/kunlong-luo/merchivra/actions/runs/42931141727) success.

## Scope and why it matters

V132–V134 fixed false first-200/300 source history, warehouse locations, exact receipt putaway quantities and expiry alert navigation. SQL pagination is correct at a few hundred rows, but **correctness alone does not establish query-plan or latency safety for a large tenant**. We must not claim production readiness from those small synthetic fixtures.

This V135 adds a **read-only query-plan acceptance** against the existing forward-only V49/V85/V86/V87 indexes. It does not rewrite business SQL, add unnecessary schema migrations, or assume that a query with `LIMIT 51` is automatically fast.

## Implementation and safety model

`scripts/check_lot_query_plans_v135.py`, executed only in the **full** disposable PostgreSQL 18 GitHub Actions job, after the authenticated V95, V132, V133 and V134 HTTP smokes:

- Refuses execution unless `GITHUB_ACTIONS=true`, `MERCHIVRA_V135_DISPOSABLE_ONLY=true`, server is PG18 at `127.0.0.1`, expected disposable CI database/password/Owner, demo enabled, real Amazon SP-API and OAuth **off**, and Amazon remote writes **off**.
- Verifies existing V49/V85/V86/V87 indexes from `pg_indexes`, then inserts **12,000 synthetic local position rows** (with mixed valid, missing and out-of-window expiry) and **6,000 nonposting `COUNT_OBSERVATION` actions** attached to synthetic V95 evidence positions. All INSERTs, statistics refresh for representative planning and EXPLAIN calls happen inside one transaction that ends with **ROLLBACK**. Even if any SQL/timeout/parse fails, the disposable session is closed and transaction automatically rolls back; no synthetic row is committed.
- Measures seven shapes in **real PostgreSQL 18** using `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`: first/deep warehouse-position pages, first/deep **source-or-destination** action history pages, first/deep expired/30-day alert pages, and exact original receipt root-only allocation aggregation. Queries preserve the same tenant predicate, joins, sort, timestamp/UUID keysets and 51-row lookahead used by Spring endpoints.
- Reports only plan type/selected index names, 51-row or single-row bounds, execution milliseconds and shared buffer counters. **No private record contents, cookies, SQL credentials, business documents or real user data** are logged or uploaded.
- Rejects lost V85/V86/V87 indexing, a large-table sequential scan on bounded page reads, missing lookahead rows, or a severe slow query. Read probe gate: 1,500 ms per query; root aggregation: 3,500 ms. Bulk synthetic fixture setup has its own **30-second statement bound**, reads have **6-second statement timeout**, and the Python subprocess has a hard upper limit. These conservative synthetic thresholds are CI regression alarms, **not** advertised production SLAs or measured p95/p99.
- After transaction rollback, restores PostgreSQL statistics against actual remaining disposable rows, re-reads position/action/signed-movement counts, and requires all three counts unchanged. The script does not issue a POST/UPDATE/DELETE against any stock API or Amazon service.
- The PR fast Compose job runs `py_compile` and the standalone `--self-test` JSON parser/access-index rejection tests with **no database**, preserving the normal fast development cadence. One post-merge exact-SHA full A3 runs the real plan suite alongside the existing Java/Next/Chromium/Compose/PG18/HTTP/backup tests.

## Interpretation, limitations and follow-up

An index was created in a previous Flyway migration **only if the planner genuinely selects it under this dataset**; catalog existence alone does not count as a passing performance plan. `Shared Read/Hit Blocks` and planning/execution time are recorded so future comparisons can identify work amplification.

This is synthetic, single-run, warm-cache, disposable data. It is **not** a realistic tenant workload distribution, a concurrency/lock-contention proof, a production capacity reservation, or an authorization/authenticity audit. Destination transfer events are covered by the existing SQL `OR` and V85 index structural checks, but the bulk synthetic action fixture is nonposting `COUNT_OBSERVATION` and cannot prove real cross-warehouse transfer throughput. Real target anonymized workload statistics, representative EXPLAIN/IO under concurrent stock operations, index bloat/vacuum, queue lag and SLO approval remain separate release gates.

Production HTTPS/trusted proxy/managed secrets, least-privilege DB role, backup/PITR/restore and web rollback rehearsal, V51/V52 private evidence retention/scanning, and user-authorized local Amazon Seller/Ads OAuth remain OPEN. Do not claim V135 accepted until its own full exact-main-SHA A3 succeeds, or call an A3 result permission to deploy production.
