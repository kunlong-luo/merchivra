-- V94 / product V167: non-accounting, operator-linked settlement fee-line evidence.
-- A shared order reference or matching amount is NOT proof of a duplicate debit.
-- Links and independent reviews never clear V165 collision holds or post to V64.
CREATE UNIQUE INDEX finance_cost_source_mp_pk_v94
  ON finance_cost_source_v91(tenant_id,seller_marketplace_id,id);
CREATE UNIQUE INDEX settlement_report_mp_pk_v94
  ON spapi_settlement_report(tenant_id,seller_marketplace_id,id);

CREATE TABLE finance_cost_settlement_line_link_v94 (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  source_id UUID NOT NULL,
  report_id UUID NOT NULL,
  line_number INTEGER NOT NULL CHECK(line_number BETWEEN 1 AND 10000),
  source_amount NUMERIC(20,6) NOT NULL CHECK(source_amount>0),
  source_currency CHAR(3) NOT NULL,
  line_signed_amount NUMERIC(20,6) NOT NULL,
  line_currency CHAR(3) NOT NULL,
  similarity VARCHAR(40) NOT NULL CHECK(similarity IN (
    'CURRENCY_MISMATCH','CREDIT_OR_ZERO_NOT_DEBIT',
    'SIMILAR_REFERENCE_AND_AMOUNT','SIMILAR_REFERENCE_ONLY',
    'AMOUNT_ONLY_NO_IDENTITY','UNRELATED_LINE_NO_EVIDENCE')),
  evidence_origin VARCHAR(32) NOT NULL DEFAULT 'LOCAL_LINE_ASSOCIATION'
    CHECK(evidence_origin='LOCAL_LINE_ASSOCIATION'),
  state VARCHAR(40) NOT NULL DEFAULT 'OPEN' CHECK(state IN (
    'OPEN','ESCALATED_NEEDS_PROOF','POTENTIAL_DUPLICATE_UNVERIFIED',
    'NOT_SAME_CHARGE_UNVERIFIED')),
  recorded_by UUID NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_by UUID,
  reviewed_at TIMESTAMPTZ,
  review_reason VARCHAR(500),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,source_id,report_id,line_number),
  FOREIGN KEY(tenant_id,seller_marketplace_id,source_id)
    REFERENCES finance_cost_source_v91(tenant_id,seller_marketplace_id,id),
  FOREIGN KEY(tenant_id,seller_marketplace_id,report_id)
    REFERENCES spapi_settlement_report(tenant_id,seller_marketplace_id,id),
  FOREIGN KEY(tenant_id,report_id,line_number)
    REFERENCES spapi_settlement_report_line(tenant_id,report_id,line_number),
  FOREIGN KEY(tenant_id,recorded_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id),
  CHECK (
    (state='OPEN' AND reviewed_by IS NULL AND reviewed_at IS NULL
      AND review_reason IS NULL)
    OR
    (state<>'OPEN' AND reviewed_by IS NOT NULL AND reviewed_by<>recorded_by
      AND reviewed_at IS NOT NULL AND review_reason IS NOT NULL
      AND length(btrim(review_reason)) BETWEEN 10 AND 500
      AND position(chr(10) IN review_reason)=0
      AND position(chr(13) IN review_reason)=0)
  )
);
CREATE INDEX finance_cost_settlement_links_recent_v94
  ON finance_cost_settlement_line_link_v94
    (tenant_id,seller_marketplace_id,source_id,recorded_at DESC,id DESC);

