-- V23: source order items are only observed when v2026 Orders supplied them.
-- Missing orderItems != empty; pending orders may have missing proceeds.
ALTER TABLE sales_order
  ADD COLUMN items_observation VARCHAR(24) NOT NULL DEFAULT 'NOT_OBSERVED'
    CHECK(items_observation IN ('NOT_OBSERVED','OBSERVED')),
  ADD COLUMN items_observed_at TIMESTAMPTZ;
ALTER TABLE order_item
  ADD COLUMN source_updated_at TIMESTAMPTZ,
  ADD COLUMN is_current BOOLEAN NOT NULL DEFAULT true;
CREATE INDEX order_item_detail_idx ON order_item(tenant_id,sales_order_id,external_item_id);
