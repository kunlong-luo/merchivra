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
        String internalSku,BigDecimal unitCost,String unitCurrency) {}
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
        int covered=0;
        BigDecimal sum=BigDecimal.ZERO;
        for(Item item:items) {
            if(item==null||item.itemId()==null)
                throw new IllegalArgumentException("Invalid order item");
            String issue=!orderLive?"ORDER_NOT_LIVE":
                !itemsObserved?"ITEMS_NOT_OBSERVED":
                orderCreatedAt==null?"ORDER_DATE_UNKNOWN":
                !"LIVE".equals(item.origin())?"ITEM_SOURCE_UNVERIFIED":
                item.quantity()==null||item.quantity()<0?"QUANTITY_UNKNOWN":
                item.sellerSku()==null||item.sellerSku().isBlank()?"SELLER_SKU_UNKNOWN":
                item.internalSku()==null?"MAPPING_MISSING_AS_OF_DATE":
                item.unitCost()==null?"UNIT_COST_MISSING_AS_OF_DATE":
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
