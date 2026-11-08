# V106 — Finance event review: Base UI Marketplace + TanStack Table

## Motivation

V105 proved that Base UI Select plus a server-owned TanStack Table column
model can replace bespoke UI without replacing Spring business behavior.
The existing finance event view was the next safe place to adopt this
pattern: original signed amounts, data source and manual review state are
material facts that cannot be recalculated or merged across Marketplaces.

## Changes

- `FinanceMarketplaceFilter.tsx` renders the set of **server-authorized**
  Marketplace IDs using Base UI 1.9 Select. Switching marketplace clears the
  in-progress event match and returns to the FIRST server cursor.
  This is presentation; Spring still checks the active tenant and site
  authorization on each request.
- `FinanceEventsTable.tsx` uses TanStack Table 8.21.3
  (`getRowId: event.id`, `manualPagination`, `manualSorting`,
  `manualFiltering`) to display exactly the server-returned page.
  Optional category, postedAt and sourceType columns may be hidden,
  but event key, **MANUAL_UNVERIFIED versus LIVE** provenance, signed
  currency/amount, manual match state and review action remain required.
  Do NOT add an in-browser sort across incomplete pages.
- Finance cursor navigation maintains a back-stack of
  `{beforeAt,beforeId}` per Marketplace so operators can go back one
  page instead of being forced to restart. Since the legacy endpoint is
  a bare array of at most 50 rows, exactly 50 means only **possible**
  more records. The UI intentionally says "尝试下一页"; no global total or
  hasMore claim is invented. Empty last pages can return to prior pages.
- `datetime-local` and basic monetary form inputs stay native HTML to
  preserve browser validation and avoid unnecessary custom components.
- No change to finance source ingestion, original breakdown, signed amount,
  cost calculation, currency handling, order matching logic, Spring Session,
  CSRF, business audit, permissions, or production SP-API authorization.
- The Chromium synthetic fixture generates a **51-item USD feed** and a
  separate **1-item EUR Marketplace feed**, asserts real cursor UUID/time
  query parameters, previous-page restoration, authorized Marketplace
  switching, reset cursor, source distinction, mandatory columns and 360px
  viewport. No production data or Amazon calls are involved.

## Non-goals

Server still controls latest-first finance event order and record limits.
TanStack Table is not allowed to imply global sorting/search or total
counts. Real date filtering needs a new scoped backend endpoint before
an advanced calendar picker makes sense. UI source labels do not
independently attest production Amazon authorization.

References:
- https://base-ui.com/react/components/select
- https://tanstack.com/table/v8/docs/guide/pagination
