-- V62 / product milestone V64: persistent bounded financial contribution extracts.
-- The order cohort is captured when created; each order is assessed once in its
-- own commit batch. NOT an atomic historical snapshot of all external Finances.
-- No bank verification, Ads deduction, GL posting or Amazon write.
CREATE TABLE finance_profit_report_job (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL,
 seller_marketplace_id UUID NOT NULL,
 created_by UUID NOT NULL,
 period_after DATE NOT NULL,
 period_before DATE NOT NULL,
 currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
 state VARCHAR(16) NOT NULL DEFAULT 'READY'
   CHECK(state IN ('READY','RETRY','SUCCEEDED','PAUSED')),
 total_orders INTEGER NOT NULL CHECK(total_orders BETWEEN 0 AND 5000),
 processed_orders INTEGER NOT NULL DEFAULT 0
   CHECK(processed_orders>=0 AND processed_orders<=total_orders),
 retry_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_count BETWEEN 0 AND 5),
 failure_code VARCHAR(48),
 next_retry_at TIMESTAMPTZ,
 manual_ads_reference NUMERIC(20,6) NOT NULL DEFAULT 0,
 manual_ads_count BIGINT NOT NULL DEFAULT 0 CHECK(manual_ads_count>=0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 completed_at TIMESTAMPTZ,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,seller_marketplace_id)
   REFERENCES seller_marketplace(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id),
 CHECK(period_before>period_after AND period_before<=period_after+92),
 CHECK((state='SUCCEEDED')=(completed_at IS NOT NULL)),
 CHECK(state<>'SUCCEEDED' OR processed_orders=total_orders)
);
CREATE INDEX finance_profit_report_job_recent_v62
 ON finance_profit_report_job(tenant_id,seller_marketplace_id,created_at DESC);
CREATE INDEX finance_profit_report_job_due_v62
 ON finance_profit_report_job(state,next_retry_at,updated_at)
 WHERE state IN ('READY','RETRY');
CREATE TABLE finance_profit_report_order (
 tenant_id UUID NOT NULL,
 job_id UUID NOT NULL,
 order_id UUID NOT NULL,
 cohort_position INTEGER NOT NULL CHECK(cohort_position BETWEEN 0 AND 4999),
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING'
   CHECK(state IN ('PENDING','ELIGIBLE','BLOCKED')),
 readiness VARCHAR(96),
 currency_code CHAR(3),
 reviewed_events INTEGER NOT NULL DEFAULT 0 CHECK(reviewed_events>=0),
 signed_sales NUMERIC(20,6),
 signed_amazon_fees NUMERIC(20,6),
 signed_fba_fees NUMERIC(20,6),
 provisional_cogs NUMERIC(20,6),
 estimated_contribution NUMERIC(20,6),
 source_fingerprint CHAR(64) CHECK(source_fingerprint ~ '^[0-9a-f]{64}$'),
 captured_at TIMESTAMPTZ,
 PRIMARY KEY(tenant_id,job_id,order_id),
 UNIQUE(tenant_id,job_id,cohort_position),
 FOREIGN KEY(tenant_id,job_id) REFERENCES finance_profit_report_job(tenant_id,id),
 FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id),
 CHECK (
    (state='PENDING' AND readiness IS NULL AND captured_at IS NULL
       AND source_fingerprint IS NULL)
    OR
    (state<>'PENDING' AND readiness IS NOT NULL AND captured_at IS NOT NULL
       AND source_fingerprint IS NOT NULL)
 ),
 CHECK (
    (state='ELIGIBLE' AND currency_code IS NOT NULL
      AND signed_sales IS NOT NULL AND signed_amazon_fees IS NOT NULL
      AND signed_fba_fees IS NOT NULL AND provisional_cogs IS NOT NULL
      AND estimated_contribution IS NOT NULL
      AND estimated_contribution=signed_sales+signed_amazon_fees
        +signed_fba_fees-provisional_cogs)
    OR
    (state IN ('PENDING','BLOCKED') AND signed_sales IS NULL
      AND signed_amazon_fees IS NULL AND signed_fba_fees IS NULL
      AND provisional_cogs IS NULL AND estimated_contribution IS NULL)
 )
);
CREATE INDEX finance_profit_report_order_state_v62
 ON finance_profit_report_order(tenant_id,job_id,state,cohort_position);
CREATE TABLE finance_profit_report_sku (
 tenant_id UUID NOT NULL,
 job_id UUID NOT NULL,
 order_id UUID NOT NULL,
 internal_sku VARCHAR(160) NOT NULL,
 units INTEGER NOT NULL CHECK(units>0),
 signed_sales NUMERIC(20,6) NOT NULL,
 signed_amazon_fees NUMERIC(20,6) NOT NULL,
 signed_fba_fees NUMERIC(20,6) NOT NULL,
 provisional_cogs NUMERIC(20,6) NOT NULL,
 estimated_contribution NUMERIC(20,6) NOT NULL
   CHECK(estimated_contribution=signed_sales+signed_amazon_fees
     +signed_fba_fees-provisional_cogs),
 PRIMARY KEY(tenant_id,job_id,order_id,internal_sku),
 FOREIGN KEY(tenant_id,job_id,order_id)
   REFERENCES finance_profit_report_order(tenant_id,job_id,order_id)
);
CREATE INDEX finance_profit_report_sku_aggregate_v62
 ON finance_profit_report_sku(tenant_id,job_id,internal_sku);
