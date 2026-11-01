package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V61 read-only bounded page rollup of already-reviewed order contribution.
 * This is explicitly NOT realized profit, Ads net margin or bank settlement.
 */
public final class SkuProfitPreviewEngine {
    private SkuProfitPreviewEngine(){}

    public record Input(String orderId,String externalOrderId,String orderedAt,
        String sku,int units,String costReadiness,
        boolean multiSku,OrderContributionEngine.Preview contribution) {}
    public record OrderRow(String orderId,String externalOrderId,String orderedAt,
        String sku,int units,String readiness,String currency,
        int reviewedEventCount,String sales,String amazonFees,String fbaFees,
        String cogs,String contribution) {}
    public record SkuRow(String sku,int orderCount,int units,String currency,
        String sales,String amazonFees,String fbaFees,String cogs,String contribution) {}
    public record Totals(int scannedOrders,int eligibleOrders,int blockedOrders,
        int multiSkuOrders,int sourceEvents,String currency,String sales,
        String amazonFees,String fbaFees,String cogs,String contribution,
        boolean completePeriod,boolean financialSourceComplete,
        boolean bankVerified,boolean ledgerPosted) {}
    public record Rollup(Totals totals,List<SkuRow> skus,List<OrderRow> orders) {}

    private record Numbers(BigDecimal sale,BigDecimal amazon,BigDecimal fba,
        BigDecimal cost,BigDecimal contribution) {
        static Numbers zero(){return new Numbers(BigDecimal.ZERO,BigDecimal.ZERO,
            BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO);}
        Numbers plus(Numbers other){
            return new Numbers(sale.add(other.sale()),amazon.add(other.amazon()),
                fba.add(other.fba()),cost.add(other.cost()),
                contribution.add(other.contribution()));
        }
    }
    private static final class Bucket {
        int count,units;
        Numbers total=Numbers.zero();
    }

    public static Rollup calculate(String currency,List<Input> inputs,
        boolean hasMore,boolean earlierPages) {
        if(currency==null||!currency.matches("[A-Z]{3}")||inputs==null
            ||inputs.size()>50)throw new IllegalArgumentException("Invalid bounded rollup");
        Map<String,Bucket> groups=new LinkedHashMap<>();
        List<OrderRow> orders=new ArrayList<>();
        Numbers total=Numbers.zero();
        int eligible=0,multi=0,events=0;
        for(var input:inputs) {
            if(input==null||input.orderId()==null||input.contribution()==null
                ||input.units()<0)throw new IllegalArgumentException("Invalid source order");
            var p=input.contribution();
            if(!currency.equals(p.currency()))
                throw new IllegalArgumentException("Mixed original-currency reports forbidden");
            // An order-level finance transaction is attributable to a SKU
            // only if the whole order's current items map to exactly that SKU.
            String state=input.multiSku()?"MULTI_SKU_UNALLOCATED":
                input.sku()==null?"SKU_NOT_MAPPED":
                input.units()==0?"NO_POSITIVE_UNITS":
                !"COGS_READY_PREVIEW".equals(input.costReadiness())
                    ?input.costReadiness():
                !"REVIEWED_SOURCE_CONTRIBUTION_PREVIEW".equals(p.readiness())
                    ?p.readiness():"ELIGIBLE_PREVIEW";
            boolean ready="ELIGIBLE_PREVIEW".equals(state);
            String sales=null,amazon=null,fba=null,cogs=null,contribution=null;
            if(input.multiSku())multi++;
            if(ready) {
                var values=new Numbers(new BigDecimal(p.signedSales()),
                    new BigDecimal(p.signedAmazonFees()),
                    new BigDecimal(p.signedFbaFees()),
                    new BigDecimal(p.provisionalUnitCost()),
                    new BigDecimal(p.estimatedContribution()));
                if(values.sale().add(values.amazon()).add(values.fba())
                    .subtract(values.cost()).compareTo(values.contribution())!=0)
                    throw new IllegalArgumentException("Inconsistent financial source totals");
                sales=values.sale().toPlainString();
                amazon=values.amazon().toPlainString();
                fba=values.fba().toPlainString();
                cogs=values.cost().toPlainString();
                contribution=values.contribution().toPlainString();
                total=total.plus(values);
                Bucket b=groups.computeIfAbsent(input.sku(),ignored->new Bucket());
                b.count++;b.units+=input.units();
                b.total=b.total.plus(values);
                eligible++;
                events+=p.reviewedEventCount();
            }
            orders.add(new OrderRow(input.orderId(),input.externalOrderId(),
                input.orderedAt(),input.sku(),input.units(),state,currency,
                ready?p.reviewedEventCount():0,sales,amazon,fba,cogs,contribution));
        }
        List<SkuRow> skus=groups.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(item->{
                Bucket b=item.getValue();
                return new SkuRow(item.getKey(),b.count,b.units,currency,
                    b.total.sale().toPlainString(),b.total.amazon().toPlainString(),
                    b.total.fba().toPlainString(),b.total.cost().toPlainString(),
                    b.total.contribution().toPlainString());
            }).toList();
        return new Rollup(new Totals(inputs.size(),eligible,inputs.size()-eligible,
            multi,events,currency,total.sale().toPlainString(),
            total.amazon().toPlainString(),total.fba().toPlainString(),
            total.cost().toPlainString(),total.contribution().toPlainString(),
            // A page, even if exhaustive, does not prove all Ads, refunds,
            // pending/unlinked Finances or unsettled liabilities are covered.
            !hasMore&&!earlierPages,false,false,false),
            List.copyOf(skus),List.copyOf(orders));
    }

