# V129 — V52 private proof original download audit (P0 hardening)

Date: 2026-11-09. Main baseline prior to change: `c18ef3caf2d8e05edbbcf673dd22ec1f21a39ce0`, [V128 full A3 #42628915049](https://github.com/kunlong-luo/merchivra/actions/runs/42628915049) **success** for that immutable SHA.

## Finding

V52 original attachment upload already checks Owner session and CSRF, tenant/proof binding, server-calculated SHA-256, maximum 2 MiB, immutable original bytes and safe no-store/attachment download headers. However, downloading an original only read the file table: it created **no durable per-access audit**, so there was no independent trail of who retrieved a particular original.

## Change

- Forward-only PG18 Flyway `V84__private_lot_proof_download_audit.sql` records each successful original retrieval with tenant, proof, original file, authenticated Owner UUID, original server checksum and database time; it does **not** store or log file bytes, filenames, cookies, access tokens or request headers.
- A composite `(tenant_id, proof_id, file_id)` FK connects every audit row to that **same** V52 original (not another file within the same tenant), and `(tenant_id, downloaded_by)` FK verifies a tenant membership exists. Ordinary application code exposes no update/delete method for the audit table.
- Download now calculates/verifies length and digest, inserts an access event **inside the same non-read-only transaction**, and only returns the private byte array on exactly one successful audit insert. Missing, corrupted or cross-tenant originals, absent identity and audit failures fail closed. Spring's transaction interceptor commits before the controller builds the file response; an SQL/commit failure cannot release the file. Service takes the actor from the authenticated session, not a user-supplied body/query value.
- Existing V52 file bytes and metadata are not modified or copied. A GET metadata lookup is intentionally not considered an original-file download and is not logged as one. Audit entries demonstrate **internal access**, never supplier/third-party authenticity or a legal signature.

## Validation and remaining gates

- JUnit/Mockito covers successful actor-scoped audit insert, zero inserted rows as a failure, checksum tamper and absent/cross-tenant identity; the controller continues to require Owner-scoped `currentTenant`.
- Real PG18 full A3 verifies applied Flyway V84 and composite unique source key in addition to all existing HTTP/Chromium/Compose checks. **Do not claim the new migration has been tested until the full main-SHA run has actually passed.**
- Operational hardening **still open**: real target read/download activity monitoring, separate least-privilege database roles, retention/deletion legal policy, encrypted backups, malware screening or a reviewed scanning/quarantine gateway, external authenticity and supplier acknowledgment, and positive/negative multi-tenant HTTP evidence with actual uploaded files. Neither Amazon Seller credentials nor real private documents belong in CI.

**Release gate:** synthetic success is not production approval; Issue #63 remains open until real TLS/secrets/role/cookie/rollback evidence and maintainer authorization.
