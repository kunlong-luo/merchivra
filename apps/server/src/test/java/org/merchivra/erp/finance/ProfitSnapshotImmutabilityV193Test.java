package org.merchivra.erp.finance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** V193 safety contract; the actual PostgreSQL trigger scenarios run in A3. */
class ProfitSnapshotImmutabilityV193Test {
    private static final Path MIGRATION=Path.of(
        "src/main/resources/db/migration/V103__profit_snapshot_immutability.sql");

    @Test void allFourV64FinancialEvidenceTablesHaveDatabaseMutationGuards()
        throws Exception {
        String sql=Files.readString(MIGRATION);
        for(String type:List.of("job","order","sku","audit")) {
            assertTrue(sql.contains("CREATE FUNCTION finance_profit_report_"
                +type+"_guard_v103()"),type);
            assertTrue(sql.contains("CREATE TRIGGER finance_profit_report_"
                +type+"_guard_trigger_v103"),type);
        }
        assertTrue(sql.contains("BEFORE INSERT OR UPDATE OR DELETE ON finance_profit_report_order"));
        assertTrue(sql.contains("BEFORE INSERT OR UPDATE OR DELETE ON finance_profit_report_sku"));
        assertTrue(sql.contains("BEFORE UPDATE OR DELETE ON finance_profit_report_audit"));
        assertTrue(sql.contains("OLD.state<>'PENDING'"));
        assertTrue(sql.contains("cohort_position<already_processed"));
        assertTrue(sql.contains("FOR SHARE"));
        assertTrue(sql.contains("FOR UPDATE"));
        assertTrue(sql.contains("append-only"));
        assertFalse(sql.contains("CREATE EXTENSION"));
        assertFalse(sql.contains("UPDATE finance_profit_report_order SET "));
        assertFalse(sql.contains("UPDATE finance_profit_report_sku SET "));
        assertFalse(sql.contains("INSERT INTO finance_profit_report_audit"));
    }

    @Test void originalV64CommitSequenceDoesNotBypassTheNewGuards()
        throws Exception {
        var source=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/ProfitReportJobService.java"));
        assertTrue(source.contains("header(tenant,mp,jobId,true)"));
        assertTrue(source.contains("SET state=?,readiness=?,currency_code=?,reviewed_events=?"));
        assertTrue(source.contains("WHERE tenant_id=? AND job_id=? AND order_id=? AND state='PENDING'"));
        int frozen=source.indexOf("UPDATE finance_profit_report_order");
        int skus=source.indexOf("INSERT INTO finance_profit_report_sku");
        int progress=source.indexOf("UPDATE finance_profit_report_job",skus);
        assertTrue(frozen>=0&&skus>frozen&&progress>skus,
            "First capture, then SKU inserts, then durable batch progress");
        assertTrue(source.contains("if(updated!=1)throw new IllegalStateException"));
        assertTrue(source.contains("batchTx.executeWithoutResult"));
    }

    @Test void isolatedPg18TestDoesNotWriteOrDeleteHistoricalFinancialRows()
        throws Exception {
        String script=Files.readString(Path.of(
            "../../scripts/smoke_profit_snapshot_guards_v193.sql"));
        assertTrue(script.contains("BEGIN;"));
        assertTrue(script.contains("ROLLBACK;"));
        assertTrue(script.contains("CREATE TEMP TABLE finance_profit_report_order"));
        assertTrue(script.contains("CREATE TEMP TABLE finance_profit_report_sku"));
        assertTrue(script.contains("pg_temp.expect_blocked"));
        assertTrue(script.contains("V103 Flyway migration missing"));
        assertFalse(script.contains("TRUNCATE public."));
        assertFalse(script.contains("DROP TABLE public."));
    }
}
