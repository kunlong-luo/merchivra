package org.merchivra.erp.spapi;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rewraps an existing LWA refresh token under the active AES key version.
 * This NEVER calls Amazon and NEVER renews a Seller OAuth authorization.
 * Transactional row lock serializes with V8 verification and V7 revocation.
 * No decrypt operation or key material is exposed to browsers.
 */
public final class SellerGrantKeyRotationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SellerTokenCipher cipher;

    public SellerGrantKeyRotationService(JdbcTemplate jdbc, TransactionTemplate tx,
                                        SellerTokenCipher cipher) {
        this.jdbc = jdbc; this.tx = tx; this.cipher = cipher;
    }

    public record Result(String status, int keyVersion) {}
    private record Locked(String sellerId, byte[] ciphertext, byte[] nonce, int version) {}

    public Result rotate(UUID tenant, UUID actor, UUID account) {
        if (tenant == null || actor == null || account == null)
            throw new IllegalArgumentException("Authenticated tenant OWNER required");
        return tx.execute(s -> {
            List<Locked> found = jdbc.query("""
                SELECT g.seller_id,g.ciphertext,g.nonce,g.key_version
                  FROM spapi_seller_authorization g
                  JOIN tenant t ON t.id=g.tenant_id
                 WHERE g.tenant_id=? AND g.seller_account_id=?
                   AND g.status <> 'REVOKED' AND t.status='ACTIVE'
                   AND EXISTS(
                     SELECT 1 FROM membership m JOIN app_user u ON u.id=m.user_id
                     WHERE m.tenant_id=g.tenant_id AND m.user_id=?
                       AND m.status='ACTIVE' AND u.status='ACTIVE'
                       AND EXISTS (
                         SELECT 1 FROM role_binding rb
                         WHERE rb.tenant_id=m.tenant_id AND rb.user_id=m.user_id
                           AND rb.role_code='OWNER' AND rb.seller_account_id IS NULL))
                 FOR UPDATE OF g
                """, (rs,n) -> new Locked(rs.getString("seller_id"),
                    rs.getBytes("ciphertext"),rs.getBytes("nonce"),
                    rs.getInt("key_version")),tenant,account,actor);
            if (found.size() != 1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Seller grant not found");
            Locked old=found.getFirst();
            if (old.version() == cipher.activeVersion())
                return new Result("UNCHANGED",cipher.activeVersion());

            // Fail closed and roll back if an old key is absent or its AAD/tag fails.
            String refresh=cipher.decrypt(tenant,account,old.sellerId(),
                new SellerTokenCipher.Sealed(old.ciphertext(),old.nonce(),old.version()));
            var next=cipher.encrypt(tenant,account,old.sellerId(),refresh);
            if (jdbc.update("""
                UPDATE spapi_seller_authorization
                   SET ciphertext=?,nonce=?,key_version=?,updated_at=now()
                 WHERE tenant_id=? AND seller_account_id=? AND key_version=?
                   AND status<>'REVOKED'
                """,next.ciphertext(),next.nonce(),next.version(),
                tenant,account,old.version())!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Seller grant changed during key rotation");
            jdbc.update("""
                INSERT INTO spapi_oauth_audit
                  (id,tenant_id,actor_user_id,seller_account_id,event_type)
                VALUES (?,?,?,?,'KEY_REWRAPPED')
                """, UUID.randomUUID(),tenant,actor,account);
            return new Result("REWRAPPED",next.version());
        });
    }
}
