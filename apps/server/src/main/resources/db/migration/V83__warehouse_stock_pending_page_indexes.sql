-- V113: read-path indexes for complete local stock and pending purchase line pages.
-- Stock movements already have (tenant_id,warehouse_id,product_sku_id) from V39.
-- Unique tenant+order_number is retained; status filter benefits from this index.
CREATE INDEX erp_purchase_order_pending_page_idx
  ON erp_purchase_order(tenant_id,status,order_number,id)
  WHERE status IN ('APPROVED','RECEIVING');
CREATE INDEX erp_receipt_batch_stock_keys_idx
  ON erp_receipt_quality_batch(tenant_id,warehouse_id,product_sku_id);
