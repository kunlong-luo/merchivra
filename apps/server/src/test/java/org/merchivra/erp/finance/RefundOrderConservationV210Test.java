package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * V210: full-order multi-event evidence, not an already-visible refund page.
 * A complete synthetic manual review is still not true cost reversal or profit.
 */
class RefundOrderConservationV210Test {
    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-4000-8000-"
            +String.format("%012x",n));
    }
    private static final UUID ORDER=id(101);
    private static final UUID EVENT1=id(201);
    private static final UUID EVENT2=id(202);

    private static RefundOrderConservationEngineV210.Source source(
        UUID event,String currency,String signedAmount) {
        return new RefundOrderConservationEngineV210.Source(event,ORDER,
            Instant.parse("2026-11-01T12:00:00Z"),currency,
            new BigDecimal(signedAmount));
    }
    private static RefundOrderConservationEngineV210.ReviewItem reviewed(
        UUID event,int qty,String disposition) {
        return new RefundOrderConservationEngineV210.ReviewItem(
            event,"ITEM-1",qty,disposition);
    }
    private static RefundRecoveryService.Status status(
        UUID event,String currency,String signedAmount,int quantity,
        String disposition,String unitCost) {
        var historical=new BigDecimal(unitCost)
            .multiply(BigDecimal.valueOf(quantity));
        var claimed="RESTOCKED_CLAIMED".equals(disposition)
            ?historical:BigDecimal.ZERO;
        var line=new RefundRecoveryEngine.Item("ITEM-1","SKU-1",quantity,
            disposition,signedAmount,historical.toPlainString(),
            claimed.toPlainString());
        var exposure=new RefundRecoveryEngine.Preview(
            "MANUAL_EVIDENCE_BALANCED_UNVERIFIED",currency,signedAmount,
            signedAmount,"0",historical.toPlainString(),
            claimed.toPlainString(),null,false,false,List.of(line));
        return new RefundRecoveryService.Status(event.toString(),ORDER.toString(),
            "REVIEWED",1,"MANUAL_UNVERIFIED","MAKER","CHECKER",
            true,false,false,exposure);
    }
    private static RefundOrderConservationEngineV210.Probe probe(
        UUID event,String signed,int qty,String disposition) {
        return new RefundOrderConservationEngineV210.Probe(
            source(event,"USD",signed),
            status(event,"USD",signed,qty,disposition,"8.00"));
    }
    private static List<RefundOrderConservationEngineV210.SaleItem> sale(int qty) {
        return List.of(new RefundOrderConservationEngineV210.SaleItem(
            "ITEM-1",qty));
    }

    @Test void twoPartialRefundEventsConserveOriginalQuantityAndSignedMoneyOnly() {
        var result=RefundOrderConservationEngineV210.calculate(ORDER,sale(5),
            List.of(reviewed(EVENT1,2,"RESTOCKED_CLAIMED"),
                reviewed(EVENT2,1,"NOT_RESTOCKED")),
            List.of(probe(EVENT1,"-20.00",2,"RESTOCKED_CLAIMED"),
                probe(EVENT2,"-10.00",1,"NOT_RESTOCKED")));
        assertEquals(2,result.sourceEventCount());
        assertEquals(2,result.manualReviewRowCount());
        assertEquals(0,result.crossEventQuantityConflictCount());
        assertEquals(3,result.quantities().getFirst().manuallyReviewedQuantity());
        assertEquals(2,result.quantities().getFirst().distinctRefundEventCount());
        assertEquals("MANUAL_QUANTITY_WITHIN_ORIGINAL_UNVERIFIED",
            result.quantities().getFirst().state());
        assertTrue(result.completeLocalManualConservation());
        var usd=result.currencies().getFirst();
        assertEquals("USD",usd.currency());
        assertEquals(2,usd.sourceEventCount());
        assertEquals(0,new BigDecimal(usd.signedSourceRefundTotal())
            .compareTo(new BigDecimal("-30")));
        assertEquals(0,new BigDecimal(usd.historicalCostExposureUnverified())
            .compareTo(new BigDecimal("24.00")));
        assertEquals(0,new BigDecimal(usd.claimedRestockCostSensitivityUnverified())
            .compareTo(new BigDecimal("16.00")));
        assertTrue(usd.allLocalManualEvidenceConsistent());
        assertFalse(usd.actualCogsReversalVerified());
        assertNull(usd.actualCogsReversal());
        assertNull(result.actualCogsReversal());
        assertNull(result.realizedNetProfit());
        assertFalse(result.independentlyVerifiedActualBatchCogs());
        assertFalse(result.fullFinancesRefundCoverageVerified());
        assertFalse(result.physicalReturnReceiptVerified());
        assertFalse(result.actualCogsReversalsAllowed());
        assertTrue(result.blockerCodes().contains(
            "G1_REFUND_COGS_REVERSAL_POLICY_NOT_APPROVED"));
        assertThrows(UnsupportedOperationException.class,
            ()->result.events().clear());
    }

    @Test void crossEventQuantityOverflowHoldsAllCostSensitivitiesNotSourceAmount() {
        var result=RefundOrderConservationEngineV210.calculate(ORDER,sale(5),
            List.of(reviewed(EVENT1,3,"RESTOCKED_CLAIMED"),
                reviewed(EVENT2,3,"RESTOCKED_CLAIMED")),
            List.of(probe(EVENT1,"-20",3,"RESTOCKED_CLAIMED"),
                probe(EVENT2,"-10",3,"RESTOCKED_CLAIMED")));
        assertEquals(1,result.crossEventQuantityConflictCount());
        assertEquals("CROSS_EVENT_REFUNDED_QUANTITY_EXCEEDS_ORIGINAL",
            result.quantities().getFirst().state());
        assertFalse(result.completeLocalManualConservation());
        assertTrue(result.blockerCodes().contains(
            "CROSS_EVENT_REFUNDED_QUANTITY_EXCEEDS_ORIGINAL"));
        assertEquals(0,new BigDecimal(result.currencies().getFirst()
            .signedSourceRefundTotal()).compareTo(new BigDecimal("-30")));
        assertNull(result.currencies().getFirst().historicalCostExposureUnverified());
        assertNull(result.currencies().getFirst()
            .claimedRestockCostSensitivityUnverified());
        assertNull(result.realizedNetProfit());
    }

    @Test void sourceSnapshotCurrencyAmountOrReviewQuantityDriftHoldAllCogs() {
        var badAmount=new RefundOrderConservationEngineV210.Probe(
            source(EVENT1,"USD","-20"),status(EVENT1,"USD","-21",1,
                "RESTOCKED_CLAIMED","8"));
        var amountResult=RefundOrderConservationEngineV210.calculate(
            ORDER,sale(2),List.of(reviewed(EVENT1,1,"RESTOCKED_CLAIMED")),
            List.of(badAmount));
        assertFalse(amountResult.completeLocalManualConservation());
        assertFalse(amountResult.events().getFirst().snapshotCurrent());
        assertNull(amountResult.events().getFirst().historicalCostExposureUnverified());
        assertTrue(amountResult.events().getFirst().blockerCodes().contains(
            "REFUND_AMOUNT_OR_CURRENCY_CHANGED_SINCE_SNAPSHOT"));

        var quantityResult=RefundOrderConservationEngineV210.calculate(
            ORDER,sale(2),List.of(reviewed(EVENT1,2,"RESTOCKED_CLAIMED")),
            List.of(probe(EVENT1,"-20",1,"RESTOCKED_CLAIMED")));
        assertFalse(quantityResult.completeLocalManualConservation());
        assertTrue(quantityResult.events().getFirst().blockerCodes().contains(
            "V34_V63_REVIEW_QUANTITY_OR_DISPOSITION_DRIFT"));
        assertNull(quantityResult.currencies().getFirst()
            .claimedRestockCostSensitivityUnverified());
    }

    @Test void oldOrOrphanedRefundManualReviewCannotAppearAsCurrentCogs() {
        var incomplete=RefundOrderConservationEngineV210.calculate(
            ORDER,sale(5),List.of(reviewed(EVENT1,1,"RESTOCKED_CLAIMED")),
            List.of(new RefundOrderConservationEngineV210.Probe(
                source(EVENT1,"USD","-20"),null)));
        assertFalse(incomplete.completeLocalManualConservation());
        assertEquals("UNAVAILABLE",incomplete.events().getFirst().manualReviewState());
        assertNull(incomplete.currencies().getFirst()
            .historicalCostExposureUnverified());

        var orphan=RefundOrderConservationEngineV210.calculate(ORDER,sale(5),
            List.of(reviewed(EVENT2,1,"RESTOCKED_CLAIMED")),
            List.of(probe(EVENT1,"-20",1,"RESTOCKED_CLAIMED")));
        assertTrue(orphan.blockerCodes().contains(
            "ORPHANED_OR_CHANGED_REFUND_REVIEW_SOURCE"));
        assertNull(orphan.currencies().getFirst()
            .historicalCostExposureUnverified());
    }

    @Test void missingLiveQuantityCannotBecomeZeroAndPositiveRefundIsHeld() {
        var missing=RefundOrderConservationEngineV210.calculate(ORDER,
            List.of(new RefundOrderConservationEngineV210.SaleItem("ITEM-1",null)),
            List.of(reviewed(EVENT1,1,"RESTOCKED_CLAIMED")),
            List.of(probe(EVENT1,"-20",1,"RESTOCKED_CLAIMED")));
        assertEquals("ORIGINAL_LIVE_ITEM_QUANTITY_UNKNOWN",
            missing.quantities().getFirst().state());
        assertNull(missing.currencies().getFirst()
            .historicalCostExposureUnverified());
        var positive=RefundOrderConservationEngineV210.calculate(ORDER,sale(2),
            List.of(reviewed(EVENT1,1,"RESTOCKED_CLAIMED")),
            List.of(new RefundOrderConservationEngineV210.Probe(
                source(EVENT1,"USD","20"),
                status(EVENT1,"USD","20",1,"RESTOCKED_CLAIMED","8"))));
        assertTrue(positive.blockerCodes().contains(
            "SOURCE_EVENT_NOT_NEGATIVE_PRODUCT_REFUND"));
        assertNull(positive.currencies().getFirst()
            .claimedRestockCostSensitivityUnverified());
    }

    @Test void distinctOriginalCurrenciesNeverAddConvertedProvisionalCost() {
        var a=probe(EVENT1,"-20",2,"RESTOCKED_CLAIMED");
        var b=new RefundOrderConservationEngineV210.Probe(
            source(EVENT2,"EUR","-10"),
            status(EVENT2,"EUR","-10",1,"NOT_RESTOCKED","7.00"));
        var result=RefundOrderConservationEngineV210.calculate(ORDER,sale(5),
            List.of(reviewed(EVENT1,2,"RESTOCKED_CLAIMED"),
                reviewed(EVENT2,1,"NOT_RESTOCKED")),List.of(a,b));
        assertEquals(2,result.currencies().size());
        assertEquals("USD",result.currencies().get(0).currency());
        assertEquals("EUR",result.currencies().get(1).currency());
        assertEquals("16.00",result.currencies().get(0)
            .claimedRestockCostSensitivityUnverified());
        assertEquals("0",result.currencies().get(1)
            .claimedRestockCostSensitivityUnverified());
        assertNull(result.realizedNetProfit());
    }

    @Test void zeroMatchedEventsOrDuplicateSourcesCannotInventCoverage() {
        var empty=RefundOrderConservationEngineV210.calculate(ORDER,sale(2),
            List.of(),List.of());
        assertEquals(0,empty.sourceEventCount());
        assertFalse(empty.fullFinancesRefundCoverageVerified());
        assertFalse(empty.completeLocalManualConservation());
        assertTrue(empty.blockerCodes().contains(
            "NO_MATCHED_LIVE_PRODUCT_REFUND_EVENTS"));
        assertTrue(empty.currencies().isEmpty());
        assertNull(empty.realizedNetProfit());
        var one=probe(EVENT1,"-20",1,"NOT_RESTOCKED");
        assertThrows(IllegalArgumentException.class,
            ()->RefundOrderConservationEngineV210.calculate(ORDER,sale(5),
                List.of(reviewed(EVENT1,1,"NOT_RESTOCKED")),List.of(one,one)));
        assertThrows(IllegalArgumentException.class,
            ()->RefundOrderConservationEngineV210.calculate(ORDER,sale(5),
                List.of(reviewed(EVENT1,1,"NOT_RESTOCKED"),
                    reviewed(EVENT1,1,"NOT_RESTOCKED")),List.of(one)));
    }

    @Test void fullOrderServiceReadsWholeSourcesNotLastVisibleFifty() {
        String sql=RefundOrderConservationServiceV210.MATCHED_REFUNDS_SQL;
        assertTrue(sql.contains("source_family='SPAPI_FINANCES'"));
        assertTrue(sql.contains("data_origin='LIVE'"));
        assertTrue(sql.contains("category='PRODUCT_REFUND'"));
        assertTrue(sql.contains("v.state='MATCHED'"));
        assertTrue(sql.contains("v.order_id=?"));
        assertFalse(sql.contains("LIMIT 50"));
        assertTrue(sql.contains("LIMIT 1001"));
        assertTrue(RefundOrderConservationServiceV210.ALL_ORDER_MANUAL_REVIEWS_SQL
            .contains("r.sales_order_id=?"));
        assertFalse(RefundOrderConservationServiceV210.ALL_ORDER_MANUAL_REVIEWS_SQL
            .contains("r.event_id=?"));
        assertTrue(RefundOrderConservationServiceV210.ALL_LIVE_ORDER_ITEMS_SQL
            .contains("i.is_current=true AND i.data_origin='LIVE'"));
    }

    @Test void scopedReadOnlyOwnerRouteCannotPostCogs() {
        var access=mock(TenantAccessService.class);
        var svc=mock(RefundOrderConservationServiceV210.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        var ctl=new RefundOrderConservationControllerV210(access,svc);
        UUID tenant=id(20),mp=id(21),order=id(22);
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        assertTrue(ctl.preview(auth,request,mp,order).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(svc).preview(tenant,mp,order);
    }
}
