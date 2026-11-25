-- V248 / Flyway V118: disambiguate the relation alias from trigger OLD.
-- Preserve V117 checks, lock ordering, and append-only historical migration.
CREATE OR REPLACE FUNCTION finance_sale_lot_cost_range_capture_v117() RETURNS trigger
LANGUAGE plpgsql AS $v117$
DECLARE
  claim_row RECORD;
BEGIN
  -- A receipt row lock plus a fresh snapshot for the subsequent overlap
  -- scan is required. No application write API is exposed; reject stale
  -- RR/SERIALIZABLE snapshots rather than trust an invisible competing row.
  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'V117: range binding requires READ COMMITTED';
  END IF;
  SELECT c.receipt_id,c.assigned_quantity,c.snapshot_accepted_quantity
    INTO claim_row FROM finance_sale_lot_claim_v106 c
    WHERE c.tenant_id=NEW.tenant_id AND c.id=NEW.claim_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'V117: original local claim not found in tenant';
  END IF;
  PERFORM 1 FROM erp_purchase_receipt r
    WHERE r.tenant_id=NEW.tenant_id AND r.id=claim_row.receipt_id
    FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'V117: original receipt missing';
  END IF;
  -- Freeze the V106 accepted-unit reference, NOT an approved capitalization
  -- denominator. Ignore any caller-supplied receipt or basis snapshot.
  NEW.receipt_id=claim_row.receipt_id;
  NEW.basis_quantity=claim_row.snapshot_accepted_quantity;
  IF NEW.unit_from IS NULL OR NEW.unit_to IS NULL
    OR NEW.unit_from<0 OR NEW.unit_to>NEW.basis_quantity
    OR NEW.unit_to::bigint-NEW.unit_from::bigint<>claim_row.assigned_quantity THEN
    RAISE EXCEPTION 'V117: range length or bounds do not match original claim';
  END IF;
  IF EXISTS(SELECT 1 FROM finance_sale_lot_retraction_v106 ret
    WHERE ret.tenant_id=NEW.tenant_id AND ret.claim_id=NEW.claim_id) THEN
    RAISE EXCEPTION 'V117: retracted claim cannot receive a new range';
  END IF;
  IF EXISTS(SELECT 1 FROM finance_sale_lot_cost_range_v117 existing_range
    WHERE existing_range.tenant_id=NEW.tenant_id AND existing_range.receipt_id=NEW.receipt_id
      AND (existing_range.basis_quantity<>NEW.basis_quantity OR
        (existing_range.unit_from<NEW.unit_to AND NEW.unit_from<existing_range.unit_to))) THEN
    RAISE EXCEPTION 'V117: overlapping range or inconsistent receipt basis';
  END IF;
  RETURN NEW;
END
$v117$;
