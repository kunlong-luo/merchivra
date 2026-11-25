-- V276: local independent category review history, never a posting or V56 approval.
CREATE TABLE finance_settlement_category_request_v125 (
 tenant_id uuid NOT NULL,
 report_id uuid NOT NULL,
 mapping_sha256 char(64) NOT NULL,
 evidence_sha256 char(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
 requested_by uuid NOT NULL,
 request_reason varchar(500) NOT NULL CHECK(length(btrim(request_reason)) BETWEEN 10 AND 500),
 requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,report_id,mapping_sha256,evidence_sha256),
 UNIQUE(tenant_id,report_id,mapping_sha256,evidence_sha256,requested_by),
 FOREIGN KEY(tenant_id,report_id,mapping_sha256)
   REFERENCES finance_settlement_category_mapping_v124(tenant_id,report_id,mapping_sha256),
 FOREIGN KEY(tenant_id,requested_by) REFERENCES membership(tenant_id,user_id)
);
CREATE TABLE finance_settlement_category_approval_v125 (
 tenant_id uuid NOT NULL,
 report_id uuid NOT NULL,
 mapping_sha256 char(64) NOT NULL,
 evidence_sha256 char(64) NOT NULL,
 requested_by uuid NOT NULL,
 approved_by uuid NOT NULL,
 approval_reason varchar(500) NOT NULL CHECK(length(btrim(approval_reason)) BETWEEN 10 AND 500),
 approved_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,report_id,mapping_sha256,evidence_sha256),
 FOREIGN KEY(tenant_id,report_id,mapping_sha256,evidence_sha256,requested_by)
   REFERENCES finance_settlement_category_request_v125(tenant_id,report_id,mapping_sha256,evidence_sha256,requested_by),
 FOREIGN KEY(tenant_id,approved_by) REFERENCES membership(tenant_id,user_id),
 CHECK(requested_by<>approved_by)
);
CREATE TRIGGER settlement_category_request_immutable_v125
 BEFORE UPDATE OR DELETE ON finance_settlement_category_request_v125
 FOR EACH ROW EXECUTE FUNCTION finance_sale_lot_immutable_v106();
CREATE TRIGGER settlement_category_approval_immutable_v125
 BEFORE UPDATE OR DELETE ON finance_settlement_category_approval_v125
 FOR EACH ROW EXECUTE FUNCTION finance_sale_lot_immutable_v106();
COMMENT ON TABLE finance_settlement_category_approval_v125 IS
 'Local independent review only; does not activate mapping, verify bank, or permit posting';
