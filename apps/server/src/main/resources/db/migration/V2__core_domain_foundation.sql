-- M2 foundation. No live Seller/Finance API is exposed by this migration.
-- User-facing authorization is NOT yet implemented. Do not ingest real seller data.
-- All cross-table business references use (tenant_id, id) composite FKs.
CREATE TABLE tenant (
  id UUID PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','SUSPENDED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
  id UUID PRIMARY KEY,
  identity_ref VARCHAR(256) NOT NULL UNIQUE,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','DISABLED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE membership (
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  user_id UUID NOT NULL REFERENCES app_user(id),
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','REVOKED')),
  PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE seller_account (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  seller_id VARCHAR(128) NOT NULL,
  region VARCHAR(4) NOT NULL CHECK (region IN ('NA','EU','FE')),
  connection_status VARCHAR(32) NOT NULL DEFAULT 'UNCONFIGURED',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, seller_id, region)
);

CREATE TABLE role_binding (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  user_id UUID NOT NULL,
  role_code VARCHAR(64) NOT NULL,
  seller_account_id UUID,
  FOREIGN KEY (tenant_id, user_id) REFERENCES membership(tenant_id, user_id),
  FOREIGN KEY (tenant_id, seller_account_id) REFERENCES seller_account(tenant_id, id),
  UNIQUE (tenant_id, user_id, role_code, seller_account_id)
);
-- PostgreSQL UNIQUE treats NULL values as distinct here; account-agnostic role
-- uniqueness will be enforced once the global-vs-account RBAC design is signed.

CREATE TABLE seller_marketplace (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_account_id UUID NOT NULL,
  marketplace_id VARCHAR(64) NOT NULL,
  currency_code CHAR(3) NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
  timezone VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, seller_account_id, marketplace_id),
  FOREIGN KEY (tenant_id, seller_account_id)
    REFERENCES seller_account(tenant_id, id)
);

CREATE TABLE product_sku (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  internal_sku VARCHAR(200) NOT NULL,
  display_name VARCHAR(300) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, internal_sku)
);

CREATE TABLE marketplace_listing (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  seller_sku VARCHAR(200) NOT NULL,
  asin VARCHAR(32),
  fnsku VARCHAR(64),
  mapping_status VARCHAR(32) NOT NULL DEFAULT 'UNMAPPED'
    CHECK (mapping_status IN ('UNMAPPED','SUGGESTED','VERIFIED','ACTIVE','CONFLICT','SUPERSEDED')),
  source_updated_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, seller_marketplace_id, seller_sku),
  FOREIGN KEY (tenant_id, seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id, id)
);

CREATE TABLE listing_sku_mapping (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  listing_id UUID NOT NULL,
  product_sku_id UUID,
  status VARCHAR(32) NOT NULL
    CHECK (status IN ('SUGGESTED','VERIFIED','ACTIVE','SUPERSEDED','CONFLICT')),
  valid_from TIMESTAMPTZ NOT NULL,
  valid_to TIMESTAMPTZ,
  mapping_version INTEGER NOT NULL CHECK (mapping_version > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  UNIQUE (tenant_id, listing_id, mapping_version),
  FOREIGN KEY (tenant_id, listing_id)
    REFERENCES marketplace_listing(tenant_id, id),
  FOREIGN KEY (tenant_id, product_sku_id)
    REFERENCES product_sku(tenant_id, id)
);
-- Prevent two active "current" mappings; temporal exclusion is left for G2.
CREATE UNIQUE INDEX listing_one_current_active_mapping
  ON listing_sku_mapping(tenant_id, listing_id)
  WHERE status='ACTIVE' AND valid_to IS NULL;

CREATE TABLE sales_order (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  external_order_id VARCHAR(160) NOT NULL,
  fulfillment_status_raw VARCHAR(100) NOT NULL,
  refund_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'
    CHECK (refund_status IN ('NONE','PARTIAL','FULL','UNKNOWN')),
  financial_status VARCHAR(40) NOT NULL DEFAULT 'NOT_OBSERVED',
  order_time TIMESTAMPTZ,
  source_updated_at TIMESTAMPTZ,
  ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, seller_marketplace_id, external_order_id),
  FOREIGN KEY (tenant_id, seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id, id)
);
CREATE INDEX sales_order_recent_idx
  ON sales_order(tenant_id, seller_marketplace_id, source_updated_at DESC, id DESC);

CREATE TABLE order_item (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  sales_order_id UUID NOT NULL,
  external_item_id VARCHAR(160) NOT NULL,
  seller_sku_raw VARCHAR(200),
  quantity INTEGER CHECK (quantity IS NULL OR quantity >= 0),
  proceeds_amount NUMERIC(20,6),
  proceeds_currency CHAR(3) CHECK (proceeds_currency ~ '^[A-Z]{3}$'),
  CHECK ((proceeds_amount IS NULL) = (proceeds_currency IS NULL)),
  UNIQUE (tenant_id, sales_order_id, external_item_id),
  FOREIGN KEY (tenant_id, sales_order_id)
    REFERENCES sales_order(tenant_id, id)
);

CREATE TABLE fba_inventory_observation (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  seller_sku_raw VARCHAR(200) NOT NULL,
  source_observation_key VARCHAR(256) NOT NULL,
  quality VARCHAR(32) NOT NULL
    CHECK (quality IN ('OBSERVED','PARTIAL','UNKNOWN','STALE')),
  fulfillable INTEGER CHECK (fulfillable IS NULL OR fulfillable >= 0),
  reserved INTEGER CHECK (reserved IS NULL OR reserved >= 0),
  observed_at TIMESTAMPTZ,
  ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, seller_marketplace_id, source_observation_key),
  FOREIGN KEY (tenant_id, seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id, id)
);
CREATE INDEX fba_inventory_observation_recent_idx
  ON fba_inventory_observation(tenant_id, seller_marketplace_id, seller_sku_raw, ingested_at DESC);

CREATE TABLE sync_stream (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_account_id UUID NOT NULL,
  operation VARCHAR(96) NOT NULL,
  api_version VARCHAR(32) NOT NULL,
  scope_fingerprint VARCHAR(128) NOT NULL,
  last_safe_watermark TIMESTAMPTZ,
  lease_epoch BIGINT NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
  lease_owner UUID,
  lease_expires_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, seller_account_id, operation, api_version, scope_fingerprint),
  FOREIGN KEY (tenant_id, seller_account_id)
    REFERENCES seller_account(tenant_id, id),
  CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL))
);

CREATE TABLE sync_run (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  stream_id UUID NOT NULL,
  trigger_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL
    CHECK (status IN ('QUEUED','RUNNING','WAITING_RETRY','FAILED','SUCCEEDED','PAUSED')),
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, stream_id) REFERENCES sync_stream(tenant_id, id)
);

CREATE TABLE sync_page_commit (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  run_id UUID NOT NULL,
  page_fingerprint VARCHAR(256) NOT NULL,
  record_count INTEGER NOT NULL CHECK (record_count >= 0),
  committed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, run_id, page_fingerprint),
  FOREIGN KEY (tenant_id, run_id) REFERENCES sync_run(tenant_id, id)
);

-- Not in this baseline: Finances source uniqueness, FX, cost/settlement,
-- Amazon credential tables, refund posting rules, RLS policies and real API routes.
