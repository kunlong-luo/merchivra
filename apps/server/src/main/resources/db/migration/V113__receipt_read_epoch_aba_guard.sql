-- V221 / Flyway V113: transactionally committed, monotonically increasing
-- tenant-scoped change counter for V216 receipt/source audit reads.
-- A->B->A updates are NOT detectable by comparing two data snapshots alone.
-- NO backfill: absence of a row is revision ZERO (never an attestation).
-- Neither a revision nor a matching V112 allocation is external cost evidence.
-- Read-only V216 GET never writes a revision. Only successful source mutations do.

CREATE TABLE finance_receipt_read_epoch_v113 (
    tenant_id UUID PRIMARY KEY REFERENCES tenant(id),
    revision BIGINT NOT NULL CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The epoch advances under the same DB transaction as the source mutation.
-- ON CONFLICT row locking gives a COMMIT-ordered marker per tenant, avoiding
-- misleading PostgreSQL sequence values allocated by uncommitted transactions.
CREATE FUNCTION finance_receipt_read_epoch_touch_v113(scope_tenant UUID)
RETURNS VOID LANGUAGE plpgsql AS $v113$
BEGIN
    IF scope_tenant IS NULL THEN
        RAISE EXCEPTION 'V113 requires an explicit tenant identity';
    END IF;
    INSERT INTO finance_receipt_read_epoch_v113 AS current_epoch
        (tenant_id, revision, updated_at)
    VALUES (scope_tenant, 1, clock_timestamp())
    ON CONFLICT (tenant_id) DO UPDATE
      SET revision = current_epoch.revision + 1,
          updated_at = clock_timestamp();
END
$v113$;

-- One tick per affected tenant per SQL statement. This avoids one lock/update
-- per source row in bulk imports but intentionally serializes tenant writes.
-- Both OLD and NEW tenant identities are counted on UPDATE (no silent move).
-- In-flight or rolled-back changes never count as committed revisions.
CREATE FUNCTION finance_receipt_read_epoch_statement_v113()
RETURNS TRIGGER LANGUAGE plpgsql AS $v113$
DECLARE scope_tenant UUID;
BEGIN
    IF TG_OP = 'INSERT' THEN
        FOR scope_tenant IN SELECT DISTINCT tenant_id FROM new_rows LOOP
            PERFORM finance_receipt_read_epoch_touch_v113(scope_tenant);
        END LOOP;
    ELSIF TG_OP = 'DELETE' THEN
        FOR scope_tenant IN SELECT DISTINCT tenant_id FROM old_rows LOOP
            PERFORM finance_receipt_read_epoch_touch_v113(scope_tenant);
        END LOOP;
    ELSIF TG_OP = 'UPDATE' THEN
        FOR scope_tenant IN
          SELECT tenant_id FROM old_rows
          UNION SELECT tenant_id FROM new_rows
        LOOP
            PERFORM finance_receipt_read_epoch_touch_v113(scope_tenant);
        END LOOP;
    ELSE
        RAISE EXCEPTION 'Unsupported V113 source mutation';
    END IF;
    RETURN NULL;
END
$v113$;

-- Exact V216 business rows, their review audit, tenant/Marketplace/receipt
-- scope and ALL V165 local collision input tables. Do not silently narrow
-- this list when the V165 signal query changes.
DO $v113$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
       'finance_cost_source_v91',
       'finance_cost_source_audit_v91',
       'finance_cost_allocation_batch_v92',
       'finance_receipt_cost_plan_v112',
       'finance_receipt_cost_line_v112',
       'finance_receipt_cost_audit_v112',
       'erp_purchase_order',
       'erp_purchase_receipt',
       'seller_marketplace',
       'finance_ad_spend_reference',
       'ads_report_import',
       'finance_source_event',
       'spapi_settlement_report',
       'finance_settlement_statement',
       'finance_cost_settlement_line_link_v94'
    ] LOOP
        EXECUTE format(
          'CREATE TRIGGER %I AFTER INSERT ON %I '
          || 'REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT '
          || 'EXECUTE FUNCTION finance_receipt_read_epoch_statement_v113()',
          table_name || '_epoch_insert_v113', table_name);
        EXECUTE format(
          'CREATE TRIGGER %I AFTER UPDATE ON %I '
          || 'REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows '
          || 'FOR EACH STATEMENT '
          || 'EXECUTE FUNCTION finance_receipt_read_epoch_statement_v113()',
          table_name || '_epoch_update_v113', table_name);
        EXECUTE format(
          'CREATE TRIGGER %I AFTER DELETE ON %I '
          || 'REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT '
          || 'EXECUTE FUNCTION finance_receipt_read_epoch_statement_v113()',
          table_name || '_epoch_delete_v113', table_name);
    END LOOP;
END
$v113$;

-- A tenant may be suspended between the two V216 snapshot transactions.
-- Existing authentication still determines OWNER; this version check is an
-- independent read-time observation, NOT a replacement for currentTenant().
CREATE FUNCTION finance_receipt_read_epoch_tenant_v113()
RETURNS TRIGGER LANGUAGE plpgsql AS $v113$
BEGIN
    PERFORM finance_receipt_read_epoch_touch_v113(NEW.id);
    RETURN NEW;
END
$v113$;
CREATE TRIGGER tenant_receipt_read_epoch_update_v113
  AFTER UPDATE OF status ON tenant FOR EACH ROW
  WHEN (OLD.status IS DISTINCT FROM NEW.status)
  EXECUTE FUNCTION finance_receipt_read_epoch_tenant_v113();

COMMENT ON TABLE finance_receipt_read_epoch_v113 IS
  'Tenant-scoped monotonic committed revision for V216 double RR read ABA detection; not external cost verification.';
