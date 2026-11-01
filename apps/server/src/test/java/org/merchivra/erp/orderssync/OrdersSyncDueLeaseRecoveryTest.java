package org.merchivra.erp.orderssync;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrdersSyncDueLeaseRecoveryTest {
    @Test
    void pollsExpiredRunningLeaseForFencedReclaimWithoutEarlyTakeover() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        OrdersSyncSource source=mock(OrdersSyncSource.class);
        when(source.dataOrigin()).thenReturn("MOCK");
        var service=new OrdersSyncService(jdbc,mock(TransactionTemplate.class),source,
            Clock.systemUTC());

        List<java.util.UUID[]> result=service.due(10);

        assertNotNull(result);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),any(RowMapper.class),eq("MOCK"),eq(10));
        assertTrue(sql.getValue().contains("state='RUNNING' AND lease_expires_at<=now()"));
        assertTrue(sql.getValue().contains("lease_expires_at IS NULL OR lease_expires_at<=now()"));
        assertTrue(sql.getValue().contains("PARTITION BY tenant_id"));
        assertTrue(sql.getValue().contains("LIMIT ?"));
    }
}