    /**
     * V62: elevate only explicitly approved, perfectly conserving multi-SKU
     * source allocations into the same bounded page. A multi-SKU order counts
     * once in eligibleOrders, once in page totals, and once per distinct SKU
     * in the SKU group. Existing single-SKU logic remains unchanged.
     */
    public static Rollup calculate(String currency,List<Input> inputs,
        boolean hasMore,boolean earlierPages,
        Map<String,MultiSkuContributionService.Result> multiReviewed) {
        Rollup base=calculate(currency,inputs,hasMore,earlierPages);
        if(multiReviewed==null||multiReviewed.isEmpty())return base;
        Map<String,Bucket> groups=new LinkedHashMap<>();
        for(var s:base.skus()) {
            var b=new Bucket();b.count=s.orderCount();b.units=s.units();
            b.total=new Numbers(new BigDecimal(s.sales()),
                new BigDecimal(s.amazonFees()),new BigDecimal(s.fbaFees()),
                new BigDecimal(s.cogs()),new BigDecimal(s.contribution()));
            groups.put(s.sku(),b);
        }
        Map<String,Input> byId=new java.util.HashMap<>();
        for(var i:inputs)byId.put(i.orderId(),i);
        Numbers total=new Numbers(new BigDecimal(base.totals().sales()),
            new BigDecimal(base.totals().amazonFees()),
            new BigDecimal(base.totals().fbaFees()),
            new BigDecimal(base.totals().cogs()),
            new BigDecimal(base.totals().contribution()));
        int eligible=base.totals().eligibleOrders();
        int events=base.totals().sourceEvents();
        List<OrderRow> orders=new ArrayList<>();
        for(var row:base.orders()) {
            Input input=byId.get(row.orderId());
            MultiSkuContributionService.Result result=multiReviewed.get(row.orderId());
            if(input==null||!input.multiSku()||result==null) {
                orders.add(row);continue;
            }
            if(!"REVIEWED_MULTI_SKU_CONTRIBUTION_PREVIEW".equals(result.state())) {
                orders.add(new OrderRow(row.orderId(),row.externalOrderId(),
                    row.orderedAt(),row.sku(),row.units(),result.state(),currency,
                    0,null,null,null,null,null));
                continue;
            }
            var p=input.contribution();
            if(!"COGS_READY_PREVIEW".equals(input.costReadiness())
                ||!"REVIEWED_SOURCE_CONTRIBUTION_PREVIEW".equals(p.readiness())
                ||result.skus().size()<2||result.eventCount()!=p.reviewedEventCount())
                throw new IllegalArgumentException("Stale multi-SKU source evidence");
            Numbers perOrder=Numbers.zero();
            int units=0;
            var seen=new java.util.HashSet<String>();
            for(var s:result.skus()) {
                if(s.sku()==null||!seen.add(s.sku())||!currency.equals(s.currency())
                    ||s.units()<=0)throw new IllegalArgumentException("Unmapped SKU");
                var values=new Numbers(new BigDecimal(s.signedSales()),
                    new BigDecimal(s.signedAmazonFees()),new BigDecimal(s.signedFbaFees()),
                    new BigDecimal(s.provisionalCogs()),new BigDecimal(s.contribution()));
                if(values.sale().add(values.amazon()).add(values.fba())
                    .subtract(values.cost()).compareTo(values.contribution())!=0)
                    throw new IllegalArgumentException("SKU amount conservation failure");
                perOrder=perOrder.plus(values);
                units+=s.units();
            }
            if(units!=input.units() || perOrder.sale().compareTo(new BigDecimal(p.signedSales()))!=0
                ||perOrder.amazon().compareTo(new BigDecimal(p.signedAmazonFees()))!=0
                ||perOrder.fba().compareTo(new BigDecimal(p.signedFbaFees()))!=0
                ||perOrder.cost().compareTo(new BigDecimal(p.provisionalUnitCost()))!=0
                ||perOrder.contribution().compareTo(new BigDecimal(p.estimatedContribution()))!=0)
                throw new IllegalArgumentException("Source totals changed after allocation review");
            // Update only after the entire order has passed every check.
            for(var s:result.skus()) {
                Bucket bucket=groups.computeIfAbsent(s.sku(),ignored->new Bucket());
                bucket.count++;bucket.units+=s.units();
                bucket.total=bucket.total.plus(new Numbers(
                    new BigDecimal(s.signedSales()),new BigDecimal(s.signedAmazonFees()),
                    new BigDecimal(s.signedFbaFees()),new BigDecimal(s.provisionalCogs()),
                    new BigDecimal(s.contribution())));
            }
            total=total.plus(perOrder);eligible++;events+=p.reviewedEventCount();
            orders.add(new OrderRow(row.orderId(),row.externalOrderId(),
                row.orderedAt(),null,row.units(),"ELIGIBLE_MULTI_SKU_PREVIEW",
                currency,p.reviewedEventCount(),p.signedSales(),p.signedAmazonFees(),
                p.signedFbaFees(),p.provisionalUnitCost(),p.estimatedContribution()));
        }
        var skus=groups.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(item->{
                Bucket group=item.getValue();Numbers n=group.total;
                return new SkuRow(item.getKey(),group.count,group.units,currency,
                    n.sale().toPlainString(),n.amazon().toPlainString(),
                    n.fba().toPlainString(),n.cost().toPlainString(),
                    n.contribution().toPlainString());
            }).toList();
        Totals prior=base.totals();
        return new Rollup(new Totals(prior.scannedOrders(),eligible,
            prior.scannedOrders()-eligible,prior.multiSkuOrders(),events,currency,
            total.sale().toPlainString(),total.amazon().toPlainString(),
            total.fba().toPlainString(),total.cost().toPlainString(),
            total.contribution().toPlainString(),prior.completePeriod(),
            false,false,false),List.copyOf(skus),List.copyOf(orders));
    }
}
