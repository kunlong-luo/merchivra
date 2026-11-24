package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * V216: cross-source read-time audit of V112 receipt-scoped MANUAL claims.
 * Local cost allocations and V165 collisions are not verified landed cost.
 */
public final class ReceiptCostIntegrityGateV216 {
    private ReceiptCostIntegrityGateV216() {}
    static final int MAX_PLANS = 50;
    public static final String RULE = "V216_RECEIPT_V112_V91_V92_V165_READ_GATE_V1";

    public record Source(UUID planId, UUID sourceId, String state,
        String sourceReviewState, String sourceCurrency, String sourceCategory,
        BigDecimal sourceAmount, String snapshotCurrency,
        BigDecimal snapshotAmount, BigDecimal receiptAmount,
        BigDecimal allReceiptsAmount, long lineCount,
        long sameCurrencyLineCount, long currentReceiptCount,
        long competingV92Count, boolean initialAuditPresent,
        boolean decisionAuditPresent, boolean sourceReviewAuditPresent) {}

    public record Plan(String planId, String sourceId, String state,
        String originalCurrency, String localReceiptCandidateAmount,
        String localAllReceiptsCandidateAmount, long globalReceiptCount,
        long crossSourceIdentitySignalCount, long sharedSettlementLineCount,
        String integrityState, List<String> blockerCodes,
        boolean independentlyVerifiedLandedCost, String actualLandedCost) {}

    public record Result(String marketplaceId, String purchaseReceiptId,
        String ruleVersion, String sourceBasis, long localPlanCount,
        List<Plan> plans, List<String> blockerCodes,
        boolean completeExternalCostCoverage,
        boolean receiptLandedCostVerified,
        boolean actualBatchCogsVerified, String actualBatchCogs,
        boolean postingAllowed, String realizedNetProfit) {}

