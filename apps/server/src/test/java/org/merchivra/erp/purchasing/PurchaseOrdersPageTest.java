package org.merchivra.erp.purchasing;

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

/** V110: fail closed for forged cross-scope cursors and never truncate at 100. */
class PurchaseOrdersPageTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PurchasingService purchasing=
        new PurchasingService(jdbc,mock(WarehouseInventoryService.class));
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID foreign=UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static PurchasingService.Order order(long n) {
        return new PurchasingService.Order(new UUID(0,n).toString(),"PO-"+n,
            "33333333-3333-4333-8333-333333333333","DRAFT","USD",
            "44444444-4444-4444-8444-444444444444",null,null,
            "2026-11-08T09:00:00Z");
    }
    private static void bad(Runnable action) {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,action::run).getStatusCode());
    }

    @Test void oneHundredTwentyFiveOrdersAreReachableThroughThreeStablePages() {
        var all=new ArrayList<PurchasingService.Order>();
        for(long i=125;i>0;i--)all.add(order(i));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers
            .<RowMapper<PurchasingService.Order>>any(),any(Object[].class)))
            .thenAnswer(inv -> {
                String query=inv.getArgument(0);
                assertTrue(query.contains("WHERE tenant_id=?"));
                assertTrue(query.contains("(updated_at,id) < (?,?)") ||
                    !query.contains("AND (updated_at,id)"));
                assertTrue(query.contains("ORDER BY updated_at DESC,id DESC LIMIT ?"));
                // Mockito expands varargs in getArguments(); raw retains the JDBC Object[].
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertEquals(tenant,args[0]);
                assertEquals(51,args[args.length-1]);
                int start=0;
                if(args.length==4) {
                    assertEquals(OffsetDateTime.parse("2026-11-08T09:00:00Z"),args[1]);
                    UUID before=(UUID)args[2];
                    start=(int)(126-before.getLeastSignificantBits());
                }
                return all.subList(start,Math.min(start+51,all.size()));
            });
        var a=purchasing.ordersPage(tenant,50,"ALL","",null);
        var b=purchasing.ordersPage(tenant,50,"ALL","",a.nextCursor());
        var c=purchasing.ordersPage(tenant,50,"ALL","",b.nextCursor());
        assertEquals(List.of(50,50,25),
            List.of(a.items().size(),b.items().size(),c.items().size()));
        assertTrue(a.hasMore());
        assertTrue(b.hasMore());
        assertFalse(c.hasMore());
        assertNull(c.nextCursor());
        var ids=new HashSet<String>();
        for(var page:List.of(a,b,c))for(var row:page.items())
            assertTrue(ids.add(row.id()),"No duplicated order between pages");
        assertEquals(125,ids.size());
        assertEquals(order(1).id(),c.items().getLast().id());
        verify(jdbc,times(3)).query(anyString(),any(RowMapper.class),any(Object[].class));
    }

    @Test void cursorRejectsForeignTenantFilterAndMalformedTimeWithoutQueries() {
        var cursor=LocalKeysetPage.encode("po-v1",tenant.toString(),
            "APPROVED","q:PO-42","2026-11-08T09:00:00Z",
            new UUID(0,10).toString());
        bad(()->purchasing.ordersPage(foreign,50,"APPROVED","PO-42",cursor));
        bad(()->purchasing.ordersPage(tenant,50,"DRAFT","PO-42",cursor));
        bad(()->purchasing.ordersPage(tenant,50,"APPROVED","PO-43",cursor));
        bad(()->purchasing.ordersPage(tenant,50,"APPROVED","PO-42",
            LocalKeysetPage.encode("po-v1",tenant.toString(),"APPROVED","q:PO-42",
                "not-an-instant",new UUID(0,10).toString())));
        bad(()->purchasing.ordersPage(tenant,50,"APPROVED","PO-42","invalid*"));
        bad(()->purchasing.ordersPage(tenant,101,"ALL","",null));
        bad(()->purchasing.ordersPage(tenant,0,"ALL","",null));
        bad(()->purchasing.ordersPage(tenant,50,"NOT_A_STATE","",null));
        bad(()->purchasing.ordersPage(tenant,50,"ALL","PO%42",null));
        bad(()->purchasing.ordersPage(tenant,50,"ALL","X".repeat(81),null));
        verifyNoInteractions(jdbc);
    }

    @Test void filteredSearchIsParameterizedAndTenantBound() {
        purchasing.ordersPage(tenant,50,"APPROVED"," po-42 ",null);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        var binds=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(),any(RowMapper.class),binds.capture());
        assertTrue(sql.getValue().contains("WHERE tenant_id=?"));
        assertTrue(sql.getValue().contains("AND status=?"));
        assertTrue(sql.getValue().contains("POSITION(? IN UPPER(order_number))>0"));
        assertFalse(sql.getValue().contains("PO-42"));
        assertArrayEquals(new Object[]{tenant,"APPROVED","PO-42",51},binds.getValue());
    }
}
