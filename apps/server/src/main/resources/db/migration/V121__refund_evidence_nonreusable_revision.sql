-- V121: V34/V35 local evidence row identities must never be reused.
-- Existing V63 digests are intentionally stale under the new digest version;
-- no review history, financial amount or V119 reservation is rewritten.
CREATE SEQUENCE finance_refund_evidence_revision_v121 AS bigint NO CYCLE;

ALTER TABLE finance_refund_item_review
  ADD COLUMN evidence_revision_v121 bigint NOT NULL
    DEFAULT nextval('finance_refund_evidence_revision_v121'),
  ADD CONSTRAINT refund_item_evidence_revision_v121_positive CHECK(evidence_revision_v121>0);

ALTER TABLE finance_refund_amount_allocation
  ADD COLUMN evidence_revision_v121 bigint NOT NULL
    DEFAULT nextval('finance_refund_evidence_revision_v121'),
  ADD CONSTRAINT refund_amount_evidence_revision_v121_positive CHECK(evidence_revision_v121>0);

ALTER TABLE finance_refund_item_review ALTER COLUMN evidence_revision_v121 DROP DEFAULT;
ALTER TABLE finance_refund_amount_allocation ALTER COLUMN evidence_revision_v121 DROP DEFAULT;

CREATE FUNCTION assign_refund_evidence_revision_v121() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='UPDATE' THEN
    IF NEW IS NOT DISTINCT FROM OLD THEN
      RETURN NEW;
    END IF;
  END IF;
  -- Never accept a caller-supplied version, even on recreation of a row.
  NEW.evidence_revision_v121 := nextval('finance_refund_evidence_revision_v121');
  RETURN NEW;
END;
$$;

CREATE TRIGGER refund_item_evidence_revision_v121
  BEFORE INSERT OR UPDATE ON finance_refund_item_review
  FOR EACH ROW EXECUTE FUNCTION assign_refund_evidence_revision_v121();

CREATE TRIGGER refund_amount_evidence_revision_v121
  BEFORE INSERT OR UPDATE ON finance_refund_amount_allocation
  FOR EACH ROW EXECUTE FUNCTION assign_refund_evidence_revision_v121();
