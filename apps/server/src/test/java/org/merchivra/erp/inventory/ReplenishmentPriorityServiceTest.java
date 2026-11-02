package org.merchivra.erp.inventory;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplenishmentPriorityServiceTest {
    private static final UUID MARKET=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String OBS="22222222-2222-4222-8222-222222222222";
    private static ReplenishmentService.Advice stock(String state,Integer fulfillable) {
        return new ReplenishmentService.Advice(
          new ReplenishmentService.Policy(MARKET.toString(),"SKU-1",3,20,14,
            "2026-11-01T00:00:00Z"),OBS,"OBSERVED",fulfillable,0,
          "2026-11-02T00:00:00Z",state,
          "REORDER".equals(state)?20-fulfillable:null);
    }
    private static ReplenishmentDemandService.Signal history(String status,String mode,
                                                              Long quantity) {
        return new ReplenishmentDemandService.Signal("SKU-1",status,mode,
            "2026-10-01T00:00:00Z","2026-11-01T23:30:00Z",14,
            1L,0L,quantity,quantity,quantity==null?null:"1.000",
            quantity==null?null:14L);
    }
    @Test void inventoryUnknownStaleOrIncompleteNeverCreatesCoverage() {
        for(String state:new String[]{"NO_OBSERVATION","STALE_OBSERVATION",
                                      "INCOMPLETE_OBSERVATION"}) {
            var v=ReplenishmentPriorityService.classify(stock(state,null),
                history("OBSERVED_ONLY","LIVE",28L),"LIVE");
            assertEquals("STOCK_UNTRUSTED",v.evidenceLevel());
            assertNull(v.coverageDays());
            assertNull(v.policyLeadUnits());
            assertEquals("BLOCKED",v.priorityCode());
        }
    }
    @Test void demandNotCompleteOrNoSkuOrdersIsUnknownNotZero() {
        var a=ReplenishmentPriorityService.classify(stock("REORDER",2),
          history("INCOMPLETE_ITEMS","MOCK",null),"MOCK");
        assertEquals("DEMAND_INCOMPLETE",a.evidenceLevel());
        assertNull(a.coverageDays());
        var b=ReplenishmentPriorityService.classify(stock("REORDER",2),
          history("NO_OBSERVED_ORDERS","MOCK",0L),"MOCK");
        assertEquals("DEMAND_INCOMPLETE",b.evidenceLevel());
        assertNull(b.coverageDays());
        var c=ReplenishmentPriorityService.classify(stock("REORDER",2),
          history("MOCK_SAMPLE","MOCK",0L),"MOCK");
        assertEquals("NO_SKU_RATE",c.evidenceLevel());
        assertNull(c.coverageDays());
    }
    @Test void sourceMismatchCannotMixHistoricalLiveWithMockStock() {
        var s=ReplenishmentPriorityService.classify(stock("REORDER",2),
          history("OBSERVED_ONLY","LIVE",28L),"MOCK");
        assertEquals("SOURCE_MISMATCH",s.evidenceLevel());
        assertNull(s.coverageDays());
        assertEquals("BLOCKED",s.priorityCode());
    }
    @Test void mockAndLiveRateStaySeparatedAndOneDecimalCoverageIsIllustrative() {
        var mock=ReplenishmentPriorityService.classify(stock("REORDER",2),
          history("MOCK_SAMPLE","MOCK",28L),"MOCK");
        assertEquals("MOCK_SAMPLE",mock.evidenceLevel());
        assertEquals("2.0",mock.coverageDays());
        assertEquals(14L,mock.policyLeadUnits());
        assertEquals("LEAD_WINDOW_EXPOSURE",mock.riskCode());
        assertEquals("HIGH",mock.priorityCode());
        var live=ReplenishmentPriorityService.classify(stock("HEALTHY",18),
          history("OBSERVED_ONLY","LIVE",28L),"LIVE");
        assertEquals("OBSERVED_HISTORY",live.evidenceLevel());
        assertEquals("18.0",live.coverageDays());
        assertEquals("HISTORICAL_COVERAGE_OK",live.riskCode());
        assertEquals("LOW",live.priorityCode());
    }
    @Test void boundaryUsesExactUnitsNotRoundedOneDecimalDisplay() {
        // 13 units with 27 observed units in 28 days:
        // exact 13.481 days, displayed 13.5; below policy lead 14.
        var test=ReplenishmentPriorityService.classify(stock("HEALTHY",13),
          history("OBSERVED_ONLY","LIVE",27L),"LIVE");
        assertEquals("13.5",test.coverageDays());
        assertEquals("HIGH",test.priorityCode());
        assertEquals("LEAD_WINDOW_EXPOSURE",test.riskCode());
        assertEquals(14L,test.policyLeadUnits());
    }
}
