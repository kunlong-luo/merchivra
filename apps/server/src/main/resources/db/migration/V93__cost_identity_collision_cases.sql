-- V93 / V166: non-accounting collision review cases for exact local identity overlaps.
-- Neither "not same charge" nor "potential duplicate" removes V165 holds.
-- Candidate IDs refer to heterogeneous existing local-source tables; the
-- application must verify tenant/Marketplace/identity at discovery time.
CREATE TABLE finance_cost_identity_case_v93 (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  seller_marketplace_id UUID NOT NULL,
  source_id UUID NOT NULL,
  signal_code VARCHAR(64) NOT NULL CHECK(signal_code IN (
    'OTHER_MANUAL_COST_SAME_CLAIMED_DOCUMENT',
    'MANUAL_ADS_SPEND_SAME_KEY',
    'OFFLINE_ADS_REPORT_SAME_REFERENCE',
    'OFFLINE_ADS_REPORT_SAME_CLAIMED_DOCUMENT',
    'SPAPI_FINANCES_EVENT_SAME_KEY',
    'SPAPI_SETTLEMENT_REPORT_SAME_REFERENCE',
    'SPAPI_SETTLEMENT_REPORT_SAME_CLAIMED_DOCUMENT',
    'MANUAL_SETTLEMENT_STATEMENT_SAME_REFERENCE')),
  candidate_id UUID NOT NULL,
  evidence_origin VARCHAR(24) NOT NULL DEFAULT 'LOCAL_IDENTITY_ONLY'
    CHECK(evidence_origin='LOCAL_IDENTITY_ONLY'),
  state VARCHAR(36) NOT NULL DEFAULT 'OPEN'
    CHECK(state IN ('OPEN','ESCALATED_NEEDS_PROOF',
                    'POTENTIAL_DUPLICATE','NOT_SAME_CHARGE_UNVERIFIED')),
  opened_by UUID NOT NULL,
  opened_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_by UUID,
  reviewed_at TIMESTAMPTZ,
  review_note VARCHAR(500),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,source_id,signal_code,candidate_id),
  FOREIGN KEY(tenant_id,source_id)
    REFERENCES finance_cost_source_v91(tenant_id,id),
  FOREIGN KEY(tenant_id,seller_marketplace_id)
    REFERENCES seller_marketplace(tenant_id,id),
  FOREIGN KEY(tenant_id,opened_by) REFERENCES membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by) REFERENCES membership(tenant_id,user_id),
  CHECK(
    (state='OPEN' AND reviewed_by IS NULL AND reviewed_at IS NULL
       AND review_note IS NULL)
    OR
    (state<>'OPEN' AND reviewed_by IS NOT NULL
       AND reviewed_by<>opened_by AND reviewed_at IS NOT NULL
       AND length(btrim(review_note)) BETWEEN 10 AND 500
       AND position(chr(10) IN review_note)=0
       AND position(chr(13) IN review_note)=0)
  )
);
CREATE INDEX finance_cost_identity_case_recent_v93
  ON finance_cost_identity_case_v93
    (tenant_id,source_id,opened_at DESC,id DESC);

-- Protect original candidate and source scope and one-shot outcome in the DB;
-- never upgrade an operator-provided document digest to independent proof.
CREATE FUNCTION finance_cost_identity_case_guard_v93() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE source_maker UUID;
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Cost collision cases cannot be deleted';
  END IF;
  SELECT created_by INTO source_maker FROM finance_cost_source_v91
   WHERE tenant_id=NEW.tenant_id
     AND seller_marketplace_id=NEW.seller_marketplace_id
     AND id=NEW.source_id FOR UPDATE;
  IF source_maker IS NULL THEN
    RAISE EXCEPTION 'Collision case source outside tenant marketplace';
  END IF;
  IF TG_OP='INSERT' THEN
    IF NEW.state<>'OPEN' OR NEW.opened_by IS NULL THEN
      RAISE EXCEPTION 'Collision cases must start OPEN';
    END IF;
    RETURN NEW;
  END IF;
  IF ROW(NEW.id,NEW.tenant_id,NEW.seller_marketplace_id,NEW.source_id,
         NEW.signal_code,NEW.candidate_id,NEW.evidence_origin,
         NEW.opened_by,NEW.opened_at)
     IS DISTINCT FROM
     ROW(OLD.id,OLD.tenant_id,OLD.seller_marketplace_id,OLD.source_id,
         OLD.signal_code,OLD.candidate_id,OLD.evidence_origin,
         OLD.opened_by,OLD.opened_at)
     OR OLD.state<>'OPEN' OR NEW.state='OPEN'
     OR NEW.reviewed_by IS NULL OR NEW.reviewed_by=source_maker
     OR NEW.reviewed_by=OLD.opened_by
  THEN RAISE EXCEPTION 'Collision review requires independent one-shot decision';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER finance_cost_identity_case_guard_trigger_v93
  BEFORE INSERT OR UPDATE OR DELETE ON finance_cost_identity_case_v93
  FOR EACH ROW EXECUTE FUNCTION finance_cost_identity_case_guard_v93();

CREATE TABLE finance_cost_identity_case_audit_v93 (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  case_id UUID NOT NULL,
  action VARCHAR(36) NOT NULL
    CHECK(action IN ('OPEN','ESCALATED_NEEDS_PROOF',
                     'POTENTIAL_DUPLICATE','NOT_SAME_CHARGE_UNVERIFIED')),
  actor_id UUID NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(tenant_id,case_id)
    REFERENCES finance_cost_identity_case_v93(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_cost_identity_case_audit_recent_v93
  ON finance_cost_identity_case_audit_v93
    (tenant_id,case_id,occurred_at DESC,id DESC);
CREATE TRIGGER finance_cost_identity_case_audit_append_only_v93
  BEFORE UPDATE OR DELETE ON finance_cost_identity_case_audit_v93
  FOR EACH ROW EXECUTE FUNCTION finance_cost_audit_append_only_v92();
