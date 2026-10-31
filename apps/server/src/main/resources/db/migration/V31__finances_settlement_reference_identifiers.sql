-- V31: only non-PII, document-level Finances related IDs.
-- Preserves Amazon provenance; matching does NOT mean payout was confirmed.
CREATE TABLE finance_transaction_reference (
  tenant_id UUID NOT NULL,
  event_id UUID NOT NULL,
  reference_kind VARCHAR(32) NOT NULL CHECK(reference_kind IN
    ('SETTLEMENT_ID','DISBURSEMENT_ID','FINANCIAL_EVENT_GROUP_ID')),
  reference_value VARCHAR(160) NOT NULL CHECK(
    reference_value ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$'),
  ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,event_id,reference_kind,reference_value),
  FOREIGN KEY(tenant_id,event_id) REFERENCES finance_source_event(tenant_id,id)
);
CREATE INDEX finance_transaction_reference_lookup
 ON finance_transaction_reference(tenant_id,reference_kind,reference_value,event_id);
