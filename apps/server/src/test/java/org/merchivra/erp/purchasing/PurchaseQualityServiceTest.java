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

class PurchaseQualityServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PurchaseQualityService service=new PurchaseQualityService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID receiptId=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID orderId=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID lineId=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID sku=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final UUID warehouse=UUID.fromString("77777777-7777-4777-8777-777777777777");
    private final UUID requestId=UUID.fromString("88888888-8888-4888-8888-888888888888");
    private final UUID qcId=UUID.fromString("99999999-9999-4999-8999-999999999999");
    private final UUID returnId=UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID supplier=UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    private PurchaseQualityService.Receipt source(String inspectionId) {
        return new PurchaseQualityService.Receipt(receiptId.toString(),orderId.toString(),
            lineId.toString(),warehouse.toString(),sku.toString(),10,
            "2026-10-31T12:00:00Z",inspectionId,0,0,0);
    }
    private PurchaseQualityService.Inspection inspection() {
        return new PurchaseQualityService.Inspection(qcId.toString(),receiptId.toString(),
            warehouse.toString(),sku.toString(),10,7,3,"Packaging damaged",
            actor.toString(),"2026-10-31T12:30:00Z");
    }
    private PurchaseQualityService.SupplierReturn supplierReturn(String status) {
        return new PurchaseQualityService.SupplierReturn(returnId.toString(),qcId.toString(),
            supplier.toString(),2,"RMA-123",status,actor.toString(),
            "2026-10-31T13:00:00Z",
            "DISPATCHED".equals(status)?"2026-10-31T14:00:00Z":null);
    }
    private void lockTenant() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
    }
    private void sourceReceipt(PurchaseQualityService.Receipt r) {
        when(jdbc.query(contains("FROM erp_purchase_receipt r"),
            any(RowMapper.class),eq(tenant),eq(receiptId))).thenReturn(List.of(r));
    }
    @Test void inspectionMustAccountForEveryPhysicalUnit() {
        PurchaseQualityService.checkParts(10,7,3);
        PurchaseQualityService.checkParts(10,10,0);
        for(int[] pieces:new int[][]{{10,6,3},{10,-1,11},{10,11,-1},{0,0,0}}) {
            assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
                ()->PurchaseQualityService.checkParts(pieces[0],pieces[1],pieces[2]))
                .getStatusCode());
        }
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.inspect(tenant,actor,receiptId,
                new PurchaseQualityService.InspectRequest(requestId,7,3,"bad")))
            .getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void cannotInspectHistoricalReceiptWithoutKnownWarehouse() {
        lockTenant();
        var original=source(null);
        sourceReceipt(new PurchaseQualityService.Receipt(
            original.id(),original.orderId(),original.lineId(),null,
            original.productSkuId(),10,original.receivedAt(),null,0,0,0));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.inspect(tenant,actor,receiptId,
                new PurchaseQualityService.InspectRequest(requestId,7,3,"Packaging damaged")))
            .getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void cannotQuarantineMoreThanSignedLocalStock() {
        lockTenant();sourceReceipt(source(null));
        when(jdbc.queryForList(contains("FROM erp_local_warehouse"),
            eq(UUID.class),eq(tenant),eq(warehouse))).thenReturn(List.of(warehouse));
        when(jdbc.queryForObject(contains("FROM erp_local_stock_movement"),
            eq(Long.class),eq(tenant),eq(warehouse),eq(sku))).thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.inspect(tenant,actor,receiptId,
                new PurchaseQualityService.InspectRequest(requestId,7,3,"Packaging damaged")))
            .getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void oneInspectionMovesOnlyRejectedUnitsOutOfOnHand() {
        lockTenant();sourceReceipt(source(null));
        when(jdbc.queryForList(contains("FROM erp_local_warehouse"),
            eq(UUID.class),eq(tenant),eq(warehouse))).thenReturn(List.of(warehouse));
        when(jdbc.queryForObject(contains("FROM erp_local_stock_movement"),
            eq(Long.class),eq(tenant),eq(warehouse),eq(sku))).thenReturn(10L);
        when(jdbc.query(contains("FROM erp_purchase_receipt_qc WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),any(UUID.class)))
            .thenReturn(List.of(inspection()));
        var qc=service.inspect(tenant,actor,receiptId,
            new PurchaseQualityService.InspectRequest(requestId,7,3,"Packaging damaged"));
        assertEquals(3,qc.rejectedQuantity());
        verify(jdbc).update(contains("INSERT INTO erp_purchase_receipt_qc"),
            any(UUID.class),eq(tenant),eq(requestId),eq(receiptId),eq(warehouse),
            eq(sku),eq(10),eq(7),eq(3),eq("Packaging damaged"),eq(actor));
        verify(jdbc).update(contains("INSERT INTO erp_local_stock_movement"),
            any(UUID.class),eq(tenant),eq(warehouse),eq(sku),
            any(UUID.class),eq(-3),eq(actor));
    }
    @Test void sameQcKeyReplaysWithoutPostingAgain() {
        lockTenant();
        when(jdbc.queryForList(contains("FROM erp_purchase_receipt_qc"),
            eq(UUID.class),eq(tenant),eq(requestId))).thenReturn(List.of(qcId));
        when(jdbc.query(contains("FROM erp_purchase_receipt_qc WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(qcId))).thenReturn(List.of(inspection()));
        assertEquals(qcId.toString(),service.inspect(tenant,actor,receiptId,
            new PurchaseQualityService.InspectRequest(requestId,7,3,"Packaging damaged")).id());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void supplierReturnCannotExceedQuarantinedQuantityOrDoubleDebitStock() {
        lockTenant();
        when(jdbc.queryForList(contains("FROM erp_purchase_receipt_qc"),
            eq(UUID.class),eq(tenant),eq(qcId))).thenReturn(List.of(qcId));
        when(jdbc.query(contains("FROM erp_purchase_receipt_qc WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(qcId))).thenReturn(List.of(inspection()));
        when(jdbc.queryForObject(contains("FROM erp_supplier_return"),
            eq(Long.class),eq(tenant),eq(qcId))).thenReturn(2L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.createReturn(tenant,actor,qcId,
                new PurchaseQualityService.ReturnRequest(requestId,2,"RMA-123")))
            .getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void returnDispatchIsIdempotentAndNeverWritesStockMovement() {
        lockTenant();
        when(jdbc.query(contains("FROM erp_supplier_return WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(returnId)))
            .thenReturn(List.of(supplierReturn("DRAFT")),
                        List.of(supplierReturn("DRAFT")),
                        List.of(supplierReturn("DISPATCHED")));
        when(jdbc.queryForList(contains("FROM erp_purchase_receipt_qc"),
            eq(UUID.class),eq(tenant),eq(qcId))).thenReturn(List.of(qcId));
        assertEquals("DISPATCHED",service.changeReturn(tenant,actor,returnId,"dispatch").status());
        verify(jdbc).update(contains("UPDATE erp_supplier_return"),
            eq("DISPATCHED"),eq("DISPATCHED"),eq(tenant),eq(returnId));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),any(Object[].class));
    }
    @Test void qcAlertsRejectInvalidStateBeforeDbAndCannotReadForeignFinding() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.changeFinding(tenant,actor,qcId,"AUTO_APPROVED"))
            .getStatusCode());
        lockTenant();
        when(jdbc.query(contains("FROM erp_purchase_quality_finding"),
            any(RowMapper.class),eq(tenant),eq(qcId))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.changeFinding(tenant,actor,qcId,"DONE"))
            .getStatusCode());
    }
    @Test void scanOnlyCreatesLocalReviewEvidence() {
        lockTenant();
        var result=service.scan(tenant,null,"SCHEDULED");
        assertEquals("SCHEDULED",result.triggerType());
        verify(jdbc).update(contains("QC_REJECTED_PENDING"),any(UUID.class),eq(tenant));
        verify(jdbc).update(contains("QC_HOLD_AGED"),any(UUID.class),eq(tenant));
        verify(jdbc).update(contains("UPDATE erp_purchase_quality_finding"),
            eq(tenant),any(UUID.class));
        verify(jdbc).update(contains("INSERT INTO erp_purchase_quality_scan_run"),
            any(UUID.class),eq(tenant),isNull(),eq("SCHEDULED"),eq(0),eq(0));
    }
}
