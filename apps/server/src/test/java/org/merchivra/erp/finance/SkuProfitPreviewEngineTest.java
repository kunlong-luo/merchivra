package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkuProfitPreviewEngineTest {
    private static OrderContributionEngine.Preview ready(String sale,String fee,
        String fba,String cost,String contribution) {
        return new OrderContributionEngine.Preview(
            "REVIEWED_SOURCE_CONTRIBUTION_PREVIEW","USD",2,0,
            sale,fee,fba,cost,contribution,false,false);
    }
    private static SkuProfitPreviewEngine.Input order(String id,String sku,
        boolean mixed,String state,OrderContributionEngine.Preview preview) {
        return new SkuProfitPreviewEngine.Input(id,"ORDER-"+id,
            "2026-10-31T20:00:00Z",sku,2,state,mixed,preview);
    }
    @Test
    void aggregatesSignedFeesOnlyForSingleSkuFullyReviewedOrders() {
        var preview=SkuProfitPreviewEngine.calculate("USD",List.of(
            order("a","ABC",false,"COGS_READY_PREVIEW",
                ready("110.00","-7.25","-2.75","40.00","60.00")),
            order("b","ABC",false,"COGS_READY_PREVIEW",
                ready("60","-5","-1","20","34")),
            order("c",null,true,"COGS_READY_PREVIEW",
                ready("100","-10","-5","40","45"))
        ),true,false);
        assertEquals(3,preview.totals().scannedOrders());
        assertEquals(2,preview.totals().eligibleOrders());
        assertEquals(1,preview.totals().multiSkuOrders());
        assertEquals("170.00",new BigDecimal(preview.totals().sales()).toPlainString());
        assertEquals(0,new BigDecimal(preview.totals().contribution())
            .compareTo(new BigDecimal("94.00")));
        assertEquals(1,preview.skus().size());
        assertEquals("ABC",preview.skus().getFirst().sku());
        assertEquals("MULTI_SKU_UNALLOCATED",preview.orders().get(2).readiness());
        assertNull(preview.orders().get(2).contribution());
        assertFalse(preview.totals().completePeriod());
        assertFalse(preview.totals().financialSourceComplete());
        assertFalse(preview.totals().bankVerified());
        assertFalse(preview.totals().ledgerPosted());
    }

    @Test
    void neverTurnsRefundReviewOrMissingCogsIntoZeroProfit() {
        var blocked=new OrderContributionEngine.Preview(
            "REFUND_COST_REVERSAL_UNVERIFIED","USD",1,0,
            null,null,null,null,null,false,false);
        var p=SkuProfitPreviewEngine.calculate("USD",List.of(
            order("refund","SKU-1",false,"COGS_READY_PREVIEW",blocked),
            order("missing","SKU-2",false,"INCOMPLETE",
                ready("30","-2","-1","10","17"))
        ),false,false);
        assertEquals(0,p.totals().eligibleOrders());
        assertEquals(2,p.totals().blockedOrders());
        assertTrue(p.skus().isEmpty());
        assertEquals("REFUND_COST_REVERSAL_UNVERIFIED",
            p.orders().getFirst().readiness());
        assertEquals("INCOMPLETE",p.orders().get(1).readiness());
        assertNull(p.orders().getFirst().sales());
        assertNull(p.orders().get(1).contribution());
        assertFalse(p.totals().financialSourceComplete());
    }

    @Test
    void preventsMixedCurrenciesAndIncorrectSourceArithmetic() {
        var invalidCurrency=new OrderContributionEngine.Preview(
            "REVIEWED_SOURCE_CONTRIBUTION_PREVIEW","EUR",1,0,
            "10","-2","-1","3","4",false,false);
        assertThrows(IllegalArgumentException.class,()->
            SkuProfitPreviewEngine.calculate("USD",List.of(
                order("a","ABC",false,"COGS_READY_PREVIEW",invalidCurrency)),
                false,false));
        assertThrows(IllegalArgumentException.class,()->
            SkuProfitPreviewEngine.calculate("USD",List.of(
                order("a","ABC",false,"COGS_READY_PREVIEW",
                    ready("10","-2","-1","3","999"))),false,false));
    }

    @Test
    void pageCompletenessIsSeparateFromFinancialSourceCompleteness() {
        var full=SkuProfitPreviewEngine.calculate("USD",List.of(),false,false);
        assertTrue(full.totals().completePeriod());
        assertFalse(full.totals().financialSourceComplete());
        assertFalse(SkuProfitPreviewEngine.calculate("USD",List.of(),false,true)
            .totals().completePeriod());
    }
}
