-- V82. Tenant/marketplace-scoped human priority overrides, with optimistic
-- revisions and immutable audit. Do not alter Amazon, Ops rule or source evidence.
CREATE TABLE fba_exception_priority_setting (
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 reason_code VARCHAR(40) NOT NULL CHECK (reason_code IN (
  'SOURCE_CHANGED','PAST_DUE','INDEPENDENT_IDENTITY_REVIEW',
  'LOCAL_VARIANCE_REVIEW','DUE_SOON','HIGH_SEVERITY',
  'NEEDS_OWNER','SOURCE_WARNING','ROUTINE_FOLLOWUP')),
 priority VARCHAR(2) NOT NULL CHECK (priority IN ('P0','P1','P2','P3')),
 version BIGINT NOT NULL CHECK (version > 0),
 updated_by UUID NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,seller_marketplace_id,reason_code),
 FOREIGN KEY(tenant_id,seller_marketplace_id)
  REFERENCES seller_marketplace(tenant_id,id),
 FOREIGN KEY(tenant_id,updated_by) REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE fba_exception_priority_audit (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 reason_code VARCHAR(40) NOT NULL,
 old_priority VARCHAR(2),
 new_priority VARCHAR(2) NOT NULL,
 old_version BIGINT NOT NULL,
 new_version BIGINT NOT NULL,
 actor_user_id UUID NOT NULL,
 note VARCHAR(300) NOT NULL CHECK(length(btrim(note)) BETWEEN 10 AND 300),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,seller_marketplace_id,reason_code)
  REFERENCES fba_exception_priority_setting(tenant_id,seller_marketplace_id,reason_code),
 FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX fba_exception_priority_audit_v77
 ON fba_exception_priority_audit(tenant_id,seller_marketplace_id,
                                occurred_at DESC,id DESC);

-- Extend the existing V77/V79 replay ledger for explicitly confirmed human
-- RESOLVE batches. Existing ACKNOWLEDGE and ASSIGN entries remain intact.
ALTER TABLE fba_purchase_exception_batch
 DROP CONSTRAINT fba_purchase_exception_batch_action_type_check;
ALTER TABLE fba_purchase_exception_batch
 ADD CONSTRAINT fba_purchase_exception_batch_action_type_check
 CHECK(action_type IN ('ACKNOWLEDGE','ASSIGN','RESOLVE'));
ALTER TABLE fba_purchase_exception_batch
 DROP CONSTRAINT fba_purchase_batch_type_v76;
ALTER TABLE fba_purchase_exception_batch
 ADD CONSTRAINT fba_purchase_batch_type_v77
 CHECK ((action_type IN ('ACKNOWLEDGE','RESOLVE') AND assignee_id IS NULL)
     OR (action_type='ASSIGN' AND assignee_id IS NOT NULL));
