-- V171: one-shot manual physical return RECEIPT CLAIM, never trusted WMS receipt.
-- Purchase receipts (V39/V44) are NOT customer return receipts.
-- Required composite identity for the event+order+item evidence reference.
ALTER TABLE finance_refund_item_review
 ADD CONSTRAINT finance_refund_item_review_full_v96 UNIQUE
 (tenant_id,event_id,sales_order_id,external_item_id);
CREATE TABLE finance_return_receipt_claim_v96 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 event_id UUID NOT NULL,
 sales_order_id UUID NOT NULL,
 external_item_id VARCHAR(160) NOT NULL,
 source_review_revision INTEGER NOT NULL CHECK(source_review_revision>0),
 source_fingerprint CHAR(64) NOT NULL CHECK(source_fingerprint ~ '^[0-9a-f]{64}$'),
 claimed_quantity INTEGER NOT NULL CHECK(claimed_quantity BETWEEN 1 AND 1000000),
 condition_code VARCHAR(24) NOT NULL CHECK(condition_code IN
   ('SELLABLE_CLAIMED','UNSELLABLE_CLAIMED','UNKNOWN')),
 local_warehouse_id UUID,
 evidence_reference VARCHAR(160) NOT NULL CHECK(
   evidence_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
 evidence_origin VARCHAR(24) NOT NULL DEFAULT 'MANUAL_UNVERIFIED'
   CHECK(evidence_origin='MANUAL_UNVERIFIED'),
 state VARCHAR(24) NOT NULL DEFAULT 'PENDING'
   CHECK(state IN ('PENDING','REVIEWED_MANUAL','REJECTED')),
 requested_by UUID NOT NULL,
 requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 decided_by UUID,
 decided_at TIMESTAMPTZ,
 decision_reason VARCHAR(500),
 inventory_receipt_verified BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(inventory_receipt_verified=FALSE),
 cogs_reversal_posted BOOLEAN NOT NULL DEFAULT FALSE
   CHECK(cogs_reversal_posted=FALSE),
 FOREIGN KEY(tenant_id,seller_marketplace_id,event_id)
   REFERENCES finance_source_event(tenant_id,seller_marketplace_id,id),
 FOREIGN KEY(tenant_id,seller_marketplace_id,sales_order_id)
   REFERENCES sales_order(tenant_id,seller_marketplace_id,id),
 FOREIGN KEY(tenant_id,event_id,sales_order_id,external_item_id)
   REFERENCES finance_refund_item_review(tenant_id,event_id,sales_order_id,external_item_id),
 FOREIGN KEY(tenant_id,local_warehouse_id)
   REFERENCES erp_local_warehouse(tenant_id,id),
 FOREIGN KEY(tenant_id,requested_by) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,decided_by) REFERENCES membership(tenant_id,user_id),
 CHECK(requested_by IS DISTINCT FROM decided_by),
 CHECK((state='PENDING')=(decided_by IS NULL AND decided_at IS NULL
   AND decision_reason IS NULL)),
 CHECK((state<>'PENDING')=(decision_reason IS NOT NULL))
);
-- Only one undecided or manually-reviewed claim for each original refund item,
-- even across later evidence revisions. Rejected claims remain immutable history.
CREATE UNIQUE INDEX finance_return_receipt_claim_active_v96
 ON finance_return_receipt_claim_v96(tenant_id,event_id,external_item_id)
 WHERE state IN ('PENDING','REVIEWED_MANUAL');
CREATE INDEX finance_return_receipt_claim_event_v96
 ON finance_return_receipt_claim_v96(tenant_id,seller_marketplace_id,event_id,requested_at DESC,id);

ALTER TABLE finance_return_receipt_claim_v96
 ADD CONSTRAINT finance_return_receipt_claim_tenant_id UNIQUE(tenant_id,id);

CREATE TABLE finance_return_receipt_audit_v96 (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 claim_id UUID NOT NULL,
 actor_user_id UUID NOT NULL,
 action VARCHAR(24) NOT NULL CHECK(action IN
   ('REQUESTED','REVIEWED_MANUAL','REJECTED')),
 note VARCHAR(500) NOT NULL CHECK(length(btrim(note)) BETWEEN 10 AND 500),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,claim_id)
   REFERENCES finance_return_receipt_claim_v96(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_return_receipt_audit_recent_v96
 ON finance_return_receipt_audit_v96(tenant_id,claim_id,occurred_at DESC,id);

CREATE FUNCTION finance_return_receipt_claim_guard_v96() RETURNS trigger AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   RAISE EXCEPTION 'Return receipt claims cannot be deleted';
 END IF;
 IF OLD.state<>'PENDING' OR NEW.state NOT IN ('REVIEWED_MANUAL','REJECTED')
    OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
    OR NEW.id IS DISTINCT FROM OLD.id
    OR NEW.seller_marketplace_id IS DISTINCT FROM OLD.seller_marketplace_id
    OR NEW.event_id IS DISTINCT FROM OLD.event_id
    OR NEW.sales_order_id IS DISTINCT FROM OLD.sales_order_id
    OR NEW.external_item_id IS DISTINCT FROM OLD.external_item_id
    OR NEW.source_review_revision IS DISTINCT FROM OLD.source_review_revision
    OR NEW.source_fingerprint IS DISTINCT FROM OLD.source_fingerprint
    OR NEW.claimed_quantity IS DISTINCT FROM OLD.claimed_quantity
    OR NEW.condition_code IS DISTINCT FROM OLD.condition_code
    OR NEW.local_warehouse_id IS DISTINCT FROM OLD.local_warehouse_id
    OR NEW.evidence_reference IS DISTINCT FROM OLD.evidence_reference
    OR NEW.evidence_origin IS DISTINCT FROM OLD.evidence_origin
    OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
    OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
    OR NEW.inventory_receipt_verified IS DISTINCT FROM OLD.inventory_receipt_verified
    OR NEW.cogs_reversal_posted IS DISTINCT FROM OLD.cogs_reversal_posted
    OR NEW.decided_by IS NULL OR NEW.decided_by=OLD.requested_by
    OR NEW.decided_at IS NULL OR NEW.decision_reason IS NULL THEN
   RAISE EXCEPTION 'Return receipt evidence is immutable; independent one-shot decision only';
 END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER finance_return_receipt_claim_guard_v96
 BEFORE UPDATE OR DELETE ON finance_return_receipt_claim_v96
 FOR EACH ROW EXECUTE FUNCTION finance_return_receipt_claim_guard_v96();

CREATE FUNCTION finance_return_receipt_audit_append_only_v96() RETURNS trigger AS $$
BEGIN
 RAISE EXCEPTION 'Return receipt audit is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER finance_return_receipt_audit_append_only_v96
 BEFORE UPDATE OR DELETE ON finance_return_receipt_audit_v96
 FOR EACH ROW EXECUTE FUNCTION finance_return_receipt_audit_append_only_v96();
