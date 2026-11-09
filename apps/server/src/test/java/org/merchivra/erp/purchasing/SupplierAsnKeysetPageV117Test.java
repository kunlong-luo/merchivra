package org.merchivra.erp.purchasing;

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

/** V117 complete ASN + event audit pages beyond the former LIMIT 200. */
class SupplierAsnKeysetPageV117Test {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SupplierAsnService service=new SupplierAsnService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID order=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID otherOrder=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID asnId=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final OffsetDateTime start=OffsetDateTime.of(
        2026,11,9,0,0,0,0,ZoneOffset.UTC);

    private SupplierAsnService.Asn asn(int index) {
        return new SupplierAsnService.Asn(new UUID(0,index).toString(),
            order.toString(),new UUID(1,index).toString(),asnId.toString(),
            10,0,"2026-11-15",null,"DRAFT",null,
            start.minusSeconds(index).toInstant().toString());
    }
    private SupplierAsnService.Event event(int index) {
        return new SupplierAsnService.Event(new UUID(0,index).toString(),
            "ETA_UPDATED",tenant.toString(),null,"2026-11-15",
            "2026-11-16","Manual review",
            start.minusSeconds(index).toInstant().toString());
    }
    private static void bad(Runnable action) {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,action::run).getStatusCode());
    }

    @Test void twoHundredAndTwentyFiveNoticesProduceFiveStableScopedPages() {
        var all=new ArrayList<SupplierAsnService.Asn>();
        for(int i=1;i<=225;i++)all.add(asn(i));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers
            .<RowMapper<SupplierAsnService.Asn>>any(),any(Object[].class)))
            .thenAnswer(inv->{
                String sql=inv.getArgument(0);
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertTrue(sql.contains("a.purchase_order_id=?"));
                assertTrue(sql.contains("ORDER BY a.created_at DESC,a.id DESC LIMIT ?"));
                assertEquals(tenant,args[0]);
                assertEquals(order,args[1]);
                assertEquals(51,args[args.length-1]);
                int previous=0;
                if(args.length==5){
                    assertTrue(sql.contains("(a.created_at,a.id)<(?,?)"));
                    previous=(int)((UUID)args[3]).getLeastSignificantBits();
                    assertEquals(start.minusSeconds(previous),args[2]);
                }
                return all.subList(previous,Math.min(previous+51,all.size()));
            });
        String cursor=null;
        var seen=new HashSet<String>();
        var sizes=new ArrayList<Integer>();
        for(int p=0;p<5;p++){
            var page=service.listPage(tenant,order,50,cursor);
            sizes.add(page.items().size());
            page.items().forEach(x->assertTrue(seen.add(x.id())));
            if(p==4)assertEquals(asn(201),page.items().getFirst());
            cursor=page.nextCursor();
            assertEquals(p<4,page.hasMore());
        }
        assertEquals(List.of(50,50,50,50,25),sizes);
        assertEquals(225,seen.size());
        assertNull(cursor);
        verify(jdbc,times(5)).query(anyString(),any(RowMapper.class),any(Object[].class));
    }

    @Test void foreignScopeAndMalformedCursorsRejectBeforeAnyQuery() {
        String valid=LocalKeysetPage.encode("asn-v117",tenant.toString(),
            order.toString(),start.minusSeconds(50).toString(),
            new UUID(0,50).toString());
        bad(()->service.listPage(tenant,otherOrder,50,valid));
        bad(()->service.listPage(otherOrder,order,50,valid));
        bad(()->service.listPage(tenant,order,50,"bad cursor"));
        bad(()->service.listPage(tenant,order,101,null));
        bad(()->service.listPage(tenant,order,50,LocalKeysetPage.encode(
            "asn-v117",tenant.toString(),order.toString(),"invalid-date",
            new UUID(0,50).toString())));
        bad(()->service.listPage(tenant,order,50,LocalKeysetPage.encode(
            "asn-v117",tenant.toString(),order.toString(),
            start.minusSeconds(50).toString(),"not-a-uuid")));
        verifyNoInteractions(jdbc);
    }

    @Test void immutableEventHistoryPaginatesAndBindsParentOrderAndAsn() {
        when(jdbc.queryForList(contains("erp_purchase_asn"),eq(UUID.class),
            eq(tenant),eq(order),eq(asnId))).thenReturn(List.of(asnId));
        var all=new ArrayList<SupplierAsnService.Event>();
        for(int i=1;i<=225;i++)all.add(event(i));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers
            .<RowMapper<SupplierAsnService.Event>>any(),any(Object[].class)))
            .thenAnswer(inv->{
                String sql=inv.getArgument(0);
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertTrue(sql.contains("e.tenant_id=? AND e.asn_id=?"));
                assertEquals(tenant,args[0]);
                assertEquals(asnId,args[1]);
                assertEquals(51,args[args.length-1]);
                int previous=args.length==5?
                    (int)((UUID)args[3]).getLeastSignificantBits():0;
                if(args.length==5)assertEquals(start.minusSeconds(previous),args[2]);
                return all.subList(previous,Math.min(previous+51,all.size()));
            });
        String cursor=null;
        var sizes=new ArrayList<Integer>();
        for(int p=0;p<5;p++){
            var page=service.eventsPage(tenant,order,asnId,50,cursor);
            sizes.add(page.items().size());
            if(p==4)assertEquals(event(201),page.items().getFirst());
            cursor=page.nextCursor();
            assertEquals(p<4,page.hasMore());
        }
        assertEquals(List.of(50,50,50,50,25),sizes);
        assertNull(cursor);
        String scoped=LocalKeysetPage.encode("asn-events-v117",tenant.toString(),
            order.toString(),asnId.toString(),
            start.minusSeconds(50).toString(),new UUID(0,50).toString());
        bad(()->service.eventsPage(tenant,otherOrder,asnId,50,scoped));
        bad(()->service.eventsPage(tenant,order,UUID.randomUUID(),50,scoped));
        bad(()->service.eventsPage(UUID.randomUUID(),order,asnId,50,scoped));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.eventsPage(tenant,otherOrder,asnId,50,null)).getStatusCode());
    }
}
