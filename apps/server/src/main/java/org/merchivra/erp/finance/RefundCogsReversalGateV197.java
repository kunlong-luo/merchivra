package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * V197: fail-closed, read-only refund COGS evidence gate.
 *
 * V170 freezes a source page, then probes V63 outside its transaction to
 * avoid rollback-only poisoning on normal business 409s. This gate compares
 * the probed V63 event/order/currency/signed amount to the earlier snapshot;
 * a concurrent reclassification or reassignment must not be presented as a
 * current, balanced refund sensitivity for that original source.
 *
 * V34/V35/V63 manual evidence never proves independent source authenticity,
 * physical receipt, approved G1 accounting policy, or ledger permission.
 * This class introduces no new approval, stock movement or COGS posting.
 */
final class RefundCogsReversalGateV197 {
    private RefundCogsReversalGateV197() {}

    record Assessment(boolean sourceSnapshotConsistent,
        List<String> blockerCodes) {}

    static Assessment assess(UUID snapshotEventId,
        RefundRecoveryService.Status review,UUID snapshotOrderId,
        String snapshotCurrency,BigDecimal snapshotAmount) {
        var codes=new ArrayList<String>();
        boolean eventMatches=review!=null && snapshotEventId!=null
            && snapshotEventId.toString().equals(review.eventId());
        if(!eventMatches)
            codes.add("REFUND_EVENT_IDENTITY_NOT_IN_SOURCE_SNAPSHOT");
        boolean orderMatches=review!=null && snapshotOrderId!=null
            && snapshotOrderId.toString().equals(review.orderId());
        boolean moneyMatches=false;
        if(review!=null && review.exposure()!=null
            && snapshotAmount!=null && snapshotCurrency!=null
            && Objects.equals(snapshotCurrency,review.exposure().currency())
            && review.exposure().signedRefundTotal()!=null) {
            try {
                moneyMatches=new BigDecimal(review.exposure().signedRefundTotal())
                    .compareTo(snapshotAmount)==0;
            } catch(NumberFormatException ignored) {
                // Never display a malformed changed source as reliable.
            }
        }
        boolean sameSnapshot=eventMatches && orderMatches && moneyMatches;
        if(!orderMatches)
            codes.add("REFUND_ORDER_EVIDENCE_NOT_IN_SOURCE_SNAPSHOT");
        if(review!=null && !moneyMatches)
            codes.add("REFUND_AMOUNT_OR_CURRENCY_CHANGED_SINCE_SNAPSHOT");
        if(review==null) {
            codes.add("REFUND_ITEM_AMOUNT_OR_COST_EVIDENCE_UNAVAILABLE");
            codes.add("INDEPENDENT_CURRENT_REFUND_REVIEW_REQUIRED");
        } else {
            if(!"MANUAL_UNVERIFIED".equals(review.evidenceOrigin()))
                codes.add("REFUND_EVIDENCE_ORIGIN_INCONSISTENT");
            if(review.exposure()==null
                ||!"MANUAL_EVIDENCE_BALANCED_UNVERIFIED".equals(
                    review.exposure().status())
                ||review.exposure().items()==null
                ||review.exposure().items().isEmpty())
                codes.add("REFUND_ITEM_AMOUNT_OR_COST_EVIDENCE_UNAVAILABLE");
            if(!review.evidenceCurrent())
                codes.add("REFUND_REVIEW_EVIDENCE_NOT_CURRENT");
            if(!"REVIEWED".equals(review.state())
                ||review.requestedBy()==null||review.decidedBy()==null
                ||review.requestedBy().equals(review.decidedBy()))
                codes.add("INDEPENDENT_CURRENT_REFUND_REVIEW_REQUIRED");
            if(review.exposure()!=null && review.exposure().items()!=null
                && review.exposure().items().stream()
                    .anyMatch(i->"UNKNOWN".equals(i.disposition())))
                codes.add("RETURN_ITEM_DISPOSITION_UNRESOLVED");
        }

        // Deliberately unconditional until separate EXTERNAL attestation,
        // approved G1 policy and ledger chains exist. V63's booleans, even
        // if later set true, are not sufficient to release COGS.
        codes.add("EXTERNAL_REFUND_SOURCE_AUTHENTICITY_UNVERIFIED");
        codes.add("PHYSICAL_RETURN_RECEIPT_AND_DISPOSITION_UNVERIFIED");
        codes.add("G1_REFUND_COGS_REVERSAL_POLICY_NOT_APPROVED");
        codes.add("INVENTORY_AND_ACCOUNTING_POSTING_NOT_AUTHORIZED");
        return new Assessment(sameSnapshot,List.copyOf(codes));
    }
}
