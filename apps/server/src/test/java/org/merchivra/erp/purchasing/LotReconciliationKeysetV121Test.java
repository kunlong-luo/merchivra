package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
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

/** V121 complete V50 source evidence beyond the previous first 200 records. */
class LotReconciliationKeysetV121Test {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseStockOperationService stock=mock(WarehouseStockOperationService.class);
    private final LotReconciliationService service=new LotReconciliationService(jdbc,stock);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID warehouse=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID other=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final OffsetDateTime timestamp=OffsetDateTime.parse("2026-11-09T12:00:00Z");

    private ResultSet row(int n) throws Exception {
        ResultSet rs=mock(ResultSet.class);
        UUID id=new UUID(0,n);
        when(rs.getObject("created_at",OffsetDateTime.class))
            .thenReturn(timestamp.minusSeconds(n));
        when(rs.getObject(anyString(),eq(UUID.class))).thenAnswer(inv->id);
        when(rs.getInt(anyString())).thenReturn(1);
        when(rs.getLong(anyString())).thenReturn(10L);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getString(anyString())).thenAnswer(inv->{
            String field=inv.getArgument(0);
            return switch(field){
                case "id"->id.toString();
                case "status"->"PENDING_REVIEW";
                case "method"->"SUPPLIER_RETURN";
                default->"Manual verification in warehouse";
            };
        });
        return rs;
    }

    @SuppressWarnings("unchecked")
    private void fakePages(String kind) {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenAnswer(inv->{
                String sql=inv.getArgument(0);
                RowMapper<?> mapper=inv.getArgument(1);
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertTrue(sql.contains("ORDER BY "));
                assertTrue(sql.contains("LIMIT ?"));
                assertFalse(sql.contains("LIMIT 200"));
                assertEquals(tenant,args[0]);
                assertEquals(warehouse,args[1]);
                assertEquals(51,args[args.length-1]);
                switch(kind){
                    case "COUNTS"->assertTrue(sql.contains("erp_local_lot_count_review"));
                    case "TRANSFERS"->assertTrue(sql.contains("a.destination_warehouse_id=?"));
                    case "DISPOSITIONS"->assertTrue(sql.contains("erp_local_lot_disposition"));
                    case "UNPLANNED"->{
                        assertTrue(sql.contains("a.action_type='EXPIRED_QUARANTINE'"));
                        assertTrue(sql.contains("d.id IS NULL"));
                    }
                    default->fail("unexpected page kind");
                }
                int prior=0;
                if(args.length==5){
                    prior=(int)((UUID)args[3]).getLeastSignificantBits();
                    assertEquals(timestamp.minusSeconds(prior),args[2]);
                    assertTrue(sql.contains("< (?,?)"));
                } else assertEquals(3,args.length);
                var result=new ArrayList<Object>();
                for(int n=prior+1;n<=Math.min(225,prior+51);n++)
                    result.add(mapper.mapRow(row(n),n));
                return result;
            });
    }
    private static void bad(Runnable runnable) {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,runnable::run).getStatusCode());
    }

    @Test void countsAreAll225AcrossFivePages() {
        verifyAll("COUNTS");
    }
    @Test void transfersAreAll225AcrossFivePages() {
        verifyAll("TRANSFERS");
    }
    @Test void dispositionsAreAll225AcrossFivePages() {
        verifyAll("DISPOSITIONS");
    }
    @Test void unplannedQuarantinesAreAll225AcrossFivePages() {
        verifyAll("UNPLANNED");
    }
    private void verifyAll(String kind) {
        fakePages(kind);
        String cursor=null;var seen=new HashSet<String>();
        var sizes=new ArrayList<Integer>();
        for(int p=0;p<5;p++){
            LocalKeysetPage.Page<String> unplanned=null;
            LocalKeysetPage.Page<?> page=switch(kind){
                case "COUNTS"->service.countsPage(tenant,warehouse,50,cursor);
                case "TRANSFERS"->service.transfersPage(tenant,warehouse,50,cursor);
                case "DISPOSITIONS"->service.dispositionsPage(tenant,warehouse,50,cursor);
                default->service.unplannedQuarantinesPage(tenant,warehouse,50,cursor);
            };
            sizes.add(page.items().size());
            for(Object o:page.items()){
                String rowId=switch(kind){
                    case "COUNTS"->((LotReconciliationService.CountCase)o).id();
                    case "TRANSFERS"->((LotReconciliationService.Transfer)o).actionId();
                    case "DISPOSITIONS"->((LotReconciliationService.Disposition)o).id();
                    default->(String)o;
                };
                assertTrue(seen.add(rowId));
            }
            assertEquals(p<4,page.hasMore());
            if(p==4){
                Object first=page.items().getFirst();
                String firstId=kind.equals("COUNTS")?
                    ((LotReconciliationService.CountCase)first).id():
                    kind.equals("TRANSFERS")?
                    ((LotReconciliationService.Transfer)first).actionId():
                    kind.equals("DISPOSITIONS")?
                    ((LotReconciliationService.Disposition)first).id():(String)first;
                assertEquals(new UUID(0,201).toString(),firstId);
            }
            cursor=page.nextCursor();
        }
        assertEquals(List.of(50,50,50,50,25),sizes);
        assertEquals(225,seen.size());
        assertNull(cursor);
        verify(jdbc,times(5)).query(anyString(),any(RowMapper.class),any(Object[].class));
        verifyNoInteractions(stock);
    }
    @Test void scopeDomainTimestampAndLimitsRejectBeforeSQL() {
        String time=timestamp.minusSeconds(50).toInstant().toString();
        String key=new UUID(0,50).toString();
        String countCursor=LocalKeysetPage.encode(tenant.toString(),
            warehouse.toString(),"COUNTS",time,key);
        bad(()->service.countsPage(other,warehouse,50,countCursor));
        bad(()->service.countsPage(tenant,other,50,countCursor));
        bad(()->service.transfersPage(tenant,warehouse,50,countCursor));
        bad(()->service.dispositionsPage(tenant,warehouse,50,countCursor));
        bad(()->service.unplannedQuarantinesPage(tenant,warehouse,50,countCursor));
        bad(()->service.countsPage(tenant,warehouse,0,null));
        bad(()->service.countsPage(tenant,warehouse,101,null));
        bad(()->service.countsPage(tenant,warehouse,50,"not-valid%"));
        bad(()->service.countsPage(tenant,warehouse,50,LocalKeysetPage.encode(
            tenant.toString(),warehouse.toString(),"COUNTS","not-time",key)));
        bad(()->service.countsPage(tenant,warehouse,50,LocalKeysetPage.encode(
            tenant.toString(),warehouse.toString(),"COUNTS",time,"not-uuid")));
        verifyNoInteractions(jdbc,stock);
    }
}
