package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Signed per-item refund attribution; independent of return-stock and accounting. */
public final class RefundAmountAllocationEngine {
    private RefundAmountAllocationEngine() {}
    public record Entry(String itemId,BigDecimal signedAmount,String currency) {}
    public record Summary(String status,String currency,String sourceRefundAmount,
        String allocatedAmount,String remainingAmount,int itemCount,
        boolean inventoryReceiptVerified,boolean accountingPosted) {}

    public static Summary evaluate(BigDecimal refundTotal,String currency,
        boolean sourceReady,List<Entry> assigned) {
        Objects.requireNonNull(currency);
        Objects.requireNonNull(assigned);
        if(assigned.size()>1000)throw new IllegalArgumentException("Too many refund items");
        if(!sourceReady||refundTotal==null||refundTotal.signum()==0)
            return new Summary("SOURCE_REFUND_NOT_READY",currency,null,null,null,
                assigned.size(),false,false);
        BigDecimal sum=BigDecimal.ZERO;
        var keys=new HashSet<String>();
        boolean wrongCurrency=false,wrongSign=false;
        for(Entry e:assigned) {
            if(e==null||e.itemId()==null||e.signedAmount()==null||e.currency()==null
                ||e.signedAmount().signum()==0||!keys.add(e.itemId()))
                throw new IllegalArgumentException("Invalid or duplicated refund item attribution");
            if(!currency.equals(e.currency()))wrongCurrency=true;
            if(refundTotal.signum()!=e.signedAmount().signum())wrongSign=true;
            sum=sum.add(e.signedAmount());
        }
        BigDecimal remainder=refundTotal.subtract(sum);
        String status=wrongCurrency?"CURRENCY_MISMATCH":
            wrongSign?"SIGNED_DIRECTION_MISMATCH":
            sum.abs().compareTo(refundTotal.abs())>0?"OVER_ALLOCATED":
            assigned.isEmpty()?"NO_ITEM_ALLOCATIONS":
            remainder.signum()!=0?"PARTIALLY_ALLOCATED":"SIGNED_TOTAL_MATCHES_UNVERIFIED";
        return new Summary(status,currency,refundTotal.toPlainString(),
            wrongCurrency?null:sum.toPlainString(),
            wrongCurrency?null:remainder.toPlainString(),assigned.size(),false,false);
    }

    public static void requireCandidate(BigDecimal total,String currency,
        List<Entry> candidate) {
        Summary state=evaluate(total,currency,true,candidate);
        if("CURRENCY_MISMATCH".equals(state.status())
            ||"SIGNED_DIRECTION_MISMATCH".equals(state.status())
            ||"OVER_ALLOCATED".equals(state.status())
            ||"SOURCE_REFUND_NOT_READY".equals(state.status()))
            throw new IllegalArgumentException("Refund amount allocation is not eligible");
    }
}
