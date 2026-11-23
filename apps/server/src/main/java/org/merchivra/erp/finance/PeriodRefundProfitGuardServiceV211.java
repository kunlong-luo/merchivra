package org.merchivra.erp.finance;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * V211: compare immutable V64/V194 frozen order profit with ALL current LIVE
 * Finances PRODUCT_REFUND events by their separate UTC posted-at time axis.
 * No journal writes, no recognition policy and no actual refund COGS.
 *
 * Order V210 probes occur OUTSIDE the period snapshot transaction. Their
 * conflict status remains a 409, never a swallowed rollback-only HTTP 500.
 */
@Service
public class PeriodRefundProfitGuardServiceV211 {
    static final int MAX_PERIOD_SOURCES=1000;
    static final int MAX_REFUND_ORDERS=100;
    static final int MAX_OVERLAPS=100;

    static final String PERIOD_SOURCE_SQL="""
        SELECT f.id,f.posted_at,f.currency_code,f.amount,
          review.matched_order_id,review.match_count,
          EXISTS(SELECT 1 FROM finance_profit_report_order o
            WHERE o.tenant_id=f.tenant_id AND o.job_id=?
              AND o.order_id=review.matched_order_id) AS in_frozen_cohort
        FROM finance_source_event f
        LEFT JOIN LATERAL (
          SELECT min(v.order_id::text)::uuid AS matched_order_id,
            count(*) AS match_count
          FROM finance_event_link_review v
          WHERE v.tenant_id=f.tenant_id AND v.event_id=f.id
            AND v.state='MATCHED'
        ) review ON true
        WHERE f.tenant_id=? AND f.seller_marketplace_id=?
          AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
          AND f.category='PRODUCT_REFUND'
          AND f.posted_at>=? AND f.posted_at<?
        ORDER BY f.id LIMIT 1001
        """;

    // A frozen V64 order may have its refund posted outside V64's order
    // creation window. V34 orphan manual claims also require V210 evaluation.
    // Neither source needs to fall inside the chosen posting-date window.
    static final String REFUND_COHORT_ORDERS_SQL="""
        SELECT o.order_id
        FROM finance_profit_report_order o
        WHERE o.tenant_id=? AND o.job_id=?
          AND (
            EXISTS (SELECT 1 FROM finance_refund_item_review r
              WHERE r.tenant_id=o.tenant_id
                AND r.sales_order_id=o.order_id)
            OR EXISTS (
              SELECT 1 FROM finance_event_link_review v
              JOIN finance_source_event f ON f.tenant_id=v.tenant_id
                AND f.id=v.event_id
              WHERE v.tenant_id=o.tenant_id AND v.order_id=o.order_id
                AND v.state='MATCHED'
                AND f.seller_marketplace_id=?
                AND f.source_family='SPAPI_FINANCES'
                AND f.data_origin='LIVE'
                AND f.category='PRODUCT_REFUND'
            )
          )
        ORDER BY o.order_id LIMIT 101
        """;

    // Report jobs are frozen SNAPSHOTS, not accounting journal entries.
    // This is a caution against summing two exported overlapping reports.
    static final String OTHER_REPORT_OVERLAPS_SQL="""
        SELECT DISTINCT older.job_id,older.order_id
        FROM finance_profit_report_order current_order
        JOIN finance_profit_report_order older
          ON older.tenant_id=current_order.tenant_id
          AND older.order_id=current_order.order_id
          AND older.job_id<>current_order.job_id
        JOIN finance_profit_report_job j
          ON j.tenant_id=older.tenant_id AND j.id=older.job_id
        WHERE current_order.tenant_id=? AND current_order.job_id=?
          AND j.seller_marketplace_id=? AND j.period_after<?
          AND j.period_before>?
        ORDER BY older.job_id,older.order_id LIMIT 101
        """;

    record Frozen(List<PeriodRefundProfitGuardV211.Source> period,
        List<UUID> refundOrders,
        List<PeriodRefundProfitGuardV211.SnapshotOverlap> overlaps) {}

    private final JdbcTemplate jdbc;
    private final PeriodProfitWaterfallServiceV188 waterfall;
    private final RefundOrderConservationServiceV210 refundOrders;
    private final TransactionTemplate frozenTx;

