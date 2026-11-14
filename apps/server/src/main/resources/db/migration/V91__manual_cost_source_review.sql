-- V91 / product V163: append-only manual cost source facts with independent
-- review metadata. Does NOT post accounting entries, update V64, or validate
-- Amazon Ads/Finances invoices. sha256 is a user-claimed document digest only.
CREATE TABLE finance_cost_source_v91 (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  category VARCHAR(32) NOT NULL CHECK(category IN (
    'ADS_SPEND','INBOUND_FREIGHT','FBA_STORAGE','FBM_SHIPPING',
    'RETURN_HANDLING','IMPORT_DUTY','PAYMENT_AND_FX','OTHER_OPERATING')),
  source_kind VARCHAR(24) NOT NULL CHECK(source_kind IN (
    'MANUAL_INVOICE','MANUAL_CARRIER','MANUAL_OTHER')),
  source_key VARCHAR(128) NOT NULL
    CHECK(source_key ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,127}$'),
  source_document_sha256 CHAR(64) NOT NULL
    CHECK(source_document_sha256 ~ '^[0-9a-f]{64}$'),
  business_date DATE NOT NULL,
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  amount NUMERIC(20,6) NOT NULL CHECK(amount > 0 AND amount <= 99999999999999.999999),
  evidence_origin VARCHAR(24) NOT NULL DEFAULT 'MANUAL_UNVERIFIED'
    CHECK(evidence_origin='MANUAL_UNVERIFIED'),
  review_state VARCHAR(24) NOT NULL DEFAULT 'STAGED'
    CHECK(review_state IN ('STAGED','REVIEWED_UNVERIFIED','REJECTED')),
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_by UUID,
  reviewed_at TIMESTAMPTZ,
  review_reason VARCHAR(500),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,seller_marketplace_id,source_kind,source_key),
  FOREIGN KEY(tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id),
  CHECK(
    (review_state='STAGED' AND reviewed_by IS NULL AND reviewed_at IS NULL
       AND review_reason IS NULL)
    OR
    (review_state<>'STAGED' AND reviewed_by IS NOT NULL
       AND reviewed_by<>created_by AND reviewed_at IS NOT NULL
       AND length(btrim(review_reason)) BETWEEN 10 AND 500)
  )
);
CREATE INDEX finance_cost_source_recent_v91
  ON finance_cost_source_v91(tenant_id,seller_marketplace_id,business_date DESC,id DESC);

-- Database-level immutable financial source facts, including original business
-- time, category, currency, source ID, digest, maker and amount.
CREATE FUNCTION finance_cost_source_immutable_v91() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.id,NEW.tenant_id,NEW.seller_marketplace_id,NEW.category,
         NEW.source_kind,NEW.source_key,NEW.source_document_sha256,
         NEW.business_date,NEW.currency_code,NEW.amount,
         NEW.evidence_origin,NEW.created_by,NEW.created_at)
    IS DISTINCT FROM
     ROW(OLD.id,OLD.tenant_id,OLD.seller_marketplace_id,OLD.category,
         OLD.source_kind,OLD.source_key,OLD.source_document_sha256,
         OLD.business_date,OLD.currency_code,OLD.amount,
         OLD.evidence_origin,OLD.created_by,OLD.created_at)
  THEN RAISE EXCEPTION 'Cost source facts are immutable';
  END IF;
  IF OLD.review_state <> 'STAGED' OR NEW.review_state = 'STAGED'
     OR NEW.review_state = OLD.review_state
  THEN RAISE EXCEPTION 'Cost source review may be completed only once';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER finance_cost_source_immutable_guard_v91
  BEFORE UPDATE ON finance_cost_source_v91
  FOR EACH ROW EXECUTE FUNCTION finance_cost_source_immutable_v91();

-- Audit contains only safe identifiers and decision codes, never raw invoice
-- contents, seller tokens, bank information or unbounded reviewer text.
CREATE TABLE finance_cost_source_audit_v91 (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  source_id UUID NOT NULL,
  action VARCHAR(24) NOT NULL
    CHECK(action IN ('STAGED','REVIEWED_UNVERIFIED','REJECTED')),
  actor_id UUID NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,source_id)
    REFERENCES finance_cost_source_v91(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_cost_source_audit_recent_v91
  ON finance_cost_source_audit_v91(tenant_id,source_id,occurred_at DESC,id DESC);
