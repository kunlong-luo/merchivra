package org.merchivra.erp.finance;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkuProfitMultiSkuTest {
    static OrderContributionEngine.Preview financial(){
        return new OrderContributionEngine.Preview(
            "REVIEWED_SOURCE_CONTRIBUTION_PREVIEW","USD",1,0,
            "100","-10","-5","40","45",false,false);
    }
    static SkuProfitPreviewEngine.Input order(){
        return new SkuProfitPreviewEngine.Input("order-1","AMZ-ORDER-1",
            "2026-10-29T20:00:00Z",null,2,"COGS_READY_PREVIEW",true,financial());
    }
    @Test void pendingMultiSkuDoesNotLeakPartialSkuAmounts(){
        var pending=new MultiSkuContributionService.Result(
            "ALLOCATIONS_AWAITING_INDEPENDENT_APPROVAL",List.of(),1,false,false);
        var result=SkuProfitPreviewEngine.calculate("USD",List.of(order()),
            false,false,Map.of("order-1",pending));
        assertEquals(0,result.totals().eligibleOrders());
        assertEquals(1,result.totals().blockedOrders());
        assertEquals("0",result.totals().contribution());
        assertEquals("ALLOCATIONS_AWAITING_INDEPENDENT_APPROVAL",
            result.orders().getFirst().readiness());
        assertNull(result.orders().getFirst().contribution());
        assertTrue(result.skus().isEmpty());
    }
    @Test void approvedMultiSkuIsCountedOnceAndBothSkuRowsConserveAmount(){
        var s1=new MultiSkuContributionService.Sku("SKU-1",1,1,"USD",
            "40","-3","-2","10","25");
        var s2=new MultiSkuContributionService.Sku("SKU-2",1,1,"USD",
            "60","-7","-3","30","20");
        var approved=new MultiSkuContributionService.Result(
            "REVIEWED_MULTI_SKU_CONTRIBUTION_PREVIEW",List.of(s1,s2),
            1,false,false);
        var result=SkuProfitPreviewEngine.calculate("USD",List.of(order()),
            false,false,Map.of("order-1",approved));
        assertEquals(1,result.totals().eligibleOrders());
        assertEquals(1,result.totals().multiSkuOrders());
        assertEquals(0,result.totals().blockedOrders());
        assertEquals("45",result.totals().contribution());
        assertEquals(2,result.skus().size());
        assertEquals("ELIGIBLE_MULTI_SKU_PREVIEW",result.orders().getFirst().readiness());
        assertFalse(result.totals().financialSourceComplete());
        assertFalse(result.totals().bankVerified());
        assertFalse(result.totals().ledgerPosted());
    }
    @Test void inconsistentPerSkuAllocationsCannotBeSilentlyIncluded(){
        var s1=new MultiSkuContributionService.Sku("SKU-1",1,1,"USD",
            "40","-3","-2","10","25");
        var wrong=new MultiSkuContributionService.Sku("SKU-2",1,1,"USD",
            "60","-7","-3","30","30");
        assertThrows(IllegalArgumentException.class,()->
            SkuProfitPreviewEngine.calculate("USD",List.of(order()),false,false,
                Map.of("order-1",new MultiSkuContributionService.Result(
                    "REVIEWED_MULTI_SKU_CONTRIBUTION_PREVIEW",
                    List.of(s1,wrong),1,false,false))));
    }
}
