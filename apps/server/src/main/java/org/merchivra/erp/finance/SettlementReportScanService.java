package org.merchivra.erp.finance;

import java.io.IOException;
import java.time.*;
import java.util.*;
import org.merchivra.erp.spapi.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.spapi.ApiException;

/** Durable tenant-scoped read-only Reports page job with transaction-fenced imports. */
@Service
@ConditionalOnProperty(prefix="merchivra.spapi.reports",name="enabled",havingValue="true")
public class SettlementReportScanService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SpApiSellerGrantVault vault;
    private final SpApiSettlementReportsWorker worker;
    private final Clock clock;
    @Autowired
    public SettlementReportScanService(JdbcTemplate jdbc,TransactionTemplate tx,
        SpApiSellerGrantVault vault,SpApiSettlementReportsWorker worker,
        @Value("${merchivra.demo.enabled:false}") boolean demo,
        @Value("${merchivra.spapi.enabled:false}") boolean enabled,
        @Value("${merchivra.spapi.sandbox:true}") boolean sandbox,
        @Value("${merchivra.spapi.allow-writes:false}") boolean writes,
        @Value("${merchivra.spapi.rate-limit.enabled:false}") boolean quota,
        @Value("${merchivra.spapi.verification.enabled:false}") boolean verified,
        @Value("${merchivra.spapi.oauth.enabled:false}") boolean oauth,
        @Value("${merchivra.spapi.private.enabled:false}") boolean priv) {
        this(jdbc,tx,vault,worker,Clock.systemUTC());
        if(demo||!enabled||sandbox||writes||!quota||!verified||(!oauth&&!priv))
            throw new IllegalStateException("Reports require verified LIVE read-only Seller and Redis quota");
    }
    SettlementReportScanService(JdbcTemplate jdbc,TransactionTemplate tx,
        SpApiSellerGrantVault vault,SpApiSettlementReportsWorker worker,Clock clock){
        this.jdbc=jdbc;this.tx=tx;this.vault=vault;this.worker=worker;this.clock=clock;
    }

    public record Status(String jobId,String state,int generation,int completedPages,
        int importedReports,int retryCount,int rowOffset,String nextAttemptAt,
        String lastFailureCode,String updatedAt) {}
    private record Market(UUID account,String site) {}
    private record Job(UUID id,String state,Instant from,Instant until,String token,
        Instant tokenAt,int offset,int page,int generation,int imported,int attempts,
        long epoch,UUID owner,Instant lease,Instant retry,Instant updated) {}
    private record Claim(UUID tenant,UUID marketplace,Market market,Job job,
        UUID owner,long epoch,SpApiSellerScope scope) {}

    private static OffsetDateTime db(Instant t) {
        return t==null?null:t.atOffset(ZoneOffset.UTC);
    }
    private static Instant time(java.sql.ResultSet r,String col)throws java.sql.SQLException {
        var value=r.getObject(col,OffsetDateTime.class);
        return value==null?null:value.toInstant();
    }
    private Market market(UUID tenant,UUID mp) {
        if(tenant==null||mp==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var rows=jdbc.query("""
            SELECT m.seller_account_id,m.marketplace_id FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            JOIN seller_account a ON a.tenant_id=m.tenant_id AND a.id=m.seller_account_id
            WHERE m.tenant_id=? AND m.id=?
            """,(r,n)->new Market(r.getObject(1,UUID.class),r.getString(2)),tenant,mp);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Marketplace unavailable");
        return rows.getFirst();
    }
    private SpApiSellerScope approved(UUID tenant,Market market) {
        return vault.loadFinancesApproved(tenant,market.account(),market.site())
            .orElseThrow(()->new SecurityException("Finance Seller permission absent"));
    }
    private Job job(UUID tenant,UUID mp,boolean lock) {
        return jdbc.query("""
            SELECT id,state,window_after,window_before,next_token,token_issued_at,
                   row_offset,page_index,generation,imported_count,retry_count,
                   lease_epoch,lease_owner,lease_expires_at,retry_at,updated_at
            FROM spapi_settlement_scan_job
            WHERE tenant_id=? AND seller_marketplace_id=?
            """+(lock?" FOR UPDATE":""),
            (r,n)->new Job(r.getObject("id",UUID.class),r.getString("state"),
                time(r,"window_after"),time(r,"window_before"),
                r.getString("next_token"),time(r,"token_issued_at"),
                r.getInt("row_offset"),r.getInt("page_index"),
                r.getInt("generation"),r.getInt("imported_count"),
                r.getInt("retry_count"),r.getLong("lease_epoch"),
                r.getObject("lease_owner",UUID.class),time(r,"lease_expires_at"),
                time(r,"retry_at"),time(r,"updated_at")),tenant,mp)
            .stream().findFirst().orElse(null);
    }
    private Status view(UUID tenant,Job j) {
        if(j==null)return new Status(null,"NOT_STARTED",0,0,0,0,0,null,null,null);
        String failure=jdbc.queryForObject("""
            SELECT last_failure_code FROM spapi_settlement_scan_job
            WHERE tenant_id=? AND id=?
            """,String.class,tenant,j.id());
        return new Status(j.id().toString(),j.state(),j.generation(),
            j.page(),j.imported(),j.attempts(),j.offset(),
            j.retry()==null?null:j.retry().toString(),failure,j.updated().toString());
    }
    public Status status(UUID tenant,UUID mp) {
        market(tenant,mp);
        return view(tenant,job(tenant,mp,false));
    }

    /** Explicit Owner start; fixed bounded window, no browser-supplied cursor or URL. */
    public Status start(UUID tenant,UUID mp,UUID actor) {
        if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return tx.execute(t->{
            Market m=market(tenant,mp);
            approved(tenant,m);
            Instant until=clock.instant().minus(Duration.ofHours(48));
            Instant from=until.minus(Duration.ofDays(14));
            UUID id=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO spapi_settlement_scan_job
                  (id,tenant_id,seller_marketplace_id,seller_account_id,created_by,
                   window_after,window_before)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,seller_marketplace_id) DO NOTHING
                """,id,tenant,mp,m.account(),actor,db(from),db(until));
            Job previous=job(tenant,mp,true);
            if(previous==null)throw new IllegalStateException("Report job missing");
            if("RUNNING".equals(previous.state())
                && previous.lease()!=null && previous.lease().isAfter(clock.instant()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Report job is active");
            if(!previous.id().equals(id))jdbc.update("""
                UPDATE spapi_settlement_scan_job
                SET state='READY',window_after=?,window_before=?,
                    next_token=NULL,token_issued_at=NULL,row_offset=0,
                    page_index=0,generation=generation+1,
                    lease_owner=NULL,lease_expires_at=NULL,retry_at=NULL,
                    retry_count=0,last_failure_code=NULL,imported_count=0,
                    completed_at=NULL,updated_at=now()
                WHERE tenant_id=? AND id=?
                """,db(from),db(until),tenant,previous.id());
            return view(tenant,job(tenant,mp,false));
        });
    }

    /** At most two NEW report documents; network does not hold a SQL lock. */
    public Status step(UUID tenant,UUID mp) {
        Claim c=tx.execute(t->{
            Market m=market(tenant,mp);
            SpApiSellerScope scope;
            try {scope=approved(tenant,m);}
            catch(SecurityException denied) {
                // Stop permanent retrying after Seller revocation or Finance
                // evidence expiry. An Owner must re-approve before restart.
                jdbc.update("""
                    UPDATE spapi_settlement_scan_job
                    SET state='PAUSED',last_failure_code='AUTHORIZATION_REJECTED',
                        lease_owner=NULL,lease_expires_at=NULL,updated_at=now()
                    WHERE tenant_id=? AND seller_marketplace_id=?
                      AND state IN ('READY','RETRY','RUNNING')
                      AND (lease_expires_at IS NULL OR lease_expires_at<=now())
                    """,tenant,mp);
                return null; // Commit PAUSED; do not roll back this transaction.
            }
            Job j=job(tenant,mp,true);
            Instant now=clock.instant();
            if(j==null||"PAUSED".equals(j.state())||"SUCCEEDED".equals(j.state()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Start a report scan first");
            if(j.lease()!=null&&j.lease().isAfter(now))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Report lease active");
            if(j.retry()!=null&&j.retry().isAfter(now))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Report retry not due");
            // Conservative cursor lifetime with overlapping-window replay.
            if((j.token()!=null&&(j.tokenAt()==null
                ||j.tokenAt().plus(Duration.ofMinutes(15)).isBefore(now)))
                || (j.token()==null && j.offset()>0 && j.updated()!=null
                    && j.updated().plus(Duration.ofMinutes(15)).isBefore(now))) {
                jdbc.update("""
                    UPDATE spapi_settlement_scan_job
                    SET next_token=NULL,token_issued_at=NULL,row_offset=0,
                        page_index=0,generation=generation+1
                    WHERE tenant_id=? AND id=?
                    """,tenant,j.id());
                j=job(tenant,mp,true);
            }
            UUID owner=UUID.randomUUID();
            long epoch=j.epoch()+1;
            jdbc.update("""
                UPDATE spapi_settlement_scan_job
                SET state='RUNNING',lease_epoch=?,lease_owner=?,
                    lease_expires_at=?,updated_at=now()
                WHERE tenant_id=? AND id=?
                """,epoch,owner,db(now.plus(Duration.ofMinutes(10))),tenant,j.id());
            return new Claim(tenant,mp,m,j,owner,epoch,scope);
        });
        if(c==null)return status(tenant,mp);
        try {
            var part=worker.scanPage(c.tenant(),c.market().account(),c.market().site(),
                db(c.job().from()),db(c.job().until()),c.job().token(),
                c.job().offset(),2,(scope,reportId,documentId,bytes,gzip)->
                    importFenced(c,scope,reportId,documentId,bytes,gzip));
            if(part.nextOffset()>0
                &&!Objects.equals(part.nextToken(),c.job().token()))
                throw new IllegalArgumentException("Invalid partial-page checkpoint");
            return tx.execute(t->{
                Job j=assertLease(c);
                sameScope(c.scope(),approved(tenant,c.market()));
                int changed=jdbc.update("""
                    UPDATE spapi_settlement_scan_job
                    SET state=?,next_token=?,token_issued_at=?,row_offset=?,
                        page_index=page_index+?,imported_count=imported_count+?,
                        lease_owner=NULL,lease_expires_at=NULL,retry_at=NULL,
                        retry_count=0,last_failure_code=NULL,
                        completed_at=CASE WHEN ? THEN now() ELSE completed_at END,
                        updated_at=now()
                    WHERE tenant_id=? AND id=? AND lease_epoch=? AND lease_owner=?
                    """,part.done()?"SUCCEEDED":"READY",part.nextToken(),
                    part.nextToken()==null?null:
                        Objects.equals(part.nextToken(),j.token())?
                            db(j.tokenAt()):db(clock.instant()),
                    part.nextOffset(),part.nextOffset()==0?1:0,
                    part.imported(),part.done(),tenant,j.id(),c.epoch(),c.owner());
                if(changed!=1)throw new SecurityException("Reports lease changed");
                return view(tenant,job(tenant,mp,false));
            });
        } catch(Exception failure) {
            fail(c,failure);
            return status(tenant,mp);
        }
    }

    /** Job row lock and immutable staged source transaction share one commit. */
    private void importFenced(Claim c,SpApiSellerScope provided,String reportId,
        String documentId,byte[] bytes,boolean gzip) {
        tx.executeWithoutResult(t->{
            assertLease(c);
            var live=approved(c.tenant(),c.market());
            sameScope(c.scope(),live);
            sameScope(live,provided);
            new SettlementReportImportService(jdbc).importDocument(
                live,reportId,documentId,bytes,gzip);
        });
    }
    private static void sameScope(SpApiSellerScope expected,SpApiSellerScope live) {
        if(!expected.tenantId().equals(live.tenantId())
            || !expected.sellerAccountId().equals(live.sellerAccountId())
            || !expected.sellerId().equals(live.sellerId())
            || !expected.marketplaceId().equals(live.marketplaceId())
            || expected.region()!=live.region()
            || !live.approvedRoles().contains("Finance and Accounting"))
            throw new SecurityException("Finance grant changed during report read");
    }
    private Job assertLease(Claim c) {
        Job j=job(c.tenant(),c.marketplace(),true);
        if(j==null || !"RUNNING".equals(j.state()) || j.epoch()!=c.epoch()
            || !c.owner().equals(j.owner())
            || j.lease()==null||!j.lease().isAfter(clock.instant()))
            throw new SecurityException("Stale Reports worker lease");
        return j;
    }

    public List<UUID[]> due(int count) {
        if(count<1||count>20)throw new IllegalArgumentException("Batch must be 1..20");
        return jdbc.query("""
            WITH eligible AS (
              SELECT tenant_id,seller_marketplace_id,updated_at,
                ROW_NUMBER() OVER (PARTITION BY tenant_id ORDER BY updated_at,id) lane
              FROM spapi_settlement_scan_job
              WHERE (state IN ('READY','RETRY')
                 OR (state='RUNNING' AND lease_expires_at<=now()))
                AND (retry_at IS NULL OR retry_at<=now())
                AND (lease_expires_at IS NULL OR lease_expires_at<=now())
            )
            SELECT tenant_id,seller_marketplace_id FROM eligible
            ORDER BY lane,updated_at,tenant_id LIMIT ?
            """,(r,n)->new UUID[]{r.getObject(1,UUID.class),
                r.getObject(2,UUID.class)},count);
    }

    private void fail(Claim c,Exception failure) {
        int status=failure instanceof ApiException api?api.getCode():0;
        boolean stale=failure instanceof SpApiSettlementReportsWorker.StaleCursorException
            || ((status==400||status==422)&&c.job().token()!=null);
        boolean transientFailure=stale || status==429 || status>=500
            || failure instanceof SpApiAdmissionException
            || failure instanceof IOException || failure instanceof InterruptedException;
        String code=stale?"CURSOR_RESTART":
            (failure instanceof SecurityException||status==401||status==403)?
                "AUTHORIZATION_REJECTED":
            transientFailure?"UPSTREAM_RETRY":"SOURCE_VALIDATION_FAILED";
        tx.executeWithoutResult(t->{
            Job current=job(c.tenant(),c.marketplace(),true);
            if(current==null||current.epoch()!=c.epoch()
                ||!c.owner().equals(current.owner())
                ||current.lease()==null
                ||!current.lease().isAfter(clock.instant()))return;
            boolean again=transientFailure&&current.attempts()<8;
            Duration minimum=failure instanceof SpApiAdmissionException adm
                ?adm.retryAfter():Duration.ofMinutes(1);
            if(minimum.compareTo(Duration.ofMinutes(1))<0)
                minimum=Duration.ofMinutes(1);
            Duration wait=SpApiRetryPolicy.nextDelay(current.attempts()+1,minimum);
            jdbc.update("""
                UPDATE spapi_settlement_scan_job
                SET state=?,retry_at=?,last_failure_code=?,
                    retry_count=retry_count+1,
                    next_token=CASE WHEN ? THEN NULL ELSE next_token END,
                    token_issued_at=CASE WHEN ? THEN NULL ELSE token_issued_at END,
                    row_offset=CASE WHEN ? THEN 0 ELSE row_offset END,
                    page_index=CASE WHEN ? THEN 0 ELSE page_index END,
                    generation=generation+CASE WHEN ? THEN 1 ELSE 0 END,
                    lease_owner=NULL,lease_expires_at=NULL,updated_at=now()
                WHERE tenant_id=? AND id=? AND lease_epoch=? AND lease_owner=?
                """,again?"RETRY":"PAUSED",db(again?clock.instant().plus(wait):null),
                again?code:"RETRY_LIMIT_OR_"+code,
                stale,stale,stale,stale,stale,
                c.tenant(),current.id(),c.epoch(),c.owner());
        });
    }
}
