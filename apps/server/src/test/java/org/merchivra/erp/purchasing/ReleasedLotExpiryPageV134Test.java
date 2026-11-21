package org.merchivra.erp.purchasing;

import java.time.LocalDate;
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

/** V134: 300+ expiring and expired source positions must all remain visible. */
class ReleasedLotExpiryPageV134Test {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseStockOperationService stock=mock(WarehouseStockOperationService.class);
    private final ReleasedLotLifecycleService service=new ReleasedLotLifecycleService(jdbc,stock);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID anotherTenant=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID warehouse=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID otherWarehouse=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID sku=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private final LocalDate expiry=LocalDate.parse("2026-11-10");

    private ReleasedLotLifecycleService.ExpiryAlert alert(int n) {
        return new ReleasedLotLifecycleService.ExpiryAlert(new UUID(0,n).toString(),
            "LOT-V134","SHELF-V134",sku.toString(),1,expiry.toString(),"EXPIRED");
    }

    @SuppressWarnings("unchecked")
    private void fixture(){
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenAnswer(inv->{
                String query=inv.getArgument(0);
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertTrue(query.contains("p.tenant_id=? AND p.warehouse_id=?"));
                // V136 guards the actual bounded index-first query plan;
                // joins must happen outside a 51-row MATERIALIZED CTE.
                assertTrue(query.contains("WITH selected AS MATERIALIZED"));
                assertTrue(query.contains("FROM selected p"));
                assertTrue(query.indexOf("LIMIT ?") < query.indexOf("FROM selected p"));
                assertTrue(query.contains("p.remaining_quantity>0"));
                assertTrue(query.contains("p.expiry_date<=CURRENT_DATE+30"));
                assertTrue(query.contains("ORDER BY p.expiry_date ASC,p.id ASC LIMIT ?"));
                assertFalse(query.contains("LIMIT 300"));
                assertEquals(tenant,args[0]);
                assertEquals(warehouse,args[1]);
                assertEquals(51,args[args.length-1]);
                int after=0;
                if(args.length==5){
                    assertTrue(query.contains("(p.expiry_date,p.id) > (?,?)"));
                    assertEquals(expiry,args[2]);
                    after=(int)((UUID)args[3]).getLeastSignificantBits();
                } else assertEquals(3,args.length);
                var rows=new ArrayList<ReleasedLotLifecycleService.ExpiryAlert>();
                for(int n=after+1;n<=Math.min(325,after+51);n++)rows.add(alert(n));
                return rows;
            });
    }
    @Test void all325ExpiringPositionsAcrossSevenPagesWithoutDuplicateOrEarlyCutoff() {
        fixture();
        String cursor=null;
        var ids=new HashSet<String>();
        var sizes=new ArrayList<Integer>();
        for(int i=0;i<7;i++){
            var page=service.expiryPage(tenant,warehouse,50,cursor);
            sizes.add(page.items().size());
            assertEquals(i<6,page.hasMore());
            assertEquals(50,page.pageSize());
            for(var item:page.items())assertTrue(ids.add(item.positionId()));
            if(i==6)assertEquals(new UUID(0,301).toString(),page.items().getFirst().positionId());
            cursor=page.nextCursor();
        }
        assertEquals(List.of(50,50,50,50,50,50,25),sizes);
        assertEquals(325,ids.size());
        assertNull(cursor);
        verify(jdbc,times(7)).query(anyString(),any(RowMapper.class),any(Object[].class));
        verifyNoInteractions(stock);
    }
    private void bad(Runnable call) {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,call::run).getStatusCode());
    }
    @Test void malformedCrossTenantWarehouseOrDomainCursorFailsBeforeAnySql(){
        String key=new UUID(0,50).toString();
        String good=LocalKeysetPage.encode(tenant.toString(),warehouse.toString(),
            "EXPIRY",expiry.toString(),key);
        bad(()->service.expiryPage(anotherTenant,warehouse,50,good));
        bad(()->service.expiryPage(tenant,otherWarehouse,50,good));
        bad(()->service.expiryPage(tenant,warehouse,50,LocalKeysetPage.encode(
            tenant.toString(),warehouse.toString(),"COUNTS",expiry.toString(),key)));
        bad(()->service.expiryPage(tenant,warehouse,0,null));
        bad(()->service.expiryPage(tenant,warehouse,101,null));
        bad(()->service.expiryPage(tenant,warehouse,50,"invalid%"));
        bad(()->service.expiryPage(tenant,warehouse,50,LocalKeysetPage.encode(
            tenant.toString(),warehouse.toString(),"EXPIRY","not-a-date",key)));
        bad(()->service.expiryPage(tenant,warehouse,50,LocalKeysetPage.encode(
            tenant.toString(),warehouse.toString(),"EXPIRY",expiry.toString(),"bad-uuid")));
        verifyNoInteractions(jdbc,stock);
    }
}
