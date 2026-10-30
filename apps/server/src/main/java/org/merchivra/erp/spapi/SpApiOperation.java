package org.merchivra.erp.spapi;

import java.util.Map;
import java.util.Set;

/**
 * Reviewed operation/path registry. Not the entire Amazon API surface.
 * Read and write methods are explicit so the transport never acts as an
 * open proxy to arbitrary paths or unreviewed operations.
 */
public enum SpApiOperation {
    SEARCH_ORDERS("Orders", "2026-01-01", "GET", "/orders/2026-01-01/orders",
        Set.of("marketplaceIds", "createdAfter", "createdBefore",
            "lastUpdatedAfter", "lastUpdatedBefore", "paginationToken",
            "maxResultsPerPage", "includedData", "fulfillmentStatuses", "fulfilledBy")),
    GET_ORDER("Orders", "2026-01-01", "GET", "/orders/2026-01-01/orders/{orderId}",
        Set.of("includedData")),
    SEARCH_LISTINGS("Listings", "2021-08-01", "GET", "/listings/2021-08-01/items/{sellerId}",
        Set.of("marketplaceIds", "includedData", "pageSize", "pageToken",
            "lastUpdatedAfter", "lastUpdatedBefore")),
    GET_LISTING("Listings", "2021-08-01", "GET", "/listings/2021-08-01/items/{sellerId}/{sku}",
        Set.of("marketplaceIds", "includedData", "issueLocale")),
    GET_FBA_INVENTORY("FBA Inventory", "v1", "GET", "/fba/inventory/v1/summaries",
        Set.of("granularityType", "granularityId", "marketplaceIds", "details",
            "startDateTime", "sellerSkus", "sellerSku", "nextToken")),
    SEARCH_CATALOG("Catalog", "2022-04-01", "GET", "/catalog/2022-04-01/items",
        Set.of("marketplaceIds", "keywords", "identifiers", "identifiersType",
            "includedData", "pageSize", "pageToken", "brandNames")),
    GET_CATALOG_ITEM("Catalog", "2022-04-01", "GET", "/catalog/2022-04-01/items/{asin}",
        Set.of("marketplaceIds", "includedData", "locale")),
    LIST_TRANSACTIONS("Finances", "2024-06-19", "GET", "/finances/2024-06-19/transactions",
        Set.of("postedAfter", "postedBefore", "marketplaceId", "nextToken",
            "pageSize", "transactionStatus", "relatedIdentifierName", "relatedIdentifierValue")),
    GET_REPORTS("Reports", "2021-06-30", "GET", "/reports/2021-06-30/reports",
        Set.of("reportTypes", "processingStatuses", "marketplaceIds",
            "pageSize", "nextToken", "createdSince", "createdUntil")),
    GET_REPORT("Reports", "2021-06-30", "GET", "/reports/2021-06-30/reports/{reportId}",
        Set.of()),
    GET_REPORT_DOCUMENT("Reports", "2021-06-30", "GET",
        "/reports/2021-06-30/documents/{reportDocumentId}", Set.of()),
    CREATE_REPORT("Reports", "2021-06-30", "POST", "/reports/2021-06-30/reports", Set.of()),
    CREATE_FEED_DOCUMENT("Feeds", "2021-06-30", "POST", "/feeds/2021-06-30/documents", Set.of()),
    GET_FEED("Feeds", "2021-06-30", "GET", "/feeds/2021-06-30/feeds/{feedId}", Set.of()),
    GET_FEEDS("Feeds", "2021-06-30", "GET", "/feeds/2021-06-30/feeds",
        Set.of("feedTypes", "marketplaceIds", "pageSize", "nextToken",
            "processingStatuses", "createdSince", "createdUntil")),
    CREATE_FEED("Feeds", "2021-06-30", "POST", "/feeds/2021-06-30/feeds", Set.of());

    private final String family, version, method, path;
    private final Set<String> params;
    SpApiOperation(String family, String version, String method, String path, Set<String> params) {
        this.family = family; this.version = version;
        this.method = method; this.path = path; this.params = Set.copyOf(params);
    }
    public String family() { return family; }
    public String version() { return version; }
    public String method() { return method; }
    public String path() { return path; }
    public Set<String> queryParameters() { return params; }
    public boolean mutatesRemoteState() { return !"GET".equals(method); }
    public boolean requiresFinanceRole() { return this == LIST_TRANSACTIONS; }

    public void checkQuery(Map<String, String> query) {
        if (query.keySet().stream().anyMatch(k -> !params.contains(k))) {
            throw new IllegalArgumentException("Unsupported query parameter for " + name());
        }
        if ((this == SEARCH_ORDERS || this == GET_ORDER)
            && query.getOrDefault("includedData", "").toUpperCase(java.util.Locale.ROOT)
                .matches(".*(BUYER|RECIPIENT).*")) {
            throw new IllegalArgumentException("Buyer/recipient PII disabled by default");
        }
        if (this == GET_FBA_INVENTORY && query.containsKey("startDateTime")
            && (query.containsKey("sellerSku") || query.containsKey("sellerSkus"))) {
            throw new IllegalArgumentException("SKU filters are ignored by Amazon when startDateTime is used");
        }
    }
}
