package org.merchivra.erp.orderssync;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Reproduces the race: the page is fetched, but Seller role/grant is revoked
 * before the final tenant-scoped commit. No real SP-API calls are made.
 */
class OrdersSyncRevocationFenceTest {
    @Test
    @SuppressWarnings("unchecked")
    void revokedAfterFetchCannotWriteOrdersOrAdvanceCursor() throws Exception {
        UUID tenant=UUID.randomUUID(),market=UUID.randomUUID(),job=UUID.randomUUID();
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        TransactionTemplate tx=mock(TransactionTemplate.class);
        OrdersSyncSource source=mock(OrdersSyncSource.class);
        AtomicBoolean revoked=new AtomicBoolean(false);

        when(source.allowed()).thenReturn(true);
        when(source.dataOrigin()).thenReturn("LIVE");
        when(tx.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback=inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(jdbc.query(anyString(),any(RowMapper.class),eq(tenant),eq(market)))
            .thenAnswer(inv -> {
                String sql=inv.getArgument(0);
                RowMapper<?> mapper=inv.getArgument(1);
                if(sql.contains("SELECT m.marketplace_id")) {
                    if(revoked.get())return List.of();
                    ResultSet rs=mock(ResultSet.class);
                    when(rs.getString(1)).thenReturn("ATVPDKIKX0DER");
                    return List.of(mapper.mapRow(rs,0));
                }
                if(sql.contains("FROM orders_sync_job")) {
                    ResultSet rs=mock(ResultSet.class);
                    when(rs.getObject("id",UUID.class)).thenReturn(job);
                    when(rs.getString("mode")).thenReturn("LIVE");
                    when(rs.getString("state")).thenReturn("READY");
                    when(rs.getObject("window_after",OffsetDateTime.class))
                        .thenReturn(OffsetDateTime.ofInstant(Instant.parse("2026-10-29T00:00:00Z"),
                            ZoneOffset.UTC));
                    when(rs.getObject("window_before",OffsetDateTime.class))
                        .thenReturn(OffsetDateTime.ofInstant(Instant.parse("2026-10-30T00:00:00Z"),
                            ZoneOffset.UTC));
                    when(rs.getInt("page_index")).thenReturn(0);
                    when(rs.getInt("generation")).thenReturn(0);
                    when(rs.getLong("lease_epoch")).thenReturn(0L);
                    return List.of(mapper.mapRow(rs,0));
                }
                throw new AssertionError("Unexpected SQL in guarded workflow");
            });
        when(source.fetch(any(),any(),any(),isNull())).thenAnswer(inv -> {
            revoked.set(true);
            return new OrdersSyncSource.Page(
                List.of(new OrdersSyncSource.OrderFact("LIVE-ORDER-1",
                    "ATVPDKIKX0DER",Instant.parse("2026-10-29T12:00:00Z"),
                    "SHIPPED")),null);
        });

        var service=new OrdersSyncService(jdbc,tx,source);
        var error=assertThrows(ResponseStatusException.class,
            ()->service.step(tenant,market));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
        assertTrue(revoked.get());
        verify(source,times(1)).fetch(any(),any(),any(),isNull());
        verify(jdbc,never()).update(contains("INSERT INTO sales_order"),
            any(Object[].class));
        verify(jdbc,never()).update(contains("orders_sync_page_commit"),
            any(Object[].class));
        verify(jdbc,never()).update(contains("last_safe_watermark"),
            any(Object[].class));
    }
}
