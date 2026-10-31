package org.merchivra.erp.orderssync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.spapi.SpApiHttpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * V11A: one page per call, durable tenant-scoped cursor, epoch/owner fencing.
 * Network/fixture fetch is OUTSIDE DB transactions; a page and cursor commit
 * in the SAME transaction after checking account status again.
 */
@Service
@ConditionalOnProperty(prefix="merchivra.orders-sync", name="enabled", havingValue="true")
public class OrdersSyncService {
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration TOKEN_MAX_AGE = Duration.ofHours(20);
    private static final Duration OVERLAP = Duration.ofMinutes(5);
    private static final Duration FIRST_WINDOW = Duration.ofHours(24);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OrdersSyncSource source;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public OrdersSyncService(JdbcTemplate jdbc, TransactionTemplate tx, OrdersSyncSource source) {
        this(jdbc, tx, source, Clock.systemUTC());
    }
    // Package-private clock for deterministic tests.
    OrdersSyncService(JdbcTemplate jdbc, TransactionTemplate tx, OrdersSyncSource source, Clock clock) {
        this.jdbc=jdbc; this.tx=tx; this.source=source; this.clock=clock;
    }

    public record Status(String jobId, String state, int committedPages, long leaseEpoch,
                         String windowAfter, String windowBefore, String lastSafeWatermark,
                         String nextAttemptAt, String lastFailureCode) {}
    private record Stored(UUID jobId, String mode, String state, Instant after, Instant before,
                          int page, int generation, String token, Instant tokenIssuedAt,
                          Instant safe, int retries, Instant nextAttempt,
                          long epoch, UUID leaseOwner, Instant leaseExpires) {}
    private record Claim(UUID tenant, UUID marketplace, OrdersSyncSource.Scope scope,
                         Stored stored, UUID owner, long epoch) {}

