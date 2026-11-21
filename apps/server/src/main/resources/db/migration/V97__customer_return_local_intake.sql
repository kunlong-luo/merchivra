-- V172: customer-return LOCAL warehouse intake candidates, separately from V39
-- PURCHASE_RECEIPT and V96 manual assertions. Never finance/stock posting.
-- Prevent cross-order, cross-event or cross-item mixing even by direct SQL.
ALTER TABLE finance_return_receipt_claim_v96
 ADD CONSTRAINT finance_return_receipt_claim_scoped_v97 UNIQUE
 (tenant_id,id,event_id,sales_order_id,external_item_id);
CREATE TABLE finance_customer_return_intake_v97 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 event_id UUID NOT NULL,
 sales_order_id UUID NOT NULL,
 external_item_id VARCHAR(160) NOT NULL,
 claim_id UUID NOT NULL,
 warehouse_id UUID NOT NULL,
 receipt_reference VARCHAR(160) NOT NULL
   CHECK (receipt_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
 received_quantity INTEGER NOT NULL CHECK(received_quantity BETWEEN 1 AND 1000000),
 condition_code VARCHAR(24) NOT NULL CHECK(condition_code IN
   ('SELLABLE_CLAIMED','UNSELLABLE_CLAIMED','UNKNOWN')),
 source_fingerprint CHAR(64) NOT NULL
   CHECK(source_fingerprint ~ '^[0-9a-f]{64}$'),
 state VARCHAR(24) NOT NULL DEFAULT 'PENDING'
   CHECK(state IN ('PENDING','ATTESTED_LOCAL','REJECTED')),
 origin VARCHAR(32) NOT NULL DEFAULT 'LOCAL_TWO_PERSON_UNVERIFIED'
   CHECK(origin='LOCAL_TWO_PERSON_UNVERIFIED'),
 requested_by UUID NOT NULL,
 requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 decided_by UUID,
 decided_at TIMESTAMPTZ,
 decision_reason VARCHAR(500),
 external_wms_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(external_wms_verified=FALSE),
 inventory_posted BOOLEAN NOT NULL DEFAULT FALSE CHECK(inventory_posted=FALSE),
 cogs_posted BOOLEAN NOT NULL DEFAULT FALSE CHECK(cogs_posted=FALSE),
 UNIQUE(tenant_id,id),
 -- A source document / parcel can only be linked once per physical warehouse.
 -- Rejected entries reserve identity for fraud/duplicate audit.
 UNIQUE(tenant_id,warehouse_id,receipt_reference),
 FOREIGN KEY(tenant_id,seller_marketplace_id,event_id)
   REFERENCES finance_source_event(tenant_id,seller_marketplace_id,id),
 FOREIGN KEY(tenant_id,seller_marketplace_id,sales_order_id)
   REFERENCES sales_order(tenant_id,seller_marketplace_id,id),
 FOREIGN KEY(tenant_id,event_id,sales_order_id,external_item_id)
   REFERENCES finance_refund_item_review(tenant_id,event_id,sales_order_id,external_item_id),
 FOREIGN KEY(tenant_id,claim_id,event_id,sales_order_id,external_item_id)
   REFERENCES finance_return_receipt_claim_v96
     (tenant_id,id,event_id,sales_order_id,external_item_id),
 FOREIGN KEY(tenant_id,warehouse_id)
   REFERENCES erp_local_warehouse(tenant_id,id),
 FOREIGN KEY(tenant_id,requested_by)
   REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,decided_by)
   REFERENCES membership(tenant_id,user_id),
 CHECK(requested_by IS DISTINCT FROM decided_by),
 CHECK((state='PENDING' AND decided_by IS NULL AND decided_at IS NULL
   AND decision_reason IS NULL) OR
   (state<>'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
   AND decision_reason IS NOT NULL))
);
CREATE INDEX finance_customer_return_intake_event_v97
 ON finance_customer_return_intake_v97
 (tenant_id,seller_marketplace_id,event_id,requested_at DESC,id DESC);
CREATE INDEX finance_customer_return_intake_claim_v97
 ON finance_customer_return_intake_v97(tenant_id,claim_id,state);

CREATE TABLE finance_customer_return_intake_audit_v97 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 intake_id UUID NOT NULL,
 actor_user_id UUID NOT NULL,
 action VARCHAR(24) NOT NULL
   CHECK(action IN ('REQUESTED','ATTESTED_LOCAL','REJECTED')),
 note VARCHAR(500) NOT NULL CHECK(length(btrim(note)) BETWEEN 10 AND 500),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,intake_id)
   REFERENCES finance_customer_return_intake_v97(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_user_id)
   REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_customer_return_intake_audit_v97_recent
 ON finance_customer_return_intake_audit_v97
 (tenant_id,intake_id,occurred_at DESC,id DESC);

CREATE FUNCTION finance_customer_return_intake_guard_v97() RETURNS trigger AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   RAISE EXCEPTION 'Customer return intake is append-only';
 END IF;
 IF OLD.state<>'PENDING' OR NEW.state NOT IN ('ATTESTED_LOCAL','REJECTED')
    OR NEW.id IS DISTINCT FROM OLD.id
    OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
    OR NEW.seller_marketplace_id IS DISTINCT FROM OLD.seller_marketplace_id
    OR NEW.event_id IS DISTINCT FROM OLD.event_id
    OR NEW.sales_order_id IS DISTINCT FROM OLD.sales_order_id
    OR NEW.external_item_id IS DISTINCT FROM OLD.external_item_id
    OR NEW.claim_id IS DISTINCT FROM OLD.claim_id
    OR NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
    OR NEW.receipt_reference IS DISTINCT FROM OLD.receipt_reference
    OR NEW.received_quantity IS DISTINCT FROM OLD.received_quantity
    OR NEW.condition_code IS DISTINCT FROM OLD.condition_code
    OR NEW.source_fingerprint IS DISTINCT FROM OLD.source_fingerprint
    OR NEW.origin IS DISTINCT FROM OLD.origin
    OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
    OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
    OR NEW.external_wms_verified IS DISTINCT FROM OLD.external_wms_verified
    OR NEW.inventory_posted IS DISTINCT FROM OLD.inventory_posted
    OR NEW.cogs_posted IS DISTINCT FROM OLD.cogs_posted
    OR NEW.decided_by IS NULL OR NEW.decided_by=OLD.requested_by
    OR NEW.decided_at IS NULL OR NEW.decision_reason IS NULL THEN
   RAISE EXCEPTION 'Customer return intake can only be independently decided once';
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_customer_return_intake_guard_v97
 BEFORE UPDATE OR DELETE ON finance_customer_return_intake_v97
 FOR EACH ROW EXECUTE FUNCTION finance_customer_return_intake_guard_v97();

CREATE FUNCTION finance_customer_return_intake_audit_guard_v97() RETURNS trigger AS $$
BEGIN
 RAISE EXCEPTION 'Customer return intake audit is append-only';
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_customer_return_intake_audit_guard_v97
 BEFORE UPDATE OR DELETE ON finance_customer_return_intake_audit_v97
 FOR EACH ROW EXECUTE FUNCTION finance_customer_return_intake_audit_guard_v97();
