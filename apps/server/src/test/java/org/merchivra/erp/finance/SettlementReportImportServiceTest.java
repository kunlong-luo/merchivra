package org.merchivra.erp.finance;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.spapi.SpApiRegion;
import org.merchivra.erp.spapi.SpApiSellerScope;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettlementReportImportServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SettlementReportImportService service=new SettlementReportImportService(jdbc);

    private SpApiSellerScope scope(Set<String> roles) {
        return new SpApiSellerScope(UUID.randomUUID(),UUID.randomUUID(),
            "A1REALSELLER","ATVPDKIKX0DER",SpApiRegion.NA,"SECRET-GRANT",roles);
    }

    @Test
    void deniesUnreviewedFinanceScopeBeforeParsingOrAccessingDatabase() {
        var denied=scope(Set.of("Inventory and Order Tracking"));
        assertThrows(SecurityException.class,()->
            service.importDocument(denied,"RPT-12345678","DOC-12345678",
                "dummy".getBytes(StandardCharsets.UTF_8),false));
        verifyNoInteractions(jdbc);
    }

    @Test
    void validFinanceRoleStillRequiresLiveDatabaseEvidence() {
        var finance=scope(Set.of("Finance and Accounting"));
        byte[] report=("""
            settlement-id\ttransaction-type\tamount\tcurrency
            SETTLE-00000001\tOrder\t1.00\tUSD
            """).getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,()->
            service.importDocument(finance,"RPT-12345678","DOC-12345678",report,false));
        verify(jdbc).query(contains("spapi_finances_role_evidence"),any(
            org.springframework.jdbc.core.RowMapper.class),any(),any(),any(),any(),any());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void reportReadsAlwaysRequireTenantAndMarketplaceBoundaries() {
        // Reflects SQL contract without executing PostgreSQL here; real Flyway
        // migration and live start are verified by separate PG18 CI job.
        service.list(UUID.randomUUID(),UUID.randomUUID());
        verify(jdbc).query(contains("WHERE tenant_id=? AND seller_marketplace_id=?"),
            any(org.springframework.jdbc.core.RowMapper.class),any(),any());
    }
}
