-- V256 / Flyway V119. Local observations only, no sale-time accounting or
-- physical refund proof. No backfill or public write API.
CREATE TABLE finance_original_cost_basis_v119 (
 tenant_id UUID NOT NULL,
 claim_id UUID NOT NULL,
 basis_sha256 CHAR(64) NOT NULL CHECK(basis_sha256 ~ '^[0-9a-f]{64}$'),
 canonical_payload TEXT NOT NULL CHECK(length(canonical_payload) BETWEEN 1 AND 16384),
 recorded_by UUID NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,claim_id),
 UNIQUE(tenant_id,claim_id,basis_sha256),
 FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_sale_lot_cost_range_v117(tenant_id,claim_id),
 FOREIGN KEY(tenant_id,recorded_by) REFERENCES membership(tenant_id,user_id),
 CHECK(basis_sha256=encode(sha256(convert_to(canonical_payload,'UTF8')),'hex'))
);

CREATE FUNCTION finance_original_cost_basis_capture_v119() RETURNS trigger
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
 source_amount NUMERIC;
 claim_amount NUMERIC;
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
   source_amount=parts[ix+2]::numeric;
   claim_amount=parts[ix+3]::numeric;
   -- Exact integer micro-unit floor differences, no numeric division rounding.
   computed=(div(source_amount*1000000*c.unit_to,c.basis_quantity)
      -div(source_amount*1000000*c.unit_from,c.basis_quantity))/1000000;
   IF computed<>claim_amount THEN
     RAISE EXCEPTION 'V119: source allocation does not recompute';
   END IF;
   IF parts[ix]='PURCHASE_PO_REFERENCE' THEN
     po_count=po_count+1;
     IF parts[ix+1]<>'PO' OR source_amount<>c.snapshot_unit_cost*c.basis_quantity THEN
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
         AND l.amount=source_amount
     ) THEN RAISE EXCEPTION 'V119: receipt source mismatch'; END IF;
     IF parts[ix]='INBOUND_FREIGHT' THEN freight_count=freight_count+1;
     ELSE duty_count=duty_count+1; END IF;
   END IF;
   total=total+claim_amount;
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
CREATE TRIGGER finance_original_cost_basis_capture_v119
 BEFORE INSERT ON finance_original_cost_basis_v119
 FOR EACH ROW EXECUTE FUNCTION finance_original_cost_basis_capture_v119();
CREATE TRIGGER finance_original_cost_basis_immutable_v119
 BEFORE UPDATE OR DELETE ON finance_original_cost_basis_v119
 FOR EACH ROW EXECUTE FUNCTION finance_sale_lot_immutable_v106();

