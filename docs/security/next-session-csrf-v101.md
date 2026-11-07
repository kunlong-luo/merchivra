# V101 — Next.js real Session/CSRF proxy gate (disposable CI)

This change upgrades the Next.js migration verification from a synthetic
browser fixture to a **real Spring Boot 4 + PostgreSQL 18 integration check**.
It does **not** switch production from Vite/Nginx to Next.

## Verified by CI

The existing `pg18-migrations` runner boots the real Spring Boot service with
Flyway on disposable PostgreSQL 18. After the existing V68 HTTP security
regression, it builds and starts Next.js 16.4.5 at `127.0.0.1:3355`,
rewriting `/api/:path*` to the same runner's Spring API at
`127.0.0.1:8080`. `scripts/smoke_next_session_csrf_v101.py` uses only
standard-library HTTP clients with independent cookie jars.

The test checks:

- Anonymous `auth/me` and operations reads remain 401.
- CSRF bootstrap supplies the expected server header name and token.
- Login without CSRF fails; login with CSRF grants an actual owner session.
- `JSESSIONID` survives Next's same-origin rewrite with HttpOnly and SameSite
  Strict; separate clients cannot see each other's authenticated session.
- Authenticated operations read works while write/logout without CSRF fails.
- Valid CSRF logout invalidates the session and subsequent protected reads.
- Authenticated HTML contains neither the logged-in tenant/user identifiers,
  the CSRF token nor the bootstrap secret; user data stays client-fetched.

The test asserts `MERCHIVRA_V101_DISPOSABLE_ONLY=true`, disables Seller
OAuth and remote SP-API and never accesses a non-loopback host. Credentials
exist only as CI disposable environment variables and are not printed.

## Still required before any production cutover

1. Validate HTTPS edge proxy, forwarded headers, secure cookie semantics,
   session-store configuration and CSRF origin protections in the target
   production network.
2. Verify OAuth callback URL/query redaction in ingress and application
   access logs; do not enable production Seller OAuth during this migration.
3. Exercise real tenant permissions in a dedicated staging environment,
   capture/refine the A+B screens and confirm the rollback/observability
   plan before removing the Vite fallback.
4. Keep GitHub Pages a public **synthetic read-only** demo. It is not a
   suitable host for a private authenticated ERP API or server runtime.
