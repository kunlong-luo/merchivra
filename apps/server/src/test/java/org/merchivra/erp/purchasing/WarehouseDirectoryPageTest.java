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

/** V114 complete warehouse selection: 225 rows and tenant/filter-bound cursors. */
class WarehouseDirectoryPageTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseInventoryService service=new WarehouseInventoryService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID foreign=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static WarehouseInventoryService.Warehouse warehouse(int n) {
        return new WarehouseInventoryService.Warehouse(new UUID(0,n).toString(),
            String.format(java.util.Locale.ROOT,"WH-%04d",n),
            "V114 Local Warehouse "+n,"ACTIVE");
    }
    private static void bad(Runnable action) {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,action::run).getStatusCode());
    }
    @Test void beyondTheOldTwoHundredWarehouseCapAllRowsRemainSelectable() {
        var all=new ArrayList<WarehouseInventoryService.Warehouse>();
        for(int i=1;i<=225;i++)all.add(warehouse(i));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers
            .<RowMapper<WarehouseInventoryService.Warehouse>>any(),any(Object[].class)))
            .thenAnswer(inv->{
                String sql=inv.getArgument(0);
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertTrue(sql.contains("FROM erp_local_warehouse WHERE tenant_id=?"));
                assertTrue(sql.contains("ORDER BY warehouse_code ASC,id ASC LIMIT ?"));
                assertEquals(tenant,args[0]);
                assertEquals(51,args[args.length-1]);
                int start=0;
                if(args.length==4){
                    assertTrue(sql.contains("(warehouse_code,id)>(?,?)"));
                    start=Integer.parseInt(((String)args[1]).substring(3));
                    assertEquals(warehouse(start).id(),((UUID)args[2]).toString());
                }
                return all.subList(start,Math.min(start+51,all.size()));
            });
        var pages=new ArrayList<LocalKeysetPage.Page<WarehouseInventoryService.Warehouse>>();
        String cursor=null;
        for(int i=0;i<5;i++){
            var page=service.warehousesPage(tenant,50,"ALL","",cursor);
            pages.add(page);
            cursor=page.nextCursor();
        }
        assertEquals(List.of(50,50,50,50,25),pages.stream()
            .map(p->p.items().size()).toList());
        assertEquals(warehouse(201),pages.get(4).items().getFirst());
        assertFalse(pages.getLast().hasMore());
        assertNull(cursor);
        var ids=new HashSet<String>();
        for(var page:pages)for(var row:page.items())
            assertTrue(ids.add(row.id()),"Duplicate warehouse across pages");
        assertEquals(225,ids.size());
        verify(jdbc,times(5)).query(anyString(),any(RowMapper.class),any(Object[].class));
    }
    @Test void validatesEveryCursorDimensionBeforeReading() {
        var cursor=LocalKeysetPage.encode("warehouse-v1",tenant.toString(),
            "ACTIVE","q:WH_","WH_0200",new UUID(0,200).toString());
        bad(()->service.warehousesPage(foreign,50,"ACTIVE","WH_",cursor));
        bad(()->service.warehousesPage(tenant,50,"ARCHIVED","WH_",cursor));
        bad(()->service.warehousesPage(tenant,50,"ACTIVE","WH-",cursor));
        bad(()->service.warehousesPage(tenant,50,"ACTIVE","WH_",
            LocalKeysetPage.encode("warehouse-v1",tenant.toString(),
                "ACTIVE","q:WH_","bad code!",new UUID(0,200).toString())));
        bad(()->service.warehousesPage(tenant,50,"ACTIVE","WH_",
            LocalKeysetPage.encode("warehouse-v1",tenant.toString(),
                "ACTIVE","q:WH_","WH_0200","bad-uuid")));
        bad(()->service.warehousesPage(tenant,50,"ACTIVE","WH_","bad*"));
        bad(()->service.warehousesPage(tenant,0,"ALL","",null));
        bad(()->service.warehousesPage(tenant,101,"ALL","",null));
        bad(()->service.warehousesPage(tenant,50,"HIDDEN","",null));
        bad(()->service.warehousesPage(tenant,50,"ALL","WH%",null));
        bad(()->service.warehousesPage(tenant,50,"ALL","W".repeat(61),null));
        verifyNoInteractions(jdbc);
    }
    @Test void statusAndLiteralPrefixAreAppliedAcrossEntireTenant() {
        service.warehousesPage(tenant,50,"ACTIVE"," wh_ ",null);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(),any(RowMapper.class),args.capture());
        assertTrue(sql.getValue().contains("AND status=?"));
        assertTrue(sql.getValue().contains("LEFT(warehouse_code,LENGTH(?))=?"));
        assertArrayEquals(new Object[]{tenant,"ACTIVE","WH_","WH_",51},args.getValue());
    }
    @Test void exactReadNeverReturnsForeignTenantOrArchiveAsActive() {
        UUID id=new UUID(0,201);
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers
            .<RowMapper<WarehouseInventoryService.Warehouse>>any(),
            eq(tenant),eq(id))).thenReturn(List.of(warehouse(201)));
        assertEquals(warehouse(201),service.warehouseItem(tenant,id));
        verify(jdbc).query(contains("WHERE tenant_id=? AND id=?"),
            any(RowMapper.class),eq(tenant),eq(id));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.warehouseItem(foreign,id)).getStatusCode());
    }
}
