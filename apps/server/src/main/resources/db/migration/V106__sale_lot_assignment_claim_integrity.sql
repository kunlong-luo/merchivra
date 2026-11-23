-- V203 / Flyway V106. Isolated sale-to-local-pick CLAIMS, never verified COGS.
-- An internal pick is NOT Amazon delivery, an external digest is NOT proof of
-- operator identity, and revoking a claim does NOT recycle physical units.
-- Zero historical backfills: no guessed FIFO, synthetic WMS, or false approvals.

ALTER TABLE order_item ADD CONSTRAINT order_item_tenant_identity_v106
  UNIQUE (tenant_id,id);
ALTER TABLE erp_local_lot_pick_line ADD CONSTRAINT erp_lot_pick_line_tenant_identity_v106
  UNIQUE (tenant_id,id);

CREATE TABLE finance_sale_lot_claim_v106 (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  marketplace_id UUID NOT NULL,
  sales_order_id UUID NOT NULL,
  order_item_id UUID NOT NULL,
  pick_line_id UUID NOT NULL,
  receipt_id UUID NOT NULL,
  assigned_quantity INTEGER NOT NULL CHECK (assigned_quantity BETWEEN 1 AND 1000000),
  external_dispatch_ref VARCHAR(160) NOT NULL
    CHECK (length(btrim(external_dispatch_ref)) BETWEEN 8 AND 160),
  external_payload_sha256 CHAR(64) NOT NULL
    CHECK (external_payload_sha256 ~ '^[0-9a-f]{64}$'),
  contract_version SMALLINT NOT NULL DEFAULT 1 CHECK (contract_version=1),
  snapshot_external_item_id VARCHAR(160) NOT NULL,
  snapshot_item_quantity INTEGER NOT NULL CHECK (snapshot_item_quantity>0),
  snapshot_item_source_updated_at TIMESTAMPTZ,
  snapshot_order_source_updated_at TIMESTAMPTZ,
  snapshot_pick_line_quantity INTEGER NOT NULL CHECK (snapshot_pick_line_quantity>0),
  snapshot_operation_id UUID NOT NULL,
  snapshot_lot_code VARCHAR(80) NOT NULL,
  snapshot_batch_released_at TIMESTAMPTZ NOT NULL,
  snapshot_inspection_id UUID NOT NULL,
  snapshot_accepted_quantity INTEGER NOT NULL CHECK(snapshot_accepted_quantity>0),
  snapshot_receipt_quantity INTEGER NOT NULL CHECK(snapshot_receipt_quantity>0),
  snapshot_purchase_line_id UUID NOT NULL,
  snapshot_product_sku_id UUID NOT NULL,
  snapshot_warehouse_id UUID NOT NULL,
  snapshot_unit_cost NUMERIC(20,6) NOT NULL CHECK(snapshot_unit_cost>=0),
  snapshot_currency CHAR(3) NOT NULL CHECK(snapshot_currency ~ '^[A-Z]{3}$'),
  -- These fields cannot become TRUE via a manual claim or SQL UPDATE.
  operator_identity_verified BOOLEAN NOT NULL DEFAULT FALSE
    CHECK(operator_identity_verified=FALSE),
  platform_fulfillment_verified BOOLEAN NOT NULL DEFAULT FALSE
    CHECK(platform_fulfillment_verified=FALSE),
  actual_batch_cogs_verified BOOLEAN NOT NULL DEFAULT FALSE
    CHECK(actual_batch_cogs_verified=FALSE),
  cogs_posted BOOLEAN NOT NULL DEFAULT FALSE CHECK(cogs_posted=FALSE),
  recorded_by UUID NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,order_item_id,pick_line_id),
  UNIQUE(tenant_id,external_dispatch_ref,pick_line_id),
  FOREIGN KEY(tenant_id,marketplace_id) REFERENCES seller_marketplace(tenant_id,id),
  FOREIGN KEY(tenant_id,sales_order_id) REFERENCES sales_order(tenant_id,id),
  FOREIGN KEY(tenant_id,order_item_id) REFERENCES order_item(tenant_id,id),
  FOREIGN KEY(tenant_id,pick_line_id) REFERENCES erp_local_lot_pick_line(tenant_id,id),
  FOREIGN KEY(tenant_id,receipt_id) REFERENCES erp_purchase_receipt(tenant_id,id),
  FOREIGN KEY(tenant_id,recorded_by) REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_sale_lot_claim_order_v106
  ON finance_sale_lot_claim_v106(tenant_id,marketplace_id,sales_order_id,id);
