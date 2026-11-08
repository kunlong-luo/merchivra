# V99 — Next.js 16.4.5 A+B implementation bridge

> **状态修订（V109 / 2026-11-08）**：本页记录 V99 初期 Next App Router 技术桥接及 V100 增量，**不应被误读为 Next 已正式切流**。目前项目继续以 Vite/Nginx 作为默认私有 ERP Web 交付，Next 16.4.5 的真实 App Router 可编译并通过合成 Chromium，但真实同源 Session/CSRF/敏感 OAuth 代理、Docker Node runtime、TLS 与 Vite 回退必须完成 [P0 #63](https://github.com/kunlong-luo/merchivra/issues/63) 后才允许正式切换。现行优先级见 [V108 MVP 计划](../roadmap/mvp-freeze-release-v108.md)。

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

## Automated checks (V99)

CI executes the original locked Vite/Orval/Vitest build, then `npm run
 typecheck:next` and `npm run build:next` using Next 16.4.5. It also starts
 a disposable Next production server on 127.0.0.1:3355 and verifies that /,
 /orders, /purchasing, /finance, /ops, /core and /connections serve HTTP 200,
 while an unknown module returns 404. This checks routing and production
 compilation only; it **does not** prove valid real Seller OAuth, session cookie
 forwarding, CSRF write flows, or finance authorization. Those remain explicit
 production migration blockers.

## V100 — Next.js native routes, real data and A+B refinement

- `src/lib/workbench-routes.ts` is the central typed route registry.
  The Next `app/[view]/page.tsx` validates only known views; unknown
  nested paths yield 404, not arbitrary API or business handlers.
- `app/portal.tsx` now uses Next `useRouter`/`usePathname` to update
  navigation without directly mutating browser history. Back, forward and
  hard refresh must restore the selected module. The legacy Vite build
  retains its original local tab behavior.
- **Important provenance correction:** Next `/orders`, `/catalog` and
  `/inventory` are real tenant-scoped `CoreWorkspace` sections, not the
  separate legacy Vite Mock Gateway. The app retains actual roles, source
  quality, known-zero vs UNKNOWN, and backend paging.
- V100 A+B CSS adds sectioned sidebar, consistent selected tabs, data-table
  rhythm, operator-focused evidence note and a mobile scrollable nav strip,
  scoped to the Next preview only.
- Chromium V100 tests exercise synthetic (only fixture) Core order/inventory
  data and assert Next URL transitions, back/forward, hard refresh, no
  unexpected Mock Gateway banner, keyboard and small-screen layouts.

Remaining cutover gates are unchanged: verify true Session/CSRF and reverse
proxy with non-synthetic backend, review OAuth callback redaction/HTTPS
forwarded headers, validate operator role restrictions and visually review
all key workbenches. GitHub Pages still runs only the Vite static public
showcase; Next full ERP does **not** run on Pages.
