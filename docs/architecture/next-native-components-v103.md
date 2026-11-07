# V103 — mainstream enterprise shell with mature UI primitives

## Decision

The existing A+B desktop hierarchy (grouped left sidebar, header, status,
high-density data tables) remains a conventional task-focused ERP layout.
We do **not** replace it with a generic landing-page dashboard.

Next.js App Router supplies navigation/layout APIs, **not** a built-in
commercial Sidebar, Modal, Data Table or design system. For interaction
primitives, follow the current shadcn/ui direction and use stable
`@base-ui/react` (Base UI) directly while preserving our A+B CSS. This avoids
introducing Tailwind just to rewrite the 190+ operational React modules.
Use `lucide-react` for a consistent, tree-shakeable icon set instead of
handwritten unicode glyphs.

## Implemented in this stage

- Next-only `NextWorkbenchNavigation` displays grouped owner-aware modules,
  real HTML deep links and professionally consistent Lucide icons.
- Mobile navigation switches from a long horizontal link strip to a Base UI
  modal drawer with keyboard focus management, Escape, scroll locking, focus
  return, semantic title/description, visible close control and backdrop.
- Core business routes, Orval hooks, pagination, Source provenance, auth
  session and Spring authorization remain unchanged.
- Vite fallback retains the original shell, and GitHub Pages remains a
  separate synthetic read-only showcase.
- Chromium synthetic browser smoke now verifies a mobile open/close cycle and
  a real `/orders` deep link, in addition to existing route/data assertions.

## Component boundaries and next phases

| Purpose | Standard |
| --- | --- |
| App Router and URLs | Next.js built-in router, real `a href` |
| Mobile modal and complex overlays | Base UI React |
| Icons | Lucide React |
| Basic inputs, labels, buttons, links | Native HTML with A+B tokens |
| Advanced multi-column grids | Evaluate TanStack Table for large modules |
| Existing API and source trust | Orval + TanStack Query + Zod + Spring |

Do **not** replace accessible native HTML for trivial controls. Do **not**
rewrite all business tables at once: retain server-side filtering/cursors and
unknown-vs-zero/financial evidence semantics when migrating each table.

A Next preview is still not production cutover. Preserve the V101
Session/CSRF tests, OAuth callback/HTTPS security blockers, and Vite rollback.
References:
- https://base-ui.com/react/components/dialog
- https://ui.shadcn.com/docs/components/base/sidebar
- https://lucide.dev/guide/react/
