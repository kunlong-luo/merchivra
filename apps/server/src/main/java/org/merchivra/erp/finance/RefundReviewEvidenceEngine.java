package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.Objects;

/** Advisory only; never posts physical inventory or refunds to an accounting ledger. */
public final class RefundReviewEvidenceEngine {
    private RefundReviewEvidenceEngine() {}
    public static void requireQuantity(int ordered,int claimed,int otherReviewed) {
        if(ordered<0||claimed<=0||otherReviewed<0
           ||claimed>ordered||otherReviewed>ordered-claimed)
            throw new IllegalArgumentException(
                "Manual refund review quantity exceeds original order quantity");
    }
    /**
     * An estimate of potential cost reversal ONLY when someone claims restock.
     * A claimed return is not an independently verified inventory receipt.
     */
    public static String unverifiedRestockSensitivity(String disposition,
        BigDecimal historicalUnitCost,int claimedQuantity,
        String orderCurrency,String costCurrency) {
        if(!"RESTOCKED_CLAIMED".equals(disposition)
           ||historicalUnitCost==null||orderCurrency==null
           ||!Objects.equals(orderCurrency,costCurrency)
           ||historicalUnitCost.signum()<0||claimedQuantity<1)return null;
        return historicalUnitCost.multiply(BigDecimal.valueOf(claimedQuantity))
            .toPlainString();
    }
}
