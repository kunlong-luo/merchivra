package org.merchivra.erp.purchasing;

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

class PurchaseFollowupServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PurchaseFollowupService service=new PurchaseFollowupService(jdbc,7);
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID actor=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID finding=UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test void invalidSettingsFailFast() {
        assertThrows(IllegalArgumentException.class,()->new PurchaseFollowupService(jdbc,0));
        assertThrows(IllegalArgumentException.class,()->new PurchaseFollowupService(jdbc,61));
    }
    @Test void revokedTenantCannotCreateFollowupAndInvalidTriggerFailsClosed() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.scan(tenant,actor,"MANUAL")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.scan(tenant,actor,"HACK")).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void ownerScanKeepsSeparateCoverageAndUnshippedCriteriaAndClearsOld() {
        when(jdbc.queryForList(contains("FROM tenant"),eq(UUID.class),eq(tenant)))
            .thenReturn(List.of(tenant));
        when(jdbc.update(contains("'ASN_COVERAGE_GAP'"),any(),eq(tenant),eq(7)))
            .thenReturn(2);
        when(jdbc.update(contains("'ASN_NOT_DISPATCHED'"),any(),eq(tenant),eq(7)))
            .thenReturn(1);
        when(jdbc.update(contains("SET state='CLEARED'"),eq(tenant),any()))
            .thenReturn(1);
        var result=service.scan(tenant,actor,"MANUAL");
        assertEquals("MANUAL",result.triggerType());
        assertEquals(3,result.matchedCount());
        assertEquals(1,result.clearedCount());
        verify(jdbc).update(contains("erp_purchase_followup_scan"),
            any(),eq(tenant),eq(actor),eq("MANUAL"),eq(3),eq(1));
    }
    @Test void pageLimitsRawFindingIdsBeforeLoadingASNAndReceiptFacts() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenAnswer(inv->{
                String sql=inv.getArgument(0);
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertTrue(sql.contains("WITH page_ids AS MATERIALIZED"));
                assertTrue(sql.contains("SELECT f.id FROM erp_purchase_followup_finding f"));
                assertTrue(sql.contains("ORDER BY "));
                assertTrue(sql.contains("f.changed_at DESC,f.id DESC LIMIT ?"));
                assertTrue(sql.contains("FROM page_ids page JOIN erp_purchase_followup_finding f"));
                assertTrue(sql.contains("LEFT JOIN LATERAL"));
                assertTrue(sql.indexOf("SELECT f.id FROM") <
                    sql.indexOf("LEFT JOIN LATERAL"));
                assertEquals(5,args.length);
                assertEquals(tenant,args[0]);
                assertEquals("ALL",args[1]);
                assertEquals(51,args[2]);
                assertEquals(tenant,args[3]);
                assertEquals("ALL",args[4]);
                return List.of();
            });
        var page=service.findingsPage(tenant,"ALL",50,null);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        assertFalse(page.hasMore());
        assertEquals(50,page.pageSize());
    }

    @Test void pageCursorKeysetPrecedesExpensiveJoinAndIsTenantFilterBound() {
        var id=UUID.fromString("44444444-4444-4444-8444-444444444444");
        String cursor=LocalKeysetPage.encode(tenant.toString(),"ACTIVE",
            "1","2026-11-09T09:00:00Z",id.toString());
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenAnswer(inv->{
                String sql=inv.getArgument(0);
                Object[] args=(Object[])inv.getRawArguments()[2];
                assertTrue(sql.contains("WITH page_ids AS MATERIALIZED"));
                assertTrue(sql.contains("(f.changed_at,f.id)<(?,?)"));
                assertEquals(9,args.length);
                assertEquals(tenant,args[0]);
                assertEquals("ACTIVE",args[1]);
                assertEquals(1,args[2]);
                assertEquals(1,args[3]);
                assertEquals(id,args[5]);
                assertEquals(51,args[6]);
                assertEquals(tenant,args[7]);
                assertEquals("ACTIVE",args[8]);
                return List.of();
            });
        assertTrue(service.findingsPage(tenant,"ACTIVE",50,cursor).items().isEmpty());
    }

    @Test void invalidOrClearedFindingCannotBeReopened() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.changeState(tenant,actor,finding,"UNKNOWN")).getStatusCode());
        when(jdbc.query(contains("erp_purchase_followup_finding"),
            any(RowMapper.class),eq(tenant),eq(finding))).thenReturn(List.of("CLEARED"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.changeState(tenant,actor,finding,"OPEN")).getStatusCode());
    }
}
