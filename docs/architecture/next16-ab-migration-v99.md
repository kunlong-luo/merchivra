# V99 — Next.js 16.4.5 A+B implementation bridge

The production V98 React/Vite/Nginx application remains unchanged and fully
operational while the Next.js App Router migration is validated.

## What is implemented

- A genuine Next.js 16.4.5 App Router application in apps/web/app, reusing the
  existing session-protected Spring Boot business modules, data clients,
  TanStack Query, Orval hooks, Zod and 190+ existing source files.
- Real authenticated business workbench routes: /, /core, /orders,
  /catalog, /inventory, /purchasing, /ops, /finance, /team,
  /connections, /sync. App Router route entry selects a real business
  workspace; client-side navigation updates browser history.
- A+B design tokens based on the approved Penpot-compatible SVG source:
  navy #14243B, blue #315FEA, canvas #F6F8FC, stroke #E5EBF3,
  status colors, 8px rhythm, 14px cards and dense tables.
- Existing Spring Session/CSRF and same-origin /api requests via a
  private Next rewrites proxy. No hardcoded Seller secrets or real OAuth.
  Note the original backend business security checks remain authoritative.
- Separate npm scripts: dev:next, build:next, typecheck:next, start:next.
  The original npm run dev/build and GitHub Pages preview remain intact.
  This is a migration bridge, not a claimed production switchover.

## Validate in the repository

In apps/web, after npm ci:

    npm run typecheck:next
    npm run build:next
    MERCHIVRA_API_INTERNAL_URL=http://127.0.0.1:8080 npm run dev:next

Start the normal Spring Boot 4 + PG18 Compose backend with Seller OAuth/remote
writes DISABLED, then open http://127.0.0.1:3001. Full login uses the
original session endpoints. Do not consider API availability an Amazon
connected state. /finance is still Owner-gated; insufficient source data must
show unknown, not fabricated profit.

## Remaining before switching traffic

1. Run same-origin Session/CSRF/login/logout, backend API proxy and OAuth
   callback redaction tests under Next's reverse proxy. Production HTTPS
   ingress/session cookie/origin must be reviewed separately.
2. Verify all old UI modules/Orval generated clients; fix E2E smoke and
   inspect browser screenshots for all six key flows.
3. Convert internal App local-tab routing to idiomatic URL-based App Router
   navigation and preserve current table/filter state appropriately.
4. Migrate the isolated public Pages preview to Next static output: export.
   The ordinary authenticated ERP requires a Node runtime + Spring Boot +
   PostgreSQL 18 and MUST NOT be deployed as static GitHub Pages.
5. Only after checks are green, replace the production Vite/Nginx container.
   No real Amazon seller authorization until the maintainer locally enables
   and validates it.
