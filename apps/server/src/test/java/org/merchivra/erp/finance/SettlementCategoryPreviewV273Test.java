package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.merchivra.erp.finance.SettlementCategoryPreviewV273.*;

class SettlementCategoryPreviewV273Test {
    private static final ReportType SALE=new ReportType("Order","ItemPrice","Principal");
    private static final ReportType COMMISSION=new ReportType("Order","ItemFees","Commission");
    private static final ReportType FBA=new ReportType("Order","ItemFees","FBA");
    private static final List<Mapping> RULES=List.of(new Mapping(SALE,"PRODUCT_SALES"),
        new Mapping(COMMISSION,"AMAZON_FEE"),new Mapping(FBA,"FBA_FEE"));
    private static SourceRow report(String order,ReportType type,String amount) {
        return new SourceRow(true,order,type,null,new BigDecimal(amount));
    }
    private static SourceRow finance(String order,String category,String amount) {
        return new SourceRow(false,order,null,category,new BigDecimal(amount));
    }
    private static Preview check(List<SourceRow> rows) {
        return evaluate("SOURCE_AMOUNTS_AGREE_UNVERIFIED","USD",rows,validate(RULES));
    }
    @Test void detectsSameDirectionCategoryOffsetDespiteEqualGrossAndNet() {
        var result=check(List.of(report("A",SALE,"20"),report("A",COMMISSION,"-3"),
            report("A",FBA,"-2"),finance("A","PRODUCT_SALES","20"),
            finance("A","AMAZON_FEE","-2"),finance("A","FBA_FEE","-3")));
        assertEquals("ORDER_CATEGORY_AMOUNT_DIFFERENCE",result.state());
        assertEquals(2,result.mismatchedBuckets());
        assertFalse(result.mappingApproved());assertFalse(result.ledgerPosted());
        assertNull(result.realizedNetProfit());
    }
    @Test void explicitMatchingRulesRemainUnverifiedAndUnknownLeavesHideTotals() {
        var rows=List.of(report(null,SALE,"20"),report(null,COMMISSION,"-3"),
            report(null,FBA,"-2"),finance(null,"PRODUCT_SALES","20.00"),
            finance(null,"AMAZON_FEE","-3"),finance(null,"FBA_FEE","-2"));
        assertEquals("LOCAL_CATEGORY_AMOUNTS_AGREE_UNVERIFIED",check(rows).state());
        var missing=new java.util.ArrayList<>(rows);
        missing.set(5,finance(null,"UNCLASSIFIED","-2"));
        assertEquals("CATEGORY_MAPPING_INCOMPLETE",check(missing).state());
        assertEquals(1,check(missing).unmappedFinanceLeaves());
        assertTrue(check(missing).categoryTotals().isEmpty());
        assertEquals("SOURCE_COMPARISON_HOLD",evaluate("SOURCE_TOTAL_DIFFERENCE","USD",rows,validate(RULES)).state());
    }
    @Test void exactTupleAndZeroCategoryCoverageCannotBeGuessed() {
        assertThrows(IllegalArgumentException.class,()->validate(List.of(RULES.getFirst(),RULES.getFirst())));
        assertThrows(IllegalArgumentException.class,()->validate(List.of(new Mapping(SALE,"UNCLASSIFIED"))));
        var rows=List.of(report("A",SALE,"20"),report("A",COMMISSION,"-3"),
            report("A",FBA,"0"),finance("A","PRODUCT_SALES","20"),finance("A","AMAZON_FEE","-3"));
        assertEquals("ORDER_CATEGORY_AMOUNT_DIFFERENCE",check(rows).state());
        var wrong=new java.util.ArrayList<>(rows);
        wrong.set(1,report("A",new ReportType("Refund","ItemFees","Commission"),"-3"));
        assertEquals("CATEGORY_MAPPING_INCOMPLETE",check(wrong).state());
        assertTrue(check(wrong).categoryTotals().isEmpty());
    }
}
