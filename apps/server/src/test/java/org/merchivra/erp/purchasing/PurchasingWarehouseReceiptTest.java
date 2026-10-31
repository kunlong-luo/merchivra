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

class PurchasingWarehouseReceiptTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseInventoryService warehouses=mock(WarehouseInventoryService.class);
    private final PurchasingService service=new PurchasingService(jdbc,warehouses);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID orderId=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID lineId=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID skuId=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID warehouseId=UUID.fromString("66666666-6666-4666-8666-666666666666");

    @Test void newReceiptsMustSpecifyWarehouse() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.receive(tenant,actor,orderId,lineId,3,null)).getStatusCode());
        verifyNoInteractions(jdbc,warehouses);
    }
    @Test void badQuantityNeverPostsAnyStock() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.receive(tenant,actor,orderId,lineId,0,warehouseId)).getStatusCode());
        verifyNoInteractions(jdbc,warehouses);
    }
    @Test void draftCannotReceiveOrCreateWarehouseMovement() {
        var draft=new PurchasingService.Order(orderId.toString(),"PO-T",skuId.toString(),
            "DRAFT","USD",actor.toString(),null,null,"2026-10-31T00:00:00Z");
        when(jdbc.query(contains("FROM erp_purchase_order WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(orderId))).thenReturn(List.of(draft));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.receive(tenant,actor,orderId,lineId,1,warehouseId)).getStatusCode());
        verifyNoInteractions(warehouses);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void approvedReceiptPostsMatchingSkuAndQuantityExactlyOnce() {
        var approved=new PurchasingService.Order(orderId.toString(),"PO-T",skuId.toString(),
            "APPROVED","USD",actor.toString(),null,null,"2026-10-31T00:00:00Z");
        var line=new PurchasingService.Line(lineId.toString(),skuId.toString(),10,0,"4.0");
        when(jdbc.query(contains("FROM erp_purchase_order WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(orderId))).thenReturn(List.of(approved));
        when(jdbc.query(contains("FROM erp_purchase_order_line l"),
            any(RowMapper.class),eq(tenant),eq(orderId))).thenReturn(List.of(line));
        service.receive(tenant,actor,orderId,lineId,3,warehouseId);
        verify(warehouses).requireActive(tenant,warehouseId);
        verify(jdbc).update(contains("INSERT INTO erp_purchase_receipt"),
            any(UUID.class),eq(tenant),eq(orderId),eq(lineId),eq(3),eq(actor),eq(warehouseId));
        verify(warehouses).recordReceipt(eq(tenant),eq(actor),eq(warehouseId),
            eq(skuId),any(UUID.class),eq(3));
    }
}
