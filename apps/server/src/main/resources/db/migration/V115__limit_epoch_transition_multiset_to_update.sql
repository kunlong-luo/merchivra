-- V224B / Flyway V115: forward-only fix for V114 UPDATE-only relation guard.
-- PostgreSQL does not guarantee expression short-circuit in the compound IF:
-- "TG_OP='UPDATE' AND ... old_rows" may resolve old_rows on INSERT.
-- V114's first A3 revealed seller_marketplace INSERT HTTP500 / old_rows absent.
-- Do NOT modify V113/V114 checksums or disable the 46 triggers.
-- An outer PL/pgSQL IF ensures UPDATE transition relations are used ONLY in
-- UPDATE trigger invocations; INSERT/DELETE keep V113 committed tenant epoch.
-- No expense accounting, realized COGS or real Seller/Ads authorization.

CREATE OR REPLACE FUNCTION finance_receipt_read_epoch_statement_v113()
RETURNS TRIGGER LANGUAGE plpgsql AS $v115$
DECLARE scope_tenant UUID;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF TG_TABLE_NAME IN ('erp_purchase_order', 'seller_marketplace')
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
            -- UPDATE matched no rows or no observable source value changed.
            -- Any committed A->B followed by B->A still advances twice.
            RETURN NULL;
        END IF;
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
        RAISE EXCEPTION 'Unsupported V113/V115 source mutation';
    END IF;
    RETURN NULL;
END
$v115$;

COMMENT ON FUNCTION finance_receipt_read_epoch_statement_v113() IS
  'V115 fixes V114 PG18 INSERT transition relation mis-planning; only UPDATE may inspect both row sets; real changes keep V113 committed epoch.';
