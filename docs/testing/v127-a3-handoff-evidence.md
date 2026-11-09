# V127 — A3 evidence and controlled production handoff (NOT a release approval)

Date: 2026-11-09. Scope: Issue #62 / #63. Baseline main commit: `6aad048dde13d723d0a66e7abd5e233c9ccc3b8a`.

## Verified evidence (synthetic / disposable only)

- [V126 complete milestone](https://github.com/kunlong-luo/merchivra/actions/runs/42625621926): **success** for the exact baseline commit. Covers Java 25/Gradle, PostgreSQL 18/Flyway + HTTP, Vite and Next Chromium, standalone Next Node ↔ Spring Session/CSRF ↔ PostgreSQL Compose, and isolated backup/restore. Do not infer production performance or security certification.
- [main CI](https://github.com/kunlong-luo/merchivra/actions/runs/42625621962): **success** for the same commit.
- V119 full-database delivery summary/pagination, V120 supplier follow-up/return trace and V121 V50/V51 history pagination are merged; do not reopen the old LIMIT 300 task.
- The remaining open pull requests at this baseline are Dependabot dependency upgrades (#17–#19, #21, #73); none is a missing V119 implementation.

## P0 audit and evidence still needed

| Gate | Current evidence | Required next action | Owner |
| --- | --- | --- | --- |
| V48 historical lot-position provenance | Existing business logic; not an independently signed full-history source audit | Trace UI → API → tenant/warehouse-scoped SQL → immutable provenance for >200 records, including negative authorization and broken-cursor cases | Development |
| V51/V52 original attachment and audit chain | Multipart path deliberately not converted to JSON Orval | Verify MIME/size/hash, private storage access, owner role/CSRF, tenant separation, upload/download audit and retention; never expose private files as CI artifacts | Development/security |
| FBA ↔ local purchasing source contracts | Local ASN/receipt is not Amazon FBA receipt | Inventory all remaining client operations; preserve UNKNOWN/null, local-vs-LIVE source separation and no implied remote writes | Development |
| Large tenant query plans | 180-row V97 and 225/325-row synthetic cases are functional evidence, not capacity certification | Capture sanitized PG18 EXPLAIN (ANALYZE, BUFFERS) on representative *synthetic* scale; define budget and index decision before calling performance complete | Development |
| Private Next deployment | Standalone Node/PG18/Session synthetic Compose validated | On an approved target: HTTPS ingress, trusted forwarded headers, external secrets, secure cookies, health checks, firewall, observability, backup/PITR and rollback drill | Maintainer + operations |
| Real Seller/Ads integration | Disabled by policy | Local, explicitly approved read-only OAuth, revocation, role/account/region, 429, data provenance and finance reconciliation; remote writes require separate approval | Maintainer |
| Release authorization | No G1/G2/G3 signatures | Record security, business/finance and production approval independently | Maintainer |

## Non-destructive handoff procedure

1. Freeze an immutable candidate SHA; check all PRs and CI against that exact SHA. Any subsequent merge invalidates the candidate's A3 sign-off until rerun.
2. In a disposable local environment, follow [V122 Next runtime instructions](../architecture/next-node-cutover-v122.md). Keep `MERCHIVRA_SPAPI_ENABLED=false`, OAuth and remote writes disabled; no production credentials in GitHub Actions.
3. Before any traffic cutover, capture a *real* target-environment TLS/ingress, trusted-proxy, cookie, session invalidation, role and CSRF report, plus operator-approved backup/restore evidence and rollback timings. CI cannot substitute.
4. For a private cutover, preserve the Vite/Nginx endpoint as an immediate **web-only** rollback. Never roll back Flyway SQL or overwrite a PostgreSQL volume to undo a UI change.
5. Record evidence URLs, timestamp, SHA, responsible reviewer, open risks and explicit G1/G2/G3 decisions. Do not call GitHub Pages a deployed ERP.

**Status: A3 synthetic gate PASS; P0 audit and production/private-beta authorization OPEN.** This document is an evidence ledger and handoff checklist, not a claim that the remaining actions were performed.
