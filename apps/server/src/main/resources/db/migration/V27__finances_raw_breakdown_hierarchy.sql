-- V27: preserve raw Finances breakdown hierarchy; never double-count parents/children.
CREATE TABLE finance_transaction_breakdown (
  tenant_id UUID NOT NULL,
  event_id UUID NOT NULL,
  source_path VARCHAR(96) NOT NULL CHECK(source_path ~ '^[0-9]+([.][0-9]+)*$'),
  parent_path VARCHAR(96),
  source_type VARCHAR(160) NOT NULL,
  original_amount NUMERIC(20,6),
  currency_code CHAR(3) CHECK(currency_code ~ '^[A-Z]{3}$'),
  is_leaf BOOLEAN NOT NULL,
  ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,event_id,source_path),
  FOREIGN KEY(tenant_id,event_id) REFERENCES finance_source_event(tenant_id,id),
  CHECK((original_amount IS NULL)=(currency_code IS NULL))
);
CREATE INDEX finance_breakdown_parent_idx ON finance_transaction_breakdown
 (tenant_id,event_id,parent_path);
