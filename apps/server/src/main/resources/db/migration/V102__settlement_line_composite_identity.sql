-- V102 / product V181: immutable composite provenance of an actual imported
-- Amazon settlement REPORT and its concrete LINE, linked to a manual cost.
-- V94/V95 already capture matching amounts and potential shared-line claims.
-- This stores the original imported document identity/line attributes at
-- attachment time; it does NOT attest Amazon authenticity, duplicate payment,
-- bank settlement, cost eligibility or verified net profit.
--
-- Intentionally NO backfill: historical V94 links did not capture this evidence
-- at creation. The API must display CAPTURE_NOT_AVAILABLE for those links.
CREATE TABLE finance_cost_settlement_line_identity_v102 (
    tenant_id UUID NOT NULL,
    seller_marketplace_id UUID NOT NULL,
    link_id UUID NOT NULL,
    source_id UUID NOT NULL,
    report_id UUID NOT NULL,
    line_number INTEGER NOT NULL CHECK(line_number BETWEEN 1 AND 10000),
    amazon_report_id VARCHAR(160) NOT NULL,
    report_document_id VARCHAR(256) NOT NULL,
    report_sha256 CHAR(64) NOT NULL CHECK(report_sha256 ~ '^[0-9a-f]{64}$'),
    transaction_type VARCHAR(100),
    amount_type VARCHAR(100),
    amount_description VARCHAR(160),
    external_order_id VARCHAR(160),
    signed_amount NUMERIC(20,6) NOT NULL,
    currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
    recorded_by UUID NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,link_id),
    FOREIGN KEY (tenant_id,link_id)
        REFERENCES finance_cost_settlement_line_link_v94(tenant_id,id),
    FOREIGN KEY (tenant_id,seller_marketplace_id,source_id)
        REFERENCES finance_cost_source_v91(tenant_id,seller_marketplace_id,id),
    FOREIGN KEY (tenant_id,seller_marketplace_id,report_id)
        REFERENCES spapi_settlement_report(tenant_id,seller_marketplace_id,id),
    FOREIGN KEY (tenant_id,report_id,line_number)
        REFERENCES spapi_settlement_report_line(tenant_id,report_id,line_number),
    FOREIGN KEY (tenant_id,recorded_by)
        REFERENCES membership(tenant_id,user_id)
);
CREATE INDEX finance_cost_settlement_identity_report_v102
  ON finance_cost_settlement_line_identity_v102
      (tenant_id,seller_marketplace_id,report_id,line_number);

-- Database independently confirms every captured field against imported
-- source and V94 tenant/Marketplace/link, under row locks. Direct SQL cannot
-- manufacture a differently scoped or forged imported-line snapshot.
CREATE FUNCTION finance_cost_settlement_identity_guard_v102()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  link_record finance_cost_settlement_line_link_v94%ROWTYPE;
  report_record spapi_settlement_report%ROWTYPE;
  line_record spapi_settlement_report_line%ROWTYPE;
BEGIN
  IF TG_OP<>'INSERT' THEN
    RAISE EXCEPTION 'Frozen settlement line identity cannot be changed or deleted';
  END IF;
  SELECT * INTO link_record FROM finance_cost_settlement_line_link_v94
    WHERE tenant_id=NEW.tenant_id AND id=NEW.link_id
    FOR SHARE;
  IF NOT FOUND OR ROW(link_record.seller_marketplace_id,
       link_record.source_id,link_record.report_id,
       link_record.line_number,link_record.recorded_by)
       IS DISTINCT FROM ROW(NEW.seller_marketplace_id,NEW.source_id,
       NEW.report_id,NEW.line_number,NEW.recorded_by) THEN
    RAISE EXCEPTION 'V102 evidence must match tenant-scoped V94 link';
  END IF;
  SELECT * INTO report_record FROM spapi_settlement_report
    WHERE tenant_id=NEW.tenant_id
      AND seller_marketplace_id=NEW.seller_marketplace_id
      AND id=NEW.report_id
      AND state='IMPORTED' AND source_origin='LIVE' FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Imported LIVE report unavailable for evidence capture';
  END IF;
  SELECT * INTO line_record FROM spapi_settlement_report_line
    WHERE tenant_id=NEW.tenant_id AND report_id=NEW.report_id
      AND line_number=NEW.line_number FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Concrete report line unavailable for evidence capture';
  END IF;
  IF ROW(NEW.amazon_report_id,NEW.report_document_id,NEW.report_sha256)
       IS DISTINCT FROM ROW(report_record.amazon_report_id,
       report_record.report_document_id,report_record.document_sha256)
     OR ROW(NEW.transaction_type,NEW.amount_type,NEW.amount_description,
       NEW.external_order_id,NEW.signed_amount,NEW.currency_code)
       IS DISTINCT FROM ROW(line_record.transaction_type,line_record.amount_type,
       line_record.amount_description,line_record.external_order_id,
       line_record.signed_amount,line_record.currency_code)
     OR ROW(NEW.signed_amount,NEW.currency_code)
       IS DISTINCT FROM ROW(link_record.line_signed_amount,
       link_record.line_currency)
  THEN RAISE EXCEPTION 'Imported report/line composite identity mismatch'; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER finance_cost_settlement_identity_guard_trigger_v102
  BEFORE INSERT OR UPDATE OR DELETE
  ON finance_cost_settlement_line_identity_v102
  FOR EACH ROW EXECUTE FUNCTION finance_cost_settlement_identity_guard_v102();

-- V95 already protects any linked LINE against mutation. Protect report
-- metadata as well AFTER the first V102 identity capture. No trigger rewrites
-- historical V94 links: old evidence remains explicitly incomplete.
CREATE FUNCTION finance_settlement_report_identity_immutable_v102()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM finance_cost_settlement_line_identity_v102 i
    WHERE i.tenant_id=OLD.tenant_id AND i.report_id=OLD.id
  ) THEN
    RAISE EXCEPTION 'Report identity with frozen cost links is immutable';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER finance_settlement_report_identity_guard_trigger_v102
  BEFORE UPDATE OR DELETE ON spapi_settlement_report
  FOR EACH ROW EXECUTE FUNCTION finance_settlement_report_identity_immutable_v102();
