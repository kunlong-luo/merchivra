-- Product V75 / Flyway V73: explicit per-Seller-SKU inbound intent -> local PO proof.
-- V63 is local/mock-only, never Amazon-confirmed. No inferred ownership by date/qty/SKU.
-- Multiple shipments may share a PO/ASN only while allocations remain within totals.
CREATE TABLE fba_inbound_purchase_link (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  shipment_id UUID NOT NULL,
  inbound_line_id UUID NOT NULL,
  purchase_order_id UUID NOT NULL,
  purchase_order_line_id UUID NOT NULL,
  asn_id UUID,
  request_key UUID NOT NULL,
  proof_reference VARCHAR(200) NOT NULL
    CHECK(length(btrim(proof_reference)) BETWEEN 10 AND 200),
  state VARCHAR(16) NOT NULL DEFAULT 'PROPOSED'
    CHECK(state IN ('PROPOSED','CONFIRMED','REVOKED')),
  proposed_by UUID NOT NULL,
  confirmed_by UUID,
  revoked_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  confirmed_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,request_key),
  FOREIGN KEY(tenant_id,shipment_id,inbound_line_id)
    REFERENCES fba_inbound_line(tenant_id,shipment_id,id),
  FOREIGN KEY(tenant_id,purchase_order_id,purchase_order_line_id)
    REFERENCES erp_purchase_order_line(tenant_id,purchase_order_id,id),
  FOREIGN KEY(tenant_id,purchase_order_id,purchase_order_line_id,asn_id)
    REFERENCES erp_purchase_asn(tenant_id,purchase_order_id,purchase_order_line_id,id),
  FOREIGN KEY(tenant_id,proposed_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,confirmed_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,revoked_by) REFERENCES membership(tenant_id,user_id),
  CHECK(
    (state='PROPOSED' AND confirmed_by IS NULL AND confirmed_at IS NULL
       AND revoked_by IS NULL AND revoked_at IS NULL)
    OR (state='CONFIRMED' AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL
       AND confirmed_by<>proposed_by AND revoked_by IS NULL AND revoked_at IS NULL)
    OR (state='REVOKED' AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL)
  )
);
CREATE UNIQUE INDEX fba_inbound_purchase_link_active_v73
  ON fba_inbound_purchase_link(tenant_id,shipment_id,inbound_line_id)
  WHERE state<>'REVOKED';
CREATE INDEX fba_inbound_purchase_link_po_v73
  ON fba_inbound_purchase_link(tenant_id,purchase_order_line_id,state);
CREATE INDEX fba_inbound_purchase_link_asn_v73
  ON fba_inbound_purchase_link(tenant_id,asn_id,state) WHERE asn_id IS NOT NULL;

CREATE TABLE fba_inbound_purchase_link_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  link_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  action VARCHAR(16) NOT NULL
    CHECK(action IN ('PROPOSED','CONFIRMED','REVOKED')),
  note VARCHAR(300) NOT NULL CHECK(length(btrim(note)) BETWEEN 10 AND 300),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,link_id) REFERENCES fba_inbound_purchase_link(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX fba_inbound_purchase_link_audit_v73
  ON fba_inbound_purchase_link_audit(tenant_id,link_id,occurred_at DESC);
