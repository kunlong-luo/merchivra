-- V123 / V266: immutable local compensation request and approval, not activation.
-- No foreign key or trigger changes V119 reservations, inventory or accounting.
CREATE TABLE finance_refund_compensation_proposal_v123 (
 tenant_id uuid NOT NULL,
 marketplace_id uuid NOT NULL,
 sales_order_id uuid NOT NULL,
 event_id uuid NOT NULL,
 order_item_id uuid NOT NULL,
 proposal_sha256 char(64) NOT NULL CHECK(proposal_sha256 ~ '^[0-9a-f]{64}$'),
 canonical_payload text NOT NULL CHECK(length(canonical_payload) BETWEEN 1 AND 32768),
 review_revision integer NOT NULL CHECK(review_revision>0),
 review_sha256 char(64) NOT NULL CHECK(review_sha256 ~ '^[0-9a-f]{64}$'),
 requested_by uuid NOT NULL,
 request_reason varchar(500) NOT NULL CHECK(length(btrim(request_reason)) BETWEEN 10 AND 500),
 requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,proposal_sha256),
 UNIQUE(tenant_id,proposal_sha256,requested_by),
 FOREIGN KEY(tenant_id,marketplace_id,event_id)
   REFERENCES finance_source_event(tenant_id,seller_marketplace_id,id),
 FOREIGN KEY(tenant_id,marketplace_id,sales_order_id)
   REFERENCES sales_order(tenant_id,seller_marketplace_id,id),
 FOREIGN KEY(tenant_id,order_item_id) REFERENCES order_item(tenant_id,id),
 FOREIGN KEY(tenant_id,requested_by) REFERENCES membership(tenant_id,user_id),
 CHECK(proposal_sha256=encode(sha256(convert_to(canonical_payload,'UTF8')),'hex'))
);
CREATE TABLE finance_refund_compensation_approval_v123 (
 tenant_id uuid NOT NULL,
 proposal_sha256 char(64) NOT NULL,
 requested_by uuid NOT NULL,
 approved_by uuid NOT NULL,
 approval_reason varchar(500) NOT NULL CHECK(length(btrim(approval_reason)) BETWEEN 10 AND 500),
 approved_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,proposal_sha256),
 FOREIGN KEY(tenant_id,proposal_sha256,requested_by)
   REFERENCES finance_refund_compensation_proposal_v123(tenant_id,proposal_sha256,requested_by),
 FOREIGN KEY(tenant_id,approved_by) REFERENCES membership(tenant_id,user_id),
 CHECK(requested_by<>approved_by)
);
CREATE TRIGGER refund_compensation_proposal_immutable_v123
 BEFORE UPDATE OR DELETE ON finance_refund_compensation_proposal_v123
 FOR EACH ROW EXECUTE FUNCTION finance_sale_lot_immutable_v106();
CREATE TRIGGER refund_compensation_approval_immutable_v123
 BEFORE UPDATE OR DELETE ON finance_refund_compensation_approval_v123
 FOR EACH ROW EXECUTE FUNCTION finance_sale_lot_immutable_v106();
COMMENT ON TABLE finance_refund_compensation_approval_v123 IS
 'Local approval history only; does not activate, release or replace any original reservation';
