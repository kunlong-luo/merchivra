package org.merchivra.erp.catalog;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;
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

    /** V87: latest mapping *version* is informational; a non-ACTIVE listing
     * must never borrow the SKU identity from a superseded mapping. */
    public record ListingPageItem(String id,String sellerSku,String asin,String fnsku,
                                  String mappingStatus,String internalSku,
                                  Integer activeMappingVersion,Integer latestMappingVersion,
                                  String sourceUpdatedAt,String dataOrigin) {}
    public record ListingPage(List<ListingPageItem> items,String nextCursor,
                              boolean hasMore,int pageSize,String field,
                              String searchPrefix,String mappingStatus) {}
    private record ListingCursor(String sellerSku,UUID id) {}

    public record OrderView(String id, String externalOrderId, String fulfillmentStatus,
                            String refundStatus, String financialStatus,
                            long itemCount, String sourceUpdatedAt, String dataOrigin,
                            String itemsObservation) {}

    public record OrderPage(List<OrderView> items,String nextCursor,
                            boolean hasMore,int pageSize,String searchPrefix) {}
    private record OrderCursor(Instant updatedAt,UUID id) {}

    public record OrderItemView(String id,String externalItemId,String sellerSku,
                                Integer quantity,String itemSubtotal,String currency) {}

    public record InventoryView(String id, String sellerSku, String quality,
                                Integer fulfillable, Integer reserved,
                                String observedAt, String ingestedAt, String dataOrigin) {}
    public record InventoryPage(List<InventoryView> items,String nextCursor,
                                boolean hasMore,int pageSize,String searchPrefix,
                                String quality,String origin) {}


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


    /**
     * V87 / FR-CAT-02/03: bounded, server-side Listing prefix search and
     * deterministic keyset pagination. The caller's Session still supplies
     * tenant/actor on EVERY page, and a cursor is not an authorization token.
     */
    @GetMapping("/{marketplaceId}/listings/page")
    public org.springframework.http.ResponseEntity<ListingPage> listingPage(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(defaultValue="SELLER_SKU") String field,
        @RequestParam(defaultValue="") String search,
        @RequestParam(defaultValue="ALL") String status,
        @RequestParam(required=false) String cursor) {
        int size=limit(limit);
        String mode=listingSearchField(field);
        String filter=listingMappingStatus(status);
        String prefix=normalizedOrderPrefix(search);
        UUID tenant=authorizedMarketplace(authentication,request,marketplaceId);
        ListingCursor after=parseListingCursor(cursor,marketplaceId,mode,prefix,filter);
        // Exclamation-mark LIKE escaping avoids treating %, _ or ! as wildcards.
        String pattern=prefix.replace("!","!!").replace("%","!%")
            .replace("_","!_")+"%";
        // Only this closed enum chooses the SQL column. No client SQL fragments.
        String column=mode.equals("ASIN")?"l.asin":"l.seller_sku";
        String sql="""
            SELECT l.id,l.seller_sku,l.asin,l.fnsku,l.mapping_status,
                   active.internal_sku,active.mapping_version AS active_version,
                   (SELECT MAX(mm.mapping_version) FROM listing_sku_mapping mm
                    WHERE mm.tenant_id=l.tenant_id AND mm.listing_id=l.id)
                     AS latest_version,
                   l.source_updated_at,l.data_origin
            FROM marketplace_listing l
            LEFT JOIN LATERAL (
              SELECT p.internal_sku,m.mapping_version
              FROM listing_sku_mapping m
              LEFT JOIN product_sku p
                ON p.tenant_id=m.tenant_id AND p.id=m.product_sku_id
              WHERE m.tenant_id=l.tenant_id AND m.listing_id=l.id
                AND m.status='ACTIVE' AND m.valid_to IS NULL
              ORDER BY m.mapping_version DESC LIMIT 1
            ) active ON TRUE
            WHERE l.tenant_id=? AND l.seller_marketplace_id=?
              AND lower(%s) LIKE ? ESCAPE '!'
              AND (?='ALL' OR l.mapping_status=?)
              AND (?::boolean=false OR (l.seller_sku,l.id)>(?::text,?::uuid))
            ORDER BY l.seller_sku,l.id
            LIMIT ?
            """.formatted(column);
        List<ListingPageItem> fetched=jdbc.query(sql,(rs,i)->new ListingPageItem(
            uuid(rs,"id"),rs.getString("seller_sku"),rs.getString("asin"),
            rs.getString("fnsku"),rs.getString("mapping_status"),
            rs.getString("internal_sku"),
            (Integer)rs.getObject("active_version"),
            (Integer)rs.getObject("latest_version"),
            instant(rs,"source_updated_at"),rs.getString("data_origin")),
            tenant,marketplaceId,pattern,filter,filter,after!=null,
            after==null?null:after.sellerSku(),
            after==null?null:after.id(),size+1);
        boolean hasMore=fetched.size()>size;
        List<ListingPageItem> items=hasMore?
            new ArrayList<>(fetched.subList(0,size)):fetched;
        String next=hasMore?listingCursor(marketplaceId,mode,prefix,filter,
            items.getLast()):null;
        return org.springframework.http.ResponseEntity.ok()
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .body(new ListingPage(List.copyOf(items),next,hasMore,size,
                mode,prefix,filter));
    }

    private static String listingSearchField(String field) {
        if("SELLER_SKU".equals(field)||"ASIN".equals(field))return field;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Listing search field must be SELLER_SKU or ASIN");
    }
    private static String listingMappingStatus(String status) {
        if(java.util.Set.of("ALL","UNMAPPED","SUGGESTED","VERIFIED","ACTIVE",
            "CONFLICT","SUPERSEDED").contains(status))return status;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Unsupported Listing mapping status");
    }
    private static String listingCursor(UUID marketplace,String field,
                                         String prefix,String status,
                                         ListingPageItem last) {
        return skuCursor(marketplace,field,prefix,status,last.sellerSku(),last.id());
    }
    /** V88: reuse V87's newline-safe, filter-bound SKU keyset cursor format. */
    private static String skuCursor(UUID marketplace,String kind,String prefix,
                                    String filters,String sellerSku,String id) {
        String encodedSku=Base64.getUrlEncoder().withoutPadding().encodeToString(
            sellerSku.getBytes(StandardCharsets.UTF_8));
        String raw=marketplace+"\n"+kind+"\n"+prefix+"\n"+filters+
            "\n"+encodedSku+"\n"+id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            raw.getBytes(StandardCharsets.UTF_8));
    }
    private static ListingCursor parseListingCursor(String cursor,UUID marketplace,
                                                      String field,String prefix,
                                                      String status) {
        if(cursor==null||cursor.isBlank())return null;
        if(cursor.length()>4096)throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,"Invalid Listing cursor");
        try {
            String raw=new String(Base64.getUrlDecoder().decode(cursor),
                StandardCharsets.UTF_8);
            String[] parts=raw.split("\n",-1);
            if(parts.length!=6||!marketplace.toString().equals(parts[0])
                ||!field.equals(parts[1])||!prefix.equals(parts[2])
                ||!status.equals(parts[3]))throw new IllegalArgumentException();
            String sellerSku=new String(Base64.getUrlDecoder().decode(parts[4]),
                StandardCharsets.UTF_8);
            UUID id=UUID.fromString(parts[5]);
            if(sellerSku.isEmpty()||sellerSku.length()>200)
                throw new IllegalArgumentException();
            return new ListingCursor(sellerSku,id);
        } catch(IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid or mismatched Listing cursor");
        }
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

    /**
     * V86 / FR-ORD-02: server-side order ID prefix search + stable keyset pages.
     * Unlike the original 50-row summary, this can traverse the full observed
     * order table without using offset, trusting a browser tenantId, or
     * requesting buyer/recipient PII. A cursor is only a position, never
     * an authorization credential or a frozen global source snapshot.
     */
    @GetMapping("/{marketplaceId}/orders/page")
    public org.springframework.http.ResponseEntity<OrderPage> orderPage(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(defaultValue="") String search,
        @RequestParam(required=false) String cursor) {
        int size=limit(limit);
        String prefix=normalizedOrderPrefix(search);
        UUID tenant=authorizedMarketplace(authentication,request,marketplaceId);
        OrderCursor after=parseOrderCursor(cursor,marketplaceId,prefix);
        String pattern=prefix.replace("\\","\\\\").replace("%","\\%")
            .replace("_","\\_")+"%";
        List<OrderView> fetched=jdbc.query("""
            SELECT o.id,o.external_order_id,o.fulfillment_status_raw,
                   o.refund_status,o.financial_status,o.source_updated_at,
                   o.data_origin,o.items_observation,
                   (SELECT count(*) FROM order_item i
                    WHERE i.tenant_id=o.tenant_id AND i.sales_order_id=o.id
                      AND i.is_current=true) AS item_count
            FROM sales_order o
            WHERE o.tenant_id=? AND o.seller_marketplace_id=?
              AND o.external_order_id ILIKE ? ESCAPE '\\'
              AND (?::boolean=false OR
                (COALESCE(o.source_updated_at,'-infinity'::timestamptz),o.id)
                  < (COALESCE(?::timestamptz,'-infinity'::timestamptz),?::uuid))
            ORDER BY o.source_updated_at DESC NULLS LAST,o.id DESC
            LIMIT ?
            """,(rs,row)->new OrderView(
                uuid(rs,"id"),rs.getString("external_order_id"),
                rs.getString("fulfillment_status_raw"),rs.getString("refund_status"),
                rs.getString("financial_status"),rs.getLong("item_count"),
                instant(rs,"source_updated_at"),rs.getString("data_origin"),
                rs.getString("items_observation")),
            tenant,marketplaceId,pattern,after!=null,
            after==null||after.updatedAt()==null?null:after.updatedAt().toString(),
            after==null?null:after.id(),size+1);
        boolean hasMore=fetched.size()>size;
        List<OrderView> items=hasMore?new ArrayList<>(fetched.subList(0,size)):fetched;
        String next=hasMore?orderCursor(marketplaceId,prefix,items.getLast()):null;
        return org.springframework.http.ResponseEntity.ok()
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .body(new OrderPage(List.copyOf(items),next,hasMore,size,prefix));
    }

    private static String normalizedOrderPrefix(String input) {
        String prefix=input==null?"":input.strip().toLowerCase(Locale.ROOT);
        if(prefix.length()>80||prefix.codePoints().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Order search prefix must be <=80 printable characters");
        return prefix;
    }

    private static String orderCursor(UUID marketplace,String prefix,OrderView last) {
        String raw=marketplace+"\n"+prefix+"\n"+
            (last.sourceUpdatedAt()==null?"":last.sourceUpdatedAt())+"\n"+last.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            raw.getBytes(StandardCharsets.UTF_8));
    }
    private static OrderCursor parseOrderCursor(String input,UUID marketplace,String prefix) {
        if(input==null||input.isBlank())return null;
        if(input.length()>512)throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,"Invalid order cursor");
        try {
            String raw=new String(Base64.getUrlDecoder().decode(input),
                StandardCharsets.UTF_8);
            String[] parts=raw.split("\n",-1);
            if(parts.length!=4||!marketplace.toString().equals(parts[0])
                ||!prefix.equals(parts[1]))throw new IllegalArgumentException();
            UUID row=UUID.fromString(parts[3]);
            Instant stamp=parts[2].isEmpty()?null:Instant.parse(parts[2]);
            if(stamp!=null&&!stamp.toString().equals(parts[2]))
                throw new IllegalArgumentException();
            return new OrderCursor(stamp,row);
        } catch(IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid or mismatched order cursor");
        }
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

    /**
     * V88 / FR-INV-01/02: all latest-per-SKU FBA source observations, searched
     * and filtered on PostgreSQL before paging. Quality/origin are applied to
     * the latest observation only, never to older history before DISTINCT ON.
     * An OBSERVED zero stays zero; a missing quantity stays null, never zero.
     */
    @GetMapping("/{marketplaceId}/inventory/fba/page")
    public org.springframework.http.ResponseEntity<InventoryPage> inventoryPage(
        Authentication authentication,HttpServletRequest request,
        @PathVariable UUID marketplaceId,
        @RequestParam(defaultValue="50") int limit,
        @RequestParam(defaultValue="") String search,
        @RequestParam(defaultValue="ALL") String quality,
        @RequestParam(defaultValue="ALL") String origin,
        @RequestParam(required=false) String cursor) {
        int size=limit(limit);
        String prefix=normalizedOrderPrefix(search);
        String checkedQuality=inventoryQuality(quality);
        String checkedOrigin=inventoryOrigin(origin);
        UUID tenant=authorizedMarketplace(authentication,request,marketplaceId);
        // Distinct kinds prevent accidental cross-reuse of a V87 Listing cursor.
        String filterKey=checkedQuality+"|"+checkedOrigin;
        ListingCursor after=parseListingCursor(cursor,marketplaceId,
            "FBA_INVENTORY",prefix,filterKey);
        String pattern=prefix.replace("!","!!").replace("%","!%")
            .replace("_","!_")+"%";
        List<InventoryView> fetched=jdbc.query("""
            WITH latest AS (
              SELECT DISTINCT ON (seller_sku_raw)
                id,seller_sku_raw,quality,fulfillable,reserved,
                observed_at,ingested_at,data_origin
              FROM fba_inventory_observation
              WHERE tenant_id=? AND seller_marketplace_id=?
                AND lower(seller_sku_raw) LIKE ? ESCAPE '!'
              ORDER BY seller_sku_raw,observed_at DESC NULLS LAST,
                       ingested_at DESC,id DESC
            )
            SELECT i.id,i.seller_sku_raw,i.quality,i.fulfillable,i.reserved,
                   i.observed_at,i.ingested_at,i.data_origin
            FROM latest i
            WHERE (?='ALL' OR i.quality=?)
              AND (?='ALL' OR i.data_origin=?)
              AND (?::boolean=false OR (i.seller_sku_raw,i.id)>(?::text,?::uuid))
            ORDER BY i.seller_sku_raw,i.id
            LIMIT ?
            """,(rs,row)->new InventoryView(uuid(rs,"id"),
                rs.getString("seller_sku_raw"),rs.getString("quality"),
                nullableInteger(rs,"fulfillable"),nullableInteger(rs,"reserved"),
                instant(rs,"observed_at"),instant(rs,"ingested_at"),
                rs.getString("data_origin")),
            tenant,marketplaceId,pattern,
            checkedQuality,checkedQuality,checkedOrigin,checkedOrigin,
            after!=null,after==null?null:after.sellerSku(),
            after==null?null:after.id(),size+1);
        boolean hasMore=fetched.size()>size;
        List<InventoryView> items=hasMore?
            new ArrayList<>(fetched.subList(0,size)):fetched;
        String next=hasMore?skuCursor(marketplaceId,"FBA_INVENTORY",
            prefix,filterKey,items.getLast().sellerSku(),items.getLast().id()):null;
        return org.springframework.http.ResponseEntity.ok()
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .body(new InventoryPage(List.copyOf(items),next,hasMore,size,
                prefix,checkedQuality,checkedOrigin));
    }
    private static String inventoryQuality(String quality) {
        if(java.util.Set.of("ALL","OBSERVED","PARTIAL","UNKNOWN","STALE")
            .contains(quality))return quality;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Unsupported inventory quality");
    }
    private static String inventoryOrigin(String origin) {
        if(java.util.Set.of("ALL","MOCK","UNVERIFIED","LIVE")
            .contains(origin))return origin;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Unsupported inventory data origin");
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
