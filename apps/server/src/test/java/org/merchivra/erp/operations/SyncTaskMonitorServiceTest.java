package org.merchivra.erp.operations;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncTaskMonitorServiceTest {
    @Test
    void everySourceSelectIsTenantScopedAndNeverQueriesSecretFields() {
        String sql=SyncTaskMonitorService.JOBS;
        assertTrue(sql.contains("FROM orders_sync_job WHERE tenant_id=?"));
        assertTrue(sql.contains("FROM spapi_catalog_sync_job WHERE tenant_id=?"));
        assertTrue(sql.contains("FROM spapi_finances_sync_job WHERE tenant_id=?"));
        for (String forbidden : List.of("next_token", "refresh_token", "lease_owner",
                "seller_id", "response_body", "access_token"))
            assertFalse(sql.contains(forbidden), forbidden);
    }

    @Test
    void rejectsUnsupportedFiltersAndOversizedRequestsBeforeSql() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var service=new SyncTaskMonitorService(jdbc);
        UUID tenant=UUID.randomUUID();
        assertThrows(SecurityException.class,()->service.snapshot(null,"ALL","ALL",10));
        assertThrows(IllegalArgumentException.class,()->service.snapshot(tenant,"ALL","ALL",101));
        assertThrows(IllegalArgumentException.class,()->service.snapshot(tenant,"ALL","ALL",0));
        assertThrows(IllegalArgumentException.class,()->service.snapshot(tenant,"' OR TRUE --","ALL",10));
        assertThrows(IllegalArgumentException.class,()->service.snapshot(tenant,"ALL","SLEEP",10));
        verifyNoInteractions(jdbc);
    }
}
