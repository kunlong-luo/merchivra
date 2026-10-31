package org.merchivra.erp.catalog;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only queries from normalized ERP tables, NOT the JSONB demo snapshot.
 * The tenant is derived from the authenticated session on every request.
 * Scope is also enforced in every SQL statement (defense in depth).
 *
 * V16: OWNER-wide or explicitly seller-account-scoped OPERATOR_READ only.
 */
@RestController
@RequestMapping("/api/v1/seller-marketplaces")
public class MarketplaceReadController {
    private final JdbcTemplate jdbc;
    private final TenantAccessService tenantAccess;

    public MarketplaceReadController(JdbcTemplate jdbc, TenantAccessService tenantAccess) {
        this.jdbc = jdbc;
        this.tenantAccess = tenantAccess;
    }

    public record MarketplaceView(String id, String sellerAccountId, String marketplaceId,
                                  String region, String currency, String timezone,
                                  String connectionStatus) {}

    public record ListingView(String id, String sellerSku, String asin, String fnsku,
                              String mappingStatus, String internalSku,
                              String sourceUpdatedAt, String dataOrigin) {}

    public record OrderView(String id, String externalOrderId, String fulfillmentStatus,
                            String refundStatus, String financialStatus,
                            long itemCount, String sourceUpdatedAt, String dataOrigin,
                            String itemsObservation) {}

    public record OrderItemView(String id,String externalItemId,String sellerSku,
                                Integer quantity,String itemSubtotal,String currency) {}

    public record InventoryView(String id, String sellerSku, String quality,
                                Integer fulfillable, Integer reserved,
                                String observedAt, String ingestedAt, String dataOrigin) {}

    @GetMapping
    public List<MarketplaceView> marketplaces(Authentication authentication, HttpServletRequest request) {
        UUID tenantId = tenantAccess.currentReadTenant(authentication, request);
        UUID actor = tenantAccess.authenticatedUser(authentication);
        return jdbc.query("""
            SELECT mp.id, mp.seller_account_id, mp.marketplace_id, a.region,
                   mp.currency_code, mp.timezone, a.connection_status
            FROM seller_marketplace mp
            JOIN seller_account a ON a.tenant_id = mp.tenant_id AND a.id = mp.seller_account_id
            WHERE mp.tenant_id = ?
              AND EXISTS (
                SELECT 1 FROM membership m JOIN app_user u ON u.id=m.user_id
                JOIN role_binding rb ON rb.tenant_id=m.tenant_id AND rb.user_id=m.user_id
                WHERE m.tenant_id=mp.tenant_id AND m.user_id=? AND m.status='ACTIVE'
                  AND u.status='ACTIVE'
                  AND ((rb.role_code='OWNER' AND rb.seller_account_id IS NULL)
                    OR (rb.role_code='OPERATOR_READ' AND rb.seller_account_id=a.id))
              )
            ORDER BY mp.marketplace_id, mp.id
            LIMIT 100
            """, (rs, row) -> new MarketplaceView(
                uuid(rs, "id"), uuid(rs, "seller_account_id"),
                rs.getString("marketplace_id"), rs.getString("region"),
                rs.getString("currency_code").trim(), rs.getString("timezone"),
                rs.getString("connection_status")), tenantId,actor);
    }

    @GetMapping("/{marketplaceId}/listings")
    public List<ListingView> listings(
        Authentication authentication, HttpServletRequest request,
        @PathVariable UUID marketplaceId, @RequestParam(defaultValue = "50") int limit) {
        int safeLimit = limit(limit);
        UUID tenant = authorizedMarketplace(authentication, request, marketplaceId);
        return jdbc.query("""
            SELECT l.id, l.seller_sku, l.asin, l.fnsku, l.mapping_status,
                   sku.internal_sku, l.source_updated_at, l.data_origin
            FROM marketplace_listing l
            LEFT JOIN LATERAL (
              SELECT p.internal_sku FROM listing_sku_mapping m
              LEFT JOIN product_sku p
                ON p.tenant_id = m.tenant_id AND p.id = m.product_sku_id
              WHERE m.tenant_id = l.tenant_id AND m.listing_id = l.id
                AND m.status = 'ACTIVE' AND m.valid_to IS NULL
              ORDER BY m.mapping_version DESC LIMIT 1
            ) sku ON TRUE
            WHERE l.tenant_id = ? AND l.seller_marketplace_id = ?
            ORDER BY l.seller_sku, l.id
            LIMIT ?
            """, (rs, row) -> new ListingView(
                uuid(rs, "id"), rs.getString("seller_sku"), rs.getString("asin"),
                rs.getString("fnsku"), rs.getString("mapping_status"),
                rs.getString("internal_sku"), instant(rs, "source_updated_at"),
                rs.getString("data_origin")),
            tenant, marketplaceId, safeLimit);
    }

