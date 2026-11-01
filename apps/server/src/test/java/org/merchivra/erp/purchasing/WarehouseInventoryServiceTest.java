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

class WarehouseInventoryServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseInventoryService warehouse=new WarehouseInventoryService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID location=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID sku=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID receipt=UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Test void malformedWarehouseCannotBeInserted() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->warehouse.create(tenant,actor,"bad code","warehouse")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->warehouse.create(tenant,actor,"MAIN","\n")).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void nonexistentOrForeignWarehouseCannotReceive() {
        when(jdbc.queryForList(contains("erp_local_warehouse"),
            eq(UUID.class),eq(tenant),eq(location))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->warehouse.requireActive(tenant,location)).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void missingWarehouseIsRejectedBeforeDatabase() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->warehouse.requireActive(tenant,null)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void stockForAnotherTenantIsNotExposed() {
        when(jdbc.queryForObject(contains("erp_local_warehouse"),
            eq(Integer.class),eq(tenant),eq(location))).thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->warehouse.stock(tenant,location)).getStatusCode());
        verify(jdbc,never()).query(contains("erp_local_stock_movement"),
            any(RowMapper.class),any(UUID.class),any(UUID.class));
    }
    @Test void newReceiptCreatesHeldLotAndAuditWithoutCreditingStock() {
        warehouse.recordReceipt(tenant,actor,location,sku,receipt,5);
        verify(jdbc).update(contains("INSERT INTO erp_receipt_quality_batch"),
            eq(tenant),eq(receipt),eq(location),eq(sku),eq(5),
            startsWith("LOT-"),eq(actor));
        verify(jdbc).update(contains("INSERT INTO erp_receipt_quality_batch_event"),
            any(UUID.class),eq(tenant),eq(receipt),eq(actor),startsWith("LOT-"),eq(5));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
    }
    @Test void recordedPhysicalReceiptsRemainSeparateFromReleasedStockQuery() {
        when(jdbc.queryForObject(contains("erp_local_warehouse"),
            eq(Integer.class),eq(tenant),eq(location))).thenReturn(1);
        warehouse.stock(tenant,location);
        verify(jdbc).query(contains("WITH sku_keys AS"),
            any(RowMapper.class),eq(tenant),eq(location),eq(tenant),eq(location));
    }
    @Test void pendingReceiptsAreScopedToAuthenticatedTenant() {
        warehouse.pendingReceipts(tenant);
        verify(jdbc).query(contains("o.status IN ('APPROVED','RECEIVING')"),
            any(RowMapper.class),eq(tenant));
    }
}
