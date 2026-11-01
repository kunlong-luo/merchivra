package org.merchivra.erp.finance;

import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.spapi.SpApiSellerGrantVault;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettlementReportScanServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SettlementReportScanService service=new SettlementReportScanService(
        jdbc,mock(TransactionTemplate.class),mock(SpApiSellerGrantVault.class),
        mock(SpApiSettlementReportsWorker.class),Clock.systemUTC());

    @Test
    void scheduleIsBoundedTenantFairAndDoesNotClaimUnexpiredRunningLease(){
        assertThrows(IllegalArgumentException.class,()->service.due(0));
        assertThrows(IllegalArgumentException.class,()->service.due(21));
        service.due(5);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),any(org.springframework.jdbc.core.RowMapper.class),
            eq(5));
        assertTrue(sql.getValue().contains("ROW_NUMBER() OVER (PARTITION BY tenant_id"));
        assertTrue(sql.getValue().contains("state='RUNNING' AND lease_expires_at<=now()"));
        assertTrue(sql.getValue().contains("lease_expires_at IS NULL OR lease_expires_at<=now()"));
        assertTrue(sql.getValue().contains("retry_at IS NULL OR retry_at<=now()"));
    }

    @Test
    void rejectsMissingActorBeforeCreatingAnyReportsJob(){
        var error=assertThrows(ResponseStatusException.class,
            ()->service.start(UUID.randomUUID(),UUID.randomUUID(),null));
        assertEquals(HttpStatus.UNAUTHORIZED,error.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void reportWorkerPageValidatesUnboundedInputWithoutNetworkOrDb(){
        var worker=new SpApiSettlementReportsWorker(null,null,null,null);
        assertThrows(IllegalArgumentException.class,()->worker.scanPage(
            UUID.randomUUID(),UUID.randomUUID(),"ATVPDKIKX0DER",
            java.time.OffsetDateTime.now().minusDays(15),java.time.OffsetDateTime.now(),
            null,0,2,(scope,report,doc,body,gzip)->{}));
        assertThrows(IllegalArgumentException.class,()->worker.scanPage(
            UUID.randomUUID(),UUID.randomUUID(),"ATVPDKIKX0DER",
            java.time.OffsetDateTime.now().minusDays(1),java.time.OffsetDateTime.now(),
            null,1001,2,(scope,report,doc,body,gzip)->{}));
        assertThrows(IllegalArgumentException.class,()->worker.scanPage(
            UUID.randomUUID(),UUID.randomUUID(),"ATVPDKIKX0DER",
            java.time.OffsetDateTime.now().minusDays(1),java.time.OffsetDateTime.now(),
            null,0,3,(scope,report,doc,body,gzip)->{}));
    }
}
