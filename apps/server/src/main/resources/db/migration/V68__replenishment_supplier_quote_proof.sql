-- V68 / product V71. Preserve V37 immutable evidence and V38 observation dedupe.
-- Old local human-price drafts remain legal; they get ordered=suggested and
-- no V42 supplier-rule proof. New quote-backed drafts snapshot frozen terms.
ALTER TABLE erp_replenishment_purchase_draft_source
 ADD COLUMN ordered_quantity INTEGER,
 ADD COLUMN supplier_rule_updated_at TIMESTAMPTZ,
 ADD COLUMN minimum_order_quantity INTEGER,
 ADD COLUMN pack_multiple INTEGER,
 ADD COLUMN supplier_lead_time_days INTEGER,
 ADD COLUMN supplier_pricing_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL'
   CHECK (supplier_pricing_source IN ('MANUAL','V42_RULE'));
UPDATE erp_replenishment_purchase_draft_source
 SET ordered_quantity=suggested_quantity WHERE ordered_quantity IS NULL;
ALTER TABLE erp_replenishment_purchase_draft_source
 ALTER COLUMN ordered_quantity SET NOT NULL,
 ADD CONSTRAINT repl_draft_order_quantity_v68
  CHECK (ordered_quantity BETWEEN suggested_quantity AND 1000000),
 ADD CONSTRAINT repl_draft_quote_proof_v68 CHECK (
  (supplier_pricing_source='MANUAL'
   AND ordered_quantity=suggested_quantity
   AND supplier_rule_updated_at IS NULL AND minimum_order_quantity IS NULL
   AND pack_multiple IS NULL AND supplier_lead_time_days IS NULL)
  OR
  (supplier_pricing_source='V42_RULE'
   AND supplier_rule_updated_at IS NOT NULL
   AND minimum_order_quantity BETWEEN 1 AND 1000000
   AND pack_multiple BETWEEN 1 AND 1000000
   AND supplier_lead_time_days BETWEEN 0 AND 365
   AND ordered_quantity >= minimum_order_quantity
   AND ordered_quantity % pack_multiple = 0)
 );
