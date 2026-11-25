package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Explicit, ephemeral Reports mapping + current V28 leaf interpretation.
 * This is a diagnostic, never a persisted rule, review, or posting.
 */
public final class SettlementCategoryPreviewV273 {
    private SettlementCategoryPreviewV273() {}
    private static final Set<String> CATEGORIES=Set.of("PRODUCT_SALES","PRODUCT_REFUND",
        "AMAZON_FEE","FBA_FEE","ADJUSTMENT");
    public record ReportType(String transactionType,String amountType,String amountDescription) {}
    public record Mapping(ReportType reportType,String category) {}
    record SourceRow(boolean report,String orderRef,ReportType reportType,String category,BigDecimal amount) {}
    private record Bucket(String orderRef,String category,int direction) {}
    private record Amounts(BigDecimal reports,BigDecimal finances) {}
    public record CategoryTotal(String category,int direction,String reports,String finances) {}
    public record Preview(String state,String sourceState,String currency,int unmappedReportRows,
        int unmappedFinanceLeaves,int unusedMappings,int mismatchedBuckets,List<CategoryTotal> categoryTotals,
        boolean mappingApproved,boolean bankVerified,boolean ledgerPosted,String realizedNetProfit) {}

    private static boolean field(String s,int max) {
        return s==null||(!s.isBlank()&&s.length()<=max&&s.matches("[A-Za-z0-9 _./+()\\-]+"));
    }
    static Map<ReportType,String> validate(List<Mapping> input) {
        if(input==null||input.isEmpty()||input.size()>200)throw new IllegalArgumentException("Mapping count");
        var result=new HashMap<ReportType,String>();
        for(var mapping:input) {
            if(mapping==null||mapping.reportType()==null||mapping.category()==null||
                !CATEGORIES.contains(mapping.category()))throw new IllegalArgumentException("Mapping invalid");
            var k=mapping.reportType();
            if(k.transactionType()==null||!field(k.transactionType(),100)||!field(k.amountType(),100)
                ||!field(k.amountDescription(),160)||result.putIfAbsent(k,mapping.category())!=null)
                throw new IllegalArgumentException("Invalid or duplicate report type");
        }
        return Map.copyOf(result);
    }
    static Preview blocked(String source,String currency,String state) {
        return new Preview(state,source,currency,0,0,0,0,List.of(),false,false,false,null);
    }
    static Preview evaluate(String source,String currency,List<SourceRow> rows,Map<ReportType,String> mappings) {
        if(!"SOURCE_AMOUNTS_AGREE_UNVERIFIED".equals(source))
            return blocked(source,currency,"SOURCE_COMPARISON_HOLD");
        if(rows.size()>30000)return blocked(source,currency,"CATEGORY_POPULATION_UNBOUNDED");
        if(rows.isEmpty()||rows.stream().noneMatch(SourceRow::report)||rows.stream().allMatch(SourceRow::report))
            return blocked(source,currency,"CATEGORY_POPULATION_INCOMPLETE");
        var used=new HashSet<ReportType>();
        var reportSums=new HashMap<Bucket,BigDecimal>();
        var financeSums=new HashMap<Bucket,BigDecimal>();
        int missingReport=0,missingFinance=0;
        for(var row:rows) {
            if(row.amount()==null)return blocked(source,currency,"CATEGORY_POPULATION_INCOMPLETE");
            String category=row.report()?mappings.get(row.reportType()):row.category();
            if(category==null||!CATEGORIES.contains(category)) {
                if(row.report())missingReport++;else missingFinance++;
                continue;
            }
            if(row.report())used.add(row.reportType());
            var key=new Bucket(row.orderRef(),category,row.amount().signum());
            (row.report()?reportSums:financeSums).merge(key,row.amount(),BigDecimal::add);
        }
        int unused=mappings.size()-used.size();
        if(missingReport!=0||missingFinance!=0||unused!=0)
            return new Preview("CATEGORY_MAPPING_INCOMPLETE",source,currency,missingReport,missingFinance,
                unused,0,List.of(),false,false,false,null);
        var keys=new HashSet<>(reportSums.keySet());keys.addAll(financeSums.keySet());
        int conflicts=0;
        var totals=new TreeMap<String,Amounts>();
        for(var key:keys) {
            var a=reportSums.get(key);var b=financeSums.get(key);
            if(a==null||b==null||a.compareTo(b)!=0)conflicts++;
            String label=key.category()+":"+key.direction();
            totals.merge(label,new Amounts(a,b),(left,right)->new Amounts(
                add(left.reports(),right.reports()),add(left.finances(),right.finances())));
        }
        var output=new ArrayList<CategoryTotal>();
        totals.forEach((key,value)->{
            String[] parts=key.split(":");
            output.add(new CategoryTotal(parts[0],Integer.parseInt(parts[1]),
                value.reports()==null?null:value.reports().toPlainString(),
                value.finances()==null?null:value.finances().toPlainString()));
        });
        // Partial side totals cannot imply coverage; only expose fully mapped rows.
        return new Preview(conflicts==0?"LOCAL_CATEGORY_AMOUNTS_AGREE_UNVERIFIED":"ORDER_CATEGORY_AMOUNT_DIFFERENCE",
            source,currency,0,0,0,conflicts,List.copyOf(output),false,false,false,null);
    }
    private static BigDecimal add(BigDecimal a,BigDecimal b) {
        return a==null?b:b==null?a:a.add(b);
    }
}
