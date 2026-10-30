package org.merchivra.erp.demo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.demo.DemoModels.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Opt-in simulated adapter -> PostgreSQL canonical facts, scoped to a local
 * unconfigured account of the current authenticated tenant.
 * NEVER call real SP-API, use live credentials, or infer settled finances.
 */
@Service
@ConditionalOnProperty(prefix="merchivra.demo", name="enabled", havingValue="true")
public class SandboxSyncService {
    private final JdbcTemplate jdbc;
    private final MockSpApiClient client;

    public SandboxSyncService(JdbcTemplate jdbc, MockSpApiClient client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    public record Result(String id, String status, String source, String marketplaceId,
                         int orderPageCount, int listingCount, int orderCount, int inventoryCount,
                         String financeStatus, String failureCode, String createdAt) {}

    public static UUID stableId(UUID tenant, UUID marketplace, String kind, String key) {
        return UUID.nameUUIDFromBytes(
            ("merchivra:sandbox:" + tenant + ":" + marketplace + ":" + kind + ":" + key)
                .getBytes(StandardCharsets.UTF_8));
    }

    public static UUID stableProductId(UUID tenant, String internalSku) {
        // An ERP internal SKU is tenant-wide, NOT marketplace-specific.
        // US/DE listings can share the same tenant SKU without conflicting IDs.
        return UUID.nameUUIDFromBytes(
            ("merchivra:sandbox:" + tenant + ":sku:" + internalSku)
                .getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public Result replay(UUID tenant, UUID actor, UUID marketplace, String requestKey,
                         boolean simulateFailure) {
        if (requestKey == null || !requestKey.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        // Must be a LOCAL-only, UNCONFIGURED account in the authenticated tenant.
        // Unknown and foreign tenant marketplaces have exactly the same 404.
        List<String> marketplaces = jdbc.query("""
            SELECT mp.marketplace_id FROM seller_marketplace mp
            JOIN seller_account sa ON sa.tenant_id = mp.tenant_id
              AND sa.id = mp.seller_account_id
            WHERE mp.tenant_id = ? AND mp.id = ?
              AND sa.connection_status = 'UNCONFIGURED' AND sa.seller_id LIKE 'LOCAL-%'
            """, (rs, row) -> rs.getString("marketplace_id"), tenant, marketplace);
        if (marketplaces.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local marketplace not found");
        }
        String site = marketplaces.getFirst();
        MockSpApiClient.Snapshot fixture;
        try { fixture = client.simulate(site); }
        catch (IllegalArgumentException unsupported) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "No synthetic scenario for selected Marketplace");
        }
        String fingerprint = simulateFailure ? "MOCK:FAIL" : "MOCK:IMPORT";
        UUID runId = UUID.randomUUID();
        String status = simulateFailure ? "FAILED_RETRYABLE" : "SUCCEEDED";
        int pageCount = simulateFailure ? 0 : fixture.orderPages().size();
        int orderCount = simulateFailure ? 0 : fixture.orderPages().stream()
            .mapToInt(page -> page.records().size()).sum();
        int listingCount = simulateFailure ? 0 : fixture.listings().size();
        int inventoryCount = simulateFailure ? 0 : fixture.inventory().size();
        String failureCode = simulateFailure ? "MOCK_PAGE_FETCH_FAILED" : null;

        int inserted = jdbc.update("""
            INSERT INTO sandbox_sync_run
              (id, tenant_id, actor_user_id, seller_marketplace_id, idempotency_key,
               request_fingerprint, status, order_page_count, listing_count, order_count,
               inventory_count, failure_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, seller_marketplace_id, idempotency_key) DO NOTHING
            """, runId, tenant, actor, marketplace, requestKey, fingerprint, status,
            pageCount, listingCount, orderCount, inventoryCount, failureCode);
        if (inserted == 0) {
            List<String> stored = jdbc.queryForList("""
                SELECT request_fingerprint FROM sandbox_sync_run
                WHERE tenant_id = ? AND seller_marketplace_id = ? AND idempotency_key = ?
                """, String.class, tenant, marketplace, requestKey);
            if (stored.isEmpty()) throw new IllegalStateException("Idempotency record missing");
            if (!fingerprint.equals(stored.getFirst())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency-Key reused with different request");
            }
            return byKey(tenant, marketplace, requestKey);
        }
        if (!simulateFailure) {
            for (Listing listing : fixture.listings()) upsertListing(tenant, marketplace, listing);
            // Each mocked Orders page is treated as a separate stable page. In the
            // real Worker this boundary must include a durable page commit and lease.
            for (MockSpApiClient.Page page : fixture.orderPages()) {
                for (Order order : page.records()) upsertOrder(tenant, marketplace, order);
            }
            for (Inventory observation : fixture.inventory()) upsertInventory(tenant, marketplace, observation);
            jdbc.update("""
                INSERT INTO local_setup_audit
                  (id, tenant_id, actor_user_id, action, resource_type, resource_id)
                VALUES (?, ?, ?, 'SANDBOX_FIXTURE_IMPORTED', 'MARKETPLACE', ?)
                """, UUID.randomUUID(), tenant, actor, marketplace);
        }
        return byKey(tenant, marketplace, requestKey);
    }

    @Transactional(readOnly=true)
    public List<Result> recent(UUID tenant, UUID marketplace) {
        if (jdbc.queryForObject("""
            SELECT COUNT(*) FROM seller_marketplace WHERE tenant_id = ? AND id = ?
            """, Integer.class, tenant, marketplace) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Marketplace not found");
        }
        return jdbc.query("""
            SELECT id, status, source, seller_marketplace_id, order_page_count, listing_count,
                   order_count, inventory_count, failure_code, created_at
            FROM sandbox_sync_run WHERE tenant_id = ? AND seller_marketplace_id = ?
            ORDER BY created_at DESC, id DESC LIMIT 20
            """, SandboxSyncService::mapRun, tenant, marketplace);
    }

    private Result byKey(UUID tenant, UUID marketplace, String key) {
        return jdbc.query("""
            SELECT id, status, source, seller_marketplace_id, order_page_count, listing_count,
                   order_count, inventory_count, failure_code, created_at
            FROM sandbox_sync_run
            WHERE tenant_id = ? AND seller_marketplace_id = ? AND idempotency_key = ?
            """, SandboxSyncService::mapRun, tenant, marketplace, key).getFirst();
    }

    private static Result mapRun(ResultSet rs, int row) throws SQLException {
        return new Result(rs.getObject("id", UUID.class).toString(), rs.getString("status"),
            rs.getString("source"), rs.getObject("seller_marketplace_id", UUID.class).toString(),
            rs.getInt("order_page_count"), rs.getInt("listing_count"), rs.getInt("order_count"),
            rs.getInt("inventory_count"), "NOT_AUTHORIZED", rs.getString("failure_code"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant().toString());
    }

    private void upsertListing(UUID tenant, UUID marketplace, Listing item) {
        UUID id = stableId(tenant, marketplace, "listing", item.sellerSku());
        jdbc.update("""
            INSERT INTO marketplace_listing
              (id, tenant_id, seller_marketplace_id, seller_sku, asin, mapping_status, data_origin)
            VALUES (?, ?, ?, ?, ?, ?, 'MOCK')
            ON CONFLICT (tenant_id, seller_marketplace_id, seller_sku) DO NOTHING
            """, id, tenant, marketplace, item.sellerSku(), item.asin(), item.mappingStatus());
        if (item.internalSku() != null) {
            String localSku = "SANDBOX-" + item.internalSku();
            UUID skuId = stableProductId(tenant, localSku);
            jdbc.update("""
                INSERT INTO product_sku (id, tenant_id, internal_sku, display_name)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, internal_sku) DO NOTHING
                """, skuId, tenant, localSku, "Sandbox · " + localSku);
            jdbc.update("""
                INSERT INTO listing_sku_mapping
                  (id, tenant_id, listing_id, product_sku_id, status, valid_from, mapping_version)
                VALUES (?, ?, ?, ?, 'ACTIVE', '2026-10-27T00:00:00Z', 1)
                ON CONFLICT (id) DO NOTHING
                """, stableId(tenant, marketplace, "mapping", item.sellerSku()),
                tenant, id, skuId);
        }
    }

    private void upsertOrder(UUID tenant, UUID marketplace, Order item) {
        String key = "SANDBOX-" + item.externalOrderId();
        UUID id = stableId(tenant, marketplace, "order", key);
        jdbc.update("""
            INSERT INTO sales_order
              (id, tenant_id, seller_marketplace_id, external_order_id, fulfillment_status_raw,
               refund_status, financial_status, source_updated_at, data_origin)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MOCK')
            ON CONFLICT (tenant_id, seller_marketplace_id, external_order_id) DO NOTHING
            """, id, tenant, marketplace, key, item.fulfillmentStatus(), item.refundStatus(),
            item.financialStatus(), OffsetDateTime.parse(item.sourceUpdatedAt()));
        jdbc.update("""
            INSERT INTO order_item
              (id, tenant_id, sales_order_id, external_item_id, seller_sku_raw, quantity,
               proceeds_amount, proceeds_currency, data_origin)
            VALUES (?, ?, ?, 'MOCK-ITEM-1', ?, 1, ?, ?, 'MOCK')
            ON CONFLICT (tenant_id, sales_order_id, external_item_id) DO NOTHING
            """, stableId(tenant, marketplace, "order-item", key), tenant, id, item.sellerSku(),
            new BigDecimal(item.provisionalSales().amount()), item.provisionalSales().currency());
    }

    private void upsertInventory(UUID tenant, UUID marketplace, Inventory item) {
        String key = "SANDBOX:" + item.id();
        jdbc.update("""
            INSERT INTO fba_inventory_observation
              (id, tenant_id, seller_marketplace_id, seller_sku_raw, source_observation_key,
               quality, fulfillable, reserved, observed_at, data_origin)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'MOCK')
            ON CONFLICT (tenant_id, seller_marketplace_id, source_observation_key) DO NOTHING
            """, stableId(tenant, marketplace, "inventory", key),
            tenant, marketplace, item.sellerSku(), key, item.quality(), item.fulfillable(),
            item.reserved(), item.observedAt() == null ? null : OffsetDateTime.parse(item.observedAt()));
    }
}
