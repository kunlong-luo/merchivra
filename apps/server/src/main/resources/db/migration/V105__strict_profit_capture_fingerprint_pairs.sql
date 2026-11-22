-- V194 hardening / Flyway V105: close the V104 SQL NULL/UNKNOWN loophole.
-- PostgreSQL CHECK accepts UNKNOWN, not only TRUE. The original V104
-- (version=1 AND hash ~ ...) branch can become UNKNOWN if one field is NULL,
-- so a partially populated captured order or SKU could have been accepted.
-- The new explicit IS NOT NULL checks make every partial pair FALSE, never
-- UNKNOWN. Historical (NULL,NULL) rows remain legacy and are NOT upgraded.
-- Do not repair suspect rows silently: fail migration if invalid rows exist.
-- No data updates, no Amazon/Ads/bank authorization or profit posting.
ALTER TABLE finance_profit_report_order
  ADD CONSTRAINT finance_profit_report_order_capture_pair_strict_v105 CHECK (
    (capture_version_v194 IS NULL AND capture_sha256_v194 IS NULL)
    OR
    (capture_version_v194 IS NOT NULL
      AND capture_sha256_v194 IS NOT NULL
      AND capture_version_v194 = 1
      AND capture_sha256_v194 ~ '^[0-9a-f]{64}$')
  );
ALTER TABLE finance_profit_report_sku
  ADD CONSTRAINT finance_profit_report_sku_capture_pair_strict_v105 CHECK (
    (capture_version_v194 IS NULL AND capture_sha256_v194 IS NULL)
    OR
    (capture_version_v194 IS NOT NULL
      AND capture_sha256_v194 IS NOT NULL
      AND capture_version_v194 = 1
      AND capture_sha256_v194 ~ '^[0-9a-f]{64}$')
  );
