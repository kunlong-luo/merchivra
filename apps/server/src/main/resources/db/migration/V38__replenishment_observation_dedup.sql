-- V38: do not let a second request key create another purchase draft
-- from the SAME FBA observation for the SAME tenant/marketplace/Seller SKU.
-- Re-ordering after cancellation requires a newer observation and fresh review.
CREATE UNIQUE INDEX erp_replenishment_draft_observation_unique
  ON erp_replenishment_purchase_draft_source
  (tenant_id,seller_marketplace_id,seller_sku,observation_id);
