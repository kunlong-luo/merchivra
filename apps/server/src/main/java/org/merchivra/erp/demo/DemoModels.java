package org.merchivra.erp.demo;

import java.util.List;

/**
 * Read-only demonstration contract. All identifiers and amounts are invented.
 * This is NOT a canonical Amazon adapter or a live business data model.
 */
public final class DemoModels {
    private DemoModels() {}

    public record Money(String amount, String currency) {}
    public record Account(String id, String name, String region,
                          String marketplaceId, String currency, String connectionStatus) {}
    public record Listing(String id, String accountId, String sellerSku,
                          String asin, String internalSku, String mappingStatus) {}
    public record Order(String id, String accountId, String externalOrderId,
                        String sellerSku, String fulfillmentStatus, String refundStatus,
                        String financialStatus, Money provisionalSales, Money refundAmount,
                        String sourceUpdatedAt) {}
    public record Inventory(String id, String accountId, String sellerSku,
                            Integer fulfillable, Integer reserved, String quality,
                            String observedAt) {}
    public record AccountSummary(String accountId, String currency, int orderCount,
                                 Money provisionalSales, Money observedRefunds,
                                 int listingCount, int unmappedListings,
                                 int unknownInventoryCount, String financialCompleteness) {}
    public record Overview(String mode, String notice, String fixtureAsOf,
                           List<Account> accounts, List<Listing> listings,
                           List<Order> orders, List<Inventory> inventory,
                           List<AccountSummary> summaries) {}
}
