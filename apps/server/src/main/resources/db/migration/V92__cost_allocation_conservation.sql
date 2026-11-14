-- V92 / product V164: immutable, one-shot manual cost allocation proposals.
-- NEVER recognized net profit, bank proof, trusted Ads evidence or V64 ledger.
-- PostgreSQL 18. Batch targets are mutually exclusive SKU/ORDER/PERIOD dimensions.
CREATE TABLE finance_cost_allocation_batch_v92 (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  source_id UUID NOT NULL,
  request_key UUID NOT NULL,
  target_kind VARCHAR(12) NOT NULL CHECK(target_kind IN ('SKU','ORDER','PERIOD')),
  state VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW'
    CHECK(state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED','REJECTED')),
  proposed_by UUID NOT NULL,
  proposed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_by UUID,
  reviewed_at TIMESTAMPTZ,
  review_reason VARCHAR(500),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,source_id,request_key),
  FOREIGN KEY(tenant_id,source_id) REFERENCES finance_cost_source_v91(tenant_id,id),
  FOREIGN KEY(tenant_id,proposed_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id),
  CHECK(
    (state='PENDING_REVIEW' AND reviewed_by IS NULL AND reviewed_at IS NULL
       AND review_reason IS NULL)
    OR
    (state<>'PENDING_REVIEW' AND reviewed_by IS NOT NULL
       AND reviewed_by<>proposed_by AND reviewed_at IS NOT NULL
       AND length(btrim(review_reason)) BETWEEN 10 AND 500)
  )
);
-- No concurrent pending competing plans or two approved deductions of one source.
-- After REJECTED a new version/request key may be proposed (old record retained).
CREATE UNIQUE INDEX finance_cost_allocation_active_v92
  ON finance_cost_allocation_batch_v92(tenant_id,source_id)
  WHERE state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED');
CREATE INDEX finance_cost_allocation_recent_v92
  ON finance_cost_allocation_batch_v92(tenant_id,source_id,proposed_at DESC,id DESC);

CREATE TABLE finance_cost_allocation_line_v92 (
  tenant_id UUID NOT NULL,
  batch_id UUID NOT NULL,
  line_no INTEGER NOT NULL CHECK(line_no BETWEEN 1 AND 100),
  sku_id UUID,
  order_id UUID,
  period_after DATE,
  period_before DATE,
  original_currency CHAR(3) NOT NULL
    CHECK(original_currency ~ '^[A-Z]{3}$'),
  expense_amount NUMERIC(20,6) NOT NULL
    CHECK(expense_amount>0 AND expense_amount<=99999999999999.999999),
  PRIMARY KEY(tenant_id,batch_id,line_no),
  UNIQUE NULLS NOT DISTINCT (tenant_id,batch_id,sku_id,order_id,
                             period_after,period_before),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES finance_cost_allocation_batch_v92(tenant_id,id),
  FOREIGN KEY(tenant_id,sku_id) REFERENCES product_sku(tenant_id,id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES sales_order(tenant_id,id),
  CHECK(
    (sku_id IS NOT NULL AND order_id IS NULL
       AND period_after IS NULL AND period_before IS NULL)
    OR
    (order_id IS NOT NULL AND sku_id IS NULL
       AND period_after IS NULL AND period_before IS NULL)
    OR
    (period_after IS NOT NULL AND period_before IS NOT NULL
       AND sku_id IS NULL AND order_id IS NULL
       AND period_after<period_before
       AND period_before<=period_after+92)
  )
);
CREATE INDEX finance_cost_allocation_line_target_v92
  ON finance_cost_allocation_line_v92(tenant_id,batch_id,line_no);

-- Every allocation row must match both the original source currency and the
-- batch's ONE target dimension. Order must belong to that same Marketplace.
CREATE FUNCTION finance_cost_allocation_line_guard_v92() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
  parent_state TEXT;
  scope_kind TEXT;
  currency TEXT;
  marketplace UUID;
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'Cost allocations are immutable after submission';
  END IF;
  SELECT b.state,b.target_kind,s.currency_code,s.seller_marketplace_id
    INTO parent_state,scope_kind,currency,marketplace
    FROM finance_cost_allocation_batch_v92 b
    JOIN finance_cost_source_v91 s ON s.tenant_id=b.tenant_id
       AND s.id=b.source_id
    WHERE b.tenant_id=NEW.tenant_id AND b.id=NEW.batch_id
    FOR UPDATE OF b;
  IF parent_state IS DISTINCT FROM 'PENDING_REVIEW'
     OR BTRIM(NEW.original_currency)<>BTRIM(currency)
     OR (scope_kind='SKU' AND NEW.sku_id IS NULL)
     OR (scope_kind='ORDER' AND NEW.order_id IS NULL)
     OR (scope_kind='PERIOD' AND NEW.period_after IS NULL)
  THEN
    RAISE EXCEPTION 'Invalid source scope, currency or pending proposal';
  END IF;
  IF NEW.order_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM sales_order o
      WHERE o.tenant_id=NEW.tenant_id AND o.id=NEW.order_id
        AND o.seller_marketplace_id=marketplace
  ) THEN
    RAISE EXCEPTION 'Allocated order is outside source Marketplace';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER finance_cost_allocation_lines_guard_v92
  BEFORE INSERT OR UPDATE OR DELETE ON finance_cost_allocation_line_v92
  FOR EACH ROW EXECUTE FUNCTION finance_cost_allocation_line_guard_v92();

