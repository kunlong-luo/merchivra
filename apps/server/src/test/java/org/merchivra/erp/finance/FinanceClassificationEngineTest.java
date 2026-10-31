package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinanceClassificationEngineTest {
    private static FinanceClassificationEngine.Leaf leaf(String id,String type,
        String amount,String currency) {
        return new FinanceClassificationEngine.Leaf(id,type,
            amount==null?null:new BigDecimal(amount),currency);
    }
    @Test void reconciledLeavesProduceOnlyProvisionalSignedTotals() {
        var preview=FinanceClassificationEngine.evaluate(new BigDecimal("7.00"),"USD",
            List.of(leaf("0.0","ProductSale","10.00","USD"),
                leaf("0.1","Commission","-2.00","USD"),
                leaf("0.2","FulfillmentFee","-1.00","USD")),
            Map.of("ProductSale","PRODUCT_SALES","Commission","AMAZON_FEE",
                "FulfillmentFee","FBA_FEE"));
        assertEquals("RECONCILED_UNVERIFIED",preview.coverage());
        assertEquals(3,preview.leafCount());
        assertEquals(3,preview.mappedCount());
        assertEquals("7.00",preview.leafTotal());
        assertEquals(3,preview.provisionalTotals().size());
        assertEquals("-2.00",preview.provisionalTotals().stream()
            .filter(x->x.category().equals("AMAZON_FEE"))
            .findFirst().orElseThrow().signedAmount());
    }
    @Test void absentBreakdownsAreNotZeroFees() {
        var p=FinanceClassificationEngine.evaluate(new BigDecimal("7"),"USD",
            List.of(),Map.of());
        assertEquals("NO_BREAKDOWNS",p.coverage());
        assertTrue(p.provisionalTotals().isEmpty());
    }
    @Test void partiallyObservedMoneyIsNotReconciled() {
        var p=FinanceClassificationEngine.evaluate(new BigDecimal("7"),"USD",
            List.of(leaf("0","ProductSale","7","USD"),
                leaf("1","Commission",null,null)),
            Map.of("ProductSale","PRODUCT_SALES","Commission","AMAZON_FEE"));
        assertEquals("MISSING_AMOUNT",p.coverage());
        assertNull(p.leafTotal());
        assertTrue(p.provisionalTotals().isEmpty());
    }
    @Test void currencyMismatchDoesNotGetConvertedOrSummed() {
        var p=FinanceClassificationEngine.evaluate(new BigDecimal("7"),"USD",
            List.of(leaf("0","ProductSale","7","EUR")),Map.of("ProductSale","PRODUCT_SALES"));
        assertEquals("CURRENCY_MISMATCH",p.coverage());
        assertNull(p.leafTotal());
        assertTrue(p.provisionalTotals().isEmpty());
    }
    @Test void unknownCategoryBlocksProvisionalTotalsEvenIfSourceReconciles() {
        var p=FinanceClassificationEngine.evaluate(new BigDecimal("7"),"USD",
            List.of(leaf("0","ProductSale","10","USD"),
                leaf("1","UnknownFee","-3","USD")),Map.of("ProductSale","PRODUCT_SALES"));
        assertEquals("UNMAPPED_LEAF",p.coverage());
        assertEquals(1,p.mappedCount());
        assertTrue(p.provisionalTotals().isEmpty());
    }
    @Test void mismatchedSignedLeafSumDoesNotPretendToReconcile() {
        var p=FinanceClassificationEngine.evaluate(new BigDecimal("7"),"USD",
            List.of(leaf("0","ProductSale","8","USD")),Map.of("ProductSale","PRODUCT_SALES"));
        assertEquals("AMOUNT_MISMATCH",p.coverage());
        assertEquals("8",p.leafTotal());
        assertTrue(p.provisionalTotals().isEmpty());
    }
    @Test void unclassifiedRuleIsNotAReviewedClassification() {
        var p=FinanceClassificationEngine.evaluate(BigDecimal.ONE,"USD",
            List.of(leaf("0","Unknown", "1","USD")),Map.of("Unknown","UNCLASSIFIED"));
        assertEquals("UNMAPPED_LEAF",p.coverage());
    }
}
