-- V147: full local FBA identity proof and approved PO/ASN source browsing.
-- Forward-only indexes; no data migration, deleted evidence, or Amazon writes.
CREATE INDEX fba_purchase_link_history_page_v90
  ON fba_inbound_purchase_link(tenant_id,shipment_id,created_at DESC,id DESC)
  INCLUDE(inbound_line_id,state);

CREATE INDEX erp_purchase_order_candidate_page_v90
  ON erp_purchase_order(tenant_id,updated_at DESC,id DESC)
  INCLUDE(status,supplier_id);

CREATE INDEX erp_purchase_asn_candidate_page_v90
  ON erp_purchase_asn(tenant_id,purchase_order_line_id,status,id DESC);
