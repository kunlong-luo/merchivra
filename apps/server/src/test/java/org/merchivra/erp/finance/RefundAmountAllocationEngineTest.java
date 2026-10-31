package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RefundAmountAllocationEngineTest {
    private RefundAmountAllocationEngine.Entry item(String id,String amount,String c) {
        return new RefundAmountAllocationEngine.Entry(id,new BigDecimal(amount),c);
    }
    @Test void negativeRefundAllocatesWithoutSignInversion() {
        var v=RefundAmountAllocationEngine.evaluate(new BigDecimal("-12.50"),"USD",true,
            List.of(item("a","-4.50","USD"),item("b","-8.00","USD")));
        assertEquals("SIGNED_TOTAL_MATCHES_UNVERIFIED",v.status());
        assertEquals("0.00",v.remainingAmount());
        assertFalse(v.accountingPosted());
        assertFalse(v.inventoryReceiptVerified());
    }
    @Test void partialAllocationIsExplicitNotAZeroRefund() {
        var v=RefundAmountAllocationEngine.evaluate(new BigDecimal("-12"),"USD",true,
            List.of(item("a","-7","USD")));
        assertEquals("PARTIALLY_ALLOCATED",v.status());
        assertEquals("-5",v.remainingAmount());
    }
    @Test void overAllocationAndSignReversalAreRejected() {
        assertThrows(IllegalArgumentException.class,()->
            RefundAmountAllocationEngine.requireCandidate(new BigDecimal("-4"),"USD",
                List.of(item("a","-5","USD"))));
        assertThrows(IllegalArgumentException.class,()->
            RefundAmountAllocationEngine.requireCandidate(new BigDecimal("-4"),"USD",
                List.of(item("a","4","USD"))));
    }
    @Test void mixedCurrencyAndDuplicateItemCannotPass() {
        assertThrows(IllegalArgumentException.class,()->
            RefundAmountAllocationEngine.requireCandidate(new BigDecimal("-4"),"USD",
                List.of(item("a","-4","EUR"))));
        assertThrows(IllegalArgumentException.class,()->
            RefundAmountAllocationEngine.evaluate(new BigDecimal("-4"),"USD",true,
                List.of(item("a","-2","USD"),item("a","-2","USD"))));
    }
    @Test void missingReviewedSourceCannotShowFakeFinancialTotal() {
        var v=RefundAmountAllocationEngine.evaluate(null,"USD",false,List.of());
        assertEquals("SOURCE_REFUND_NOT_READY",v.status());
        assertNull(v.allocatedAmount());
    }
    @Test void positiveRefundReversalRequiresMatchingDirection() {
        var v=RefundAmountAllocationEngine.evaluate(new BigDecimal("10"),"USD",true,
            List.of(item("a","10","USD")));
        assertEquals("SIGNED_TOTAL_MATCHES_UNVERIFIED",v.status());
    }
}
