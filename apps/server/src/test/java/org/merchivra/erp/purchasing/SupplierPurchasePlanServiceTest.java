package org.merchivra.erp.purchasing;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupplierPurchasePlanServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PurchasingService purchasing=mock(PurchasingService.class);
    private final SupplierPurchasePlanService service=
        new SupplierPurchasePlanService(jdbc,purchasing);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID warehouse=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID supplier=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID sku=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID evidence=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final UUID key=UUID.fromString("77777777-7777-4777-8777-777777777777");
    private final UUID planId=UUID.fromString("88888888-8888-4888-8888-888888888888");

    private SupplierPurchasePlanService.Request request(){
        return new SupplierPurchasePlanService.Request(key,warehouse,supplier,sku,evidence,
            "2026-10-31T07:00:00Z","2026-10-31T07:30:00Z",5L,24);
    }
    private SupplierPurchasePlanService.Recommendation source(long balance,String movement){
        return new SupplierPurchasePlanService.Recommendation(warehouse.toString(),
            supplier.toString(),"Supplier",sku.toString(),"SKU-1",movement,
            "2026-10-31T07:00:00Z","2026-10-31T07:30:00Z",
            balance,5,19,20,12,14,"USD","2.50",
            balance<=5?"LOW_STOCK":"HEALTHY",balance<=5?19L-balance:null,
            balance<=5?24L:null);
    }
    private SupplierPurchasePlanService.Plan stored(String status){
        return new SupplierPurchasePlanService.Plan(planId.toString(),warehouse.toString(),
            supplier.toString(),sku.toString(),evidence.toString(),
            "2026-10-31T07:00:00Z","2026-10-31T07:30:00Z",
            5,5,19,14,20,12,14,24,"USD","2.50","2026-11-14",
            status,"CONVERTED".equals(status)?"99999999-9999-4999-8999-999999999999":null,
            actor.toString(),"2026-10-31T08:00:00Z");
    }
    private void tenantAndWarehouse(){
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.queryForList(contains("FROM erp_local_warehouse"),
            eq(UUID.class),eq(tenant),eq(warehouse))).thenReturn(List.of(warehouse));
    }
    private void current(SupplierPurchasePlanService.Recommendation value){
        when(jdbc.query(contains("AND p.product_sku_id=? AND r.supplier_id=?"),
            any(RowMapper.class),eq(tenant),eq(warehouse),eq(sku),eq(supplier)))
            .thenReturn(List.of(value));
    }
    private void plan(SupplierPurchasePlanService.Plan value){
        when(jdbc.query(contains("FROM erp_local_purchase_plan WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(planId))).thenReturn(List.of(value));
    }
    @Test void cartonRoundingUsesMaximumOfSuggestionAndMoq(){
        assertEquals(24L,SupplierPurchasePlanService.orderQuantity(14,20,12));
        assertEquals(36L,SupplierPurchasePlanService.orderQuantity(29,20,12));
        assertEquals(21L,SupplierPurchasePlanService.orderQuantity(21,10,7));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->SupplierPurchasePlanService.orderQuantity(999_999,5,48)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->SupplierPurchasePlanService.orderQuantity(0,1,1)).getStatusCode());
    }
    @Test void rejectInvalidTermsAndMissingEvidenceWithoutWrites(){
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.setRule(tenant,actor,supplier,sku,0,12,14,"USD","2.50"))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.setRule(tenant,actor,supplier,sku,20,12,14,"USD","free"))
            .getStatusCode());
        var req=request();
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.propose(tenant,actor,new SupplierPurchasePlanService.Request(
                req.requestKey(),req.warehouseId(),req.supplierId(),req.productSkuId(),
                null,req.expectedPolicyUpdatedAt(),req.expectedRuleUpdatedAt(),
                5,24))).getStatusCode());
        verifyNoInteractions(jdbc,purchasing);
    }
    @Test void inactiveTenantCannotCreatePlan(){
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.propose(tenant,actor,request())).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verifyNoInteractions(purchasing);
    }
    @Test void staleEvidenceCannotCreatePlanOrPurchaseOrder(){
        tenantAndWarehouse();
        when(jdbc.queryForList(contains("FROM erp_local_purchase_plan"),
            eq(UUID.class),eq(tenant),eq(key))).thenReturn(List.of());
        current(source(4L,evidence.toString()));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.propose(tenant,actor,request())).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verifyNoInteractions(purchasing);
    }
    @Test void otherOpenSkuPurchaseBlocksDuplicateLocalPlanning(){
        tenantAndWarehouse();
        when(jdbc.queryForList(contains("FROM erp_local_purchase_plan"),
            eq(UUID.class),eq(tenant),eq(key))).thenReturn(List.of());
        current(source(5L,evidence.toString()));
        when(jdbc.queryForObject(contains("FROM erp_purchase_order_line l"),
            eq(Integer.class),eq(tenant),eq(sku))).thenReturn(1);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.propose(tenant,actor,request())).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verifyNoInteractions(purchasing);
    }
    @Test void unconvertedPlanForAnotherWarehouseCannotCauseSecondSkuPlan(){
        tenantAndWarehouse();
        when(jdbc.queryForList(contains("FROM erp_local_purchase_plan"),
            eq(UUID.class),eq(tenant),eq(key))).thenReturn(List.of());
        current(source(5L,evidence.toString()));
        when(jdbc.queryForObject(contains("FROM erp_local_purchase_plan"),
            eq(Integer.class),eq(tenant),eq(sku))).thenReturn(1);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.propose(tenant,actor,request())).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void exactIdempotentPlanReplayDoesNotInsertOrMakePurchaseOrder(){
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.queryForList(contains("FROM erp_local_purchase_plan"),
            eq(UUID.class),eq(tenant),eq(key))).thenReturn(List.of(planId));
        plan(stored("PROPOSED"));
        assertEquals(planId.toString(),service.propose(tenant,actor,request()).id());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verifyNoInteractions(purchasing);
    }
    @Test void conversionRechecksFrozenLedgerSnapshot(){
        tenantAndWarehouse();
        plan(stored("PROPOSED"));
        current(source(6L,evidence.toString()));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.change(tenant,actor,planId,"convert")).getStatusCode());
        verifyNoInteractions(purchasing);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void repeatConversionReturnsExistingPlanWithoutAnotherOrder(){
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        plan(stored("CONVERTED"));
        assertEquals("CONVERTED",service.change(tenant,actor,planId,"convert").status());
        verifyNoInteractions(purchasing);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void successfulConversionCreatesOnlyLocalDraftThenRecordsProvenance(){
        tenantAndWarehouse();
        when(jdbc.query(contains("FROM erp_local_purchase_plan WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(planId)))
            .thenReturn(List.of(stored("PROPOSED")),List.of(stored("CONVERTED")));
        current(source(5L,evidence.toString()));
        var localOrder=new PurchasingService.Order(
            "99999999-9999-4999-8999-999999999999","PO-LOCAL",
            supplier.toString(),"DRAFT","USD",actor.toString(),null,null,
            "2026-10-31T08:00:00Z");
        when(purchasing.create(eq(tenant),eq(actor),eq(supplier),eq("USD"),
            contains("Local warehouse plan"),anyList())).thenReturn(
                new PurchasingService.Detail(localOrder,List.of()));
        var converted=service.change(tenant,actor,planId,"convert");
        assertEquals("CONVERTED",converted.status());
        verify(purchasing).create(eq(tenant),eq(actor),eq(supplier),eq("USD"),
            contains("Local warehouse plan"),
            eq(List.of(new PurchasingService.RequestedLine(sku,24,"2.50"))));
        verify(jdbc).update(contains("UPDATE erp_local_purchase_plan"),
            eq(UUID.fromString(localOrder.id())),eq(tenant),eq(planId));
    }
}
