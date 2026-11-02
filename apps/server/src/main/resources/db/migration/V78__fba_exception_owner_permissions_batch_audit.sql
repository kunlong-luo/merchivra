-- V84: opt-in Owner-only exception capability restrictions, not new staff roles.
-- Defaults to unrestricted Owner; explicit profile restricts an existing Owner
-- only for local FBA purchase exception workflow. Inactive users receive no grant.
CREATE TABLE fba_exception_owner_permission (
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 owner_user_id UUID NOT NULL,
 can_view BOOLEAN NOT NULL,
 can_assign BOOLEAN NOT NULL,
 can_review BOOLEAN NOT NULL,
 can_resolve BOOLEAN NOT NULL,
 can_configure BOOLEAN NOT NULL,
 can_manage BOOLEAN NOT NULL,
 version BIGINT NOT NULL CHECK(version>0),
 updated_by UUID NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,seller_marketplace_id,owner_user_id),
 FOREIGN KEY(tenant_id,seller_marketplace_id)
   REFERENCES seller_marketplace(tenant_id,id),
 FOREIGN KEY(tenant_id,owner_user_id) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,updated_by) REFERENCES membership(tenant_id,user_id),
 CHECK (can_manage = FALSE OR (can_view AND can_configure))
);
CREATE TABLE fba_exception_owner_permission_audit (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 owner_user_id UUID NOT NULL,
 actor_user_id UUID NOT NULL,
 old_version BIGINT NOT NULL,
 new_version BIGINT NOT NULL,
 old_grants VARCHAR(80),
 new_grants VARCHAR(80) NOT NULL,
 reason VARCHAR(300) NOT NULL CHECK(length(btrim(reason)) BETWEEN 10 AND 300),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,seller_marketplace_id,owner_user_id)
  REFERENCES fba_exception_owner_permission(tenant_id,seller_marketplace_id,owner_user_id),
 FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX fba_exception_owner_permission_audit_v78
 ON fba_exception_owner_permission_audit(tenant_id,seller_marketplace_id,occurred_at DESC,id DESC);

-- New batches link each exact V77 action audit to the immutable batch ledger.
-- Earlier V77/V79/V82 rows stay NULL, because their per-batch lineage cannot
-- honestly be reconstructed from timestamps or request notes.
ALTER TABLE fba_purchase_exception_batch
 ADD CONSTRAINT fba_purchase_exception_batch_tenant_id_v78 UNIQUE(tenant_id,id);
ALTER TABLE fba_purchase_exception_audit
 ADD COLUMN batch_id UUID,
 ADD CONSTRAINT fba_purchase_exception_audit_batch_v78 FOREIGN KEY(tenant_id,batch_id)
  REFERENCES fba_purchase_exception_batch(tenant_id,id);
CREATE INDEX fba_purchase_exception_audit_batch_v78
 ON fba_purchase_exception_audit(tenant_id,batch_id,occurred_at,id)
 WHERE batch_id IS NOT NULL;
