-- V95 / V168: reverse line-source lookup and fail-closed allocation guard.
-- One report line linked by two manual costs is an unresolved provenance
-- COLLISION, not proof that either source is a real duplicate debit.
CREATE INDEX finance_cost_settlement_line_reverse_v95
  ON finance_cost_settlement_line_link_v94
  (tenant_id,seller_marketplace_id,report_id,line_number,source_id);

-- Linking a line must never permit later mutation of its external reference,
-- debit, currency, or transaction kind. The V94 link trigger locks the row
-- FOR SHARE on insert; UPDATE/DELETE wait and then check this constraint.
CREATE FUNCTION finance_settlement_line_linked_immutable_v95()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM finance_cost_settlement_line_link_v94 x
    WHERE x.tenant_id=OLD.tenant_id
      AND x.report_id=OLD.report_id AND x.line_number=OLD.line_number
  ) THEN
    RAISE EXCEPTION 'Settlement report lines with expense links are immutable';
  END IF;
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END;
$$;
CREATE TRIGGER finance_settlement_line_linked_immutable_trigger_v95
  BEFORE UPDATE OR DELETE ON spapi_settlement_report_line
  FOR EACH ROW EXECUTE FUNCTION finance_settlement_line_linked_immutable_v95();

-- Defense in depth: direct SQL cannot approve an allocation whose source
-- shares ANY concrete report/line identity with a different manual source.
-- Evidence can still be added *after* a historical unverified approval;
-- reports and previews must detect this later collision, never hide it.
CREATE FUNCTION finance_cost_allocation_shared_line_guard_v95()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='UPDATE' AND NEW.state='APPROVED_UNVERIFIED'
    AND EXISTS (
      SELECT 1 FROM finance_cost_settlement_line_link_v94 x
      JOIN finance_cost_settlement_line_link_v94 y
        ON y.tenant_id=x.tenant_id
        AND y.seller_marketplace_id=x.seller_marketplace_id
        AND y.report_id=x.report_id
        AND y.line_number=x.line_number
        AND y.source_id<>x.source_id
      WHERE x.tenant_id=NEW.tenant_id AND x.source_id=NEW.source_id
  ) THEN
    RAISE EXCEPTION 'Shared settlement line source collision requires proof';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER finance_cost_allocation_shared_line_guard_trigger_v95
  BEFORE UPDATE ON finance_cost_allocation_batch_v92
  FOR EACH ROW EXECUTE FUNCTION finance_cost_allocation_shared_line_guard_v95();
