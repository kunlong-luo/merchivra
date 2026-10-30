package org.merchivra.erp.spapi;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Internal worker-only secret reader. Refuses provisional and revoked grants,
 * disabled accounts and mismatched Marketplace scope. Never exposed by HTTP.
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
}