CREATE INDEX finance_sale_lot_claim_pick_v106
  ON finance_sale_lot_claim_v106(tenant_id,pick_line_id);

CREATE TABLE finance_sale_lot_retraction_v106 (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  claim_id UUID NOT NULL,
  retracted_by UUID NOT NULL,
  reason VARCHAR(500) NOT NULL CHECK(length(btrim(reason)) BETWEEN 10 AND 500),
  retracted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,claim_id),
  FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_sale_lot_claim_v106(tenant_id,id),
  FOREIGN KEY(tenant_id,retracted_by) REFERENCES membership(tenant_id,user_id)
);

-- Serialize on the canonical item and pick-line rows before counting. Both
-- dimensions must conserve quantity, including RETRACTED history; otherwise
-- a retract-and-reassign cycle would double-own a physical picked unit.
CREATE FUNCTION finance_sale_lot_capture_v106() RETURNS trigger AS $$
DECLARE
  sale RECORD;
  picked RECORD;
  item_used BIGINT;
  pick_used BIGINT;
  receipt_used BIGINT;
BEGIN
  SELECT i.quantity,i.external_item_id,i.is_current,i.source_updated_at,
         o.data_origin,o.source_updated_at AS order_updated_at
    INTO sale
    FROM order_item i JOIN sales_order o
      ON o.tenant_id=i.tenant_id AND o.id=i.sales_order_id
    WHERE i.tenant_id=NEW.tenant_id AND i.id=NEW.order_item_id
      AND o.id=NEW.sales_order_id AND o.seller_marketplace_id=NEW.marketplace_id
    FOR UPDATE OF i;
  IF NOT FOUND OR sale.quantity IS NULL OR sale.quantity<=0
    OR sale.is_current IS DISTINCT FROM TRUE OR sale.data_origin<>'LIVE' THEN
    RAISE EXCEPTION 'V106: current LIVE sale item required';
  END IF;

  SELECT pl.quantity,pl.position_id,p.receipt_id,p.product_sku_id,p.warehouse_id,
         pk.operation_id,pk.quantity AS pick_quantity,pk.product_sku_id AS pick_sku,
         pk.warehouse_id AS pick_warehouse,
         op.operation_type,op.source_warehouse_id,op.product_sku_id AS op_sku,
         op.requested_quantity,op.applied_delta,
         b.lot_code,b.status AS batch_status,b.released_at,b.product_sku_id AS batch_sku,
         q.accepted_quantity AS accepted_units,b.inspection_id,
         r.quantity AS receipt_quantity,r.purchase_order_line_id,
         l.unit_cost,po.currency_code
    INTO picked
    FROM erp_local_lot_pick_line pl
    JOIN erp_local_lot_pick pk ON pk.tenant_id=pl.tenant_id AND pk.id=pl.pick_id
    JOIN erp_local_stock_operation op
      ON op.tenant_id=pk.tenant_id AND op.id=pk.operation_id
    JOIN erp_local_lot_position p
      ON p.tenant_id=pl.tenant_id AND p.id=pl.position_id
    JOIN erp_receipt_quality_batch b
      ON b.tenant_id=p.tenant_id AND b.receipt_id=p.receipt_id
    JOIN erp_purchase_receipt r
      ON r.tenant_id=b.tenant_id AND r.id=b.receipt_id
    JOIN erp_purchase_receipt_qc q
      ON q.tenant_id=b.tenant_id AND q.id=b.inspection_id
    JOIN erp_purchase_order_line l
      ON l.tenant_id=r.tenant_id AND l.purchase_order_id=r.purchase_order_id
      AND l.id=r.purchase_order_line_id
    JOIN erp_purchase_order po
      ON po.tenant_id=l.tenant_id AND po.id=l.purchase_order_id
    WHERE pl.tenant_id=NEW.tenant_id AND pl.id=NEW.pick_line_id
    FOR UPDATE OF pl;
  IF NOT FOUND OR picked.receipt_id<>NEW.receipt_id
    OR picked.batch_status<>'RELEASED' OR picked.released_at IS NULL
    OR picked.product_sku_id<>picked.pick_sku
    OR picked.product_sku_id<>picked.batch_sku
    OR picked.product_sku_id<>picked.op_sku
    OR picked.warehouse_id<>picked.pick_warehouse
    OR picked.warehouse_id<>picked.source_warehouse_id
    OR picked.accepted_units<=0
    OR picked.operation_type<>'OUTBOUND'
    OR picked.requested_quantity<>picked.pick_quantity
    OR picked.applied_delta<>-picked.pick_quantity THEN
    RAISE EXCEPTION 'V106: purchase batch/pick/outbound local identity conflict';
  END IF;

  -- Lock the parent receipt too: allocations on different orders and picks
  -- from the same receipt cannot race past its accepted physical units.
  PERFORM 1 FROM erp_purchase_receipt r
    WHERE r.tenant_id=NEW.tenant_id AND r.id=NEW.receipt_id
    FOR UPDATE;
  SELECT coalesce(sum(assigned_quantity),0) INTO receipt_used
    FROM finance_sale_lot_claim_v106
    WHERE tenant_id=NEW.tenant_id AND receipt_id=NEW.receipt_id;
  SELECT coalesce(sum(assigned_quantity),0) INTO item_used
    FROM finance_sale_lot_claim_v106
    WHERE tenant_id=NEW.tenant_id AND order_item_id=NEW.order_item_id;
  SELECT coalesce(sum(assigned_quantity),0) INTO pick_used
    FROM finance_sale_lot_claim_v106
    WHERE tenant_id=NEW.tenant_id AND pick_line_id=NEW.pick_line_id;
  IF item_used+NEW.assigned_quantity>sale.quantity
     OR pick_used+NEW.assigned_quantity>picked.quantity
     OR receipt_used+NEW.assigned_quantity>picked.accepted_units THEN
    RAISE EXCEPTION 'V106: sale or pick quantity conservation exceeded';
  END IF;

  -- Capture the canonical source values IN DATABASE, not client-submitted
  -- snapshots. Historical sources are not modified or retroactively attested.
  NEW.snapshot_external_item_id := sale.external_item_id;
  NEW.snapshot_item_quantity := sale.quantity;
  NEW.snapshot_item_source_updated_at := sale.source_updated_at;
  NEW.snapshot_order_source_updated_at := sale.order_updated_at;
  NEW.snapshot_pick_line_quantity := picked.quantity;
  NEW.snapshot_operation_id := picked.operation_id;
  NEW.snapshot_lot_code := picked.lot_code;
  NEW.snapshot_batch_released_at := picked.released_at;
  NEW.snapshot_inspection_id := picked.inspection_id;
  NEW.snapshot_accepted_quantity := picked.accepted_units;
  NEW.snapshot_receipt_quantity := picked.receipt_quantity;
  NEW.snapshot_purchase_line_id := picked.purchase_order_line_id;
  NEW.snapshot_product_sku_id := picked.product_sku_id;
  NEW.snapshot_warehouse_id := picked.warehouse_id;
  NEW.snapshot_unit_cost := picked.unit_cost;
  NEW.snapshot_currency := picked.currency_code;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_sale_lot_capture_v106
  BEFORE INSERT ON finance_sale_lot_claim_v106
  FOR EACH ROW EXECUTE FUNCTION finance_sale_lot_capture_v106();

