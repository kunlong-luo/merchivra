package org.merchivra.erp.spapi;

import software.amazon.spapi.api.orders.v2026_01_01.SearchOrdersApi;
import software.amazon.spapi.api.orders.v2026_01_01.GetOrderApi;
import software.amazon.spapi.api.listings.items.v2021_08_01.ListingsApi;
import software.amazon.spapi.api.fba.inventory.v1.FbaInventoryApi;
import software.amazon.spapi.api.catalogitems.v2022_04_01.CatalogApi;
import software.amazon.spapi.api.finances.v2024_06_19.DefaultApi;
import software.amazon.spapi.api.reports.v2021_06_30.ReportsApi;
import software.amazon.spapi.api.feeds.v2021_06_30.FeedsApi;
import software.amazon.spapi.api.notifications.v1.NotificationsApi;
import software.amazon.spapi.api.sellers.v1.SellersApi;
import software.amazon.spapi.api.sales.v1.SalesApi;
import software.amazon.spapi.api.pricing.v2022_05_01.ProductPricingApi;
import software.amazon.spapi.api.producttypedefinitions.v2020_09_01.DefinitionsApi;
import software.amazon.spapi.api.shipping.v2.ShippingApi;
import software.amazon.spapi.api.fulfillment.inbound.v2024_03_20.FbaInboundApi;
import software.amazon.spapi.api.fulfillment.outbound.v2026_07_04.FulfillmentOrdersApi;
import software.amazon.spapi.api.tokens.v2021_03_01.TokensApi;

/**
 * Typed access to the official SDK's generated API groups.
 *
 * These API objects are for trusted, authorized internal workers ONLY.
 * They may contain mutating methods: caller must separately verify allowed
 * role, Seller account scope, policy, and operator approval BEFORE invocation.
 * The application exposes none of these SDK methods as a user-facing proxy.
 */
public final class SpApiOfficialModules {
    private final SpApiOfficialSdkFactory sdk;
    public SpApiOfficialModules(SpApiOfficialSdkFactory sdk) { this.sdk=sdk; }

    public SearchOrdersApi searchOrders(SpApiSellerScope scope) { return sdk.create(SearchOrdersApi.class, scope); }
    public GetOrderApi ordersDetail(SpApiSellerScope scope) { return sdk.create(GetOrderApi.class, scope); }
    public ListingsApi listings(SpApiSellerScope scope) { return sdk.create(ListingsApi.class, scope); }
    public FbaInventoryApi fbaInventory(SpApiSellerScope scope) { return sdk.create(FbaInventoryApi.class, scope); }
    public CatalogApi catalog(SpApiSellerScope scope) { return sdk.create(CatalogApi.class, scope); }
    public DefaultApi finances(SpApiSellerScope scope) {
        if (!scope.approvedRoles().contains("Finance and Accounting"))
            throw new SecurityException("Finance and Accounting role not verified");
        return sdk.create(DefaultApi.class, scope);
    }
    public ReportsApi reports(SpApiSellerScope scope) { return sdk.create(ReportsApi.class, scope); }
    public FeedsApi feeds(SpApiSellerScope scope) { return sdk.create(FeedsApi.class, scope); }
    public NotificationsApi notifications(SpApiSellerScope scope) { return sdk.create(NotificationsApi.class, scope); }
    public SellersApi sellers(SpApiSellerScope scope) { return sdk.create(SellersApi.class, scope); }
    public SalesApi sales(SpApiSellerScope scope) { return sdk.create(SalesApi.class, scope); }
    public ProductPricingApi pricing(SpApiSellerScope scope) { return sdk.create(ProductPricingApi.class, scope); }
    public DefinitionsApi productTypes(SpApiSellerScope scope) { return sdk.create(DefinitionsApi.class, scope); }
    public ShippingApi shipping(SpApiSellerScope scope) { return sdk.create(ShippingApi.class, scope); }
    public FbaInboundApi fbaInbound(SpApiSellerScope scope) { return sdk.create(FbaInboundApi.class, scope); }
    public FulfillmentOrdersApi fulfillmentOrders(SpApiSellerScope scope) {
        return sdk.create(FulfillmentOrdersApi.class, scope);
    }
    public TokensApi restrictedData(SpApiSellerScope scope) {
        throw new SecurityException("Restricted PII token operations require separate security approval");
    }
}
