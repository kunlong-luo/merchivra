package org.merchivra.erp.purchasing;

import java.time.Instant;
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

/** V112 tenant/warehouse-bound cursor and 125-row movement history unit tests. */
class WarehouseLedgerPageTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseInventoryService service=new WarehouseInventoryService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID foreign=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID warehouse=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID otherWarehouse=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID sku=UUID.fromString("55555555-5555-4555-8555-555555555555");

    private WarehouseInventoryService.Movement movement(int index) {
        return new WarehouseInventoryService.Movement(
            new UUID(0,125-index).toString(),warehouse.toString(),sku.toString(),
            "V112-SEARCH",null,new UUID(1,index+1).toString(),null,null,
            index==0?-2:1,index==0?"OUTBOUND":"COUNT_ADJUSTMENT",
            index==0?"V112 manual outbound":"V112 count adjustment",
            Instant.parse("2026-11-08T12:00:00Z").minusSeconds(index/3).toString());
    }

    private static void bad(Runnable action) {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,action::run).getStatusCode());
    }

    @Test void oneHundredTwentyFiveRowsReachThirdPageWithoutLossOrDuplicate() {
        when(jdbc.queryForObject(contains("erp_local_warehouse"),
            eq(Integer.class),eq(tenant),eq(warehouse))).thenReturn(1);
        var all=new ArrayList<WarehouseInventoryService.Movement>();
        for(int i=0;i<125;i++)all.add(movement(i));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers
            .<RowMapper<WarehouseInventoryService.Movement>>any(),any(Object[].class)))
            .thenAnswer(inv->{
                String sql=inv.getArgument(0);
                assertTrue(sql.contains("m.tenant_id=? AND m.warehouse_id=?"));
                assertTrue(sql.contains("ORDER BY m.created_at DESC,m.id DESC LIMIT ?"));
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertEquals(tenant,args[0]);
                assertEquals(warehouse,args[1]);
                assertEquals(51,args[args.length-1]);
                int start=0;
                if(args.length==5) {
                    assertTrue(sql.contains("(m.created_at,m.id)<(?,?)"));
                    UUID last=(UUID)args[3];
                    start=126-(int)last.getLeastSignificantBits();
                    assertEquals(movement(start-1).createdAt(),
                        ((java.time.OffsetDateTime)args[2]).toInstant().toString());
                }
                return all.subList(start,Math.min(start+51,all.size()));
            });
        var pages=new ArrayList<LocalKeysetPage.Page<WarehouseInventoryService.Movement>>();
        String cursor=null;
        for(int i=0;i<3;i++){
            var page=service.ledgerPage(tenant,warehouse,50,"ALL","",cursor);
            pages.add(page);
            cursor=page.nextCursor();
        }
        assertEquals(List.of(50,50,25),pages.stream().map(p->p.items().size()).toList());
        assertTrue(pages.get(0).hasMore());
        assertTrue(pages.get(1).hasMore());
        assertFalse(pages.get(2).hasMore());
        assertNull(cursor);
        var ids=new HashSet<String>();
        for(var page:pages)for(var row:page.items())assertTrue(ids.add(row.id()));
        assertEquals(125,ids.size());
        assertEquals(-2,pages.get(0).items().getFirst().quantity());
        assertEquals(movement(100),pages.get(2).items().getFirst());
        verify(jdbc,times(3)).query(anyString(),any(RowMapper.class),any(Object[].class));
    }

    @Test void scopeFiltersAndMalformedCursorsAreRejectedBeforeDatabase() {
        String valid=LocalKeysetPage.encode("ledger-v1",tenant.toString(),
            warehouse.toString(),"OUTBOUND","q:V112",
            "2026-11-08T12:00:00Z",new UUID(0,12).toString());
        bad(()->service.ledgerPage(foreign,warehouse,50,"OUTBOUND","V112",valid));
        bad(()->service.ledgerPage(tenant,otherWarehouse,50,"OUTBOUND","V112",valid));
        bad(()->service.ledgerPage(tenant,warehouse,50,"ALL","V112",valid));
        bad(()->service.ledgerPage(tenant,warehouse,50,"OUTBOUND","V113",valid));
        bad(()->service.ledgerPage(tenant,warehouse,50,"OUTBOUND","V112",
            LocalKeysetPage.encode("ledger-v1",tenant.toString(),warehouse.toString(),
                "OUTBOUND","q:V112","garbage",new UUID(0,1).toString())));
        bad(()->service.ledgerPage(tenant,warehouse,50,"OUTBOUND","V112",
            LocalKeysetPage.encode("ledger-v1",tenant.toString(),warehouse.toString(),
                "OUTBOUND","q:V112","2026-11-08T12:00:00Z","bad-uuid")));
        bad(()->service.ledgerPage(tenant,warehouse,50,"OUTBOUND","V112","bad*"));
        bad(()->service.ledgerPage(tenant,warehouse,0,"ALL","",null));
        bad(()->service.ledgerPage(tenant,warehouse,101,"ALL","",null));
        bad(()->service.ledgerPage(tenant,warehouse,50,"INVALID","",null));
        bad(()->service.ledgerPage(tenant,warehouse,50,"ALL","X".repeat(81),null));
        bad(()->service.ledgerPage(tenant,null,50,"ALL","",null));
        verifyNoInteractions(jdbc);
    }
    @Test void parameterizedServerSearchIsLiteralAndWarehouseIsChecked() {
        when(jdbc.queryForObject(contains("erp_local_warehouse"),
            eq(Integer.class),eq(tenant),eq(warehouse))).thenReturn(1);
        service.ledgerPage(tenant,warehouse,50,"QC_RELEASE"," sku_% ",null);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(),any(RowMapper.class),args.capture());
        assertTrue(sql.getValue().contains("AND m.movement_type=?"));
        assertTrue(sql.getValue().contains("POSITION(LOWER(?) IN LOWER(s.internal_sku))>0"));
        assertFalse(sql.getValue().contains("SKU_%"));
        assertArrayEquals(new Object[]{tenant,warehouse,"QC_RELEASE","SKU_%",51},
            args.getValue());
        verify(jdbc).queryForObject(contains("erp_local_warehouse"),
            eq(Integer.class),eq(tenant),eq(warehouse));
    }

    @Test void nonexistentOrForeignWarehouseDoesNotExposeLedger() {
        when(jdbc.queryForObject(contains("erp_local_warehouse"),
            eq(Integer.class),eq(tenant),eq(warehouse))).thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.ledgerPage(tenant,warehouse,50,"ALL","",null))
            .getStatusCode());
        verify(jdbc,never()).query(anyString(),any(RowMapper.class),any(Object[].class));
    }
}
