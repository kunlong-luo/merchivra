-- V224 / Flyway V114: forward-only, source-row-identical UPDATE fast path.
-- V113's committed tenant epoch and all 46 existing triggers stay installed.
-- Only small shared purchase/seller scope tables are eligible; all other
-- V91/V92/V112/V165 input UPDATEs remain conservative (tick per statement).
-- Transition row MULTISET equivalence (EXCEPT ALL both directions) is required:
-- comparing counts, keys, or unordered aggregate hashes alone is NOT sufficient.
-- A change reverted in a later committed transaction must still tick twice.
-- Visible row contents are compared, not ctid/xmin; physical no-op UPDATE does
-- not create new V216-relevant facts. Exact changes still serialize by tenant.

CREATE OR REPLACE FUNCTION finance_receipt_read_epoch_statement_v113()
RETURNS TRIGGER LANGUAGE plpgsql AS $v114$
DECLARE scope_tenant UUID;
BEGIN
    IF TG_OP = 'UPDATE'
       AND TG_TABLE_NAME IN ('erp_purchase_order', 'seller_marketplace')
       AND NOT EXISTS (
           SELECT 1 FROM (
               SELECT to_jsonb(previous_row) AS payload
               FROM old_rows AS previous_row
               EXCEPT ALL
               SELECT to_jsonb(current_row)
               FROM new_rows AS current_row
           ) AS removed_rows
       )
       AND NOT EXISTS (
           SELECT 1 FROM (
               SELECT to_jsonb(current_row) AS payload
               FROM new_rows AS current_row
               EXCEPT ALL
               SELECT to_jsonb(previous_row)
               FROM old_rows AS previous_row
           ) AS added_rows
       )
    THEN
        -- No observable row value change. Do not serialize this tenant or
        -- induce an avoidable cross-Marketplace V216 HTTP409.
        RETURN NULL;
    END IF;

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
        RAISE EXCEPTION 'Unsupported V113/V114 source mutation';
    END IF;
    RETURN NULL;
END
$v114$;

COMMENT ON FUNCTION finance_receipt_read_epoch_statement_v113() IS
    'V114: skip identical visible row UPDATE on purchase orders and seller_marketplace; preserve committed tenant version on every real V216/V165 evidence change.';