CREATE FUNCTION finance_sale_lot_immutable_v106() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'V106: sale-lot claims and retractions are append-only';
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER finance_sale_lot_claim_immutable_v106
  BEFORE UPDATE OR DELETE ON finance_sale_lot_claim_v106
  FOR EACH ROW EXECUTE FUNCTION finance_sale_lot_immutable_v106();
CREATE TRIGGER finance_sale_lot_retraction_immutable_v106
  BEFORE UPDATE OR DELETE ON finance_sale_lot_retraction_v106
  FOR EACH ROW EXECUTE FUNCTION finance_sale_lot_immutable_v106();

-- Independent read-time recomputation. This is LOCAL integrity ONLY.
-- Even 'LOCAL_LINK_INTACT_UNVERIFIED' is NOT authorization for actual COGS.
CREATE VIEW finance_sale_lot_integrity_v106 AS
WITH item_usage AS (
  SELECT tenant_id,order_item_id,sum(assigned_quantity) AS allocated
  FROM finance_sale_lot_claim_v106 GROUP BY tenant_id,order_item_id
), pick_usage AS (
  SELECT tenant_id,pick_line_id,sum(assigned_quantity) AS allocated
  FROM finance_sale_lot_claim_v106 GROUP BY tenant_id,pick_line_id
), receipt_usage AS (
  SELECT tenant_id,receipt_id,sum(assigned_quantity) AS allocated
  FROM finance_sale_lot_claim_v106 GROUP BY tenant_id,receipt_id
), pick_totals AS (
  SELECT tenant_id,pick_id,sum(quantity) AS total
  FROM erp_local_lot_pick_line GROUP BY tenant_id,pick_id
)
SELECT c.id AS claim_id,c.tenant_id,c.marketplace_id,c.sales_order_id,
  c.order_item_id,c.pick_line_id,c.receipt_id,c.external_dispatch_ref,
  c.external_payload_sha256,c.assigned_quantity,
  c.snapshot_external_item_id,c.snapshot_product_sku_id,
  coalesce(iu.allocated,0) AS order_item_allocated,
  coalesce(pu.allocated,0) AS pick_line_allocated,
  coalesce(ru.allocated,0) AS receipt_allocated,
  CASE
    WHEN ret.claim_id IS NOT NULL THEN 'RETRACTED'
    WHEN i.is_current IS DISTINCT FROM TRUE OR o.data_origin IS DISTINCT FROM 'LIVE'
      OR i.sales_order_id IS DISTINCT FROM c.sales_order_id
      OR o.seller_marketplace_id IS DISTINCT FROM c.marketplace_id
      OR i.external_item_id IS DISTINCT FROM c.snapshot_external_item_id
      OR i.quantity IS DISTINCT FROM c.snapshot_item_quantity
      OR i.source_updated_at IS DISTINCT FROM c.snapshot_item_source_updated_at
      OR o.source_updated_at IS DISTINCT FROM c.snapshot_order_source_updated_at
      THEN 'SALE_EVIDENCE_STALE'
    WHEN pl.quantity IS DISTINCT FROM c.snapshot_pick_line_quantity
      OR p.receipt_id IS DISTINCT FROM c.receipt_id
      OR p.product_sku_id IS DISTINCT FROM c.snapshot_product_sku_id
      OR p.warehouse_id IS DISTINCT FROM c.snapshot_warehouse_id
      OR pk.operation_id IS DISTINCT FROM c.snapshot_operation_id
      OR pk.product_sku_id IS DISTINCT FROM p.product_sku_id
      OR pk.warehouse_id IS DISTINCT FROM p.warehouse_id
      OR op.operation_type IS DISTINCT FROM 'OUTBOUND'
      OR op.source_warehouse_id IS DISTINCT FROM pk.warehouse_id
      OR op.product_sku_id IS DISTINCT FROM pk.product_sku_id
      OR op.requested_quantity IS DISTINCT FROM pk.quantity
      OR op.applied_delta IS DISTINCT FROM -pk.quantity
      OR pt.total IS DISTINCT FROM pk.quantity
      OR move.outbound_count IS DISTINCT FROM 1
      OR move.outbound_quantity IS DISTINCT FROM -pk.quantity
      THEN 'PICK_OUTBOUND_EVIDENCE_STALE'
    WHEN b.status IS DISTINCT FROM 'RELEASED'
      OR b.lot_code IS DISTINCT FROM c.snapshot_lot_code
      OR b.released_at IS DISTINCT FROM c.snapshot_batch_released_at
      OR b.inspection_id IS DISTINCT FROM c.snapshot_inspection_id
      OR q.accepted_quantity IS DISTINCT FROM c.snapshot_accepted_quantity
      OR b.product_sku_id IS DISTINCT FROM p.product_sku_id
      OR r.quantity IS DISTINCT FROM c.snapshot_receipt_quantity
      OR r.purchase_order_line_id IS DISTINCT FROM c.snapshot_purchase_line_id
      OR l.product_sku_id IS DISTINCT FROM c.snapshot_product_sku_id
      OR l.unit_cost IS DISTINCT FROM c.snapshot_unit_cost
      OR po.currency_code IS DISTINCT FROM c.snapshot_currency
      OR q.purchase_receipt_id IS DISTINCT FROM r.id
      OR q.accepted_quantity IS NULL OR q.accepted_quantity<=0
      OR q.accepted_quantity+q.rejected_quantity IS DISTINCT FROM r.quantity
      OR release.release_count IS DISTINCT FROM 1
      OR release.released_quantity IS DISTINCT FROM q.accepted_quantity
      THEN 'PURCHASE_BATCH_EVIDENCE_STALE'
    WHEN iu.allocated>i.quantity OR pu.allocated>pl.quantity
      OR ru.allocated>q.accepted_quantity
      OR c.assigned_quantity>pl.quantity
      THEN 'QUANTITY_CONSERVATION_BROKEN'
    ELSE 'LOCAL_LINK_INTACT_UNVERIFIED'
  END AS integrity_state,
  FALSE::boolean AS external_operator_verified,
  FALSE::boolean AS platform_fulfillment_verified,
  FALSE::boolean AS actual_batch_cogs_verified,
  NULL::numeric AS realized_batch_cogs