-- Reservations are local proposed refund coordinates, not financial postings.
-- A single event can span multiple original claims, but its reviewed item
-- quantity is conserved across all of them. No history is recycled.
CREATE TABLE finance_refund_cost_slice_v119 (
 tenant_id UUID NOT NULL,
 claim_id UUID NOT NULL,
 basis_sha256 CHAR(64) NOT NULL,
 refund_event_id UUID NOT NULL,
 unit_from INTEGER NOT NULL CHECK(unit_from>=0),
 unit_to INTEGER NOT NULL CHECK(unit_to>unit_from),
 review_revision INTEGER NOT NULL CHECK(review_revision>0),
 review_quantity INTEGER NOT NULL CHECK(review_quantity>0),
 review_at TIMESTAMPTZ NOT NULL,
 order_item_id UUID NOT NULL,
 recorded_by UUID NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,claim_id,refund_event_id),
 FOREIGN KEY(tenant_id,claim_id,basis_sha256)
   REFERENCES finance_original_cost_basis_v119(tenant_id,claim_id,basis_sha256),
 FOREIGN KEY(tenant_id,refund_event_id) REFERENCES finance_source_event(tenant_id,id),
 FOREIGN KEY(tenant_id,order_item_id) REFERENCES order_item(tenant_id,id),
 FOREIGN KEY(tenant_id,recorded_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_refund_slice_event_item_v119
 ON finance_refund_cost_slice_v119(tenant_id,refund_event_id,order_item_id);
CREATE FUNCTION finance_refund_cost_slice_capture_v119() RETURNS trigger
LANGUAGE plpgsql AS $v119$
DECLARE
 c RECORD;
 rv RECORD;
 used BIGINT;
BEGIN
 IF current_setting('transaction_isolation')<>'read committed' THEN
   RAISE EXCEPTION 'V119: refund reservation requires READ COMMITTED';
 END IF;
 SELECT cl.*,r.unit_from AS original_from,r.unit_to AS original_to INTO c
 FROM finance_sale_lot_claim_v106 cl
 JOIN finance_sale_lot_cost_range_v117 r ON r.tenant_id=cl.tenant_id AND r.claim_id=cl.id
 JOIN finance_original_cost_basis_v119 b ON b.tenant_id=cl.tenant_id AND b.claim_id=cl.id
 WHERE cl.tenant_id=NEW.tenant_id AND cl.id=NEW.claim_id AND b.basis_sha256=NEW.basis_sha256;
 IF NOT FOUND THEN RAISE EXCEPTION 'V119: original basis unavailable'; END IF;
 -- Serialize all claims for the same item, including separate receipts and
 -- events; the next statements see committed rivals at READ COMMITTED.
 PERFORM 1 FROM order_item i WHERE i.tenant_id=NEW.tenant_id AND i.id=c.order_item_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'V119: original item missing'; END IF;
 IF NOT EXISTS(SELECT 1 FROM finance_sale_lot_integrity_v106 v
   WHERE v.tenant_id=NEW.tenant_id AND v.claim_id=NEW.claim_id
     AND v.integrity_state='LOCAL_LINK_INTACT_UNVERIFIED') THEN
   RAISE EXCEPTION 'V119: original claim stale or retracted';
 END IF;
 IF NEW.unit_from IS NULL OR NEW.unit_to IS NULL OR NEW.unit_from<c.original_from
   OR NEW.unit_to>c.original_to OR NEW.unit_to<=NEW.unit_from THEN
   RAISE EXCEPTION 'V119: refund outside original sale range';
 END IF;
 SELECT r.revision,r.reviewed_quantity,r.reviewed_at INTO rv
 FROM finance_refund_item_review r
 JOIN finance_source_event f ON f.tenant_id=r.tenant_id AND f.id=r.event_id
 JOIN finance_event_link_review l ON l.tenant_id=f.tenant_id AND l.event_id=f.id
 WHERE r.tenant_id=NEW.tenant_id AND r.event_id=NEW.refund_event_id
   AND r.sales_order_id=c.sales_order_id AND r.external_item_id=c.snapshot_external_item_id
   AND r.disposition='RESTOCKED_CLAIMED' AND f.seller_marketplace_id=c.marketplace_id
   AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
   AND f.category='PRODUCT_REFUND' AND f.amount<0
   AND l.state='MATCHED' AND l.order_id=c.sales_order_id
 FOR SHARE OF r,f,l;
 IF NOT FOUND THEN RAISE EXCEPTION 'V119: matching local refund review required'; END IF;
 IF EXISTS(SELECT 1 FROM finance_refund_cost_slice_v119 s
   WHERE s.tenant_id=NEW.tenant_id AND s.claim_id=NEW.claim_id
     AND s.unit_from<NEW.unit_to AND NEW.unit_from<s.unit_to) THEN
   RAISE EXCEPTION 'V119: overlapping refund reservation';
 END IF;
 SELECT coalesce(sum(s.unit_to::bigint-s.unit_from::bigint),0) INTO used
 FROM finance_refund_cost_slice_v119 s
 WHERE s.tenant_id=NEW.tenant_id AND s.refund_event_id=NEW.refund_event_id
   AND s.order_item_id=c.order_item_id;
 IF used+NEW.unit_to::bigint-NEW.unit_from::bigint>rv.reviewed_quantity THEN
   RAISE EXCEPTION 'V119: refund item quantity exceeded';
 END IF;
 NEW.order_item_id=c.order_item_id;
 NEW.review_revision=rv.revision;
 NEW.review_quantity=rv.reviewed_quantity;
 NEW.review_at=rv.reviewed_at;
 NEW.recorded_at=clock_timestamp();
 RETURN NEW;
END
$v119$;
CREATE TRIGGER finance_refund_cost_slice_capture_v119
 BEFORE INSERT ON finance_refund_cost_slice_v119
 FOR EACH ROW EXECUTE FUNCTION finance_refund_cost_slice_capture_v119();
CREATE TRIGGER finance_refund_cost_slice_immutable_v119
 BEFORE UPDATE OR DELETE ON finance_refund_cost_slice_v119
 FOR EACH ROW EXECUTE FUNCTION finance_sale_lot_immutable_v106();
COMMENT ON TABLE finance_original_cost_basis_v119 IS 'Immutable local observation, not original sale-time cost or independently verified COGS';
COMMENT ON TABLE finance_refund_cost_slice_v119 IS 'Permanent local refund reservations only; not physical return or ledger posting';
