# Contributing to Merchivra / 贡献指南

Merchivra is **pre-alpha**, self-hosted and not production-approved. Before making code or documentation changes, read the [current docs hub](docs/README.md), [V108 P0/MVP plan](docs/roadmap/mvp-freeze-release-v108.md), [CI cadence](docs/testing/ci-policy.md) and [agent rules](AGENTS.md). Older Vxx design/roadmap notes may be historical; do not copy outdated Vue/Maven/PG17/JWT guidance into current instructions.

## Recommended contribution steps

1. Check existing issues first (including [P0 purchasing/warehouse pagination #62](https://github.com/kunlong-luo/merchivra/issues/62) and [Next.js production cutover #63](https://github.com/kunlong-luo/merchivra/issues/63)). Prefer completing MVP blockers over unrelated scope expansion.
2. Use a short-lived branch from `main` with a focused PR. Include actual file/API changes, risk boundary, tests run, and any remaining unverified conditions; update appropriate docs and the [audit/index](docs/governance/docs-audit-v109.md).
3. For server changes use **Java 25 / Gradle Wrapper / Spring Boot 4.1.2 / PostgreSQL 18**; never add Maven or alter old Flyway migrations. For frontend changes use **React 19 / Next 16 App Router preview + Vite fallback**, Node 24, locked npm dependencies and existing Orval/Zod/TanStack/Base UI.
4. Run relevant fast tests from repository root:

   ```bash
   (cd apps/server && ./gradlew test)
   (cd apps/web && npm ci && npm run api:check && npm run typecheck && npm run test && npm run build && npm run typecheck:next)
   python3 scripts/check_ci_cadence_v108.py
   python3 scripts/check_docs_consistency_v109.py
   ```

5. Ordinary PRs trigger the **fast CI** only. For changes to tenant/role authorization, Seller secrets/OAuth, Session/CSRF, procurement approvals, inventory writes, money/FX or synchronization, immediately run focused security/integration tests. For cross-database or milestone candidates, run the [consolidated full validation workflow](https://github.com/kunlong-luo/merchivra/actions/workflows/milestone-validation.yml) on the target `main` commit. Record the tested SHA; a prior successful run does not validate later code.
6. When adding an API endpoint, follow existing Session/CSRF, tenant isolation, original-currency/source semantics and mature Springdoc/Orval contract generation; do not treat the [historic G2 design JSON](docs/api/README.md) as live production schema.

Use concise commit messages (`feat:`, `fix:`, `docs:`, `test:`). Keep all real Amazon, Ads, buyer and bank data out of Git, public Actions, PRs, examples, logs and Pages assets. For vulnerabilities follow [SECURITY.md](SECURITY.md), never public issues with exploit details.

## Documentation ownership and status

The [V109 docs audit](docs/governance/docs-audit-v109.md) separates **CURRENT** documentation from **historical design snapshots**. Keep authoritative entrypoints and relative links accurate; preserve older dated evidence rather than silently claiming its unimplemented features are still missing, or claiming its mocked tests approved production.
