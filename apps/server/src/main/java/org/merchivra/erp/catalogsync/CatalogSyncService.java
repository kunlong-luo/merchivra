package org.merchivra.erp.catalogsync;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.spapi.SpApiHttpException;
import org.merchivra.erp.spapi.SpApiOfficialSdkFactory;
import org.merchivra.erp.spapi.SpApiSellerGrantVault;
import org.merchivra.erp.spapi.SpApiSellerScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * LIVE-only page Worker. Fetch occurs outside the transaction; each page and
 * cursor commit under role, grant, seller and lease locks. Never runs in DEMO.
 */
@Service
@ConditionalOnProperty(prefix="merchivra.catalog-sync",name="enabled",havingValue="true")
public class CatalogSyncService {
    private static final Duration LEASE=Duration.ofMinutes(3);
    // Amazon FBA Inventory nextToken expires after about 30 seconds.
    private static final Duration FBA_TOKEN_AGE=Duration.ofSeconds(20);
    private static final Duration LISTING_TOKEN_AGE=Duration.ofMinutes(25);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SpApiSellerGrantVault vault;
    private final TypedCatalogSdkReader reader;
    private final Clock clock;

    public CatalogSyncService(JdbcTemplate jdbc,TransactionTemplate tx,
        SpApiSellerGrantVault vault,SpApiOfficialSdkFactory factory,
        @Value("${merchivra.demo.enabled:false}") boolean demo,
        @Value("${merchivra.spapi.enabled:false}") boolean outbound,
        @Value("${merchivra.spapi.sandbox:true}") boolean sandbox,
        @Value("${merchivra.spapi.allow-writes:false}") boolean writes,
        @Value("${merchivra.spapi.oauth.enabled:false}") boolean oauth,
        @Value("${merchivra.spapi.private.enabled:false}") boolean privateSeller,
        @Value("${merchivra.spapi.verification.enabled:false}") boolean verified) {
        this(jdbc,tx,vault,new TypedCatalogSdkReader(factory),Clock.systemUTC(),
            demo,outbound,sandbox,writes,oauth,privateSeller,verified);
    }
    CatalogSyncService(JdbcTemplate jdbc,TransactionTemplate tx,
        SpApiSellerGrantVault vault,TypedCatalogSdkReader reader,Clock clock,
        boolean demo,boolean outbound,boolean sandbox,boolean writes,
        boolean oauth,boolean privateSeller,boolean verified) {
        if(demo || !outbound || sandbox || writes || (!oauth&&!privateSeller) || !verified)
            throw new IllegalStateException(
                "Catalog LIVE sync requires verified production read-only Seller authorization, no Demo");
        this.jdbc=jdbc;this.tx=tx;this.vault=vault;this.reader=reader;this.clock=clock;
    }