    private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        OffsetDateTime value=rs.getObject(col,OffsetDateTime.class);
        return value==null ? null : value.toInstant();
    }
    private static String time(Instant instant) { return instant == null ? null : instant.toString(); }
    private static OffsetDateTime dbTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, java.time.ZoneOffset.UTC);
    }

    private Stored row(UUID tenant, UUID mp, boolean lock) {
        List<Stored> rows=jdbc.query("""
            SELECT id,mode,state,window_after,window_before,page_index,generation,
                   next_token,token_issued_at,last_safe_watermark,retry_count,next_attempt_at,
                   lease_epoch,lease_owner,lease_expires_at
            FROM orders_sync_job WHERE tenant_id=? AND seller_marketplace_id=?
            """ + (lock ? " FOR UPDATE" : ""),
            (rs,n)->new Stored(rs.getObject("id",UUID.class),rs.getString("mode"),rs.getString("state"),
                instant(rs,"window_after"),instant(rs,"window_before"),
                rs.getInt("page_index"),rs.getInt("generation"),rs.getString("next_token"),
                instant(rs,"token_issued_at"),instant(rs,"last_safe_watermark"),
                rs.getInt("retry_count"),instant(rs,"next_attempt_at"),
                rs.getLong("lease_epoch"),rs.getObject("lease_owner",UUID.class),
                instant(rs,"lease_expires_at")),tenant,mp);
        return rows.isEmpty()?null:rows.getFirst();
    }

    private String mode() {
        String origin=source.dataOrigin();
        if(!"MOCK".equals(origin) && !"LIVE".equals(origin))
            throw new IllegalStateException("Unsupported Orders source provenance");
        return origin;
    }

    /**
     * In LIVE mode, lock the account, grant, V12 evidence, participation and
     * tenant for the duration of the claiming/committing transaction. A
     * concurrent revocation cannot slip between the final check and commit.
     */
    private OrdersSyncSource.Scope scope(UUID tenant,UUID mp) {
        String origin=mode();
        String sql;
        if("MOCK".equals(origin)) {
            sql="""
                SELECT m.marketplace_id FROM seller_marketplace m
                JOIN seller_account a ON a.tenant_id=m.tenant_id AND a.id=m.seller_account_id
                JOIN tenant t ON t.id=m.tenant_id
                WHERE m.tenant_id=? AND m.id=?
                  AND a.seller_id LIKE 'LOCAL-%'
                  AND a.connection_status='UNCONFIGURED' AND t.status='ACTIVE'
                """;
        } else {
            sql="""
                SELECT m.marketplace_id FROM seller_marketplace m
                JOIN seller_account a ON a.tenant_id=m.tenant_id AND a.id=m.seller_account_id
                JOIN tenant t ON t.id=m.tenant_id
                JOIN spapi_seller_authorization g
                  ON g.tenant_id=m.tenant_id AND g.seller_account_id=m.seller_account_id
                JOIN spapi_orders_role_evidence e
                  ON e.tenant_id=g.tenant_id AND e.seller_account_id=g.seller_account_id
                JOIN spapi_marketplace_verification v
                  ON v.tenant_id=m.tenant_id AND v.seller_account_id=m.seller_account_id
                  AND v.marketplace_id=m.marketplace_id
                WHERE m.tenant_id=? AND m.id=?
                  AND t.status='ACTIVE' AND a.connection_status='CONNECTED'
                  AND a.seller_id NOT LIKE 'LOCAL-%' AND a.region=g.region
                  AND g.status='VERIFIED' AND g.seller_id=a.seller_id
                  AND g.verified_at IS NOT NULL AND g.verified_at=e.grant_verified_at
                  AND v.verified_at > now()-interval '24 hours'
                  AND e.role_code='Inventory and Order Tracking'
                  AND e.evidence_kind='EXTERNAL_APP_ROLE_REVIEW'
                  AND e.revoked_at IS NULL AND e.expires_at>now()
                FOR SHARE OF m,a,t,g,e,v
                """;
        }
        List<String> sites=jdbc.query(sql,(rs,n)->rs.getString(1),tenant,mp);
        if(sites.size()!=1) throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,"Eligible Marketplace not found");
        OrdersSyncSource.Scope value=new OrdersSyncSource.Scope(tenant,mp,sites.getFirst());
        source.requireAuthorized(value);
        return value;
    }

    private void requireMatchingMode(Stored stored) {
        if(stored!=null && !mode().equals(stored.mode()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Job source mode mismatch; never reclassify checkpoint provenance");
    }

    private Status view(Stored stored,String code) {
        return new Status(stored.jobId().toString(),stored.state(),stored.page(),stored.epoch(),
            time(stored.after()),time(stored.before()),time(stored.safe()),
            time(stored.nextAttempt()),code);
    }
    public Status status(UUID tenant,UUID marketplace) {
        scope(tenant,marketplace);
        Stored row=row(tenant,marketplace,false);
        if(row==null) return new Status(null,"NOT_STARTED",0,0,null,null,null,null,null);
        requireMatchingMode(row);
        List<String> codes=jdbc.query(
            "SELECT last_failure_code FROM orders_sync_job WHERE tenant_id=? AND id=?",
            (rs,n)->rs.getString(1),tenant,row.jobId());
        return view(row,codes.isEmpty()?null:codes.getFirst());
    }

    public Status start(UUID tenant,UUID marketplace) {
        if(!source.allowed()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Orders source disabled");
        return tx.execute(transaction -> {
            scope(tenant,marketplace);
            Instant now=clock.instant();
            Instant before=now.minus(Duration.ofMinutes(2));
            Instant after=before.minus(FIRST_WINDOW);
            UUID proposed=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO orders_sync_job
                  (id,tenant_id,seller_marketplace_id,mode,state,window_after,window_before)
                VALUES (?,?,?,?,'READY',?,?)
                ON CONFLICT (tenant_id,seller_marketplace_id) DO NOTHING
                """,proposed,tenant,marketplace,mode(),dbTime(after),dbTime(before));
            Stored current=row(tenant,marketplace,true);
            requireMatchingMode(current);
            if ("SUCCEEDED".equals(current.state())) {
                Instant resume=current.safe()==null?after:current.safe().minus(OVERLAP);
                jdbc.update("""
                    UPDATE orders_sync_job SET state='READY',window_after=?,window_before=?,
                      page_index=0,generation=generation+1,next_token=NULL,
                      token_issued_at=NULL,retry_count=0,next_attempt_at=NULL,
                      lease_owner=NULL,lease_expires_at=NULL,last_failure_code=NULL,updated_at=now()
                    WHERE tenant_id=? AND id=?
                    """,dbTime(resume),dbTime(before),tenant,current.jobId());
            } else if ("PAUSED".equals(current.state())
                || ("RUNNING".equals(current.state()) && current.leaseExpires()!=null
                    && current.leaseExpires().isAfter(now))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Orders sync active or paused");
            }
            return status(tenant,marketplace);
        });
    }

    public Status step(UUID tenant, UUID marketplace) {
        if(!source.allowed()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Orders source disabled");
        Claim claim=tx.execute(transaction -> {
            OrdersSyncSource.Scope scope=scope(tenant,marketplace);
            Stored stored=row(tenant,marketplace,true);
            if(stored==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Start a sync job first");
            requireMatchingMode(stored);
            Instant now=clock.instant();
            if("SUCCEEDED".equals(stored.state()) || "PAUSED".equals(stored.state()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Sync is not ready");
            if(stored.leaseOwner()!=null && stored.leaseExpires()!=null
                && stored.leaseExpires().isAfter(now))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Another worker owns the lease");
            if(stored.nextAttempt()!=null && stored.nextAttempt().isAfter(now))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Retry not yet due");
            if(stored.token()!=null && (stored.tokenIssuedAt()==null ||
                stored.tokenIssuedAt().plus(TOKEN_MAX_AGE).isBefore(now))) {
                // Same immutable time window; replay via upsert, not unsafe watermark advance.
                jdbc.update("""
                    UPDATE orders_sync_job SET page_index=0,generation=generation+1,
                      next_token=NULL,token_issued_at=NULL,retry_count=0
                    WHERE tenant_id=? AND id=?
                    """,tenant,stored.jobId());
                stored=row(tenant,marketplace,true);
            }
            UUID owner=UUID.randomUUID();
            long epoch=stored.epoch()+1;
            jdbc.update("""
                UPDATE orders_sync_job SET state='RUNNING',lease_owner=?,
                    lease_expires_at=?,lease_epoch=?,updated_at=now()
                WHERE tenant_id=? AND id=?
                """,owner,dbTime(now.plus(LEASE)),epoch,tenant,stored.jobId());
            return new Claim(tenant,marketplace,scope,stored,owner,epoch);
        });

        OrdersSyncSource.Page page;
        try {
            page=source.fetch(claim.scope(),claim.stored().after(),claim.stored().before(),
                claim.stored().token());
            validate(page,claim.scope());
        } catch(Exception upstream) {
            fail(claim,upstream);
            return status(tenant,marketplace);
        }

        try {
          return tx.execute(transaction -> {
            // A revoked/changed seller or tenant cannot commit after fetching.
            scope(tenant,marketplace);
            source.requireAuthorized(claim.scope());
            Stored current=row(tenant,marketplace,true);
            requireMatchingMode(current);
            if(current==null || !"RUNNING".equals(current.state())
               || !claim.owner().equals(current.leaseOwner()) || current.epoch()!=claim.epoch()
               || current.leaseExpires()==null || !current.leaseExpires().isAfter(clock.instant())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Stale lease fencing epoch");
            }
            for(OrdersSyncSource.OrderFact order:page.orders()) {
                jdbc.update("""
                    INSERT INTO sales_order
                      (id,tenant_id,seller_marketplace_id,external_order_id,
                       fulfillment_status_raw,source_updated_at,data_origin)
                    VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT (tenant_id,seller_marketplace_id,external_order_id)
                    DO UPDATE SET fulfillment_status_raw=EXCLUDED.fulfillment_status_raw,
                        source_updated_at=EXCLUDED.source_updated_at
                    WHERE sales_order.data_origin=EXCLUDED.data_origin
                      AND (sales_order.source_updated_at IS NULL
                           OR sales_order.source_updated_at<=EXCLUDED.source_updated_at)
                    """,UUID.randomUUID(),tenant,marketplace,order.orderId(),
                    order.fulfillment(),dbTime(order.updatedAt()),mode());
            }
            jdbc.update("""
                INSERT INTO orders_sync_page_commit
                  (id,tenant_id,job_id,generation,page_index,record_count)
                VALUES (?,?,?,?,?,?)
                """,UUID.randomUUID(),tenant,current.jobId(),current.generation(),
                current.page(),page.orders().size());
            boolean complete=page.nextToken()==null;
            jdbc.update("""
                UPDATE orders_sync_job SET page_index=page_index+1,
                    state=?,next_token=?,token_issued_at=?,
                    last_safe_watermark=CASE WHEN ? THEN window_before ELSE last_safe_watermark END,
                    retry_count=0,next_attempt_at=?,
                    lease_owner=NULL,lease_expires_at=NULL,last_failure_code=NULL,
                    updated_at=now()
                WHERE tenant_id=? AND id=? AND lease_epoch=? AND lease_owner=?
                """,complete?"SUCCEEDED":"READY",page.nextToken(),
                dbTime(complete?null:(current.tokenIssuedAt()==null?clock.instant():current.tokenIssuedAt())),
                complete,
                dbTime(complete || "MOCK".equals(source.dataOrigin())?null:
                    clock.instant().plus(Duration.ofSeconds(180))),
                tenant,current.jobId(),claim.epoch(),claim.owner());
            return status(tenant,marketplace);
          });
        } catch (SecurityException | ResponseStatusException rejected) {
            // Transaction rolled back, so neither page nor watermark advanced.
            // Fence the same claim into PAUSED without writing upstream data.
            fail(claim,new SecurityException("Authorization check rejected"));
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Orders authorization revoked before commit");
        }
    }

    private static void validate(OrdersSyncSource.Page page,OrdersSyncSource.Scope scope) {
        if(page==null || page.orders()==null || page.orders().size()>100
            || (page.nextToken()!=null && (page.nextToken().isBlank()
                || page.nextToken().length()>4096))) throw new IllegalArgumentException("Invalid orders page");
        for(OrdersSyncSource.OrderFact o:page.orders()) {
            if(o==null || o.orderId()==null || o.orderId().isBlank()
               || o.orderId().length()>160 || !scope.marketplaceCode().equals(o.marketplaceCode())
               || o.updatedAt()==null || o.fulfillment()==null
               || o.fulfillment().length()>100) throw new IllegalArgumentException("Unsafe order fact");
        }
    }

    private void fail(Claim claim,Exception error) {
        int http=error instanceof SpApiHttpException e ? e.status() : 0;
        // Exclude HTTP body, cursor, refresh/access token and raw exception message.
        boolean transientFailure=http==429 || http==500 || http==503 || http==502 || http==504;
        boolean expiredCursor=http==400 && claim.stored().token()!=null;
        String code=expiredCursor?"CURSOR_EXPIRED":transientFailure?"UPSTREAM_RETRY":
            http==401 || http==403 || error instanceof SecurityException
              ?"AUTHORIZATION_REJECTED":"SOURCE_ERROR";
        tx.executeWithoutResult(txStatus -> {
            Stored current=row(claim.tenant(),claim.marketplace(),true);
            if(current==null || current.epoch()!=claim.epoch()
               || !claim.owner().equals(current.leaseOwner())) return;
            int attempt=current.retries()+1;
            boolean retry=(transientFailure || expiredCursor) && attempt<=8;
            long delay=Math.min(3600,180L*(1L<<Math.min(attempt-1,4)));
            jdbc.update("""
                UPDATE orders_sync_job SET state=?,retry_count=?,
                    next_attempt_at=?,last_failure_code=?,
                    lease_owner=NULL,lease_expires_at=NULL,
                    next_token=CASE WHEN ? THEN NULL ELSE next_token END,
                    token_issued_at=CASE WHEN ? THEN NULL ELSE token_issued_at END,
                    page_index=CASE WHEN ? THEN 0 ELSE page_index END,
                    generation=generation+CASE WHEN ? THEN 1 ELSE 0 END,
                    updated_at=now()
                WHERE tenant_id=? AND id=? AND lease_epoch=? AND lease_owner=?
                """,retry?"WAITING_RETRY":"PAUSED",attempt,
                dbTime(retry?clock.instant().plusSeconds(delay):null),code,
                expiredCursor,expiredCursor,expiredCursor,expiredCursor,
                claim.tenant(),current.jobId(),claim.epoch(),claim.owner());
        });
    }

    public List<UUID[]> due(int max) {
        if(max<1 || max>20)throw new IllegalArgumentException("batch must be 1..20");
        return jdbc.query("""
            SELECT tenant_id,seller_marketplace_id FROM orders_sync_job
            WHERE state IN ('READY','WAITING_RETRY')
              AND (next_attempt_at IS NULL OR next_attempt_at<=now())
              AND (lease_expires_at IS NULL OR lease_expires_at<=now())
              AND mode=?
            ORDER BY updated_at LIMIT ?
            """,(rs,n)->new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)},
            mode(),max);
    }
}
