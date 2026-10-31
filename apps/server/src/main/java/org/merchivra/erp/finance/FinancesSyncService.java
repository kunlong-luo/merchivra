package org.merchivra.erp.finance;

import java.time.*;
import java.util.*;
import org.merchivra.erp.spapi.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Read-only, opt-in Finances worker: network outside DB tx, fenced atomic page commits. */
@Service
@ConditionalOnProperty(prefix="merchivra.finances-sync",name="enabled",havingValue="true")
public class FinancesSyncService {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 private final SpApiSellerGrantVault vault;
 private final TypedFinancesSdkReader api;
 private final FinanceEventService events;
 private final Clock clock;
 @Autowired public FinancesSyncService(JdbcTemplate j,TransactionTemplate t,
   SpApiSellerGrantVault v,SpApiOfficialSdkFactory sdk,FinanceEventService e,
   @Value("${merchivra.demo.enabled:false}") boolean demo,
   @Value("${merchivra.spapi.enabled:false}") boolean outbound,
   @Value("${merchivra.spapi.sandbox:true}") boolean sandbox,
   @Value("${merchivra.spapi.allow-writes:false}") boolean writes,
   @Value("${merchivra.spapi.oauth.enabled:false}") boolean oauth,
   @Value("${merchivra.spapi.private.enabled:false}") boolean priv,
   @Value("${merchivra.spapi.verification.enabled:false}") boolean verified){
    if(demo||!outbound||sandbox||writes||(!oauth&&!priv)||!verified)
      throw new IllegalStateException("Real Finances read requires verified read-only Seller");
    jdbc=j;tx=t;vault=v;api=new TypedFinancesSdkReader(sdk);events=e;clock=Clock.systemUTC();
 }
 public record Status(String jobId,String state,int committedPages,int generation,
   String nextRetryAt,String lastFailureCode) {}
 private record Job(UUID id,String state,Instant after,Instant before,String cursor,
   Instant tokenAt,int page,int generation,long epoch,UUID owner,Instant lease,Instant retry) {}
 private record Claim(UUID tenant,UUID marketplace,Job job,UUID owner,long epoch,
   SpApiSellerScope scope) {}
 private static Instant time(java.sql.ResultSet r,String k)throws java.sql.SQLException {
   var t=r.getObject(k,OffsetDateTime.class);return t==null?null:t.toInstant();
 }
 private static OffsetDateTime db(Instant t){return t==null?null:t.atOffset(ZoneOffset.UTC);}
 private Job job(UUID tenant,UUID mp,boolean lock){
   return jdbc.query("""
     SELECT id,state,posted_after,posted_before,next_token,token_issued_at,
       page_index,generation,lease_epoch,lease_owner,lease_expires_at,retry_at
     FROM spapi_finances_sync_job WHERE tenant_id=? AND seller_marketplace_id=?
     """+(lock?" FOR UPDATE":""),
     (r,n)->new Job(r.getObject("id",UUID.class),r.getString("state"),
       time(r,"posted_after"),time(r,"posted_before"),r.getString("next_token"),
       time(r,"token_issued_at"),r.getInt("page_index"),r.getInt("generation"),
       r.getLong("lease_epoch"),r.getObject("lease_owner",UUID.class),
       time(r,"lease_expires_at"),time(r,"retry_at")),tenant,mp)
     .stream().findFirst().orElse(null);
 }
 private Status view(UUID tenant,Job j){
   if(j==null)return new Status(null,"NOT_STARTED",0,0,null,null);
   String code=jdbc.queryForObject("""
      SELECT last_failure_code FROM spapi_finances_sync_job WHERE tenant_id=? AND id=?
      """,String.class,tenant,j.id());
   return new Status(j.id().toString(),j.state(),j.page(),j.generation(),
     j.retry()==null?null:j.retry().toString(),code);
 }
 /** Locks all eligibility evidence before page commit, fencing concurrent revocation. */
 private SpApiSellerScope approved(UUID tenant,UUID mp){
   record Seller(UUID account,String id,String region,String site){}
   var sellers=jdbc.query("""
     SELECT m.seller_account_id,a.seller_id,a.region,m.marketplace_id
     FROM seller_marketplace m JOIN seller_account a ON a.tenant_id=m.tenant_id
       AND a.id=m.seller_account_id JOIN tenant t ON t.id=m.tenant_id
     JOIN spapi_seller_authorization g ON g.tenant_id=m.tenant_id
       AND g.seller_account_id=m.seller_account_id
     JOIN spapi_marketplace_verification v ON v.tenant_id=m.tenant_id
       AND v.seller_account_id=m.seller_account_id AND v.marketplace_id=m.marketplace_id
     JOIN spapi_finances_role_evidence e ON e.tenant_id=m.tenant_id
       AND e.seller_account_id=m.seller_account_id
     WHERE m.tenant_id=? AND m.id=? AND t.status='ACTIVE'
       AND a.connection_status='CONNECTED' AND a.seller_id=g.seller_id
       AND a.region=g.region AND a.seller_id NOT LIKE 'LOCAL-%'
       AND g.status='VERIFIED' AND e.grant_verified_at=g.verified_at
       AND v.verified_at>now()-interval '24 hours'
       AND e.role_code='Finance and Accounting'
       AND e.evidence_kind='EXTERNAL_APP_ROLE_REVIEW'
       AND e.revoked_at IS NULL AND e.expires_at>now()
     FOR SHARE OF m,a,t,g,v,e
     """,(r,n)->new Seller(r.getObject(1,UUID.class),r.getString(2),
       r.getString(3),r.getString(4)),tenant,mp);
   if(sellers.size()!=1)throw new SecurityException("Finances source not approved");
   var s=sellers.getFirst();
   var approved=vault.loadFinancesApproved(tenant,s.account(),s.site())
     .orElseThrow(()->new SecurityException("Finances role evidence missing"));
   if(!approved.sellerId().equals(s.id())||!approved.region().name().equals(s.region()))
     throw new SecurityException("Finances source changed");
   return approved;
 }
 public Status status(UUID tenant,UUID mp){
   return tx.execute(t->{approved(tenant,mp);return view(tenant,job(tenant,mp,false));});
 }
 /** Explicit Owner start; fixed 14-day lookback excludes unposted recent 48 h. */
 public Status start(UUID tenant,UUID mp){
   return tx.execute(t->{
     approved(tenant,mp);
     Instant before=clock.instant().minus(Duration.ofHours(48));
     Instant after=before.minus(Duration.ofDays(14));
     UUID id=UUID.randomUUID();
     jdbc.update("""
       INSERT INTO spapi_finances_sync_job
         (id,tenant_id,seller_marketplace_id,posted_after,posted_before)
       VALUES (?,?,?,?,?) ON CONFLICT (tenant_id,seller_marketplace_id) DO NOTHING
       """,id,tenant,mp,db(after),db(before));
     Job j=job(tenant,mp,true);
     if(j==null)throw new IllegalStateException("Finances job missing");
     if("RUNNING".equals(j.state())&&j.lease()!=null&&j.lease().isAfter(clock.instant()))
       throw new ResponseStatusException(HttpStatus.CONFLICT,"Finances job active");
     if(!j.id().equals(id))jdbc.update("""
       UPDATE spapi_finances_sync_job SET state='READY',posted_after=?,posted_before=?,
         next_token=NULL,token_issued_at=NULL,page_index=0,generation=generation+1,
         lease_owner=NULL,lease_expires_at=NULL,retry_at=NULL,
         retry_count=0,last_failure_code=NULL,updated_at=now() WHERE tenant_id=? AND id=?
       """,db(after),db(before),tenant,j.id());
     return view(tenant,job(tenant,mp,false));
   });
 }
 public Status step(UUID tenant,UUID mp){
   Claim c=tx.execute(t->{
     SpApiSellerScope scope=approved(tenant,mp);
     Job j=job(tenant,mp,true);Instant now=clock.instant();
     if(j==null||"PAUSED".equals(j.state())||"SUCCEEDED".equals(j.state()))
       throw new ResponseStatusException(HttpStatus.CONFLICT,"Start a Finances scan first");
     if(j.lease()!=null&&j.lease().isAfter(now))
       throw new ResponseStatusException(HttpStatus.CONFLICT,"Finances lease active");
     if(j.retry()!=null&&j.retry().isAfter(now))
       throw new ResponseStatusException(HttpStatus.CONFLICT,"Retry not due");
     if(j.cursor()!=null&&(j.tokenAt()==null||j.tokenAt().plus(Duration.ofMinutes(15)).isBefore(now))){
       jdbc.update("""
         UPDATE spapi_finances_sync_job SET next_token=NULL,token_issued_at=NULL,
           generation=generation+1,page_index=0 WHERE tenant_id=? AND id=?
         """,tenant,j.id());
       j=job(tenant,mp,true);
     }
     UUID owner=UUID.randomUUID();long epoch=j.epoch()+1;
     jdbc.update("""
       UPDATE spapi_finances_sync_job SET state='RUNNING',lease_epoch=?,
         lease_owner=?,lease_expires_at=?,updated_at=now() WHERE tenant_id=? AND id=?
       """,epoch,owner,db(now.plusSeconds(180)),tenant,j.id());
     return new Claim(tenant,mp,j,owner,epoch,scope);
   });
   TypedFinancesSdkReader.Page page;
   try {
     page=api.fetch(c.scope(),c.job().after(),c.job().before(),c.job().cursor());
     if(page==null||(page.nextToken()!=null&&page.nextToken().equals(c.job().cursor())))
       throw new IllegalArgumentException("Unsafe Finances cursor");
   }catch(Exception e){
     fail(c,e);return view(tenant,job(tenant,mp,false));
   }
   try {
     return tx.execute(t->{
       SpApiSellerScope scope=approved(tenant,mp);
       if(!scope.sellerId().equals(c.scope().sellerId())||
           scope.region()!=c.scope().region())
         throw new SecurityException("Finances scope changed during read");
       Job j=job(tenant,mp,true);
       if(j==null||!"RUNNING".equals(j.state())||j.epoch()!=c.epoch()
           ||!c.owner().equals(j.owner())||j.lease()==null||
           !j.lease().isAfter(clock.instant()))
         throw new ResponseStatusException(HttpStatus.CONFLICT,"Stale Finances lease");
       for(var event:page.events())events.recordTrusted(scope,event);
       jdbc.update("""
         INSERT INTO spapi_finances_page_commit
           (id,tenant_id,job_id,generation,page_index,event_count)
         VALUES (?,?,?,?,?,?)
         """,UUID.randomUUID(),tenant,j.id(),j.generation(),j.page(),page.events().size());
       boolean last=page.nextToken()==null;
       jdbc.update("""
         UPDATE spapi_finances_sync_job SET state=?,next_token=?,token_issued_at=?,
           page_index=page_index+1,lease_owner=NULL,lease_expires_at=NULL,
           retry_at=NULL,retry_count=0,last_failure_code=NULL,
           completed_at=CASE WHEN ? THEN now() ELSE completed_at END,
           updated_at=now()
         WHERE tenant_id=? AND id=? AND lease_epoch=? AND lease_owner=?
         """,last?"SUCCEEDED":"READY",page.nextToken(),
           db(last?null:clock.instant()),last,tenant,j.id(),c.epoch(),c.owner());
       return view(tenant,job(tenant,mp,false));
     });
   }catch(SecurityException|ResponseStatusException e){
     fail(c,new SecurityException("Finances source revoked"));
     throw new ResponseStatusException(HttpStatus.CONFLICT,"Finances scope revoked or stale");
   }
 }
 public List<UUID[]> due(int max){
   if(max<1||max>20)throw new IllegalArgumentException("batch must be 1..20");
   return jdbc.query("""
     SELECT tenant_id,seller_marketplace_id FROM spapi_finances_sync_job
     WHERE (state IN ('READY','RETRY') OR (state='RUNNING' AND lease_expires_at<=now()))
       AND (retry_at IS NULL OR retry_at<=now())
       AND (lease_expires_at IS NULL OR lease_expires_at<=now())
     ORDER BY updated_at LIMIT ?
     """,(r,n)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)},max);
 }
 private void fail(Claim c,Exception e){
   int code=e instanceof SpApiHttpException h?h.status():0;
   boolean stale=code==400&&c.job().cursor()!=null;
   boolean retry=stale||code==429||code==500||code==502||code==503||code==504;
   String reason=stale?"CURSOR_RESTART":retry?"UPSTREAM_RETRY":
     code==401||code==403||e instanceof SecurityException?
       "AUTHORIZATION_REJECTED":"SOURCE_ERROR";
   tx.executeWithoutResult(t->{
     Job j=job(c.tenant(),c.marketplace(),true);
     if(j==null||j.epoch()!=c.epoch()||!c.owner().equals(j.owner()))return;
     int attempts=jdbc.queryForObject("""
       SELECT retry_count FROM spapi_finances_sync_job WHERE tenant_id=? AND id=?
       """,Integer.class,c.tenant(),j.id());
     boolean mayRetry=retry&&attempts<8;
     long seconds=Math.min(3600,180L*(1L<<Math.min(attempts,4)));
     jdbc.update("""
       UPDATE spapi_finances_sync_job SET state=?,retry_at=?,last_failure_code=?,
         retry_count=retry_count+1,
         next_token=CASE WHEN ? THEN NULL ELSE next_token END,
         token_issued_at=CASE WHEN ? THEN NULL ELSE token_issued_at END,
         page_index=CASE WHEN ? THEN 0 ELSE page_index END,
         generation=generation+CASE WHEN ? THEN 1 ELSE 0 END,
         lease_owner=NULL,lease_expires_at=NULL,updated_at=now()
       WHERE tenant_id=? AND id=? AND lease_epoch=? AND lease_owner=?
       """,mayRetry?"RETRY":"PAUSED",
         db(mayRetry?clock.instant().plusSeconds(seconds):null),
         mayRetry?reason:"RETRY_LIMIT_OR_"+reason,
         stale,stale,stale,stale,c.tenant(),j.id(),c.epoch(),c.owner());
   });
 }
}
