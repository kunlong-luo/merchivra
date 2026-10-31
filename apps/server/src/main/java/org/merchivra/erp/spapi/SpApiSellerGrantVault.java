package org.merchivra.erp.spapi;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Internal worker-only secret reader. Refuses provisional and revoked grants,
 * disabled accounts, mismatched or stale Marketplace evidence. Never exposed by HTTP.
 * Role grants must be independently verified before populating approvedRoles.
 */
public final class SpApiSellerGrantVault {
    private final JdbcTemplate jdbc;
    private final SellerTokenCipher cipher;

    public SpApiSellerGrantVault(JdbcTemplate jdbc, SellerTokenCipher cipher) {
        this.jdbc=jdbc; this.cipher=cipher;
    }

    public Optional<SpApiSellerScope> loadVerified(UUID tenant, UUID account, String marketplaceId) {
        if (tenant == null || account == null || marketplaceId == null || marketplaceId.isBlank())
            throw new IllegalArgumentException("Server-side Seller scope required");
        record Grant(String seller, String region, byte[] cipherText, byte[] nonce, int version) {}
        List<Grant> result=jdbc.query("""
            SELECT g.seller_id,g.region,g.ciphertext,g.nonce,g.key_version
            FROM spapi_seller_authorization g
            JOIN seller_account sa ON sa.tenant_id=g.tenant_id
              AND sa.id=g.seller_account_id
            JOIN seller_marketplace mp ON mp.tenant_id=g.tenant_id
              AND mp.seller_account_id=g.seller_account_id
            JOIN tenant t ON t.id=g.tenant_id
            WHERE g.tenant_id=? AND g.seller_account_id=? AND mp.marketplace_id=?
              AND g.status='VERIFIED' AND sa.connection_status='CONNECTED'
              AND sa.region=g.region AND mp.marketplace_id IS NOT NULL
              AND t.status='ACTIVE'
              AND EXISTS (
                SELECT 1 FROM spapi_marketplace_verification v
                WHERE v.tenant_id=g.tenant_id
                  AND v.seller_account_id=g.seller_account_id
                  AND v.marketplace_id=mp.marketplace_id
                  AND v.verified_at > now() - interval '24 hours'
              )
            """, (rs,n)->new Grant(rs.getString("seller_id"),rs.getString("region"),
                rs.getBytes("ciphertext"),rs.getBytes("nonce"),rs.getInt("key_version")),
            tenant,account,marketplaceId);
        if (result.isEmpty()) return Optional.empty();
        Grant g=result.getFirst();
        String refresh=cipher.decrypt(tenant,account,g.seller(),
            new SellerTokenCipher.Sealed(g.cipherText(),g.nonce(),g.version()));
        return Optional.of(new SpApiSellerScope(tenant,account,g.seller(),marketplaceId,
            SpApiRegion.valueOf(g.region()),refresh,Set.of()));
    }

    /**
     * Orders read scope requires both verified Seller participation and
     * independently reviewed, non-expired Orders role evidence. No endpoint
     * or Seller Marketplace verification auto-creates this evidence.
     */
    public Optional<SpApiSellerScope> loadOrdersApproved(
        UUID tenant, UUID account, String marketplaceId) {
        Optional<SpApiSellerScope> base=loadVerified(tenant,account,marketplaceId);
        if(base.isEmpty()) return Optional.empty();
        SpApiSellerScope scope=base.get();
        List<String> roles=jdbc.query("""
            SELECT e.role_code FROM spapi_orders_role_evidence e
            JOIN spapi_seller_authorization g
              ON g.tenant_id=e.tenant_id AND g.seller_account_id=e.seller_account_id
            WHERE e.tenant_id=? AND e.seller_account_id=?
              AND e.role_code='Inventory and Order Tracking'
              AND e.evidence_kind='EXTERNAL_APP_ROLE_REVIEW'
              AND e.revoked_at IS NULL AND e.expires_at > now()
              AND e.grant_verified_at = g.verified_at
              AND g.status='VERIFIED' AND g.seller_id=? AND g.region=?
              AND EXISTS (
                SELECT 1 FROM membership m
                JOIN role_binding rb ON rb.tenant_id=m.tenant_id
                  AND rb.user_id=m.user_id
                WHERE m.tenant_id=e.tenant_id AND m.user_id=e.reviewed_by
                  AND m.status='ACTIVE' AND rb.role_code='SECURITY_REVIEWER'
                  AND (rb.seller_account_id IS NULL
                       OR rb.seller_account_id=e.seller_account_id)
              )
            """, (rs,n)->rs.getString(1),tenant,account,
            scope.sellerId(),scope.region().name());
        if(!roles.contains("Inventory and Order Tracking")) return Optional.empty();
        return Optional.of(new SpApiSellerScope(
            scope.tenantId(),scope.sellerAccountId(),scope.sellerId(),
            scope.marketplaceId(),scope.region(),scope.refreshToken(),
            Set.of("Inventory and Order Tracking")));
    }
}
