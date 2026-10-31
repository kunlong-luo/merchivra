package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdSpendAttributionEngineTest {
    private final UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID b=UUID.fromString("00000000-0000-0000-0000-000000000002");
    @Test void manualSpendCanBeAttributedWithoutFakingAdsApiEvidence() {
        var p=AdSpendAttributionEngine.evaluate(new BigDecimal("25.000000"),
            "USD",List.of(new AdSpendAttributionEngine.Share(a,6000),
                          new AdSpendAttributionEngine.Share(b,4000)));
        assertEquals("FULLY_ALLOCATED_UNVERIFIED",p.status());
        assertEquals("25.000000",p.attributedSpend());
        assertEquals("0.000000",p.unallocatedSpend());
        assertFalse(p.adsApiVerified());
        assertFalse(p.includedInRecognizedProfit());
    }
    @Test void partialAndUnknownAreNotMistakenForZeroSpend() {
        var p=AdSpendAttributionEngine.evaluate(new BigDecimal("20"),"USD",
            List.of(new AdSpendAttributionEngine.Share(a,2500)));
        assertEquals("PARTIALLY_ALLOCATED",p.status());
        assertEquals("5.000000",p.attributedSpend());
        assertEquals("15.000000",p.unallocatedSpend());
        assertEquals("NO_SKU_ALLOCATION",AdSpendAttributionEngine.evaluate(
            new BigDecimal("20"),"USD",List.of()).status());
    }
    @Test void cannotDoubleAssignOrExceed100Percent() {
        assertThrows(IllegalArgumentException.class,()->
            AdSpendAttributionEngine.evaluate(BigDecimal.ONE,"USD",List.of(
                new AdSpendAttributionEngine.Share(a,6000),
                new AdSpendAttributionEngine.Share(b,5000))));
        assertThrows(IllegalArgumentException.class,()->
            AdSpendAttributionEngine.evaluate(BigDecimal.ONE,"USD",List.of(
                new AdSpendAttributionEngine.Share(a,1000),
                new AdSpendAttributionEngine.Share(a,2000))));
    }
    @Test void negativeSourceSpendIsNeverInventedAsPaidMediaExpense() {
        assertThrows(IllegalArgumentException.class,()->
            AdSpendAttributionEngine.evaluate(new BigDecimal("-1"),"USD",List.of()));
    }
}
