package org.merchivra.erp.demo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.merchivra.erp.demo.DemoModels.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Maps ONLY the fixed, invented Mock fixtures to tenant-scoped core tables.
 * This must never be called from a real Seller adapter or public business API.
 * DemoSyncService controls the transaction (snapshot + core upserts + run record).
 */
@Service
@ConditionalOnProperty(prefix = "merchivra.demo", name = "enabled", havingValue = "true")
public class DemoCoreImporter {
    public static final UUID DEMO_TENANT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private final JdbcTemplate jdbc;

    public DemoCoreImporter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static UUID stableId(String kind, String externalKey) {
        return UUID.nameUUIDFromBytes(("merchivra:demo:" + kind + ":" + externalKey)
            .getBytes(StandardCharsets.UTF_8));
    }

    public static void validateFixture(Overview overview) {
        if (!"MOCK".equals(overview.mode()) || overview.accounts().size() != 2) {
            throw new IllegalArgumentException("Only the two internal Mock shops are supported");
        }
        Set<String> expected = Set.of(
            MockMarketplaceOverviewGateway.US_ACCOUNT, MockMarketplaceOverviewGateway.EU_ACCOUNT);
        Set<String> actual = new HashSet<>();
        Map<String, String> currency = new HashMap<>();
        for (Account account : overview.accounts()) {
            if (!expected.contains(account.id()) || !actual.add(account.id())
                || !"MOCK_ONLY".equals(account.connectionStatus())) {
                throw new IllegalArgumentException("Unknown or duplicated demo Seller account");
            }
            currency.put(account.id(), account.currency());
        }
        if (!actual.equals(expected)) throw new IllegalArgumentException("Incomplete demo accounts");
        for (Listing listing : overview.listings()) requireAccount(actual, listing.accountId());
        for (Order order : overview.orders()) {
            requireAccount(actual, order.accountId());
            if (!currency.get(order.accountId()).equals(order.provisionalSales().currency())
                || !currency.get(order.accountId()).equals(order.refundAmount().currency())) {
                throw new IllegalArgumentException("Mock cross-currency data is not allowed");
            }
            new BigDecimal(order.provisionalSales().amount()); // Reject invalid amounts.
        }
        for (Inventory observation : overview.inventory()) {
            requireAccount(actual, observation.accountId());
            if ((observation.fulfillable() != null && observation.fulfillable() < 0)
                || (observation.reserved() != null && observation.reserved() < 0)) {
                throw new IllegalArgumentException("Mock stock cannot be negative");
            }
        }
    }

    private static void requireAccount(Set<String> accounts, String id) {
        if (!accounts.contains(id)) throw new IllegalArgumentException("Cross-scope Mock record");
    }

