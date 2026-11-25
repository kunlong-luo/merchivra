package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read-only COGS estimate, not inventory ledger or a profit recognition rule. */
public final class OrderCostEngine {
    private OrderCostEngine() {}
    public record Item(String itemId,String sellerSku,Integer quantity,String origin,
        String internalSku,BigDecimal unitCost,String unitCurrency,
        Long mappingCandidateCount,Long costVersionCandidateCount) {
        // Preserve callers of the earlier read model. Database-backed callers
        // must supply actual whole-window candidate counts.
        public Item(String itemId,String sellerSku,Integer quantity,String origin,
            String internalSku,BigDecimal unitCost,String unitCurrency) {
            this(itemId,sellerSku,quantity,origin,internalSku,unitCost,unitCurrency,
                internalSku==null?0L:1L,unitCost==null?0L:1L);
        }
    }
    public record Line(String itemId,String sellerSku,Integer quantity,String internalSku,
        String unitCost,String currency,String extendedCost,String issue) {}
    public record Preview(String readiness,String currency,String orderCreatedAt,
        int itemCount,int coveredItems,String provisionalCost,List<Line> items) {}

    public static Preview evaluate(boolean orderLive,boolean itemsObserved,
        Instant orderCreatedAt,String currency,List<Item> items) {
        Objects.requireNonNull(currency);
        Objects.requireNonNull(items);
        if(items.size()>1000)throw new IllegalArgumentException("Order has too many items");
        var rows=new ArrayList<Line>();
        var seenItems=new java.util.HashSet<String>();
        int covered=0;
        BigDecimal sum=BigDecimal.ZERO;
        for(Item item:items) {
            if(item==null||item.itemId()==null||item.itemId().isBlank()
                ||!seenItems.add(item.itemId()))
                throw new IllegalArgumentException("Invalid or duplicated current order item");
            String issue=!orderLive?"ORDER_NOT_LIVE":
                !itemsObserved?"ITEMS_NOT_OBSERVED":
                orderCreatedAt==null?"ORDER_DATE_UNKNOWN":
                !"LIVE".equals(item.origin())?"ITEM_SOURCE_UNVERIFIED":
                item.quantity()==null||item.quantity()<0?"QUANTITY_UNKNOWN":
                item.sellerSku()==null||item.sellerSku().isBlank()?"SELLER_SKU_UNKNOWN":
                item.mappingCandidateCount()==null||item.mappingCandidateCount()<0
                    ||item.costVersionCandidateCount()==null
                    ||item.costVersionCandidateCount()<0?"COST_EVIDENCE_COUNT_UNKNOWN":
                item.mappingCandidateCount()>1?"MAPPING_AMBIGUOUS_AS_OF_DATE":
                item.mappingCandidateCount()==0||item.internalSku()==null
                    ||item.internalSku().isBlank()?"MAPPING_MISSING_AS_OF_DATE":
                item.costVersionCandidateCount()>1?"UNIT_COST_VERSION_OVERLAP_AS_OF_DATE":
                item.costVersionCandidateCount()==0||item.unitCost()==null?"UNIT_COST_MISSING_AS_OF_DATE":
                item.unitCost().signum()<0||item.unitCost().stripTrailingZeros().scale()>6
                    ||item.unitCost().compareTo(new BigDecimal("100000000000000"))>=0
                    ?"UNIT_COST_INVALID":
                !currency.equals(item.unitCurrency())?"CURRENCY_MISMATCH":"READY";
            String extended=null;
            if("READY".equals(issue)) {
                BigDecimal value=item.unitCost().multiply(BigDecimal.valueOf(item.quantity()));
                sum=sum.add(value);
                extended=value.toPlainString();
                covered++;
            }
            rows.add(new Line(item.itemId(),item.sellerSku(),item.quantity(),
                item.internalSku(),item.unitCost()==null?null:item.unitCost().toPlainString(),
                item.unitCurrency(),extended,issue));
        }
        String state=!orderLive?"ORDER_NOT_LIVE":
            !itemsObserved?"ITEMS_NOT_OBSERVED":
            orderCreatedAt==null?"ORDER_DATE_UNKNOWN":
            items.isEmpty()?"NO_ITEMS":
            covered!=items.size()?"INCOMPLETE":"COGS_READY_PREVIEW";
        return new Preview(state,currency,orderCreatedAt==null?null:orderCreatedAt.toString(),
            items.size(),covered,"COGS_READY_PREVIEW".equals(state)?sum.toPlainString():null,
            List.copyOf(rows));
    }
}
