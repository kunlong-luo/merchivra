-- V57: immutable minimal manual bank payout references. No institution connection.
-- Never use these rows as proof of actual funds/ledger posting.
CREATE TABLE bank_payout_import_batch (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  statement_ref VARCHAR(160) NOT NULL
    CHECK(statement_ref ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
  file_sha256 CHAR(64) NOT NULL CHECK(file_sha256 ~ '^[0-9a-f]{64}$'),
  source_origin VARCHAR(24) NOT NULL DEFAULT 'MANUAL_UNVERIFIED'
    CHECK(source_origin='MANUAL_UNVERIFIED'),
  line_count INTEGER NOT NULL CHECK(line_count BETWEEN 1 AND 50),
  imported_by UUID NOT NULL,
  imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,seller_marketplace_id,statement_ref),
  UNIQUE(tenant_id,seller_marketplace_id,file_sha256),
  FOREIGN KEY(tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id,id),
  FOREIGN KEY(tenant_id,imported_by) REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE bank_payout_observation (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  batch_id UUID NOT NULL,
  row_number INTEGER NOT NULL CHECK(row_number BETWEEN 1 AND 50),
  entry_ref VARCHAR(160) NOT NULL
    CHECK(entry_ref ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
  settlement_ref VARCHAR(160) NOT NULL
    CHECK(settlement_ref ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
  value_date DATE NOT NULL,
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  credit_amount NUMERIC(20,6) NOT NULL CHECK(credit_amount>0),
  withheld_fee NUMERIC(20,6) NOT NULL DEFAULT 0 CHECK(withheld_fee>=0),
  declared_fx_rate NUMERIC(20,8) CHECK(declared_fx_rate>0),
  evidence_origin VARCHAR(24) NOT NULL DEFAULT 'MANUAL_UNVERIFIED'
    CHECK(evidence_origin='MANUAL_UNVERIFIED'),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,batch_id,row_number),
  UNIQUE(tenant_id,seller_marketplace_id,entry_ref),
  FOREIGN KEY(tenant_id,batch_id)
    REFERENCES bank_payout_import_batch(tenant_id,id),
  FOREIGN KEY(tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id,id)
);
CREATE INDEX bank_payout_observation_ref_v57
  ON bank_payout_observation(tenant_id,seller_marketplace_id,settlement_ref);
