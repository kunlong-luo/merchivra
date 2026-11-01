package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RefundRecoveryEngineTest {
    private static RefundRecoveryEngine.SourceLine item(String id,int quantity,
        String state) {
        return new RefundRecoveryEngine.SourceLine(id,"SKU-"+id,
            5,quantity,state,"USD",new BigDecimal("8.25"),1,"RETURN-REVIEW-001");
    }
    private static RefundRecoveryEngine.RefundLine refund(String id,String signed) {
        return new RefundRecoveryEngine.RefundLine(id,new BigDecimal(signed),
            "USD",1,"REFUND-ALLOCATION-001");
    }
    @Test void balancedRefundExposesHistoryAndClaimedRestockButNeverRecognizesIt() {
        var p=RefundRecoveryEngine.calculate("USD",new BigDecimal("-45.00"),
            List.of(item("A",2,"RESTOCKED_CLAIMED"),
                item("B",1,"NOT_RESTOCKED")),
            List.of(refund("A","-30"),refund("B","-15")));
        assertEquals("MANUAL_EVIDENCE_BALANCED_UNVERIFIED",p.status());
        assertEquals("0.00",p.remainingRefund());
        assertEquals("24.75",p.historicalCostExposure());
        assertEquals("16.50",p.claimedRestockCostSensitivity());
        assertNull(p.actualCogsReversal());
        assertFalse(p.inventoryReceiptVerified());
        assertFalse(p.ledgerPosted());
        assertEquals(2,p.items().size());
    }
    @Test void incompleteAllocationNeverInventsAZeroUnallocatedRefund() {
        var p=RefundRecoveryEngine.calculate("USD",new BigDecimal("-45.00"),
            List.of(item("A",2,"RESTOCKED_CLAIMED"),item("B",1,"UNKNOWN")),
            List.of(refund("A","-30")));
        assertEquals("REFUND_ITEMS_OR_AMOUNT_INCOMPLETE",p.status());
        assertEquals("-15.00",p.remainingRefund());
        assertNull(p.actualCogsReversal());
    }
    @Test void rejectsExcessOrWrongSignAndDuplicateAndUnknownItem() {
        var review=List.of(item("A",2,"RESTOCKED_CLAIMED"));
        assertThrows(IllegalArgumentException.class,()->
            RefundRecoveryEngine.calculate("USD",new BigDecimal("-30"),
                review,List.of(refund("A","-35"))));
        assertThrows(IllegalArgumentException.class,()->
            RefundRecoveryEngine.calculate("USD",new BigDecimal("-30"),
                review,List.of(refund("A","30"))));
        assertThrows(IllegalArgumentException.class,()->
            RefundRecoveryEngine.calculate("USD",new BigDecimal("-30"),
                review,List.of(refund("A","-15"),refund("A","-15"))));
        assertThrows(IllegalArgumentException.class,()->
            RefundRecoveryEngine.calculate("USD",new BigDecimal("-30"),
                review,List.of(refund("MISSING","-30"))));
    }
    @Test void rejectsInvalidQuantityOrCostAndCurrency() {
        assertThrows(IllegalArgumentException.class,()->
            RefundRecoveryEngine.calculate("USD",new BigDecimal("-10"),
                List.of(item("A",6,"RESTOCKED_CLAIMED")),
                List.of(refund("A","-10"))));
        assertThrows(IllegalArgumentException.class,()->
            RefundRecoveryEngine.calculate("EUR",new BigDecimal("-10"),
                List.of(item("A",1,"UNKNOWN")),
                List.of(refund("A","-10"))));
        assertThrows(IllegalArgumentException.class,()->
            RefundRecoveryEngine.calculate("USD",new BigDecimal("-10"),
                List.of(new RefundRecoveryEngine.SourceLine("A","SKU-A",5,1,
                    "UNKNOWN","USD",new BigDecimal("-1"),1,"RETURN-001")),
                List.of(refund("A","-10"))));
    }
}
