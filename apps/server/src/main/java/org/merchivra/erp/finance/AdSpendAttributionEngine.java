package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Only a manual advertising-spend allocation suggestion, not an Ads API
 * conversion report, customer order attribution or a recognized profit.
 */
public final class AdSpendAttributionEngine {
    private AdSpendAttributionEngine() {}
    public record Share(UUID skuId,int basisPoints) {}
    public record Preview(String status,String spendAmount,String currency,
        int totalBasisPoints,int unallocatedBasisPoints,
        String attributedSpend,String unallocatedSpend,boolean adsApiVerified,
        boolean includedInRecognizedProfit) {}

    public static Preview evaluate(BigDecimal sourceSpend,String currency,List<Share> shares) {
        Objects.requireNonNull(sourceSpend);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(shares);
        if(sourceSpend.signum()<0||shares.size()>200)
            throw new IllegalArgumentException("Invalid ad-spend scope");
        int total=0;
        var keys=new HashSet<UUID>();
        for(var entry:shares) {
            if(entry==null||entry.skuId()==null||!keys.add(entry.skuId())
                ||entry.basisPoints()<1||entry.basisPoints()>10000)
                throw new IllegalArgumentException("Invalid or repeated SKU attribution");
            total+=entry.basisPoints();
        }
        if(total>10000)
            throw new IllegalArgumentException("Combined attribution exceeds 100%");
        BigDecimal attributed=sourceSpend.multiply(BigDecimal.valueOf(total))
            .divide(BigDecimal.valueOf(10000),6,RoundingMode.HALF_UP);
        BigDecimal remaining=sourceSpend.subtract(attributed);
        return new Preview(total==0?"NO_SKU_ALLOCATION":
            total==10000?"FULLY_ALLOCATED_UNVERIFIED":"PARTIALLY_ALLOCATED",
            sourceSpend.toPlainString(),currency,total,10000-total,
            attributed.toPlainString(),remaining.toPlainString(),false,false);
    }
}
