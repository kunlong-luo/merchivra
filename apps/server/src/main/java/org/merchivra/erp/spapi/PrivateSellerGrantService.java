package org.merchivra.erp.spapi;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Trusted server-side, one-shot import of a Seller Central self-authorized
 * PRIVATE application's refresh token. Never expose a token ingestion HTTP
 * endpoint, never mark the grant VERIFIED and never create API role evidence.
 */
public final class PrivateSellerGrantService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SellerTokenCipher cipher;

    public PrivateSellerGrantService(JdbcTemplate jdbc, TransactionTemplate tx,
                                     SellerTokenCipher cipher) {
        this.jdbc=jdbc; this.tx=tx; this.cipher=cipher;
    }

    private record Account(String region,String status,String sellerId) {}
    private record Existing(String sellerId,String region,String status) {}
    public record Status(String status,String sellerId,String region,String authorizationMethod) {}
    public record Audit(String type,String occurredAt) {}

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Seller account not eligible for self-authorization");
    }

    public void importFromServer(UUID tenant,UUID account,String sellerId,String refreshToken) {
        if(tenant==null || account==null || sellerId==null
           || !sellerId.matches("[A-Za-z0-9_-]{5,128}")
           || sellerId.startsWith("LOCAL-")
           || refreshToken==null || refreshToken.length()<16 || refreshToken.length()>8192
           || !refreshToken.equals(refreshToken.strip())
           || refreshToken.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid private import configuration");
        tx.executeWithoutResult(t->{
            // Grant before seller_account matches the LIVE worker's revocation
            // lock ordering; both updates and the audit share this transaction.
            List<Existing> grants=jdbc.query("""
                SELECT seller_id,region,status FROM spapi_seller_authorization
                WHERE tenant_id=? AND seller_account_id=? FOR UPDATE
                """,(rs,n)->new Existing(rs.getString(1),rs.getString(2),
                    rs.getString(3)),tenant,account);
            List<Account> accounts=jdbc.query("""
                SELECT a.region,a.connection_status,a.seller_id
                FROM seller_account a JOIN tenant t ON t.id=a.tenant_id
                WHERE a.tenant_id=? AND a.id=? AND t.status='ACTIVE'
                  AND a.connection_status IN ('UNCONFIGURED','REVOKED')
                FOR UPDATE OF a
                """,(rs,n)->new Account(rs.getString(1),rs.getString(2),
                    rs.getString(3)),tenant,account);
            if(accounts.size()!=1)throw conflict();
            Account current=accounts.getFirst();
            if(!current.sellerId().startsWith("LOCAL-")
                && !current.sellerId().equals(sellerId))throw conflict();
            if(!List.of("NA","EU","FE").contains(current.region()))throw conflict();
            Integer configured=jdbc.queryForObject("""
                SELECT COUNT(*) FROM seller_marketplace
                WHERE tenant_id=? AND seller_account_id=?
                """,Integer.class,tenant,account);
            if(configured==null || configured<1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Configure at least one Marketplace first");
            var sealed=cipher.encrypt(tenant,account,sellerId,refreshToken);
            int written;
            if("UNCONFIGURED".equals(current.status())
                && current.sellerId().startsWith("LOCAL-") && grants.isEmpty()) {
                written=jdbc.update("""
                    INSERT INTO spapi_seller_authorization
                      (id,tenant_id,seller_account_id,seller_id,region,status,
                       ciphertext,nonce,key_version,authorization_method)
                    VALUES (?,?,?,?,?,'PENDING_VERIFICATION',?,?,?,'PRIVATE_SELF')
                    """,UUID.randomUUID(),tenant,account,sellerId,current.region(),
                    sealed.ciphertext(),sealed.nonce(),sealed.version());
            } else if("REVOKED".equals(current.status()) && grants.size()==1
                && "REVOKED".equals(grants.getFirst().status())
                && sellerId.equals(grants.getFirst().sellerId())
                && current.region().equals(grants.getFirst().region())) {
                written=jdbc.update("""
                    UPDATE spapi_seller_authorization SET
                      status='PENDING_VERIFICATION',authorization_method='PRIVATE_SELF',
                      ciphertext=?,nonce=?,key_version=?,revoked_at=NULL,
                      verification_attempt_id=NULL,verification_started_at=NULL,
                      verification_error_code=NULL,verified_at=NULL,updated_at=now()
                    WHERE tenant_id=? AND seller_account_id=? AND seller_id=?
                      AND region=? AND status='REVOKED'
                    """,sealed.ciphertext(),sealed.nonce(),sealed.version(),
                    tenant,account,sellerId,current.region());
            } else throw conflict();
            if(written!=1)throw conflict();
            int updated=jdbc.update("""
                UPDATE seller_account SET connection_status='AUTHORIZATION_PENDING'
                WHERE tenant_id=? AND id=? AND connection_status=?
                """,tenant,account,current.status());
            if(updated!=1)throw conflict();
            // No human actor is falsely attributed to a server-only import.
            audit(tenant,null,account,"PRIVATE_IMPORTED");
        });
    }

    public Status status(UUID tenant,UUID account) {
        return jdbc.query("""
            SELECT status,seller_id,region,authorization_method
            FROM spapi_seller_authorization
            WHERE tenant_id=? AND seller_account_id=?
            """,(rs,n)->new Status(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4)),tenant,account)
            .stream().findFirst().orElse(new Status("NOT_CONNECTED",null,null,"PRIVATE_SELF"));
    }

    public List<Audit> history(UUID tenant,UUID account) {
        return jdbc.query("""
            SELECT event_type,occurred_at FROM spapi_oauth_audit
            WHERE tenant_id=? AND seller_account_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 30
            """,(rs,n)->new Audit(rs.getString(1),
                rs.getObject(2,java.time.OffsetDateTime.class).toInstant().toString()),
                tenant,account);
    }

    public void revoke(UUID tenant,UUID actor,UUID account) {
        tx.executeWithoutResult(t->{
            int updated=jdbc.update("""
                UPDATE spapi_seller_authorization
                SET status='REVOKED',ciphertext=NULL,nonce=NULL,revoked_at=now(),
                    verification_attempt_id=NULL,verification_started_at=NULL,
                    verification_error_code=NULL,verified_at=NULL,updated_at=now()
                WHERE tenant_id=? AND seller_account_id=? AND status<>'REVOKED'
                  AND authorization_method='PRIVATE_SELF'
                """,tenant,account);
            if(updated!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            int changed=jdbc.update("""
                UPDATE seller_account SET connection_status='REVOKED'
                WHERE tenant_id=? AND id=? AND connection_status IN
                    ('AUTHORIZATION_PENDING','CONNECTED')
                """,tenant,account);
            if(changed!=1)throw conflict();
            jdbc.update("""
                DELETE FROM spapi_marketplace_verification
                WHERE tenant_id=? AND seller_account_id=?
                """,tenant,account);
            jdbc.update("""
                UPDATE spapi_orders_role_evidence SET revoked_at=now()
                WHERE tenant_id=? AND seller_account_id=? AND revoked_at IS NULL
                """,tenant,account);
            jdbc.update("""
                UPDATE spapi_orders_role_review_request SET state='STALE'
                WHERE tenant_id=? AND seller_account_id=? AND state='PENDING'
                """,tenant,account);
            audit(tenant,actor,account,"PRIVATE_REVOKED");
        });
    }

    private void audit(UUID tenant,UUID actor,UUID account,String event) {
        jdbc.update("""
            INSERT INTO spapi_oauth_audit
              (id,tenant_id,actor_user_id,seller_account_id,event_type)
            VALUES (?,?,?,?,?)
            """,UUID.randomUUID(),tenant,actor,account,event);
    }
}
