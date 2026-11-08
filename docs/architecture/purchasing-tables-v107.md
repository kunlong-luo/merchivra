# V107 — Purchasing, suppliers and local warehouse audit lists

## Existing API contracts and deliberate scope

The legacy purchasing `GET /suppliers`, `GET /orders` and
`GET /warehouse-ledger?warehouseId=...` currently return a JSON array.
Warehouse ledger is explicitly a recent-record view capped to the server's
100 records; purchase and supplier APIs do not expose cursors or totals.
**Do not invent a global total, page count, server search or pagination.**

The site preserves Spring Session/CSRF, active-tenant server rules and
independent Owner procurement approval. No local warehouse action is
performed while filtering or inspecting the ledger.

## Implemented with already pinned components

- `components/PurchasingDataTables.tsx`: TanStack Table v8 table model,
  server row UUID keys, semantic table/column headers, keyboard-focusable
  horizontal scroll, manual sorting/filtering/pagination flags. No new
  table/grid dependency and no backend/API rewrites.
- Supplier directory: shows the supplier code, name and lifecycle state
  returned by the scoped supplier endpoint (previous UI only showed count).
  Distinguish the returned count from any claim about full-database totals.
- Purchase orders: controlled Base UI 1.9 Select for statuses and native
  search input for order-number matching. **Both are labelled as filtering
  only rows in the current API response**. Default columns retain order
  identity, lifecycle state, original currency and operation buttons.
  Supplier display and local updated timestamp are optional show/hide
  columns. If supplier metadata is missing, show a partial ID and
  explicitly say it could not be resolved instead of guessing the name.
- Purchase actions: existing view, submit, approve and cancel callbacks
  unchanged. Approve still needs another Owner. Cancel retains V104
  Base UI AlertDialog confirmation, CSRF and backend checks. Do not
  introduce a direct write from the table or bypass independent approval.
- Local warehouse movement view: controlled Base UI type filter across
  the server's most recently loaded ledger rows. Original UUID, timestamp,
  SKU, signed quantity, movement type, business source ID and reason are
  retained; no client aggregation or balance reconstruction. The filter
  resets if the selected warehouse changes, and the actual ledger query
  continues to be warehouse-scoped.
- Compact A+B layout uses shared popup and in-flow column chooser styles,
  responsive local horizontal scrolling, and native form inputs for
  inventory operations.

## Regression and trust

`scripts/smoke_browser_layout.mjs` runs on disposable localhost fixture.
It now includes two synthetic suppliers, draft and approved local purchase
orders, one warehouse stock entry and two different immutable movements.
Chromium verifies supplier rows, status selection, optional column
visibility, non-mutation, warehouse filter, source ID and reason.
Retains V104 cancellation "dialog dismiss does not write, approve writes
once" regression. Fixes a main-branch flaky V106 browser check:
wait for **the actual operations findings table**, not the first row of
an unrelated operations sub-panel.

Do not present uninspected local inventory as FBA, signed deltas as
available-to-promise, or locally submitted procurement as Amazon orders.

References:
- https://base-ui.com/react/components/select
- https://tanstack.com/table/v8/docs/guide/column-visibility
