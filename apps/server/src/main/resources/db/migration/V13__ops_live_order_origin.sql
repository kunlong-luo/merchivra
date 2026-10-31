-- V13: V11C read-only LIVE Orders source is a real provenance value.
-- Findings remain advisory; never treat LIVE alone as finance settlement.
ALTER TABLE ops_finding DROP CONSTRAINT ops_finding_data_origin_check;
ALTER TABLE ops_finding ADD CONSTRAINT ops_finding_data_origin_check
    CHECK(data_origin IN ('MOCK','UNVERIFIED','LIVE'));
