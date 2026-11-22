-- V103 / product V193: database-enforced V64 immutable financial snapshots.
-- V62 per-order SHA-256 is LOCAL capture metadata, NOT a third-party proof.
-- V192 read-time SKU arithmetic is retained; this migration prevents later
-- direct SQL from changing a captured order or a saved SKU and prevents
-- late append after a batch advances. No history backfill or external trust.
--
-- Historical rows are protected FROM migration time onward; do not pretend
-- their pre-V103 history was verified.
CREATE FUNCTION finance_profit_report_job_guard_v103()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'V64 report cohort jobs cannot be deleted';
  END IF;
  IF OLD.state='SUCCEEDED'
     OR ROW(OLD.id,OLD.request_key,OLD.tenant_id,
       OLD.seller_marketplace_id,OLD.created_by,OLD.period_after,
       OLD.period_before,OLD.currency_code,OLD.total_orders,
       OLD.manual_ads_reference,OLD.manual_ads_count,OLD.created_at)
     IS DISTINCT FROM
       ROW(NEW.id,NEW.request_key,NEW.tenant_id,
       NEW.seller_marketplace_id,NEW.created_by,NEW.period_after,
       NEW.period_before,NEW.currency_code,NEW.total_orders,
       NEW.manual_ads_reference,NEW.manual_ads_count,NEW.created_at)
  THEN
    RAISE EXCEPTION 'V64 immutable cohort identity or completed job changed';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER finance_profit_report_job_guard_trigger_v103
  BEFORE UPDATE OR DELETE ON finance_profit_report_job
  FOR EACH ROW EXECUTE FUNCTION finance_profit_report_job_guard_v103();

CREATE FUNCTION finance_profit_report_order_guard_v103()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  job_state text;
  already_processed integer;
  total integer;
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'V64 cohort order rows cannot be deleted';
  END IF;
  -- The job lock serializes state/counter checks with another worker's
  -- state transition. It never grants approval of underlying Finances.
  SELECT state,processed_orders,total_orders
    INTO job_state,already_processed,total
  FROM finance_profit_report_job
  WHERE tenant_id=NEW.tenant_id AND id=NEW.job_id
  FOR SHARE;
  IF NOT FOUND OR job_state NOT IN ('READY','RETRY')
      OR NEW.cohort_position<already_processed
      OR NEW.cohort_position>=total THEN
    RAISE EXCEPTION 'V64 order must belong to an unprocessed active cohort';
  END IF;
  IF TG_OP='INSERT' THEN
    IF NEW.state<>'PENDING' THEN
      RAISE EXCEPTION 'V64 cohort must be captured as PENDING';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.state<>'PENDING'
    OR NEW.state NOT IN ('ELIGIBLE','BLOCKED')
    OR ROW(OLD.tenant_id,OLD.job_id,OLD.order_id,OLD.cohort_position)
      IS DISTINCT FROM
       ROW(NEW.tenant_id,NEW.job_id,NEW.order_id,NEW.cohort_position)
  THEN
    RAISE EXCEPTION 'Frozen V64 order evidence is immutable after first capture';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER finance_profit_report_order_guard_trigger_v103
  BEFORE INSERT OR UPDATE OR DELETE ON finance_profit_report_order
  FOR EACH ROW EXECUTE FUNCTION finance_profit_report_order_guard_v103();

CREATE FUNCTION finance_profit_report_sku_guard_v103()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  job_state text;
  already_processed integer;
  order_state text;
  order_position integer;
BEGIN
  IF TG_OP<>'INSERT' THEN
    RAISE EXCEPTION 'Frozen V64 SKU evidence is append-only';
  END IF;
  -- Same lock order as V64 processing: job first, then source cohort row.
  -- Waiting inserts recheck the committed processed counter and terminal
  -- state; a past order cannot gain a new SKU after its batch commits.
  SELECT state,processed_orders INTO job_state,already_processed
  FROM finance_profit_report_job
  WHERE tenant_id=NEW.tenant_id AND id=NEW.job_id
  FOR SHARE;
  IF NOT FOUND OR job_state NOT IN ('READY','RETRY') THEN
    RAISE EXCEPTION 'Late SKU append after V64 job completion prohibited';
  END IF;
  SELECT o.state,o.cohort_position INTO order_state,order_position
  FROM finance_profit_report_order o
  WHERE tenant_id=NEW.tenant_id AND job_id=NEW.job_id
    AND order_id=NEW.order_id
  FOR UPDATE;
  IF NOT FOUND OR order_state<>'ELIGIBLE'
     OR order_position<already_processed THEN
    RAISE EXCEPTION 'V64 SKU must belong to the currently captured eligible order';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER finance_profit_report_sku_guard_trigger_v103
  BEFORE INSERT OR UPDATE OR DELETE ON finance_profit_report_sku
  FOR EACH ROW EXECUTE FUNCTION finance_profit_report_sku_guard_v103();

CREATE FUNCTION finance_profit_report_audit_guard_v103()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'V64 report audit events are append-only';
END;
$$;
CREATE TRIGGER finance_profit_report_audit_guard_trigger_v103
  BEFORE UPDATE OR DELETE ON finance_profit_report_audit
  FOR EACH ROW EXECUTE FUNCTION finance_profit_report_audit_guard_v103();
