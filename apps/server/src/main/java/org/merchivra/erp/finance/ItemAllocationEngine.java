package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact signed amounts, no arbitrary percentage, no estimated Orders proceeds. */
public final class ItemAllocationEngine {
    private ItemAllocationEngine(){}
    public static final Set<String> ELIGIBLE=Set.of("PRODUCT_SALES","AMAZON_FEE","FBA_FEE");
    public record Line(String itemId,String category,BigDecimal signedAmount,
        String currency,String reference) {}
    public record Residual(String category,String currency,String sourceTotal,
        String allocated,String remainder,boolean exact) {}
    public record Summary(boolean fullyAllocated,List<Residual> residuals) {}

    public static Summary summarize(String currency,Map<String,BigDecimal> sources,
        List<Line> lines,Set<String> validItems) {
        if(currency==null||!currency.matches("[A-Z]{3}")||sources==null
            ||sources.isEmpty()||lines==null||lines.size()>2000||validItems==null
            ||validItems.isEmpty())throw new IllegalArgumentException("Invalid allocation scope");
        Map<String,BigDecimal> totals=new HashMap<>();
        for(var e:sources.entrySet()) {
            if(!ELIGIBLE.contains(e.getKey())||e.getValue()==null)
                throw new IllegalArgumentException("Unsupported financial category");
            totals.put(e.getKey(),BigDecimal.ZERO);
        }
        Set<String> seen=new java.util.HashSet<>();
        for(var l:lines) {
            if(l==null||l.signedAmount()==null||l.signedAmount().signum()==0
                ||!currency.equals(l.currency())||!validItems.contains(l.itemId())
                ||!seen.add(l.itemId()+"\u0000"+l.category())
                ||!sources.containsKey(l.category()))
                throw new IllegalArgumentException("Invalid item/category allocation");
            BigDecimal source=sources.get(l.category());
            if(source.signum()==0||l.signedAmount().signum()!=source.signum())
                throw new IllegalArgumentException("Wrong source amount sign");
            totals.merge(l.category(),l.signedAmount(),BigDecimal::add);
        }
        boolean complete=true;
        List<Residual> residuals=new ArrayList<>();
        for(var category:new java.util.TreeSet<>(sources.keySet())) {
            BigDecimal source=sources.get(category);
            BigDecimal allocated=totals.get(category);
            if(allocated.abs().compareTo(source.abs())>0)
                throw new IllegalArgumentException("Allocated amount exceeds source");
            BigDecimal remaining=source.subtract(allocated);
            boolean exact=remaining.signum()==0;
            if(!exact)complete=false;
            residuals.add(new Residual(category,currency,source.toPlainString(),
                allocated.toPlainString(),remaining.toPlainString(),exact));
        }
        return new Summary(complete,List.copyOf(residuals));
    }
}
