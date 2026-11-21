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
    @Test void newReceiptCreatesHeldLotAndVirtualQcPieceWithoutCreditingStock() {
        UUID bin=UUID.fromString("88888888-8888-4888-8888-888888888888");
        when(jdbc.queryForList(contains("FROM erp_local_stock_bin"),
            eq(UUID.class),eq(tenant),eq(location))).thenReturn(List.of(bin));
        warehouse.recordReceipt(tenant,actor,location,sku,receipt,5);
        verify(jdbc).update(contains("INSERT INTO erp_receipt_quality_batch"),
            eq(tenant),eq(receipt),eq(location),eq(sku),eq(5),
            startsWith("LOT-"),eq(actor));
        verify(jdbc).update(contains("INSERT INTO erp_receipt_quality_batch_event"),
            any(UUID.class),eq(tenant),eq(receipt),eq(actor),startsWith("LOT-"),eq(5));
        verify(jdbc).update(contains("INSERT INTO erp_receipt_lot_piece"),
            any(UUID.class),eq(tenant),eq(receipt),eq(location),eq(sku),
            eq(bin),eq(5),eq(actor));
        verify(jdbc).update(contains("INSERT INTO erp_receipt_lot_piece_event"),
            any(UUID.class),eq(tenant),eq(receipt),any(UUID.class),eq(actor),
            eq(5),eq(bin));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
    }
    @Test void missingDefaultQcBinRollsBackNewReceiptInsteadOfLosingItsLocation() {
        when(jdbc.queryForList(contains("FROM erp_local_stock_bin"),
            eq(UUID.class),eq(tenant),eq(location))).thenReturn(List.of());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->warehouse.recordReceipt(tenant,actor,location,sku,receipt,5))
            .getStatusCode());
        verify(jdbc,never()).update(contains("erp_receipt_lot_piece"),
            any(Object[].class));
    }
    @Test void recordedPhysicalReceiptsRemainSeparateFromReleasedStockQuery() {
        when(jdbc.queryForObject(contains("erp_local_warehouse"),
            eq(Integer.class),eq(tenant),eq(location))).thenReturn(1);
        warehouse.stock(tenant,location);
        verify(jdbc).query(contains("WITH sku_keys AS"),
            any(RowMapper.class),eq(tenant),eq(location),eq(tenant),eq(location));
    }
    /** V123 PG18 A3 regression: status+SKU term must not turn ? AND into
     * a PostgreSQL "$2AND" parameter syntax error (formerly HTTP 500). */
    @Test void filteredPendingReceiptSqlSeparatesBoundStatusFromSearch() {
        doAnswer(inv->{
            String sql=inv.getArgument(0);
            assertTrue(sql.contains("WHERE o.tenant_id=?"));
            assertTrue(sql.matches("(?s).*o.status=\\?\\s+AND \\(POSITION\\(\\? IN UPPER\\(o.order_number\\)\\).*"),
                "SQL must place whitespace after the bound status parameter: "+sql);
            assertFalse(sql.contains("=?AND"));
            Object[] args=(Object[])inv.getRawArguments()[2];
            assertArrayEquals(new Object[]{tenant,"RECEIVING",
                "PO-V113-PEND-","PO-V113-PEND-",51},args);
            return List.of();
        }).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        var page=warehouse.pendingReceiptsPage(tenant,50,"RECEIVING",
            "PO-V113-PEND-",null);
        assertTrue(page.items().isEmpty());
        assertFalse(page.hasMore());
    }

    @Test void pendingReceiptsAreScopedToAuthenticatedTenant() {
        warehouse.pendingReceipts(tenant);
        verify(jdbc).query(contains("o.status IN ('APPROVED','RECEIVING')"),
            any(RowMapper.class),eq(tenant));
    }
}
