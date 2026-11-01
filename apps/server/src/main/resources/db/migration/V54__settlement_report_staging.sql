-- V58: immutable, bounded Amazon settlement report staging, never ledger postings.
-- Only trusted Workers holding a verified Seller+Finance scope can import LIVE.
CREATE TABLE spapi_settlement_report (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  amazon_report_id VARCHAR(160) NOT NULL,
  report_document_id VARCHAR(256) NOT NULL,
  report_type VARCHAR(96) NOT NULL CHECK
    (report_type='GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE_V2'),
  state VARCHAR(20) NOT NULL CHECK (state='IMPORTED'),
  source_origin VARCHAR(12) NOT NULL CHECK (source_origin='LIVE'),
  document_sha256 CHAR(64) NOT NULL CHECK (document_sha256 ~ '^[0-9a-f]{64}$'),
  settlement_reference VARCHAR(160) NOT NULL,
  statement_currency CHAR(3) NOT NULL CHECK (statement_currency ~ '^[A-Z]{3}$'),
  declared_net NUMERIC(20,6),
  line_count INTEGER NOT NULL CHECK (line_count BETWEEN 1 AND 10000),
  imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id,id),
  UNIQUE (tenant_id,seller_marketplace_id,amazon_report_id),
  UNIQUE (tenant_id,seller_marketplace_id,report_document_id),
  FOREIGN KEY (tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace (tenant_id,id)
);

CREATE TABLE spapi_settlement_report_line (
  tenant_id UUID NOT NULL,
  report_id UUID NOT NULL,
  line_number INTEGER NOT NULL CHECK(line_number BETWEEN 1 AND 10000),
  transaction_type VARCHAR(100),
  amount_type VARCHAR(100),
  amount_description VARCHAR(160),
  external_order_id VARCHAR(160),
  signed_amount NUMERIC(20,6) NOT NULL,
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  PRIMARY KEY (tenant_id,report_id,line_number),
  FOREIGN KEY (tenant_id,report_id)
    REFERENCES spapi_settlement_report(tenant_id,id)
);

CREATE INDEX spapi_settlement_report_recent_v54
  ON spapi_settlement_report(tenant_id,seller_marketplace_id,imported_at DESC);