-- Source snapshot, line snapshot and similarity are checked in PostgreSQL.
-- Captured facts are immutable; a review is a one-shot unverified disposition.
CREATE FUNCTION finance_cost_settlement_line_link_guard_v94() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
  s finance_cost_source_v91%ROWTYPE;
  l spapi_settlement_report_line%ROWTYPE;
  expected_similarity VARCHAR(40);
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Cost-to-settlement evidence links cannot be deleted';
  END IF;
  SELECT * INTO s FROM finance_cost_source_v91
    WHERE tenant_id=NEW.tenant_id
      AND seller_marketplace_id=NEW.seller_marketplace_id
      AND id=NEW.source_id FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Cost evidence source outside tenant/Marketplace';
  END IF;
  SELECT * INTO l FROM spapi_settlement_report_line
    WHERE tenant_id=NEW.tenant_id
      AND report_id=NEW.report_id AND line_number=NEW.line_number FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Missing actual settlement report line';
  END IF;
  IF ROW(NEW.source_amount,NEW.source_currency,NEW.line_signed_amount,NEW.line_currency)
     IS DISTINCT FROM ROW(s.amount,s.currency_code,l.signed_amount,l.currency_code) THEN
    RAISE EXCEPTION 'Settlement line snapshot mismatch';
  END IF;
  expected_similarity := CASE
    WHEN s.currency_code<>l.currency_code THEN 'CURRENCY_MISMATCH'
    WHEN l.signed_amount>=0 THEN 'CREDIT_OR_ZERO_NOT_DEBIT'
    WHEN l.external_order_id=s.source_key AND -l.signed_amount=s.amount
      THEN 'SIMILAR_REFERENCE_AND_AMOUNT'
    WHEN l.external_order_id=s.source_key THEN 'SIMILAR_REFERENCE_ONLY'
    WHEN -l.signed_amount=s.amount THEN 'AMOUNT_ONLY_NO_IDENTITY'
    ELSE 'UNRELATED_LINE_NO_EVIDENCE'
  END;
  IF NEW.similarity<>expected_similarity THEN
    RAISE EXCEPTION 'Cost evidence classification mismatch';
  END IF;
  IF TG_OP='INSERT' THEN
    IF NEW.state<>'OPEN' THEN
      RAISE EXCEPTION 'Cost evidence links must start OPEN';
    END IF;
    RETURN NEW;
  END IF;
  IF ROW(NEW.id,NEW.tenant_id,NEW.seller_marketplace_id,NEW.source_id,
      NEW.report_id,NEW.line_number,NEW.source_amount,NEW.source_currency,
      NEW.line_signed_amount,NEW.line_currency,NEW.similarity,NEW.evidence_origin,
      NEW.recorded_by,NEW.recorded_at)
    IS DISTINCT FROM
     ROW(OLD.id,OLD.tenant_id,OLD.seller_marketplace_id,OLD.source_id,
      OLD.report_id,OLD.line_number,OLD.source_amount,OLD.source_currency,
      OLD.line_signed_amount,OLD.line_currency,OLD.similarity,OLD.evidence_origin,
      OLD.recorded_by,OLD.recorded_at)
     OR OLD.state<>'OPEN' OR NEW.state='OPEN'
     OR NEW.reviewed_by IS NULL
     OR NEW.reviewed_by=s.created_by OR NEW.reviewed_by=OLD.recorded_by
  THEN RAISE EXCEPTION 'Cost evidence review must be independent and one-shot';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER finance_cost_settlement_line_link_guard_trigger_v94
  BEFORE INSERT OR UPDATE OR DELETE ON finance_cost_settlement_line_link_v94
  FOR EACH ROW EXECUTE FUNCTION finance_cost_settlement_line_link_guard_v94();

CREATE TABLE finance_cost_settlement_line_audit_v94 (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  link_id UUID NOT NULL,
  action VARCHAR(40) NOT NULL CHECK(action IN (
    'OPEN','ESCALATED_NEEDS_PROOF','POTENTIAL_DUPLICATE_UNVERIFIED',
    'NOT_SAME_CHARGE_UNVERIFIED')),
  actor_id UUID NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,link_id)
    REFERENCES finance_cost_settlement_line_link_v94(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_cost_settlement_line_audit_recent_v94
  ON finance_cost_settlement_line_audit_v94
    (tenant_id,link_id,occurred_at DESC,id DESC);
CREATE TRIGGER finance_cost_settlement_line_audit_append_only_v94
  BEFORE UPDATE OR DELETE ON finance_cost_settlement_line_audit_v94
  FOR EACH ROW EXECUTE FUNCTION finance_cost_audit_append_only_v92();
