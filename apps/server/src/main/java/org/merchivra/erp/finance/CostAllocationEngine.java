package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * V164 immutable manual cost-allocation plan arithmetic. No financial source
 * authentication, no exchange conversion, GL posting or recognized net profit.
 * Exactly ONE targeting dimension may be used per source proposal.
 */
public final class CostAllocationEngine {
    private CostAllocationEngine() {}
    public enum Target { SKU, ORDER, PERIOD }
    public record Line(UUID skuId, UUID orderId, LocalDate periodAfter,
                       LocalDate periodBefore, String amount) {}
    public record Summary(String state, String currency, String sourceAmount,
                          String allocatedAmount, String unallocatedAmount,
                          int lineCount, boolean fullyAllocated,
                          boolean externalCostVerified, boolean recognizedProfitAllowed) {}

    public static BigDecimal positive(String amount) {
        if(amount==null||!amount.matches("[0-9]{1,14}(?:[.][0-9]{1,6})?"))
            throw new IllegalArgumentException("Cost amount must be exact positive decimal");
        BigDecimal n=new BigDecimal(amount);
        if(n.signum()<=0)throw new IllegalArgumentException("Zero cannot be allocated");
        return n;
    }
    public static String key(Target target,Line line) {
        if(line==null)throw new IllegalArgumentException("Missing cost line");
        if(target==Target.SKU && line.skuId()!=null && line.orderId()==null
            && line.periodAfter()==null && line.periodBefore()==null)
            return line.skuId().toString();
        if(target==Target.ORDER && line.orderId()!=null && line.skuId()==null
            && line.periodAfter()==null && line.periodBefore()==null)
            return line.orderId().toString();
        if(target==Target.PERIOD && line.skuId()==null && line.orderId()==null
            && line.periodAfter()!=null && line.periodBefore()!=null
            && line.periodBefore().isAfter(line.periodAfter())
            && !line.periodBefore().isAfter(line.periodAfter().plusDays(92)))
            return line.periodAfter()+":"+line.periodBefore();
        throw new IllegalArgumentException("Mixed dimensions, missing or invalid target");
    }
    public static Summary evaluate(String sourceAmount,String currency,
                                   String targetKind,List<Line> lines) {
        if(currency==null||!currency.matches("[A-Z]{3}")||lines==null
            ||lines.isEmpty()||lines.size()>100)
            throw new IllegalArgumentException("Cost currency or line limit invalid");
        Target target;
        try {target=Target.valueOf(targetKind);}
        catch(RuntimeException bad) {throw new IllegalArgumentException("Invalid target dimension",bad);}
        BigDecimal source=positive(sourceAmount);
        BigDecimal allocated=BigDecimal.ZERO;
        Set<String> targets=new HashSet<>();
        for(Line line:lines) {
            if(!targets.add(key(target,line)))
                throw new IllegalArgumentException("Repeated target within source allocation");
            allocated=allocated.add(positive(line.amount()));
            if(allocated.compareTo(source)>0)
                throw new IllegalArgumentException("Allocated amount exceeds original source");
        }
        BigDecimal remaining=source.subtract(allocated);
        boolean full=remaining.signum()==0;
        return new Summary(full?"FULLY_ASSIGNED_UNVERIFIED":"PARTIAL_WITH_REMAINDER",
            currency,source.toPlainString(),allocated.toPlainString(),
            remaining.toPlainString(),lines.size(),full,false,false);
    }
}
