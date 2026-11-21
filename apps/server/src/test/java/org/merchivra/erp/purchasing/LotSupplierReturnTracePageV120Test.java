package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
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

/** V120: full local return history, strict warehouse+tenant keysets. */
class LotSupplierReturnTracePageV120Test {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final LotSupplierReturnTraceService returns=new LotSupplierReturnTraceService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID warehouse=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID other=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final OffsetDateTime base=OffsetDateTime.parse("2026-11-09T09:00:00Z");

    private ResultSet row(int n) throws Exception {
        ResultSet rs=mock(ResultSet.class);
        when(rs.getObject("dispatch_reported_at",OffsetDateTime.class))
            .thenReturn(base.minusSeconds(n));
        when(rs.getObject("id",UUID.class)).thenReturn(new UUID(0,n));
        when(rs.getObject("disposition_id",UUID.class)).thenReturn(new UUID(1,n));
        when(rs.getObject("source_receipt_id",UUID.class)).thenReturn(new UUID(2,n));
        when(rs.getObject("supplier_id",UUID.class)).thenReturn(new UUID(3,n));
        when(rs.getObject("dispatched_by",UUID.class)).thenReturn(new UUID(4,n));
        when(rs.getInt("quantity")).thenReturn(10);
        when(rs.getString("carrier_reference")).thenReturn("LOCAL-REPORT-"+n);
        when(rs.getString("reason")).thenReturn("Artificial dispatched return");
        when(rs.getString("status")).thenReturn("DISPATCH_REPORTED");
        when(rs.getInt("received_quantity")).thenReturn(0);
        when(rs.wasNull()).thenReturn(true);
        return rs;
    }

    @Test void allTwoHundredTwentyFiveReturnedAcrossFivePagesWithoutLegacyCap() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenAnswer(inv->{
                String sql=inv.getArgument(0);
                RowMapper<?> mapper=inv.getArgument(1);
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertTrue(sql.contains("WHERE tenant_id=?"));
                assertTrue(sql.contains("WHERE tenant_id=? AND warehouse_id=?"));
                assertTrue(sql.contains("ORDER BY dispatch_reported_at DESC,id DESC LIMIT ?"));
                assertFalse(sql.contains("LIMIT 200"));
                assertEquals(tenant,args[0]);
                assertEquals(tenant,args[1]);
                assertEquals(warehouse,args[2]);
                assertEquals(51,args[args.length-1]);
                int start=0;
                if(args.length==6) {
                    assertTrue(sql.contains("(dispatch_reported_at,id)<(?,?)"));
                    start=(int)((UUID)args[4]).getLeastSignificantBits();
                    assertEquals(base.minusSeconds(start),args[3]);
                } else assertEquals(4,args.length);
                var fetched=new ArrayList<Object>();
                for(int n=start+1;n<=Math.min(225,start+51);n++)
                    fetched.add(mapper.mapRow(row(n),n));
                return fetched;
            });
        String cursor=null;
        var ids=new HashSet<String>();
        var sizes=new ArrayList<Integer>();
        for(int i=0;i<5;i++){
            var page=returns.listPage(tenant,warehouse,50,cursor);
            sizes.add(page.items().size());
            page.items().forEach(x->{
                assertTrue(ids.add(x.id()));
                assertEquals("DISPATCH_REPORTED",x.status());
                assertNull(x.receivedQuantity());
            });
            if(i==4)assertEquals(new UUID(0,201).toString(),
                page.items().getFirst().id());
            assertEquals(i<4,page.hasMore());
            cursor=page.nextCursor();
        }
        assertEquals(List.of(50,50,50,50,25),sizes);
        assertEquals(225,ids.size());
        assertNull(cursor);
        verify(jdbc,times(5)).query(anyString(),any(RowMapper.class),any(Object[].class));
    }

    private static void bad(Runnable action) {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,action::run).getStatusCode());
    }

    @Test void rejectForeignTenantWarehouseBadDateVersionAndLimitBeforeDatabaseReads(){
        String cursor=LocalKeysetPage.encode("return-v120",tenant.toString(),
            warehouse.toString(),base.minusSeconds(50).toString(),
            new UUID(0,50).toString());
        bad(()->returns.listPage(other,warehouse,50,cursor));
        bad(()->returns.listPage(tenant,other,50,cursor));
        bad(()->returns.listPage(tenant,warehouse,0,null));
        bad(()->returns.listPage(tenant,warehouse,101,null));
        bad(()->returns.listPage(tenant,warehouse,50,"bad%cursor"));
        bad(()->returns.listPage(tenant,warehouse,50,LocalKeysetPage.encode(
            "return-v119",tenant.toString(),warehouse.toString(),
            base.toString(),new UUID(0,50).toString())));
        bad(()->returns.listPage(tenant,warehouse,50,LocalKeysetPage.encode(
            "return-v120",tenant.toString(),warehouse.toString(),
            "not-time",new UUID(0,50).toString())));
        bad(()->returns.listPage(tenant,warehouse,50,LocalKeysetPage.encode(
            "return-v120",tenant.toString(),warehouse.toString(),
            base.toString(),"not-uuid")));
        verifyNoInteractions(jdbc);
    }
}
