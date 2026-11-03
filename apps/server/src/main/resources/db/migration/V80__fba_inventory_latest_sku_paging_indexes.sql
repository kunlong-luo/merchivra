-- V88: latest source observation per Seller SKU, not a cached balance.
-- Distinct selection order: source observed_at DESC NULLS LAST,
-- ingestion timestamp DESC, immutable id DESC. A NULL source time must
-- NOT defeat an older genuinely observed time by arriving later.
CREATE INDEX fba_inventory_v80_latest_per_sku
 ON fba_inventory_observation (
   tenant_id,seller_marketplace_id,seller_sku_raw,
   observed_at DESC NULLS LAST,ingested_at DESC,id DESC);

-- Prefix filtering for a user's entire per-Marketplace SKU history.
CREATE INDEX fba_inventory_v80_lower_sku_prefix
 ON fba_inventory_observation (
   tenant_id,seller_marketplace_id,
   (lower(seller_sku_raw)) text_pattern_ops);
