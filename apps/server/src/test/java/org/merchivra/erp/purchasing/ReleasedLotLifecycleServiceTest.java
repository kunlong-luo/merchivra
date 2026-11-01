package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.time.LocalDate;
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

class ReleasedLotLifecycleServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseStockOperationService stock=mock(WarehouseStockOperationService.class);
    private final ReleasedLotLifecycleService service=new ReleasedLotLifecycleService(jdbc,stock);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID warehouse=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID destinationWh=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID sku=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID receipt=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final UUID position=UUID.fromString("77777777-7777-4777-8777-777777777777");
    private final UUID oldBin=UUID.fromString("88888888-8888-4888-8888-888888888888");
    private final UUID newBin=UUID.fromString("99999999-9999-4999-8999-999999999999");
    private final UUID key=UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID actionId=UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private final UUID operation=UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private final String detail="Physical cartons verified by warehouse owner";

    private ReleasedLotLifecycleService.Request request(String type,int qty,UUID bin,
        Integer observed) {
        return new ReleasedLotLifecycleService.Request(key,type,bin,qty,10,
            List.of("TRANSFER","EXPIRED_QUARANTINE").contains(type)?20L:null,
            observed,detail);
    }
    private void activeTenant() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.queryForList(contains("FROM erp_local_lot_action"),
            eq(UUID.class),eq(tenant),eq(key))).thenReturn(List.of());
    }
    private void source(LocalDate expiry) throws Exception {
        when(jdbc.query(contains("FROM erp_local_lot_position WHERE tenant_id=? AND id=? FOR UPDATE"),
            any(RowMapper.class),eq(tenant),eq(position))).thenAnswer(inv->{
                RowMapper<?> mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getObject("id",UUID.class)).thenReturn(position);
                when(rs.getObject("receipt_id",UUID.class)).thenReturn(receipt);
                when(rs.getObject("warehouse_id",UUID.class)).thenReturn(warehouse);
                when(rs.getObject("product_sku_id",UUID.class)).thenReturn(sku);
                when(rs.getObject("bin_id",UUID.class)).thenReturn(oldBin);
                when(rs.getInt("remaining_quantity")).thenReturn(10);
                when(rs.getObject("expiry_date",LocalDate.class)).thenReturn(expiry);
                return List.of(mapper.mapRow(rs,0));
            });
    }
    private void warehouseLock(boolean transfer) {
        if(transfer)when(jdbc.queryForList(contains("id IN (?,?)"),
            eq(UUID.class),eq(tenant),eq(warehouse),eq(destinationWh)))
            .thenReturn(List.of(warehouse,destinationWh));
        else when(jdbc.queryForList(contains("FROM erp_local_warehouse"),
            eq(UUID.class),eq(tenant),eq(warehouse))).thenReturn(List.of(warehouse));
    }
    private void mappedAction(String type,int qty,UUID targetWarehouse,UUID bin,
                              UUID op,Integer observed){
        var saved=new ReleasedLotLifecycleService.Action(actionId.toString(),type,
            position.toString(),warehouse.toString(),oldBin.toString(),
            targetWarehouse==null?null:targetWarehouse.toString(),
            bin==null?null:bin.toString(),null,op==null?null:op.toString(),
            qty,10,List.of("TRANSFER","EXPIRED_QUARANTINE").contains(type)?20L:null,
            observed,detail,actor.toString(),"2026-11-01T00:00:00Z");
        when(jdbc.query(contains("FROM erp_local_lot_action WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),any(UUID.class))).thenReturn(List.of(saved));
    }
    @Test void malformedRequestsFailBeforeDatabase() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.act(tenant,actor,position,
                request("TRANSFER",0,newBin,null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.act(tenant,actor,position,
                request("COUNT_OBSERVATION",0,null,null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.act(tenant,actor,position,
                new ReleasedLotLifecycleService.Request(key,"MOVE",newBin,0,10,
                    null,null,"bad"))).getStatusCode());
        verifyNoInteractions(jdbc,stock);
    }
    @Test void countOnlyRecordsImmutableObservationWithoutStockChanges() throws Exception {
        activeTenant();source(null);warehouseLock(false);
        mappedAction("COUNT_OBSERVATION",0,null,null,null,7);
        var result=service.act(tenant,actor,position,
            request("COUNT_OBSERVATION",0,null,7));
        assertEquals(7,result.observedQuantity());
        verify(jdbc).update(contains("INSERT INTO erp_local_lot_action"),
            any(UUID.class),eq(tenant),eq(key),eq("COUNT_OBSERVATION"),
            eq(position),eq(warehouse),isNull(),eq(oldBin),isNull(),isNull(),
            isNull(),eq(0),eq(10),isNull(),eq(7),eq(detail),eq(actor));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
        verifyNoInteractions(stock);
    }
    @Test void withinWarehouseMoveDoesNotPostStock() throws Exception {
        activeTenant();source(null);warehouseLock(false);
        when(jdbc.query(contains("FROM erp_local_stock_bin"),
            any(RowMapper.class),eq(tenant),eq(newBin))).thenReturn(List.of(warehouse));
        when(jdbc.update(contains("UPDATE erp_local_lot_position SET bin_id="),
            eq(newBin),eq(tenant),eq(position),eq(oldBin),eq(10))).thenReturn(1);
        mappedAction("MOVE",0,null,newBin,null,null);
        assertEquals("MOVE",service.act(tenant,actor,position,
            request("MOVE",0,newBin,null)).actionType());
        verify(jdbc).update(contains("UPDATE erp_local_lot_position SET bin_id="),
            eq(newBin),eq(tenant),eq(position),eq(oldBin),eq(10));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
        verifyNoInteractions(stock);
    }
    @Test void transferMovesSignedStockAndCreatesLinkedDestinationPosition() throws Exception {
        activeTenant();source(LocalDate.of(2027,1,1));warehouseLock(true);
        when(jdbc.query(contains("FROM erp_local_stock_bin"),
            any(RowMapper.class),eq(tenant),eq(newBin))).thenReturn(List.of(destinationWh));
        when(jdbc.queryForObject(contains("FROM erp_local_stock_movement"),
            eq(Long.class),eq(tenant),eq(warehouse),eq(sku))).thenReturn(20L);
        when(jdbc.update(contains("SET remaining_quantity=remaining_quantity-?"),
            eq(3),eq(tenant),eq(position),eq(10))).thenReturn(1);
        var posted=new WarehouseStockOperationService.Operation(operation.toString(),
            "TRANSFER",warehouse.toString(),destinationWh.toString(),sku.toString(),
            3,20,-3,17,detail,actor.toString(),"2026-11-01T00:00:00Z");
        when(stock.post(eq(tenant),eq(actor),
            any(WarehouseStockOperationService.Request.class))).thenReturn(posted);
        mappedAction("TRANSFER",3,destinationWh,newBin,operation,null);
        var result=service.act(tenant,actor,position,
            request("TRANSFER",3,newBin,null));
        assertEquals(operation.toString(),result.operationId());
        verify(stock).post(eq(tenant),eq(actor),argThat(x->
            x.type().equals("TRANSFER")&&x.quantity()==3
            &&x.expectedOnHand()==20&&x.warehouseId().equals(warehouse)
            &&x.destinationWarehouseId().equals(destinationWh)));
        verify(jdbc).update(contains("INSERT INTO erp_local_lot_position"),
            any(UUID.class),eq(tenant),any(UUID.class),eq(receipt),
            eq(destinationWh),eq(sku),eq(newBin),eq(3),eq(3),
            eq(LocalDate.of(2027,1,1)),eq(detail),eq(actor),eq(position));
    }
    @Test void expiryQuarantineNeedsVerifiedExpiredDate() throws Exception {
        activeTenant();source(LocalDate.of(2027,1,1));warehouseLock(false);
        when(jdbc.queryForObject(contains("FROM erp_local_stock_movement"),
            eq(Long.class),eq(tenant),eq(warehouse),eq(sku))).thenReturn(20L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.act(tenant,actor,position,
                request("EXPIRED_QUARANTINE",2,null,null))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void expiredLotOnlyDebitsExplicitSignedQuarantineMovement() throws Exception {
        activeTenant();source(LocalDate.of(2025,1,1));warehouseLock(false);
        when(jdbc.queryForObject(contains("FROM erp_local_stock_movement"),
            eq(Long.class),eq(tenant),eq(warehouse),eq(sku))).thenReturn(20L);
        when(jdbc.update(contains("SET remaining_quantity=remaining_quantity-?"),
            eq(2),eq(tenant),eq(position),eq(10))).thenReturn(1);
        mappedAction("EXPIRED_QUARANTINE",2,null,null,null,null);
        service.act(tenant,actor,position,
            request("EXPIRED_QUARANTINE",2,null,null));
        verify(jdbc).update(contains("'LOT_QUARANTINE'"),
            any(UUID.class),eq(tenant),eq(warehouse),eq(sku),any(UUID.class),
            eq(-2),eq(actor));
        verifyNoInteractions(stock);
    }
}
