package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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

class PeriodProfitWaterfallV188Test {
    private static final UUID MP=UUID.randomUUID(), JOB=UUID.randomUUID();
    private static final String START="2026-11-01", END="2026-12-01";
    private static ProfitReportJobService.Job job(String state,int total,int done,
        int eligible,int blocked) {
        return new ProfitReportJobService.Job(JOB.toString(),state,
            START,END,"USD",total,done,eligible,blocked,
            0,null,"0.000000",0,"2026-11-01T00:00:00Z",
            "SUCCEEDED".equals(state)?"2026-12-01T00:00:00Z":null,
            true,false,false,false,false);
    }
    private static PeriodProfitWaterfallV188.FrozenAmounts frozen(
        long eligible,long blocked,long pending) {
        return new PeriodProfitWaterfallV188.FrozenAmounts(
            eligible,blocked,pending,0,0,
            eligible>0?"100.000000":"0",
            eligible>0?"-10.000000":"0",
            eligible>0?"-5.000000":"0",
            eligible>0?"40.000000":"0",
            eligible>0?"45.000000":"0");
    }
    private static CostOwnershipBridgeServiceV186.Bucket cost(
        String currency,String kind,long approved,long pending,long unassigned,
        String claim,String allocated,String remainder) {
        return new CostOwnershipBridgeServiceV186.Bucket(
            "INBOUND_FREIGHT",currency,kind,approved+pending+unassigned,
            approved,pending,unassigned,0,claim,allocated,remainder,
            false,false,false);
    }
    private static CostOwnershipBridgeServiceV186.Report owners(
        List<CostOwnershipBridgeServiceV186.Bucket> buckets) {
        return new CostOwnershipBridgeServiceV186.Report(
            MP.toString(),START,END,
            "V91_SOURCE_BUSINESS_DATE_NOT_ACCOUNTING_RECOGNITION_DATE",
            buckets.stream().mapToLong(
                CostOwnershipBridgeServiceV186.Bucket::sourceCount).sum(),
            buckets,List.of("ADS_SPEND","RETURN_HANDLING"),List.of(),
            false,false,false,false,false,false,false,null);
    }
    private static CostCrossSourceRiskServiceV187.Report risks(
        long sources,long collision) {
        return new CostCrossSourceRiskServiceV187.Report(
            MP.toString(),START,END,
            "V91_SOURCE_BUSINESS_DATE_NOT_ACCOUNTING_RECOGNITION_DATE",
            sources,collision,0,collision,sources-collision,
            List.of(),false,List.of("INDEPENDENT_EXTERNAL_EXPENSE_AUTHENTICITY_MISSING"),
            false,false,false,false,false,false,null);
    }
    private static PeriodProfitWaterfallV188.Preview calc(
        ProfitReportJobService.Job job,PeriodProfitWaterfallV188.FrozenAmounts amount,
        List<CostOwnershipBridgeServiceV186.Bucket> buckets,long risk) {
        long sources=buckets.stream().mapToLong(
            CostOwnershipBridgeServiceV186.Bucket::sourceCount).sum();
        return PeriodProfitWaterfallV188.calculate(MP,job,amount,
            owners(buckets),risks(sources,risk));
    }
    private static void neverRecognize(PeriodProfitWaterfallV188.Preview p) {
        assertTrue(p.financeFeesExcludedFromAdditionalCosts());
        assertFalse(p.additionalCostExternallyVerified());
        assertFalse(p.accountingPolicyApproved());
        assertFalse(p.refundCogsReversalVerified());
        assertFalse(p.fxConversionApproved());
        assertFalse(p.bankSettlementVerified());
        assertFalse(p.ledgerPosted());
        assertFalse(p.realizedNetProfitVerified());
        assertNull(p.realizedNetProfit());
        assertTrue(p.blockerCodes().contains("REFUND_COGS_REVERSAL_NOT_VERIFIED"));
        assertTrue(p.blockerCodes().contains(
            "V64_BATCH_FROZEN_V91_V92_V165_V168_EVIDENCE_LIVE"));
    }

