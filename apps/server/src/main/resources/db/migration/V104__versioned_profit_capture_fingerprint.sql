-- Product V194 / Flyway V104: versioned local capture fingerprints.
-- Historical V62 extract_sha256 remains an UNVERSIONED local digest; its
-- original construction is not independently verifiable from frozen rows.
-- DO NOT backfill historical rows or claim real Amazon/Ads/bank provenance.
ALTER TABLE finance_profit_report_order
  ADD COLUMN capture_version_v194 SMALLINT,
  ADD COLUMN capture_sha256_v194 CHAR(64),
  ADD CONSTRAINT finance_profit_report_order_capture_pair_v104 CHECK (
    (capture_version_v194 IS NULL AND capture_sha256_v194 IS NULL)
    OR
    (capture_version_v194=1
      AND capture_sha256_v194 ~ '^[0-9a-f]{64}$')
  ),
  ADD CONSTRAINT finance_profit_report_order_pending_v104 CHECK (
    state<>'PENDING' OR
    (capture_version_v194 IS NULL AND capture_sha256_v194 IS NULL)
  );
ALTER TABLE finance_profit_report_sku
  ADD COLUMN capture_version_v194 SMALLINT,
  ADD COLUMN capture_sha256_v194 CHAR(64),
  ADD CONSTRAINT finance_profit_report_sku_capture_pair_v104 CHECK (
    (capture_version_v194 IS NULL AND capture_sha256_v194 IS NULL)
    OR
    (capture_version_v194=1
      AND capture_sha256_v194 ~ '^[0-9a-f]{64}$')
  );
-- The V103 UPDATE/DELETE guards protect the new columns too. A direct SQL
-- legacy-style capture remains classified UNVERIFIED, never granted profit.
-- No UPDATE of existing rows, no defaults, no external authorization.
