package org.merchivra.erp.demo;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.demo.DemoModels.*;
import static org.junit.jupiter.api.Assertions.*;

class DemoCoreImporterTest {
    private final MockMarketplaceOverviewGateway fixtureGateway = new MockMarketplaceOverviewGateway();

    @Test
    void syntheticFixturePassesScopeValidation() {
        assertDoesNotThrow(() -> DemoCoreImporter.validateFixture(fixtureGateway.getOverview()));
    }

    @Test
    void identifiersAreDeterministicAndScoped() {
        var one = DemoCoreImporter.stableId("order", "one/MOCK-001");
        assertEquals(one, DemoCoreImporter.stableId("order", "one/MOCK-001"));
        assertNotEquals(one, DemoCoreImporter.stableId("order", "two/MOCK-001"));
        assertNotEquals(one, DemoCoreImporter.stableId("item", "one/MOCK-001"));
    }

    @Test
    void rejectsCrossTenantOrUnknownSellerSource() {
        var original = fixtureGateway.getOverview();
        var tampered = new Overview(original.mode(), original.notice(), original.fixtureAsOf(),
            original.accounts(), original.listings(),
            List.of(new Order("ord-evil", "33333333-3333-4333-8333-333333333333",
                "MOCK-EVIL", "SKU", "SHIPPED", "NONE", "NOT_OBSERVED",
                new Money("1.00","USD"), new Money("0.00","USD"), "2026-10-28T00:00:00Z")),
            original.inventory(), original.summaries());
        assertThrows(IllegalArgumentException.class,
            () -> DemoCoreImporter.validateFixture(tampered));
    }

    @Test
    void rejectsCurrencyMixing() {
        var original = fixtureGateway.getOverview();
        var badOrder = new Order("ord-evil", MockMarketplaceOverviewGateway.US_ACCOUNT,
            "MOCK-EVIL", "SKU", "SHIPPED", "NONE", "NOT_OBSERVED",
            new Money("1.00","EUR"), new Money("0.00","USD"), "2026-10-28T00:00:00Z");
        var tampered = new Overview(original.mode(), original.notice(), original.fixtureAsOf(),
            original.accounts(), original.listings(), List.of(badOrder),
            original.inventory(), original.summaries());
        assertThrows(IllegalArgumentException.class,
            () -> DemoCoreImporter.validateFixture(tampered));
    }

    @Test
    void rejectsNegativeInventoryAndNonMockModes() {
        var original = fixtureGateway.getOverview();
        var inv = new Inventory("bad", MockMarketplaceOverviewGateway.US_ACCOUNT,
            "SKU", -1, null, "OBSERVED", null);
        var badStock = new Overview(original.mode(), original.notice(), original.fixtureAsOf(),
            original.accounts(), original.listings(), original.orders(),
            List.of(inv), original.summaries());
        assertThrows(IllegalArgumentException.class,
            () -> DemoCoreImporter.validateFixture(badStock));

        var live = new Overview("LIVE", original.notice(), original.fixtureAsOf(),
            original.accounts(), original.listings(), original.orders(),
            original.inventory(), original.summaries());
        assertThrows(IllegalArgumentException.class,
            () -> DemoCoreImporter.validateFixture(live));
    }
}