-- A batch cannot be silently edited; final approval must recompute exact
-- original-currency line sum under the source lock and leave the remainder
-- explicitly unallocated. No zero-valued compensating rows.
CREATE FUNCTION finance_cost_allocation_batch_guard_v92() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
  source_amount NUMERIC(20,6);
  source_state TEXT;
  source_maker UUID;
  allocated NUMERIC(20,6);
  line_count INTEGER;
BEGIN
  IF TG_OP='INSERT' THEN
    SELECT s.amount,s.review_state INTO source_amount,source_state
      FROM finance_cost_source_v91 s
      WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.source_id FOR UPDATE;
    IF source_state IS DISTINCT FROM 'REVIEWED_UNVERIFIED' THEN
      RAISE EXCEPTION 'Manual cost source must pass independent metadata review first';
    END IF;
    RETURN NEW;
  END IF;
  IF ROW(NEW.id,NEW.tenant_id,NEW.source_id,NEW.request_key,NEW.target_kind,
         NEW.proposed_by,NEW.proposed_at)
     IS DISTINCT FROM
     ROW(OLD.id,OLD.tenant_id,OLD.source_id,OLD.request_key,OLD.target_kind,
         OLD.proposed_by,OLD.proposed_at)
    OR OLD.state <> 'PENDING_REVIEW'
    OR NEW.state NOT IN ('APPROVED_UNVERIFIED','REJECTED')
  THEN
    RAISE EXCEPTION 'Cost proposal has immutable source and one-shot decision';
  END IF;
  SELECT s.amount,s.review_state,s.created_by
    INTO source_amount,source_state,source_maker
    FROM finance_cost_source_v91 s
    WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.source_id FOR UPDATE;
  IF source_state IS DISTINCT FROM 'REVIEWED_UNVERIFIED'
     OR NEW.reviewed_by IS NULL
     OR NEW.reviewed_by=NEW.proposed_by OR NEW.reviewed_by=source_maker
  THEN
    RAISE EXCEPTION 'Independent cost proposal and source review required';
  END IF;
  IF NEW.state='APPROVED_UNVERIFIED' THEN
    SELECT COALESCE(SUM(l.expense_amount),0),COUNT(*)
      INTO allocated,line_count FROM finance_cost_allocation_line_v92 l
      WHERE l.tenant_id=NEW.tenant_id AND l.batch_id=NEW.id;
    IF line_count<1 OR line_count>100 OR allocated>source_amount THEN
      RAISE EXCEPTION 'Cost allocation must be nonempty and not overallocate';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER finance_cost_allocation_batch_guard_v92
  BEFORE INSERT OR UPDATE ON finance_cost_allocation_batch_v92
  FOR EACH ROW EXECUTE FUNCTION finance_cost_allocation_batch_guard_v92();

CREATE TABLE finance_cost_allocation_audit_v92 (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  batch_id UUID NOT NULL,
  action VARCHAR(24) NOT NULL CHECK(action IN (
    'PROPOSED','APPROVED_UNVERIFIED','REJECTED')),
  actor_id UUID NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,batch_id)
    REFERENCES finance_cost_allocation_batch_v92(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_cost_allocation_audit_recent_v92
  ON finance_cost_allocation_audit_v92(tenant_id,batch_id,occurred_at DESC,id DESC);

-- Preserve both existing source-review audit history (V91) and new V92
-- proposal decisions even from accidental SQL UPDATE/DELETE.
CREATE FUNCTION finance_cost_audit_append_only_v92() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Cost evidence audit history is append-only';
END;
$$;
CREATE TRIGGER finance_cost_source_audit_append_only_v92
  BEFORE UPDATE OR DELETE ON finance_cost_source_audit_v91
  FOR EACH ROW EXECUTE FUNCTION finance_cost_audit_append_only_v92();
CREATE TRIGGER finance_cost_allocation_audit_append_only_v92
  BEFORE UPDATE OR DELETE ON finance_cost_allocation_audit_v92
  FOR EACH ROW EXECUTE FUNCTION finance_cost_audit_append_only_v92();
