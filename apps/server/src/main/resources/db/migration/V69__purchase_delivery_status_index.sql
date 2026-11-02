-- V72 read-only delivery board over existing V20/V43/V45/V46 facts.
-- No new source-of-truth state, no inferred carrier delivery or Amazon FBA stock.
CREATE INDEX erp_purchase_order_delivery_v69
  ON erp_purchase_order(tenant_id,updated_at DESC,id DESC)
  WHERE status IN ('APPROVED','RECEIVING','RECEIVED');