FROM finance_sale_lot_claim_v106 c
JOIN order_item i ON i.tenant_id=c.tenant_id AND i.id=c.order_item_id
JOIN sales_order o ON o.tenant_id=i.tenant_id AND o.id=i.sales_order_id
JOIN item_usage iu ON iu.tenant_id=c.tenant_id AND iu.order_item_id=c.order_item_id
JOIN pick_usage pu ON pu.tenant_id=c.tenant_id AND pu.pick_line_id=c.pick_line_id
JOIN receipt_usage ru ON ru.tenant_id=c.tenant_id AND ru.receipt_id=c.receipt_id
LEFT JOIN erp_local_lot_pick_line pl
  ON pl.tenant_id=c.tenant_id AND pl.id=c.pick_line_id
LEFT JOIN erp_local_lot_pick pk
  ON pk.tenant_id=pl.tenant_id AND pk.id=pl.pick_id
LEFT JOIN pick_totals pt ON pt.tenant_id=pk.tenant_id AND pt.pick_id=pk.id
LEFT JOIN erp_local_lot_position p
  ON p.tenant_id=pl.tenant_id AND p.id=pl.position_id
LEFT JOIN erp_local_stock_operation op
  ON op.tenant_id=pk.tenant_id AND op.id=pk.operation_id
