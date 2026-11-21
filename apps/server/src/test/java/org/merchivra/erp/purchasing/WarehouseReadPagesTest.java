package org.merchivra.erp.purchasing;

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

/** V113: more than legacy 500, cursor isolation and observed-zero discipline. */
class WarehouseReadPagesTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseInventoryService service=new WarehouseInventoryService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID foreign=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID warehouse=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID other=UUID.fromString("44444444-4444-4444-8444-444444444444");

    private WarehouseInventoryService.Stock stock(int n) {
        return new WarehouseInventoryService.Stock(warehouse.toString(),
            new UUID(0,n).toString(),String.format("V113-SKU-%04d",n),0,n);
    }
    private WarehouseInventoryService.PendingReceipt pending(int n) {
        return new WarehouseInventoryService.PendingReceipt(new UUID(1,n).toString(),
            String.format("PO-V113-%04d",n),new UUID(2,n).toString(),
            new UUID(0,n).toString(),"V113-SKU", "APPROVED",10,0,10,"USD","2.50");
    }
    private static void bad(Runnable action) {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,action::run).getStatusCode());
    }
    private void readable() {
        when(jdbc.queryForObject(contains("erp_local_warehouse"),
            eq(Integer.class),eq(tenant),eq(warehouse))).thenReturn(1);
    }

    @Test void fiveHundredTwentyFiveStockSkuKeysReachEleventhPage() {
        readable();
        var all=new ArrayList<WarehouseInventoryService.Stock>();
        for(int i=1;i<=525;i++)all.add(stock(i));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers
            .<RowMapper<WarehouseInventoryService.Stock>>any(),any(Object[].class)))
            .thenAnswer(inv->{
                String sql=inv.getArgument(0);
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertTrue(sql.contains("page_keys AS MATERIALIZED"));
                assertTrue(sql.contains("WHERE tenant_id=? AND warehouse_id=?"));
                assertTrue(sql.contains("ORDER BY s.internal_sku ASC,k.product_sku_id ASC LIMIT ?"));
                assertEquals(tenant,args[0]);assertEquals(warehouse,args[1]);
                assertEquals(tenant,args[2]);assertEquals(warehouse,args[3]);
                assertEquals(51,args[args.length-1]);
                int begin=0;
                if(args.length==7) {
                    assertTrue(sql.contains("(s.internal_sku,k.product_sku_id)>(?,?)"));
                    begin=Integer.parseInt(((String)args[4]).substring(9));
                    assertEquals(stock(begin).productSkuId(),((UUID)args[5]).toString());
                }
                return all.subList(begin,Math.min(begin+51,all.size()));
            });
        String cursor=null;
        var seen=new HashSet<String>();
        var sizes=new ArrayList<Integer>();
        for(int i=0;i<11;i++){
            var page=service.stockPage(tenant,warehouse,50,"",cursor);
            sizes.add(page.items().size());
            for(var row:page.items())assertTrue(seen.add(row.productSkuId()));
            if(i<10)assertTrue(page.hasMore());
            cursor=page.nextCursor();
        }
        assertEquals(List.of(50,50,50,50,50,50,50,50,50,50,25),sizes);
        assertEquals(525,seen.size());
        assertNull(cursor);
        verify(jdbc,times(11)).query(anyString(),any(RowMapper.class),any(Object[].class));
    }

    @Test void fiveHundredTwentyFiveUnreceivedLinesReachEleventhPage() {
        var all=new ArrayList<WarehouseInventoryService.PendingReceipt>();
        for(int i=1;i<=525;i++)all.add(pending(i));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers
            .<RowMapper<WarehouseInventoryService.PendingReceipt>>any(),any(Object[].class)))
            .thenAnswer(inv->{
                String sql=inv.getArgument(0);
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertTrue(sql.contains("o.tenant_id=?"));
                assertTrue(sql.contains("HAVING l.ordered_quantity>COALESCE(SUM(r.quantity),0)"));
                assertTrue(sql.contains("ORDER BY o.order_number ASC,l.id ASC LIMIT ?"));
                assertEquals(tenant,args[0]);
                assertEquals(51,args[args.length-1]);
                int begin=0;
                if(args.length==4) {
                    begin=Integer.parseInt(((String)args[1]).substring(8));
                    assertEquals(pending(begin).purchaseOrderLineId(),
                        ((UUID)args[2]).toString());
                }
                return all.subList(begin,Math.min(begin+51,all.size()));
            });
        var sizes=new ArrayList<Integer>();
        var seen=new HashSet<String>();
        String cursor=null;
        for(int i=0;i<11;i++) {
            var page=service.pendingReceiptsPage(tenant,50,"ALL","",cursor);
            sizes.add(page.items().size());
            for(var row:page.items())assertTrue(seen.add(row.purchaseOrderLineId()));
            cursor=page.nextCursor();
        }
        assertEquals(List.of(50,50,50,50,50,50,50,50,50,50,25),sizes);
        assertEquals(525,seen.size());
        assertNull(cursor);
    }

    @Test void tenantWarehouseStatusAndSearchCursorsFailClosed() {
        String stockCursor=LocalKeysetPage.encode("stock-v1",tenant.toString(),
            warehouse.toString(),"q:SKU","V113-SKU-0050",new UUID(0,50).toString());
        bad(()->service.stockPage(foreign,warehouse,50,"SKU",stockCursor));
        bad(()->service.stockPage(tenant,other,50,"SKU",stockCursor));
        bad(()->service.stockPage(tenant,warehouse,50,"OTHER",stockCursor));
        bad(()->service.stockPage(tenant,warehouse,50,"SKU","bad*"));
        bad(()->service.stockPage(tenant,warehouse,50,"SKU",
            LocalKeysetPage.encode("stock-v1",tenant.toString(),
              warehouse.toString(),"q:SKU","V113-SKU-0050","invalid uuid")));
        bad(()->service.stockPage(tenant,warehouse,101,"",null));
        bad(()->service.stockPage(tenant,warehouse,0,"",null));
        bad(()->service.stockPage(tenant,warehouse,50,"ABC%",null));
        bad(()->service.stockPage(tenant,null,50,"",null));
        String pendingCursor=LocalKeysetPage.encode("pending-v1",tenant.toString(),
            "APPROVED","q:PO-V113","PO-V113-0050",new UUID(2,50).toString());
        bad(()->service.pendingReceiptsPage(foreign,50,"APPROVED","PO-V113",pendingCursor));
        bad(()->service.pendingReceiptsPage(tenant,50,"RECEIVING","PO-V113",pendingCursor));
        bad(()->service.pendingReceiptsPage(tenant,50,"APPROVED","SKU",pendingCursor));
        bad(()->service.pendingReceiptsPage(tenant,50,"INVALID","",null));
        bad(()->service.pendingReceiptsPage(tenant,50,"APPROVED","PO%",null));
        bad(()->service.pendingReceiptsPage(tenant,50,"APPROVED","PO-V113",
            LocalKeysetPage.encode("pending-v1",tenant.toString(),
              "APPROVED","q:PO-V113","PO-V113-0050","invalid uuid")));
        verifyNoInteractions(jdbc);
    }

    @Test void searchUsesLiteralParameterizationAndChecksWarehouse() {
        readable();
        service.stockPage(tenant,warehouse,50," sku_ ",null);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(),any(RowMapper.class),args.capture());
        assertTrue(sql.getValue().contains("POSITION(? IN UPPER(s.internal_sku))>0"));
        assertArrayEquals(new Object[]{tenant,warehouse,tenant,warehouse,"SKU_",51},args.getValue());
    }
    @Test void exactMissingSkuIsNotInventedZeroOrSubmitted() {
        readable();
        UUID sku=new UUID(0,525);
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                ()->service.stockItem(tenant,warehouse,sku)).getStatusCode());
        verify(jdbc).query(contains("s.status='ACTIVE'"),any(RowMapper.class),
            eq(warehouse),eq(warehouse),eq(warehouse),eq(tenant),eq(sku));
        // An exact, registered active SKU with no recorded movements is *observed* zero.
        reset(jdbc);
        readable();
        when(jdbc.query(contains("s.status='ACTIVE'"),
            org.mockito.ArgumentMatchers.<RowMapper<WarehouseInventoryService.Stock>>any(),
            eq(warehouse),eq(warehouse),eq(warehouse),eq(tenant),eq(sku)))
            .thenReturn(List.of(new WarehouseInventoryService.Stock(
                warehouse.toString(),sku.toString(),"V113-SKU-0525",0,0)));
        assertEquals(0,service.stockItem(tenant,warehouse,sku).onHandQuantity());
    }
    @Test void foreignWarehouseCannotExposeExactOrPagedBalance() {
        when(jdbc.queryForObject(contains("erp_local_warehouse"),
            eq(Integer.class),eq(tenant),eq(other))).thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                ()->service.stockItem(tenant,other,new UUID(0,1))).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                ()->service.stockPage(tenant,other,50,"",null)).getStatusCode());
        verify(jdbc,never()).query(anyString(),any(RowMapper.class),any(Object[].class));
    }
}
