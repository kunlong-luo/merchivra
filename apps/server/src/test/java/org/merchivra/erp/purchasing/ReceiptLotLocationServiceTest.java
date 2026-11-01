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

class ReceiptLotLocationServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ReceiptLotLocationService service=new ReceiptLotLocationService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID warehouse=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID receipt=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID sku=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID piece=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final UUID from=UUID.fromString("77777777-7777-4777-8777-777777777777");
    private final UUID to=UUID.fromString("88888888-8888-4888-8888-888888888888");
    private final UUID requestKey=UUID.fromString("99999999-9999-4999-8999-999999999999");

    private ReceiptLotLocationService.Piece root(){
        return new ReceiptLotLocationService.Piece(piece.toString(),receipt.toString(),
            warehouse.toString(),sku.toString(),from.toString(),"ROOT",10);
    }
    private void activeTenant(){
        when(jdbc.queryForList(contains("FROM tenant"),
            eq(UUID.class),eq(tenant))).thenReturn(List.of(tenant));
    }
    private void pending(){
        when(jdbc.queryForList(contains("FROM erp_receipt_quality_batch"),
            eq(UUID.class),eq(tenant),eq(receipt))).thenReturn(List.of(receipt));
    }
    private void storedPiece(){
        when(jdbc.query(contains("FROM erp_receipt_lot_piece"),
            any(RowMapper.class),eq(tenant),eq(piece))).thenReturn(List.of(root()));
    }
    private void activeBin(){
        when(jdbc.queryForList(contains("FROM erp_local_stock_bin"),
            eq(UUID.class),eq(tenant),eq(warehouse),eq(to))).thenReturn(List.of(to));
    }
    private ReceiptLotLocationService.Split split(int qty) {
        return new ReceiptLotLocationService.Split(requestKey,piece,qty,
            "ROOT-CHILD",to,"Physical lots counted separately");
    }
    private ReceiptLotLocationService.Move move(UUID expected){
        return new ReceiptLotLocationService.Move(requestKey,piece,expected,to,
            "Move physical cartons to QC shelf");
    }

    @Test void invalidSplitDoesNotReadAnyDatabase(){
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.split(tenant,actor,receipt,split(0))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.split(tenant,actor,receipt,
                new ReceiptLotLocationService.Split(requestKey,piece,2,
                    "../BAD",to,"Bad physical label"))).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void splitCannotConsumeEntireReceivingPiece(){
        activeTenant();pending();storedPiece();
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.split(tenant,actor,receipt,split(10))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void splitCannotTargetUnknownOrWrongWarehouseBin(){
        activeTenant();pending();storedPiece();
        when(jdbc.queryForList(contains("FROM erp_local_stock_bin"),
            eq(UUID.class),eq(tenant),eq(warehouse),eq(to))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.split(tenant,actor,receipt,split(3))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void splitConservesSourceAndNewPieceWithoutAnyStockLedgerPosting(){
        activeTenant();pending();storedPiece();activeBin();
        when(jdbc.update(contains("UPDATE erp_receipt_lot_piece SET quantity=quantity-?"),
            eq(3),eq(tenant),eq(piece),eq(10))).thenReturn(1);
        service.split(tenant,actor,receipt,split(3));
        verify(jdbc).update(contains("INSERT INTO erp_receipt_lot_piece"),
            any(UUID.class),eq(tenant),eq(receipt),eq(warehouse),eq(sku),
            eq(to),eq("ROOT-CHILD"),eq(3),eq(actor));
        verify(jdbc).update(contains("INSERT INTO erp_receipt_lot_piece_event"),
            any(UUID.class),eq(tenant),eq(receipt),eq(piece),any(UUID.class),
            eq(requestKey),eq(actor),eq("SPLIT"),eq(3),eq(from),eq(to),
            eq("Physical lots counted separately"));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
    }
    @Test void cannotMoveWithoutOriginalLocationSnapshot(){
        activeTenant();pending();storedPiece();
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.move(tenant,actor,receipt,move(to))).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void newQcLocationMustBelongToCurrentTenantWarehouse(){
        activeTenant();
        when(jdbc.queryForList(contains("FROM erp_local_warehouse"),
            eq(UUID.class),eq(tenant),eq(warehouse))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.createBin(tenant,actor,warehouse,
                new ReceiptLotLocationService.NewBin("QC-RACK-1","QC_HOLD")))
            .getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void reservedSystemQcHoldLocationCannotBeDuplicated(){
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.createBin(tenant,actor,warehouse,
                new ReceiptLotLocationService.NewBin("QC-HOLD","QC_HOLD")))
            .getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void theReadOnlyPressureQuerySeparatesReleasedAndHeldGoods(){
        service.pressure(tenant,warehouse);
        verify(jdbc).query(contains("pending_units"),
            any(RowMapper.class),eq(tenant),eq(warehouse));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
