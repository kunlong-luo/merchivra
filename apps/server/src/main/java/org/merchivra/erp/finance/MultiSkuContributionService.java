package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only multi-SKU contribution projection. Every reconciled Finances event
 * must have a current, independently approved, precisely conserving item split.
 */
@Service
public class MultiSkuContributionService {
    private final ItemAllocationService allocations;
    public MultiSkuContributionService(ItemAllocationService allocations) {
        this.allocations=allocations;
    }
    public record Sku(String sku,int units,int reviewedEvents,String currency,
        String signedSales,String signedAmazonFees,String signedFbaFees,
        String provisionalCogs,String contribution) {}
    public record Result(String state,List<Sku> skus,int eventCount,
        boolean financialSourceComplete,boolean bankVerified) {}

    private static final class Bucket {
        int units,events;
        BigDecimal sales=BigDecimal.ZERO,amazon=BigDecimal.ZERO,
            fba=BigDecimal.ZERO,cost=BigDecimal.ZERO;
    }
    private static BigDecimal read(String amount){
        if(amount==null)throw new IllegalArgumentException("Unready source amount");
        return new BigDecimal(amount);
    }
    private static Result blocked(String state,int events){
        return new Result(state,List.of(),events,false,false);
    }
    public Result preview(UUID tenant,UUID mp,UUID orderId,
        OrderCostEngine.Preview costs,OrderContributionEngine.Preview financial) {
        if(costs==null||financial==null)
            throw new IllegalArgumentException("Order evidence required");
        if(!"COGS_READY_PREVIEW".equals(costs.readiness())
            ||!"REVIEWED_SOURCE_CONTRIBUTION_PREVIEW".equals(financial.readiness()))
            return blocked("ORDER_FINANCIAL_SOURCE_INCOMPLETE",0);
        Map<String,Bucket> buckets=new LinkedHashMap<>();
        Map<String,String> skus=new HashMap<>();
        for(var line:costs.items()) {
            if(!"READY".equals(line.issue())||line.internalSku()==null
                ||line.quantity()==null||line.quantity()<=0
                ||line.extendedCost()==null||skus.putIfAbsent(line.itemId(),
                    line.internalSku())!=null)
                return blocked("ORDER_ITEM_EVIDENCE_INCOMPLETE",0);
            Bucket b=buckets.computeIfAbsent(line.internalSku(),ignored->new Bucket());
            b.units+=line.quantity();
            b.cost=b.cost.add(read(line.extendedCost()));
        }
        if(buckets.size()<2)
            return blocked("MULTI_SKU_NOT_REQUIRED",0);
        var eventIds=allocations.linkedEvents(tenant,mp,orderId);
        if(eventIds.isEmpty()||eventIds.size()>100
            ||eventIds.size()!=financial.reviewedEventCount())
            return blocked("LINKED_EVENT_SET_INCOMPLETE",eventIds.size());
        BigDecimal sumSale=BigDecimal.ZERO,sumAmazon=BigDecimal.ZERO,
            sumFba=BigDecimal.ZERO;
        try {
            for(UUID event:eventIds) {
                var view=allocations.preview(tenant,mp,event);
                if(!"APPROVED".equals(view.state())||!view.evidenceCurrent()
                    ||!view.amounts().fullyAllocated()
                    ||!view.orderId().equals(orderId.toString())
                    ||!view.currency().equals(financial.currency()))
                    return blocked("ALLOCATIONS_AWAITING_INDEPENDENT_APPROVAL",
                        eventIds.size());
                var touched=new java.util.HashSet<String>();
                for(var allocation:view.lines()) {
                    String sku=skus.get(allocation.externalItemId());
                    if(sku==null) return blocked("ALLOCATION_ITEM_NO_LONGER_MAPPED",
                        eventIds.size());
                    Bucket b=buckets.get(sku);
                    BigDecimal amount=read(allocation.signedAmount());
                    touched.add(sku);
                    switch(allocation.category()) {
                        case "PRODUCT_SALES" -> {
                            b.sales=b.sales.add(amount);sumSale=sumSale.add(amount);
                        }
                        case "AMAZON_FEE" -> {
                            b.amazon=b.amazon.add(amount);sumAmazon=sumAmazon.add(amount);
                        }
                        case "FBA_FEE" -> {
                            b.fba=b.fba.add(amount);sumFba=sumFba.add(amount);
                        }
                        default -> {
                            return blocked("UNSUPPORTED_FINANCES_COMPONENT",eventIds.size());
                        }
                    }
                }
                for(String sku:touched)buckets.get(sku).events++;
            }
        } catch(ResponseStatusException changed) {
            return blocked("ALLOCATION_EVIDENCE_CHANGED",eventIds.size());
        }
        if(sumSale.compareTo(read(financial.signedSales()))!=0
            ||sumAmazon.compareTo(read(financial.signedAmazonFees()))!=0
            ||sumFba.compareTo(read(financial.signedFbaFees()))!=0)
            return blocked("ORDER_EVENT_TOTALS_CHANGED",eventIds.size());
        BigDecimal cogs=buckets.values().stream().map(b->b.cost)
            .reduce(BigDecimal.ZERO,BigDecimal::add);
        if(cogs.compareTo(read(financial.provisionalUnitCost()))!=0)
            return blocked("ORDER_COST_TOTALS_CHANGED",eventIds.size());
        var rows=buckets.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e->{
                Bucket b=e.getValue();
                return new Sku(e.getKey(),b.units,b.events,financial.currency(),
                    b.sales.toPlainString(),b.amazon.toPlainString(),
                    b.fba.toPlainString(),b.cost.toPlainString(),
                    b.sales.add(b.amazon).add(b.fba).subtract(b.cost).toPlainString());
            }).toList();
        BigDecimal whole=rows.stream().map(row->read(row.contribution()))
            .reduce(BigDecimal.ZERO,BigDecimal::add);
        if(whole.compareTo(read(financial.estimatedContribution()))!=0)
            return blocked("ORDER_CONTRIBUTION_TOTALS_CHANGED",eventIds.size());
        // Amounts may be source-attributed, but the period and bank are not proven.
        return new Result("REVIEWED_MULTI_SKU_CONTRIBUTION_PREVIEW",
            List.copyOf(rows),eventIds.size(),false,false);
    }
}
