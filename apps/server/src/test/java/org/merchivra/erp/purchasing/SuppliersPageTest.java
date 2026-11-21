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

/** V111: full-tenant supplier listing and fail-closed cursor tests (unit mocks). */
class SuppliersPageTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PurchasingService purchasing=
        new PurchasingService(jdbc,mock(WarehouseInventoryService.class));
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID foreign=UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static PurchasingService.Supplier supplier(int n) {
        return new PurchasingService.Supplier(new UUID(0,n).toString(),
            String.format(java.util.Locale.ROOT,"SUP-%04d",n),"Test Supplier "+n,
            "ACTIVE");
    }
    private static void bad(Runnable action) {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,action::run).getStatusCode());
    }

    @Test void twoHundredTwentyFiveSuppliersReachFifthPageWithoutDuplicates() {
        var all=new ArrayList<PurchasingService.Supplier>();
        for(int i=1;i<=225;i++)all.add(supplier(i));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers
            .<RowMapper<PurchasingService.Supplier>>any(),any(Object[].class)))
            .thenAnswer(inv -> {
                String sql=inv.getArgument(0);
                assertTrue(sql.contains("WHERE tenant_id=?"));
                assertTrue(sql.contains("ORDER BY supplier_code ASC,id ASC LIMIT ?"));
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertEquals(tenant,args[0]);
                assertEquals(51,args[args.length-1]);
                int start=0;
                if(args.length==4) {
                    assertTrue(sql.contains("(supplier_code,id) > (?,?)"));
                    String last=(String)args[1];
                    start=Integer.parseInt(last.substring(4));
                    assertEquals(supplier(start).id(),((UUID)args[2]).toString());
                }
                return all.subList(start,Math.min(start+51,all.size()));
            });
        var pages=new ArrayList<LocalKeysetPage.Page<PurchasingService.Supplier>>();
        String cursor=null;
        for(int i=0;i<5;i++) {
            var page=purchasing.suppliersPage(tenant,50,"ALL","",cursor);
            pages.add(page);
            cursor=page.nextCursor();
        }
        assertEquals(List.of(50,50,50,50,25),pages.stream()
            .map(p->p.items().size()).toList());
        assertTrue(pages.get(3).hasMore());
        assertFalse(pages.get(4).hasMore());
        assertNull(cursor);
        var ids=new HashSet<String>();
        for(var page:pages)for(var value:page.items())
            assertTrue(ids.add(value.id()),"A supplier cannot repeat between pages");
        assertEquals(225,ids.size());
        assertEquals(supplier(201),pages.get(4).items().getFirst());
        verify(jdbc,times(5)).query(anyString(),any(RowMapper.class),any(Object[].class));
    }

    @Test void statusPrefixAndTenantAreBoundAndCursorCannotBeReused() {
        String cursor=LocalKeysetPage.encode("supplier-v1",tenant.toString(),
            "ACTIVE","q:SUP_","SUP_0010",new UUID(0,10).toString());
        bad(()->purchasing.suppliersPage(foreign,50,"ACTIVE","SUP_",cursor));
        bad(()->purchasing.suppliersPage(tenant,50,"ALL","SUP_",cursor));
        bad(()->purchasing.suppliersPage(tenant,50,"ACTIVE","SUP-",cursor));
        bad(()->purchasing.suppliersPage(tenant,50,"ACTIVE","SUP_",
            LocalKeysetPage.encode("supplier-v1",tenant.toString(),"ACTIVE",
                "q:SUP_","invalid code!",new UUID(0,10).toString())));
        bad(()->purchasing.suppliersPage(tenant,50,"ACTIVE","SUP_",
            LocalKeysetPage.encode("supplier-v1",tenant.toString(),"ACTIVE",
                "q:SUP_","SUP_0010","invalid uuid")));
        bad(()->purchasing.suppliersPage(tenant,50,"ACTIVE","SUP_","invalid*"));
        bad(()->purchasing.suppliersPage(tenant,101,"ALL","",null));
        bad(()->purchasing.suppliersPage(tenant,0,"ALL","",null));
        bad(()->purchasing.suppliersPage(tenant,50,"DISABLED","",null));
        bad(()->purchasing.suppliersPage(tenant,50,"ALL","SUP%",null));
        bad(()->purchasing.suppliersPage(tenant,50,"ALL","S".repeat(61),null));
        verifyNoInteractions(jdbc);
    }

    @Test void searchesEntireTenantWithParameterizedLiteralPrefix() {
        purchasing.suppliersPage(tenant,50,"ACTIVE"," sup_ ",null);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        var params=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(),any(RowMapper.class),params.capture());
        assertTrue(sql.getValue().contains("WHERE tenant_id=?"));
        assertTrue(sql.getValue().contains("AND status=?"));
        assertTrue(sql.getValue().contains("LEFT(supplier_code,LENGTH(?))=?"));
        assertFalse(sql.getValue().contains("SUP_"));
        assertArrayEquals(new Object[]{tenant,"ACTIVE","SUP_","SUP_",51},
            params.getValue());
    }

    @Test void purchaseCreationStillRequiresActiveSupplierWithinTenant() {
        var request=List.of(new PurchasingService.RequestedLine(
            new UUID(0,22),1,"2.50"));
        ResponseStatusException exception=assertThrows(ResponseStatusException.class,
            ()->purchasing.create(tenant,new UUID(0,5),new UUID(0,9),
                "USD","unit test",request));
        assertEquals(HttpStatus.NOT_FOUND,exception.getStatusCode());
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),any(RowMapper.class),eq(tenant),eq(new UUID(0,9)));
        assertTrue(sql.getValue().contains("tenant_id=? AND id=? AND status='ACTIVE'"));
    }
}
