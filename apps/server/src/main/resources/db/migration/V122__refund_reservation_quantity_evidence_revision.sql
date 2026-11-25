-- V122 / V263: bind immutable V119 coordinates to the exact V121 V34 row.
-- Legacy reservations intentionally retain NULL, never fabricate a capture.
ALTER TABLE finance_refund_cost_slice_v119
 ADD COLUMN review_evidence_revision_v122 bigint
 CHECK(review_evidence_revision_v122>0);

CREATE FUNCTION finance_refund_cost_slice_version_v122() RETURNS trigger
LANGUAGE plpgsql AS $v122$
DECLARE
 captured bigint;
BEGIN
 -- PostgreSQL executes same-kind triggers alphabetically: the V119 capture
 -- trigger has already locked the original item and the source/review rows.
 -- Recheck the exact V119 snapshot identity before recording the new version.
 SELECT r.evidence_revision_v121 INTO captured
 FROM finance_refund_item_review r
 JOIN finance_sale_lot_claim_v106 c
   ON c.tenant_id=r.tenant_id AND c.sales_order_id=r.sales_order_id
   AND c.snapshot_external_item_id=r.external_item_id
 WHERE c.tenant_id=NEW.tenant_id AND c.id=NEW.claim_id
   AND c.order_item_id=NEW.order_item_id AND r.event_id=NEW.refund_event_id
   AND r.revision=NEW.review_revision AND r.reviewed_quantity=NEW.review_quantity
   AND r.reviewed_at=NEW.review_at AND r.disposition='RESTOCKED_CLAIMED'
 FOR SHARE OF r;
 IF NOT FOUND OR captured IS NULL THEN
   RAISE EXCEPTION 'V122: exact refund quantity evidence required';
 END IF;
 IF EXISTS (
   SELECT 1 FROM finance_refund_cost_slice_v119 s
   WHERE s.tenant_id=NEW.tenant_id AND s.refund_event_id=NEW.refund_event_id
     AND s.order_item_id=NEW.order_item_id
     AND s.review_evidence_revision_v122 IS DISTINCT FROM captured
 ) THEN
   RAISE EXCEPTION 'V122: stale or legacy peer refund reservation';
 END IF;
 -- Never trust an explicitly supplied token.
 NEW.review_evidence_revision_v122=captured;
 RETURN NEW;
END
$v122$;

CREATE TRIGGER finance_refund_cost_slice_version_v122
 BEFORE INSERT ON finance_refund_cost_slice_v119
 FOR EACH ROW EXECUTE FUNCTION finance_refund_cost_slice_version_v122();

COMMENT ON COLUMN finance_refund_cost_slice_v119.review_evidence_revision_v122 IS
 'V34 evidence row version at reservation capture; NULL legacy evidence is not current';
