# V134 — V49 full expiry and overdue position evidence

Baseline: [V133 full A3 #42823047710](https://github.com/kunlong-luo/merchivra/actions/runs/42823047710) and [same-SHA main CI #42823047323](https://github.com/kunlong-luo/merchivra/actions/runs/42823047323) both SUCCESS on `874f00a14f2156c2ba682cceeac60d66efa70a10`. This document describes a *new* candidate until its own exact-SHA A3 passes.

## Defect

V49 `GET /api/v1/purchasing/lot-lifecycle/warehouses/{warehouseId}/expiry-alerts` returned only the first 300 positions with `remaining_quantity>0` and a manually registered expiry date at most 30 days ahead. With more than 300 expired/soon-expiring active lot positions, operators could not browse all notices. This read never implied that goods had been automatically quarantined.

## Implementation

- Add `GET .../expiry-alerts/page?limit=50&cursor=...`: bounded page size 1–100, default 50; ascending composite keyset `(p.expiry_date,p.id)` with `WHERE p.remaining_quantity>0 AND p.expiry_date<=CURRENT_DATE+30`. Past-due alerts remain in the scope. Cursor includes authenticated tenant, warehouse, `EXPIRY` domain, last date and UUID; bad date/UUID/limit/tenant/warehouse/domain gives HTTP 400 before any SQL execution. Response `{items,nextCursor,hasMore,pageSize}` is `Cache-Control: no-store`. This is not a total count or historical snapshot under concurrent changes.
- Keep the old first-300 array for legacy compatibility. V49 React now queries each real page through OpenAPI 3.1, generated Orval client, strict Zod shape and TanStack Query tenant/warehouse/cursor keys. Existing `CursorPageControls` provides previous/next and displays page-local scope only. A warning on a particular position does not automatically submit an expiry quarantine or adjust stock.
- Flyway V87 forward-only index: `erp_local_lot_position(tenant_id,warehouse_id,expiry_date ASC,id ASC)` restricted to active remaining positions with explicit expiry. No updates to source positions or signed stock.
- JUnit/Mockito covers 325 notices over seven pages, stable order and no duplicate/omissions, cross-tenant, wrong warehouse/domain, malformed cursor date/UUID, 0/101 limits, and zero stock interactions.
- Vitest exercises 325 notices over seven paginated Orval/Zod calls, schema rejection for invalid cursor consistency or oversize, and strict UUID path checks.
- True PG18 + Spring HTTP smoke `scripts/smoke_lot_expiry_alerts_v134.py` follows V95/V132/V133 in the disposable A3: insert **330 explicitly synthetic, nonposting** local position rows attached to the existing mock receipt, 160 already expired, 165 due within 30 days, 5 due in 90 days. Confirm legacy endpoint returns 300, complete keyset retrieves all 325 due positions without duplicates in ascending expiry date + UUID order while excluding the five later dates; 401 unauthorized, 400 wrong-scope cursor/invalid limit, no-store; remove synthetic position rows; assert zero additional warehouse signed movements. No external Amazon or 3PL calls.

## Release gate

Pass ordinary PR Java 25, React/Next and Compose quick CI before merge. A3 must rerun on **the exact merged main SHA** across PG18/Flyway V87/real HTTP, Vite+Next Chromium, Compose, session/CSRF, backup/recovery. No borrowed V133 pass. User approval still required for target HTTPS, secret provisioning, IAM, production PITR and rollback rehearsal, and finally local Seller/Ads OAuth.

## Remaining P0

Large-tenant PG18 explain plans/IO/locks for lot and action history joins, full V51/V52 original attachment retention/scanning/authorization on the real target, production operational readiness and source-specific fulfillment provenance are **not** established by this synthetic pagination regression. This work deliberately avoids any real production credentials, remote writes or public cutover.
