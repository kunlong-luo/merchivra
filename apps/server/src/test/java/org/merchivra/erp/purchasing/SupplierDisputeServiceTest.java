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

class SupplierDisputeServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SupplierDisputeService service=new SupplierDisputeService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID owner=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID reviewer=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID order=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID line=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID supplier=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final UUID key=UUID.fromString("77777777-7777-4777-8777-777777777777");
    private final UUID varianceId=UUID.fromString("88888888-8888-4888-8888-888888888888");
    private final UUID qcId=UUID.fromString("99999999-9999-4999-8999-999999999999");
    private final UUID claimId=UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    private SupplierDisputeService.Report shortage(){
        return new SupplierDisputeService.Report(key,line,"PO_SHORTAGE",null,0,
            "Physical receipt count remains incomplete");
    }
    private SupplierDisputeService.Variance variance(String state){
        return new SupplierDisputeService.Variance(varianceId.toString(),order.toString(),
            line.toString(),null,"PO_SHORTAGE",10,7,3,
            "Physical receipt count remains incomplete",
            state,owner.toString(),
            "OPEN".equals(state)?null:reviewer.toString(),
            "2026-10-31T12:00:00Z");
    }
    private SupplierDisputeService.Claim claim(String status){
        return new SupplierDisputeService.Claim(claimId.toString(),order.toString(),
            supplier.toString(),null,qcId.toString(),3,"REPLACEMENT",
            "QC units damaged during shipment",status,owner.toString(),
            "APPROVED".equals(status)?reviewer.toString():null,
            null,"2026-10-31T13:00:00Z");
    }
    private void activeTenant(){
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
    }
    private void orderStatus(String status){
        when(jdbc.query(contains("FROM erp_purchase_order"),
            any(RowMapper.class),eq(tenant),eq(order)))
            .thenReturn(List.of(newPrivateOrderState(status)));
    }
    // Nested records in the service are private, so use the public contract
    // through a separately stubbed RowMapper answer to build a row dynamically.
    private Object newPrivateOrderState(String status){
        try {
            Class<?> c=Class.forName("org.merchivra.erp.purchasing.SupplierDisputeService$OrderState");
            var ctor=c.getDeclaredConstructor(String.class,String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(status,supplier.toString());
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private Object newPrivateQuantities(int ordered,long received){
        try {
            Class<?> c=Class.forName("org.merchivra.erp.purchasing.SupplierDisputeService$Quantities");
            var ctor=c.getDeclaredConstructor(int.class,long.class);
            ctor.setAccessible(true);
            return ctor.newInstance(ordered,received);
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private void lineQuantities(int ordered,long received){
        when(jdbc.query(contains("FROM erp_purchase_order_line l"),
            any(RowMapper.class),eq(tenant),eq(order),eq(line)))
            .thenReturn(List.of(newPrivateQuantities(ordered,received)));
    }
    private void storedVariance(SupplierDisputeService.Variance v){
        when(jdbc.query(contains("FROM erp_purchase_variance WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),any(UUID.class)))
            .thenReturn(List.of(v));
    }
    private void storedClaim(SupplierDisputeService.Claim c){
        when(jdbc.query(contains("FROM erp_supplier_claim WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(claimId)))
            .thenReturn(List.of(c));
    }
    @Test void invalidMismatchAndMissingSourcesDoNotTouchDatabase(){
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.report(tenant,owner,order,
                new SupplierDisputeService.Report(key,line,"ASN_SHORTAGE",null,0,
                    "Missing ASN source"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.report(tenant,owner,order,
                new SupplierDisputeService.Report(key,line,"PO_SHORTAGE",null,2,
                    "Reported excess belongs to another type"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.createClaim(tenant,owner,
                new SupplierDisputeService.ClaimRequest(key,null,null,1,
                    "REPLACEMENT","Missing source"))).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void unapprovedPurchaseCannotCreateClaimableDiscrepancy(){
        activeTenant();orderStatus("DRAFT");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.report(tenant,owner,order,shortage())).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void shortageSnapshotUsesRealRecordedReceiptAndNeverPostsStock(){
        activeTenant();orderStatus("RECEIVING");lineQuantities(10,7);
        storedVariance(variance("OPEN"));
        SupplierDisputeService.Variance found=service.report(tenant,owner,order,shortage());
        assertEquals(3,found.varianceQuantity());
        verify(jdbc).update(contains("INSERT INTO erp_purchase_variance"),
            any(UUID.class),eq(tenant),eq(key),eq(order),eq(line),isNull(),
            eq("PO_SHORTAGE"),eq(10),eq(7),eq(3),
            eq("Physical receipt count remains incomplete"),eq(owner));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
        verify(jdbc,never()).update(contains("erp_purchase_receipt"),
            any(Object[].class));
    }
    @Test void shortageNeedsDifferentOwnerAndFreshRealReceiptsForReview(){
        activeTenant();storedVariance(variance("OPEN"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.changeVariance(tenant,owner,varianceId,"review"))
            .getStatusCode());
        orderStatus("RECEIVING");lineQuantities(10,8);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.changeVariance(tenant,reviewer,varianceId,"review"))
            .getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void discrepancyCannotCloseBeforeIndependentReview(){
        activeTenant();storedVariance(variance("OPEN"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.changeVariance(tenant,reviewer,varianceId,"close"))
            .getStatusCode());
    }
    @Test void claimQuantityCannotExceedQcRejection(){
        activeTenant();
        when(jdbc.query(contains("FROM erp_supplier_claim WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),any(UUID.class)))
            .thenReturn(List.of());
        when(jdbc.query(contains("FROM erp_purchase_receipt_qc q"),
            any(RowMapper.class),eq(tenant),eq(qcId)))
            .thenReturn(List.of(newPrivateSource(2L,"INSPECTED")));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.createClaim(tenant,owner,
                new SupplierDisputeService.ClaimRequest(key,null,qcId,3,
                    "CREDIT_REQUEST","QC rejected units shortage")))
            .getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    private Object newPrivateSource(long limit,String status){
        try{
            Class<?> c=Class.forName("org.merchivra.erp.purchasing.SupplierDisputeService$Source");
            var ctor=c.getDeclaredConstructor(String.class,String.class,long.class,String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(order.toString(),supplier.toString(),limit,status);
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    @Test void creatorCannotSelfApproveSubmittedClaim(){
        activeTenant();storedClaim(claim("SUBMITTED"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.changeClaim(tenant,owner,claimId,"approve",null))
            .getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void approvedClaimClosureNeedsDocumentedResolution(){
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.changeClaim(tenant,reviewer,claimId,"close","ok"))
            .getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void approvedClaimCloseNeverPostsMoneyOrInventory(){
        activeTenant();
        when(jdbc.query(contains("FROM erp_supplier_claim WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(claimId)))
            .thenReturn(List.of(claim("APPROVED")),List.of(claim("CLOSED")));
        assertEquals("CLOSED",
            service.changeClaim(tenant,reviewer,claimId,"close",
                "Supplier contacted; awaiting documented external settlement").status());
        verify(jdbc).update(contains("UPDATE erp_supplier_claim"),
            eq("CLOSED"),eq("CLOSED"),eq(reviewer),eq("CLOSED"),eq("CLOSED"),
            eq("Supplier contacted; awaiting documented external settlement"),
            eq(tenant),eq(claimId),eq("APPROVED"));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
    }
    @Test void supplierPerformanceIsTenantScopedReadOnly(){
        service.performance(tenant);
        verify(jdbc).query(contains("FROM erp_supplier s WHERE s.tenant_id=?"),
            any(RowMapper.class),eq(tenant));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