    @Test void fullCostWaterfallIsArithmeticSensitivityNotRecognizedNetProfit() {
        var p=calc(job("SUCCEEDED",1,1,1,0),frozen(1,0,0),
            List.of(cost("USD","PERIOD",1,0,0,
                "12.000001","12.000001","0.000000")),0);
        assertEquals("V188_V64_ELIGIBLE_CONTRIBUTION_MINUS_V92_APPROVED_UNVERIFIED_ORIGINAL_CURRENCY_V1",
            p.ruleVersion());
        assertEquals("45.000000",p.eligibleContribution());
        assertEquals("100.000000",p.eligibleSignedSales());
        assertEquals("-10.000000",p.eligibleSignedAmazonFees());
        assertEquals("-5.000000",p.eligibleSignedFbaFees());
        assertEquals("40.000000",p.eligibleProvisionalCogs());
        assertEquals("12.000001",p.approvedUnverifiedLocalCostExposure());
        assertEquals("32.999999",p.unverifiedCostSensitivityContribution());
        assertEquals("UNVERIFIED_COST_SENSITIVITY_ONLY",p.sensitivityState());
        assertEquals(1,p.reviewedCostSourceCount());
        assertEquals(1,p.costLines().size());
        neverRecognize(p);
    }

    @Test void aV64BlockedOrderCannotBeDisguisedAsWholePeriodProfit() {
        var p=calc(job("SUCCEEDED",2,2,1,1),frozen(1,1,0),
            List.of(cost("USD","ORDER",1,0,0,"5.00","5.00","0.00")),0);
        assertEquals("45.000000",p.eligibleContribution()); // eligible SUBSET only
        assertEquals("ORDER_FINANCIAL_BASELINE_INCOMPLETE",p.sensitivityState());
        assertNull(p.unverifiedCostSensitivityContribution());
        neverRecognize(p);
    }
    @Test void unfinishedJobAndUnprocessedOrdersNeverCreateWholePeriodSensitivity() {
        var p=calc(job("READY",2,1,1,0),frozen(1,0,1),
            List.of(cost("USD","PERIOD",1,0,0,"2","2","0")),0);
        assertEquals("ORDER_FINANCIAL_BASELINE_INCOMPLETE",p.sensitivityState());
        assertEquals(1,p.eligibleOrders());
        assertNull(p.approvedUnverifiedLocalCostExposure());
        neverRecognize(p);
    }

    @Test void zeroOrdersOrUnknownExpenseIsNotZeroRealCost() {
        var noOrders=calc(job("SUCCEEDED",0,0,0,0),frozen(0,0,0),
            List.of(),0);
        assertNull(noOrders.eligibleContribution());
        assertNull(noOrders.unverifiedCostSensitivityContribution());
        neverRecognize(noOrders);
        var noExpense=calc(job("SUCCEEDED",1,1,1,0),frozen(1,0,0),
            List.of(),0);
        assertEquals("ADDITIONAL_COST_SOURCES_UNKNOWN",noExpense.sensitivityState());
        assertNull(noExpense.approvedUnverifiedLocalCostExposure());
        assertNull(noExpense.unverifiedCostSensitivityContribution());
        neverRecognize(noExpense);
    }

    @Test void uniqueSourceCannotBeDeductedAgainWithPendingOrUnallocatedBatches() {
        var cases=List.of(
            cost("USD","SKU",0,1,0,"9",null,null),
            cost("USD","UNASSIGNED",0,0,1,"9",null,null),
            cost("USD","ORDER",1,0,0,"9","7","2"));
        for(var c:cases) {
            var p=calc(job("SUCCEEDED",1,1,1,0),frozen(1,0,0),
                List.of(c),0);
            assertEquals("V92_UNVERIFIED_COST_DISTRIBUTION_INCOMPLETE",
                p.sensitivityState());
            assertNull(p.unverifiedCostSensitivityContribution());
            assertEquals("9.000000",p.costLines().getFirst().claimedAmount());
            neverRecognize(p);
        }
    }

