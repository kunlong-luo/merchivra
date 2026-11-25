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

    /**
     * V246 arithmetic only: one immutable receipt cost amount, an explicitly
     * chosen quantity denominator, and caller-supplied non-overlapping
     * half-open unit ranges. Never invent receipt/dispatch/return identities.
     * No FX, capitalization policy, external evidence, or ledger permission.
     */
    public record ReceiptRange(UUID claimId,int fromInclusive,int toExclusive) {}
    public record ReceiptShare(UUID claimId,int fromInclusive,int toExclusive,
        String candidateAmount) {}
    public record ReceiptPartition(String currency,String sourceAmount,int basisQuantity,
        int allocatedQuantity,int unallocatedQuantity,String allocatedAmount,
        String unallocatedAmount,List<ReceiptShare> shares) {}

    public static ReceiptPartition partitionReceiptAmount(String amount,String currency,
        int basisQuantity,List<ReceiptRange> ranges) {
        if(currency==null||!currency.matches("[A-Z]{3}")||basisQuantity<=0
            ||ranges==null||ranges.size()>1000||amount==null
            ||!amount.matches("[0-9]{1,14}(?:[.][0-9]{1,6})?"))
            throw new IllegalArgumentException("Invalid receipt amount or quantity basis");
        BigDecimal total=new BigDecimal(amount).setScale(6);
        var sorted=new java.util.ArrayList<ReceiptRange>();
        Set<UUID> ids=new HashSet<>();
        for(var range:ranges) {
            if(range==null||range.claimId()==null||!ids.add(range.claimId())
                ||range.fromInclusive()<0||range.toExclusive()<=range.fromInclusive()
                ||range.toExclusive()>basisQuantity)
                throw new IllegalArgumentException("Invalid or duplicate receipt claim range");
            sorted.add(range);
        }
        sorted.sort(java.util.Comparator.comparingInt(ReceiptRange::fromInclusive));
        var shares=new java.util.ArrayList<ReceiptShare>();
        int lastEnd=0,quantity=0;
        BigDecimal allocated=BigDecimal.ZERO.setScale(6);
        for(var range:sorted) {
            if(range.fromInclusive()<lastEnd)
                throw new IllegalArgumentException("Overlapping receipt claim ranges");
            // Difference of cumulative floors, not independently rounded unit
            // prices: adjacent slices telescope exactly back to original amount.
            BigDecimal share=receiptPrefix(total,basisQuantity,range.toExclusive())
                .subtract(receiptPrefix(total,basisQuantity,range.fromInclusive()));
            allocated=allocated.add(share);
            quantity=Math.addExact(quantity,range.toExclusive()-range.fromInclusive());
            shares.add(new ReceiptShare(range.claimId(),range.fromInclusive(),
                range.toExclusive(),share.toPlainString()));
            lastEnd=range.toExclusive();
        }
        return new ReceiptPartition(currency,total.toPlainString(),basisQuantity,
            quantity,basisQuantity-quantity,allocated.toPlainString(),
            total.subtract(allocated).toPlainString(),List.copyOf(shares));
    }

    private static BigDecimal receiptPrefix(BigDecimal total,int basis,int boundary) {
        return total.multiply(BigDecimal.valueOf(boundary))
            .divide(BigDecimal.valueOf(basis),6,java.math.RoundingMode.DOWN);
    }

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
        // V164 actual PG18 stores NUMERIC(20,6). Stabilize all public decimal
        // amounts without changing value or rounding, so API/CSV snapshots and
        // independent replay checks cannot differ only by input scale.
        return new Summary(full?"FULLY_ASSIGNED_UNVERIFIED":"PARTIAL_WITH_REMAINDER",
            currency,source.setScale(6,java.math.RoundingMode.UNNECESSARY).toPlainString(),
            allocated.setScale(6,java.math.RoundingMode.UNNECESSARY).toPlainString(),
            remaining.setScale(6,java.math.RoundingMode.UNNECESSARY).toPlainString(),
            lines.size(),full,false,false);
    }
}
