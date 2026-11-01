-- Business V66 / Flyway V64. Independent Ads identity references and immutable
-- OFFLINE report evidence. No Ads authorization/tokens, Ads HTTP calls or GL posting.
CREATE TABLE ads_profile_reference (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  profile_reference VARCHAR(80) NOT NULL CHECK(length(btrim(profile_reference)) BETWEEN 3 AND 80),
  origin VARCHAR(24) NOT NULL CHECK(origin IN ('MOCK','MANUAL_UNVERIFIED')),
  authorization_status VARCHAR(24) NOT NULL DEFAULT 'NOT_AUTHORIZED'
    CHECK(authorization_status='NOT_AUTHORIZED'),
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,seller_marketplace_id,id),
  UNIQUE(tenant_id,profile_reference),
  FOREIGN KEY(tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX ads_profile_marketplace_v64 ON ads_profile_reference(tenant_id,seller_marketplace_id);

CREATE TABLE ads_report_import (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  profile_id UUID NOT NULL,
  report_reference VARCHAR(160) NOT NULL
    CHECK(report_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
  report_sha256 CHAR(64) NOT NULL CHECK(report_sha256 ~ '^[0-9a-f]{64}$'),
  report_type VARCHAR(32) NOT NULL DEFAULT 'SP_CAMPAIGN_DAILY'
    CHECK(report_type='SP_CAMPAIGN_DAILY'),
  evidence_origin VARCHAR(24) NOT NULL
    CHECK(evidence_origin IN ('MOCK','MANUAL_UNVERIFIED')),
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  from_date DATE NOT NULL,
  through_date DATE NOT NULL,
  row_count INTEGER NOT NULL CHECK(row_count BETWEEN 1 AND 200),
  state VARCHAR(24) NOT NULL DEFAULT 'STAGED'
    CHECK(state IN ('STAGED','REVIEWED_UNVERIFIED','REJECTED')),
  imported_by UUID NOT NULL,
  imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_by UUID,
  reviewed_at TIMESTAMPTZ,
  review_note VARCHAR(500),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,seller_marketplace_id,profile_id,report_reference),
  FOREIGN KEY(tenant_id,seller_marketplace_id,profile_id)
    REFERENCES ads_profile_reference(tenant_id,seller_marketplace_id,id),
  FOREIGN KEY(tenant_id,imported_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id),
  CHECK(from_date<=through_date AND through_date<=from_date+30),
  CHECK(
    (state='STAGED' AND reviewed_by IS NULL AND reviewed_at IS NULL AND review_note IS NULL)
    OR
    (state<>'STAGED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
      AND length(btrim(review_note)) BETWEEN 10 AND 500 AND reviewed_by<>imported_by)
  )
);
CREATE INDEX ads_report_recent_v64
 ON ads_report_import(tenant_id,seller_marketplace_id,imported_at DESC,id DESC);

CREATE TABLE ads_report_cost_row (
  tenant_id UUID NOT NULL,
  batch_id UUID NOT NULL,
  row_number INTEGER NOT NULL CHECK(row_number BETWEEN 1 AND 200),
  business_date DATE NOT NULL,
  campaign_reference VARCHAR(160) NOT NULL
    CHECK(campaign_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$'),
  spend_amount NUMERIC(20,6) NOT NULL CHECK(spend_amount>=0),
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  PRIMARY KEY(tenant_id,batch_id,row_number),
  UNIQUE(tenant_id,batch_id,business_date,campaign_reference),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES ads_report_import(tenant_id,id)
);

-- Reviewed references are a unique, NON-ACCOUNTING projection.
-- Same profile/day/campaign cannot be reviewed twice across overlapping reports.
CREATE TABLE ads_report_reviewed_cost (
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  profile_id UUID NOT NULL,
  business_date DATE NOT NULL,
  campaign_reference VARCHAR(160) NOT NULL,
  batch_id UUID NOT NULL,
  row_number INTEGER NOT NULL,
  spend_amount NUMERIC(20,6) NOT NULL CHECK(spend_amount>=0),
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  PRIMARY KEY(tenant_id,profile_id,business_date,campaign_reference),
  FOREIGN KEY(tenant_id,seller_marketplace_id,profile_id)
    REFERENCES ads_profile_reference(tenant_id,seller_marketplace_id,id),
  FOREIGN KEY(tenant_id,batch_id,row_number)
    REFERENCES ads_report_cost_row(tenant_id,batch_id,row_number)
);
CREATE INDEX ads_reviewed_recent_v64
 ON ads_report_reviewed_cost(tenant_id,seller_marketplace_id,business_date DESC);
