package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CostSettlementLineCollisionV168Test {
    @Test void sharedConcreteLineHoldsEvenWithoutTheEightReferenceSignals() {
        UUID source=UUID.randomUUID();
        var none=Collections.nCopies(8,false);
        var noLink=CostIdentityReconciliationService.summarize(
            source,"REVIEWED_UNVERIFIED",none,0,0);
        assertFalse(noLink.approvalHeld());
        assertEquals(0,noLink.linkedSettlementLineCount());
        assertFalse(noLink.lineChargeOwnershipVerified());

        var oneLink=CostIdentityReconciliationService.summarize(
            source,"REVIEWED_UNVERIFIED",none,1,0);
        assertFalse(oneLink.approvalHeld()); // one claim != verified fee either
        assertFalse(oneLink.lineChargeOwnershipVerified());

        var shared=CostIdentityReconciliationService.summarize(
            source,"REVIEWED_UNVERIFIED",none,3,1);
        assertEquals("POTENTIAL_SETTLEMENT_LINE_COLLISION_HOLD",shared.status());
        assertEquals(1,shared.sharedSettlementLineCount());
        assertEquals(3,shared.linkedSettlementLineCount());
        assertEquals(8,shared.signals().size()); // V165's 8 signals remain stable
        assertTrue(shared.approvalHeld());
        assertFalse(shared.netProfitVerified());
        assertFalse(shared.completeSourceCoverage());
        assertFalse(shared.independentExternalEvidenceVerified());
        assertFalse(shared.lineChargeOwnershipVerified());

        assertThrows(IllegalArgumentException.class,()->
            CostIdentityReconciliationService.summarize(source,"STAGED",none,0,1));
        assertThrows(IllegalArgumentException.class,()->
            CostIdentityReconciliationService.summarize(source,"STAGED",none,-1,0));
    }
    @Test void readOnlyReverseLineageGetsTenantFromSessionAndNeverCaches() {
        var access=mock(TenantAccessService.class);
        var service=mock(CostIdentityReconciliationService.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),source=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        var controller=new CostIdentityReconciliationController(access,service);
        assertTrue(controller.sharedSettlementLines(auth,req,mp,source)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).sharedLinePreview(tenant,mp,source);
    }
    @Test void reverseLinksHaveScopedQueryAndRealPg18ImmutabilityGuard() throws Exception {
        var java=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostIdentityReconciliationService.java"));
        for(var key:new String[]{"x.tenant_id=s.tenant_id",
            "x.seller_marketplace_id=s.seller_marketplace_id",
            "x.source_id=s.id","y.report_id=x.report_id",
            "y.line_number=x.line_number","y.source_id<>x.source_id",
            "LIMIT 101","sourceId.toString()"})
            assertTrue(java.contains(key),key);
        assertFalse(java.contains("x.source_amount=y.source_amount"));
        assertFalse(java.contains("confirmedDuplicate=true"));
        var ddl=Files.readString(Path.of(
            "src/main/resources/db/migration/V95__settlement_line_multi_source_collision.sql"));
        for(var key:new String[]{
            "CREATE INDEX finance_cost_settlement_line_reverse_v95",
            "BEFORE UPDATE OR DELETE ON spapi_settlement_report_line",
            "CREATE TRIGGER finance_cost_allocation_shared_line_guard_trigger_v95",
            "NEW.state='APPROVED_UNVERIFIED'",
            "y.source_id<>x.source_id"
        }) assertTrue(ddl.contains(key),key);
        assertFalse(ddl.contains("UPDATE finance_profit_report"));
        assertFalse(ddl.contains("INSERT INTO finance_source_event"));
    }
}
