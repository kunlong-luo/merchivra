-- V21: finance source events are not provisional Orders sales and not bank settlements.
-- No buyer PII, payment details or credential material.
CREATE TABLE finance_source_event (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  source_family VARCHAR(24) NOT NULL CHECK(source_family IN ('MANUAL','SPAPI_FINANCES')),
  source_event_key VARCHAR(200) NOT NULL,
  data_origin VARCHAR(24) NOT NULL CHECK(data_origin IN ('MANUAL_UNVERIFIED','LIVE')),
  category VARCHAR(32) NOT NULL
    CHECK(category IN ('PRODUCT_SALES','PRODUCT_REFUND','AMAZON_FEE','FBA_FEE','ADJUSTMENT','UNCLASSIFIED')),
  amount NUMERIC(20,6) NOT NULL,
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  posted_at TIMESTAMPTZ NOT NULL,
  external_order_id VARCHAR(160),
  source_type VARCHAR(120) NOT NULL,
  ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by UUID,
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,seller_marketplace_id,source_family,source_event_key),
  FOREIGN KEY(tenant_id,seller_marketplace_id)
     REFERENCES seller_marketplace(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id),
  CHECK((source_family='MANUAL' AND data_origin='MANUAL_UNVERIFIED'
      AND created_by IS NOT NULL)
    OR (source_family='SPAPI_FINANCES' AND data_origin='LIVE'
      AND created_by IS NULL))
);
CREATE INDEX finance_event_recent ON finance_source_event
 (tenant_id,seller_marketplace_id,posted_at DESC,id DESC);
CREATE INDEX finance_event_order_ref ON finance_source_event
 (tenant_id,seller_marketplace_id,external_order_id)
 WHERE external_order_id IS NOT NULL;

-- Explicit human link review never changes the source event, amount or category.
CREATE TABLE finance_event_link_review (
  tenant_id UUID NOT NULL,
  event_id UUID NOT NULL,
  order_id UUID,
  state VARCHAR(24) NOT NULL CHECK(state IN ('MATCHED','UNMATCHED')),
  reviewed_by UUID NOT NULL,
  reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,event_id),
  FOREIGN KEY(tenant_id,event_id) REFERENCES finance_source_event(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id),
  FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id),
  CHECK((state='MATCHED')=(order_id IS NOT NULL))
);
CREATE TABLE finance_event_review_audit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  event_id UUID NOT NULL,
  actor_user_id UUID NOT NULL,
  reviewed_order_id UUID,
  state VARCHAR(24) NOT NULL CHECK(state IN ('MATCHED','UNMATCHED')),
  reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,event_id) REFERENCES finance_source_event(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_order_id) REFERENCES sales_order(tenant_id,id),
  CHECK((state='MATCHED')=(reviewed_order_id IS NOT NULL))
);
CREATE INDEX finance_review_history ON finance_event_review_audit
 (tenant_id,event_id,reviewed_at DESC);
