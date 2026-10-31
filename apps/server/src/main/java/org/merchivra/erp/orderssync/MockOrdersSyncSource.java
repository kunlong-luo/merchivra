package org.merchivra.erp.orderssync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.demo.MockSpApiClient;

/** Local-only fixture source, not the Amazon API sandbox and never a live Seller. */
public final class MockOrdersSyncSource implements OrdersSyncSource {
    private final MockSpApiClient mock;
    private final boolean demo;
    public MockOrdersSyncSource(MockSpApiClient mock, boolean demo) {
        this.mock = mock; this.demo = demo;
    }
    @Override public boolean allowed() { return demo; }
    @Override public String dataOrigin() { return "MOCK"; }

    @Override public void requireAuthorized(Scope scope) {
        if (!demo || scope == null || scope.tenant() == null || scope.marketplace() == null
            || !List.of("ATVPDKIKX0DER", "A1PA6795UKMFR9").contains(scope.marketplaceCode()))
            throw new SecurityException("Local synthetic source not permitted");
        // The service verifies tenant + LOCAL seller/account status in SQL
        // on every claim and commit. This gateway never calls Amazon.
    }

    @Override public Page fetch(Scope scope, Instant after, Instant before, String cursor) {
        requireAuthorized(scope);
        var pages = mock.simulate(scope.marketplaceCode()).orderPages();
        int index;
        if (cursor == null) index = 0;
        else if (cursor.matches("MOCK-ORDERS-PAGE-[1-9][0-9]*")) {
            index = Integer.parseInt(cursor.substring("MOCK-ORDERS-PAGE-".length()));
        } else throw new IllegalArgumentException("Invalid synthetic pagination token");
        if (index >= pages.size()) throw new IllegalArgumentException("Synthetic token expired");
        var part = pages.get(index);
        List<OrderFact> facts = part.records().stream()
            .map(o -> new OrderFact(o.externalOrderId(), scope.marketplaceCode(),
                Instant.parse(o.sourceUpdatedAt()), o.fulfillmentStatus())).toList();
        return new Page(facts,
            index + 1 < pages.size() ? "MOCK-ORDERS-PAGE-" + (index + 1) : null);
    }
}
