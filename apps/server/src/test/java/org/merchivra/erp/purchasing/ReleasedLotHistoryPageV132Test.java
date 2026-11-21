package org.merchivra.erp.purchasing;

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

/** V132: never truncate immutable transfer/source history at the legacy 200. */
class ReleasedLotHistoryPageV132Test {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseStockOperationService stock=mock(WarehouseStockOperationService.class);
    private final ReleasedLotLifecycleService service=new ReleasedLotLifecycleService(jdbc,stock);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID otherTenant=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID position=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID otherPosition=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final OffsetDateTime timestamp=OffsetDateTime.parse("2026-11-09T12:00:00Z");

    private ReleasedLotLifecycleService.Action action(int n) {
        String id=new UUID(0,n).toString();
        String time=timestamp.minusSeconds(n).toInstant().toString();
        // Alternate source-side and destination-side transfer evidence.
        boolean destination=n%2==0;
        return new ReleasedLotLifecycleService.Action(id,"TRANSFER",
            destination?otherPosition.toString():position.toString(),
            otherTenant.toString(),position.toString(),
            tenant.toString(),position.toString(),
            destination?position.toString():otherPosition.toString(),UUID.randomUUID().toString(),
            1,1,1L,null,"Confirmed actual local transfer",tenant.toString(),time);
    }

    @SuppressWarnings("unchecked")
    private void pageFixtures() {
        when(jdbc.queryForList(contains("FROM erp_local_lot_position"),
            eq(UUID.class),eq(tenant),eq(position))).thenReturn(List.of(position));
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenAnswer(inv->{
                String sql=inv.getArgument(0);
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertTrue(sql.contains("source_position_id=? OR destination_position_id=?"));
                assertTrue(sql.contains("ORDER BY created_at DESC,id DESC LIMIT ?"));
                assertFalse(sql.contains("LIMIT 200"));
                assertEquals(tenant,args[0]);
                assertEquals(position,args[1]);
                assertEquals(position,args[2]);
                assertEquals(51,args[args.length-1]);
                int prior=0;
                if(args.length==6) {
                    UUID last=(UUID)args[4];
                    prior=(int)last.getLeastSignificantBits();
                    assertEquals(timestamp.minusSeconds(prior),
                        ((OffsetDateTime)args[3]).withOffsetSameInstant(ZoneOffset.UTC));
                    assertTrue(sql.contains("(created_at,id) < (?,?)"));
                } else assertEquals(4,args.length);
                var result=new ArrayList<ReleasedLotLifecycleService.Action>();
                for(int n=prior+1;n<=Math.min(225,prior+51);n++)
                    result.add(action(n));
                return result;
            });
    }

    @Test void sourceAndDestinationActionsBeyond200ReturnComplete225WithoutDuplicates() {
        pageFixtures();
        String cursor=null;
        var seen=new HashSet<String>();
        var sizes=new ArrayList<Integer>();
        for(int pageNumber=0;pageNumber<5;pageNumber++) {
            var page=service.historyPage(tenant,position,50,cursor);
            sizes.add(page.items().size());
            for(var item:page.items())assertTrue(seen.add(item.id()));
            assertEquals(pageNumber<4,page.hasMore());
            assertEquals(50,page.pageSize());
            if(pageNumber==4)assertEquals(new UUID(0,201).toString(),
                page.items().getFirst().id());
            cursor=page.nextCursor();
        }
        assertEquals(List.of(50,50,50,50,25),sizes);
        assertEquals(225,seen.size());
        assertNull(cursor);
        verify(jdbc,times(5)).query(anyString(),any(RowMapper.class),any(Object[].class));
        verifyNoInteractions(stock);
    }

    private static void bad(Runnable call) {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,call::run).getStatusCode());
    }

    @Test void wrongTenantPositionTimestampIdAndLimitsRejectBeforeAnyQuery() {
        String good=LocalKeysetPage.encode(tenant.toString(),position.toString(),
            timestamp.toInstant().toString(),new UUID(0,1).toString());
        bad(()->service.historyPage(otherTenant,position,50,good));
        bad(()->service.historyPage(tenant,otherPosition,50,good));
        bad(()->service.historyPage(tenant,position,0,null));
        bad(()->service.historyPage(tenant,position,101,null));
        bad(()->service.historyPage(tenant,position,50,"bad%cursor"));
        bad(()->service.historyPage(tenant,position,50,LocalKeysetPage.encode(
            tenant.toString(),position.toString(),"invalid-time",new UUID(0,1).toString())));
        bad(()->service.historyPage(tenant,position,50,LocalKeysetPage.encode(
            tenant.toString(),position.toString(),timestamp.toInstant().toString(),"not-a-uuid")));
        verifyNoInteractions(jdbc,stock);
    }

    @Test void missingOrCrossTenantPositionIsNotAQueryableHistory() {
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                ()->service.historyPage(tenant,position,50,null)).getStatusCode());
        verify(jdbc,never()).query(anyString(),any(RowMapper.class),any(Object[].class));
        verifyNoInteractions(stock);
    }
}
