package org.merchivra.erp.demo;

import java.util.List;
import org.merchivra.erp.demo.DemoModels.*;
import org.springframework.stereotype.Component;

/**
 * Documentation-shaped *simulator*, not an Amazon HTTP client.
 * Calls are named after the intended operations, but payloads come ONLY
 * from committed fictional fixtures and never request buyer/recipient PII.
 */
@Component
public class MockSpApiClient {
    private final MockMarketplaceOverviewGateway fixtures;
    public MockSpApiClient(MockMarketplaceOverviewGateway fixtures) { this.fixtures = fixtures; }

    public record Page(List<Order> records, String nextToken) {}
    public record Snapshot(List<Listing> listings, List<Page> orderPages,
                           List<Inventory> inventory, String financeStatus) {}

    public Snapshot simulate(String marketplaceId) {
        String account = switch (marketplaceId) {
            case "ATVPDKIKX0DER" -> MockMarketplaceOverviewGateway.US_ACCOUNT;
            case "A1PA6795UKMFR9" -> MockMarketplaceOverviewGateway.EU_ACCOUNT;
            default -> throw new IllegalArgumentException("No fictional SP-API scenario for marketplace");
        };
        Overview data = fixtures.getOverview();
        var listings = data.listings().stream().filter(x -> x.accountId().equals(account)).toList();
        var orders = data.orders().stream().filter(x -> x.accountId().equals(account)).toList();
        var stock = data.inventory().stream().filter(x -> x.accountId().equals(account)).toList();
        // SearchOrders simulation: page size 1, stable nextToken; every page
        // intentionally retains the exact same marketplace request scope.
        List<Page> pages = java.util.stream.IntStream.range(0, orders.size())
            .mapToObj(i -> new Page(List.of(orders.get(i)),
                i + 1 < orders.size() ? "MOCK-PAGE-" + (i + 1) : null)).toList();
        // No Finance and Accounting grant, so the simulator cannot fabricate settlements.
        return new Snapshot(listings, pages, stock, "NOT_AUTHORIZED");
    }
}
