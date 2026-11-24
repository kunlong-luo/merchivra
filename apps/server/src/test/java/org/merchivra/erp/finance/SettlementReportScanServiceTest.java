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


    /**
     * Real Java service, vault and PG18 transactions; only the remote worker is
     * mocked. The fixture is deliberately restricted to the disposable A3 job.
     * No Amazon network, production grants or accounting approval.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named="MERCHIVRA_V236_DISPOSABLE_ONLY",matches="true")
    void actualPg18OwnerRestartAndFencedImportRejectStaleAuthorization() throws Exception {
        var required=java.util.Map.ofEntries(
            java.util.Map.entry("GITHUB_ACTIONS","true"),
            java.util.Map.entry("GITHUB_REPOSITORY","kunlong-luo/merchivra"),
            java.util.Map.entry("GITHUB_REF","refs/heads/main"),
            java.util.Map.entry("MERCHIVRA_SPAPI_ENABLED","false"),
            java.util.Map.entry("MERCHIVRA_SPAPI_OAUTH_ENABLED","false"),
            java.util.Map.entry("DB_HOST","127.0.0.1"),
            java.util.Map.entry("DB_PORT","5432"),
            java.util.Map.entry("DB_NAME","merchivra"),
            java.util.Map.entry("DB_USER","merchivra"),
            java.util.Map.entry("DB_PASSWORD","ci_disposable_database_password"),
            java.util.Map.entry("MERCHIVRA_BOOTSTRAP_USERNAME","v65-ci-owner"));
        required.forEach((k,v)->assertTrue(v.equals(System.getenv(k)),"Disposable CI required: "+k));
        assertTrue(System.getenv().getOrDefault("GITHUB_RUN_ID","").matches("[0-9]+"));
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:postgresql://127.0.0.1:5432/merchivra","merchivra","ci_disposable_database_password");
        var db=new JdbcTemplate(ds);
        int major=Integer.parseInt(db.queryForObject("SHOW server_version_num",String.class));
        assertTrue(major>=180000&&major<190000);
        assertEquals(1,db.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version='116' AND success",Integer.class));
        var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        var cipher=new org.merchivra.erp.spapi.SellerTokenCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]),1);
        UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),reviewer=UUID.randomUUID(),
            account=UUID.randomUUID(),market=UUID.randomUUID(),job=UUID.randomUUID();
        String seller="V236-SYNTHETIC-"+account,site="V236-"+market;
        var sealed=cipher.encrypt(tenant,account,seller,"synthetic-v236-refresh-only");
        tx.executeWithoutResult(s->{
            db.update("INSERT INTO tenant(id,name) VALUES (?,?)",tenant,"V236 DISPOSABLE");
            for(UUID user:java.util.List.of(actor,reviewer)) {
                db.update("INSERT INTO app_user(id,identity_ref) VALUES (?,?)",user,"local:v236:"+user);
                db.update("INSERT INTO membership(tenant_id,user_id) VALUES (?,?)",tenant,user);
                db.update("INSERT INTO role_binding(id,tenant_id,user_id,role_code) VALUES (?,?,?,'OWNER')",
                    UUID.randomUUID(),tenant,user);
            }
            db.update("INSERT INTO role_binding(id,tenant_id,user_id,role_code) VALUES (?,?,?,'SECURITY_REVIEWER')",
                UUID.randomUUID(),tenant,reviewer);
            db.update("""
                INSERT INTO seller_account(id,tenant_id,seller_id,region,connection_status)
                VALUES (?,?,?,'NA','CONNECTED')
                """,account,tenant,seller);
            db.update("""
                INSERT INTO seller_marketplace(id,tenant_id,seller_account_id,marketplace_id,currency_code,timezone)
                VALUES (?,?,?,?,'USD','Etc/UTC')
                """,market,tenant,account,site);
            db.update("""
                INSERT INTO spapi_seller_authorization(id,tenant_id,seller_account_id,seller_id,region,status,
                    ciphertext,nonce,key_version,verified_at)
                VALUES (?,?,?,?,'NA','VERIFIED',?,?,?,now())
                """,UUID.randomUUID(),tenant,account,seller,sealed.ciphertext(),sealed.nonce(),sealed.version());
            db.update("""
                INSERT INTO spapi_marketplace_verification(tenant_id,seller_account_id,marketplace_id,verified_at)
                VALUES (?,?,?,now())
                """,tenant,account,site);
            db.update("""
                INSERT INTO spapi_finances_role_evidence(tenant_id,seller_account_id,role_code,evidence_kind,
                    evidence_sha256,grant_verified_at,reviewed_by,reviewed_at,expires_at)
                SELECT tenant_id,seller_account_id,'Finance and Accounting','EXTERNAL_APP_ROLE_REVIEW',
                    repeat('a',64),verified_at,?,now(),now()+interval '1 day'
                FROM spapi_seller_authorization WHERE tenant_id=? AND seller_account_id=?
                """,reviewer,tenant,account);
            db.update("""
                INSERT INTO spapi_settlement_scan_job(id,tenant_id,seller_marketplace_id,seller_account_id,
                    created_by,window_after,window_before,next_token,token_issued_at,row_offset)
                VALUES (?,?,?,?,?,now()-interval '2 days',now()-interval '1 day','legacy-cursor',now(),1)
                """,job,tenant,market,account,actor);
        });
        var vault=new SpApiSellerGrantVault(db,cipher);
        var worker=mock(SpApiSettlementReportsWorker.class);
        var live=new SettlementReportScanService(db,tx,vault,worker,Clock.systemUTC());
        var first=vault.loadFinancesApproved(tenant,account,site).orElseThrow();
        assertTrue(first.hasReportsRevisions());
        var paused=live.step(tenant,market);
        assertEquals("PAUSED",paused.state());
        assertEquals("AUTHORIZATION_REVISION_CHANGED",paused.lastFailureCode());
        assertEquals(0,paused.rowOffset());
        verifyNoInteractions(worker);
        assertEquals(Boolean.TRUE,db.queryForObject("""
            SELECT next_token IS NULL AND token_issued_at IS NULL AND report_grant_revision IS NULL
            FROM spapi_settlement_scan_job WHERE id=?
            """,Boolean.class,job));
        assertEquals("READY",live.start(tenant,market,actor).state());
        assertEquals(first.grantRevision(),db.queryForObject(
            "SELECT report_grant_revision FROM spapi_settlement_scan_job WHERE id=?",Long.class,job));
        when(worker.scanPage(eq(first),eq(tenant),eq(account),eq(site),any(),any(),
            isNull(),eq(0),eq(2),any())).thenReturn(
                new SpApiSettlementReportsWorker.Page("v236-next",0,0,0,0,0,false));
        assertEquals("READY",live.step(tenant,market).state());
        assertEquals("v236-next",db.queryForObject(
            "SELECT next_token FROM spapi_settlement_scan_job WHERE id=?",String.class,job));
        // Same credential, changed persisted revision: must never reuse cursor.
        db.update("UPDATE spapi_seller_authorization SET status=status WHERE tenant_id=? AND seller_account_id=?",
            tenant,account);
        assertEquals("PAUSED",live.step(tenant,market).state());
        verify(worker,times(1)).scanPage(any(),any(),any(),any(),any(),any(),nullable(String.class),
            anyInt(),anyInt(),any());
        assertEquals("READY",live.start(tenant,market,actor).state());
        var second=vault.loadFinancesApproved(tenant,account,site).orElseThrow();
        assertNotEquals(first.grantRevision(),second.grantRevision());
        byte[] body=("settlement-id\ttransaction-type\tamount\tcurrency\n"
            +"V236-SETTLE-0001\tOrder\t1.00\tUSD\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(worker.scanPage(eq(second),eq(tenant),eq(account),eq(site),any(),any(),
            isNull(),eq(0),eq(2),any())).thenAnswer(call->{
                // Commit role change outside fenced import transaction.
                db.update("UPDATE spapi_finances_role_evidence SET expires_at=expires_at WHERE tenant_id=? AND seller_account_id=?",
                    tenant,account);
                SpApiSettlementReportsWorker.FencedImport persist=call.getArgument(9);
                persist.persist(second,"V236-OLD-REPORT","V236-OLD-DOC",body,false);
                fail("Stale scope imported");
                return null;
            });
        var rejected=live.step(tenant,market);
        assertEquals("PAUSED",rejected.state());
        assertTrue(rejected.lastFailureCode().contains("AUTHORIZATION_REJECTED"));
        assertEquals(0,db.queryForObject(
            "SELECT count(*) FROM spapi_settlement_report WHERE tenant_id=?",Integer.class,tenant));
        assertEquals("READY",live.start(tenant,market,actor).state());
        var third=vault.loadFinancesApproved(tenant,account,site).orElseThrow();
        assertNotEquals(second.financeRoleRevision(),third.financeRoleRevision());
        when(worker.scanPage(eq(third),eq(tenant),eq(account),eq(site),any(),any(),
            isNull(),eq(0),eq(2),any())).thenAnswer(call->{
                SpApiSettlementReportsWorker.FencedImport persist=call.getArgument(9);
                persist.persist(third,"V236-NEW-REPORT","V236-NEW-DOC",body,false);
                return new SpApiSettlementReportsWorker.Page(null,0,1,1,0,0,true);
            });
        assertEquals("SUCCEEDED",live.step(tenant,market).state());
        var report=db.queryForMap("""
            SELECT report_grant_revision,report_role_revision,document_sha256,line_count
            FROM spapi_settlement_report WHERE tenant_id=? AND seller_marketplace_id=?
            """,tenant,market);
        assertEquals(third.grantRevision(),report.get("report_grant_revision"));
        assertEquals(third.financeRoleRevision(),report.get("report_role_revision"));
        assertEquals(1,report.get("line_count"));
        assertEquals(java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(body)),
            report.get("document_sha256").toString().strip());
        // Fixture exists only inside isolated disposable service DB.
        System.out.println("PASS V236 real PG18 Java Reports start/step/fenced import; synthetic remote worker only");
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
