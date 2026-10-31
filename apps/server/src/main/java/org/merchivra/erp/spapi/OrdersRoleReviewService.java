package org.merchivra.erp.spapi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicit two-person manual review of external Amazon application Orders
 * role evidence. The software cannot independently attest Amazon developer
 * role approval; a separate SECURITY_REVIEWER must verify external records.
 *
 * This is disabled by default and never stores raw evidence, tokens, URLs,
 * screenshots, or buyer PII. It never makes an Amazon API call.
 */
@Service
@ConditionalOnProperty(prefix="merchivra.spapi.orders-role-review",
    name="enabled",havingValue="true")
public class OrdersRoleReviewService {
    static final String ROLE="Inventory and Order Tracking";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public OrdersRoleReviewService(JdbcTemplate jdbc,TransactionTemplate tx) {
        this.jdbc=jdbc;this.tx=tx;
    }

    private record Grant(Instant verifiedAt) {}
    private record Request(UUID id,UUID account,UUID requester,String state,
                           String caseRef,String digest,Instant grantVerifiedAt) {}
    public record ReviewStatus(UUID id,String state,String externalCaseRef,
                               String evidenceSha256,UUID requestedBy,
                               String createdAt,String reviewedAt) {}

    private static Instant instant(ResultSet rs,String key) throws SQLException {
        OffsetDateTime value=rs.getObject(key,OffsetDateTime.class);
        return value==null?null:value.toInstant();
    }
    private static String time(Instant value) { return value==null?null:value.toString(); }

