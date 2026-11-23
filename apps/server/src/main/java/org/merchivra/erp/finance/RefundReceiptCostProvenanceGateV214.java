package org.merchivra.erp.finance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * V214: V91/V92 are marketplace-wide MANUAL cost candidates, not a receipt
 * allocation ledger. No V91/V92 field links a cost source or target to a
 * V20 purchase receipt. Even independent local review is not external proof.
 */
public final class RefundReceiptCostProvenanceGateV214 {
    private RefundReceiptCostProvenanceGateV214() {}
    public static final String RULE = "V214_MARKETPLACE_MANUAL_COST_RECEIPT_PROVENANCE_HOLD_V1";
    static final int MAX_SOURCE_ROWS = 1000;
    static final List<String> CATEGORIES = List.of(
        "INBOUND_FREIGHT", "IMPORT_DUTY", "PAYMENT_AND_FX");

    /** V91 row with at most one currently active V92 proposal. No amounts. */
    public record ManualSource(UUID id, String category, String reviewState,
        UUID activeAllocationId, String allocationState, String targetKind) {}

    public record CategoryEvidence(String category, long manualSourceCount,
        long locallyReviewedUnverifiedCount, long activeAllocationCount,
        long approvedUnverifiedAllocationCount) {}

    public record Result(String marketplaceId, String orderId,
        String ruleVersion, String sourceBasis, int originalReceiptCount,
        long marketplaceManualCandidateSourceCount,
        long locallyReviewedUnverifiedCandidateCount,
        long activeManualAllocationCount,
        long approvedUnverifiedAllocationCount,
        List<CategoryEvidence> categories, List<String> blockerCodes,
        boolean v91V92PurchaseReceiptIdentityLinked,
        boolean externalFreightDutyAndFxVerified,
        boolean verifiedLandedReceiptCostReady,
        String verifiedLandedReceiptCost,
        boolean refundCogsReversalAllowed,
        String actualRefundCogsReversal, String realizedNetProfit) {}

    static Result evaluate(UUID marketplace, UUID order,
        RefundGlobalReceiptCapacityGateV213.Result receipt,
        List<ManualSource> sources) {
        if (marketplace == null || order == null || receipt == null
            || sources == null || sources.size() > MAX_SOURCE_ROWS
            || receipt.originalReceiptCount() < 0
            || !marketplace.toString().equals(receipt.marketplaceId())
            || !order.toString().equals(receipt.orderId())
            || receipt.blockerCodes() == null
            || receipt.independentlyVerifiedActualLandedCosts()
            || receipt.refundCogsReversalAllowed()
            || receipt.actualRefundCogsReversal() != null
            || receipt.realizedNetProfit() != null)
            throw new IllegalArgumentException("V213 receipt evidence or financial hold changed");

        var counts = new LinkedHashMap<String, long[]>();
        for (String category : CATEGORIES) counts.put(category, new long[4]);
        var ids = new HashSet<UUID>();
        var blockers = new LinkedHashSet<String>(receipt.blockerCodes());
        blockers.add("V91_V92_COST_SOURCE_HAS_NO_PURCHASE_RECEIPT_IDENTITY");
        blockers.add("MARKETPLACE_COST_CANDIDATE_IS_NOT_RECEIPT_LANDED_COST");
        blockers.add("INDEPENDENT_SUPPLIER_CARRIER_BANK_OR_CUSTOMS_PROOF_MISSING");
        blockers.add("G1_LANDED_COST_FX_CAPITALIZATION_AND_PERIOD_POLICY_UNSIGNED");
        blockers.add("REFUND_ORIGINAL_PHYSICAL_LOT_AND_REAL_COGS_NOT_VERIFIED");
        blockers.add("V214_COST_SOURCE_OBSERVATION_IS_NOT_SIGNED_ATOMIC_EVIDENCE");

        long reviewed = 0;
        long active = 0;
        long approved = 0;
        for (var source : sources) {
            if (source == null || source.id() == null
                || !ids.add(source.id()) || !counts.containsKey(source.category())
                || source.reviewState() == null
                || !List.of("STAGED", "REVIEWED_UNVERIFIED", "REJECTED")
                    .contains(source.reviewState()))
                throw new IllegalArgumentException("Duplicated or invalid V91 cost source");
            var bucket = counts.get(source.category());
            bucket[0]++;
            if ("REVIEWED_UNVERIFIED".equals(source.reviewState())) {
                bucket[1]++;
                reviewed++;
            }
            if (source.activeAllocationId() == null) {
                if (source.allocationState() != null || source.targetKind() != null)
                    throw new IllegalArgumentException("Orphan V92 allocation state");
            } else {
                if (source.allocationState() == null || source.targetKind() == null
                    || !List.of("PENDING_REVIEW", "APPROVED_UNVERIFIED")
                    .contains(source.allocationState())
                    || !List.of("SKU", "ORDER", "PERIOD")
                        .contains(source.targetKind()))
                    throw new IllegalArgumentException("Invalid V92 active allocation");
                bucket[2]++;
                active++;
                if (!"REVIEWED_UNVERIFIED".equals(source.reviewState()))
                    blockers.add("V92_ACTIVE_ALLOCATION_SOURCE_REVIEW_NOT_VALID");
                if ("APPROVED_UNVERIFIED".equals(source.allocationState())) {
                    bucket[3]++;
                    approved++;
                }
            }
        }
        if (sources.isEmpty())
            blockers.add("NO_MANUAL_LANDED_COST_CANDIDATE_METADATA_NOT_ZERO_COST");
        if (approved > 0)
            blockers.add("V92_APPROVED_UNVERIFIED_IS_NOT_RECEIPT_COST_PROOF");

        var categories = new ArrayList<CategoryEvidence>();
        for (var entry : counts.entrySet()) {
            long[] c = entry.getValue();
            categories.add(new CategoryEvidence(entry.getKey(),
                c[0], c[1], c[2], c[3]));
        }
        return new Result(marketplace.toString(), order.toString(), RULE,
            "V213_LOCAL_ORIGINAL_RECEIPTS_PLUS_MARKETPLACE_WIDE_V91_V92_METADATA_ONLY",
            receipt.originalReceiptCount(), sources.size(), reviewed, active,
            approved, List.copyOf(categories), List.copyOf(blockers),
            false, false, false, null, false, null, null);
    }
}
