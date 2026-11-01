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

class LotSupplierReturnTraceServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final LotSupplierReturnTraceService service=new LotSupplierReturnTraceService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID other=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID plan=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID receipt=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final UUID supplier=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private final UUID trace=UUID.fromString("77777777-7777-4777-8777-777777777777");
    private final UUID key=UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final String REASON="Warehouse return cartons were manually dispatched";
    private void active(){
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
    }
    private void source(String method,String status) throws Exception {
        when(jdbc.query(contains("JOIN erp_purchase_order po"),
            any(RowMapper.class),eq(tenant),eq(plan))).thenAnswer(inv->{
            RowMapper<?> mapper=inv.getArgument(1);
            ResultSet rs=mock(ResultSet.class);
            when(rs.getObject("id",UUID.class)).thenReturn(plan);
            when(rs.getObject("receipt_id",UUID.class)).thenReturn(receipt);
            when(rs.getObject("supplier_id",UUID.class)).thenReturn(supplier);
            when(rs.getInt("quantity")).thenReturn(8);
            when(rs.getString("status")).thenReturn(status);
            when(rs.getString("method")).thenReturn(method);
            return List.of(mapper.mapRow(rs,0));
        });
    }
    private LotSupplierReturnTraceService.Trace trace(String state,int qty,UUID signer){
        return new LotSupplierReturnTraceService.Trace(trace.toString(),plan.toString(),
            receipt.toString(),supplier.toString(),8,"TRK-0001",REASON,state,
            actor.toString(),qty<0?null:qty,qty<0?null:"VENDOR-RCPT-001",
            signer==null?null:signer.toString());
    }
    @Test void cannotClaimDisposalIsRealSupplierReturn() throws Exception {
        active();source("DISPOSAL","EXECUTED_REPORTED");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.reportDispatch(tenant,actor,
                new LotSupplierReturnTraceService.Dispatch(key,plan,"TRK-0001",REASON)))
            .getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void sourceSupplierComesFromOriginalReceiptPurchaseOrder() throws Exception {
        active();source("SUPPLIER_RETURN","EXECUTED_REPORTED");
        var row=trace("DISPATCH_REPORTED",-1,null);
        when(jdbc.query(contains("FROM erp_lot_supplier_return_trace WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),any(UUID.class))).thenReturn(List.of(row));
        var result=service.reportDispatch(tenant,actor,
            new LotSupplierReturnTraceService.Dispatch(key,plan,"TRK-0001",REASON));
        assertEquals(supplier.toString(),result.supplierId());
        verify(jdbc).update(contains("INSERT INTO erp_lot_supplier_return_trace"),
            any(UUID.class),eq(tenant),eq(key),eq(plan),eq(receipt),eq(supplier),
            eq(8),eq("TRK-0001"),eq(REASON),eq(actor));
    }
    @Test void dispatcherCannotConfirmOwnSupplierReceipt() {
        active();
        when(jdbc.query(contains("FROM erp_lot_supplier_return_trace WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(trace)))
            .thenReturn(List.of(trace("DISPATCH_REPORTED",-1,null)));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.reportReceipt(tenant,actor,trace,
                new LotSupplierReturnTraceService.Receipt(8,
                    "VENDOR-RCPT-001","Supplier manually reported full receipt")))
            .getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void recordedMismatchNeverMovesLocalWarehouseStock() {
        active();
        when(jdbc.query(contains("FROM erp_lot_supplier_return_trace WHERE tenant_id=?"),
            any(RowMapper.class),eq(tenant),eq(trace)))
            .thenReturn(List.of(trace("DISPATCH_REPORTED",-1,null)),
                List.of(trace("RECEIPT_MISMATCH",5,other)));
        var result=service.reportReceipt(tenant,other,trace,
            new LotSupplierReturnTraceService.Receipt(5,
                "VENDOR-RCPT-001","Supplier reported only five cartons"));
        assertEquals("RECEIPT_MISMATCH",result.status());
        verify(jdbc).update(contains("UPDATE erp_lot_supplier_return_trace"),
            eq(5),eq("VENDOR-RCPT-001"),eq("Supplier reported only five cartons"),
            eq(other),eq("RECEIPT_MISMATCH"),eq(tenant),eq(trace));
        verify(jdbc,never()).update(contains("erp_local_stock_movement"),
            any(Object[].class));
    }
}
