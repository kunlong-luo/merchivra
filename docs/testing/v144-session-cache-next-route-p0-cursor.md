# V144 — Cross-session cache isolation, purchase keyset contract and all Next module entrypoints

Baseline (2026-11-12): **V143** [main exact SHA `42e3ef0f991fa53fe710420759afc93dc3308716`](https://github.com/kunlong-luo/merchivra/commit/42e3ef0f991fa53fe710420759afc93dc3308716), [full A3 #43204653128](https://github.com/kunlong-luo/merchivra/actions/runs/43204653128) 7/7 success. Production release G1/G2/G3 remains separate, blocked by [#104](https://github.com/kunlong-luo/merchivra/issues/104), independent of [#62](https://github.com/kunlong-luo/merchivra/issues/62) purchasing pagination and [#63](https://github.com/kunlong-luo/merchivra/issues/63) Next runtime cutover.

## Real P0 issue identified

Both Vite and Next App Router reuse `src/Root.tsx` + a **persistent** TanStack Query v5 `QueryClient`. On successful login/logout the old `clearCaches()` manually removed a **fixed subset** of prefixes (such as `['core']` and `['team']`) but omitted `['purchasing',tenantId]`, `['catalog',tenantId]` and **future new modules**. Server-side Session/RBAC still correctly rejects unauthorized requests, but stale cached data or mutation messages from a previous principal could remain client-visible until refetch, **including two accounts in the same tenant with different roles**. That is not acceptable for a private multi-user ERP.

**V144 fix:** `src/lib/session-query-cache.ts` has one transition hook that (1) cancels every non-auth pending TanStack query, (2) removes **all** non-auth cached queries regardless of existing/future prefix, and (3) removes cached mutation results. It deliberately preserves the mounted `['auth','me']` observer so Root can immediately re-fetch the Spring Session principal. `Root` **awaits** this purge after both successful login and successful logout and **before** the new `/api/v1/auth/me` fetch. No persistent localStorage storage or token caching is introduced. Frontend cache isolation complements, never substitutes for, Spring server tenant isolation.

## Purchasing full-history contract

Issue #62 introduced server keyset interfaces across V110–V114 and migrated read/write interfaces to **OpenAPI/Orval** through V115–V121. The full PostgreSQL 18 CI runs `smoke_purchase_orders_page_v110.py`, `smoke_supplier_pages_v111.py`, `smoke_warehouse_ledger_pages_v112.py`, `smoke_warehouse_read_pages_v113.py`, and `smoke_warehouse_directory_v114.py`; these retain 125–525+ record tests for all-page navigation and wrong-tenant/cursor/scope handling. Fast React CI checks `npm run api:check` against locked generated Orval code.

V144 corrects one remaining inconsistent front-end contract: `purchaseOrdersPage` was the ONLY main purchasing keyset parser without an explicit maximum 100 server items and without `hasMore === (nextCursor !== null)`. A malformed server response could display an incorrect end-of-history. V144 now rejects contradictory `nextCursor`/`hasMore`, overlong 100+ lists, and pages containing more items than their declared `pageSize`, with dedicated Vitest negative tests. It preserves Orval's generated API implementation and Zod validation; there is **no client-side fake “full tenant” sum**, no loss of typed decimal money and no automatic remote writes. Backend stable keyset, tenant filtering, CSRF/Owner and signed ledger invariants are unchanged.

## Next App Router technical regression, not production switch

The V122 actual standalone **Node 24 / Next 16** Docker Compose test already used the same disposable Spring Boot/PG18 session to verify login, SameSite, CSRF, logout and an anonymous browser but sampled just 7 routes. V144 extends real HTTP smoke to all **11** `/ /core /orders /catalog /inventory /purchasing /ops /finance /connections /team /sync` direct links. For each path it checks the genuine Next HTML shell, HTTP 200 and that the HTML contains none of the session tenant ID, user ID, CSRF token or password. A distinct anonymous browser must not inherit Owner data on /purchasing; an unknown path returns 404; the old Spring proxy 401/403/CSRF/204 logout checks continue. The full Compose test also asserts all 11 entrypoints, and **keeps the Vite/Nginx rollback container online**.

These checks do not prove production TLS proxy trust, real IAM/issuer policies, user-performed cutover/rollback or human screenshot acceptance. Those remain open on #63; cannot be described as a production release.

## Acceptance

- PR fast CI: Java 25, TypeScript and Vitest (including all V144 cache, cursor, pending cancellation tests), locked Orval generation/diff, Vite build, Next production build, Compose static safety.
- One merged-main **exact SHA** full A3: all existing PG18 Flyway/HTTP/125–525+ purchasing paging checks, real V140 proof audit, V141 storage, V142 real disposable PITR + internal Alertmanager, V143 fail-closed G1/G2/G3 preflight, 11 route Next Spring Session/CSRF, two Chromium workbenches and backup/recovery.
- **No real Amazon Seller/Ads authorization, remote writes, evidence original DELETE, production deployment or auto-approved G1/G2/G3**. Actual target release #104 remains OPEN. GitHub Pages remains a synthetic read-only public site.

A passing V144 A3 can be used to **review and potentially close #62's technical P0 pagination/Orval checklist**, but never to close #63's target production cutover or #104's independent human approvals.
