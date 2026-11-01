# V65: FBA local inbound shipments, transit and variance reconciliation

Status: local backend, generated Orval client, and React owner UI committed on feature branch; further PG concurrency/integration coverage pending; real Amazon connection OFF.

## Scope and meaning

- Flyway V63 stores shipment plans, SKU lines, append-only reported receiving evidence, and immutable variance decisions.
- Owner session supplies tenant and actor; every query scopes tenant and marketplace. All writes require CSRF. The HTTP handlers set no-store.
- States: DRAFT → IN_TRANSIT → RECEIVING (optional) → REVIEWING → RECONCILED. Review freezes new receipt writes.
- Only local MOCK or MANUAL_UNVERIFIED evidence is allowed. No official Amazon FBA shipment creation, Fulfillment Inbound API calls, seller authorization or stock mutation is involved.
- Only IN_TRANSIT/RECEIVING shipments enter the per-SKU transit summary, grouped separately by MOCK vs MANUAL_UNVERIFIED origin. REVIEWING/RECONCILED shortfalls are never presented as stock still in transit.
- Reported received is a SUM of local receipt evidence. Estimated in-transit = max(planned - reported, 0), but only after dispatch. A positive delta means reported overage; a negative delta means reported shortage.
- Both amazonVerified and fbaStockCredited remain false. Do not combine those values with FBA fulfillable, reserved, Amazon inbound observation, finance events or local warehouse stock.
- Nonzero deltas require an independent Owner resolution with an explanation; receipt evidence authors cannot accept their own discrepancies. Exact matches can be closed without a variance decision.
- Client requestKey is required for shipment creation and each receipt; repeated identical requests return the same shipment/receipt-derived view, changed payloads are rejected.

## Spring MVC operation IDs

Prefix: /api/v1/fba/marketplaces/{marketplaceId}/shipments

| Method | Suffix | operationId |
| --- | --- | --- |
| GET | / | listFbaInboundShipments |
| GET | /in-transit | listFbaInboundTransit |
| GET | /{shipmentId} | getFbaInboundShipment |
| POST | / | createFbaInboundShipment |
| POST | /{shipmentId}/dispatch | dispatchFbaInboundShipment |
| POST | /{shipmentId}/receipts | recordFbaInboundReceipt |
| POST | /{shipmentId}/review | startFbaInboundReview |
| POST | /{shipmentId}/variances | resolveFbaInboundVariance |
| POST | /{shipmentId}/reconcile | finalizeFbaInboundReconciliation |

## Verified OpenAPI + Orval path

Only export from the actual Spring MVC server, not from docs/api/openapi.json (DESIGN ONLY). A loopback-access-only endpoint is available when MERCHIVRA_CONTRACT_EXPORT_ENABLED=true. CI boots Java 25 + PG18 and verifies exactly nine operations against the running server. The normalized live contract is committed, compared byte-for-byte in PG18 CI, and uploaded as the v65-fba-live-openapi artifact.

To complete the frontend migration:
1. Start the development server with PG18 and explicit contract export enabled, keeping Amazon OAuth/Sp-API disabled.
2. Fetch http://127.0.0.1:8080/v3/api-docs/fba-inbound to a temporary JSON file.
3. Run node scripts/normalize-fba-openapi.mjs input.json apps/web/openapi/fba-inbound.json.
4. Add the fba-inbound entry to apps/web/orval.config.ts, reusing the existing merchivraFetch transport; run npm ci and npm run api:generate from apps/web. Commit both verified schema and generated client. Do not hand-edit generated hooks.
5. Implement React shipment and variance panels on top of the generated Orval client, then run api:check, typecheck, Vitest and Vite build.

Outstanding before V65 is complete: generated Orval client + React UI, real PG integration tests for concurrent idempotent receipts and two-Owner reviews, mock end-to-end workflow tests. No real Seller authorization until all core modules are finished and Owner tests locally.

## Runtime generation smoke

The CI PG18 job now installs the locked Node 24 client toolchain, runs Orval 8 against ONLY the normalized live FBA contract, and preserves the schema and generated TypeScript model/hook files as the PR artifact. This runs with Amazon OAuth/Sp-API switched OFF; the generated files are committed and checked for drift. The React shipment panel uses those requests through the existing same-origin mutator and Zod response validation.
