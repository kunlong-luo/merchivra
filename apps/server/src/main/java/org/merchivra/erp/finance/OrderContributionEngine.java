package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Conservative per-order provisional contribution: no Orders proceeds as sales,
 * no automatic refund cost reversal and no settlement/bank confirmation.
 */
public final class OrderContributionEngine {
    private OrderContributionEngine() {}
    public record CategoryAmount(String category,String currency,BigDecimal signedAmount) {}
    public record Transaction(String eventId,String coverage,
                              List<CategoryAmount> categoryAmounts) {}
    public record Preview(String readiness,String currency,int reviewedEventCount,
        int pendingOrderLinks,String signedSales,String signedAmazonFees,
        String signedFbaFees,String provisionalUnitCost,String estimatedContribution,
        boolean financialSourceComplete,boolean bankSettlementVerified) {}

    public static Preview calculate(String currency,OrderCostEngine.Preview cost,
        List<Transaction> reviewed,int pendingOrderLinks,boolean truncated) {
        Objects.requireNonNull(currency);
        Objects.requireNonNull(cost);
        Objects.requireNonNull(reviewed);
        if(pendingOrderLinks<0||reviewed.size()>100)
            throw new IllegalArgumentException("Invalid contribution evidence scope");
        String reason=null;
        if(!"COGS_READY_PREVIEW".equals(cost.readiness())
            || cost.provisionalCost()==null
            || !currency.equals(cost.currency()))
            reason="UNIT_COST_NOT_READY";
        else if(truncated)reason="SOURCE_RESULT_TRUNCATED";
        else if(pendingOrderLinks>0)reason="ORDER_LINKS_AWAITING_REVIEW";
        else if(reviewed.isEmpty())reason="NO_REVIEWED_LIVE_FINANCES";

        BigDecimal sales=BigDecimal.ZERO,amazon=BigDecimal.ZERO,fba=BigDecimal.ZERO;
        boolean hasSale=false;
        var keys=new HashSet<String>();
        for(Transaction tx:reviewed) {
            if(tx==null||tx.eventId()==null||!keys.add(tx.eventId())
                ||tx.categoryAmounts()==null)
                throw new IllegalArgumentException("Duplicate or invalid financial source");
            if(!"RECONCILED_UNVERIFIED".equals(tx.coverage())) {
                if(reason==null)reason="FINANCES_CLASSIFICATION_INCOMPLETE";
                continue;
            }
            for(CategoryAmount amount:tx.categoryAmounts()) {
                if(amount==null || amount.signedAmount()==null
                    || amount.currency()==null||amount.category()==null)
                    throw new IllegalArgumentException("Invalid classified amount");
                if(!currency.equals(amount.currency())) {
                    if(reason==null)reason="CURRENCY_MISMATCH";
                    continue;
                }
                switch(amount.category()) {
                    case "PRODUCT_SALES" -> {
                        hasSale=true;sales=sales.add(amount.signedAmount());
                    }
                    case "AMAZON_FEE" -> amazon=amazon.add(amount.signedAmount());
                    case "FBA_FEE" -> fba=fba.add(amount.signedAmount());
                    case "PRODUCT_REFUND" -> {
                        if(reason==null)reason="REFUND_COST_REVERSAL_UNVERIFIED";
                    }
                    case "ADJUSTMENT" -> {
                        if(reason==null)reason="ADJUSTMENT_ATTRIBUTION_UNVERIFIED";
                    }
                    default -> {
                        if(reason==null)reason="UNKNOWN_FINANCES_CATEGORY";
                    }
                }
            }
        }
        if(reason==null && !hasSale)reason="PRODUCT_SALES_NOT_REVIEWED";
        if(reason!=null)
            return new Preview(reason,currency,reviewed.size(),pendingOrderLinks,
                null,null,null,null,null,false,false);
        BigDecimal cogs=new BigDecimal(cost.provisionalCost());
        // Signed platform fees remain exactly as observed; do not subtract
        // a negative fee again or add Orders estimated gross proceeds.
        BigDecimal total=sales.add(amazon).add(fba).subtract(cogs);
        return new Preview("REVIEWED_SOURCE_CONTRIBUTION_PREVIEW",currency,reviewed.size(),
            pendingOrderLinks,sales.toPlainString(),amazon.toPlainString(),
            fba.toPlainString(),cogs.toPlainString(),total.toPlainString(),
            false,false);
    }
}
