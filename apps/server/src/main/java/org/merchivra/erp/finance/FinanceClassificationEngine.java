package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Non-posting read model. Source event total is NOT a category amount.
 * Only leaf nodes can participate, and only after every leaf can be reconciled.
 */
public final class FinanceClassificationEngine {
    private FinanceClassificationEngine() {}
    public record Leaf(String path,String sourceType,BigDecimal amount,String currency) {}
    public record Line(String path,String sourceType,String amount,String currency,
                       String proposedCategory) {}
    public record CategoryTotal(String category,String currency,String signedAmount) {}
    public record Preview(String coverage,String sourceTotal,String currency,
                          String leafTotal,int leafCount,int mappedCount,
                          List<Line> leaves,List<CategoryTotal> provisionalTotals) {}

    public static Preview evaluate(BigDecimal sourceTotal,String currency,
        List<Leaf> leaves,Map<String,String> activeRules) {
        Objects.requireNonNull(sourceTotal);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(leaves);
        Objects.requireNonNull(activeRules);
        if(leaves.size()>2000)throw new IllegalArgumentException("Too many detail lines");
        var lines=new ArrayList<Line>();
        var totals=new LinkedHashMap<String,BigDecimal>();
        BigDecimal total=BigDecimal.ZERO;
        boolean missing=false,differentCurrency=false,unmapped=false;
        int mapped=0;
        for(Leaf leaf:leaves) {
            if(leaf==null || leaf.path()==null || leaf.sourceType()==null)
                throw new IllegalArgumentException("Invalid financial leaf");
            String category=activeRules.getOrDefault(leaf.sourceType(),"UNCLASSIFIED");
            boolean classified=!"UNCLASSIFIED".equals(category);
            if(classified)mapped++;else unmapped=true;
            if(leaf.amount()==null || leaf.currency()==null)missing=true;
            else {
                if(!currency.equals(leaf.currency()))differentCurrency=true;
                total=total.add(leaf.amount());
                if(classified)totals.merge(category,leaf.amount(),BigDecimal::add);
            }
            lines.add(new Line(leaf.path(),leaf.sourceType(),
                leaf.amount()==null?null:leaf.amount().toPlainString(),
                leaf.currency(),category));
        }
        String state=leaves.isEmpty()?"NO_BREAKDOWNS":
            missing?"MISSING_AMOUNT":
            differentCurrency?"CURRENCY_MISMATCH":
            unmapped?"UNMAPPED_LEAF":
            total.compareTo(sourceTotal)!=0?"AMOUNT_MISMATCH":
            "RECONCILED_UNVERIFIED";
        var sums=new ArrayList<CategoryTotal>();
        // Never publish sums until leaf coverage and source total reconcile.
        if("RECONCILED_UNVERIFIED".equals(state))
            totals.forEach((category,amount)->sums.add(new CategoryTotal(
                category,currency,amount.toPlainString())));
        return new Preview(state,sourceTotal.toPlainString(),currency,
            missing||differentCurrency?null:total.toPlainString(),leaves.size(),mapped,
            List.copyOf(lines),List.copyOf(sums));
    }
}
