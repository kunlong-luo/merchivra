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

class ReceiptBatchServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ReceiptBatchService service=new ReceiptBatchService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID inspector=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID releaser=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID receipt=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID warehouse=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID sku=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final UUID qc=UUID.fromString("77777777-7777-4777-8777-777777777777");

    private ReceiptBatchService.Batch batch(String status,int accepted,int rejected) {
        return new ReceiptBatchService.Batch(receipt.toString(),warehouse.toString(),
            sku.toString(),"LOT-A",10,status,
            "PENDING_QC".equals(status)?null:qc.toString(),
            accepted,rejected,"PENDING_QC".equals(status)?null:inspector.toString(),
            "RELEASED".equals(status)?releaser.toString():null,
            "2026-11-01T00:00:00Z");
    }
    private void tenantLock(){
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
    }
    private void batchLock(){
        when(jdbc.queryForList(contains("FROM erp_receipt_quality_batch"),
            eq(UUID.class),eq(tenant),eq(receipt))).thenReturn(List.of(receipt));
    }
    private void stored(ReceiptBatchService.Batch... snapshots){
        when(jdbc.query(contains("FROM erp_receipt_quality_batch b"),
            any(RowMapper.class),eq(tenant),eq(receipt)))
            .thenReturn(java.util.Arrays.asList(snapshots[0]),
                java.util.Arrays.asList(snapshots[snapshots.length-1]));
    }
    @Test void invalidLotCannotMutateAnyDatabase(){
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.relabel(tenant,inspector,receipt,
                new ReceiptBatchService.LotChange("LOT-A","../../bad","Invalid location")))
            .getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void lotVersionMismatchFailsClosed(){
        tenantLock();
        when(jdbc.query(contains("SELECT lot_code FROM erp_receipt_quality_batch"),
            any(RowMapper.class),eq(tenant),eq(receipt))).thenReturn(List.of("LOT-B"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.relabel(tenant,inspector,receipt,
                new ReceiptBatchService.LotChange("LOT-A","LOT-NEW","Supplier label differs")))
            .getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void relabelPendingLotRecordsOriginalNameWithoutStockChange(){
        tenantLock();
        when(jdbc.query(contains("SELECT lot_code FROM erp_receipt_quality_batch"),
            any(RowMapper.class),eq(tenant),eq(receipt))).thenReturn(List.of("LOT-A"));
        stored(batch("PENDING_QC",0,0));
        service.relabel(tenant,inspector,receipt,
            new ReceiptBatchService.LotChange("LOT-A","LOT-B","Supplier batch ref updated"));
        verify(jdbc).update(contains("UPDATE erp_receipt_quality_batch"),
            eq("LOT-B"),eq(tenant),eq(receipt));
        verify(jdbc).update(contains("INSERT INTO erp_receipt_quality_batch_event"),
            any(UUID.class),eq(tenant),eq(receipt),eq(inspector),
            eq("LOT_RELABELED"),eq("LOT-A"),eq("LOT-B"),isNull(),
            eq("Supplier batch ref updated"));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),any(Object[].class));
    }
    @Test void cannotReleaseUninspectedReceipt(){
        tenantLock();batchLock();stored(batch("PENDING_QC",0,0));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.release(tenant,releaser,receipt)).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void qcInspectorMustNotSelfApproveRelease(){
        tenantLock();batchLock();stored(batch("INSPECTED",7,3));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.release(tenant,inspector,receipt)).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void independentOwnerReleasesOnlyAcceptedQuantityExactlyOnce(){
        tenantLock();batchLock();stored(batch("INSPECTED",7,3),batch("RELEASED",7,3));
        when(jdbc.queryForList(contains("FROM erp_local_warehouse"),
            eq(UUID.class),eq(tenant),eq(warehouse))).thenReturn(List.of(warehouse));
        var result=service.release(tenant,releaser,receipt);
        assertEquals("RELEASED",result.status());
        assertEquals(7,result.acceptedQuantity());
        verify(jdbc).update(contains("'QC_RELEASE'"),
            any(UUID.class),eq(tenant),eq(warehouse),eq(sku),eq(qc),eq(7),eq(releaser));
        verify(jdbc).update(contains("SET status='RELEASED'"),
            eq(releaser),eq(tenant),eq(receipt));
        verify(jdbc).update(contains("INSERT INTO erp_receipt_quality_batch_event"),
            any(UUID.class),eq(tenant),eq(receipt),eq(releaser),
            eq("QC_RELEASED"),isNull(),isNull(),eq(7),anyString());
        verify(jdbc,never()).update(contains("QC_QUARANTINE"),any(Object[].class));
    }
    @Test void zeroAcceptedCanCompleteWithNoAvailableStockMovement(){
        tenantLock();batchLock();stored(batch("INSPECTED",0,10),batch("RELEASED",0,10));
        when(jdbc.queryForList(contains("FROM erp_local_warehouse"),
            eq(UUID.class),eq(tenant),eq(warehouse))).thenReturn(List.of(warehouse));
        assertEquals("RELEASED",service.release(tenant,releaser,receipt).status());
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
    }
    @Test void repeatedReleaseReturnsSameRecordWithoutSecondCredit(){
        tenantLock();batchLock();stored(batch("RELEASED",7,3));
        assertEquals("RELEASED",service.release(tenant,releaser,receipt).status());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
