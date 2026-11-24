-- V215 / Flyway V112: LOCAL receipt-level manual cost allocation proposal,
-- NOT verified landed cost, COGS, refund reversal or ledger posting.
-- No historical backfill. Cross V92 mutual exclusion is mandatory so one V91
-- charge cannot be presented as both ORDER/SKU/PERIOD and receipt deduction.

CREATE TABLE finance_receipt_cost_plan_v112 (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  source_id UUID NOT NULL,
  proposed_by UUID NOT NULL,
  proposed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  state VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW'
    CHECK (state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED','REJECTED')),
  reviewed_by UUID,
  reviewed_at TIMESTAMPTZ,
  review_reason VARCHAR(500),
  source_currency CHAR(3) NOT NULL CHECK (source_currency ~ '^[A-Z]{3}$'),
  source_amount NUMERIC(20,6) NOT NULL CHECK(source_amount>0),
  external_cost_verified BOOLEAN NOT NULL DEFAULT FALSE
    CHECK(external_cost_verified=FALSE),
  actual_landed_cost_posted BOOLEAN NOT NULL DEFAULT FALSE
    CHECK(actual_landed_cost_posted=FALSE),
  UNIQUE(tenant_id,id),
  -- One source can have at most ONE lifetime receipt claim proposal. A rejected
  -- or stale plan is retained; a future correction needs a distinct policy.
  UNIQUE(tenant_id,source_id),
  FOREIGN KEY(tenant_id,source_id)
    REFERENCES finance_cost_source_v91(tenant_id,id),
  FOREIGN KEY(tenant_id,proposed_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id),
  CHECK (
    (state='PENDING_REVIEW' AND reviewed_by IS NULL AND reviewed_at IS NULL
      AND review_reason IS NULL)
    OR
    (state<>'PENDING_REVIEW' AND reviewed_by IS NOT NULL
      AND reviewed_at IS NOT NULL AND reviewed_by<>proposed_by
      AND length(btrim(review_reason)) BETWEEN 10 AND 500)
  )
);

CREATE TABLE finance_receipt_cost_line_v112 (
  tenant_id UUID NOT NULL,
  plan_id UUID NOT NULL,
  receipt_id UUID NOT NULL,
  amount NUMERIC(20,6) NOT NULL CHECK (amount>0),
  PRIMARY KEY(tenant_id,plan_id,receipt_id),
  FOREIGN KEY(tenant_id,plan_id)
    REFERENCES finance_receipt_cost_plan_v112(tenant_id,id),
  -- V106 established this composite receipt identity; no fuzzy SKU/date match.
  FOREIGN KEY(tenant_id,receipt_id)
    REFERENCES erp_purchase_receipt(tenant_id,id)
);

CREATE INDEX finance_receipt_cost_receipt_v112
  ON finance_receipt_cost_line_v112(tenant_id,receipt_id,plan_id);

CREATE FUNCTION finance_receipt_cost_plan_guard_v112() RETURNS TRIGGER
LANGUAGE plpgsql AS $v112$
DECLARE
  source_row finance_cost_source_v91%ROWTYPE;
  allocated NUMERIC(20,6);
  allocation_count INTEGER;
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Receipt plans cannot be deleted';
  END IF;
  SELECT * INTO source_row FROM finance_cost_source_v91 s
    WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.source_id
    FOR UPDATE;
  IF NOT FOUND OR source_row.review_state<>'REVIEWED_UNVERIFIED'
     OR source_row.category NOT IN
       ('INBOUND_FREIGHT','IMPORT_DUTY','PAYMENT_AND_FX') THEN
    RAISE EXCEPTION 'Receipt manual cost source is missing, unreviewed or ineligible';
  END IF;

  IF TG_OP='INSERT' THEN
    IF EXISTS (
      SELECT 1 FROM finance_cost_allocation_batch_v92 b
      WHERE b.tenant_id=NEW.tenant_id AND b.source_id=NEW.source_id
        AND b.state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED')
    ) THEN
      RAISE EXCEPTION 'V91 cost source already belongs to active V92 allocation';
    END IF;
    -- Database, not caller, captures the immutable source price/currency.
    NEW.source_currency=source_row.currency_code;
    NEW.source_amount=source_row.amount;
    IF NEW.state<>'PENDING_REVIEW' OR NEW.reviewed_by IS NOT NULL
       OR NEW.reviewed_at IS NOT NULL OR NEW.review_reason IS NOT NULL
       OR NEW.external_cost_verified OR NEW.actual_landed_cost_posted THEN
      RAISE EXCEPTION 'Receipt plan must start locally unverified and pending';
    END IF;
    RETURN NEW;
  END IF;

  IF ROW(NEW.id,NEW.tenant_id,NEW.source_id,NEW.proposed_by,NEW.proposed_at,
         NEW.source_currency,NEW.source_amount,NEW.external_cost_verified,
         NEW.actual_landed_cost_posted)
     IS DISTINCT FROM
     ROW(OLD.id,OLD.tenant_id,OLD.source_id,OLD.proposed_by,OLD.proposed_at,
         OLD.source_currency,OLD.source_amount,OLD.external_cost_verified,
         OLD.actual_landed_cost_posted)
     OR OLD.state<>'PENDING_REVIEW'
     OR NEW.state NOT IN ('APPROVED_UNVERIFIED','REJECTED')
     OR NEW.reviewed_by IS NULL
     OR NEW.reviewed_by IN (OLD.proposed_by,source_row.created_by)
     OR source_row.currency_code IS DISTINCT FROM OLD.source_currency
     OR source_row.amount IS DISTINCT FROM OLD.source_amount
  THEN
    RAISE EXCEPTION 'Receipt plan immutable identity or independent review invalid';
  END IF;

  IF NEW.state='APPROVED_UNVERIFIED' THEN
    SELECT coalesce(sum(l.amount),0),count(*) INTO allocated,allocation_count
      FROM finance_receipt_cost_line_v112 l
      WHERE l.tenant_id=NEW.tenant_id AND l.plan_id=NEW.id;
    IF allocation_count<1 OR allocation_count>100
       OR allocated>OLD.source_amount THEN
      RAISE EXCEPTION 'Receipt cost allocations empty or exceed source amount';
    END IF;
  END IF;
  RETURN NEW;
END
$v112$;

CREATE TRIGGER finance_receipt_cost_plan_guard_v112
  BEFORE INSERT OR UPDATE OR DELETE ON finance_receipt_cost_plan_v112
  FOR EACH ROW EXECUTE FUNCTION finance_receipt_cost_plan_guard_v112();

CREATE FUNCTION finance_receipt_cost_line_guard_v112() RETURNS TRIGGER
LANGUAGE plpgsql AS $v112$
DECLARE
  parent_row finance_receipt_cost_plan_v112%ROWTYPE;
  receipt_currency CHAR(3);
BEGIN
  IF TG_OP<>'INSERT' THEN
    RAISE EXCEPTION 'Receipt manual cost line is immutable';
  END IF;
  SELECT * INTO parent_row FROM finance_receipt_cost_plan_v112 p
    WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.plan_id
    FOR UPDATE;
  IF NOT FOUND OR parent_row.state<>'PENDING_REVIEW' THEN
    RAISE EXCEPTION 'Receipt cost lines require a pending plan';
  END IF;
  SELECT po.currency_code INTO receipt_currency
    FROM erp_purchase_receipt r
    JOIN erp_purchase_order po
      ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id
    WHERE r.tenant_id=NEW.tenant_id AND r.id=NEW.receipt_id;
  IF receipt_currency IS NULL OR receipt_currency<>parent_row.source_currency
  THEN
    RAISE EXCEPTION 'Purchase receipt currency mismatch; unapproved FX prohibited';
  END IF;
  IF (SELECT count(*) FROM finance_receipt_cost_line_v112 l
      WHERE l.tenant_id=NEW.tenant_id AND l.plan_id=NEW.plan_id)>=100 THEN
    RAISE EXCEPTION 'Too many receipt allocations';
  END IF;
  IF (SELECT coalesce(sum(l.amount),0) FROM finance_receipt_cost_line_v112 l
      WHERE l.tenant_id=NEW.tenant_id AND l.plan_id=NEW.plan_id)
       + NEW.amount > parent_row.source_amount THEN
    RAISE EXCEPTION 'Receipt cost source original currency overallocated';
  END IF;
  RETURN NEW;
END
$v112$;

CREATE TRIGGER finance_receipt_cost_line_guard_v112
  BEFORE INSERT OR UPDATE OR DELETE ON finance_receipt_cost_line_v112
  FOR EACH ROW EXECUTE FUNCTION finance_receipt_cost_line_guard_v112();

-- Existing V92 insert already takes a source-row lock; an AFTER trigger on
-- V92 sees V112 plans committed by competing transactions after that lock.
CREATE FUNCTION finance_v92_excludes_receipt_plan_v112() RETURNS TRIGGER
LANGUAGE plpgsql AS $v112$
BEGIN
  IF NEW.state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED')
    AND EXISTS (
      SELECT 1 FROM finance_receipt_cost_plan_v112 p
      WHERE p.tenant_id=NEW.tenant_id AND p.source_id=NEW.source_id
        AND p.state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED')
    ) THEN
    RAISE EXCEPTION 'V91 source already belongs to active receipt proposal';
  END IF;
  RETURN NEW;
END
$v112$;
CREATE TRIGGER finance_v92_excludes_receipt_plan_v112
  AFTER INSERT OR UPDATE OF state ON finance_cost_allocation_batch_v92
  FOR EACH ROW EXECUTE FUNCTION finance_v92_excludes_receipt_plan_v112();

-- DB-written audit avoids trusting a separate client-side audit call.
CREATE TABLE finance_receipt_cost_audit_v112 (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id UUID NOT NULL,
  plan_id UUID NOT NULL,
  action VARCHAR(24) NOT NULL CHECK(action IN (
    'PENDING_REVIEW','APPROVED_UNVERIFIED','REJECTED')),
  actor_id UUID NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,plan_id)
    REFERENCES finance_receipt_cost_plan_v112(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES membership(tenant_id,user_id)
);
CREATE FUNCTION finance_receipt_cost_audit_v112() RETURNS TRIGGER
LANGUAGE plpgsql AS $v112$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Receipt plans cannot be deleted';
  END IF;
  INSERT INTO finance_receipt_cost_audit_v112
    (tenant_id,plan_id,action,actor_id)
  VALUES (NEW.tenant_id,NEW.id,NEW.state,
    CASE WHEN TG_OP='INSERT' THEN NEW.proposed_by ELSE NEW.reviewed_by END);
  RETURN NEW;
END
$v112$;
CREATE TRIGGER finance_receipt_cost_audit_v112
  AFTER INSERT OR UPDATE ON finance_receipt_cost_plan_v112
  FOR EACH ROW EXECUTE FUNCTION finance_receipt_cost_audit_v112();

CREATE TRIGGER finance_receipt_cost_audit_immutable_v112
  BEFORE UPDATE OR DELETE ON finance_receipt_cost_audit_v112
  FOR EACH ROW EXECUTE FUNCTION finance_cost_audit_append_only_v92();

-- Never expose cost as verified: these are only internal accounting proposals.
CREATE VIEW finance_receipt_cost_integrity_v112 AS
SELECT p.tenant_id,p.id AS plan_id,p.source_id,p.state,
  count(l.receipt_id)::bigint AS receipt_count,
  coalesce(sum(l.amount),0::numeric) AS locally_proposed_amount,
  p.source_amount,
  p.source_currency,
  (p.source_amount-coalesce(sum(l.amount),0::numeric)) AS unassigned_amount,
  (p.state='APPROVED_UNVERIFIED'
    AND count(l.receipt_id)>0
    AND count(l.receipt_id)=count(r.id)
    AND coalesce(bool_and(po.currency_code=p.source_currency),FALSE)
    AND coalesce(sum(l.amount),0::numeric)<=p.source_amount
    AND NOT EXISTS (
      SELECT 1 FROM finance_cost_allocation_batch_v92 b
      WHERE b.tenant_id=p.tenant_id AND b.source_id=p.source_id
        AND b.state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED')
    )) AS local_conservation_intact_unverified,
  FALSE::boolean AS external_cost_verified,
  FALSE::boolean AS actual_landed_cost_posted,
  NULL::numeric AS realized_batch_cogs
FROM finance_receipt_cost_plan_v112 p
LEFT JOIN finance_receipt_cost_line_v112 l
  ON l.tenant_id=p.tenant_id AND l.plan_id=p.id
LEFT JOIN erp_purchase_receipt r
  ON r.tenant_id=l.tenant_id AND r.id=l.receipt_id
LEFT JOIN erp_purchase_order po
  ON po.tenant_id=r.tenant_id AND po.id=r.purchase_order_id
GROUP BY p.tenant_id,p.id;
