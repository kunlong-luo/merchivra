# V120 — Supplier follow-up and local return trace contracts

Status: PR #79, pending fast CI and PostgreSQL 18 full_suite; not production accepted.

## Evidence and API contract

The existing V73 supplier scorecard only scores 90-day local evidence with at least three fully ASN-linked receipts and three QC samples. A score is provisional, not carrier-verified OTIF. The manual pre-dispatch follow-up state machine and scan never contact suppliers. Both read models now use generated Orval methods plus Zod runtime checks; legacy bounded endpoints remain for compatibility. The canonical workbench uses 50-row keyset pages.

The V52 return trace only concerns an **already executed-reported SUPPLIER_RETURN disposition** sourced through the original physical receipt and purchase order. It is distinct from rejected QC returns. The new warehouse-scoped `GET /api/v1/purchasing/lot-supplier-returns/warehouses/{warehouseId}/page?limit=50&cursor=...` returns all reports by immutable dispatch time and ID, with a tenant/warehouse-bound opaque cursor. Existing first-200 GET remains compatibility only. A maker reports dispatch with an idempotency key; a different Owner records supplier-receipt quantity and evidence, including mismatch. These are **human assertions**; no carrier or supplier proof, no Amazon/SP-API write, and no second inventory movement.

## Security and acceptance

Server derives tenant and actor from Owner Session; mutating routes retain CSRF, tenant lock, state transition checks, request-key idempotency, independent reviewer and original-source verification. The OpenAPI purchasing document contains 112 operations and is consumed by generated Orval clients. Zod still rejects malformed responses and inconsistent cursor metadata.

Required before merging: fast PR CI must actually pass (Java 25/Gradle, web typecheck, Vitest, OpenAPI generation). Required before milestone closure: PostgreSQL 18 Flyway + real HTTP full_suite including multi-tenant isolation, cursor traversal >200, concurrent reports, 401/403/CSRF, amount/stock invariants and query plans. Do not infer those from mocks or fast CI. Amazon local Seller OAuth and production deployment remain disabled pending user participation.
