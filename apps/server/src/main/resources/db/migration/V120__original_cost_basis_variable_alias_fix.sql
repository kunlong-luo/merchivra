-- V258 / Flyway V120. Repair V119 PL/pgSQL variable/column ambiguity.
-- Preserve V119 history, scope and exact arithmetic; no backfill or approval.
CREATE OR REPLACE FUNCTION finance_original_cost_basis_capture_v119() RETURNS trigger
LANGUAGE plpgsql AS $v119$
DECLARE
 c RECORD;
 parts TEXT[];
 token_count INTEGER;
 ix INTEGER;
 po_count INTEGER:=0;
 freight_count INTEGER:=0;
 duty_count INTEGER:=0;
 expense_count INTEGER:=0;
 seen UUID[]:=ARRAY[]::UUID[];
 source_uuid UUID;
 v_receipt_source_amount NUMERIC;
 v_original_claim_amount NUMERIC;
 computed NUMERIC;
 total NUMERIC:=0;
BEGIN
 -- This observation is never represented as the original sale-time posting.
 NEW.recorded_at=clock_timestamp();
 SELECT cl.*,r.basis_quantity,r.unit_from,r.unit_to INTO c
 FROM finance_sale_lot_claim_v106 cl
 JOIN finance_sale_lot_cost_range_v117 r
   ON r.tenant_id=cl.tenant_id AND r.claim_id=cl.id
 JOIN finance_sale_lot_integrity_v106 v
   ON v.tenant_id=cl.tenant_id AND v.claim_id=cl.id
 WHERE cl.tenant_id=NEW.tenant_id AND cl.id=NEW.claim_id
   AND v.integrity_state='LOCAL_LINK_INTACT_UNVERIFIED';
 IF NOT FOUND OR c.snapshot_receipt_quantity<>c.basis_quantity THEN
   RAISE EXCEPTION 'V119: original local range is unavailable or stale';
 END IF;
 parts=string_to_array(NEW.canonical_payload,'|');
 token_count=cardinality(parts);
 IF token_count<24 OR token_count>216 OR (token_count-12)%4<>0 OR
   parts[1:11] IS DISTINCT FROM ARRAY[
     'V255_LOCAL_SALE_COST_BASIS_V1',NEW.tenant_id::text,c.marketplace_id::text,
     c.receipt_id::text,c.id::text,c.sales_order_id::text,c.order_item_id::text,
     btrim(c.snapshot_currency),c.basis_quantity::text,c.unit_from::text,c.unit_to::text] OR
   parts[12] !~ '^[0-9]{1,16}\.[0-9]{6}$' THEN
   RAISE EXCEPTION 'V119: basis scope or canonical amount invalid';
 END IF;
 FOR ix IN 13..token_count BY 4 LOOP
   IF parts[ix+2] !~ '^[0-9]{1,14}\.[0-9]{6}$' OR
      parts[ix+3] !~ '^[0-9]{1,14}\.[0-9]{6}$' THEN
     RAISE EXCEPTION 'V119: invalid source amounts';
   END IF;
   v_receipt_source_amount=parts[ix+2]::numeric;
   v_original_claim_amount=parts[ix+3]::numeric;
   -- Exact integer micro-unit floor differences, no numeric division rounding.
   computed=(div(v_receipt_source_amount*1000000*c.unit_to,c.basis_quantity)
      -div(v_receipt_source_amount*1000000*c.unit_from,c.basis_quantity))/1000000;
   IF computed<>v_original_claim_amount THEN
     RAISE EXCEPTION 'V119: source allocation does not recompute';
   END IF;
   IF parts[ix]='PURCHASE_PO_REFERENCE' THEN
     po_count=po_count+1;
     IF parts[ix+1]<>'PO' OR v_receipt_source_amount<>c.snapshot_unit_cost*c.basis_quantity THEN
       RAISE EXCEPTION 'V119: original PO amount mismatch';
     END IF;
   ELSE
     source_uuid=parts[ix+1]::uuid;
     IF source_uuid=ANY(seen) OR parts[ix] NOT IN ('INBOUND_FREIGHT','IMPORT_DUTY') THEN
       RAISE EXCEPTION 'V119: duplicate or unsupported cost source';
     END IF;
     seen=array_append(seen,source_uuid);
     IF NOT EXISTS (
       SELECT 1 FROM finance_receipt_cost_line_v112 l
       JOIN finance_receipt_cost_plan_v112 p ON p.tenant_id=l.tenant_id AND p.id=l.plan_id
       JOIN finance_cost_source_v91 s ON s.tenant_id=p.tenant_id AND s.id=p.source_id
       WHERE l.tenant_id=NEW.tenant_id AND l.receipt_id=c.receipt_id
         AND p.source_id=source_uuid AND s.seller_marketplace_id=c.marketplace_id
         AND s.category=parts[ix] AND s.currency_code=c.snapshot_currency
         AND s.review_state='REVIEWED_UNVERIFIED' AND p.state='APPROVED_UNVERIFIED'
         AND l.amount=v_receipt_source_amount
     ) THEN RAISE EXCEPTION 'V119: receipt source mismatch'; END IF;
     IF parts[ix]='INBOUND_FREIGHT' THEN freight_count=freight_count+1;
     ELSE duty_count=duty_count+1; END IF;
   END IF;
   total=total+v_original_claim_amount;
 END LOOP;
 SELECT count(*) INTO expense_count FROM finance_receipt_cost_line_v112 l
 WHERE l.tenant_id=NEW.tenant_id AND l.receipt_id=c.receipt_id;
 IF po_count<>1 OR freight_count<1 OR duty_count<1
   OR expense_count<>cardinality(seen) OR total<>parts[12]::numeric THEN
   RAISE EXCEPTION 'V119: original cost source population or total mismatch';
 END IF;
 RETURN NEW;
END
$v119$;