    @GetMapping("/{marketplaceId}/orders")
    public List<OrderView> orders(
        Authentication authentication, HttpServletRequest request,
        @PathVariable UUID marketplaceId, @RequestParam(defaultValue = "50") int limit) {
        int safeLimit = limit(limit);
        UUID tenant = authorizedMarketplace(authentication, request, marketplaceId);
        return jdbc.query("""
            SELECT o.id, o.external_order_id, o.fulfillment_status_raw,
                   o.refund_status, o.financial_status, o.source_updated_at, o.data_origin,
                   o.items_observation,
                   (SELECT COUNT(*) FROM order_item i
                     WHERE i.tenant_id = o.tenant_id AND i.sales_order_id = o.id
                       AND i.is_current=true) AS item_count
            FROM sales_order o
            WHERE o.tenant_id = ? AND o.seller_marketplace_id = ?
            ORDER BY o.source_updated_at DESC NULLS LAST, o.id DESC
            LIMIT ?
            """, (rs, row) -> new OrderView(
                uuid(rs, "id"), rs.getString("external_order_id"),
                rs.getString("fulfillment_status_raw"), rs.getString("refund_status"),
                rs.getString("financial_status"), rs.getLong("item_count"),
                instant(rs, "source_updated_at"), rs.getString("data_origin"),
                rs.getString("items_observation")),
            tenant, marketplaceId, safeLimit);
    }

    /** No buyer/recipient PII. Same explicit Marketplace access as order listing. */
    @GetMapping("/{marketplaceId}/orders/{orderId}/items")
    public List<OrderItemView> orderItems(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId) {
        UUID tenant=authorizedMarketplace(authentication,request,marketplaceId);
        Integer found=jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales_order
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """,Integer.class,tenant,marketplaceId,orderId);
        if(found==null || found!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
        return jdbc.query("""
            SELECT i.id,i.external_item_id,i.seller_sku_raw,i.quantity,
                   i.proceeds_amount,i.proceeds_currency
            FROM order_item i JOIN sales_order o
              ON o.tenant_id=i.tenant_id AND o.id=i.sales_order_id
            WHERE o.tenant_id=? AND o.seller_marketplace_id=?
              AND o.id=? AND i.is_current=true
            ORDER BY i.external_item_id LIMIT 1000
            """,(rs,n)->{
                var amount=rs.getBigDecimal("proceeds_amount");
                var currency=rs.getString("proceeds_currency");
                return new OrderItemView(uuid(rs,"id"),
                    rs.getString("external_item_id"),rs.getString("seller_sku_raw"),
                    nullableInteger(rs,"quantity"),
                    amount==null?null:amount.toPlainString(),
                    currency==null?null:currency.trim());
            },tenant,marketplaceId,orderId);
    }

    @GetMapping("/{marketplaceId}/inventory/fba")
    public List<InventoryView> inventory(
        Authentication authentication, HttpServletRequest request,
        @PathVariable UUID marketplaceId, @RequestParam(defaultValue = "50") int limit) {
        int safeLimit = limit(limit);
        UUID tenant = authorizedMarketplace(authentication, request, marketplaceId);
        return jdbc.query("""
            SELECT i.id, i.seller_sku_raw, i.quality, i.fulfillable, i.reserved,
                   i.observed_at, i.ingested_at, i.data_origin
            FROM (
              SELECT DISTINCT ON (seller_sku_raw)
                id, seller_sku_raw, quality, fulfillable, reserved, observed_at, ingested_at, data_origin
              FROM fba_inventory_observation
              WHERE tenant_id = ? AND seller_marketplace_id = ?
              ORDER BY seller_sku_raw, observed_at DESC NULLS LAST,
                       ingested_at DESC, id DESC
            ) i
            ORDER BY i.seller_sku_raw
            LIMIT ?
            """, (rs, row) -> new InventoryView(
                uuid(rs, "id"), rs.getString("seller_sku_raw"),
                rs.getString("quality"), nullableInteger(rs, "fulfillable"),
                nullableInteger(rs, "reserved"), instant(rs, "observed_at"),
                instant(rs, "ingested_at"), rs.getString("data_origin")),
            tenant, marketplaceId, safeLimit);
    }

    private UUID authorizedMarketplace(Authentication auth, HttpServletRequest request, UUID marketplaceId) {
        UUID tenant = tenantAccess.currentReadTenant(auth, request);
        UUID actor = tenantAccess.authenticatedUser(auth);
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM seller_marketplace mp
            JOIN seller_account sa
              ON sa.tenant_id = mp.tenant_id AND sa.id = mp.seller_account_id
            WHERE mp.id = ? AND mp.tenant_id = ? AND sa.tenant_id = ?
              AND EXISTS (
                SELECT 1 FROM membership m JOIN app_user u ON u.id=m.user_id
                JOIN role_binding rb ON rb.tenant_id=m.tenant_id AND rb.user_id=m.user_id
                WHERE m.tenant_id=mp.tenant_id AND m.user_id=? AND m.status='ACTIVE'
                  AND u.status='ACTIVE'
                  AND ((rb.role_code='OWNER' AND rb.seller_account_id IS NULL)
                    OR (rb.role_code='OPERATOR_READ'
                        AND rb.seller_account_id=sa.id))
              )
            """, Integer.class, marketplaceId, tenant, tenant,actor);
        // The same 404 for unknown and foreign-tenant IDs avoids disclosing existence.
        if (count == null || count != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Marketplace not found");
        }
        return tenant;
    }

    private static int limit(int value) {
        if (value < 1 || value > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..100");
        }
        return value;
    }

    private static String uuid(ResultSet rs, String field) throws SQLException {
        return rs.getObject(field, UUID.class).toString();
    }

    private static String instant(ResultSet rs, String field) throws SQLException {
        OffsetDateTime timestamp = rs.getObject(field, OffsetDateTime.class);
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private static Integer nullableInteger(ResultSet rs, String field) throws SQLException {
        int number = rs.getInt(field);
        return rs.wasNull() ? null : number;
    }
}
