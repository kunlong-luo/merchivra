# V138 — V51/V52 proof original retention, legal hold, storage and no-delete disposition review

Baseline: `main` `cd983062a1f0026f4c1a114f72b8724dbf71e0be`, [V137 A3 #43156601839](https://github.com/kunlong-luo/merchivra/actions/runs/43156601839) **7/7 success**, [main CI #43156601655](https://github.com/kunlong-luo/merchivra/actions/runs/43156601655) and [Redis 8 Session #43156601726](https://github.com/kunlong-luo/merchivra/actions/runs/43156601726) **success**. V138 requires its OWN exact merged-main SHA A3 before technical acceptance.

## P0 governance gap and scope

V52 immutable original PDF/PNG/JPEG bytes (max 2 MiB) already have tenant Owner access, uploaded-by, SHA-256 checks, a V129 durable successful-download audit, and V137 fail-closed ClamAV scanning for new uploads and old downloads. But there was NO local retention/hold status, no tenant file capacity visibility and no reviewed legal disposition queue. Inventing an automatic expiration rule or invoking a generic `DELETE` under an unset legal policy would be unsafe.

V138 is an explicit **no-delete governance stage**. There are NO binary deletion endpoints, background cleanup jobs, scheduled purges, table `DELETE`, or privileges granted to an operator to erase files. No automatic deletion even when internal disposal review says `REVIEWED_NO_DELETE`. This state is **NOT authorization for a future batch purge**. Real policy, legal holds, backup retention, right-to-erasure obligations and any physical deletion command require a separate explicitly approved project and operational signoff.

## Schema: forward-only PostgreSQL 18 Flyway V88

- `erp_lot_proof_file_governance` is one tenant/proof/file-bound row for each file whose policy has been acted upon, with composite FK to the actual existing immutable original and membership-bound requester/approver IDs. Existing V52 originals **remain unchanged**, with a deliberate default of **unknown/unapproved retention** and **not eligible for disposal**. Missing governance rows are not mistaken for approved policy.
- `legal_hold` can be set immediately by a current Owner. A second, different Owner must approve a requested release; while pending, hold stays effective. Setting a hold invalidates any previously reviewed or pending disposal request. This does not represent a court order or legal counsel's independent determination, only locally recorded policy workflow.
- `retention_proposed_until` and proposer are separate from `retention_until`. A second Owner who is neither proposer nor original uploader must approve the proposal before any retention date becomes the internal reviewed value. The proposal is a timezone-aware ISO-8601 timestamp in the future, at most 100 years away; updating a reviewed date can only **extend**, not silently shorten, retention. Changing retention invalidates pending disposal review.
- `disposal_state`: `NONE`, `REQUESTED`, `REVIEWED_NO_DELETE`. Only a **not held**, internally approved retention that is **actually past its expiry in the database** may be requested for review. A different Owner (not requester/uploader) may record `REVIEWED_NO_DELETE`, and neither transition mutates or deletes the original file. Review is intentionally separate from physical deletion eligibility and never a file purge permit.
- `erp_lot_proof_file_governance_audit`: append-only application events for each state transition with tenant+proof+file, authenticated Owner actor, printable reason and optional retention candidate. **No binary, filename, session tokens or private file contents** are recorded. Audit failure stops the entire transaction and returns no successful governance response. Existing V129 access audit and source SHA remain independent.
- Transactions use tenant `SELECT ... FOR UPDATE` serialization before state changes, then row update and one audit insert in the same Spring transaction. DB composite FKs prevent cross-tenant or wrong-proof governance metadata.

## API and frontend

Owner-only and no-store:
- `GET /api/v1/purchasing/lot-proof-governance/{proofId}` returns current file state, or 404 for missing/other-tenant file; never bytes.
- `GET /api/v1/purchasing/lot-proof-governance/capacity` returns tenant-wide **count of original files, SUM(size_bytes), legal holds, unset retention, pending proposals and reviewed no-delete disposition**. Size is *original payload length*, **not** PostgreSQL TOAST/index/backups or legal storage quota.
- `POST /api/v1/purchasing/lot-proof-governance/{proofId}/commands` requires existing server Owner session and CSRF, 10–500 character rationale, known action and exact optional date. Actions: `PLACE_HOLD`, `REQUEST_HOLD_RELEASE`, `APPROVE_HOLD_RELEASE`, `PROPOSE_RETENTION`, `APPROVE_RETENTION`, `REQUEST_DISPOSAL`, `REVIEW_DISPOSAL`. No method accepts a file deletion request or raw SQL command.
- The V52 React workbench reuses existing session-aware `lot-evidence-files.ts` API flow, Zod validation and TanStack Query tenant/proof keys to display governance, capacity, safe action choices and explicit **no deletion** notice. It never presents `REVIEWED_NO_DELETE` as actual deletion.

## Validation

- JUnit: missing/invalid policy and action reject before DB; old files have `retentionUntil=null` and `disposalState=NONE`; immediate hold revokes disposition; same-person release/retention/disposal review forbidden; uploader cannot approve retention/disposal; unapproved/held/unexpired source cannot request disposal; review changes metadata only, not file/stock; fail-closed audit event.
- Vitest: tenant-wide capacity parsing and no-store, Owner/CSRF governance commands, no `DELETE`, rejects inconsistent capacity or unsupported disposition state.
- Real PostgreSQL 18 + Spring HTTP: `scripts/smoke_proof_governance_v138.py` uses an explicitly synthetic V95 action and fictional V50/V51/V52 metadata/file fixture in the **disposable A3 database** (not production). Checks anonymous 401, missing proof 404, default retention unset, disposal 409, immediate hold, pending release, self-approval 409, retention proposal, independent approval required, tenant capacity, 3 event audit rows, original SHA unchanged, signed stock unchanged. These fictitious test rows are disposed of when the ephemeral CI database is destroyed; the script calls **no file DELETE or stock update**. Full existing PG18 HTTP, 12k/6k query-plan, Chromium, Next, Compose and backup/restore tests continue.
- **No real legal policy, automatic deletion, production ClamAV daemon, or real tenant data** is used in CI. This only validates the software guardrails and synthetic workflow.

## Still open for G1–G3 production approval

Legal counsel/records owner must define per-document retention rules, conflicts among jurisdictions, litigation hold authority, erasure requirements, and backup/PITR deletion handling. A target operator must provision actual isolated clamd with up-to-date signatures, capacity alerts (disk/TOAST/index/backups not counted in this UI), encrypted backup/key rotation, recovery/rollback, IAM/least privilege and real HTTPS trusted proxy, and validate production-scale concurrency, lock waits and audit immutability/WORM storage as applicable.

An app-level owner review and an audit insert do not create legal retention policy, guarantee audit immutability against DB administrators, or authorize any real Amazon Seller/Ads/3PL connectivity. Keep #62/#63 OPEN and release disabled.
