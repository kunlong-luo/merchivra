package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinanceExceptionEngineTest {
    private static FinanceExceptionEngine.SourceSignal source(
        long leaves,long missing,long mismatch,long unmapped,BigDecimal total,
        boolean reviewed,boolean allocated) {
        return new FinanceExceptionEngine.SourceSignal("event-a","FIN-A","USD",
            new BigDecimal("12.00"),"ORDER-A",reviewed,allocated,
            leaves,missing,mismatch,unmapped,total);
    }
    @Test void gapsAreSignalsNeverInventedZeros() {
        var found=FinanceExceptionEngine.source(source(0,0,0,0,null,false,false));
        assertEquals(List.of("SOURCE_BREAKDOWN_ABSENT","ORDER_LINK_UNREVIEWED",
            "SETTLEMENT_UNASSIGNED"),found.stream().map(x->x.code()).toList());
        assertEquals("INFO",found.get(2).severity());
    }
    @Test void absenceOfApprovedFeeMappingsIsNotProfit() {
        var found=FinanceExceptionEngine.source(source(2,0,0,1,
            new BigDecimal("12"),true,true));
        assertEquals(1,found.size());
        assertEquals("SOURCE_TYPE_UNCLASSIFIED",found.getFirst().code());
    }
    @Test void mismatchedSourceTotalBlocksClassification() {
        var found=FinanceExceptionEngine.source(source(2,0,0,0,
            new BigDecimal("11.50"),true,true));
        assertEquals("SOURCE_BREAKDOWN_DIFFERS",found.getFirst().code());
        assertEquals("BLOCKING",found.getFirst().severity());
    }
    @Test void missingMoneyIsDifferentFromZero() {
        var found=FinanceExceptionEngine.source(source(2,1,0,0,null,true,true));
        assertEquals("SOURCE_AMOUNT_INCOMPLETE",found.getFirst().code());
    }
    @Test void reconciledSourceWithReviewedLinksHasNoException() {
        assertTrue(FinanceExceptionEngine.source(source(2,0,0,0,
            new BigDecimal("12.000"),true,true)).isEmpty());
    }
    @Test void manualStatementMatchingDoesNotProveBankPayment() {
        assertTrue(FinanceExceptionEngine.settlement("s","STATEMENT-A","USD",
            "SIGNED_TOTAL_MATCHES_UNVERIFIED","0.00",0).isEmpty());
        var found=FinanceExceptionEngine.settlement("s","STATEMENT-A","USD",
            "SIGNED_TOTAL_DIFFERS","-1.00",2);
        assertEquals("STATEMENT_SIGNED_TOTAL_DIFFERS",found.getFirst().code());
        assertEquals("STATEMENT_UNASSIGNED_CANDIDATES",found.get(1).code());
    }
    @Test void incompleteOrderUnitCostsAppearAsAdvisory() {
        var cost=OrderCostEngine.evaluate(true,true,
            Instant.parse("2026-10-30T00:00:00Z"),"USD",
            List.of(new OrderCostEngine.Item("i","SKU",1,"LIVE",null,null,null)));
        var found=FinanceExceptionEngine.order("o","ORDER-A","USD",cost);
        assertEquals("ORDER_COST_INCOMPLETE",found.getFirst().code());
        assertEquals("REVIEW",found.getFirst().severity());
    }
}
