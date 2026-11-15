package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * V170: source-level refund cost/reversal risk queue, not an accounting feed.
 * Reuses V63's fingerprinted maker/checker evidence; "REVIEWED" is still
 * MANUAL_UNVERIFIED, and never allows actual COGS reversal or ledger changes.
 */
@Service
public class RefundCostReadinessService {
    private final JdbcTemplate jdbc;
    private final RefundRecoveryService recovery;
    private final TransactionTemplate snapshotTx;
    private static final int PAGE_SIZE=50;

    public RefundCostReadinessService(JdbcTemplate jdbc,
        RefundRecoveryService recovery,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.recovery=recovery;
        this.snapshotTx=new TransactionTemplate(manager);
        this.snapshotTx.setReadOnly(true);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public record CurrencyBucket(String currency,long sourceEventCount,
        String signedProductRefundAmount,boolean completeRefundCoverage,
        boolean verifiedCogsReversal) {}
    public record Event(String eventId,String postedAtUtc,String currency,
        String signedSourceRefundAmount,String reviewedOrderId,
        String evidenceState,String evidenceGap,
        String historicalCostExposure,String claimedRestockCostSensitivity,
        boolean evidenceCurrent,boolean externalReceiptVerified,
        boolean actualCogsReversalAllowed,String actualCogsReversal) {}
    public record Page(String marketplaceId,String periodStartUtc,
        String periodEndExclusiveUtc,List<CurrencyBucket> buckets,
        List<Event> events,boolean hasMore,String nextBeforeAt,String nextBeforeId,
        boolean allFinancesRefundsCovered,boolean bankReconciled,
        boolean actualCogsReversalsVerified,boolean realizedNetProfitVerified) {}

    private record Source(UUID id,OffsetDateTime postedAt,String currency,
        BigDecimal signedAmount,UUID order) {}
    private record Snapshot(List<CurrencyBucket> buckets,List<Source> visible,
        boolean hasMore) {}
    private static Source row(ResultSet r,int ignored)throws SQLException {
        return new Source(r.getObject("id",UUID.class),
            r.getObject("posted_at",OffsetDateTime.class),
            r.getString("currency_code").trim(),r.getBigDecimal("amount"),
            r.getObject("order_id",UUID.class));
    }
    static void validate(LocalDate from,LocalDate until,Instant cursor,UUID cursorId) {
        if(from==null||until==null||!from.isBefore(until)
            ||ChronoUnit.DAYS.between(from,until)>366
            ||(cursor==null)!=(cursorId==null)
            ||cursor!=null&&(cursor.isBefore(from.atStartOfDay().toInstant(ZoneOffset.UTC))
                ||!cursor.isBefore(until.atStartOfDay().toInstant(ZoneOffset.UTC))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Require a half-open UTC period <=366 days and a complete in-range cursor");
    }
    static String amount(BigDecimal x) {
        return x.setScale(6).toPlainString();
    }
    private static CurrencyBucket bucket(ResultSet r,int i)throws SQLException {
        return new CurrencyBucket(r.getString("currency_code").trim(),
            r.getLong("event_count"),amount(r.getBigDecimal("signed_refunds")),
            false,false);
    }
    private Event describe(UUID tenant,UUID mp,Source src) {
        String state="INCOMPLETE_EVIDENCE",gap="V34_V35_OR_HISTORICAL_COST_NOT_READY";
        String exposure=null,sensitivity=null;
        boolean current=false;
        try {
            var status=recovery.preview(tenant,mp,src.id());
            state=status.evidenceCurrent()?status.state():"STALE";
            if("NOT_REQUESTED".equals(status.state()))
                state="NOT_REQUESTED";
            if(status.exposure()!=null
                &&"MANUAL_EVIDENCE_BALANCED_UNVERIFIED".equals(
                    status.exposure().status())) {
                exposure=status.exposure().historicalCostExposure();
                sensitivity=status.exposure().claimedRestockCostSensitivity();
                gap=status.evidenceCurrent()
                    ?"PHYSICAL_RECEIPT_AND_ACCOUNTING_NOT_VERIFIED"
                    :"INDEPENDENT_CURRENT_REFUND_REVIEW_REQUIRED";
            }
            current=status.evidenceCurrent();
            // An old REVIEWED V63 record can become STALE after V34/V35
            // revisions; neither state proves a physical return receipt.
        } catch(ResponseStatusException exception) {
            if(exception.getStatusCode()!=HttpStatus.CONFLICT
                &&exception.getStatusCode()!=HttpStatus.NOT_FOUND)
                throw exception;
            // An unmatched, uncategorized or historically incomplete LIVE
            // event is a visible risk, not an omitted/zero-cost event.
        }
        return new Event(src.id().toString(),
            src.postedAt().toInstant().toString(),src.currency(),
            amount(src.signedAmount()),src.order()==null?null:src.order().toString(),
            state,gap,exposure,sensitivity,current,false,false,null);
    }

    public Page report(UUID tenant,UUID mp,LocalDate from,LocalDate until,
        Instant cursor,UUID cursorId) {
        validate(from,until,cursor,cursorId);
        // V63.preview can throw a business 409 inside its own @Transactional
        // method. Calling it under the aggregate read transaction would mark
        // that entire transaction rollback-only even if the 409 is caught,
        // resulting in HTTP 500 (UnexpectedRollbackException). Freeze only
        // the two source SELECTs here, then probe V63 OUTSIDE that transaction.
        Snapshot snapshot=snapshotTx.execute(ignored->{
        var available=jdbc.query("""
            SELECT m.id FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            JOIN seller_account a ON a.tenant_id=m.tenant_id
                AND a.id=m.seller_account_id
            WHERE m.tenant_id=? AND m.id=?
            """,(r,i)->r.getObject(1,UUID.class),tenant,mp);
        if(available.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        OffsetDateTime start=from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end=until.atStartOfDay().atOffset(ZoneOffset.UTC);
        // Aggregate ALL matching source-level LIVE events across the period;
        // this is NOT every refund hidden inside an unclassified breakdown,
        // and does not claim complete official Amazon refund coverage.
        List<CurrencyBucket> buckets=jdbc.query("""
            SELECT currency_code,count(*) AS event_count,
              sum(amount) AS signed_refunds
            FROM finance_source_event
            WHERE tenant_id=? AND seller_marketplace_id=?
              AND source_family='SPAPI_FINANCES' AND data_origin='LIVE'
              AND category='PRODUCT_REFUND'
              AND posted_at>=? AND posted_at<?
            GROUP BY currency_code ORDER BY currency_code
            """,RefundCostReadinessService::bucket,tenant,mp,start,end);
        String sql="""
            SELECT f.id,f.posted_at,f.currency_code,f.amount,
              (SELECT v.order_id FROM finance_event_link_review v
                WHERE v.tenant_id=f.tenant_id AND v.event_id=f.id
                  AND v.state='MATCHED') AS order_id
            FROM finance_source_event f
            WHERE f.tenant_id=? AND f.seller_marketplace_id=?
              AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
              AND f.category='PRODUCT_REFUND'
              AND f.posted_at>=? AND f.posted_at<?
            """+(cursor==null?"":"""
              AND (f.posted_at,f.id)<(?,?)
            """)+"""
            ORDER BY f.posted_at DESC,f.id DESC LIMIT 51
            """;
        List<Source> found=cursor==null
            ?jdbc.query(sql,RefundCostReadinessService::row,tenant,mp,start,end)
            :jdbc.query(sql,RefundCostReadinessService::row,tenant,mp,
                start,end,cursor.atOffset(ZoneOffset.UTC),cursorId);
        boolean more=found.size()>PAGE_SIZE;
        List<Source> visible=found.subList(0,Math.min(found.size(),PAGE_SIZE));
        return new Snapshot(List.copyOf(buckets),List.copyOf(visible),more);
        });
        // A missing refund item/order is a normal business gap, not a reason
        // to roll back the already-read page. Each V63 preview now owns an
        // independent read transaction, and 409/404 can be rendered as
        // INCOMPLETE_EVIDENCE without poisoning the source snapshot.
        var events=new ArrayList<Event>();
        for(var source:snapshot.visible())events.add(describe(tenant,mp,source));
        Source last=snapshot.hasMore()?snapshot.visible().getLast():null;
        return new Page(mp.toString(),from.toString(),until.toString(),
            snapshot.buckets(),List.copyOf(events),snapshot.hasMore(),
            last==null?null:last.postedAt().toInstant().toString(),
            last==null?null:last.id().toString(),false,false,false,false);
    }
}
