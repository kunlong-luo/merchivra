-- V36: MANUAL_UNVERIFIED Ads-spend source facts and SKU basis-point attribution.
-- Advertising API auth is independent of SP-API; do not represent these as LIVE Ads facts.
CREATE TABLE finance_ad_spend_reference (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 source_key VARCHAR(160) NOT NULL CHECK(source_key ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
 campaign_reference VARCHAR(160) NOT NULL
   CHECK(campaign_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$'),
 business_date DATE NOT NULL,
 currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
 spend_amount NUMERIC(20,6) NOT NULL CHECK(spend_amount>=0),
 evidence_origin VARCHAR(24) NOT NULL DEFAULT 'MANUAL_UNVERIFIED'
   CHECK(evidence_origin='MANUAL_UNVERIFIED'),
 created_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,seller_marketplace_id,source_key),
 FOREIGN KEY(tenant_id,seller_marketplace_id)
   REFERENCES seller_marketplace(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_ad_spend_recent_idx
 ON finance_ad_spend_reference(tenant_id,seller_marketplace_id,business_date DESC,id DESC);

CREATE TABLE finance_ad_spend_sku_attribution (
 tenant_id UUID NOT NULL,
 source_id UUID NOT NULL,
 product_sku_id UUID NOT NULL,
 basis_points INTEGER NOT NULL CHECK(basis_points BETWEEN 1 AND 10000),
 evidence_reference VARCHAR(160) NOT NULL
   CHECK(evidence_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'),
 revision INTEGER NOT NULL CHECK(revision>0),
 reviewed_by UUID NOT NULL,
 reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,source_id,product_sku_id),
 FOREIGN KEY(tenant_id,source_id) REFERENCES finance_ad_spend_reference(tenant_id,id),
 FOREIGN KEY(tenant_id,product_sku_id) REFERENCES product_sku(tenant_id,id),
 FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE finance_ad_spend_attribution_audit (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 source_id UUID NOT NULL,
 product_sku_id UUID NOT NULL,
 action VARCHAR(24) NOT NULL CHECK(action IN ('CREATED','UPDATED','WITHDRAWN')),
 basis_points INTEGER NOT NULL CHECK(basis_points BETWEEN 1 AND 10000),
 evidence_reference VARCHAR(160) NOT NULL,
 revision INTEGER NOT NULL CHECK(revision>0),
 actor_user_id UUID NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,source_id) REFERENCES finance_ad_spend_reference(tenant_id,id),
 FOREIGN KEY(tenant_id,product_sku_id) REFERENCES product_sku(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_user_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_ad_spend_attribution_history
 ON finance_ad_spend_attribution_audit(tenant_id,source_id,occurred_at DESC,id DESC);
