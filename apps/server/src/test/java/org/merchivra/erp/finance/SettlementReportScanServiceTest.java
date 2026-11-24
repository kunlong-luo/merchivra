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
            SpApiRegion.NA,"synthetic-old-grant",roles,1L,2L);
        var unchanged=new SpApiSellerScope(tenant,account,"A1TESTSELLER",site,
            SpApiRegion.NA,"synthetic-old-grant",roles,1L,2L);
        var rotated=new SpApiSellerScope(tenant,account,"A1TESTSELLER",site,
            SpApiRegion.NA,"synthetic-new-grant",roles,3L,2L);
        var otherSite=new SpApiSellerScope(tenant,account,"A1TESTSELLER","A1OTHER",
            SpApiRegion.NA,"synthetic-old-grant",roles,1L,2L);
        var noFinance=new SpApiSellerScope(tenant,account,"A1TESTSELLER",site,
            SpApiRegion.NA,"synthetic-old-grant",Set.of(),1L,2L);
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
        var aba=new SpApiSellerScope(tenant,account,"A1TESTSELLER",site,
            SpApiRegion.NA,"synthetic-old-grant",roles,4L,2L);
        var roleAba=new SpApiSellerScope(tenant,account,"A1TESTSELLER",site,
            SpApiRegion.NA,"synthetic-old-grant",roles,1L,5L);
        assertThrows(SecurityException.class,
            ()->SpApiSettlementReportsWorker.requireUnchangedFinanceScope(original,aba));
        assertThrows(SecurityException.class,
            ()->SpApiSettlementReportsWorker.requireUnchangedFinanceScope(original,roleAba));
        assertTrue(SettlementReportScanService.revisionsMatch(original,1L,2L));
        assertFalse(SettlementReportScanService.revisionsMatch(original,null,null));
        assertFalse(SettlementReportScanService.revisionsMatch(aba,1L,2L));
        assertFalse(SettlementReportScanService.revisionsMatch(roleAba,1L,2L));
    }

    @Test
    void reportListResponseCannotProceedAfterObservedGrantRotation() throws Exception {
        UUID tenant=UUID.randomUUID(),account=UUID.randomUUID();
        String site="ATVPDKIKX0DER";
        var roles=Set.of("Finance and Accounting");
        var original=new SpApiSellerScope(tenant,account,"A1TESTSELLER",site,
            SpApiRegion.NA,"synthetic-old-grant",roles,1L,2L);
        var rotated=new SpApiSellerScope(tenant,account,"A1TESTSELLER",site,
            SpApiRegion.NA,"synthetic-new-grant",roles,3L,2L);
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
        assertThrows(SecurityException.class,()->worker.scanPage(original,tenant,account,site,
            now.minusDays(1),now,null,0,2,persist));
        verify(sdk).executeObservedRead(eq(original),eq(SpApiOperation.GET_REPORTS),
            eq(ReportsApi.class),any());
        verifyNoMoreInteractions(sdk);
        verifyNoInteractions(downloader,importer,persist);
    }

    @Test
    void authorizationFailureClearsCursorWithoutRetryButTransientErrorsPreserveIt() {
        var auth=new SettlementReportScanService.FailurePolicy("AUTHORIZATION_REJECTED",false,true);
        var stale=new SettlementReportScanService.FailurePolicy("CURSOR_RESTART",true,true);
        var retry=new SettlementReportScanService.FailurePolicy("UPSTREAM_RETRY",true,false);
        var invalid=new SettlementReportScanService.FailurePolicy("SOURCE_VALIDATION_FAILED",false,false);
        assertEquals(auth,SettlementReportScanService.failurePolicy(new SecurityException(),0,true));
        assertEquals(auth,SettlementReportScanService.failurePolicy(new IllegalStateException(),401,false));
        assertEquals(auth,SettlementReportScanService.failurePolicy(new IllegalStateException(),403,true));
        assertEquals(stale,SettlementReportScanService.failurePolicy(new IllegalStateException(),400,true));
        assertEquals(stale,SettlementReportScanService.failurePolicy(new IllegalStateException(),422,true));
        assertEquals(invalid,SettlementReportScanService.failurePolicy(new IllegalStateException(),400,false));
        assertEquals(retry,SettlementReportScanService.failurePolicy(new java.io.IOException(),0,true));
        assertEquals(retry,SettlementReportScanService.failurePolicy(new IllegalStateException(),429,true));
        assertEquals(retry,SettlementReportScanService.failurePolicy(new IllegalStateException(),503,true));
    }

    @Test
    void reportWorkerPageValidatesUnboundedInputWithoutNetworkOrDb(){
        var worker=new SpApiSettlementReportsWorker(null,null,null,null);
        assertThrows(IllegalArgumentException.class,()->worker.scanPage(
            null,UUID.randomUUID(),UUID.randomUUID(),"ATVPDKIKX0DER",
            java.time.OffsetDateTime.now().minusDays(15),java.time.OffsetDateTime.now(),
            null,0,2,(scope,report,doc,body,gzip)->{}));
        assertThrows(IllegalArgumentException.class,()->worker.scanPage(
            null,UUID.randomUUID(),UUID.randomUUID(),"ATVPDKIKX0DER",
            java.time.OffsetDateTime.now().minusDays(1),java.time.OffsetDateTime.now(),
            null,1001,2,(scope,report,doc,body,gzip)->{}));
        assertThrows(IllegalArgumentException.class,()->worker.scanPage(
            null,UUID.randomUUID(),UUID.randomUUID(),"ATVPDKIKX0DER",
            java.time.OffsetDateTime.now().minusDays(1),java.time.OffsetDateTime.now(),
            null,0,3,(scope,report,doc,body,gzip)->{}));
    }
}
