package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cost exposure and possible COGS-recovery sensitivity only. Manual V34
 * RESTOCKED_CLAIMED is not a physical receipt. No inventory or ledger posting.
 */
public final class RefundRecoveryEngine {
    private RefundRecoveryEngine(){}
    public record SourceLine(String itemId,String sku,int ordered,int refunded,
        String disposition,String currency,BigDecimal unitCost,
        int reviewRevision,String reviewReference) {}
    public record RefundLine(String itemId,BigDecimal signedAmount,
        String currency,int allocationRevision,String allocationReference) {}
    public record Item(String itemId,String sku,int refundedQuantity,
        String disposition,String signedRefund,String historicalCostExposure,
        String claimedRestockCostSensitivity) {}
    public record Preview(String status,String currency,String signedRefundTotal,
        String allocatedRefund,String remainingRefund,String historicalCostExposure,
        String claimedRestockCostSensitivity,String actualCogsReversal,
        boolean inventoryReceiptVerified,boolean ledgerPosted,
        List<Item> items) {}

    public static Preview calculate(String currency,BigDecimal sourceRefund,
        List<SourceLine> review,List<RefundLine> allocations) {
        if(currency==null||!currency.matches("[A-Z]{3}")
            ||sourceRefund==null||sourceRefund.signum()==0
            ||review==null||review.isEmpty()||review.size()>100
            ||allocations==null||allocations.size()>100)
            throw new IllegalArgumentException("Refund source or bounded rows invalid");
        var refs=new HashMap<String,SourceLine>();
        var reviewKeys=new HashSet<String>();
        for(var s:review) {
            if(s==null||s.itemId()==null||!reviewKeys.add(s.itemId())
                ||s.sku()==null||s.sku().isBlank()
                ||s.unitCost()==null||s.unitCost().signum()<0
                ||s.ordered()<=0||s.refunded()<=0||s.refunded()>s.ordered()
                ||!currency.equals(s.currency())||s.reviewRevision()<=0
                ||s.reviewReference()==null
                ||!Set.of("RESTOCKED_CLAIMED","NOT_RESTOCKED","UNKNOWN")
                    .contains(s.disposition()))
                throw new IllegalArgumentException("Incomplete historical refund item/cost");
            refs.put(s.itemId(),s);
        }
        var lines=new ArrayList<Item>();
        BigDecimal refund=BigDecimal.ZERO,cost=BigDecimal.ZERO,sensitivity=BigDecimal.ZERO;
        var counted=new HashSet<String>();
        for(var a:allocations) {
            if(a==null||!counted.add(a.itemId())||a.signedAmount()==null
                ||a.signedAmount().signum()!=sourceRefund.signum()
                ||!currency.equals(a.currency())||a.allocationRevision()<=0
                ||a.allocationReference()==null||!refs.containsKey(a.itemId()))
                throw new IllegalArgumentException("Invalid signed refund attribution");
            SourceLine s=refs.get(a.itemId());
            BigDecimal exposure=s.unitCost().multiply(BigDecimal.valueOf(s.refunded()));
            BigDecimal claimed="RESTOCKED_CLAIMED".equals(s.disposition())
                ?exposure:BigDecimal.ZERO;
            refund=refund.add(a.signedAmount());
            cost=cost.add(exposure);sensitivity=sensitivity.add(claimed);
            lines.add(new Item(a.itemId(),s.sku(),s.refunded(),
                s.disposition(),a.signedAmount().toPlainString(),
                exposure.toPlainString(),claimed.toPlainString()));
        }
        if(refund.abs().compareTo(sourceRefund.abs())>0)
            throw new IllegalArgumentException("Refund allocations exceed source");
        BigDecimal remaining=sourceRefund.subtract(refund);
        boolean complete=remaining.signum()==0&&refs.size()==allocations.size();
        return new Preview(complete?"MANUAL_EVIDENCE_BALANCED_UNVERIFIED":
            "REFUND_ITEMS_OR_AMOUNT_INCOMPLETE",currency,sourceRefund.toPlainString(),
            refund.toPlainString(),remaining.toPlainString(),cost.toPlainString(),
            sensitivity.toPlainString(),null,false,false,List.copyOf(lines));
    }
}