    @Test void localCollisionAndOtherCurrenciesBlockProfitProjection() {
        var p=calc(job("SUCCEEDED",1,1,1,0),frozen(1,0,0),
            List.of(cost("USD","PERIOD",1,0,0,"8","8","0")),1);
        assertEquals("CROSS_SOURCE_COLLISION_BLOCKED",p.sensitivityState());
        assertNull(p.unverifiedCostSensitivityContribution());
        neverRecognize(p);
        var fx=calc(job("SUCCEEDED",1,1,1,0),frozen(1,0,0),
            List.of(cost("EUR","PERIOD",1,0,0,"8","8","0")),0);
        assertEquals("MIXED_ORIGINAL_CURRENCY_BLOCKED",fx.sensitivityState());
        assertEquals("EUR",fx.costLines().getFirst().currency());
        assertNull(fx.unverifiedCostSensitivityContribution());
        neverRecognize(fx);
    }

    @Test void tamperedV64FeeArithmeticAndSourceCountsFailClosed() {
        var source=cost("USD","SKU",1,0,0,"5","5","0");
        var amounts=new PeriodProfitWaterfallV188.FrozenAmounts(
            1,0,0,0,0,"100","-10","-5","40","50");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->calc(job("SUCCEEDED",1,1,1,0),amounts,List.of(source),0))
            .getStatusCode());
        var wrongCurrency=new PeriodProfitWaterfallV188.FrozenAmounts(
            1,0,0,1,0,"100","-10","-5","40","45");
        assertThrows(ResponseStatusException.class,()->
            calc(job("SUCCEEDED",1,1,1,0),wrongCurrency,List.of(source),0));
        var invalidCost=cost("USD","SKU",1,0,0,"5","6","0");
        assertThrows(ResponseStatusException.class,()->
            calc(job("SUCCEEDED",1,1,1,0),frozen(1,0,0),
                List.of(invalidCost),0));
        assertThrows(ResponseStatusException.class,()->
            PeriodProfitWaterfallV188.calculate(MP,job("SUCCEEDED",1,1,1,0),
                frozen(1,0,0),owners(List.of(source)),risks(0,0)));
    }

    @Test void ownerOnlyScopeAndNoStoreSession() {
        var access=mock(TenantAccessService.class);
        var svc=mock(PeriodProfitWaterfallServiceV188.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        var result=new PeriodProfitWaterfallControllerV188(access,svc)
            .preview(auth,req,MP,JOB);
        assertTrue(result.getHeaders().getCacheControl().contains("no-store"));
        verify(svc).preview(tenant,MP,JOB);
        var jdbc=mock(JdbcTemplate.class);
        var reports=mock(ProfitReportJobService.class);
        var service=new PeriodProfitWaterfallServiceV188(jdbc,reports,
            mock(CostOwnershipBridgeServiceV186.class),
            mock(CostCrossSourceRiskServiceV187.class));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.preview(tenant,MP,null)).getStatusCode());
        verifyNoInteractions(jdbc,reports);
    }

    @Test void scopedFrozenSqlIsIndependentOfV64OrderPageAndDoesNotPostProfit()
        throws Exception {
        var code=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/PeriodProfitWaterfallServiceV188.java"));
        for(String expected:List.of(
            "Isolation.REPEATABLE_READ","reports.status(tenant,marketplace,reportJobId)",
            "finance_profit_report_order","tenant_id=? AND job_id=?",
            "FILTER(WHERE state='ELIGIBLE')",
            "wrong_currency_count","missing_amount_count",
            "owners.report(tenant,marketplace,start,end)",
            "risks.report(tenant,marketplace,start,end)"))
            assertTrue(code.contains(expected),expected);
        assertFalse(code.contains("INSERT INTO"));
        assertFalse(code.contains("UPDATE finance_"));
        assertFalse(code.contains("DELETE FROM"));
    }
}
