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

class WarehouseStockOperationServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseStockOperationService service=new WarehouseStockOperationService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID source=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID destination=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID sku=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID key=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final UUID id=UUID.fromString("77777777-7777-4777-8777-777777777777");

    private WarehouseStockOperationService.Request request(String type,UUID dest,
                                                           int quantity,long expected) {
        return new WarehouseStockOperationService.Request(
            key,type,source,dest,sku,quantity,expected,"Manual stock verification");
    }
    private static void status(HttpStatus expected,Runnable call) {
        assertEquals(expected,assertThrows(ResponseStatusException.class,call::run).getStatusCode());
    }
    private void stubActive(boolean transfer,long stock) {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.queryForList(contains("FROM erp_local_stock_operation"),
            eq(UUID.class),eq(tenant),eq(key))).thenReturn(List.of());
        if(transfer)when(jdbc.queryForList(contains("id IN (?,?)"),eq(UUID.class),
            eq(tenant),eq(source),eq(destination))).thenReturn(List.of(source,destination));
        else when(jdbc.queryForList(contains("erp_local_warehouse"),
            eq(UUID.class),eq(tenant),eq(source))).thenReturn(List.of(source));
        when(jdbc.queryForList(contains("FROM product_sku"),
            eq(UUID.class),eq(tenant),eq(sku))).thenReturn(List.of(sku));
        when(jdbc.queryForObject(contains("SUM(quantity)"),eq(Long.class),
            eq(tenant),eq(source),eq(sku))).thenReturn(stock);
    }

    @Test void validatesPayloadBeforeLookingUpTenant() {
        status(HttpStatus.BAD_REQUEST,()->service.post(tenant,actor,request(null,null,1,1)));
        status(HttpStatus.BAD_REQUEST,()->service.post(tenant,actor,request("TRANSFER",source,1,1)));
        status(HttpStatus.BAD_REQUEST,()->service.post(tenant,actor,request("OUTBOUND",destination,1,1)));
        status(HttpStatus.BAD_REQUEST,()->service.post(tenant,actor,request("COUNT",null,-1,0)));
        status(HttpStatus.BAD_REQUEST,()->service.post(tenant,actor,request("OUTBOUND",null,0,0)));
        verifyNoInteractions(jdbc);
    }
    @Test void computesSignedCountDeltaAndStopsInsufficientStock() {
        assertEquals(-6,WarehouseStockOperationService.delta("COUNT",4,10));
        assertEquals(8,WarehouseStockOperationService.delta("COUNT",10,2));
        status(HttpStatus.CONFLICT,()->WarehouseStockOperationService.delta("OUTBOUND",4,3));
        status(HttpStatus.CONFLICT,()->WarehouseStockOperationService.delta("COUNT",4,4));
        status(HttpStatus.BAD_REQUEST,()->WarehouseStockOperationService.delta("COUNT",
            0,2_000_000));
    }
    @Test void suspendedTenantCannotWriteStock() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of());
        status(HttpStatus.NOT_FOUND,()->service.post(tenant,actor,request("OUTBOUND",null,1,4)));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void updatedBalanceRequiresRefreshingAndDoesNotWrite() {
        stubActive(false,3);
        status(HttpStatus.CONFLICT,()->service.post(tenant,actor,
            request("OUTBOUND",null,2,5)));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void insufficientStockRejectsEvenWhenClientBalanceIsFresh() {
        stubActive(false,3);
        status(HttpStatus.CONFLICT,()->service.post(tenant,actor,
            request("OUTBOUND",null,4,3)));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void differentPayloadCannotReuseIdempotencyKey() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.queryForList(contains("FROM erp_local_stock_operation"),
            eq(UUID.class),eq(tenant),eq(key))).thenReturn(List.of(id));
        var previous=new WarehouseStockOperationService.Operation(id.toString(),
            "OUTBOUND",source.toString(),null,sku.toString(),2,5,-2,3,
            "Different reason",actor.toString(),"2026-10-31T00:00:00Z");
        when(jdbc.query(contains("FROM erp_local_stock_operation WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(id))).thenReturn(List.of(previous));
        status(HttpStatus.CONFLICT,()->service.post(tenant,actor,
            request("OUTBOUND",null,2,5)));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void transferWritesEqualAndOppositeMovementsInOneTransaction() {
        stubActive(true,10);
        var saved=new WarehouseStockOperationService.Operation(id.toString(),
            "TRANSFER",source.toString(),destination.toString(),sku.toString(),
            4,10,-4,6,"Manual stock verification",actor.toString(),"2026-10-31T00:00:00Z");
        when(jdbc.query(contains("FROM erp_local_stock_operation WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),any(UUID.class))).thenReturn(List.of(saved));
        var result=service.post(tenant,actor,request("TRANSFER",destination,4,10));
        assertEquals(6,result.afterQuantity());
        verify(jdbc).update(contains("INSERT INTO erp_local_stock_operation"),
            any(UUID.class),eq(tenant),eq(key),eq("TRANSFER"),eq(source),
            eq(destination),eq(sku),eq(4),eq(10L),eq(-4),eq(6L),
            eq("Manual stock verification"),eq(actor));
        verify(jdbc).update(contains("INSERT INTO erp_local_stock_movement"),
            any(UUID.class),eq(tenant),eq(source),eq(sku),any(UUID.class),
            eq("TRANSFER_OUT"),eq(-4),eq(actor));
        verify(jdbc).update(contains("INSERT INTO erp_local_stock_movement"),
            any(UUID.class),eq(tenant),eq(destination),eq(sku),any(UUID.class),
            eq("TRANSFER_IN"),eq(4),eq(actor));
    }
}
