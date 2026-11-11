# V137 — Fail-closed ClamAV scanning for V51/V52 private originals

Baseline: `main` `f5c0d4a90c708493f394d319bd470d7e57095a0d`, [V136 full A3 #43010550542](https://github.com/kunlong-luo/merchivra/actions/runs/43010550542) **success 7/7**, [main CI #43010550442](https://github.com/kunlong-luo/merchivra/actions/runs/43010550442) **success**. This V137 is a **new** candidate, not accepted until its own full exact SHA A3 has passed.

## Actual risk

V52 immutable original PDFs, PNGs and JPEGs were validated for max size, file magic, original SHA-256, tenant/Owner and one-time file proof binding, but **no malware screening was performed**. V129 added durable per-successful-download audit but did not distinguish known-malicious or unscanned bytes. SHA-256 and file magic do not establish a file is safe to open.

## Implemented boundary

- Introduce Spring `LotProofAvScanner`, a bounded no-dependency client for the **mature ClamAV `clamd` INSTREAM protocol**, not a homegrown antivirus engine. It sends `zINSTREAM\0`, a sequence of 32 KiB binary chunks preceded by 4-byte network-order lengths, terminates with a zero-length chunk, and accepts **only** `stream: OK` as a clean result. `... FOUND` denies with HTTP 422; daemon errors, incomplete, oversized or malformed replies and connection failures refuse with HTTP 503. Host is resolved once and must be loopback/private; public-routable scanner addresses are rejected. Original bytes, signature names, scanner IPs and file contents are not logged or returned to the browser.
- The V52 `upload` operation scans **the actual bytes before acquiring tenant locks and before the immutable BYTEA insert**. If scanning fails, it never writes a new original. The V52 `download` operation checks stored SHA-256 and length and **rescans even pre-V137 originals before** V129 logs a successful access and returns any bytes. Unknown/failed screening blocks download; successful access audit remains mandatory. This avoids silently trusting historical unscanned originals without needing to backfill an unjustified "CLEAN" status.
- `merchivra.evidence.av-required` defaults false, but with Demo OFF that mode **denies original upload/download**. The *only* bypass is explicit `MERCHIVRA_DEMO_ENABLED=true` for fictional local/demo evidence. The production Compose overlay **forces `MERCHIVRA_EVIDENCE_AV_REQUIRED=true`**, sets `MERCHIVRA_EVIDENCE_AV_HOST` and optional internal port, and **fails startup if the host is empty**. Application always denies new private binary transfer if required scanner unavailable.
- `scripts/check_release_config_v68.py` verifies production scanning cannot be disabled and scanner TCP is not published as a Compose port. The bundled Demo remains explicit, never claimed to be malware-screened or used for production.
- JUnit/Mockito tests confirm scanner error prevents **any DB access/insert** on upload, clean scan precedes immutable upload, and infected legacy originals never get downloadable bytes or successful download audit.
- A local **fake clamd TCP server** unit test validates actual byte framing across multiple chunks and exact clean/`FOUND`/`ERROR`/unknown/offline verdict handling, synthetic-only bypass, missing production host and invalid port. **This mock protocol test is not a real ClamAV signature-database or production-network acceptance test.**

Official ClamAV references: [clamd protocol](https://docs.clamav.net/manual/Usage/ClamdProtocol.html), [daemon network security](https://docs.clamav.net/manual/Usage/Scanning.html). ClamAV explicitly notes its TCP daemon traffic is **not encrypted or authenticated**. Operate clamd only on a dedicated private network reachable by the Spring container, restrict source/destination network policy, and provide operationally refreshed signature databases before enabling real private documents. Never publish TCP 3310 to the Internet. Hostname and approved network controls remain target-specific.

## Acceptance and unresolved production gates

PR quick CI must pass Java 25 scanner+service unit tests, React/Next and Compose static fail-closed config. One exact-SHA post-merge full A3 must still pass all true PG18/Flyway/HTTP, Java, Chromium and recovery tests; CI uses fictional Demo evidence and **does not** claim real ClamAV engine was started.

**Production remains blocked** until operations provide an isolated actual clamd instance with current signatures and demonstrate clean sample acceptance, known detection rejection, scanner-down denial, private network reachability, full TLS/session/CSRF and target audit/backup/PITR/rollback. A legal/data-governance retention and deletion policy (with holds and backups), database encryption/least-privilege role, and any external signature/carrier authenticity must be reviewed separately. Neither this malware screening nor SHA-256 proves documentary provenance.

No live Amazon OAuth, FBA/3PL writes or real proof bytes are used in GitHub Actions. Full production authorization continues to require maintainer approval; keep Issues #62 and #63 OPEN.