    public void importFixture(Overview fixture) {
        validateFixture(fixture);
        jdbc.update("""
            INSERT INTO tenant (id, name, status) VALUES (?, 'Merchivra synthetic demo', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """, DEMO_TENANT);
        Map<String, UUID> marketplaceIds = new HashMap<>();
        for (Account account : fixture.accounts()) {
            UUID accountId = UUID.fromString(account.id());
            UUID mpId = stableId("marketplace", account.id() + "/" + account.marketplaceId());
            marketplaceIds.put(account.id(), mpId);

            jdbc.update("""
                INSERT INTO seller_account (id, tenant_id, seller_id, region, connection_status)
                VALUES (?, ?, ?, ?, 'MOCK_ONLY')
                ON CONFLICT (id) DO NOTHING
                """, accountId, DEMO_TENANT, "MOCK-" + account.id(), account.region());
            jdbc.update("""
                INSERT INTO seller_marketplace
                  (id, tenant_id, seller_account_id, marketplace_id, currency_code, timezone)
                VALUES (?, ?, ?, ?, ?, 'UTC')
                ON CONFLICT (id) DO NOTHING
                """, mpId, DEMO_TENANT, accountId, account.marketplaceId(), account.currency());
        }

        for (Listing listing : fixture.listings()) {
            UUID marketplaceId = marketplaceIds.get(listing.accountId());
            UUID listingId = stableId("listing", listing.accountId() + "/" + listing.id());
            jdbc.update("""
                INSERT INTO marketplace_listing
                  (id, tenant_id, seller_marketplace_id, seller_sku, asin, mapping_status)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                  mapping_status = EXCLUDED.mapping_status
                WHERE marketplace_listing.tenant_id = EXCLUDED.tenant_id
                  AND marketplace_listing.seller_marketplace_id = EXCLUDED.seller_marketplace_id
                """, listingId, DEMO_TENANT, marketplaceId, listing.sellerSku(),
                listing.asin(), listing.mappingStatus());

            if (listing.internalSku() != null) {
                UUID skuId = stableId("sku", listing.internalSku());
                jdbc.update("""
                    INSERT INTO product_sku (id, tenant_id, internal_sku, display_name)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, skuId, DEMO_TENANT, listing.internalSku(), listing.internalSku());
                jdbc.update("""
                    INSERT INTO listing_sku_mapping
                      (id, tenant_id, listing_id, product_sku_id, status, valid_from, mapping_version)
                    VALUES (?, ?, ?, ?, 'ACTIVE', '2026-10-27T00:00:00Z', 1)
                    ON CONFLICT (id) DO NOTHING
                    """, stableId("listing-map", listingId.toString()),
                    DEMO_TENANT, listingId, skuId);
            }
        }

        for (Order order : fixture.orders()) {
            UUID marketplaceId = marketplaceIds.get(order.accountId());
            UUID orderId = stableId("order", order.accountId() + "/" + order.externalOrderId());
            OffsetDateTime sourceTime = OffsetDateTime.parse(order.sourceUpdatedAt());
            jdbc.update("""
                INSERT INTO sales_order
                  (id, tenant_id, seller_marketplace_id, external_order_id,
                   fulfillment_status_raw, refund_status, financial_status, source_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                  fulfillment_status_raw = EXCLUDED.fulfillment_status_raw,
                  refund_status = EXCLUDED.refund_status,
                  financial_status = EXCLUDED.financial_status,
                  source_updated_at = EXCLUDED.source_updated_at
                WHERE sales_order.tenant_id = EXCLUDED.tenant_id
                  AND sales_order.seller_marketplace_id = EXCLUDED.seller_marketplace_id
                  AND (sales_order.source_updated_at IS NULL
                       OR sales_order.source_updated_at <= EXCLUDED.source_updated_at)
                """, orderId, DEMO_TENANT, marketplaceId, order.externalOrderId(),
                order.fulfillmentStatus(), order.refundStatus(), order.financialStatus(), sourceTime);
            jdbc.update("""
                INSERT INTO order_item
                  (id, tenant_id, sales_order_id, external_item_id, seller_sku_raw,
                   quantity, proceeds_amount, proceeds_currency)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (tenant_id, sales_order_id, external_item_id)
                  DO NOTHING
                """, stableId("item", orderId.toString()), DEMO_TENANT, orderId,
                "MOCK-ITEM-1", order.sellerSku(),
                new BigDecimal(order.provisionalSales().amount()),
                order.provisionalSales().currency());
        }

        for (Inventory item : fixture.inventory()) {
            UUID marketplaceId = marketplaceIds.get(item.accountId());
            OffsetDateTime observedTime = item.observedAt() == null
                ? null : OffsetDateTime.parse(item.observedAt());
            jdbc.update("""
                INSERT INTO fba_inventory_observation
                  (id, tenant_id, seller_marketplace_id, seller_sku_raw,
                   source_observation_key, quality, fulfillable, reserved, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, seller_marketplace_id, source_observation_key)
                  DO NOTHING
                """, stableId("inventory", item.accountId() + "/" + item.id()),
                DEMO_TENANT, marketplaceId, item.sellerSku(),
                "MOCK:" + item.id(), item.quality(), item.fulfillable(), item.reserved(),
                observedTime);
        }
    }

    /**
     * Read-only counts are scoped to a hard-coded synthetic tenant.
     * Not a general public tenant query endpoint.
     */
    public CoreStatus status() {
        return new CoreStatus("MOCK", DEMO_TENANT.toString(),
            count("seller_account"), count("seller_marketplace"),
            count("product_sku"), count("marketplace_listing"),
            count("sales_order"), count("order_item"),
            count("fba_inventory_observation"));
    }

    private long count(String table) {
        // Names are fixed internal constants, never HTTP input.
        Long value = jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE tenant_id = ?",
            Long.class, DEMO_TENANT);
        return value == null ? 0 : value;
    }

    public record CoreStatus(String source, String tenantId, long accounts,
                             long marketplaces, long internalSkus, long listings,
                             long orders, long orderItems, long inventoryObservations) {}
}
