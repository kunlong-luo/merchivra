package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bounded, on-demand local finance diagnostics. Nothing is persisted,
 * auto-posted, paid, marked settled or sent to Amazon.
 */
@Service
public class FinanceExceptionQueueService {
    private final JdbcTemplate jdbc;
    private final SettlementStatementService settlements;
    private final OrderCostReadinessService costs;

    public FinanceExceptionQueueService(JdbcTemplate jdbc,
        SettlementStatementService settlements,OrderCostReadinessService costs) {
        this.jdbc=jdbc;this.settlements=settlements;this.costs=costs;
    }
    public record Queue(int scannedSourceTransactions,int scannedSettlements,
        int scannedOrders,boolean partialScan,int issueCount,
        List<FinanceExceptionEngine.Finding> findings) {}
    private record Order(UUID id,String reference,String currency) {}

    private static FinanceExceptionEngine.SourceSignal source(ResultSet r,int n)
        throws SQLException {
        return new FinanceExceptionEngine.SourceSignal(
            r.getObject("id",UUID.class).toString(),r.getString("source_event_key"),
            r.getString("currency_code").trim(),r.getBigDecimal("amount"),
            r.getString("external_order_id"),r.getBoolean("order_reviewed"),
            r.getBoolean("settlement_assigned"),r.getLong("leaf_count"),
            r.getLong("missing_amount_count"),r.getLong("currency_mismatch_count"),
            r.getLong("unmapped_count"),r.getBigDecimal("leaf_total"));
    }
    private void ensureMarketplace(UUID tenant,UUID mp) {
        Integer available=jdbc.queryForObject("""
            SELECT count(*) FROM seller_marketplace m JOIN tenant t ON t.id=m.tenant_id
            WHERE m.tenant_id=? AND m.id=? AND t.status='ACTIVE'
            """,Integer.class,tenant,mp);
        if(available==null||available!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    public Queue scan(UUID tenant,UUID mp,int sourceLimit) {
        if(sourceLimit<1||sourceLimit>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose limit 1..100");
        ensureMarketplace(tenant,mp);
        var sources=jdbc.query("""
            SELECT f.id,f.source_event_key,f.currency_code,f.amount,f.external_order_id,
               EXISTS(SELECT 1 FROM finance_event_link_review v
                 JOIN sales_order o ON o.tenant_id=v.tenant_id AND o.id=v.order_id
                 WHERE v.tenant_id=f.tenant_id AND v.event_id=f.id
                   AND v.state='MATCHED' AND o.seller_marketplace_id=f.seller_marketplace_id
                   AND o.data_origin='LIVE') AS order_reviewed,
               EXISTS(SELECT 1 FROM finance_settlement_event_link l
                 WHERE l.tenant_id=f.tenant_id AND l.event_id=f.id) AS settlement_assigned,
               COALESCE(b.leaf_count,0) AS leaf_count,
               COALESCE(b.missing_amount_count,0) AS missing_amount_count,
               COALESCE(b.currency_mismatch_count,0) AS currency_mismatch_count,
               COALESCE(b.unmapped_count,0) AS unmapped_count,
               b.leaf_total
            FROM finance_source_event f
            LEFT JOIN LATERAL (
               SELECT COUNT(*) FILTER (WHERE d.is_leaf) AS leaf_count,
                 COUNT(*) FILTER (WHERE d.is_leaf AND d.original_amount IS NULL)
                   AS missing_amount_count,
                 COUNT(*) FILTER (WHERE d.is_leaf AND d.currency_code IS DISTINCT FROM f.currency_code
                   AND d.original_amount IS NOT NULL) AS currency_mismatch_count,
                 COUNT(*) FILTER (WHERE d.is_leaf AND
                   (rule.id IS NULL OR rule.category='UNCLASSIFIED')) AS unmapped_count,
                 SUM(d.original_amount) FILTER (WHERE d.is_leaf) AS leaf_total
               FROM finance_transaction_breakdown d
               LEFT JOIN finance_breakdown_rule rule
                 ON rule.tenant_id=d.tenant_id
                 AND rule.seller_marketplace_id=f.seller_marketplace_id
                 AND rule.source_type=d.source_type AND rule.state='ACTIVE'
               WHERE d.tenant_id=f.tenant_id AND d.event_id=f.id
            ) b ON true
            WHERE f.tenant_id=? AND f.seller_marketplace_id=?
              AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
            ORDER BY f.posted_at DESC,f.id DESC LIMIT ?
            """,FinanceExceptionQueueService::source,tenant,mp,sourceLimit+1);

        List<FinanceExceptionEngine.Finding> findings=new ArrayList<>();
        int sourceCount=Math.min(sourceLimit,sources.size());
        for(int i=0;i<sourceCount;i++)
            findings.addAll(FinanceExceptionEngine.source(sources.get(i)));

        var batches=settlements.list(tenant,mp);
        int settlementCount=Math.min(20,batches.size());
        for(int i=0;i<settlementCount;i++) {
            var s=batches.get(i);
            var preview=settlements.preview(tenant,mp,UUID.fromString(s.id()));
            findings.addAll(FinanceExceptionEngine.settlement(s.id(),s.reference(),
                s.currency(),preview.status(),preview.signedDifference(),
                preview.unassignedSourceCount()));
        }
        var orders=jdbc.query("""
            SELECT o.id,o.external_order_id,m.currency_code
            FROM sales_order o JOIN seller_marketplace m
              ON m.tenant_id=o.tenant_id AND m.id=o.seller_marketplace_id
            WHERE o.tenant_id=? AND o.seller_marketplace_id=? AND o.data_origin='LIVE'
            ORDER BY o.source_updated_at DESC NULLS LAST,o.id DESC LIMIT 21
            """,(r,i)->new Order(r.getObject(1,UUID.class),r.getString(2),
                r.getString(3).trim()),tenant,mp);
        int orderCount=Math.min(20,orders.size());
        for(int i=0;i<orderCount;i++) {
            var o=orders.get(i);
            findings.addAll(FinanceExceptionEngine.order(o.id().toString(),
                o.reference(),o.currency(),costs.preview(tenant,mp,o.id())));
        }
        findings.sort(Comparator
            .comparingInt((FinanceExceptionEngine.Finding f)->switch(f.severity()) {
                case "BLOCKING" -> 0;
                case "REVIEW" -> 1;
                default -> 2;
            }).thenComparing(FinanceExceptionEngine.Finding::code)
             .thenComparing(FinanceExceptionEngine.Finding::subjectId));
        return new Queue(sourceCount,settlementCount,orderCount,
            sources.size()>sourceLimit||batches.size()>20||orders.size()>20,
            findings.size(),List.copyOf(findings));
    }
}
