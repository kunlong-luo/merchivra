package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderCostEngineTest {
    private static final Instant WHEN=Instant.parse("2026-10-30T05:00:00Z");
    private static OrderCostEngine.Item item(String id,Integer quantity,
            String cost,String currency) {
        return new OrderCostEngine.Item(id,"SELLER-SKU-A",quantity,"LIVE",
            "INTERNAL-A",cost==null?null:new BigDecimal(cost),currency);
    }
    @Test void historicalUnitCostAndQuantityFormOnlyProvisionalCost() {
        var p=OrderCostEngine.evaluate(true,true,WHEN,"USD",List.of(
            item("ITEM-1",2,"4.125000","USD"),
            item("ITEM-2",3,"0.50","USD")));
        assertEquals("COGS_READY_PREVIEW",p.readiness());
        assertEquals("9.750000",p.provisionalCost());
        assertEquals(2,p.coveredItems());
    }
    @Test void unknownCreationTimeNeverUsesCurrentCost() {
        var p=OrderCostEngine.evaluate(true,true,null,"USD",
            List.of(item("ITEM-1",1,"2","USD")));
        assertEquals("ORDER_DATE_UNKNOWN",p.readiness());
        assertNull(p.provisionalCost());
    }
    @Test void partialItemDataDoesNotProduceMisleadingCogs() {
        var p=OrderCostEngine.evaluate(true,true,WHEN,"USD",
            List.of(item("ITEM-1",2,"4","USD"),item("ITEM-2",1,null,null)));
        assertEquals("INCOMPLETE",p.readiness());
        assertEquals(1,p.coveredItems());
        assertNull(p.provisionalCost());
        assertEquals("UNIT_COST_MISSING_AS_OF_DATE",p.items().get(1).issue());
    }
    @Test void crossCurrencyCostMustNotBeCombinedWithoutFx() {
        var p=OrderCostEngine.evaluate(true,true,WHEN,"USD",
            List.of(item("ITEM-1",1,"4","EUR")));
        assertEquals("INCOMPLETE",p.readiness());
        assertEquals("CURRENCY_MISMATCH",p.items().getFirst().issue());
        assertNull(p.provisionalCost());
    }
    @Test void unobservedLinesAndUnverifiedOrdersAreNotReady() {
        assertEquals("ITEMS_NOT_OBSERVED",
            OrderCostEngine.evaluate(true,false,WHEN,"USD",List.of())
            .readiness());
        assertEquals("ORDER_NOT_LIVE",
            OrderCostEngine.evaluate(false,true,WHEN,"USD",
                List.of(item("ITEM-1",1,"3","USD"))).readiness());
    }
    @Test void mappingGapsCannotUseOtherSkuHistory() {
        var i=new OrderCostEngine.Item("ITEM-1","SELLER-SKU-A",1,
            "LIVE",null,new BigDecimal("4.00"),"USD");
        var p=OrderCostEngine.evaluate(true,true,WHEN,"USD",List.of(i));
        assertEquals("INCOMPLETE",p.readiness());
        assertEquals("MAPPING_MISSING_AS_OF_DATE",p.items().getFirst().issue());
    }
}
