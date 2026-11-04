-- V92: local-only FBA shipment history (not Amazon-confirmed inbound).
-- Preserve old V63 API/query. Index the new bounded keyset query and
-- case-insensitive literal-reference prefix; never store a second shipment copy.
CREATE INDEX fba_inbound_shipment_v81_recent_keyset
 ON fba_inbound_shipment(tenant_id,seller_marketplace_id,created_at DESC,id DESC);
CREATE INDEX fba_inbound_shipment_v81_reference_prefix
 ON fba_inbound_shipment(
  tenant_id,seller_marketplace_id,
  (lower(reference)) text_pattern_ops);
