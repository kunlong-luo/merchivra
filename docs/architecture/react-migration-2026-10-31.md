# ADR · React 19 migration (2026-10-31)

**Decision: React-only frontend. No Vue runtime, Vue SFC, Vue Query, vue-tsc or @vitejs/plugin-vue.**

User explicitly selected React over Vue. This reverses prior Vue-specific architectural decisions. Historical acceptance logs referring to Vue remain valid as evidence of the previous version, but must not be interpreted as the current stack.

## Current stack
- **Java 25 LTS + Spring Boot 4.1.2 + Spring Security 7 + PostgreSQL 18 + Flyway** (unchanged).
- **React 19.3 + React DOM 19.3**, **TypeScript 6.0.3**, **Vite 8.3.5**, **@vitejs/plugin-react 6.1**, Node 24 LTS.
- TanStack **React** Query 5.105, Zod 4.6, Vitest 5, npm **locked** via committed `apps/web/package-lock.json`; `npm ci` remains mandatory in CI and Docker.
- Existing CSS design system retained; no forced Tailwind migration, React Router, Redux or global state library.
- Spring Session/CSRF, server-side RBAC and Amazon SP-API safety gates stay on the backend. React UI visibility never replaces them.

## Migrated product flows
The React TSX implementation replaces the Vue SFC views for local login and one-time invitation activation, authorized core Marketplace/Listing/Orders/FBA, local Seller Setup, Mock Sandbox and page checkpoint, Orders MOCK/LIVE checkpoint panel, operations findings/triage, team OWNER/OPERATOR_READ and reviewer access, and local demo overview/orders/listing/inventory/sync.

## Regression contract
Run `npm ci && npm run typecheck && npm run test && npm run build`; install Chrome and execute `scripts/smoke_browser_layout.mjs` against the **React build**. Verify 360/390/768/1280 px layouts, anonymous login, staff-only navigation, tenant core data, orders/inventory UNKNOWN vs 0, MOCK labels, operations/teams, and LIVE-gated status. PostgreSQL 18 full-chain tests must still pass. No synthetic test proves real Amazon LIVE connectivity.

## Dependency safety
The former Vue package lockfile must be regenerated through actual npm resolution, not hand-edited or given fake integrity hashes. The one-time React lockfile workflow installs the React dependency graph using Node 24 and commits the resulting lockfile. Do not leave the repository with `package.json` out of sync with `package-lock.json`.

React is not a reason to change Maven, Spring JDBC or the durable transaction/lease model.
