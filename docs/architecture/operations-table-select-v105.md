# V105 — mature filters and data table: operations findings

## Decision and boundary

The main A+B ERP layout stays intact. The operations exceptions table is
the first place where an actual column model materially helps without
reimplementing an advanced grid. Adopt **Base UI 1.9.0 Select** and a
**pinned, production-established TanStack Table v8.21.3**. TanStack v9
has a different API; avoid a repository-wide major-version migration during
the Next transition. Native inputs remain appropriate for simple controls.

## Implemented

- `src/components/OpsFindingStatusFilter.tsx` is a controlled Base UI
  Select with visible label, keyboard typeahead/focus, trigger and popup
  semantics, and A+B appearance. Selecting ACTIVE, ACTIONABLE or ALL resets
  the server cursor stack to page one and re-issues the **real Spring
  filter query**. It never filters an arbitrary 50-row slice in memory.
- `src/components/OpsFindingsTable.tsx` uses TanStack's stable `getRowId`
  from the original UUID, `getCoreRowModel`, and explicit
  `manualPagination/manualSorting/manualFiltering`; backend priority order
  and opaque next/previous cursors are authoritative. There are no fake
  page numbers or client-only rank/sort controls.
- Native semantic `table/th/td` and keyboard-focusable horizontal scroll
  region stay; optional source key, last-seen and owner/reminder columns are
  show/hide controls. Severity, rule, MOCK/UNVERIFIED/LIVE provenance,
  state and operations remain visible, independent of column choices.
- Owner assignment, FBA capability checks, Spring authorizations,
  TanStack Query updates and actions remain unchanged.
- Chromium synthetic smoke asserts default 8-column table, Base UI
  filter actually requests `ACTIONABLE` from the local server, optional
  source key hide/restore, stable row evidence, and 360px viewport behavior.

## Non-goals

No date picker has been introduced: this screen only displays a source
timestamp, and a fake date filter on an incomplete page would be dangerous.
A date-range filter requires a real, permission-scoped backend query.
No arbitrary client sorting is added: the server prioritizes urgent
exceptions across the *whole* tenant result set. This also prevents
misleading “all results” claims when only a single page is loaded.

No new authorization flow or live Amazon OAuth/SP-API writes.

References:
- https://base-ui.com/react/components/select
- https://tanstack.com/table/v8/docs/guide/pagination
- https://tanstack.com/table/v8/docs/api/core/table
