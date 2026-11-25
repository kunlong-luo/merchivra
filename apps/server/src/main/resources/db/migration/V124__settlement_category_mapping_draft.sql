-- V274: report-scoped immutable mapping drafts; never a V56 approval or posting.
-- Marketplace scope is derived from the report's immutable tenant/report key.
CREATE TABLE finance_settlement_category_mapping_v124 (
 tenant_id uuid NOT NULL,
 report_id uuid NOT NULL,
 mapping_sha256 char(64) NOT NULL CHECK(mapping_sha256 ~ '^[0-9a-f]{64}$'),
 canonical_payload text NOT NULL CHECK(length(canonical_payload) BETWEEN 1 AND 85000),
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,report_id,mapping_sha256),
 FOREIGN KEY(tenant_id,report_id) REFERENCES spapi_settlement_report(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES membership(tenant_id,user_id),
 CHECK(mapping_sha256=encode(sha256(convert_to(canonical_payload,'UTF8')),'hex'))
);
CREATE TRIGGER settlement_category_mapping_immutable_v124
 BEFORE UPDATE OR DELETE ON finance_settlement_category_mapping_v124
 FOR EACH ROW EXECUTE FUNCTION finance_sale_lot_immutable_v106();
COMMENT ON TABLE finance_settlement_category_mapping_v124 IS
 'Immutable explicit Reports mapping drafts; current V28 rules and source evidence must be re-read; never approved by saving';
