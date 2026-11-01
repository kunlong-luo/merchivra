package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultiSkuContributionServiceTest {
    private static final UUID TENANT=UUID.randomUUID();
    private static final UUID MARKET=UUID.randomUUID();
    private static final UUID ORDER=UUID.randomUUID();
    private static OrderCostEngine.Preview cost() {
        return new OrderCostEngine.Preview("COGS_READY_PREVIEW","USD",
            "2026-10-30T00:00:00Z",2,2,"40",List.of(
                new OrderCostEngine.Line("item1","S1",1,"SKU-1","10","USD","10","READY"),
                new OrderCostEngine.Line("item2","S2",1,"SKU-2","30","USD","30","READY")));
    }
    private static OrderContributionEngine.Preview financial() {
        return new OrderContributionEngine.Preview(
            "REVIEWED_SOURCE_CONTRIBUTION_PREVIEW","USD",1,0,
            "100","-10","-5","40","45",false,false);
    }
    private static ItemAllocationService.View view(UUID event,boolean approved) {
        return new ItemAllocationService.View(event.toString(),ORDER.toString(),
            approved?"APPROVED":"PENDING",1,"USD",UUID.randomUUID().toString(),
            approved?UUID.randomUUID().toString():null,true,false,false,
            new ItemAllocationEngine.Summary(true,List.of(
                new ItemAllocationEngine.Residual("PRODUCT_SALES","USD","100","100","0",true),
                new ItemAllocationEngine.Residual("AMAZON_FEE","USD","-10","-10","0",true),
                new ItemAllocationEngine.Residual("FBA_FEE","USD","-5","-5","0",true))),
            List.of(new ItemAllocationService.Item("item1","SKU-1","10","USD",1),
                new ItemAllocationService.Item("item2","SKU-2","30","USD",1)),
            List.of(
                new ItemAllocationService.Allocation("item1","PRODUCT_SALES","40","USD","REF-10001"),
                new ItemAllocationService.Allocation("item2","PRODUCT_SALES","60","USD","REF-10002"),
                new ItemAllocationService.Allocation("item1","AMAZON_FEE","-3","USD","REF-10003"),
                new ItemAllocationService.Allocation("item2","AMAZON_FEE","-7","USD","REF-10004"),
                new ItemAllocationService.Allocation("item1","FBA_FEE","-2","USD","REF-10005"),
                new ItemAllocationService.Allocation("item2","FBA_FEE","-3","USD","REF-10006")));
    }
    @Test void convertsEveryExplicitlyReviewedItemIntoExactSkuContributions(){
        var gateway=mock(ItemAllocationService.class);
        UUID event=UUID.randomUUID();
        when(gateway.linkedEvents(TENANT,MARKET,ORDER)).thenReturn(List.of(event));
        when(gateway.preview(TENANT,MARKET,event)).thenReturn(view(event,true));
        var result=new MultiSkuContributionService(gateway).preview(TENANT,MARKET,
            ORDER,cost(),financial());
        assertEquals("REVIEWED_MULTI_SKU_CONTRIBUTION_PREVIEW",result.state());
        assertEquals(2,result.skus().size());
        assertEquals(0,new BigDecimal(result.skus().getFirst().contribution())
            .compareTo(new BigDecimal("25")));
        assertEquals(0,new BigDecimal(result.skus().get(1).contribution())
            .compareTo(new BigDecimal("20")));
        assertFalse(result.financialSourceComplete());
        assertFalse(result.bankVerified());
    }
    @Test void onePendingEventBlocksWholeMultiSkuOrder(){
        var gateway=mock(ItemAllocationService.class);
        UUID event=UUID.randomUUID();
        when(gateway.linkedEvents(TENANT,MARKET,ORDER)).thenReturn(List.of(event));
        when(gateway.preview(TENANT,MARKET,event)).thenReturn(view(event,false));
        var result=new MultiSkuContributionService(gateway).preview(
            TENANT,MARKET,ORDER,cost(),financial());
        assertEquals("ALLOCATIONS_AWAITING_INDEPENDENT_APPROVAL",result.state());
        assertTrue(result.skus().isEmpty());
    }
    @Test void incompleteOrderSourcesCannotProduceAnySkuAmounts(){
        var gateway=mock(ItemAllocationService.class);
        var unready=new OrderContributionEngine.Preview(
            "REFUND_COST_REVERSAL_UNVERIFIED","USD",1,0,
            null,null,null,null,null,false,false);
        var result=new MultiSkuContributionService(gateway).preview(
            TENANT,MARKET,ORDER,cost(),unready);
        assertEquals("ORDER_FINANCIAL_SOURCE_INCOMPLETE",result.state());
        verifyNoInteractions(gateway);
    }
}