    static Result evaluate(UUID marketplace, UUID receipt, List<Source> sources,
        Map<UUID,CostIdentityReconciliationService.Preview> collision) {
        if (marketplace == null || receipt == null || sources == null ||
            collision == null || sources.size() > MAX_PLANS)
            throw new IllegalArgumentException("Invalid receipt scope or partial source page");
        Set<UUID> seenPlans = new HashSet<>();
        Set<UUID> seenSources = new HashSet<>();
        var blockers = new LinkedHashSet<String>();
        blockers.add("V112_LOCAL_MANUAL_PROPOSAL_IS_NOT_EXTERNAL_LANDED_COST");
        blockers.add("G1_CAPITALIZATION_FX_AND_REFUND_POLICY_NOT_APPROVED");
        blockers.add("PHYSICAL_SALE_RETURN_ORIGINAL_LOT_NOT_EXTERNALLY_VERIFIED");
        blockers.add("SOURCE_COLLISION_ABSENCE_DOES_NOT_PROVE_COMPLETE_COVERAGE");
        var result = new ArrayList<Plan>();
        if (sources.isEmpty()) {
            if (!collision.isEmpty())
                throw new IllegalArgumentException("Unscoped collision results");
            blockers.add("NO_LOCAL_RECEIPT_COST_PROPOSAL_UNKNOWN_NOT_ZERO");
        }
        if (collision.size() != sources.size())
            throw new IllegalArgumentException("Incomplete V165 source collision population");
        for (var s : sources) {
            if (s == null || s.planId() == null || s.sourceId() == null
                || !seenPlans.add(s.planId()) || !seenSources.add(s.sourceId())
                || !List.of("PENDING_REVIEW","APPROVED_UNVERIFIED","REJECTED")
                    .contains(s.state() == null ? "" : s.state())
                || !List.of("STAGED","REVIEWED_UNVERIFIED","REJECTED")
                    .contains(s.sourceReviewState() == null ? "" : s.sourceReviewState())
                || !List.of("INBOUND_FREIGHT","IMPORT_DUTY","PAYMENT_AND_FX")
                    .contains(s.sourceCategory() == null ? "" : s.sourceCategory())
                || s.sourceCurrency() == null || !s.sourceCurrency().matches("[A-Z]{3}")
                || s.snapshotCurrency() == null || !s.snapshotCurrency().matches("[A-Z]{3}")
                || s.sourceAmount() == null || s.snapshotAmount() == null
                || s.receiptAmount() == null || s.allReceiptsAmount() == null)
                throw new IllegalArgumentException("Malformed V112 source identity");
            var existing = collision.get(s.sourceId());
            if (existing == null || !s.sourceId().toString().equals(existing.sourceId())
                || !s.sourceReviewState().equals(existing.sourceState())
                || existing.signals() == null
                || existing.signals().size() != CostIdentityReconciliationService.SIGNALS.size())
                throw new IllegalArgumentException("V165 source identity or state changed");

            var holds = new LinkedHashSet<String>();
            boolean arithmetic = s.sourceAmount().signum() > 0
                && s.snapshotAmount().signum() > 0
                && s.receiptAmount().signum() > 0
                && s.allReceiptsAmount().signum() > 0
                && s.sourceAmount().compareTo(s.snapshotAmount()) == 0
                && s.sourceCurrency().equals(s.snapshotCurrency())
                && s.receiptAmount().compareTo(s.allReceiptsAmount()) <= 0
                && s.allReceiptsAmount().compareTo(s.sourceAmount()) <= 0
                && s.lineCount() >= 1 && s.lineCount() <= 100
                && s.sameCurrencyLineCount() == s.lineCount()
                && s.currentReceiptCount() == s.lineCount()
                && s.competingV92Count() == 0;
            if (!arithmetic)
                holds.add("RECEIPT_LOCAL_SOURCE_AMOUNT_CURRENCY_OR_V92_DRIFT");
            if (!"REVIEWED_UNVERIFIED".equals(s.sourceReviewState())
                || !s.initialAuditPresent()
                || !s.sourceReviewAuditPresent()
                || (!"PENDING_REVIEW".equals(s.state()) && !s.decisionAuditPresent()))
                holds.add("SOURCE_OR_INDEPENDENT_DECISION_AUDIT_MISSING");
            if ("REJECTED".equals(s.state()))
                holds.add("REJECTED_RECEIPT_COST_PROPOSAL_RETAINED");
            else if ("PENDING_REVIEW".equals(s.state()))
                holds.add("RECEIPT_COST_PROPOSAL_PENDING_REVIEW");

            long signals = existing.signals().stream()
                .filter(CostIdentityReconciliationService.Signal::exactLocalIdentityOverlap)
                .count();
            if (signals > 0 || existing.approvalHeld()
                || existing.sharedSettlementLineCount() > 0)
                holds.add("V165_CROSS_SOURCE_POTENTIAL_COLLISION_HOLD");
            if (existing.sharedSettlementLineCount() > existing.linkedSettlementLineCount()
                || existing.sharedSettlementLineCount() < 0)
                throw new IllegalArgumentException("V165 settlement counts invalid");
            String state = !arithmetic || holds.contains("SOURCE_OR_INDEPENDENT_DECISION_AUDIT_MISSING")
                    ? "LOCAL_RECEIPT_EVIDENCE_STALE_OR_UNAUDITED"
                    : signals > 0 || existing.approvalHeld()
                        || existing.sharedSettlementLineCount() > 0
                        ? "POTENTIAL_EXTERNAL_CHARGE_IDENTITY_COLLISION_HOLD"
                        : "APPROVED_UNVERIFIED".equals(s.state())
                            ? "LOCAL_RECEIPT_ALLOCATION_INTACT_UNVERIFIED"
                            : "REJECTED".equals(s.state())
                                ? "REJECTED_LOCAL_PROPOSAL"
                                : "PENDING_LOCAL_REVIEW";
            blockers.addAll(holds);
            result.add(new Plan(s.planId().toString(),s.sourceId().toString(),
                s.state(),s.sourceCurrency(),s.receiptAmount().toPlainString(),
                s.allReceiptsAmount().toPlainString(),s.lineCount(),signals,
                existing.sharedSettlementLineCount(),state,List.copyOf(holds),false,null));
        }
        if (!collision.keySet().equals(seenSources))
            throw new IllegalArgumentException("Extra V165 source population");
        return new Result(marketplace.toString(),receipt.toString(),RULE,
            "V112_RECEIPT_EXACT_V91_V92_SOURCE_AND_V165_LOCAL_COLLISION_UNVERIFIED",
            sources.size(),List.copyOf(result),List.copyOf(blockers),false,false,
            false,null,false,null);
    }
}
