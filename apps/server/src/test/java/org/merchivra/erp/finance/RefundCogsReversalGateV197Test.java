package org.merchivra.erp.finance;

import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** V197 guards cross-transaction refund sources without posting COGS. */
class RefundCogsReversalGateV197Test {
    private static final UUID EVENT=UUID.fromString(
        "00000000-0000-4000-8000-000000000700");
    private static final UUID ORDER=UUID.fromString(
        "00000000-0000-4000-8000-000000000701");
    private static final String BALANCED="MANUAL_EVIDENCE_BALANCED_UNVERIFIED";

    private static RefundRecoveryEngine.Preview exposure(
        String state,String disposition,String currency,String amount) {
        return new RefundRecoveryEngine.Preview(state,currency,amount,
            amount,"0.000000","8.000000","8.000000",null,false,false,
            List.of(new RefundRecoveryEngine.Item("I-1","SKU-1",1,
                disposition,amount,"8.000000","8.000000")));
    }

    private static RefundRecoveryService.Status status(String state,
        boolean current,String exposureState,String disposition,
        String order,String currency,String amount) {
        return new RefundRecoveryService.Status(
            EVENT.toString(),order,state,1,"MANUAL_UNVERIFIED",
            "REQUESTER","INDEPENDENT_REVIEWER",current,false,false,
            exposure(exposureState,disposition,currency,amount));
    }

    private static RefundRecoveryService.Status reviewed() {
        return status("REVIEWED",true,BALANCED,"RESTOCKED_CLAIMED",
            ORDER.toString(),"USD","-20.000000");
    }

    private static RefundCogsReversalGateV197.Assessment assess(
        RefundRecoveryService.Status review) {
        return RefundCogsReversalGateV197.assess(
            EVENT,review,ORDER,"USD",new BigDecimal("-20"));
    }

    @Test void missingReviewNeverTurnsAnUnlinkedSourceIntoZeroCogs() {
        var assessment=RefundCogsReversalGateV197.assess(
            EVENT,null,null,"USD",new BigDecimal("-20"));
        assertFalse(assessment.sourceSnapshotConsistent());
        var blocks=assessment.blockerCodes();
        assertTrue(blocks.contains("REFUND_EVENT_IDENTITY_NOT_IN_SOURCE_SNAPSHOT"));
        assertTrue(blocks.contains("REFUND_ORDER_EVIDENCE_NOT_IN_SOURCE_SNAPSHOT"));
        assertTrue(blocks.contains("REFUND_ITEM_AMOUNT_OR_COST_EVIDENCE_UNAVAILABLE"));
        assertTrue(blocks.contains("INDEPENDENT_CURRENT_REFUND_REVIEW_REQUIRED"));
        assertTrue(blocks.contains("EXTERNAL_REFUND_SOURCE_AUTHENTICITY_UNVERIFIED"));
        assertTrue(blocks.contains("PHYSICAL_RETURN_RECEIPT_AND_DISPOSITION_UNVERIFIED"));
        assertTrue(blocks.contains("G1_REFUND_COGS_REVERSAL_POLICY_NOT_APPROVED"));
        assertThrows(UnsupportedOperationException.class,
            ()->blocks.add("FAKE_VERIFIED"));
    }

    @Test void reviewedBalancedManualRestockStillCannotReleaseCogs() {
        var assessment=assess(reviewed());
        assertTrue(assessment.sourceSnapshotConsistent());
        assertEquals(List.of(
            "EXTERNAL_REFUND_SOURCE_AUTHENTICITY_UNVERIFIED",
            "PHYSICAL_RETURN_RECEIPT_AND_DISPOSITION_UNVERIFIED",
            "G1_REFUND_COGS_REVERSAL_POLICY_NOT_APPROVED",
            "INVENTORY_AND_ACCOUNTING_POSTING_NOT_AUTHORIZED"),
            assessment.blockerCodes());
    }

