-- Product V77 / Flyway V74. Local human triage only, never an Amazon SP-API write.
-- Deliberately separate from immutable V63 receipts and V73 identity proofs.
CREATE TABLE fba_purchase_exception_policy (
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 lead_hours INTEGER NOT NULL DEFAULT 24 CHECK (lead_hours BETWEEN 1 AND 168),
 default_due_hours INTEGER NOT NULL DEFAULT 72 CHECK (default_due_hours BETWEEN 1 AND 720),
 version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
 updated_by UUID NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id,seller_marketplace_id),
 FOREIGN KEY (tenant_id,seller_marketplace_id) REFERENCES seller_marketplace(tenant_id,id),
 FOREIGN KEY (tenant_id,updated_by) REFERENCES membership(tenant_id,user_id)
);

CREATE TABLE fba_purchase_exception_case (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 shipment_id UUID NOT NULL,
 inbound_line_id UUID NOT NULL,
 state VARCHAR(16) NOT NULL CHECK (state IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
 assignee_id UUID,
 due_at TIMESTAMPTZ,
 source_fingerprint CHAR(64) NOT NULL CHECK (source_fingerprint ~ '^[0-9a-f]{64}$'),
 version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
 last_action_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE (tenant_id,id),
 UNIQUE (tenant_id,seller_marketplace_id,inbound_line_id),
 FOREIGN KEY (tenant_id,seller_marketplace_id) REFERENCES seller_marketplace(tenant_id,id),
 FOREIGN KEY (tenant_id,shipment_id,inbound_line_id)
   REFERENCES fba_inbound_line(tenant_id,shipment_id,id),
 FOREIGN KEY (tenant_id,assignee_id) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY (tenant_id,last_action_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX fba_purchase_exception_cases_due_v74
 ON fba_purchase_exception_case(tenant_id,seller_marketplace_id,state,due_at)
 WHERE state<>'RESOLVED';

CREATE TABLE fba_purchase_exception_audit (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 case_id UUID NOT NULL,
 actor_user_id UUID NOT NULL,
 action VARCHAR(24) NOT NULL CHECK(action IN
   ('ASSIGN','ACKNOWLEDGE','RESOLVE','REOPEN','SET_DUE')),
 old_state VARCHAR(16),
 new_state VARCHAR(16) NOT NULL,
 old_assignee UUID,
 new_assignee UUID,
 old_due_at TIMESTAMPTZ,
 new_due_at TIMESTAMPTZ,
 old_fingerprint CHAR(64),
 new_fingerprint CHAR(64) NOT NULL,
 note VARCHAR(300) NOT NULL CHECK(length(btrim(note)) BETWEEN 10 AND 300),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,case_id) REFERENCES fba_purchase_exception_case(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,old_assignee) REFERENCES membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,new_assignee) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX fba_purchase_exception_history_v74
 ON fba_purchase_exception_audit(tenant_id,case_id,occurred_at DESC,id DESC);

CREATE TABLE fba_purchase_exception_batch (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 request_key UUID NOT NULL,
 payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
 actor_user_id UUID NOT NULL,
 acknowledged_count INTEGER NOT NULL CHECK (acknowledged_count BETWEEN 0 AND 50),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,seller_marketplace_id,request_key),
 FOREIGN KEY (tenant_id,seller_marketplace_id) REFERENCES seller_marketplace(tenant_id,id),
 FOREIGN KEY (tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE fba_purchase_exception_policy_audit (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 actor_user_id UUID NOT NULL,
 old_version BIGINT,
 new_version BIGINT NOT NULL,
 old_enabled BOOLEAN,
 new_enabled BOOLEAN NOT NULL,
 old_lead_hours INTEGER,
 new_lead_hours INTEGER NOT NULL,
 old_default_due_hours INTEGER,
 new_default_due_hours INTEGER NOT NULL,
 note VARCHAR(300) NOT NULL CHECK(length(btrim(note)) BETWEEN 10 AND 300),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,seller_marketplace_id)
   REFERENCES fba_purchase_exception_policy(tenant_id,seller_marketplace_id),
 FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX fba_purchase_exception_policy_audit_v74
 ON fba_purchase_exception_policy_audit(tenant_id,seller_marketplace_id,occurred_at DESC,id DESC);