    public PeriodRefundProfitGuardServiceV211(JdbcTemplate jdbc,
        PeriodProfitWaterfallServiceV188 waterfall,
        RefundOrderConservationServiceV210 refundOrders,
        PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.waterfall=waterfall;this.refundOrders=refundOrders;
        this.frozenTx=new TransactionTemplate(manager);
        this.frozenTx.setReadOnly(true);
        this.frozenTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private static void bounded(String axis,int found,int maximum) {
        if(found>maximum)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Full-period "+axis+" exceeds "+maximum+
                    " rows. Split the date window; partial refund profit is forbidden");
    }

    private Frozen freeze(UUID tenant,UUID marketplace,UUID jobId,
        LocalDate after,LocalDate before) {
        return frozenTx.execute(ignored->{
            var period=jdbc.query(PERIOD_SOURCE_SQL,
                (r,n)->new PeriodRefundProfitGuardV211.Source(
                    r.getObject("id",UUID.class),
                    r.getObject("matched_order_id",UUID.class),
                    r.getObject("posted_at",OffsetDateTime.class).toInstant(),
                    r.getString("currency_code").trim(),r.getBigDecimal("amount"),
                    r.getLong("match_count"),r.getBoolean("in_frozen_cohort")),
                jobId,tenant,marketplace,
                after.atStartOfDay().atOffset(ZoneOffset.UTC),
                before.atStartOfDay().atOffset(ZoneOffset.UTC));
            bounded("posted LIVE refund sources",period.size(),MAX_PERIOD_SOURCES);

            var cohort=jdbc.query(REFUND_COHORT_ORDERS_SQL,
                (r,n)->r.getObject("order_id",UUID.class),tenant,jobId,marketplace);
            bounded("V210 refund-bearing frozen orders",cohort.size(),MAX_REFUND_ORDERS);

            var overlaps=jdbc.query(OTHER_REPORT_OVERLAPS_SQL,
                (r,n)->new PeriodRefundProfitGuardV211.SnapshotOverlap(
                    r.getObject("job_id",UUID.class),
                    r.getObject("order_id",UUID.class)),
                tenant,jobId,marketplace,before,after);
            bounded("intersecting V64 report snapshots",overlaps.size(),MAX_OVERLAPS);
            return new Frozen(List.copyOf(period),List.copyOf(cohort),
                List.copyOf(overlaps));
        });
    }

    public PeriodRefundProfitGuardV211.Result preview(UUID tenant,
        UUID marketplace,UUID jobId) {
        if(tenant==null||marketplace==null||jobId==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Tenant, Marketplace and frozen report job ID required");
        // Existing V188 performs the scoped job lookup, V194 fingerprint
        // verification, original-currency arithmetic and V165/168 blockers.
        // A stale/unversioned V64 capture already fails closed.
        var baseline=waterfall.preview(tenant,marketplace,jobId);
        LocalDate after=LocalDate.parse(baseline.periodStart());
        LocalDate before=LocalDate.parse(baseline.periodEndExclusive());
        Frozen frozen=freeze(tenant,marketplace,jobId,after,before);

        var reviewed=new ArrayList<RefundOrderConservationEngineV210.Result>();
        for(UUID order:frozen.refundOrders()) {
            try {
                reviewed.add(refundOrders.preview(tenant,marketplace,order));
            } catch(ResponseStatusException normalGap) {
                if(normalGap.getStatusCode()!=HttpStatus.NOT_FOUND
                    &&normalGap.getStatusCode()!=HttpStatus.CONFLICT)throw normalGap;
                // Don't convert a changed LIVE order or source into zero cost,
                // and don't poison an outer transaction by catching 409.
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A frozen refund-bearing order is stale or lacks current V210 evidence");
            }
        }
        try {
            return PeriodRefundProfitGuardV211.calculate(marketplace,jobId,
                baseline,frozen.period(),frozen.refundOrders(),reviewed,
                frozen.overlaps());
        } catch(IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Period refund snapshot or V210 identity changed; no monetary rollup",
                invalid);
        }
    }
}
