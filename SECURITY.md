# Security Policy · Merchivra ERP

## 当前安全状态 / Current security status (2026-11-08)

**Pre-alpha. The authenticated ERP must not be exposed publicly or used with production Seller/Ads/bank data yet.** The [public GitHub Pages showcase](https://kunlong-luo.github.io/merchivra/) is separate, synthetic-only and not an ERP instance.

Security controls that **already have implementation and isolated Mock/CI coverage** include Spring Security Session + CSRF, first-run Owner bootstrap, tenant- and Marketplace-scoped business API checks, selected staff/read permissions, audit events, sensitive Seller OAuth callback/state handling, encrypted Seller credential vault and versioned key rewrap, and optional Redis 8 session/admission support. These statements describe implemented code and disposable test coverage **only**; they are not production certifications. See [local authentication](docs/security/local-auth.md), [Seller OAuth design and implementation](docs/security/seller-oauth.md), and [key rotation](docs/security/seller-key-rotation.md).

**Unverified or blocked for production**: real Amazon Seller/Ads OAuth role grants and revocation, live SP-API/Ads rate-limit behavior, secure external KMS/vault ownership and rotation, operator MFA/SSO and final RBAC sign-off, cross-instance identity/session invalidation, true Internet TLS ingress and forwarded-header review, penetration testing/least-privilege IAM, production backups/PITR/RPO/RTO, capacity/abuse tests, privacy/retention compliance, and business G1/G2/G3 approval. Do not interpret an existing vault table or Mock 403 test as an externally reviewed production security system. See the [release-readiness blockers](docs/testing/release-readiness-v68.md) and [active V108 release plan](docs/roadmap/mvp-freeze-release-v108.md).

The latest consolidated [V108 synthetic integration baseline](https://github.com/kunlong-luo/merchivra/actions/runs/42448160593) runs only on disposable data and proves nothing about a real seller's authorization.

## Reporting a vulnerability

Please **do not** open a public GitHub issue with exploit steps, access tokens, refresh tokens, buyer PII, account details, system addresses or private evidence. If enabled on this repository, use GitHub's **private vulnerability reporting** or Security Advisory workflow. Otherwise contact the maintainers privately via their GitHub profiles, with minimal sanitized details until a secure channel is established. Do not test accounts, infrastructure or data you do not control or have permission to assess.

## Seller credentials, personal data and testing

- `MERCHIVRA_SPAPI_ENABLED` and production OAuth/writes must remain **off by default**. Live Amazon/Ads credentials, tokens, financial statements and private order payloads must never enter Git, public Actions, synthetic fixtures or Pages assets.
- Preserve service-side tenant/Marketplace authorization, Session/CSRF checks and business audit records for every write. Frontend-visible actions or hidden buttons are not substitutes for server permission.
- Never log OAuth codes, refresh/access tokens, raw authorization headers, buyer addresses, unredacted requests, uploaded proof files or production exports. Redact source IDs where necessary.
- Store real secrets outside Git. Never apply disposable PG18 `pg_restore`, `flyway clean`, `dropdb` or `docker compose down -v` scripts to production or shared databases; [deployment precautions](docs/deployment/local-spark-db.md) apply.
- Keep original amount/currency, source provenance (`MOCK`, `MANUAL_UNVERIFIED`, `LIVE` when verified), and unknown inventory distinct. Do not manufacture facts from unavailable Amazon permissions.
- Before authenticating a real seller, require a reviewed HTTPS redirect/callback path, allowed scopes/roles, vault/KMS operations, revoke behavior and an authorized maintainer-local validation plan. Only separately approved operations may enable Amazon remote writes.
