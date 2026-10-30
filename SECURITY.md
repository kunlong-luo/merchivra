# Security Policy

## Current status
Merchivra is pre-alpha and **not suitable for production or public exposure**. Authentication, authorization, data isolation, encryption-at-rest policies and rate limiting are still planned.

## Reporting a vulnerability
Do not open public GitHub issues containing exploit details or secrets. Use the repository's private vulnerability reporting feature if enabled, or contact the maintainers privately through their GitHub profile.

## Handling seller data
- Never commit SP-API client secrets, refresh/access tokens or customer PII.
- Store secrets outside Git; future integrations must encrypt sensitive credentials and use least-privilege access.
- Never log tokens, order address fields, unredacted API payloads or production exports.
- Follow applicable Amazon SP-API terms, data-protection requirements and retention limits.
- Validate tenancy and permission boundaries before enabling multi-user production use.
