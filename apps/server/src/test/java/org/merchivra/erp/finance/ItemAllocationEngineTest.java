package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemAllocationEngineTest {
    private static BigDecimal n(String v){return new BigDecimal(v);}
    private static ItemAllocationEngine.Line l(String item,String category,String n) {
        return new ItemAllocationEngine.Line(item,category,n(n),"USD","EVIDENCE-2026-001");
    }
    @Test void partialAndFullSignedConservationNeverGuessFractions() {
        var source=Map.of("PRODUCT_SALES",n("120"),"AMAZON_FEE",n("-12.50"),
            "FBA_FEE",n("-7.50"));
        var items=Set.of("item-1","item-2");
        var partial=ItemAllocationEngine.summarize("USD",source,List.of(
            l("item-1","PRODUCT_SALES","60"),
            l("item-1","AMAZON_FEE","-5")),
            items);
        assertFalse(partial.fullyAllocated());
        assertEquals("60",partial.residuals().stream()
            .filter(x->x.category().equals("PRODUCT_SALES")).findFirst().orElseThrow()
            .remainder());
        var complete=ItemAllocationEngine.summarize("USD",source,List.of(
            l("item-1","PRODUCT_SALES","60"),l("item-2","PRODUCT_SALES","60"),
            l("item-1","AMAZON_FEE","-5"),l("item-2","AMAZON_FEE","-7.50"),
            l("item-1","FBA_FEE","-3.00"),l("item-2","FBA_FEE","-4.50")),
            items);
        assertTrue(complete.fullyAllocated());
        assertTrue(complete.residuals().stream().allMatch(ItemAllocationEngine.Residual::exact));
    }
    @Test void rejectsOverAllocationSignFlipDuplicateItemCrossCurrencyAndRefund() {
        var source=Map.of("AMAZON_FEE",n("-5"));
        var items=Set.of("a","b");
        assertThrows(IllegalArgumentException.class,()->
            ItemAllocationEngine.summarize("USD",source,List.of(l("a","AMAZON_FEE","-6")),items));
        assertThrows(IllegalArgumentException.class,()->
            ItemAllocationEngine.summarize("USD",source,List.of(l("a","AMAZON_FEE","5")),items));
        assertThrows(IllegalArgumentException.class,()->
            ItemAllocationEngine.summarize("USD",source,List.of(
                l("a","AMAZON_FEE","-2"),l("a","AMAZON_FEE","-3")),items));
        assertThrows(IllegalArgumentException.class,()->
            ItemAllocationEngine.summarize("USD",source,List.of(
                l("x","AMAZON_FEE","-2")),items));
        assertThrows(IllegalArgumentException.class,()->
            ItemAllocationEngine.summarize("USD",source,List.of(
                new ItemAllocationEngine.Line("a","AMAZON_FEE",n("-2"),"EUR","EVIDENCE-2026")),items));
        assertThrows(IllegalArgumentException.class,()->
            ItemAllocationEngine.summarize("USD",
                Map.of("PRODUCT_REFUND",n("-2")),List.of(),items));
    }
}
