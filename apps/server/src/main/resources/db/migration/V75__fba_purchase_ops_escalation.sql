-- Product V78 / Flyway V75. Reuse the V10 operations inbox and V69 audit/versioned rule settings.
-- These are local advisory findings; they never post to Amazon, inventory or suppliers.
ALTER TABLE ops_finding DROP CONSTRAINT ops_finding_rule_code_check;
ALTER TABLE ops_finding ADD CONSTRAINT ops_finding_rule_code_check
 CHECK (rule_code IN ('UNMAPPED_LISTING','INVENTORY_UNKNOWN','LOW_STOCK',
  'REFUND_REVIEW','FBA_INBOUND_REVIEW','ADS_REPORT_REVIEW',
  'FBA_PO_EVIDENCE_CHANGED','FBA_PO_EXCEPTION_OVERDUE'));
ALTER TABLE ops_rule_setting DROP CONSTRAINT ops_rule_setting_rule_code_check;
ALTER TABLE ops_rule_setting ADD CONSTRAINT ops_rule_setting_rule_code_check
 CHECK (rule_code IN ('UNMAPPED_LISTING','INVENTORY_UNKNOWN','LOW_STOCK',
  'REFUND_REVIEW','FBA_INBOUND_REVIEW','ADS_REPORT_REVIEW',
  'FBA_PO_EVIDENCE_CHANGED','FBA_PO_EXCEPTION_OVERDUE'));
-- Partial tenant/marketplace index keeps periodic advisory rechecks bounded to
-- actual human-workflow cases, not the whole inbound shipment history.
CREATE INDEX fba_purchase_exception_case_scan_v75
 ON fba_purchase_exception_case(tenant_id,seller_marketplace_id,updated_at DESC)
 WHERE state <> 'RESOLVED';
