package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderLotAssignmentIntegrityV203Test {
    @Test void localIntegrityNeverUpgradesActualCogs() {
        var jdbc=mock(JdbcTemplate.class);
        var source=mock(OrderCostReadinessService.class);
        var svc=new OrderLotAssignmentIntegrityServiceV203(jdbc,source);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),order=UUID.randomUUID();
        when(source.preview(tenant,mp,order)).thenReturn(
            new OrderCostEngine.Preview("COGS_READY_PREVIEW","USD",
                Instant.parse("2026-10-01T10:00:00Z").toString(),
                1,1,"2.50",List.of(new OrderCostEngine.Line(
                    "sale-1","sku-1",1,"LOCAL-1","2.50","USD","2.50","READY"))));
        var p=svc.preview(tenant,mp,order);
        assertTrue(p.claims().isEmpty());
        assertFalse(p.saleLotAttributed());
        assertFalse(p.actualBatchCogsVerified());
        assertNull(p.realizedBatchCogs());
        assertTrue(p.blockerCodes().contains("NO_SALE_TO_LOCAL_PICK_CLAIMS"));
        assertTrue(p.blockerCodes().contains(
            "EXTERNAL_WMS_OPERATOR_AND_PHYSICAL_DISPATCH_UNVERIFIED"));
    }

    @Test void sourceChangeOrSkuMismatchMustInvalidateApparentLocalLink() {
        var line=new OrderCostEngine.Line("item-1","SKU",2,
            "INTERNAL-ONE","3.00","USD","6.00","READY");
        assertEquals("LOCAL_LINK_INTACT_UNVERIFIED",
            OrderLotAssignmentIntegrityServiceV203.effectiveState(
                "LOCAL_LINK_INTACT_UNVERIFIED","COGS_READY_PREVIEW",
                line,"INTERNAL-ONE"));
        assertEquals("SALE_SKU_MAPPING_STALE",
            OrderLotAssignmentIntegrityServiceV203.effectiveState(
                "LOCAL_LINK_INTACT_UNVERIFIED","COGS_READY_PREVIEW",
                line,"INTERNAL-TWO"));
        assertEquals("ORDER_COST_OR_ITEM_EVIDENCE_STALE",
            OrderLotAssignmentIntegrityServiceV203.effectiveState(
                "LOCAL_LINK_INTACT_UNVERIFIED","INCOMPLETE",
                line,"INTERNAL-ONE"));
        assertEquals("RETRACTED",
            OrderLotAssignmentIntegrityServiceV203.effectiveState(
                "RETRACTED","COGS_READY_PREVIEW",line,"INTERNAL-ONE"));
        assertEquals("QUANTITY_CONSERVATION_BROKEN",
            OrderLotAssignmentIntegrityServiceV203.effectiveState(
                "QUANTITY_CONSERVATION_BROKEN","COGS_READY_PREVIEW",
                line,"INTERNAL-ONE"));
    }

    @Test void verifierMustReadWholeOrderAndNeverPostOrGuess() {
        String sql=OrderLotAssignmentIntegrityServiceV203.INTEGRITY_SQL;
        assertTrue(sql.contains("finance_sale_lot_integrity_v106"));
        assertTrue(sql.contains("v.tenant_id=?"));
        assertTrue(sql.contains("v.marketplace_id=?"));
        assertTrue(sql.contains("v.sales_order_id=?"));
        assertTrue(sql.contains("LIMIT 1001"));
        assertFalse(sql.contains("INSERT "));
        assertFalse(sql.contains("UPDATE "));
    }

    @Test void routeReusesTenantAuthorizationAndNoStore() {
        var access=mock(TenantAccessService.class);
        var svc=mock(OrderLotAssignmentIntegrityServiceV203.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        var tenant=UUID.randomUUID();var mp=UUID.randomUUID();var order=UUID.randomUUID();
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        var response=new OrderLotAssignmentIntegrityControllerV203(access,svc)
            .preview(auth,request,mp,order);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(svc).preview(tenant,mp,order);
    }
}
