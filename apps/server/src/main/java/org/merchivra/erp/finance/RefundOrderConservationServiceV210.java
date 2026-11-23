package org.merchivra.erp.finance;

import java.time.OffsetDateTime;
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
 * V210: full-order reconciliation of ALL matched LIVE PRODUCT_REFUND events,
 * current order-line quantities and EVERY V34 manual refund quantity review.
 * Never totals the currently displayed 50-event page as if it were the order.
 *
 * V63 preview is evaluated AFTER the repeatable-read source transaction.
 * Its expected 404/409 business exceptions cannot poison the snapshot
 * transaction and turn a missing refund into HTTP 500.
 *
 * No ledger writes, no Amazon network, no actual COGS recognition.
 */
@Service
public class RefundOrderConservationServiceV210 {
    static final int MAX_ROWS = 1000;

    static final String MATCHED_REFUNDS_SQL = """
        SELECT f.id,f.posted_at,f.currency_code,f.amount,v.order_id
        FROM finance_source_event f
        JOIN finance_event_link_review v
          ON v.tenant_id=f.tenant_id AND v.event_id=f.id AND v.state='MATCHED'
        WHERE f.tenant_id=? AND f.seller_marketplace_id=?
          AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
          AND f.category='PRODUCT_REFUND' AND v.order_id=?
        ORDER BY f.id LIMIT 1001
        """;
    static final String ALL_ORDER_MANUAL_REVIEWS_SQL = """
        SELECT r.event_id,r.external_item_id,r.reviewed_quantity,r.disposition
        FROM finance_refund_item_review r
        WHERE r.tenant_id=? AND r.sales_order_id=?
        ORDER BY r.event_id,r.external_item_id LIMIT 1001
        """;
    static final String ALL_LIVE_ORDER_ITEMS_SQL = """
        SELECT i.external_item_id,i.quantity
        FROM order_item i
        WHERE i.tenant_id=? AND i.sales_order_id=?
          AND i.is_current=true AND i.data_origin='LIVE'
        ORDER BY i.external_item_id LIMIT 1001
        """;
    private record Snapshot(List<RefundOrderConservationEngineV210.SaleItem> items,
        List<RefundOrderConservationEngineV210.ReviewItem> reviews,
        List<RefundOrderConservationEngineV210.Source> sources) {}

    private final JdbcTemplate jdbc;
    private final RefundRecoveryService recovery;
    private final TransactionTemplate frozenSource;

    public RefundOrderConservationServiceV210(JdbcTemplate jdbc,
        RefundRecoveryService recovery,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.recovery=recovery;
        this.frozenSource=new TransactionTemplate(manager);
        this.frozenSource.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.frozenSource.setReadOnly(true);
    }

    private static void bounded(String dimension,int count) {
        if(count>MAX_ROWS)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Whole-order "+dimension+" exceeds exact 1000-row evidence bound: "
                    +"no partial refund or COGS sum may be displayed");
    }

    private Snapshot snapshot(UUID tenant,UUID marketplace,UUID order) {
        return frozenSource.execute(ignored->{
            var scoped=jdbc.queryForList("""
                SELECT o.id FROM sales_order o
                JOIN tenant t ON t.id=o.tenant_id AND t.status='ACTIVE'
                JOIN seller_marketplace m
                  ON m.tenant_id=o.tenant_id AND m.id=o.seller_marketplace_id
                JOIN seller_account a ON a.tenant_id=m.tenant_id
                  AND a.id=m.seller_account_id
                WHERE o.tenant_id=? AND o.seller_marketplace_id=?
                  AND o.id=? AND o.data_origin='LIVE'
                """,UUID.class,tenant,marketplace,order);
            if(scoped.size()!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "LIVE order not available in tenant marketplace");

            var items=jdbc.query(ALL_LIVE_ORDER_ITEMS_SQL,
                (r,i)->new RefundOrderConservationEngineV210.SaleItem(
                    r.getString("external_item_id"),
                    r.getObject("quantity",Integer.class)),tenant,order);
            bounded("sale items",items.size());

            var allReviews=jdbc.query(ALL_ORDER_MANUAL_REVIEWS_SQL,
                (r,i)->new RefundOrderConservationEngineV210.ReviewItem(
                    r.getObject("event_id",UUID.class),
                    r.getString("external_item_id"),
                    r.getInt("reviewed_quantity"),r.getString("disposition")),
                tenant,order);
            bounded("manual refund item reviews",allReviews.size());

            var events=jdbc.query(MATCHED_REFUNDS_SQL,
                (r,i)->new RefundOrderConservationEngineV210.Source(
                    r.getObject("id",UUID.class),
                    r.getObject("order_id",UUID.class),
                    r.getObject("posted_at",OffsetDateTime.class).toInstant(),
                    r.getString("currency_code").trim(),r.getBigDecimal("amount")),
                tenant,marketplace,order);
            bounded("matched LIVE refund events",events.size());
            return new Snapshot(List.copyOf(items),List.copyOf(allReviews),
                List.copyOf(events));
        });
    }

    public RefundOrderConservationEngineV210.Result preview(UUID tenant,
        UUID marketplace,UUID order) {
        if(tenant==null||marketplace==null||order==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Tenant, marketplace and LIVE order identifiers required");
        Snapshot frozen=snapshot(tenant,marketplace,order);
        var probes=new ArrayList<RefundOrderConservationEngineV210.Probe>();
        for(var event:frozen.sources()) {
            RefundRecoveryService.Status reviewed;
            try {
                reviewed=recovery.preview(tenant,marketplace,event.eventId());
            } catch(ResponseStatusException error) {
                if(error.getStatusCode()!=HttpStatus.CONFLICT
                    &&error.getStatusCode()!=HttpStatus.NOT_FOUND)
                    throw error;
                // Missing or stale V34/V35/V63 must remain an evidence gap,
                // not a fabricated zero or rolled-back aggregate snapshot.
                reviewed=null;
            }
            probes.add(new RefundOrderConservationEngineV210.Probe(event,reviewed));
        }
        try {
            return RefundOrderConservationEngineV210.calculate(order,
                frozen.items(),frozen.reviews(),probes);
        } catch(IllegalArgumentException inconsistent) {
            // A duplicated LIVE item/source identity or impossible review
            // is an explicit business conflict, not an opaque HTTP 500.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Whole-order refund source or item identities inconsistent",
                inconsistent);
        }
    }
}
