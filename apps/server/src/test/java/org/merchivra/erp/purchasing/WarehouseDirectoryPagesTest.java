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

/** V114: tenant-bound warehouse directory and exact ACTIVE lookups. */
class WarehouseDirectoryPagesTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final WarehouseInventoryService service=new WarehouseInventoryService(jdbc);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID foreign=UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static WarehouseInventoryService.Warehouse warehouse(int n){
        return new WarehouseInventoryService.Warehouse(new UUID(0,n).toString(),
            String.format("V114-WH-%04d",n),"Local Warehouse "+n,"ACTIVE");
    }
    private static void bad(Runnable action){
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,action::run).getStatusCode());
    }

    @Test void twoHundredTwentyFiveWarehousesAreDiscoverableAcrossFivePages(){
        var all=new ArrayList<WarehouseInventoryService.Warehouse>();
        for(int i=1;i<=225;i++)all.add(warehouse(i));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers
            .<RowMapper<WarehouseInventoryService.Warehouse>>any(),any(Object[].class)))
            .thenAnswer(inv->{
                String sql=inv.getArgument(0);
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertTrue(sql.contains("WHERE tenant_id=?"));
                assertTrue(sql.contains("ORDER BY warehouse_code ASC,id ASC LIMIT ?"));
                assertEquals(tenant,args[0]);
                assertEquals(51,args[args.length-1]);
                int start=0;
                if(args.length==4){
                    assertTrue(sql.contains("(warehouse_code,id)>(?,?)"));
                    start=Integer.parseInt(((String)args[1]).substring(8));
                    assertEquals(warehouse(start).id(),((UUID)args[2]).toString());
                }
                return all.subList(start,Math.min(start+51,all.size()));
            });
        String cursor=null;
        var ids=new HashSet<String>();
        var sizes=new ArrayList<Integer>();
        for(int i=0;i<5;i++){
            var page=service.warehousesPage(tenant,50,"ALL","",cursor);
            sizes.add(page.items().size());
            for(var value:page.items())assertTrue(ids.add(value.id()));
            if(i<4)assertTrue(page.hasMore());
            else assertFalse(page.hasMore());
            cursor=page.nextCursor();
        }
        assertEquals(List.of(50,50,50,50,25),sizes);
        assertEquals(225,ids.size());
        assertTrue(ids.contains(warehouse(201).id()));
        assertNull(cursor);
    }

    @Test void foreignTenantStatusPrefixAndInvalidCursorFailBeforeQuery(){
        var cursor=LocalKeysetPage.encode("warehouse-v1",tenant.toString(),
            "ACTIVE","q:V114-WH-","V114-WH-0050",new UUID(0,50).toString());
        bad(()->service.warehousesPage(foreign,50,"ACTIVE","V114-WH-",cursor));
        bad(()->service.warehousesPage(tenant,50,"ARCHIVED","V114-WH-",cursor));
        bad(()->service.warehousesPage(tenant,50,"ACTIVE","V114-WH-02",cursor));
        bad(()->service.warehousesPage(tenant,50,"ACTIVE","V114-WH-","bad*"));
        bad(()->service.warehousesPage(tenant,50,"INVALID","",null));
        bad(()->service.warehousesPage(tenant,50,"ALL","WH%",null));
        bad(()->service.warehousesPage(tenant,0,"ALL","",null));
        bad(()->service.warehousesPage(tenant,101,"ALL","",null));
        bad(()->service.warehousesPage(tenant,50,"ACTIVE","V114-WH-",
            LocalKeysetPage.encode("warehouse-v1",tenant.toString(),
                "ACTIVE","q:V114-WH-","bad code",new UUID(0,50).toString())));
        bad(()->service.warehousesPage(tenant,50,"ACTIVE","V114-WH-",
            LocalKeysetPage.encode("warehouse-v1",tenant.toString(),
                "ACTIVE","q:V114-WH-","V114-WH-0050","bad-uuid")));
        verifyNoInteractions(jdbc);
    }

    @Test void serverSearchUsesLiteralParameterizedPrefix(){
        service.warehousesPage(tenant,50,"ACTIVE"," v114_wh_ ",null);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        var params=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(),any(RowMapper.class),params.capture());
        assertTrue(sql.getValue().contains("status=?"));
        assertTrue(sql.getValue().contains("LEFT(warehouse_code,LENGTH(?))=?"));
        assertFalse(sql.getValue().contains("V114_WH_"));
        assertArrayEquals(new Object[]{tenant,"ACTIVE","V114_WH_","V114_WH_",51},
            params.getValue());
    }

    @Test void exactWarehouseLookupCannotReadOtherTenants(){
        UUID id=new UUID(0,201);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.warehouseItem(tenant,id)).getStatusCode());
        verify(jdbc).query(contains("WHERE tenant_id=? AND id=?"),
            any(RowMapper.class),eq(tenant),eq(id));
        bad(()->service.warehouseItem(tenant,null));
    }
}
