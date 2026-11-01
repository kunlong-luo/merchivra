package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-created, DB-durable immutable cohort and bounded per-order extraction.
 * One transactional batch locks a job, computes up to 10 orders and commits all
 * changes together. A crash rolls the batch back: no leases or orphaned RUNNING
 * state. Source data is assessed per batch, not one global source snapshot.
 */
@Service
public class ProfitReportJobService {
    public static final int MAX_ORDERS=5000;
    public static final int BATCH_SIZE=10;
    private final JdbcTemplate jdbc;
    private final OrderCostReadinessService costs;
    private final OrderContributionService contributions;
    private final MultiSkuContributionService multiSku;
    private final Clock clock;
    private final TransactionTemplate batchTx;

    @Autowired
    public ProfitReportJobService(JdbcTemplate jdbc,
        OrderCostReadinessService costs,OrderContributionService contributions,
        MultiSkuContributionService multiSku,PlatformTransactionManager manager) {
        this(jdbc,costs,contributions,multiSku,manager,Clock.systemUTC());
    }
    ProfitReportJobService(JdbcTemplate jdbc,
        OrderCostReadinessService costs,OrderContributionService contributions,
        MultiSkuContributionService multiSku,PlatformTransactionManager manager,Clock clock) {
        this.jdbc=jdbc;this.costs=costs;this.contributions=contributions;
        this.multiSku=multiSku;this.clock=clock;
        this.batchTx=new TransactionTemplate(manager);
        this.batchTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.batchTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public record Job(String id,String state,String after,String before,
        String currency,int totalOrders,int processedOrders,int eligibleOrders,
        int blockedOrders,int retryCount,String failureCode,
        String manualAdsReference,long manualAdsCount,String createdAt,
        String completedAt,boolean cohortFrozen,boolean globalSourceSnapshot,
        boolean financialSourceComplete,boolean bankVerified,
        boolean ledgerPosted) {}
    public record Totals(String currency,int eligibleOrders,int blockedOrders,
        int reviewedEvents,String signedSales,String signedAmazonFees,
        String signedFbaFees,String provisionalCogs,String contribution) {}
    public record Sku(String sku,int orderCount,int units,String currency,
        String sales,String amazonFees,String fbaFees,String cogs,String contribution) {}
    public record Order(String orderId,int position,String state,String readiness,
        String currency,String sales,String fees,String fba,String cogs,
        String contribution,String extractedAt,String extractSha256) {}
    public record Report(Job job,Totals totals,List<Sku> skus,List<Order> orders,
        int orderOffset,boolean hasMore) {}
    public record Audit(String action,int processedOrders,String failureCode,
        String occurredAt) {}
    private record Header(UUID tenant,UUID mp,String state,String currency,int total,
        int processed,int retries,LocalDate after,LocalDate before) {}

    static void validate(LocalDate from,LocalDate before,Clock clock) {
        SkuProfitPreviewService.validate(from,before,0,clock);
    }
    private static String hash(String canonical) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(canonical.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static String str(BigDecimal value) {
        return value==null?null:value.toPlainString();
    }
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Report unavailable in active Owner tenant/Marketplace");
    }
    private void audit(UUID tenant,UUID id,String action,int processed,String code) {
        jdbc.update("""
            INSERT INTO finance_profit_report_audit
              (id,tenant_id,job_id,action,processed_orders,failure_code)
            VALUES (?,?,?,?,?,?)
            """,UUID.randomUUID(),tenant,id,action,processed,code);
    }
    /**
     * Idempotent exact request key for accidental retries, new key for a new
     * extract of the same time window after upstream reviews changed.
     */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Job create(UUID tenant,UUID mp,UUID actor,
        UUID requestKey,LocalDate after,LocalDate before) {
        if(actor==null||requestKey==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "An authenticated Owner and idempotency request key are required");
        validate(after,before,clock);
        var previous=jdbc.query("""
            SELECT id,period_after,period_before FROM finance_profit_report_job
            WHERE tenant_id=? AND seller_marketplace_id=? AND request_key=?
            """,(r,n)->new Object[]{r.getObject("id",UUID.class),
                r.getObject("period_after",LocalDate.class),
                r.getObject("period_before",LocalDate.class)},tenant,mp,requestKey);
        if(!previous.isEmpty()) {
            var old=previous.getFirst();
            if(!after.equals(old[1])||!before.equals(old[2]))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key already used for another window");
            return status(tenant,mp,(UUID)old[0]);
        }
        var currencies=jdbc.query("""
            SELECT m.currency_code FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            WHERE m.tenant_id=? AND m.id=?
            """,(r,n)->r.getString(1).trim(),tenant,mp);
        if(currencies.size()!=1)throw notFound();
        String currency=currencies.getFirst();
        int active=jdbc.queryForObject("""
            SELECT COUNT(*) FROM finance_profit_report_job
            WHERE tenant_id=? AND seller_marketplace_id=? AND state IN ('READY','RETRY','PAUSED')
            """,Integer.class,tenant,mp);
        if(active>=3)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Maximum three unfinished reports per Marketplace");
        OffsetDateTime start=after.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end=before.atStartOfDay().atOffset(ZoneOffset.UTC);
        long count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales_order
            WHERE tenant_id=? AND seller_marketplace_id=? AND data_origin='LIVE'
              AND order_time>=? AND order_time<?
            """,Long.class,tenant,mp,start,end);
        if(count>MAX_ORDERS)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "Period exceeds 5000 LIVE orders; split into smaller date windows");
        var ads=jdbc.query("""
            SELECT COALESCE(SUM(spend_amount),0),COUNT(*)
            FROM finance_ad_spend_reference
            WHERE tenant_id=? AND seller_marketplace_id=?
              AND evidence_origin='MANUAL_UNVERIFIED' AND currency_code=?
              AND business_date>=? AND business_date<?
            """,(r,n)->new Object[]{r.getBigDecimal(1),r.getLong(2)},
            tenant,mp,currency,after,before).getFirst();
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO finance_profit_report_job(
             id,request_key,tenant_id,seller_marketplace_id,created_by,period_after,
             period_before,currency_code,state,total_orders,processed_orders,
             manual_ads_reference,manual_ads_count,completed_at)
            VALUES(?,?,?,?,?,?,?,?,
             ?,?,0,?,?,?)
            """,id,requestKey,tenant,mp,actor,after,before,currency,
            count==0?"SUCCEEDED":"READY",(int)count,ads[0],ads[1],
            count==0?OffsetDateTime.now(clock):null);
        int inserted=jdbc.update("""
            INSERT INTO finance_profit_report_order(tenant_id,job_id,order_id,cohort_position)
            SELECT ?,?,o.id,(row_number() OVER (ORDER BY o.order_time,o.id)-1)::integer
            FROM sales_order o
            WHERE o.tenant_id=? AND o.seller_marketplace_id=?
              AND o.data_origin='LIVE' AND o.order_time>=? AND o.order_time<?
            ORDER BY o.order_time,o.id
            """,tenant,id,tenant,mp,start,end);
        if(inserted!=count)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Order cohort changed during snapshot creation");
        audit(tenant,id,"CREATED",0,null);
        if(count==0)audit(tenant,id,"SUCCEEDED",0,null);
        return status(tenant,mp,id);
    }
    private Header header(UUID tenant,UUID mp,UUID id,boolean lock) {
        var jobs=jdbc.query("""
            SELECT tenant_id,seller_marketplace_id,state,currency_code,total_orders,
              processed_orders,retry_count,period_after,period_before
            FROM finance_profit_report_job
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """+(lock?" FOR UPDATE SKIP LOCKED":""),
            (r,n)->new Header(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                r.getString(3),r.getString(4).trim(),r.getInt(5),r.getInt(6),
                r.getInt(7),r.getObject(8,LocalDate.class),
                r.getObject(9,LocalDate.class)),tenant,mp,id);
        return jobs.isEmpty()?null:jobs.getFirst();
    }
    /** One processing transaction, deliberately bounded at ten source orders. */
    public Job step(UUID tenant,UUID mp,UUID jobId) {
        try {
            batchTx.executeWithoutResult(tx->process(tenant,mp,jobId));
        }catch(RuntimeException e) {
            // A failed batch rolls back in full. Store a sanitized error code
            // separately. Never persist raw exception text or sensitive sources.
            failure(tenant,mp,jobId,e);
        }
        return status(tenant,mp,jobId);
    }
    private void process(UUID tenant,UUID mp,UUID jobId) {
        Header job=header(tenant,mp,jobId,true);
        if(job==null)return; // Missing, or another worker holds the row.
        if(!"READY".equals(job.state())&&!"RETRY".equals(job.state()))return;
        var next=jdbc.query("""
            SELECT order_id FROM finance_profit_report_order
            WHERE tenant_id=? AND job_id=? AND state='PENDING'
            ORDER BY cohort_position LIMIT ?
            """,(r,n)->r.getObject(1,UUID.class),tenant,jobId,BATCH_SIZE);
        int processed=job.processed();
        for(UUID orderId:next) {
            var c=costs.preview(tenant,mp,orderId);
            var p=contributions.preview(tenant,mp,orderId);
            var skus=c.items().stream().map(OrderCostEngine.Line::internalSku)
                .filter(x->x!=null).distinct().toList();
            boolean mixed=skus.size()>1;
            String sku=skus.size()==1?skus.getFirst():null;
            int units=c.items().stream().mapToInt(item->
                item.quantity()==null?0:item.quantity()).sum();
            var input=new SkuProfitPreviewEngine.Input(orderId.toString(),
                null,null,sku,units,c.readiness(),mixed,p);
            Map<String,MultiSkuContributionService.Result> multi=mixed
                ?Map.of(orderId.toString(),multiSku.preview(tenant,mp,orderId,c,p))
                :Map.of();
            var extract=SkuProfitPreviewEngine.calculate(job.currency(),
                List.of(input),false,false,multi);
            var row=extract.orders().getFirst();
            boolean ready=row.contribution()!=null;
            var payload=new StringBuilder(jobId.toString()).append('|')
                .append(orderId).append('|').append(row.readiness()).append('|')
                .append(row.currency()).append('|').append(row.reviewedEventCount())
                .append('|').append(row.sales()).append('|')
                .append(row.amazonFees()).append('|').append(row.fbaFees())
                .append('|').append(row.cogs()).append('|').append(row.contribution());
            for(var s:extract.skus())payload.append('|').append(s.sku())
                .append(':').append(s.units()).append(':').append(s.sales())
                .append(':').append(s.amazonFees()).append(':').append(s.fbaFees())
                .append(':').append(s.cogs()).append(':').append(s.contribution());
            int updated=jdbc.update("""
                UPDATE finance_profit_report_order
                SET state=?,readiness=?,currency_code=?,reviewed_events=?,
                 signed_sales=?,signed_amazon_fees=?,signed_fba_fees=?,
                 provisional_cogs=?,estimated_contribution=?,extract_sha256=?,
                 captured_at=now()
                WHERE tenant_id=? AND job_id=? AND order_id=? AND state='PENDING'
                """,ready?"ELIGIBLE":"BLOCKED",row.readiness(),job.currency(),
                row.reviewedEventCount(),ready?new BigDecimal(row.sales()):null,
                ready?new BigDecimal(row.amazonFees()):null,
                ready?new BigDecimal(row.fbaFees()):null,
                ready?new BigDecimal(row.cogs()):null,
                ready?new BigDecimal(row.contribution()):null,
                hash(payload.toString()),tenant,jobId,orderId);
            if(updated!=1)throw new IllegalStateException("ORDER_ALREADY_CAPTURED");
            for(var s:extract.skus()) {
                jdbc.update("""
                    INSERT INTO finance_profit_report_sku
                     (tenant_id,job_id,order_id,internal_sku,units,
                      signed_sales,signed_amazon_fees,signed_fba_fees,
                      provisional_cogs,estimated_contribution)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """,tenant,jobId,orderId,s.sku(),s.units(),
                    new BigDecimal(s.sales()),new BigDecimal(s.amazonFees()),
                    new BigDecimal(s.fbaFees()),new BigDecimal(s.cogs()),
                    new BigDecimal(s.contribution()));
            }
            processed++;
        }
        boolean completed=processed==job.total();
        if(completed) {
            Integer distinct=jdbc.queryForObject("""
                SELECT COUNT(DISTINCT internal_sku)
                FROM finance_profit_report_sku WHERE tenant_id=? AND job_id=?
                """,Integer.class,tenant,jobId);
            if(distinct==null||distinct>1000)
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "More than 1000 distinct SKUs; split the report date window");
        }
        jdbc.update("""
            UPDATE finance_profit_report_job
            SET processed_orders=?,state=?,updated_at=now(),
              completed_at=CASE WHEN ? THEN now() ELSE NULL END,
              retry_count=0,failure_code=NULL,next_retry_at=NULL
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """,processed,completed?"SUCCEEDED":"READY",completed,tenant,mp,jobId);
        audit(tenant,jobId,completed?"SUCCEEDED":"BATCH_COMMITTED",processed,null);
    }
    private void failure(UUID tenant,UUID mp,UUID jobId,RuntimeException failure) {
        // Error type only; no raw exception messages, SQL, credentials or order IDs.
        String code=failure instanceof ResponseStatusException
            ?"SOURCE_CONFLICT":"BATCH_EVALUATION_FAILED";
        jdbc.update("""
            UPDATE finance_profit_report_job
            SET retry_count=LEAST(retry_count+1,5),
              state=CASE WHEN retry_count>=4 THEN 'PAUSED' ELSE 'RETRY' END,
              failure_code=?,next_retry_at=CASE WHEN retry_count>=4 THEN NULL
                ELSE now()+interval '1 minute'*(retry_count+1) END,
              updated_at=now()
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
              AND state IN ('READY','RETRY')
            """,code,tenant,mp,jobId);
        var item=header(tenant,mp,jobId,false);
        if(item!=null&&("PAUSED".equals(item.state())||"RETRY".equals(item.state())))
            audit(tenant,jobId,"PAUSED".equals(item.state())
                ?"PAUSED":"RETRY_SCHEDULED",item.processed(),code);
    }
    @Transactional
    public Job resume(UUID tenant,UUID mp,UUID id) {
        var existing=header(tenant,mp,id,true);
        if(existing==null)throw notFound();
        if(!"PAUSED".equals(existing.state()))throw new ResponseStatusException(
            HttpStatus.CONFLICT,"Only a paused report may be resumed");
        jdbc.update("""
            UPDATE finance_profit_report_job SET state='READY',
              retry_count=0,failure_code=NULL,next_retry_at=NULL,updated_at=now()
            WHERE tenant_id=? AND seller_marketplace_id=? AND id=?
            """,tenant,mp,id);
        audit(tenant,id,"RESUMED",existing.processed(),null);
        return status(tenant,mp,id);
    }
    @Transactional(readOnly=true)
    public Job status(UUID tenant,UUID mp,UUID id) {
        var rows=jdbc.query("""
            SELECT j.id,j.state,j.period_after,j.period_before,j.currency_code,
              j.total_orders,j.processed_orders,j.retry_count,j.failure_code,
              j.manual_ads_reference,j.manual_ads_count,j.created_at,j.completed_at,
              (SELECT COUNT(*) FROM finance_profit_report_order o
                 WHERE o.tenant_id=j.tenant_id AND o.job_id=j.id
                   AND o.state='ELIGIBLE') AS eligible,
              (SELECT COUNT(*) FROM finance_profit_report_order o
                 WHERE o.tenant_id=j.tenant_id AND o.job_id=j.id
                   AND o.state='BLOCKED') AS blocked
            FROM finance_profit_report_job j
            JOIN tenant t ON t.id=j.tenant_id AND t.status='ACTIVE'
            WHERE j.tenant_id=? AND j.seller_marketplace_id=? AND j.id=?
            """,(r,n)->new Job(r.getObject("id",UUID.class).toString(),
                r.getString("state"),r.getObject("period_after",LocalDate.class).toString(),
                r.getObject("period_before",LocalDate.class).toString(),
                r.getString("currency_code").trim(),r.getInt("total_orders"),
                r.getInt("processed_orders"),r.getInt("eligible"),r.getInt("blocked"),
                r.getInt("retry_count"),r.getString("failure_code"),
                r.getBigDecimal("manual_ads_reference").toPlainString(),
                r.getLong("manual_ads_count"),
                r.getObject("created_at",OffsetDateTime.class).toInstant().toString(),
                r.getObject("completed_at",OffsetDateTime.class)==null?null:
                    r.getObject("completed_at",OffsetDateTime.class).toInstant().toString(),
                true,false,false,false,false),tenant,mp,id);
        if(rows.size()!=1)throw notFound();
        return rows.getFirst();
    }
    @Transactional(readOnly=true)
    public List<Job> recent(UUID tenant,UUID mp) {
        var ids=jdbc.query("""
            SELECT id FROM finance_profit_report_job
            WHERE tenant_id=? AND seller_marketplace_id=?
            ORDER BY created_at DESC,id DESC LIMIT 20
            """,(r,n)->r.getObject(1,UUID.class),tenant,mp);
        return ids.stream().map(id->status(tenant,mp,id)).toList();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report report(UUID tenant,UUID mp,UUID id,int offset) {
        if(offset<0||offset>5000||offset%100!=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Report orders page must be 100-row aligned and <=5000");
        Job job=status(tenant,mp,id);
        var summary=jdbc.query("""
            SELECT COALESCE(SUM(signed_sales),0) AS sales,
              COALESCE(SUM(signed_amazon_fees),0) AS amazon,
              COALESCE(SUM(signed_fba_fees),0) AS fba,
              COALESCE(SUM(provisional_cogs),0) AS cogs,
              COALESCE(SUM(estimated_contribution),0) AS contribution,
              COALESCE(SUM(reviewed_events),0) AS events
            FROM finance_profit_report_order
            WHERE tenant_id=? AND job_id=? AND state='ELIGIBLE'
            """,(r,n)->new Totals(job.currency(),job.eligibleOrders(),
                job.blockedOrders(),r.getInt("events"),
                str(r.getBigDecimal("sales")),str(r.getBigDecimal("amazon")),
                str(r.getBigDecimal("fba")),str(r.getBigDecimal("cogs")),
                str(r.getBigDecimal("contribution"))),tenant,id).getFirst();
        var skus=jdbc.query("""
            SELECT internal_sku,COUNT(*) AS orders,SUM(units) AS units,
              SUM(signed_sales) AS sales,SUM(signed_amazon_fees) AS amazon,
              SUM(signed_fba_fees) AS fba,SUM(provisional_cogs) AS cogs,
              SUM(estimated_contribution) AS contribution
            FROM finance_profit_report_sku
            WHERE tenant_id=? AND job_id=?
            GROUP BY internal_sku ORDER BY internal_sku LIMIT 1001
            """,(r,n)->new Sku(r.getString("internal_sku"),r.getInt("orders"),
                r.getInt("units"),job.currency(),str(r.getBigDecimal("sales")),
                str(r.getBigDecimal("amazon")),str(r.getBigDecimal("fba")),
                str(r.getBigDecimal("cogs")),str(r.getBigDecimal("contribution"))),
            tenant,id);
        if(skus.size()>1000)throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,"More than 1000 SKUs; split period");
        var orders=jdbc.query("""
            SELECT order_id,cohort_position,state,readiness,currency_code,
              signed_sales,signed_amazon_fees,signed_fba_fees,
              provisional_cogs,estimated_contribution,captured_at,extract_sha256
            FROM finance_profit_report_order
            WHERE tenant_id=? AND job_id=?
            ORDER BY cohort_position LIMIT 101 OFFSET ?
            """,(r,n)->new Order(r.getObject("order_id",UUID.class).toString(),
                r.getInt("cohort_position"),r.getString("state"),
                r.getString("readiness"),r.getString("currency_code")==null
                    ?job.currency():r.getString("currency_code").trim(),
                str(r.getBigDecimal("signed_sales")),
                str(r.getBigDecimal("signed_amazon_fees")),
                str(r.getBigDecimal("signed_fba_fees")),
                str(r.getBigDecimal("provisional_cogs")),
                str(r.getBigDecimal("estimated_contribution")),
                r.getObject("captured_at",OffsetDateTime.class)==null?null:
                    r.getObject("captured_at",OffsetDateTime.class).toInstant().toString(),
                r.getString("extract_sha256")),tenant,id,offset);
        return new Report(job,summary,List.copyOf(skus),
            List.copyOf(orders.subList(0,Math.min(100,orders.size()))),
            offset,orders.size()>100);
    }
    @Transactional(readOnly=true)
    public List<Audit> history(UUID tenant,UUID mp,UUID id) {
        status(tenant,mp,id);
        return jdbc.query("""
            SELECT action,processed_orders,failure_code,occurred_at
            FROM finance_profit_report_audit WHERE tenant_id=? AND job_id=?
            ORDER BY occurred_at DESC,id DESC LIMIT 100
            """,(r,n)->new Audit(r.getString(1),r.getInt(2),r.getString(3),
                r.getObject(4,OffsetDateTime.class).toInstant().toString()),tenant,id);
    }
    /** Optional scheduler only advances jobs an Owner already created. */
    public List<UUID[]> due(int limit) {
        return jdbc.query("""
            SELECT j.tenant_id,j.seller_marketplace_id,j.id
            FROM finance_profit_report_job j
            JOIN tenant t ON t.id=j.tenant_id AND t.status='ACTIVE'
            WHERE j.state IN ('READY','RETRY')
              AND (j.next_retry_at IS NULL OR j.next_retry_at<=now())
            ORDER BY j.updated_at,j.id LIMIT ?
            """,(r,n)->new UUID[]{r.getObject(1,UUID.class),
                r.getObject(2,UUID.class),r.getObject(3,UUID.class)},limit);
    }
}
