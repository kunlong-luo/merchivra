package org.merchivra.erp.spapi;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-initiated Sellers v1 verification. No real Amazon call occurs until
 * all three explicit egress/OAuth/verification flags are enabled in production.
 * OAuth callback is the source of Seller ID; Sellers v1 proves marketplace
 * participation for its grant but does not independently return a Seller ID.
 */
public final class SellerConnectionVerificationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SellerTokenCipher cipher;
    private final SellerMarketplaceGateway gateway;
    private final Clock clock;

    public SellerConnectionVerificationService(JdbcTemplate jdbc, TransactionTemplate tx,
        SellerTokenCipher cipher, SellerMarketplaceGateway gateway, Clock clock) {
        this.jdbc=jdbc; this.tx=tx; this.cipher=cipher;
        this.gateway=gateway; this.clock=clock;
    }
    private record Grant(String sellerId, SpApiRegion region, byte[] ciphertext,
                         byte[] nonce, int keyVersion, UUID claim, List<String> marketplaces) {}
    public record Result(String status, int verifiedMarketplaceCount) {}

    public Result verify(UUID tenant, UUID actor, UUID account) {
        if (tenant == null || actor == null || account == null)
            throw new IllegalArgumentException("Authenticated tenant OWNER scope required");
        Grant grant=tx.execute(status -> {
            // Lock the grant while claiming a verification attempt. Revocation
            // and another verification cannot race this state transition.
            record Stored(String seller, String region, byte[] ciphertext,
                          byte[] nonce, int version) {}
            List<Stored> found=jdbc.query("""
                SELECT g.seller_id,g.region,g.ciphertext,g.nonce,g.key_version
                FROM spapi_seller_authorization g
                JOIN seller_account sa ON sa.tenant_id=g.tenant_id
                    AND sa.id=g.seller_account_id
                JOIN tenant t ON t.id=g.tenant_id
                WHERE g.tenant_id=? AND g.seller_account_id=? AND t.status='ACTIVE'
                  AND g.status IN ('PENDING_VERIFICATION','VERIFICATION_FAILED','VERIFIED','VERIFYING')
                  AND (g.status<>'VERIFYING'
                      OR g.verification_started_at < now() - interval '5 minutes')
                  AND (g.verification_started_at IS NULL
                      OR g.verification_started_at < now() - interval '1 minute')
                  AND sa.connection_status IN ('AUTHORIZATION_PENDING','CONNECTED')
                  AND sa.region=g.region
                  AND (sa.seller_id LIKE 'LOCAL-%' OR sa.seller_id=g.seller_id)
                  AND EXISTS(SELECT 1 FROM membership m JOIN app_user u ON u.id=m.user_id
                      WHERE m.tenant_id=g.tenant_id AND m.user_id=? AND m.status='ACTIVE'
                      AND u.status='ACTIVE' AND EXISTS(
                        SELECT 1 FROM role_binding rb WHERE rb.tenant_id=m.tenant_id
                          AND rb.user_id=m.user_id AND rb.role_code='OWNER'
                          AND rb.seller_account_id IS NULL))
                FOR UPDATE OF g
                """, (rs,n)->new Stored(rs.getString("seller_id"),
                    rs.getString("region"),rs.getBytes("ciphertext"),
                    rs.getBytes("nonce"),rs.getInt("key_version")),tenant,account,actor);
            if (found.size()!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Grant not eligible for verification");
            List<String> markets=jdbc.query("""
                SELECT marketplace_id FROM seller_marketplace
                WHERE tenant_id=? AND seller_account_id=? ORDER BY marketplace_id
                """,(rs,n)->rs.getString(1),tenant,account);
            if (markets.isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Configure a Marketplace first");
            UUID claim=UUID.randomUUID();
            int updated=jdbc.update("""
                UPDATE spapi_seller_authorization
                SET status='VERIFYING',verification_attempt_id=?,
                    verification_started_at=now(),verification_error_code=NULL,updated_at=now()
                WHERE tenant_id=? AND seller_account_id=? AND status<>'REVOKED'
                """,claim,tenant,account);
            if (updated!=1) throw new ResponseStatusException(HttpStatus.CONFLICT);
            audit(tenant,actor,account,"VERIFICATION_STARTED");
            var row=found.getFirst();
            return new Grant(row.seller(),SpApiRegion.valueOf(row.region()),
                row.ciphertext(),row.nonce(),row.version(),claim,markets);
        });

        List<SellerMarketplaceGateway.Participation> evidence;
        try {
            String refresh=cipher.decrypt(tenant,account,grant.sellerId(),
                new SellerTokenCipher.Sealed(grant.ciphertext(),grant.nonce(),grant.keyVersion()));
            evidence=gateway.fetch(grant.region(),refresh);
        } catch(Exception e) {
            failed(tenant,actor,account,grant.claim(),"UPSTREAM_OR_VAULT_ERROR");
            // Never return exception text, HTTP body, headers, or credentials.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Seller verification unavailable");
        }
        String validationError=validate(grant.marketplaces(),evidence);
        if (validationError!=null) {
            failed(tenant,actor,account,grant.claim(),validationError);
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Seller marketplace verification failed");
        }

        int approved=tx.execute(status -> {
            int updated=jdbc.update("""
                UPDATE spapi_seller_authorization SET status='VERIFIED',
                   verification_attempt_id=NULL,verification_error_code=NULL,
                   verified_at=now(),updated_at=now()
                WHERE tenant_id=? AND seller_account_id=?
                  AND status='VERIFYING' AND verification_attempt_id=?
                """,tenant,account,grant.claim());
            if (updated!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Grant changed during verification");
            int connected=jdbc.update("""
                UPDATE seller_account
                SET seller_id=?,connection_status='CONNECTED'
                WHERE tenant_id=? AND id=? AND region=?
                  AND connection_status IN ('AUTHORIZATION_PENDING','CONNECTED')
                  AND (seller_id LIKE 'LOCAL-%' OR seller_id=?)
                """,grant.sellerId(),tenant,account,grant.region().name(),grant.sellerId());
            if (connected!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Seller account state changed");
            // Evidence only for configured marketplaces, scoped to this Seller.
            // Reverification removes stale evidence before replacing it.
            jdbc.update("""
                DELETE FROM spapi_marketplace_verification
                WHERE tenant_id=? AND seller_account_id=?
                """,tenant,account);
            for(String marketplace : grant.marketplaces()) jdbc.update("""
                INSERT INTO spapi_marketplace_verification
                (tenant_id,seller_account_id,marketplace_id,verified_at)
                VALUES (?,?,?,now())
                """,tenant,account,marketplace);
            audit(tenant,actor,account,"VERIFIED");
            return grant.marketplaces().size();
        });
        return new Result("VERIFIED",approved);
    }

    /** Strictly require all configured sites, not just one matching participation. */
    static String validate(List<String> configured, List<SellerMarketplaceGateway.Participation> evidence) {
        if (configured==null || configured.isEmpty() || evidence==null || evidence.isEmpty())
            return "EMPTY_MARKETPLACE_RESPONSE";
        Set<String> seen=new HashSet<>();
        for(var value:evidence) {
            if (value==null || value.marketplaceId()==null || value.marketplaceId().isBlank()
                || !seen.add(value.marketplaceId())) return "INVALID_MARKETPLACE_RESPONSE";
        }
        for(String id:configured) {
            var match=evidence.stream().filter(x->id.equals(x.marketplaceId()))
                .findFirst().orElse(null);
            if (match==null) return "MARKETPLACE_MISSING";
            if (!match.participating()) return "NOT_PARTICIPATING";
            if (match.suspended()) return "LISTINGS_SUSPENDED";
        }
        return null;
    }

    private void failed(UUID tenant, UUID actor, UUID account, UUID claim, String reason) {
        tx.executeWithoutResult(s -> {
            int changed=jdbc.update("""
                UPDATE spapi_seller_authorization
                SET status='VERIFICATION_FAILED',verification_attempt_id=NULL,
                    verification_error_code=?,updated_at=now()
                WHERE tenant_id=? AND seller_account_id=?
                  AND status='VERIFYING' AND verification_attempt_id=?
                """,reason,tenant,account,claim);
            if(changed==1) {
                jdbc.update("""
                    UPDATE seller_account SET connection_status='AUTHORIZATION_PENDING'
                    WHERE tenant_id=? AND id=? AND connection_status='CONNECTED'
                    """,tenant,account);
                jdbc.update("""
                    DELETE FROM spapi_marketplace_verification
                    WHERE tenant_id=? AND seller_account_id=?
                    """,tenant,account);
                audit(tenant,actor,account,"VERIFICATION_FAILED");
            }
        });
    }

    private void audit(UUID tenant, UUID actor, UUID account, String event) {
        jdbc.update("""
            INSERT INTO spapi_oauth_audit
             (id,tenant_id,actor_user_id,seller_account_id,event_type)
            VALUES (?,?,?,?,?)
            """,UUID.randomUUID(),tenant,actor,account,event);
    }
}
