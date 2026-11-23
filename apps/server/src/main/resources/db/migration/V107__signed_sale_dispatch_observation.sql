-- V204 / V107: independently recheckable signed LOCAL dispatch observations.
-- Tenant-enrolled WMS key possession is NOT independently certified WMS
-- operator identity or Amazon FBA/FBM order fulfillment.
-- V106 sale/pick claim identity is necessary, never sufficient for COGS.
CREATE TABLE finance_sale_lot_signed_dispatch_v107 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 marketplace_id UUID NOT NULL,
 sales_order_id UUID NOT NULL,
 claim_id UUID NOT NULL,
 source_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 external_dispatch_ref VARCHAR(160) NOT NULL
   CHECK(external_dispatch_ref ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
 quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 1000000),
 observed_at TIMESTAMPTZ NOT NULL,
 signature_base64 VARCHAR(120) NOT NULL
   CHECK(signature_base64 ~ '^[A-Za-z0-9+/]{84,88}={0,2}$'),
 canonical_sha256 CHAR(64) NOT NULL
   CHECK(canonical_sha256 ~ '^[a-f0-9]{64}$'),
 contract_version SMALLINT NOT NULL DEFAULT 1 CHECK(contract_version=1),
 state VARCHAR(40) NOT NULL DEFAULT 'KEY_SIGNATURE_VALID_UNTRUSTED'
   CHECK(state='KEY_SIGNATURE_VALID_UNTRUSTED'),
 signature_valid BOOLEAN NOT NULL DEFAULT TRUE CHECK(signature_valid=TRUE),
 operator_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(operator_identity_verified=FALSE),
 physical_dispatch_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(physical_dispatch_verified=FALSE),
 platform_fulfillment_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(platform_fulfillment_verified=FALSE),
 actual_batch_cogs_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(actual_batch_cogs_verified=FALSE),
 cogs_posted BOOLEAN NOT NULL DEFAULT FALSE CHECK(cogs_posted=FALSE),
 recorded_by UUID NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,claim_id),
 UNIQUE(tenant_id,source_id,signature_base64),
 FOREIGN KEY(tenant_id,marketplace_id) REFERENCES seller_marketplace(tenant_id,id),
 FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_sale_lot_claim_v106(tenant_id,id),
 FOREIGN KEY(tenant_id,source_id,warehouse_id)
   REFERENCES finance_wms_signing_source_v98(tenant_id,id,warehouse_id),
 FOREIGN KEY(tenant_id,recorded_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_sale_lot_signed_dispatch_order_v107
 ON finance_sale_lot_signed_dispatch_v107
 (tenant_id,marketplace_id,sales_order_id,id);

-- Database second line of defense, executed after Java Ed25519 verification.
-- Only a current, unique V106 claim with a previously completed V101 key
-- challenge can be observed. Neither is external operator attestation.
CREATE FUNCTION finance_sale_lot_signed_dispatch_guard_v107()
RETURNS trigger AS $$
DECLARE
 underlying RECORD;
 source_state VARCHAR(16);
BEGIN
 SELECT c.marketplace_id,c.sales_order_id,c.snapshot_warehouse_id,
   c.assigned_quantity,c.external_dispatch_ref,v.integrity_state
 INTO underlying
 FROM finance_sale_lot_claim_v106 c
 JOIN finance_sale_lot_integrity_v106 v
   ON v.tenant_id=c.tenant_id AND v.claim_id=c.id
 WHERE c.tenant_id=NEW.tenant_id AND c.id=NEW.claim_id
 FOR UPDATE OF c;
 IF NOT FOUND OR underlying.marketplace_id IS DISTINCT FROM NEW.marketplace_id
   OR underlying.sales_order_id IS DISTINCT FROM NEW.sales_order_id
   OR underlying.snapshot_warehouse_id IS DISTINCT FROM NEW.warehouse_id
   OR underlying.assigned_quantity IS DISTINCT FROM NEW.quantity
   OR underlying.external_dispatch_ref IS DISTINCT FROM NEW.external_dispatch_ref
   OR underlying.integrity_state IS DISTINCT FROM 'LOCAL_LINK_INTACT_UNVERIFIED' THEN
   RAISE EXCEPTION 'V107: current exact local sale/lot claim required'
     USING ERRCODE='23514';
 END IF;
 SELECT state INTO source_state FROM finance_wms_signing_source_v98
 WHERE tenant_id=NEW.tenant_id AND id=NEW.source_id
   AND warehouse_id=NEW.warehouse_id
 FOR UPDATE;
 IF source_state IS DISTINCT FROM 'APPROVED' OR NOT EXISTS (
   SELECT 1 FROM finance_wms_key_proof_v101 p
   WHERE p.tenant_id=NEW.tenant_id AND p.source_id=NEW.source_id
     AND p.warehouse_id=NEW.warehouse_id
     AND p.result='KEY_POSSESSION_ONLY'
 ) THEN
   RAISE EXCEPTION 'V107: approved source and independent key possession required'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_sale_lot_signed_dispatch_guard_v107
 BEFORE INSERT ON finance_sale_lot_signed_dispatch_v107
 FOR EACH ROW EXECUTE FUNCTION finance_sale_lot_signed_dispatch_guard_v107();
CREATE TRIGGER finance_sale_lot_signed_dispatch_immutable_v107
 BEFORE UPDATE OR DELETE ON finance_sale_lot_signed_dispatch_v107
 FOR EACH ROW EXECUTE FUNCTION finance_wms_append_only_v98();

-- Read-time source revocation, retraction, local source drift and V101 proof
-- loss invalidates any earlier digest/signature. Java *also* recalculates the
-- canonical SHA-256 and Ed25519 detached signature at every evidence read.
CREATE VIEW finance_sale_lot_dispatch_integrity_v107 AS
SELECT d.id,d.tenant_id,d.marketplace_id,d.sales_order_id,d.claim_id,
 d.source_id,d.warehouse_id,d.external_dispatch_ref,d.quantity,
 d.observed_at,d.signature_base64,d.canonical_sha256,
 c.order_item_id,c.pick_line_id,c.receipt_id,c.assigned_quantity,
 c.snapshot_external_item_id,c.external_payload_sha256,
 c.snapshot_warehouse_id,s.ed25519_spki_base64,
 CASE
  WHEN v.integrity_state IS DISTINCT FROM 'LOCAL_LINK_INTACT_UNVERIFIED'
    THEN 'LOCAL_CLAIM_STALE'
  WHEN s.state IS DISTINCT FROM 'APPROVED'
    THEN 'SIGNING_SOURCE_REVOKED'
  WHEN NOT EXISTS (
     SELECT 1 FROM finance_wms_key_proof_v101 p
     WHERE p.tenant_id=d.tenant_id AND p.source_id=d.source_id
       AND p.warehouse_id=d.warehouse_id
       AND p.result='KEY_POSSESSION_ONLY'
   ) THEN 'KEY_POSSESSION_EVIDENCE_MISSING'
  WHEN d.marketplace_id IS DISTINCT FROM c.marketplace_id
    OR d.sales_order_id IS DISTINCT FROM c.sales_order_id
    OR d.warehouse_id IS DISTINCT FROM c.snapshot_warehouse_id
    OR d.external_dispatch_ref IS DISTINCT FROM c.external_dispatch_ref
    OR d.quantity IS DISTINCT FROM c.assigned_quantity
    THEN 'SIGNED_DISPATCH_IDENTITY_CONFLICT'
  ELSE 'TENANT_KEY_SIGNATURE_ONLY_UNVERIFIED'
 END AS evidence_state,
 FALSE::boolean AS operator_identity_verified,
 FALSE::boolean AS platform_fulfillment_verified,
 FALSE::boolean AS physical_dispatch_verified,
 FALSE::boolean AS actual_batch_cogs_verified,
 NULL::numeric AS realized_batch_cogs
FROM finance_sale_lot_signed_dispatch_v107 d
JOIN finance_sale_lot_claim_v106 c
 ON c.tenant_id=d.tenant_id AND c.id=d.claim_id
JOIN finance_sale_lot_integrity_v106 v
 ON v.tenant_id=d.tenant_id AND v.claim_id=c.id
JOIN finance_wms_signing_source_v98 s
 ON s.tenant_id=d.tenant_id AND s.id=d.source_id
  AND s.warehouse_id=d.warehouse_id;
COMMENT ON VIEW finance_sale_lot_dispatch_integrity_v107 IS
 'Tenant-enrolled key possession and local claim only, NOT Amazon fulfillment, operator identity or actual COGS';
