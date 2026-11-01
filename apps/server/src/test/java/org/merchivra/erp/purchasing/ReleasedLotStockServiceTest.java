package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
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

class ReleasedLotStockServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseStockOperationService stock=mock(WarehouseStockOperationService.class);
    private final ReleasedLotStockService service=new ReleasedLotStockService(jdbc,stock);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID warehouse=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID receipt=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID sku=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID bin=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final UUID key=UUID.fromString("77777777-7777-4777-8777-777777777777");
    private final UUID position=UUID.fromString("88888888-8888-4888-8888-888888888888");
    private final UUID pickId=UUID.fromString("99999999-9999-4999-8999-999999999999");
    private final UUID operation=UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    private ReleasedLotStockService.PickRequest pick(int quantity,long balance) {
        return new ReleasedLotStockService.PickRequest(key,warehouse,sku,
            "FEFO",quantity,balance,"Actually picked from storage rack");
    }
    private void activeTenant(){
        when(jdbc.queryForList(contains("FROM tenant"),
            eq(UUID.class),eq(tenant))).thenReturn(List.of(tenant));
    }
    private void activeWarehouse(){
        when(jdbc.queryForList(contains("FROM erp_local_warehouse"),
            eq(UUID.class),eq(tenant),eq(warehouse))).thenReturn(List.of(warehouse));
    }
    private void balance(long total){
        when(jdbc.queryForObject(contains("FROM erp_local_stock_movement"),
            eq(Long.class),eq(tenant),eq(warehouse),eq(sku))).thenReturn(total);
    }
    @Test void malformedPicksAndPutawayCannotTouchAnyDatabase(){
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.pick(tenant,actor,pick(0,10))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.pick(tenant,actor,
                new ReleasedLotStockService.PickRequest(key,warehouse,sku,
                    "LIFO",1,10,"Actual cartons picked"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.putaway(tenant,actor,
                new ReleasedLotStockService.PutawayRequest(key,receipt,bin,0,null,
                    "Receipt physically stored"))).getStatusCode());
        verifyNoInteractions(jdbc,stock);
    }
    @Test void cannotPutawayUnreleasedReceivingStock() throws Exception {
        activeTenant();
        when(jdbc.query(contains("FROM erp_receipt_quality_batch b"),
            any(RowMapper.class),eq(tenant),eq(receipt))).thenAnswer(inv->{
                RowMapper<?> mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getObject("warehouse_id",UUID.class)).thenReturn(warehouse);
                when(rs.getObject("product_sku_id",UUID.class)).thenReturn(sku);
                when(rs.getString("status")).thenReturn("INSPECTED");
                when(rs.getInt("accepted")).thenReturn(7);
                return List.of(mapper.mapRow(rs,0));
            });
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.putaway(tenant,actor,
                new ReleasedLotStockService.PutawayRequest(key,receipt,bin,7,null,
                    "Actually stored in approved bin"))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void cannotPickMoreThanPositionedAndNonExpiredLots(){
        activeTenant();activeWarehouse();balance(10);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.pick(tenant,actor,pick(3,10))).getStatusCode());
        verifyNoInteractions(stock);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void staleWarehouseBalanceMustRejectPickWithoutMovingPositions(){
        activeTenant();activeWarehouse();balance(7);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.pick(tenant,actor,pick(3,10))).getStatusCode());
        verifyNoInteractions(stock);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void fifoFefoPickDecrementsOnePositionAndGlobalOutboundAtomically(){
        activeTenant();activeWarehouse();balance(10);
        var positionSnapshot=new ReleasedLotStockService.Position(position.toString(),
            receipt.toString(),warehouse.toString(),sku.toString(),
            bin.toString(),"SHELF-A","LOT-A",7,7,
            "2026-12-01","2026-11-01T00:00:00Z");
        when(jdbc.query(contains("FROM erp_local_lot_position p"),
            any(RowMapper.class),eq(tenant),eq(warehouse),eq(sku),eq("FEFO")))
            .thenReturn(List.of(positionSnapshot));
        when(jdbc.update(contains("UPDATE erp_local_lot_position"),
            eq(3),eq(tenant),eq(position),eq(3))).thenReturn(1);
        var op=new WarehouseStockOperationService.Operation(operation.toString(),
            "OUTBOUND",warehouse.toString(),null,sku.toString(),3,10,-3,7,
            "Actually picked from storage rack",actor.toString(),"2026-11-01T00:00:00Z");
        when(stock.post(eq(tenant),eq(actor),
            any(WarehouseStockOperationService.Request.class))).thenReturn(op);
        var stub=new ReleasedLotStockService.Pick(pickId.toString(),warehouse.toString(),
            sku.toString(),operation.toString(),"FEFO",3,10,
            "Actually picked from storage rack",actor.toString(),List.of());
        when(jdbc.query(contains("FROM erp_local_lot_pick WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),any(UUID.class))).thenReturn(List.of(stub));
        var line=new ReleasedLotStockService.PickLine(position.toString(),
            "LOT-A","SHELF-A",3,"2026-12-01");
        when(jdbc.query(contains("FROM erp_local_lot_pick_line l"),
            any(RowMapper.class),eq(tenant),any(UUID.class))).thenReturn(List.of(line));
        var result=service.pick(tenant,actor,pick(3,10));
        assertEquals(3,result.allocations().getFirst().quantity());
        verify(jdbc).update(contains("INSERT INTO erp_local_lot_pick"),
            any(UUID.class),eq(tenant),eq(key),eq(warehouse),eq(sku),
            eq(operation),eq("FEFO"),eq(3),eq(10L),
            eq("Actually picked from storage rack"),eq(actor));
        verify(stock).post(eq(tenant),eq(actor),argThat(
            req->"OUTBOUND".equals(req.type()) && req.quantity()==3
              && req.expectedOnHand()==10 && req.requestKey().equals(key)));
    }
    @Test void previewIncludesPolicySortingAndExcludesExpiredLots(){
        service.preview(tenant,warehouse,sku,"FIFO");
        verify(jdbc).query(argThat((String sql)->
                sql.contains("p.expiry_date>=CURRENT_DATE")
                  && sql.contains("b.created_at ASC")),
            any(RowMapper.class),eq(tenant),eq(warehouse),eq(sku),eq("FIFO"));
        verifyNoInteractions(stock);
    }
}
