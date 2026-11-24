package org.merchivra.erp.finance;

import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.spapi.SpApiSellerGrantVault;
import org.merchivra.erp.spapi.SpApiSellerScope;
import org.merchivra.erp.spapi.SpApiRegion;
import org.merchivra.erp.spapi.SpApiOfficialSdkFactory;
import org.merchivra.erp.spapi.SpApiOperation;
import software.amazon.spapi.api.reports.v2021_06_30.ReportsApi;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
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
    void observedGrantChangeRejectsSameSellerWithDifferentCredentialWithoutLeakingIt() {
        UUID tenant=UUID.randomUUID(),account=UUID.randomUUID();
        String site="ATVPDKIKX0DER";
        var roles=Set.of("Finance and Accounting");
        var original=new SpApiSellerScope(tenant,account,"A1TESTSELLER",site,
            SpApiRegion.NA,"synthetic-old-grant",roles);
        var unchanged=new SpApiSellerScope(tenant,account,"A1TESTSELLER",site,
            SpApiRegion.NA,"synthetic-old-grant",roles);
        var rotated=new SpApiSellerScope(tenant,account,"A1TESTSELLER",site,
            SpApiRegion.NA,"synthetic-new-grant",roles);
        var otherSite=new SpApiSellerScope(tenant,account,"A1TESTSELLER","A1OTHER",
            SpApiRegion.NA,"synthetic-old-grant",roles);
        var noFinance=new SpApiSellerScope(tenant,account,"A1TESTSELLER",site,
            SpApiRegion.NA,"synthetic-old-grant",Set.of());
        assertDoesNotThrow(()->SpApiSettlementReportsWorker.requireUnchangedFinanceScope(original,unchanged));
        var rejected=assertThrows(SecurityException.class,
            ()->SpApiSettlementReportsWorker.requireUnchangedFinanceScope(original,rotated));
        assertEquals("Finance grant changed during report read",rejected.getMessage());
        assertThrows(SecurityException.class,
            ()->SpApiSettlementReportsWorker.requireUnchangedFinanceScope(original,otherSite));
        assertThrows(SecurityException.class,
            ()->SpApiSettlementReportsWorker.requireUnchangedFinanceScope(original,noFinance));
        assertThrows(SecurityException.class,
            ()->SpApiSettlementReportsWorker.requireUnchangedFinanceScope(noFinance,noFinance));
        assertThrows(SecurityException.class,
            ()->SpApiSettlementReportsWorker.requireUnchangedFinanceScope(original,null));
    }

    @Test
    void reportListResponseCannotProceedAfterObservedGrantRotation() throws Exception {
        UUID tenant=UUID.randomUUID(),account=UUID.randomUUID();
        String site="ATVPDKIKX0DER";
        var roles=Set.of("Finance and Accounting");
        var original=new SpApiSellerScope(tenant,account,"A1TESTSELLER",site,
            SpApiRegion.NA,"synthetic-old-grant",roles);
        var rotated=new SpApiSellerScope(tenant,account,"A1TESTSELLER",site,
            SpApiRegion.NA,"synthetic-new-grant",roles);
        var vault=mock(SpApiSellerGrantVault.class);
        var sdk=mock(SpApiOfficialSdkFactory.class);
        var downloader=mock(SignedReportDocumentDownloader.class);
        var importer=mock(SettlementReportImportService.class);
        var persist=mock(SpApiSettlementReportsWorker.FencedImport.class);
        when(vault.loadFinancesApproved(tenant,account,site))
            .thenReturn(Optional.of(original),Optional.of(rotated));
        var worker=new SpApiSettlementReportsWorker(sdk,vault,downloader,importer);
        OffsetDateTime now=OffsetDateTime.now();
        // SDK mocked; guard must run before even interpreting the list response.
        assertThrows(SecurityException.class,()->worker.scanPage(tenant,account,site,
            now.minusDays(1),now,null,0,2,persist));
        verify(sdk).executeObservedRead(eq(original),eq(SpApiOperation.GET_REPORTS),
            eq(ReportsApi.class),any());
        verifyNoMoreInteractions(sdk);
        verifyNoInteractions(downloader,importer,persist);
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
