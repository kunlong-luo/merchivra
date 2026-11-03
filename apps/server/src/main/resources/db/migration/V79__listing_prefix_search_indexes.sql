-- V87: accelerate server-side case-insensitive Listing prefix lookups.
-- Reuse the canonical marketplace_listing table and its unique
-- (tenant_id,seller_marketplace_id,seller_sku) constraint; no new cache/table.
-- text_pattern_ops supports btree prefix LIKE on the normalized expression.
CREATE INDEX marketplace_listing_v79_sku_prefix
 ON marketplace_listing (
   tenant_id,seller_marketplace_id,(lower(seller_sku)) text_pattern_ops,id);

CREATE INDEX marketplace_listing_v79_asin_prefix
 ON marketplace_listing (
   tenant_id,seller_marketplace_id,(lower(asin)) text_pattern_ops,id)
 WHERE asin IS NOT NULL;
