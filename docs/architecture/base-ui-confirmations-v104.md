# V104 · Base UI AlertDialog for auditable procurement actions

## Why

Browser `window.confirm()` is an unstyled, noncomposable blocking
UI. Merchivra has irreversible local stock movements, procurement state
changes, independent-owner supplier variance reviews and claims: these need
a clear description of the **actual** effect, keyboard focus management and
a visible cancellation choice. We reuse the existing pinned
`@base-ui/react@1.9.0` dependency rather than building a custom modal.

## Delivered

- Reusable `src/components/useConfirmAction.tsx`: Base UI `AlertDialog`
  Portal / Backdrop / Popup / Title / Description / Close, with consistent
  accessible names and focus restoration, distinct destructive styling.
- Removed every `window.confirm` in `PurchasingWorkspace` and
  `SupplierDisputesPanel`: two purchasing actions and six supplier variance /
  claim actions. Basic form fields and simple tables stay native HTML.
- Confirmation only opens after existing client validation. The accepted
  action carries an immutable snapshot of the stock operation, variance,
  claim and closure note. Cancelling the dialog never invokes the mutation.
- All existing business mutations continue to use TanStack Query and the
  established Spring endpoints. Server-side RBAC, CSRF, idempotency keys,
  inventory optimistic concurrency, independent-owner approvals and audit
  trails remain authoritative.
- Chromium synthetic test opens a real draft PO cancellation dialog at
  360px, checks its keyboard focus and explicitly dismisses it with **zero**
  writes, then approves once and verifies **one** local synthetic POST.
- Style is shared between Next App Router and rollback Vite, preserving A+B
  visual tokens without adding Tailwind.

## Next

Use the same primitive for other auditable high-impact transactions on a
module-by-module basis, and retain native HTML select/input/button/table for
simple interactions. Introduce TanStack Table only to modules where
server-side sort/filter/cursor and column management warrant the complexity.
Never replace backend authorization with UI confirmation.

This is not a production OAuth/SP-API cutover; all synthetic test records are
fabricated. Security gates from V101 remain required for release.

Dependency docs: https://base-ui.com/react/components/alert-dialog
