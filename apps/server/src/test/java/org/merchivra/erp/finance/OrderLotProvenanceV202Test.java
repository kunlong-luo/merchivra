package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Read-only local candidates must not become a sale-to-lot assignment. */
class OrderLotProvenanceV202Test {
    @Test void incompleteSourceCannotBeTreatedAsProofOfPhysicalLotCogs() {
        var jdbc=mock(JdbcTemplate.class);
        var source=mock(OrderCostReadinessService.class);
        var service=new OrderLotProvenanceServiceV202(jdbc,source);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),order=UUID.randomUUID();
        var preview=new OrderCostEngine.Preview("INCOMPLETE","USD",
            Instant.parse("2026-10-01T10:00:00Z").toString(),
            1,0,null,List.of(new OrderCostEngine.Line("item","sku",2,
                null,null,null,null,"MAPPING_MISSING_AS_OF_DATE")));
        when(source.preview(tenant,mp,order)).thenReturn(preview);
        var p=service.preview(tenant,mp,order);
        assertEquals("INCOMPLETE",p.orderCostReadiness());
        assertTrue(p.skuEvidence().isEmpty());
        assertTrue(p.blockerCodes().contains(
            "SALE_TO_LOCAL_RECEIPT_LOT_LINK_NOT_RECORDED"));
        assertTrue(p.blockerCodes().contains("ORDER_HISTORICAL_SKU_COST_NOT_READY"));
        assertFalse(p.actualBatchCogsVerified());
        assertFalse(p.saleLotAttributed());
        assertNull(p.realizedBatchCogs());
        verifyNoInteractions(jdbc);
    }
    @Test void statesAreConservativeForLegacyMixedCostAndQcMismatch() {
        assertEquals("NO_PRIOR_LOCAL_RECEIPT",
            OrderLotProvenanceServiceV202.state(0,0,0,0,0,0,0,0));
        assertEquals("LOCAL_RECEIPT_OR_QC_IDENTITY_CONFLICT",
            OrderLotProvenanceServiceV202.state(3,1,0,1,0,2,2,1));
        assertEquals("LOCAL_RECEIPT_OR_QC_IDENTITY_CONFLICT",
            OrderLotProvenanceServiceV202.state(1,0,0,0,1,1,1,1));
        assertEquals("LEGACY_LOCAL_RECEIPT_NO_QC_BATCH",
            OrderLotProvenanceServiceV202.state(2,1,0,0,0,1,1,1));
        assertEquals("MULTIPLE_LOCAL_PO_COST_REFERENCES",
            OrderLotProvenanceServiceV202.state(2,0,0,0,0,2,1,2));
        assertEquals("MULTIPLE_LOCAL_PO_COST_REFERENCES",
            OrderLotProvenanceServiceV202.state(2,0,0,0,0,1,2,2));
        assertEquals("NO_RELEASED_LOCAL_BATCH",
            OrderLotProvenanceServiceV202.state(1,0,0,0,0,1,1,0));
        assertEquals("LOCAL_BATCHES_NOT_LINKED_TO_SALES",
            OrderLotProvenanceServiceV202.state(1,0,0,0,0,1,1,1));
    }
    @Test void receiptSqlMustNotSpeculativelyClaimAnOrderOrFbaSource() {
        String sql=OrderLotProvenanceServiceV202.RECEIPT_LINEAGE_SQL;
        assertTrue(sql.contains("r.tenant_id=?"));
        assertTrue(sql.contains("r.received_at<=?"));
        assertTrue(sql.contains("s.internal_sku IN (%s)"));
        assertTrue(sql.contains("b.received_quantity IS DISTINCT FROM r.quantity"));
        assertTrue(sql.contains("q.purchase_receipt_id IS DISTINCT FROM r.id"));
        assertTrue(sql.contains("m.movement_type='QC_RELEASE'"));
        assertTrue(sql.contains("q.accepted_quantity+q.rejected_quantity"));
        assertFalse(sql.contains("sales_order"));
        assertFalse(sql.contains("fba_inbound"));
        assertFalse(sql.contains("INSERT "));
        assertFalse(sql.contains("UPDATE "));
    }
    @Test void endpointRequiresSameTenantScopeAndNoStore() {
        var access=mock(TenantAccessService.class);
        var svc=mock(OrderLotProvenanceServiceV202.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),order=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        var api=new OrderLotProvenanceControllerV202(access,svc);
        var result=api.preview(auth,req,mp,order);
        assertTrue(result.getHeaders().getCacheControl().contains("no-store"));
        verify(svc).preview(tenant,mp,order);
    }
}
