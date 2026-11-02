-- V74 read-only advisory joins scoped to the exact replenishment-generated Seller SKU.
-- Local purchase orders are NEVER assumed to be FBA in-transit or available stock.
CREATE INDEX erp_replenishment_priority_source_v71
  ON erp_replenishment_purchase_draft_source (tenant_id,seller_marketplace_id,seller_sku,purchase_order_id);
