-- V30: manual statement references and explicitly linked LIVE Finances transactions.
-- No bank integration, no confirmed payouts, no ledger postings.
CREATE TABLE finance_settlement_statement (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  statement_ref VARCHAR(160) NOT NULL
    CHECK(statement_ref ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
  period_start TIMESTAMPTZ NOT NULL,
  period_end TIMESTAMPTZ NOT NULL,
  reported_net NUMERIC(20,6) NOT NULL,
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  evidence_origin VARCHAR(32) NOT NULL DEFAULT 'MANUAL_UNVERIFIED'
    CHECK(evidence_origin = 'MANUAL_UNVERIFIED'),
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,seller_marketplace_id,statement_ref),
  CHECK(period_end>period_start),
  CHECK(period_end<=period_start+interval '93 days'),
  FOREIGN KEY(tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE finance_settlement_event_link (
  tenant_id UUID NOT NULL,
  statement_id UUID NOT NULL,
  event_id UUID NOT NULL,
  linked_by UUID NOT NULL,
  linked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,statement_id,event_id),
  UNIQUE(tenant_id,event_id),
  FOREIGN KEY(tenant_id,statement_id)
    REFERENCES finance_settlement_statement(tenant_id,id),
  FOREIGN KEY(tenant_id,event_id) REFERENCES finance_source_event(tenant_id,id),
  FOREIGN KEY(tenant_id,linked_by) REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE finance_settlement_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  statement_id UUID NOT NULL,
  event_id UUID,
  action VARCHAR(24) NOT NULL
    CHECK(action IN ('CREATED','EVENT_LINKED','EVENT_UNLINKED')),
  actor_user_id UUID NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,statement_id)
    REFERENCES finance_settlement_statement(tenant_id,id),
  FOREIGN KEY(tenant_id,event_id) REFERENCES finance_source_event(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id),
  CHECK((action='CREATED')=(event_id IS NULL))
);
CREATE INDEX finance_settlement_recent
 ON finance_settlement_statement(tenant_id,seller_marketplace_id,created_at DESC);
CREATE INDEX finance_settlement_audit_recent
 ON finance_settlement_audit(tenant_id,statement_id,occurred_at DESC);
