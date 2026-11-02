-- V72 (product V74): V24 LIVE Catalog and FBA read-only workers already write
-- data_origin='LIVE', but V5's original MOCK-only constraints were never widened.
-- Preserve explicit allowed provenance; never turn UNKNOWN into OBSERVED or NULL into zero.
-- Mirrors the V11 widening for Orders and OrderItems. No data backfill.
ALTER TABLE marketplace_listing
  DROP CONSTRAINT marketplace_listing_data_origin_check;
ALTER TABLE marketplace_listing
  ADD CONSTRAINT marketplace_listing_data_origin_check
  CHECK (data_origin IN ('UNVERIFIED','MOCK','LIVE'));
ALTER TABLE fba_inventory_observation
  DROP CONSTRAINT fba_inventory_observation_data_origin_check;
ALTER TABLE fba_inventory_observation
  ADD CONSTRAINT fba_inventory_observation_data_origin_check
  CHECK (data_origin IN ('UNVERIFIED','MOCK','LIVE'));
