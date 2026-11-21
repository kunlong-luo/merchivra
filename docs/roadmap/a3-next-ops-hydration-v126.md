# V126 — A3 PostgreSQL 18 已通过，Next Chromium 运营看板时序回归

Date: 2026-11-09. All tests are disposable and synthetic, not an Amazon real connection.

## Verified V125 milestone outcome

[MVP A3 main-SHA #42624606496](https://github.com/kunlong-luo/merchivra/actions/runs/42624606496) on `32a4e05e2afd96b6a3d01484f2b275425622254f`:
- **Passed**: actual PostgreSQL 18 / Flyway / Java 25 / signed inventory & approval / FBA mock HTTP. V65–V98 including V97 225 supplier scorecards, 180 followups and 125 manual finance settlements, V119 delivery full-tenant totals and V120 local supplier return pages. Session/CSRF, isolation and PG18 backup/restore gate passed.
- **Passed**: Docker Compose Nginx + Spring + PG18 real Session / Next standalone smoke; React / Next production build and backend tests.
- **Blocked**: Next.js Chromium synthetic UI assertion at `scripts/smoke_browser_layout.mjs:838`, "operations rule label". Vite Chromium run in the *same* job passed. After changing to Ops navigation, the fixture first waited for first row containing `模拟测试`, then immediately queried that first row again to check `FBA 库存数据不完整`. Next hydration can replace that row between two CDP evaluations, returning `undefined`. This is a flaky test observation, not an assertion of successful production UI logic.

## V126 narrow fix

Retain strict business content checks, but replace the two first-row assumptions with asynchronous, all-visible-rows predicates:
- wait for an Ops row containing synthetic-origin `模拟测试`;
- separately wait for an Ops row containing exact rule text `FBA 库存数据不完整`;
- if the second fails, log the first eight visible row texts and rethrow instead of hiding the failure.

No actual frontend, inventory, security or server behavior is changed by this PR. The workflow is updated to fire once on **the merged main SHA** for full A3 PG18, Chromium, Next, Compose and Session/CSRF validation. A3 is accepted only if the actual workflow concludes **success** with all required jobs green. Amazon Seller OAuth/SP-API writes remain disabled. Real HTTPS ingress, deployment secrets, recovery operations and user-local Amazon authorization remain the separate [Issue #63](https://github.com/kunlong-luo/merchivra/issues/63) production handoff.