    private Grant verifiedGrant(UUID tenant,UUID account) {
        List<Grant> grants=jdbc.query("""
            SELECT g.verified_at FROM spapi_seller_authorization g
            JOIN seller_account a ON a.tenant_id=g.tenant_id AND a.id=g.seller_account_id
            JOIN tenant t ON t.id=g.tenant_id
            WHERE g.tenant_id=? AND g.seller_account_id=?
              AND g.status='VERIFIED' AND g.verified_at IS NOT NULL
              AND a.connection_status='CONNECTED' AND a.region=g.region
              AND a.seller_id=g.seller_id AND t.status='ACTIVE'
              AND EXISTS (SELECT 1 FROM spapi_marketplace_verification v
                WHERE v.tenant_id=g.tenant_id AND v.seller_account_id=g.seller_account_id
                  AND v.verified_at>now()-interval '24 hours')
            FOR UPDATE OF g,a,t
            """,(rs,n)->new Grant(instant(rs,"verified_at")),tenant,account);
        if(grants.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Fresh verified Seller authorization required");
        return grants.getFirst();
    }

    private Request pending(UUID tenant,UUID reviewId) {
        List<Request> found=jdbc.query("""
            SELECT id,seller_account_id,requested_by,state,external_case_ref,
                   evidence_sha256,grant_verified_at
            FROM spapi_orders_role_review_request
            WHERE tenant_id=? AND id=? FOR UPDATE
            """,(rs,n)->new Request(
                rs.getObject("id",UUID.class),rs.getObject("seller_account_id",UUID.class),
                rs.getObject("requested_by",UUID.class),rs.getString("state"),
                rs.getString("external_case_ref"),rs.getString("evidence_sha256"),
                instant(rs,"grant_verified_at")),tenant,reviewId);
        if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return found.getFirst();
    }

    private void requireReviewer(UUID tenant,UUID actor,UUID account) {
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM membership m
            JOIN role_binding rb ON rb.tenant_id=m.tenant_id AND rb.user_id=m.user_id
            WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE'
              AND rb.role_code='SECURITY_REVIEWER'
              AND EXISTS (SELECT 1 FROM role_binding owner
                WHERE owner.tenant_id=m.tenant_id AND owner.user_id=m.user_id
                  AND owner.role_code='OWNER' AND owner.seller_account_id IS NULL)
              AND (rb.seller_account_id IS NULL OR rb.seller_account_id=?)
            """,Integer.class,tenant,actor,account);
        if(count==null || count<1) throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,"Independent security reviewer required");
    }

    private void audit(UUID tenant,UUID reviewId,UUID actor,String action) {
        jdbc.update("""
            INSERT INTO spapi_orders_role_review_audit
              (id,tenant_id,review_id,actor_id,event_code)
            VALUES (?,?,?,?,?)
            """,UUID.randomUUID(),tenant,reviewId,actor,action);
    }

    /** OWNER may request review, not approve their own request. */
    public UUID request(UUID tenant,UUID actor,UUID account,
                        String sha256,String externalCaseReference) {
        if(sha256==null || !sha256.matches("[0-9a-f]{64}")
           || externalCaseReference==null
           || !externalCaseReference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{7,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Digest and external non-secret case reference required");
        return tx.execute(transaction-> {
            Grant grant=verifiedGrant(tenant,account);
            Long pending=jdbc.queryForObject("""
                SELECT count(*) FROM spapi_orders_role_review_request
                WHERE tenant_id=? AND seller_account_id=? AND state='PENDING'
                """,Long.class,tenant,account);
            if(pending!=null && pending>0)throw new ResponseStatusException(
                HttpStatus.CONFLICT,"Review already pending");
            UUID id=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO spapi_orders_role_review_request
                  (id,tenant_id,seller_account_id,requested_by,role_code,
                   evidence_sha256,external_case_ref,grant_verified_at,state)
                VALUES (?,?,?,?,?,?,?,?,'PENDING')
                """,id,tenant,account,actor,ROLE,sha256,externalCaseReference,
                OffsetDateTime.ofInstant(grant.verifiedAt(),java.time.ZoneOffset.UTC));
            audit(tenant,id,actor,"REQUESTED");
            return id;
        });
    }

    /**
     * SECURITY_REVIEWER (a different active OWNER/user) attests an independent
     * external application role check, never Seller participation alone.
     */
    public String decide(UUID tenant,UUID actor,UUID reviewId,boolean approve) {
        return tx.execute(transaction-> {
            Request review=pending(tenant,reviewId);
            requireReviewer(tenant,actor,review.account());
            if(actor.equals(review.requester()))throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,"Cannot approve own request");
            if(!"PENDING".equals(review.state()))throw new ResponseStatusException(
                HttpStatus.CONFLICT,"Review is already closed");
            Grant grant;
            try { grant=verifiedGrant(tenant,review.account()); }
            catch(ResponseStatusException stale) {
                jdbc.update("""
                    UPDATE spapi_orders_role_review_request
                      SET state='STALE'
                    WHERE tenant_id=? AND id=? AND state='PENDING'
                    """,tenant,reviewId);
                audit(tenant,reviewId,actor,"STALE");
                return "STALE";
            }
            if(!grant.verifiedAt().equals(review.grantVerifiedAt())) {
                jdbc.update("""
                    UPDATE spapi_orders_role_review_request
                      SET state='STALE'
                    WHERE tenant_id=? AND id=? AND state='PENDING'
                    """,tenant,reviewId);
                audit(tenant,reviewId,actor,"STALE");
                return "STALE";
            }
            String status=approve?"APPROVED":"REJECTED";
            jdbc.update("""
                UPDATE spapi_orders_role_review_request
                  SET state=?,reviewed_by=?,reviewed_at=now()
                WHERE tenant_id=? AND id=? AND state='PENDING'
                """,status,actor,tenant,reviewId);
            if(approve) {
                jdbc.update("""
                    INSERT INTO spapi_orders_role_evidence
                      (tenant_id,seller_account_id,role_code,evidence_kind,
                       evidence_sha256,grant_verified_at,reviewed_by,
                       reviewed_at,expires_at,revoked_at)
                    VALUES (?,?,'Inventory and Order Tracking','EXTERNAL_APP_ROLE_REVIEW',
                      ?,?,?,now(),now()+interval '7 days',NULL)
                    ON CONFLICT (tenant_id,seller_account_id,role_code)
                    DO UPDATE SET evidence_sha256=EXCLUDED.evidence_sha256,
                      grant_verified_at=EXCLUDED.grant_verified_at,
                      reviewed_by=EXCLUDED.reviewed_by,
                      reviewed_at=EXCLUDED.reviewed_at,
                      expires_at=EXCLUDED.expires_at,revoked_at=NULL
                    """,tenant,review.account(),review.digest(),
                    OffsetDateTime.ofInstant(review.grantVerifiedAt(),java.time.ZoneOffset.UTC),actor);
            }
            audit(tenant,reviewId,actor,status);
            return status;
        });
    }

    /** OWNER emergency revocation; cannot issue, restore or approve a role. */
    public void revoke(UUID tenant,UUID actor,UUID account) {
        tx.executeWithoutResult(transaction->{
            // Emergency revoke must remain possible even if the Seller grant
            // has become stale or revoked. Lock grant to fence concurrent approvals.
            List<UUID> grant=jdbc.query("""
                SELECT seller_account_id FROM spapi_seller_authorization
                WHERE tenant_id=? AND seller_account_id=? FOR UPDATE
                """,(rs,n)->rs.getObject(1,UUID.class),tenant,account);
            if(grant.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            List<UUID> current=jdbc.query("""
                SELECT id FROM spapi_orders_role_review_request
                WHERE tenant_id=? AND seller_account_id=? AND state='APPROVED'
                ORDER BY reviewed_at DESC LIMIT 1 FOR UPDATE
                """,(rs,n)->rs.getObject("id",UUID.class),tenant,account);
            if(current.isEmpty())throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,"Reviewed role evidence not found");
            int changed=jdbc.update("""
                UPDATE spapi_orders_role_evidence
                  SET revoked_at=now()
                WHERE tenant_id=? AND seller_account_id=?
                  AND role_code='Inventory and Order Tracking' AND revoked_at IS NULL
                """,tenant,account);
            if(changed!=1)throw new ResponseStatusException(
                HttpStatus.CONFLICT,"Role already revoked");
            audit(tenant,current.getFirst(),actor,"REVOKED");
        });
    }

    public List<ReviewStatus> history(UUID tenant,UUID account) {
        return jdbc.query("""
            SELECT id,state,external_case_ref,evidence_sha256,requested_by,created_at,reviewed_at
            FROM spapi_orders_role_review_request
            WHERE tenant_id=? AND seller_account_id=?
            ORDER BY created_at DESC LIMIT 50
            """,(rs,n)->new ReviewStatus(
                rs.getObject("id",UUID.class),rs.getString("state"),
                rs.getString("external_case_ref"),rs.getString("evidence_sha256"),
                rs.getObject("requested_by",UUID.class),time(instant(rs,"created_at")),
                time(instant(rs,"reviewed_at"))),tenant,account);
    }
}