    public enum Stream { LISTINGS,FBA }
    public record Status(String jobId,String stream,String state,int committedPages,
        int generation,long leaseEpoch,String nextRetryAt,String lastFailureCode,
        String lastFinishedAt) {}
    private record Job(UUID id,String state,String cursor,Instant cursorIssued,
        int generation,int page,long epoch,UUID owner,Instant leaseUntil,Instant retryAt) {}
    private record Claim(UUID tenant,UUID marketplace,Stream stream,Job job,
                         UUID owner,long epoch,SpApiSellerScope scope) {}
    private static OffsetDateTime dbTime(Instant instant){
        return instant==null?null:OffsetDateTime.ofInstant(instant,ZoneOffset.UTC);
    }
    private static Instant instant(java.sql.ResultSet rs,String column)
        throws java.sql.SQLException {
        var value=rs.getObject(column,OffsetDateTime.class);
        return value==null?null:value.toInstant();
    }
    private SpApiSellerScope approved(UUID tenant,UUID marketplace) {
        record Seller(UUID account,String region,String seller,String site) {}
        List<Seller> rows=jdbc.query("""
            SELECT m.seller_account_id,a.region,a.seller_id,m.marketplace_id
            FROM seller_marketplace m
            JOIN seller_account a ON a.tenant_id=m.tenant_id AND a.id=m.seller_account_id
            JOIN tenant t ON t.id=m.tenant_id
            JOIN spapi_seller_authorization g ON g.tenant_id=m.tenant_id
              AND g.seller_account_id=m.seller_account_id
            JOIN spapi_marketplace_verification v ON v.tenant_id=m.tenant_id
              AND v.seller_account_id=m.seller_account_id
              AND v.marketplace_id=m.marketplace_id
            JOIN spapi_orders_role_evidence e ON e.tenant_id=m.tenant_id
              AND e.seller_account_id=m.seller_account_id
            WHERE m.tenant_id=? AND m.id=? AND t.status='ACTIVE'
              AND a.connection_status='CONNECTED'
              AND a.seller_id NOT LIKE 'LOCAL-%' AND a.seller_id=g.seller_id
              AND a.region=g.region AND g.status='VERIFIED'
              AND g.verified_at IS NOT NULL AND g.verified_at=e.grant_verified_at
              AND v.verified_at>now()-interval '24 hours'
              AND e.role_code='Inventory and Order Tracking'
              AND e.evidence_kind='EXTERNAL_APP_ROLE_REVIEW'
              AND e.revoked_at IS NULL AND e.expires_at>now()
            FOR SHARE OF m,a,t,g,e,v
            """,(rs,n)->new Seller(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getString(3),rs.getString(4)),tenant,marketplace);
        if(rows.size()!=1)throw new SecurityException("Verified Seller and role scope required");
        var seller=rows.getFirst();
        var approved=vault.loadOrdersApproved(tenant,seller.account(),seller.site())
            .orElseThrow(()->new SecurityException("Independent role review missing"));
        if(!seller.seller().equals(approved.sellerId())
          || !seller.region().equals(approved.region().name())
          || !seller.site().equals(approved.marketplaceId()))
            throw new SecurityException("Seller authorization changed");
        return approved;
    }
    private Job job(UUID tenant,UUID mp,Stream stream,boolean lock) {
        List<Job> rows=jdbc.query("""
            SELECT id,state,next_token,token_issued_at,generation,page_index,lease_epoch,
              lease_owner,lease_expires_at,retry_at
            FROM spapi_catalog_sync_job
            WHERE tenant_id=? AND seller_marketplace_id=? AND stream=?
            """+(lock?" FOR UPDATE":""),
            (rs,n)->new Job(rs.getObject("id",UUID.class),rs.getString("state"),
                rs.getString("next_token"),instant(rs,"token_issued_at"),
                rs.getInt("generation"),rs.getInt("page_index"),rs.getLong("lease_epoch"),
                rs.getObject("lease_owner",UUID.class),instant(rs,"lease_expires_at"),
                instant(rs,"retry_at")),tenant,mp,stream.name());
        return rows.isEmpty()?null:rows.getFirst();
    }
    private Status view(UUID tenant,UUID marketplace,Stream stream,Job job) {
        if(job==null)return new Status(null,stream.name(),"NOT_STARTED",0,0,0,null,null,null);
        return jdbc.query("""
            SELECT last_failure_code,last_finished_at FROM spapi_catalog_sync_job
            WHERE tenant_id=? AND seller_marketplace_id=? AND stream=?
            """,(rs,n)->new Status(job.id().toString(),stream.name(),job.state(),
                job.page(),job.generation(),job.epoch(),
                job.retryAt()==null?null:job.retryAt().toString(),
                rs.getString(1),instant(rs,"last_finished_at")==null?null:
                    instant(rs,"last_finished_at").toString()),
            tenant,marketplace,stream.name()).getFirst();
    }
    public Status status(UUID tenant,UUID marketplace,Stream stream) {
        return tx.execute(t->{
            approved(tenant,marketplace);
            return view(tenant,marketplace,stream,job(tenant,marketplace,stream,false));
        });
    }
    public Status start(UUID tenant,UUID marketplace,Stream stream) {
        return tx.execute(t->{
            approved(tenant,marketplace);
            UUID proposed=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO spapi_catalog_sync_job
                  (id,tenant_id,seller_marketplace_id,stream,state)
                VALUES (?,?,?,?,'READY') ON CONFLICT DO NOTHING
                """,proposed,tenant,marketplace,stream.name());
            Job existing=job(tenant,marketplace,stream,true);
            if(existing==null)throw new IllegalStateException("Catalog job unavailable");
            if("RUNNING".equals(existing.state()) && existing.leaseUntil()!=null
                && existing.leaseUntil().isAfter(clock.instant()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Job already active");
            if(!existing.id().equals(proposed))jdbc.update("""
                UPDATE spapi_catalog_sync_job
                SET state='READY',next_token=NULL,token_issued_at=NULL,
                    generation=generation+1,page_index=0,
                    lease_owner=NULL,lease_expires_at=NULL,retry_at=NULL,
                    last_failure_code=NULL,updated_at=now()
                WHERE tenant_id=? AND id=?
                """,tenant,existing.id());
            return view(tenant,marketplace,stream,job(tenant,marketplace,stream,false));
        });
    }
    public Status step(UUID tenant,UUID marketplace,Stream stream) {
        Claim claim=tx.execute(t->{
            SpApiSellerScope scope=approved(tenant,marketplace);
            Job current=job(tenant,marketplace,stream,true);
            if(current==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Start sync first");
            Instant now=clock.instant();
            if("PAUSED".equals(current.state()) || "SUCCEEDED".equals(current.state()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Start a new sync generation");
            if(current.owner()!=null && current.leaseUntil()!=null
               && current.leaseUntil().isAfter(now))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Job lease active");
            if(current.retryAt()!=null && current.retryAt().isAfter(now))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Retry not due");
            if(current.cursor()!=null && (current.cursorIssued()==null ||
               current.cursorIssued().plus(stream==Stream.FBA?FBA_TOKEN_AGE:LISTING_TOKEN_AGE)
                   .isBefore(now))) {
                // Restart snapshot because opaque Amazon token can expire.
                jdbc.update("""
                    UPDATE spapi_catalog_sync_job SET next_token=NULL,token_issued_at=NULL,
                      page_index=0,generation=generation+1,retry_at=NULL
                    WHERE tenant_id=? AND id=?
                    """,tenant,current.id());
                current=job(tenant,marketplace,stream,true);
            }
            UUID owner=UUID.randomUUID();
            long epoch=current.epoch()+1;
            jdbc.update("""
                UPDATE spapi_catalog_sync_job
                SET state='RUNNING',lease_epoch=?,lease_owner=?,
                    lease_expires_at=?,updated_at=now()
                WHERE tenant_id=? AND id=?
                """,epoch,owner,dbTime(now.plus(LEASE)),tenant,current.id());
            return new Claim(tenant,marketplace,stream,current,owner,epoch,scope);
        });
        TypedCatalogSdkReader.Page page;
        try {
            page=stream==Stream.LISTINGS
                ?reader.fetchListings(claim.scope(),claim.job().cursor())
                :reader.fetchInventory(claim.scope(),claim.job().cursor());
            if(page==null || page.nextToken()!=null && page.nextToken().equals(claim.job().cursor()))
                throw new IllegalArgumentException("Invalid or repeated source cursor");
        } catch(Exception remote) {
            fail(claim,remote);
            return status(tenant,marketplace,stream);
        }
        try {
            return tx.execute(t->{
                var renewed=approved(tenant,marketplace);
                if(!renewed.sellerId().equals(claim.scope().sellerId())
                  || renewed.region()!=claim.scope().region()
                  || !renewed.marketplaceId().equals(claim.scope().marketplaceId()))
                    throw new SecurityException("Seller scope changed mid-fetch");
                Job current=job(tenant,marketplace,stream,true);
                if(current==null || !"RUNNING".equals(current.state())
                    || current.epoch()!=claim.epoch()
                    || !claim.owner().equals(current.owner())
                    || current.leaseUntil()==null
                    || !current.leaseUntil().isAfter(clock.instant()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Stale sync lease");
                int count=stream==Stream.LISTINGS?page.listings().size():page.stocks().size();
                if(stream==Stream.LISTINGS)for(var record:page.listings()) {
                    jdbc.update("""
                        INSERT INTO marketplace_listing
                          (id,tenant_id,seller_marketplace_id,seller_sku,asin,fnsku,
                           mapping_status,source_updated_at,data_origin)
                        VALUES (?,?,?,?,?,?,'UNMAPPED',?,'LIVE')
                        ON CONFLICT (tenant_id,seller_marketplace_id,seller_sku)
                        DO UPDATE SET
                          asin=COALESCE(EXCLUDED.asin,marketplace_listing.asin),
                          fnsku=COALESCE(EXCLUDED.fnsku,marketplace_listing.fnsku),
                          source_updated_at=COALESCE(EXCLUDED.source_updated_at,
                                                      marketplace_listing.source_updated_at),
                          data_origin='LIVE'
                        WHERE marketplace_listing.data_origin <> 'MOCK'
                          AND (EXCLUDED.source_updated_at IS NULL OR
                            marketplace_listing.source_updated_at IS NULL OR
                            marketplace_listing.source_updated_at<=EXCLUDED.source_updated_at)
                        """,UUID.randomUUID(),tenant,marketplace,record.sellerSku(),
                        record.asin(),record.fnsku(),dbTime(record.updatedAt()));
                } else for(var record:page.stocks()) {
                    // Stable source key within one generation/page survives retries.
                    UUID stable=UUID.nameUUIDFromBytes((current.id()+":"+
                        current.generation()+":"+current.page()+":"+record.sellerSku())
                        .getBytes(StandardCharsets.UTF_8));
                    jdbc.update("""
                        INSERT INTO fba_inventory_observation
                          (id,tenant_id,seller_marketplace_id,seller_sku_raw,
                           source_observation_key,quality,fulfillable,reserved,observed_at,
                           data_origin)
                        VALUES (?,?,?,?,?,?,?,?,?,'LIVE')
                        ON CONFLICT (tenant_id,seller_marketplace_id,source_observation_key)
                        DO NOTHING
                        """,UUID.randomUUID(),tenant,marketplace,record.sellerSku(),
                        "LIVE-FBA-"+stable,record.quality(),record.fulfillable(),
                        record.reserved(),dbTime(record.updatedAt()));
                }
                jdbc.update("""
                    INSERT INTO spapi_catalog_page_commit
                      (id,tenant_id,job_id,generation,page_index,record_count)
                    VALUES (?,?,?,?,?,?)
                    """,UUID.randomUUID(),tenant,current.id(),current.generation(),
                    current.page(),count);
                boolean complete=page.nextToken()==null;
                jdbc.update("""
                    UPDATE spapi_catalog_sync_job SET
                      state=?,next_token=?,token_issued_at=?,page_index=page_index+1,
                      lease_owner=NULL,lease_expires_at=NULL,retry_at=NULL,
                      last_failure_code=NULL,
                      last_finished_at=CASE WHEN ? THEN now() ELSE last_finished_at END,
                      updated_at=now()
                    WHERE tenant_id=? AND id=? AND lease_epoch=? AND lease_owner=?
                    """,complete?"SUCCEEDED":"READY",page.nextToken(),
                    dbTime(complete?null:clock.instant()),complete,tenant,current.id(),
                    claim.epoch(),claim.owner());
                return view(tenant,marketplace,stream,job(tenant,marketplace,stream,false));
            });
        } catch(SecurityException | ResponseStatusException denied) {
            fail(claim,new SecurityException("Authorization or lease rejected"));
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Catalog sync authorization changed");
        }
    }
    /** Poll only locally started work; never silently enroll a Seller in a new sync. */
    public List<JobKey> due(int max) {
        if(max<1||max>20)throw new IllegalArgumentException("batch must be 1..20");
        return jdbc.query("""
            SELECT tenant_id,seller_marketplace_id,stream
            FROM spapi_catalog_sync_job
            WHERE state IN ('READY','RETRY')
              AND (retry_at IS NULL OR retry_at<=now())
              AND (lease_expires_at IS NULL OR lease_expires_at<=now())
            ORDER BY updated_at LIMIT ?
            """,(rs,n)->new JobKey(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                Stream.valueOf(rs.getString(3))),max);
    }
    public record JobKey(UUID tenant,UUID marketplace,Stream stream) {}

    private void fail(Claim claim,Exception error) {
        int http=error instanceof SpApiHttpException e?e.status():0;
        boolean retry=http==429 || http==500 || http==502 || http==503 || http==504;
        String code=retry?"UPSTREAM_RETRY":http==400 && claim.job().cursor()!=null
            ?"CURSOR_RESTART_REQUIRED":http==401 || http==403 ||
              error instanceof SecurityException?"AUTHORIZATION_REJECTED":"SOURCE_ERROR";
        tx.executeWithoutResult(t->{
            Job current=job(claim.tenant(),claim.marketplace(),claim.stream(),true);
            if(current==null || current.epoch()!=claim.epoch()
                || !claim.owner().equals(current.owner()))return;
            jdbc.update("""
                UPDATE spapi_catalog_sync_job
                SET state=?,retry_at=?,last_failure_code=?,
                    lease_owner=NULL,lease_expires_at=NULL,updated_at=now()
                WHERE tenant_id=? AND id=? AND lease_epoch=? AND lease_owner=?
                """,retry?"RETRY":"PAUSED",
                dbTime(retry?clock.instant().plusSeconds(180):null),code,
                claim.tenant(),current.id(),claim.epoch(),claim.owner());
        });
    }
}
