package org.merchivra.erp.spapi;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Ready-to-wire read APIs for actual seller-authorized requests. Caller must
 * resolve Seller scope from server tenancy and secret vault (NOT HTTP input).
 * All results are raw upstream JSON; no unreviewed canonical financial booking.
 */
public final class SpApiReadOperations {
    private final SpApiClient client;
    public SpApiReadOperations(SpApiClient client) { this.client=client; }

    private SpApiResponse call(SpApiSellerScope seller, SpApiOperation op,
                               Map<String,String> path, Map<String,String> query) throws Exception {
        return client.execute(SpApiRequest.read(seller.region(), op, path, query,
            seller.approvedRoles()), seller.refreshToken());
    }
    private Map<String,String> market(SpApiSellerScope seller) {
        return Map.of("marketplaceIds", seller.marketplaceId());
    }

    /** Orders v2026: no BUYER/RECIPIENT PII requested. Pagination preserves query window. */
    public SpApiResponse searchOrders(SpApiSellerScope seller, Instant updatedAfter,
                                      Instant updatedBefore, String paginationToken) throws Exception {
        if (updatedAfter == null || updatedBefore == null ||
            !updatedAfter.isBefore(updatedBefore))
            throw new IllegalArgumentException("Valid updated time window required");
        var q = new HashMap<String,String>(market(seller));
        q.put("lastUpdatedAfter", updatedAfter.toString());
        q.put("lastUpdatedBefore", updatedBefore.toString());
        q.put("maxResultsPerPage", "100");
        if (paginationToken != null && !paginationToken.isBlank()) q.put("paginationToken", paginationToken);
        return call(seller, SpApiOperation.SEARCH_ORDERS, Map.of(), q);
    }
    public SpApiResponse getOrder(SpApiSellerScope seller, String orderId) throws Exception {
        return call(seller, SpApiOperation.GET_ORDER, Map.of("orderId", orderId), Map.of());
    }
    public SpApiResponse searchListings(SpApiSellerScope seller, String pageToken) throws Exception {
        var q = new HashMap<String,String>(market(seller));
        q.put("includedData", "summaries,issues,offers,fulfillmentAvailability");
        q.put("pageSize", "20");
        if (pageToken != null && !pageToken.isBlank()) q.put("pageToken", pageToken);
        return call(seller, SpApiOperation.SEARCH_LISTINGS,
            Map.of("sellerId", seller.sellerId()), q);
    }
    public SpApiResponse getListing(SpApiSellerScope seller, String sku) throws Exception {
        return call(seller, SpApiOperation.GET_LISTING,
            Map.of("sellerId", seller.sellerId(), "sku", sku),
            Map.of("marketplaceIds", seller.marketplaceId(), "includedData", "summaries,issues"));
    }
    public SpApiResponse getInventorySummaries(SpApiSellerScope seller, String nextToken) throws Exception {
        var q = new HashMap<String,String>(market(seller));
        q.put("granularityType", "Marketplace");
        q.put("granularityId", seller.marketplaceId());
        q.put("details", "true");
        if (nextToken != null && !nextToken.isBlank()) q.put("nextToken", nextToken);
        return call(seller, SpApiOperation.GET_FBA_INVENTORY, Map.of(), q);
    }
    public SpApiResponse getCatalogItem(SpApiSellerScope seller, String asin) throws Exception {
        return call(seller, SpApiOperation.GET_CATALOG_ITEM, Map.of("asin", asin),
            Map.of("marketplaceIds", seller.marketplaceId()));
    }
    public SpApiResponse getProductTypeDefinition(SpApiSellerScope seller, String productType) throws Exception {
        return call(seller, SpApiOperation.GET_PRODUCT_TYPE_DEFINITION,
            Map.of("productType", productType), market(seller));
    }
    public SpApiResponse getListingOffers(SpApiSellerScope seller, String sku, String itemCondition) throws Exception {
        return call(seller, SpApiOperation.GET_LISTING_OFFERS,
            Map.of("SellerSKU", sku),
            Map.of("MarketplaceId", seller.marketplaceId(), "ItemCondition", itemCondition));
    }
    /** Finance and Accounting role required; never interpreted as settlement without reconciliation. */
    public SpApiResponse listTransactions(SpApiSellerScope seller, Instant postedAfter,
                                          Instant postedBefore, String nextToken) throws Exception {
        if (postedAfter == null || postedBefore == null || !postedAfter.isBefore(postedBefore))
            throw new IllegalArgumentException("Posted window required");
        var q = new HashMap<String,String>();
        q.put("postedAfter", postedAfter.toString());
        q.put("postedBefore", postedBefore.toString());
        // Intentionally omit marketplaceId: Amazon's US MFN transactions can
        // be omitted when this parameter is supplied.
        if (nextToken != null && !nextToken.isBlank()) q.put("nextToken", nextToken);
        return call(seller, SpApiOperation.LIST_TRANSACTIONS, Map.of(), q);
    }
    public SpApiResponse getReports(SpApiSellerScope seller, String reportTypes, String nextToken)
        throws Exception {
        var q = new HashMap<String,String>(market(seller));
        q.put("reportTypes", reportTypes);
        if (nextToken != null && !nextToken.isBlank()) q.put("nextToken", nextToken);
        return call(seller, SpApiOperation.GET_REPORTS, Map.of(), q);
    }
    public SpApiResponse getReport(SpApiSellerScope seller, String reportId) throws Exception {
        return call(seller, SpApiOperation.GET_REPORT, Map.of("reportId", reportId), Map.of());
    }
    /** Returns a pre-signed download URL; deliberately does not follow arbitrary URLs. */
    public SpApiResponse getReportDocumentMetadata(SpApiSellerScope seller, String documentId)
        throws Exception {
        return call(seller, SpApiOperation.GET_REPORT_DOCUMENT,
            Map.of("reportDocumentId", documentId), Map.of());
    }
    public SpApiResponse getFeeds(SpApiSellerScope seller, String feedTypes, String nextToken)
        throws Exception {
        var q = new HashMap<String,String>(market(seller));
        q.put("feedTypes", feedTypes);
        if (nextToken != null && !nextToken.isBlank()) q.put("nextToken", nextToken);
        return call(seller, SpApiOperation.GET_FEEDS, Map.of(), q);
    }
    public SpApiResponse getFeed(SpApiSellerScope seller, String feedId) throws Exception {
        return call(seller, SpApiOperation.GET_FEED, Map.of("feedId", feedId), Map.of());
    }
    public SpApiResponse getSalesMetrics(SpApiSellerScope seller, String interval, String granularity)
        throws Exception {
        return call(seller, SpApiOperation.GET_SALES_METRICS, Map.of(),
            Map.of("marketplaceIds", seller.marketplaceId(), "interval", interval,
                "granularity", granularity, "buyerType", "All"));
    }
    public SpApiResponse getSellerMarketplaceParticipations(SpApiSellerScope seller) throws Exception {
        return call(seller, SpApiOperation.GET_SELLER_MARKETPLACES, Map.of(), Map.of());
    }
    public SpApiResponse getNotificationSubscription(SpApiSellerScope seller, String type) throws Exception {
        return call(seller, SpApiOperation.GET_NOTIFICATION_SUBSCRIPTION,
            Map.of("notificationType", type), Map.of());
    }
}
