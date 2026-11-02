-- V67 / product V70: indexes for bounded 7/28-day per-Marketplace
-- historical OrderItems observations (not a forecast materialization table).
-- Only current item rows count; historical superseded rows never double count.
CREATE INDEX sales_order_demand_window_v67
  ON sales_order(tenant_id,seller_marketplace_id,order_time DESC,id)
  INCLUDE (data_origin,items_observation);
CREATE INDEX order_item_current_sku_demand_v67
  ON order_item(tenant_id,seller_sku_raw,sales_order_id)
  INCLUDE (quantity,data_origin)
  WHERE is_current=true;
