package org.merchivra.erp.purchasing;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

/** V119: full 325-line keyset, tenant/cursor safety and unbounded SQL aggregates. */
class PurchaseDeliveryFullScopeV119Test {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PurchaseDeliveryService service=new PurchaseDeliveryService(jdbc,
        Clock.fixed(Instant.parse("2026-11-09T09:00:00Z"),ZoneOffset.UTC));
    private final UUID tenant=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID other=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final OffsetDateTime base=OffsetDateTime.parse("2026-11-09T08:00:00Z");

    private ResultSet line(int n) throws Exception {
        ResultSet rs=mock(ResultSet.class);
        when(rs.getObject("cursor_updated_at",OffsetDateTime.class))
            .thenReturn(base.minusSeconds(n));
        when(rs.getObject("order_id",UUID.class)).thenReturn(new UUID(1,n));
        when(rs.getObject("supplier_id",UUID.class)).thenReturn(other);
        when(rs.getObject("line_id",UUID.class)).thenReturn(new UUID(0,n));
        when(rs.getObject("product_sku_id",UUID.class)).thenReturn(new UUID(2,n));
        when(rs.getString("order_number")).thenReturn("LOCAL-PO-"+n);
        when(rs.getString("order_status")).thenReturn("APPROVED");
        when(rs.getString("supplier_name")).thenReturn("Test supplier");
        when(rs.getString("currency_code")).thenReturn("USD");
        when(rs.getString("internal_sku")).thenReturn("SKU-"+n);
        when(rs.getInt("ordered_quantity")).thenReturn(10);
        when(rs.getLong("received_units")).thenReturn(2L);
        when(rs.getLong("draft_units")).thenReturn(0L);
        when(rs.getLong("shipped_outstanding")).thenReturn(0L);
        when(rs.getLong("qc_awaiting_units")).thenReturn(2L);
        when(rs.getLong("qc_unknown_units")).thenReturn(0L);
        when(rs.getLong("unresolved_count")).thenReturn(0L);
        when(rs.getLong("shipped_units")).thenReturn(0L);
        when(rs.getLong("unlinked_units")).thenReturn(0L);
        when(rs.getLong("qc_released_units")).thenReturn(0L);
        return rs;
    }
    private void active() {
        when(jdbc.queryForObject(contains("FROM tenant"),eq(Integer.class),eq(tenant)))
            .thenReturn(1);
    }
    private static void bad(Runnable runnable) {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,runnable::run).getStatusCode());
    }

    @Test void returnsAllThreeHundredTwentyFiveInStableSevenPagesWithoutLegacyCap() {
        active();
        doAnswer(inv->{
            String sql=inv.getArgument(0);
            RowMapper<?> mapper=inv.getArgument(1);
            Object[] args=(Object[])inv.getRawArguments()[2];
            assertTrue(sql.contains("WHERE o.tenant_id=?"));
            assertTrue(sql.contains("ORDER BY o.updated_at DESC,o.id DESC,l.id DESC LIMIT ?"));
            assertFalse(sql.contains("LIMIT 300"));
            assertEquals(tenant,args[0]);
            assertEquals(51,args[args.length-1]);
            int start=0;
            if(args.length==5) {
                assertTrue(sql.contains("(o.updated_at,o.id,l.id)<(?,?,?)"));
                start=(int)((UUID)args[3]).getLeastSignificantBits();
                assertEquals(base.minusSeconds(start),args[1]);
                assertEquals(new UUID(1,start),args[2]);
            } else assertEquals(2,args.length);
            List<Object> rows=new ArrayList<>();
            for(int n=start+1;n<=Math.min(start+51,325);n++)
                rows.add(mapper.mapRow(line(n),n));
            return rows;
        }).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));

        String cursor=null;
        var all=new HashSet<String>();
        var pageSizes=new ArrayList<Integer>();
        for(int p=0;p<7;p++) {
            var got=service.page(tenant,50,cursor);
            pageSizes.add(got.items().size());
            for(var row:got.items()){
                assertTrue(all.add(row.lineId()));
                assertEquals(8,row.remainingQuantity());
                assertEquals("NO_ASN_RECORDED",row.deliveryState());
            }
            if(p==6)assertEquals(new UUID(0,301).toString(),
                got.items().getFirst().lineId());
            assertEquals(p<6,got.hasMore());
            cursor=got.nextCursor();
        }
        assertEquals(List.of(50,50,50,50,50,50,25),pageSizes);
        assertEquals(325,all.size());
        assertNull(cursor);
        verify(jdbc,times(7)).query(anyString(),any(RowMapper.class),any(Object[].class));
    }

    @Test void rejectForeignTenantVersionMalformedTimestampAndExceededLimitWithoutSql() {
        String cursor=LocalKeysetPage.encode("delivery-v119",tenant.toString(),
            base.minusSeconds(50).toString(),new UUID(1,50).toString(),
            new UUID(0,50).toString());
        bad(()->service.page(other,50,cursor));
        bad(()->service.page(tenant,0,null));
        bad(()->service.page(tenant,101,null));
        bad(()->service.page(tenant,50,"not base64%"));
        bad(()->service.page(tenant,50,LocalKeysetPage.encode(
            "delivery-v118",tenant.toString(),base.toString(),
            new UUID(1,50).toString(),new UUID(0,50).toString())));
        bad(()->service.page(tenant,50,LocalKeysetPage.encode(
            "delivery-v119",tenant.toString(),"invalid-date",
            new UUID(1,50).toString(),new UUID(0,50).toString())));
        bad(()->service.page(tenant,50,LocalKeysetPage.encode(
            "delivery-v119",tenant.toString(),base.toString(),
            "not-uuid",new UUID(0,50).toString())));
        verifyNoInteractions(jdbc);
    }

    @Test void summaryIsOneDatabaseAggregateAcrossFullTenantNotFirstThreeHundred() {
        active();
        doAnswer(inv->{
            String sql=inv.getArgument(0);
            RowMapper<?> mapper=inv.getArgument(1);
            Object[] args=(Object[])inv.getRawArguments()[2];
            assertTrue(sql.contains("WITH delivery AS"));
            assertTrue(sql.contains("FROM erp_purchase_order o"));
            assertTrue(sql.contains("WHERE o.tenant_id=?"));
            assertTrue(sql.contains("COUNT(*) AS line_count"));
            assertTrue(sql.contains("SUM(GREATEST(ordered_quantity-received_units,0))"));
            assertTrue(sql.contains("THEN 'REPORTED_ETA_OVERDUE'"));
            assertFalse(sql.contains("LIMIT 300"));
            assertEquals(tenant,args[0]);
            assertEquals(LocalDate.parse("2026-11-09"),args[1]);
            assertEquals(LocalDate.parse("2026-11-09"),args[2]);
            ResultSet rs=mock(ResultSet.class);
            when(rs.getLong("line_count")).thenReturn(325L);
            when(rs.getLong("pending_quantity")).thenReturn(2600L);
            when(rs.getLong("reported_unreceived_quantity")).thenReturn(14L);
            when(rs.getLong("awaiting_qc_release_quantity")).thenReturn(650L);
            when(rs.getLong("warning_lines")).thenReturn(325L);
            when(rs.getLong("overdue_lines")).thenReturn(7L);
            when(rs.getLong("unresolved_variance_lines")).thenReturn(13L);
            return mapper.mapRow(rs,0);
        }).when(jdbc).queryForObject(contains("WITH delivery AS"),
            any(RowMapper.class),any(Object[].class));
        var full=service.globalSummary(tenant);
        assertEquals("2026-11-09",full.asOfDateUtc());
        assertEquals(325,full.totals().lineCount());
        assertEquals(2600L,full.totals().pendingQuantity());
        assertEquals(650L,full.totals().awaitingQcReleaseQuantity());
        assertEquals(13L,full.totals().unresolvedVarianceLines());
    }

    @Test void inactiveTenantNeverReadsOtherTenantsDeliveryFacts() {
        when(jdbc.queryForObject(contains("FROM tenant"),eq(Integer.class),eq(tenant)))
            .thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.globalSummary(tenant)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.page(tenant,50,null)).getStatusCode());
        verify(jdbc,never()).query(contains("FROM erp_purchase_order o"),
            any(RowMapper.class),any(Object[].class));
    }
}
