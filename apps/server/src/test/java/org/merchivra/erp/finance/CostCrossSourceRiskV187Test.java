package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CostCrossSourceRiskV187Test {
    private static final UUID MP=UUID.randomUUID();
    private static final LocalDate START=LocalDate.parse("2026-11-01"),
        END=LocalDate.parse("2026-12-01");
    private static CostCrossSourceRiskServiceV187.Flags row(int which,boolean shared) {
        var flags=new ArrayList<Boolean>();
        for(int i=0;i<8;i++)flags.add(i==which);
        return new CostCrossSourceRiskServiceV187.Flags(UUID.randomUUID(),
            START.plusDays(1),List.copyOf(flags),shared);
    }
    private static CostCrossSourceRiskServiceV187.Report summarize(
        CostCrossSourceRiskServiceV187.Counts c,
        List<CostCrossSourceRiskServiceV187.Flags> rows) {
        return CostCrossSourceRiskServiceV187.summarize(MP,START,END,c,rows);
    }
    private static void neverVerify(CostCrossSourceRiskServiceV187.Report r) {
        assertFalse(r.duplicateDebitVerified());
        assertFalse(r.externalChargeVerified());
        assertFalse(r.refundCogsReversalVerified());
        assertFalse(r.accountingPolicyApproved());
        assertFalse(r.bankReconciled());
        assertFalse(r.netProfitEligible());
        assertNull(r.realizedNetProfit());
        assertTrue(r.blockerCodes().contains("NO_VERIFIED_DUPLICATE_DEBIT_ADJUDICATION"));
    }

    @Test void overlappingEightSignalsAndSharedLineCountUniqueSourcesNotFlags() {
        var a=row(0,true);
        var b=row(1,false);
        var report=summarize(new CostCrossSourceRiskServiceV187.Counts(
            3,2,1,2,1),List.of(a,b));
        assertEquals(3,report.sourceCount());
        assertEquals(2,report.anyLocalCollisionSourceCount());
        assertEquals(2,report.potentialIdentityOverlapSourceCount());
        assertEquals(1,report.sharedSettlementLineSourceCount());
        assertEquals(1,report.noLocalMatchSourceCount());
        assertEquals("OTHER_MANUAL_COST_SAME_CLAIMED_DOCUMENT",
            report.examples().getFirst().localSignalCodes().getFirst());
        assertTrue(report.examples().getFirst().sharedSettlementLineClaim());
        assertFalse(report.examplesTruncated());
        assertTrue(report.blockerCodes().contains("V165_CROSS_SOURCE_IDENTITY_OVERLAP_HOLD"));
        assertTrue(report.blockerCodes().contains("V168_SHARED_SETTLEMENT_LINE_HOLD"));
        assertFalse(report.examples().getFirst().duplicateDebitVerified());
        assertFalse(report.examples().getFirst().expenseDeductionAllowed());
        neverVerify(report);
    }

    @Test void absentLocalMatchAlwaysRemainsUnverifiedEvenIfNoSources() {
        for(var count:List.of(0L,7L)) {
            var r=summarize(new CostCrossSourceRiskServiceV187.Counts(
                count,0,0,0,count),List.of());
            assertEquals(count,r.sourceCount());
            assertTrue(r.examples().isEmpty());
            assertTrue(r.blockerCodes().contains(
                "ABSENCE_OF_LOCAL_MATCH_DOES_NOT_PROVE_NO_DUPLICATES"));
            neverVerify(r);
        }
    }

    @Test void justSharedSettlementLineProducesRiskWithoutExactIdentity() {
        var r=summarize(new CostCrossSourceRiskServiceV187.Counts(
            1,0,1,1,0),List.of(row(-1,true)));
        assertTrue(r.examples().getFirst().localSignalCodes().isEmpty());
        assertEquals(1,r.sharedSettlementLineSourceCount());
        assertTrue(r.blockerCodes().contains("V168_SHARED_SETTLEMENT_LINE_HOLD"));
        neverVerify(r);
    }

    @Test void sampleCapCannotTruncateFullPeriodCounts() {
        var rows=new ArrayList<CostCrossSourceRiskServiceV187.Flags>();
        for(int i=0;i<21;i++)rows.add(row(3,false));
        var r=summarize(new CostCrossSourceRiskServiceV187.Counts(
            1200,540,0,540,660),rows);
        assertEquals(1200,r.sourceCount());
        assertEquals(540,r.potentialIdentityOverlapSourceCount());
        assertEquals(20,r.examples().size());
        assertTrue(r.examplesTruncated());
        neverVerify(r);
    }

    @Test void badOrContradictoryCountsAndUnknownSampleFailClosed() {
        var one=row(0,false);
        var invalid=List.of(
            new CostCrossSourceRiskServiceV187.Counts(1,2,0,2,0),
            new CostCrossSourceRiskServiceV187.Counts(1,0,2,2,0),
            new CostCrossSourceRiskServiceV187.Counts(1,0,0,0,0),
            new CostCrossSourceRiskServiceV187.Counts(1,0,0,0,2),
            new CostCrossSourceRiskServiceV187.Counts(-1,0,0,0,0));
        for(var c:invalid)
            assertEquals(HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class,()->
                    summarize(c,List.of())).getStatusCode());
        assertThrows(ResponseStatusException.class,()->
            summarize(new CostCrossSourceRiskServiceV187.Counts(1,1,0,1,0),
                List.of()));
        var noSignal=new CostCrossSourceRiskServiceV187.Flags(
            UUID.randomUUID(),START.plusDays(2),List.of(
                false,false,false,false,false,false,false,false),false);
        assertThrows(ResponseStatusException.class,()->
            summarize(new CostCrossSourceRiskServiceV187.Counts(1,1,0,1,0),
                List.of(noSignal)));
        assertThrows(ResponseStatusException.class,()->
            summarize(new CostCrossSourceRiskServiceV187.Counts(2,1,0,1,1),
                List.of(one,one)));
    }

    @Test void sourceDateMustStayWithinHalfOpenBusinessPeriod() {
        assertThrows(ResponseStatusException.class,()->
            CostCrossSourceRiskServiceV187.summarize(MP,START,END,
                new CostCrossSourceRiskServiceV187.Counts(1,1,0,1,0),
                List.of(new CostCrossSourceRiskServiceV187.Flags(UUID.randomUUID(),
                    END,row(0,false).signals(),false))));
        assertThrows(ResponseStatusException.class,()->
            CostCrossSourceRiskServiceV187.summarize(MP,END,START,
                new CostCrossSourceRiskServiceV187.Counts(0,0,0,0,0),
                List.of()));
    }

    @Test void ownerSessionNoStoreAndScoped404BeforeCollisionQuery() {
        var access=mock(TenantAccessService.class);
        var svc=mock(CostCrossSourceRiskServiceV187.class);
        var req=mock(HttpServletRequest.class);
        var auth=mock(Authentication.class);
        UUID tenant=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        var response=new CostCrossSourceRiskControllerV187(access,svc)
            .report(auth,req,MP,START,END);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(svc).report(tenant,MP,START,END);

        var jdbc=mock(JdbcTemplate.class);
        var service=new CostCrossSourceRiskServiceV187(jdbc);
        var ex=assertThrows(ResponseStatusException.class,()->
            service.report(tenant,MP,START,END));
        assertEquals(HttpStatus.NOT_FOUND,ex.getStatusCode());
        verify(jdbc,times(1)).query(contains("FROM seller_marketplace m"),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq(tenant),eq(MP));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void sqlReusesV165EightSignalsWithoutUnboundedSampleOrFinancialWrite()
        throws Exception {
        String service=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CostCrossSourceRiskServiceV187.java"));
        for(var condition:List.of("c.source_document_sha256=s.source_document_sha256",
            "a.source_key=s.source_key","a.report_reference=s.source_key",
            "a.report_sha256=s.source_document_sha256",
            "f.source_family='SPAPI_FINANCES'",
            "r.amazon_report_id=s.source_key",
            "r.document_sha256=s.source_document_sha256",
            "t.statement_ref=s.source_key",
            "y.report_id=x.report_id AND y.line_number=x.line_number",
            "y.source_id<>x.source_id","s.business_date>=? AND s.business_date<?",
            "count(*) FILTER(WHERE identity_overlap OR shared_line)",
            "ORDER BY business_date DESC,id DESC LIMIT 21",
            "Isolation.REPEATABLE_READ","JOIN tenant t ON t.id=s.tenant_id",
            "s.seller_marketplace_id=?"))assertTrue(service.contains(condition),condition);
        assertFalse(service.contains("UPDATE finance_"));
        assertFalse(service.contains("INSERT INTO finance_"));
        assertFalse(service.contains("SPAPI_OAUTH_ENABLED=true"));
    }
}
