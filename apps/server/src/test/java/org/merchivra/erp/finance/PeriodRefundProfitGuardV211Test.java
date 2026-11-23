package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** V211: distinguish refund posting date from V64 order date and report snapshots. */
class PeriodRefundProfitGuardV211Test {
    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-4000-8000-"
            +String.format("%012x",n));
    }
    private static final UUID MP=id(211),JOB=id(212),A=id(213),
        B=id(214),OLD_JOB=id(215),E1=id(216),E2=id(217),E3=id(218);

    private static PeriodProfitWaterfallV188.Preview baseline() {
        var p=mock(PeriodProfitWaterfallV188.Preview.class);
        when(p.marketplaceId()).thenReturn(MP.toString());
        when(p.reportJobId()).thenReturn(JOB.toString());
        when(p.periodStart()).thenReturn("2026-11-01");
        when(p.periodEndExclusive()).thenReturn("2026-12-01");
        when(p.blockerCodes()).thenReturn(List.of("REFUND_COGS_REVERSAL_NOT_VERIFIED"));
        when(p.eligibleContribution()).thenReturn("45.000000");
        when(p.unverifiedCostSensitivityContribution()).thenReturn("32.999999");
        return p;
    }

    private static RefundOrderConservationEngineV210.Source saleRefund(
        UUID order,UUID event,String date,String currency,String signed) {
        return new RefundOrderConservationEngineV210.Source(event,order,
            Instant.parse(date),currency,new BigDecimal(signed));
    }
    private static RefundRecoveryService.Status reviewed(
        UUID order,UUID event,String currency,String signed,int qty) {
        BigDecimal exposure=new BigDecimal("8").multiply(BigDecimal.valueOf(qty));
        var item=new RefundRecoveryEngine.Item("ITEM-1","SKU-1",qty,
            "RESTOCKED_CLAIMED",signed,exposure.toPlainString(),
            exposure.toPlainString());
        var money=new RefundRecoveryEngine.Preview(
            "MANUAL_EVIDENCE_BALANCED_UNVERIFIED",currency,signed,signed,
            "0",exposure.toPlainString(),exposure.toPlainString(),
            null,false,false,List.of(item));
        return new RefundRecoveryService.Status(event.toString(),
            order.toString(),"REVIEWED",1,"MANUAL_UNVERIFIED","MAKER","CHECKER",
            true,false,false,money);
    }

    private static RefundOrderConservationEngineV210.Result order(UUID order,
        RefundOrderConservationEngineV210.Source... refunds) {
        var reviews=new java.util.ArrayList<RefundOrderConservationEngineV210.ReviewItem>();
        var probes=new java.util.ArrayList<RefundOrderConservationEngineV210.Probe>();
        for(var refund:refunds) {
            reviews.add(new RefundOrderConservationEngineV210.ReviewItem(
                refund.eventId(),"ITEM-1",1,"RESTOCKED_CLAIMED"));
            probes.add(new RefundOrderConservationEngineV210.Probe(
                refund,reviewed(order,refund.eventId(),
                    refund.currency(),refund.signedRefund().toPlainString(),1)));
        }
        return RefundOrderConservationEngineV210.calculate(order,
            List.of(new RefundOrderConservationEngineV210.SaleItem("ITEM-1",5)),
            reviews,probes);
    }

    private static PeriodRefundProfitGuardV211.Source inWindow(
        UUID event,UUID order,String date,String currency,String money,
        long links,boolean inCohort) {
        return new PeriodRefundProfitGuardV211.Source(event,order,
            Instant.parse(date),currency,new BigDecimal(money),links,inCohort);
    }

    @Test void twoPeriodsTwoCurrenciesAndRepeatedSnapshotAreNeverExtraProfit() {
        var first=saleRefund(A,E1,"2026-11-10T10:00:00Z","USD","-20");
        var later=saleRefund(A,E2,"2026-12-05T10:00:00Z","USD","-10");
        var other=saleRefund(B,E3,"2026-11-15T15:00:00Z","EUR","-15");
        var result=PeriodRefundProfitGuardV211.calculate(MP,JOB,baseline(),
            List.of(inWindow(E1,A,first.postedAt().toString(),"USD","-20",1,true),
                inWindow(E3,B,other.postedAt().toString(),"EUR","-15",1,true)),
            List.of(A,B),List.of(order(A,first,later),order(B,other)),
            List.of(new PeriodRefundProfitGuardV211.SnapshotOverlap(OLD_JOB,A)));
        assertEquals(2,result.periodPostedSourceEventCount());
        assertEquals(2,result.cohortRefundOrderCount());
        assertEquals(1,result.refundEventsOutsideV64OrderDateWindow());
        assertEquals(1,result.intersectingOtherFrozenReportCount());
        assertEquals(1,result.repeatedFrozenOrderCount());
        assertTrue(result.originalCurrencySourceSnapshotConsistent());
        assertTrue(result.allLocalManualRefundConservationComplete());
        assertEquals(2,result.currencies().size());
        assertEquals("USD",result.currencies().get(0).currency());
        assertEquals("-20",result.currencies().get(0).signedRefundSourceAmount());
        assertEquals("EUR",result.currencies().get(1).currency());
        assertEquals("-15",result.currencies().get(1).signedRefundSourceAmount());
        assertEquals("45.000000",result.v188Baseline().eligibleContribution());
        assertTrue(result.blockerCodes().contains(
            "COHORT_ORDER_REFUNDS_POSTED_OUTSIDE_V64_ORDER_DATE_PERIOD"));
        assertTrue(result.blockerCodes().contains(
            "OVERLAPPING_V64_SNAPSHOTS_ARE_NOT_INDEPENDENT_LEDGER_ENTRIES"));
        assertNull(result.refundAdjustedContribution());
        assertNull(result.realizedNetProfit());
        assertFalse(result.refundRecognitionPeriodPolicyApproved());
        assertFalse(result.independentAmazonRefundCoverageVerified());
        assertFalse(result.doubleJournalDeductionAllowed());
        assertFalse(result.actualRefundCogsVerified());
        assertThrows(UnsupportedOperationException.class,
            ()->result.currencies().clear());
    }

    @Test void orphanAndOutOfCohortRefundSourcesMustNotDisappearFromPeriod() {
        var unlinked=inWindow(E1,null,"2026-11-10T10:00:00Z","USD","-20",0,false);
        var olderOrder=inWindow(E2,id(1999),
            "2026-11-12T10:00:00Z","USD","-8",1,false);
        var result=PeriodRefundProfitGuardV211.calculate(MP,JOB,baseline(),
            List.of(unlinked,olderOrder),List.of(),List.of(),List.of());
        assertEquals(2,result.periodPostedSourceEventCount());
        assertEquals(1,result.unmatchedPeriodSourceCount());
        assertEquals(1,result.periodRefundSourceOutsideFrozenCohortCount());
        assertFalse(result.originalCurrencySourceSnapshotConsistent());
        assertFalse(result.allLocalManualRefundConservationComplete());
        assertEquals("-28",result.currencies().getFirst().signedRefundSourceAmount());
        assertFalse(result.currencies().getFirst().fullyAttributedToV64Cohort());
        assertTrue(result.blockerCodes().contains(
            "UNMATCHED_REFUND_EVENTS_IN_POSTING_PERIOD"));
        assertTrue(result.blockerCodes().contains(
            "PERIOD_REFUNDS_BELONG_TO_ORDERS_OUTSIDE_V64_COHORT"));
        assertNull(result.refundAdjustedContribution());
    }

    @Test void repeatedMatchedLinkCannotMultiplyRefundOrBecomeVerified() {
        var s=saleRefund(A,E1,"2026-11-10T10:00:00Z","USD","-20");
        var result=PeriodRefundProfitGuardV211.calculate(MP,JOB,baseline(),
            List.of(inWindow(E1,A,s.postedAt().toString(),"USD","-20",2,true)),
            List.of(A),List.of(order(A,s)),List.of());
        assertEquals(1,result.multiMatchedPeriodSourceCount());
        assertEquals("-20",result.currencies().getFirst().signedRefundSourceAmount());
        assertFalse(result.originalCurrencySourceSnapshotConsistent());
        assertTrue(result.blockerCodes().contains(
            "MULTIPLY_MATCHED_REFUND_EVENTS_IN_POSTING_PERIOD"));
        assertNull(result.realizedNetProfit());
    }

    @Test void afterSnapshotRefundChangeDisablesWholePeriodEvidence() {
        var current=saleRefund(A,E1,"2026-11-10T10:00:00Z","USD","-21");
        var original=inWindow(E1,A,"2026-11-10T10:00:00Z",
            "USD","-20",1,true);
        var result=PeriodRefundProfitGuardV211.calculate(MP,JOB,baseline(),
            List.of(original),List.of(A),List.of(order(A,current)),List.of());
        assertFalse(result.originalCurrencySourceSnapshotConsistent());
        assertFalse(result.periodPostedEvents().getFirst().currentV210SourceIdentity());
        assertEquals("-20",result.currencies().getFirst().signedRefundSourceAmount());
        assertTrue(result.blockerCodes().contains(
            "PERIOD_V210_REFUND_SOURCE_SNAPSHOT_REVALIDATION_FAILED"));
        assertNull(result.refundAdjustedContribution());
    }

    @Test void unexpectedNewV210RefundInWindowIsNotSilentlyAppended() {
        var a=saleRefund(A,E1,"2026-11-10T10:00:00Z","USD","-20");
        var b=saleRefund(A,E2,"2026-11-16T10:00:00Z","USD","-5");
        var result=PeriodRefundProfitGuardV211.calculate(MP,JOB,baseline(),
            List.of(inWindow(E1,A,a.postedAt().toString(),"USD","-20",1,true)),
            List.of(A),List.of(order(A,a,b)),List.of());
        assertFalse(result.originalCurrencySourceSnapshotConsistent());
        assertTrue(result.blockerCodes().contains(
            "NEW_OR_CHANGED_PERIOD_REFUND_SOURCE_BETWEEN_SNAPSHOTS"));
        assertNull(result.refundAdjustedContribution());
    }

    @Test void missingOrDuplicateFrozenOrderAndEventsMustFailClosed() {
        var s=saleRefund(A,E1,"2026-11-10T10:00:00Z","USD","-20");
        var x=inWindow(E1,A,s.postedAt().toString(),"USD","-20",1,true);
        assertThrows(IllegalArgumentException.class,()->
            PeriodRefundProfitGuardV211.calculate(MP,JOB,baseline(),
                List.of(x,x),List.of(A),List.of(order(A,s)),List.of()));
        assertThrows(IllegalArgumentException.class,()->
            PeriodRefundProfitGuardV211.calculate(MP,JOB,baseline(),
                List.of(x),List.of(A,A),List.of(order(A,s)),List.of()));
        assertThrows(IllegalArgumentException.class,()->
            PeriodRefundProfitGuardV211.calculate(MP,JOB,baseline(),
                List.of(x),List.of(A),List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->
            PeriodRefundProfitGuardV211.calculate(MP,JOB,baseline(),
                List.of(x),List.of(A),List.of(order(A,s)),
                List.of(new PeriodRefundProfitGuardV211.SnapshotOverlap(JOB,A))));
        assertThrows(IllegalArgumentException.class,()->
            PeriodRefundProfitGuardV211.calculate(MP,id(2999),baseline(),
                List.of(),List.of(),List.of(),List.of()));
    }

    @Test void absentLiveSourcesIsUnknownNotZeroOfficialAmazonCoverage() {
        var result=PeriodRefundProfitGuardV211.calculate(MP,JOB,baseline(),
            List.of(),List.of(),List.of(),List.of());
        assertEquals(0,result.periodPostedSourceEventCount());
        assertTrue(result.blockerCodes().contains(
            "NO_CLASSIFIED_REFUND_ROWS_DOES_NOT_PROVE_ZERO_REFUNDS"));
        assertFalse(result.allLocalManualRefundConservationComplete());
        assertFalse(result.independentAmazonRefundCoverageVerified());
        assertNull(result.refundAdjustedContribution());
        assertNull(result.realizedNetProfit());
    }

    @Test void allPeriodSqlIsSourceScopedWithoutJoinAmplificationOr50RowPage() {
        String f=PeriodRefundProfitGuardServiceV211.PERIOD_SOURCE_SQL;
        assertTrue(f.contains("source_family='SPAPI_FINANCES'"));
        assertTrue(f.contains("data_origin='LIVE'"));
        assertTrue(f.contains("category='PRODUCT_REFUND'"));
        assertTrue(f.contains("LEFT JOIN LATERAL"));
        assertTrue(f.contains("count(*) AS match_count"));
        assertTrue(f.contains("o.job_id=?"));
        assertTrue(f.contains("f.posted_at>=? AND f.posted_at<?"));
        assertTrue(f.contains("LIMIT 1001"));
        assertFalse(f.contains("LIMIT 50"));
        var all=PeriodRefundProfitGuardServiceV211.REFUND_COHORT_ORDERS_SQL;
        assertTrue(all.contains("finance_refund_item_review"));
        assertTrue(all.contains("finance_source_event"));
        assertFalse(all.contains("f.posted_at>="),
            "Cohort order must be rechecked across all refund posting periods");
        assertTrue(all.contains("LIMIT 101"));
        var duplicates=PeriodRefundProfitGuardServiceV211.OTHER_REPORT_OVERLAPS_SQL;
        assertTrue(duplicates.contains("older.job_id<>current_order.job_id"));
        assertTrue(duplicates.contains("j.seller_marketplace_id=?"));
        assertTrue(duplicates.contains("j.period_after<?"));
    }

    @Test void ownerScopedNoStoreGetNeverChangesAnyRefundOrProfit() {
        var access=mock(TenantAccessService.class);
        var service=mock(PeriodRefundProfitGuardServiceV211.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        UUID tenant=id(220);
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        var controller=new PeriodRefundProfitGuardControllerV211(access,service);
        var response=controller.preview(auth,request,MP,JOB);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).preview(tenant,MP,JOB);
    }
}
