# Security Policy

## Current status
Merchivra is pre-alpha and **not suitable for production or public exposure**. Basic local Session/CSRF login, BCrypt credentials, first-run Owner bootstrap, and a limited session-scoped account read endpoint now exist. Complete RBAC, auditing, credential encryption, brute-force controls, multi-instance session handling, verified DB-level tenancy and production hardening remain incomplete. See [local auth](docs/security/local-auth.md).

## Reporting a vulnerability
Do not open public GitHub issues containing exploit details or secrets. Use the repository's private vulnerability reporting feature if enabled, or contact the maintainers privately through their GitHub profile.

## Handling seller data
- Never commit SP-API client secrets, refresh/access tokens or customer PII.
- Store secrets outside Git; future integrations must encrypt sensitive credentials and use least-privilege access.
- Never log tokens, order address fields, unredacted API payloads or production exports.
- Follow applicable Amazon SP-API terms, data-protection requirements and retention limits.
- Validate tenancy and permission boundaries before enabling multi-user production use.