    @Test void signedAmountCurrencyAndOrderMustMatchEarlierSourceSnapshot() {
        var differentOrder=status("REVIEWED",true,BALANCED,"RESTOCKED_CLAIMED",
            UUID.randomUUID().toString(),"USD","-20");
        var differentAmount=status("REVIEWED",true,BALANCED,"RESTOCKED_CLAIMED",
            ORDER.toString(),"USD","-21");
        var differentCurrency=status("REVIEWED",true,BALANCED,"RESTOCKED_CLAIMED",
            ORDER.toString(),"EUR","-20");
        assertFalse(assess(differentOrder).sourceSnapshotConsistent());
        assertTrue(assess(differentOrder).blockerCodes().contains(
            "REFUND_ORDER_EVIDENCE_NOT_IN_SOURCE_SNAPSHOT"));
        for(var review:List.of(differentAmount,differentCurrency)) {
            var assessment=assess(review);
            assertFalse(assessment.sourceSnapshotConsistent());
            assertTrue(assessment.blockerCodes().contains(
                "REFUND_AMOUNT_OR_CURRENCY_CHANGED_SINCE_SNAPSHOT"));
        }
        // Compare NUMERIC value, not textual decimal scale.
        assertTrue(assess(status("REVIEWED",true,BALANCED,"NOT_RESTOCKED",
            ORDER.toString(),"USD","-20.00")).sourceSnapshotConsistent());
    }

    @Test void differentEventCannotReuseSameOrderAndSameSignedRefund() {
        var wrong=new RefundRecoveryService.Status(UUID.randomUUID().toString(),
            ORDER.toString(),"REVIEWED",1,"MANUAL_UNVERIFIED",
            "MAKER","CHECKER",true,false,false,
            exposure(BALANCED,"RESTOCKED_CLAIMED","USD","-20"));
        var a=assess(wrong);
        assertFalse(a.sourceSnapshotConsistent());
        assertTrue(a.blockerCodes().contains(
            "REFUND_EVENT_IDENTITY_NOT_IN_SOURCE_SNAPSHOT"));
    }

    @Test void unbalancedRejectedStaleAndUnknownDispositionAreExplicit() {
        var review=status("REJECTED",false,"REFUND_ITEMS_OR_AMOUNT_INCOMPLETE",
            "UNKNOWN",ORDER.toString(),"USD","-20");
        var assessment=assess(review);
        assertTrue(assessment.sourceSnapshotConsistent());
        var blocks=assessment.blockerCodes();
        assertTrue(blocks.contains("REFUND_ITEM_AMOUNT_OR_COST_EVIDENCE_UNAVAILABLE"));
        assertTrue(blocks.contains("REFUND_REVIEW_EVIDENCE_NOT_CURRENT"));
        assertTrue(blocks.contains("INDEPENDENT_CURRENT_REFUND_REVIEW_REQUIRED"));
        assertTrue(blocks.contains("RETURN_ITEM_DISPOSITION_UNRESOLVED"));
        assertFalse(blocks.contains("REFUND_ORDER_EVIDENCE_NOT_IN_SOURCE_SNAPSHOT"));
    }

    @Test void makerCannotApproveOwnEvidenceEvenWithReviewedStatus() {
        var s=new RefundRecoveryService.Status(EVENT.toString(),ORDER.toString(),
            "REVIEWED",1,"MANUAL_UNVERIFIED","SAME","SAME",true,false,false,
            exposure(BALANCED,"RESTOCKED_CLAIMED","USD","-20"));
        assertTrue(assess(s).blockerCodes().contains(
            "INDEPENDENT_CURRENT_REFUND_REVIEW_REQUIRED"));
    }

    @Test void contradictoryPhysicalReceiptFlagsNeverOverrideExternalG1Gates() {
        var s=new RefundRecoveryService.Status(EVENT.toString(),ORDER.toString(),
            "REVIEWED",1,"MANUAL_UNVERIFIED","MAKER","CHECKER",true,true,true,
            exposure(BALANCED,"RESTOCKED_CLAIMED","USD","-20"));
        var codes=assess(s).blockerCodes();
        assertTrue(codes.contains("EXTERNAL_REFUND_SOURCE_AUTHENTICITY_UNVERIFIED"));
        assertTrue(codes.contains("PHYSICAL_RETURN_RECEIPT_AND_DISPOSITION_UNVERIFIED"));
        assertTrue(codes.contains("G1_REFUND_COGS_REVERSAL_POLICY_NOT_APPROVED"));
    }
}
