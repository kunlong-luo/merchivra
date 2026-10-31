package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * A narrowly bounded source-backed estimate. Requires explicit human-reviewed
 * event-to-order links, fully reviewed source breakdowns and date-valid SKU cost.
 */
@Service
public class OrderContributionService {
    private final JdbcTemplate jdbc;
    private final OrderCostReadinessService costs;
    private final FinanceClassificationService classification;

    public OrderContributionService(JdbcTemplate jdbc,OrderCostReadinessService costs,
        FinanceClassificationService classification) {
        this.jdbc=jdbc;this.costs=costs;this.classification=classification;
    }
    private record Order(String externalId,String currency) {}
    public OrderContributionEngine.Preview preview(UUID tenant,UUID marketplace,UUID orderId) {
        List<Order> orders=jdbc.query("""
            SELECT o.external_order_id,m.currency_code
            FROM sales_order o JOIN seller_marketplace m
              ON m.tenant_id=o.tenant_id AND m.id=o.seller_marketplace_id
            JOIN tenant t ON t.id=o.tenant_id
            WHERE o.tenant_id=? AND o.seller_marketplace_id=? AND o.id=?
              AND t.status='ACTIVE'
            """,(r,i)->new Order(r.getString(1),r.getString(2).trim()),
            tenant,marketplace,orderId);
        if(orders.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Order order=orders.getFirst();
        OrderCostEngine.Preview unitCosts=costs.preview(tenant,marketplace,orderId);

        // A source Order ID is a hint, not a human-reviewed financial link.
        Integer pending=jdbc.queryForObject("""
            SELECT count(*) FROM finance_source_event f
            WHERE f.tenant_id=? AND f.seller_marketplace_id=?
              AND f.source_family='SPAPI_FINANCES' AND f.data_origin='LIVE'
              AND f.external_order_id=?
              AND NOT EXISTS (
                SELECT 1 FROM finance_event_link_review v
                WHERE v.tenant_id=f.tenant_id AND v.event_id=f.id
                  AND v.state='MATCHED' AND v.order_id=?
              )
            """,Integer.class,tenant,marketplace,order.externalId(),orderId);
        var eventIds=jdbc.query("""
            SELECT f.id FROM finance_event_link_review v
            JOIN finance_source_event f ON f.tenant_id=v.tenant_id AND f.id=v.event_id
            WHERE v.tenant_id=? AND v.order_id=? AND v.state='MATCHED'
              AND f.seller_marketplace_id=? AND f.source_family='SPAPI_FINANCES'
              AND f.data_origin='LIVE'
            ORDER BY f.posted_at,f.id LIMIT 101
            """,(r,i)->r.getObject(1,UUID.class),tenant,orderId,marketplace);
        boolean truncated=eventIds.size()>100;
        var reviewed=new ArrayList<OrderContributionEngine.Transaction>();
        for(int i=0;i<Math.min(100,eventIds.size());i++) {
            UUID eventId=eventIds.get(i);
            var status=classification.preview(tenant,marketplace,eventId);
            var amounts=new ArrayList<OrderContributionEngine.CategoryAmount>();
            if(status.analysis()!=null) {
                for(var item:status.analysis().provisionalTotals()) {
                    amounts.add(new OrderContributionEngine.CategoryAmount(item.category(),
                        item.currency(),new BigDecimal(item.signedAmount())));
                }
            }
            reviewed.add(new OrderContributionEngine.Transaction(eventId.toString(),
                status.status(),List.copyOf(amounts)));
        }
        return OrderContributionEngine.calculate(order.currency(),unitCosts,reviewed,
            pending==null?0:pending,truncated);
    }
}
