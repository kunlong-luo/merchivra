-- V11A: durable Orders v2026 incremental sync cursor/lease/page journal.
-- No Amazon calls enabled by this migration; production egress is separately gated.
CREATE TABLE orders_sync_job (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    seller_marketplace_id UUID NOT NULL,
    mode VARCHAR(8) NOT NULL CHECK(mode IN ('MOCK','LIVE')),
    state VARCHAR(16) NOT NULL CHECK(state IN ('READY','RUNNING','WAITING_RETRY','PAUSED','SUCCEEDED')),
    window_after TIMESTAMPTZ NOT NULL,
    window_before TIMESTAMPTZ NOT NULL,
    page_index INTEGER NOT NULL DEFAULT 0 CHECK(page_index >= 0),
    generation INTEGER NOT NULL DEFAULT 0 CHECK(generation >= 0),
    next_token TEXT,
    token_issued_at TIMESTAMPTZ,
    last_safe_watermark TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    lease_epoch BIGINT NOT NULL DEFAULT 0 CHECK(lease_epoch >= 0),
    lease_owner UUID,
    lease_expires_at TIMESTAMPTZ,
    last_failure_code VARCHAR(32),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK(window_after < window_before),
    CHECK((lease_owner IS NULL) = (lease_expires_at IS NULL)),
    CHECK(next_token IS NULL OR token_issued_at IS NOT NULL),
    UNIQUE(tenant_id,id),
    UNIQUE(tenant_id,seller_marketplace_id),
    FOREIGN KEY(tenant_id,seller_marketplace_id)
      REFERENCES seller_marketplace(tenant_id,id)
);
CREATE INDEX orders_sync_ready_idx ON orders_sync_job(state,next_attempt_at,lease_expires_at);
CREATE TABLE orders_sync_page_commit (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    job_id UUID NOT NULL,
    page_index INTEGER NOT NULL CHECK(page_index >= 0),
    generation INTEGER NOT NULL CHECK(generation >= 0),
    record_count INTEGER NOT NULL CHECK(record_count >= 0),
    committed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,job_id,generation,page_index),
    FOREIGN KEY(tenant_id,job_id) REFERENCES orders_sync_job(tenant_id,id)
);
-- Real source marker is reserved for a future independently role-verified live adapter.
ALTER TABLE sales_order DROP CONSTRAINT sales_order_data_origin_check;
ALTER TABLE sales_order ADD CONSTRAINT sales_order_data_origin_check
    CHECK(data_origin IN ('UNVERIFIED','MOCK','LIVE'));
ALTER TABLE order_item DROP CONSTRAINT order_item_data_origin_check;
ALTER TABLE order_item ADD CONSTRAINT order_item_data_origin_check
    CHECK(data_origin IN ('UNVERIFIED','MOCK','LIVE'));