LEFT JOIN erp_receipt_quality_batch b
  ON b.tenant_id=p.tenant_id AND b.receipt_id=p.receipt_id
LEFT JOIN erp_purchase_receipt r
  ON r.tenant_id=b.tenant_id AND r.id=b.receipt_id
LEFT JOIN erp_purchase_order_line l
  ON l.tenant_id=r.tenant_id AND l.id=r.purchase_order_line_id
    AND l.purchase_order_id=r.purchase_order_id
LEFT JOIN erp_purchase_order po
  ON po.tenant_id=l.tenant_id AND po.id=l.purchase_order_id
LEFT JOIN erp_purchase_receipt_qc q
  ON q.tenant_id=b.tenant_id AND q.id=b.inspection_id
LEFT JOIN LATERAL (
  SELECT count(*)::bigint AS outbound_count,sum(m.quantity)::bigint AS outbound_quantity
  FROM erp_local_stock_movement m WHERE m.tenant_id=op.tenant_id
    AND m.operation_id=op.id AND m.movement_type='OUTBOUND'
) move ON TRUE
LEFT JOIN LATERAL (
  SELECT count(*)::bigint AS release_count,sum(m.quantity)::bigint AS released_quantity
  FROM erp_local_stock_movement m WHERE m.tenant_id=q.tenant_id
    AND m.inspection_id=q.id AND m.movement_type='QC_RELEASE'
) release ON TRUE
LEFT JOIN finance_sale_lot_retraction_v106 ret
  ON ret.tenant_id=c.tenant_id AND ret.claim_id=c.id;

COMMENT ON VIEW finance_sale_lot_integrity_v106 IS
  'Recomputed local linkage consistency only; NEVER external fulfillment or actual COGS approval';
