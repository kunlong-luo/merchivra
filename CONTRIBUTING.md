# Contributing to Merchivra

Thank you for contributing! / 欢迎参与贡献！

1. Check existing Issues; propose new features before implementing large changes.
2. Fork or create a feature branch from `main`, e.g. `feat/amazon-sync`.
3. Keep PRs focused; document schema changes and add tests.
4. Run backend tests with `mvn -f apps/server/pom.xml test` and frontend checks with `cd apps/web && npm install && npm run build`.
5. Open a PR describing scope, verification steps and any API or migration implications.

Use conventional commit subjects (`feat:`, `fix:`, `docs:`, `chore:`).
Never put Amazon credentials or customer data in issues, PRs, examples or logs.
Security reports should follow [SECURITY.md](SECURITY.md), not public issues.
