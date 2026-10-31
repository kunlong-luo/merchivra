package org.merchivra.erp.inventory;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplenishmentDecisionTest {
    private static final Instant NOW=Instant.parse("2026-10-31T08:00:00Z");

    @Test void freshObservedInventoryBelowPointGivesQuantity() {
        var d=ReplenishmentService.evaluate("OBSERVED",3,2,
            NOW.minusSeconds(60),4,20,NOW);
        assertEquals("REORDER",d.state());
        assertEquals(17,d.suggestedQuantity());
    }

    @Test void unknownIsNotZeroStock() {
        var d=ReplenishmentService.evaluate("UNKNOWN",null,null,
            NOW,4,20,NOW);
        assertEquals("INCOMPLETE_OBSERVATION",d.state());
        assertNull(d.suggestedQuantity());
    }

    @Test void staleOrFutureOrNullTimeNeverGeneratesPurchaseQuantity() {
        for(Instant when:new Instant[]{null,NOW.minusSeconds(48*3600+1),
                                      NOW.plusSeconds(301)}) {
            var d=ReplenishmentService.evaluate("OBSERVED",0,0,when,4,20,NOW);
            assertEquals("STALE_OBSERVATION",d.state());
            assertNull(d.suggestedQuantity());
        }
    }

    @Test void partialAndNegativeInventoryCannotTriggerPurchase() {
        assertEquals("INCOMPLETE_OBSERVATION",
            ReplenishmentService.evaluate("PARTIAL",0,0,NOW,4,20,NOW).state());
        assertEquals("INCOMPLETE_OBSERVATION",
            ReplenishmentService.evaluate("OBSERVED",-1,0,NOW,4,20,NOW).state());
        assertEquals("INCOMPLETE_OBSERVATION",
            ReplenishmentService.evaluate("OBSERVED",0,-1,NOW,4,20,NOW).state());
    }

    @Test void healthyAndUnobservedNeverGiveQuantity() {
        assertNull(ReplenishmentService.evaluate("OBSERVED",5,1,
            NOW,4,20,NOW).suggestedQuantity());
        var d=ReplenishmentService.evaluate(null,null,null,null,4,20,NOW);
        assertEquals("NO_OBSERVATION",d.state());
        assertNull(d.suggestedQuantity());
    }
}
