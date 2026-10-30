package org.merchivra.erp.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Allows a logged-in tenant OWNER to set up local, UNCONFIGURED placeholders.
 * This service NEVER accepts or stores Amazon seller IDs, OAuth tokens,
 * credentials or SP-API authorization.
 */
@Service
public class LocalSellerSetupService {
    private final JdbcTemplate jdbc;
    public LocalSellerSetupService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record LocalAccount(String id, String displayName, String region,
                               String connectionStatus) {}
    public record LocalMarketplace(String id, String sellerAccountId, String marketplaceId,
                                   String currency, String timezone, String connectionStatus) {}
    public record AuditEvent(String id, String actorUserId, String action,
                             String resourceType, String resourceId, String createdAt) {}

    private record Preset(String region, String currency, String timezone) {}
    private static final Map<String, Preset> MARKETPLACES = Map.of(
        "ATVPDKIKX0DER", new Preset("NA", "USD", "America/Los_Angeles"),
        "A1PA6795UKMFR9", new Preset("EU", "EUR", "Europe/Berlin"),
        "A1VC38T7YXB528", new Preset("FE", "JPY", "Asia/Tokyo")
    );

    public static String validName(String value) {
        if (value == null) throw badRequest("Display name required");
        String name = value.trim();
        if (name.length() < 2 || name.length() > 100
            || name.chars().anyMatch(Character::isISOControl)) {
            throw badRequest("Display name must be 2-100 printable characters");
        }
        return name;
    }

    public static String validRegion(String region) {
        if (!List.of("NA", "EU", "FE").contains(region)) throw badRequest("Unsupported region");
        return region;
    }

    public static String validKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw badRequest("Idempotency-Key must be 8-128 safe characters");
        }
        return key;
    }

    public static void validateMarketplaceRegion(String region, String marketplaceId) {
        Preset preset = MARKETPLACES.get(marketplaceId);
        if (preset == null || !preset.region().equals(region)) {
            throw badRequest("Marketplace is not in the seller account's region");
        }
    }

    @Transactional
    public LocalAccount createAccount(UUID tenant, UUID actor, String displayName,
                                      String region, String requestKey) {
        String name = validName(displayName);
        validRegion(region);
        validKey(requestKey);
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
            INSERT INTO seller_account
              (id, tenant_id, seller_id, region, connection_status, display_name, setup_request_key)
            VALUES (?, ?, ?, ?, 'UNCONFIGURED', ?, ?)
            ON CONFLICT (tenant_id, setup_request_key) WHERE setup_request_key IS NOT NULL DO NOTHING
            """, id, tenant, "LOCAL-" + id, region, name, requestKey);
        LocalAccount result = jdbc.queryForObject("""
            SELECT id, display_name, region, connection_status FROM seller_account
            WHERE tenant_id = ? AND setup_request_key = ?
            """, (rs, n) -> new LocalAccount(
                rs.getObject("id", UUID.class).toString(), rs.getString("display_name"),
                rs.getString("region"), rs.getString("connection_status")),
            tenant, requestKey);
        if (result == null) throw new IllegalStateException("Local account setup not persisted");
        if (!result.displayName().equals(name) || !result.region().equals(region)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key reused with changed fields");
        }
        if (inserted == 1) audit(tenant, actor, "LOCAL_SELLER_ACCOUNT_CREATED",
            "SELLER_ACCOUNT", UUID.fromString(result.id()));
        return result;
    }

    @Transactional
    public LocalMarketplace addMarketplace(UUID tenant, UUID actor, UUID accountId,
                                           String marketplaceId) {
        String region = jdbc.query("""
            SELECT region FROM seller_account
            WHERE tenant_id = ? AND id = ? AND connection_status = 'UNCONFIGURED'
              AND seller_id LIKE 'LOCAL-%'
            """, (rs, n) -> rs.getString("region"), tenant, accountId)
            .stream().findFirst().orElse(null);
        if (region == null) {
            // Unknown and other-tenant IDs are indistinguishable to callers.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local account not found");
        }
        validateMarketplaceRegion(region, marketplaceId);
        Preset preset = MARKETPLACES.get(marketplaceId);
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
            INSERT INTO seller_marketplace
              (id, tenant_id, seller_account_id, marketplace_id, currency_code, timezone)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, seller_account_id, marketplace_id) DO NOTHING
            """, id, tenant, accountId, marketplaceId, preset.currency(), preset.timezone());
        LocalMarketplace result = jdbc.queryForObject("""
            SELECT mp.id, mp.seller_account_id, mp.marketplace_id, mp.currency_code,
                   mp.timezone, sa.connection_status
            FROM seller_marketplace mp
            JOIN seller_account sa
              ON sa.tenant_id = mp.tenant_id AND sa.id = mp.seller_account_id
            WHERE mp.tenant_id = ? AND mp.seller_account_id = ? AND mp.marketplace_id = ?
            """, (rs, n) -> new LocalMarketplace(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("seller_account_id", UUID.class).toString(),
                rs.getString("marketplace_id"), rs.getString("currency_code").trim(),
                rs.getString("timezone"), rs.getString("connection_status")),
            tenant, accountId, marketplaceId);
        if (result == null) throw new IllegalStateException("Marketplace setup not persisted");
        if (inserted == 1) audit(tenant, actor, "LOCAL_MARKETPLACE_ADDED",
            "MARKETPLACE", UUID.fromString(result.id()));
        return result;
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> recentAudit(UUID tenant) {
        return jdbc.query("""
            SELECT id, actor_user_id, action, resource_type, resource_id, created_at
            FROM local_setup_audit WHERE tenant_id = ?
            ORDER BY created_at DESC, id DESC LIMIT 30
            """, LocalSellerSetupService::mapAudit, tenant);
    }

    private void audit(UUID tenant, UUID actor, String action, String resourceType, UUID resourceId) {
        jdbc.update("""
            INSERT INTO local_setup_audit
              (id, tenant_id, actor_user_id, action, resource_type, resource_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), tenant, actor, action, resourceType, resourceId);
    }

    private static AuditEvent mapAudit(ResultSet rs, int row) throws SQLException {
        return new AuditEvent(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("actor_user_id", UUID.class).toString(),
            rs.getString("action"), rs.getString("resource_type"),
            rs.getObject("resource_id", UUID.class).toString(),
            rs.getObject("created_at", OffsetDateTime.class).toInstant().toString());
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
