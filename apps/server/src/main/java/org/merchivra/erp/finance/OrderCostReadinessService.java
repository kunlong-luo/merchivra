package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Source- and time-aware COGS read model. This NEVER deducts COGS from
 * Finances transactions or converts it into Amazon settlement/profit.
 */
@Service
public class OrderCostReadinessService {
    private final JdbcTemplate jdbc;
    public OrderCostReadinessService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    private record Header(String dataOrigin,String observation,Instant createdAt,
                          String currency,String marketplaceTimezone) {}

    private static Instant time(java.sql.ResultSet rs,String column)
        throws java.sql.SQLException {
        OffsetDateTime t=rs.getObject(column,OffsetDateTime.class);
        return t==null?null:t.toInstant();
    }

    public OrderCostEngine.Preview preview(UUID tenant,UUID marketplace,UUID order) {
        List<Header> header=jdbc.query("""
            SELECT o.data_origin,o.items_observation,o.order_time,
              m.currency_code,m.timezone
            FROM sales_order o
            JOIN seller_marketplace m
              ON m.tenant_id=o.tenant_id AND m.id=o.seller_marketplace_id
            JOIN tenant t ON t.id=o.tenant_id
            WHERE o.tenant_id=? AND o.seller_marketplace_id=? AND o.id=?
              AND t.status='ACTIVE'
            """,(rs,n)->new Header(rs.getString("data_origin"),
                rs.getString("items_observation"),time(rs,"order_time"),
                rs.getString("currency_code").trim(),rs.getString("timezone")),
            tenant,marketplace,order);
        if(header.size()!=1)throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,"Order unavailable in selected marketplace");
        Header h=header.getFirst();
        LocalDate localDate=null;
        if(h.createdAt()!=null) {
            try { localDate=h.createdAt().atZone(
                ZoneId.of(h.marketplaceTimezone())).toLocalDate(); }
            catch(java.time.DateTimeException invalid) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Marketplace timezone must be reviewed before cost recognition");
            }
        }
        // Even when the business date is unknown, return each current line's
        // readiness reason; no speculative date-based cost joins are accepted.
        var items=jdbc.query("""
            SELECT i.external_item_id,i.seller_sku_raw,i.quantity,i.data_origin,
              p.internal_sku,c.unit_cost,c.currency_code
            FROM order_item i
            JOIN sales_order o ON o.tenant_id=i.tenant_id AND o.id=i.sales_order_id
            LEFT JOIN marketplace_listing l ON l.tenant_id=o.tenant_id
              AND l.seller_marketplace_id=o.seller_marketplace_id
              AND l.seller_sku=i.seller_sku_raw
            LEFT JOIN LATERAL (
              SELECT m.product_sku_id FROM listing_sku_mapping m
              WHERE m.tenant_id=o.tenant_id AND m.listing_id=l.id
                AND m.status IN ('ACTIVE','SUPERSEDED')
                AND m.valid_from<=o.order_time
                AND (m.valid_to IS NULL OR m.valid_to>o.order_time)
              ORDER BY m.mapping_version DESC LIMIT 1
            ) match ON TRUE
            LEFT JOIN product_sku p ON p.tenant_id=o.tenant_id AND p.id=match.product_sku_id
            LEFT JOIN LATERAL (
              SELECT unit_cost,currency_code FROM sku_cost_version v
              WHERE v.tenant_id=o.tenant_id AND v.product_sku_id=p.id
                AND v.effective_from<=CAST(? AS date)
                AND (v.effective_to IS NULL OR v.effective_to>CAST(? AS date))
              ORDER BY v.effective_from DESC LIMIT 1
            ) c ON TRUE
            WHERE i.tenant_id=? AND o.seller_marketplace_id=? AND o.id=?
              AND i.is_current=true
            ORDER BY i.external_item_id LIMIT 1001
            """,(rs,n)->new OrderCostEngine.Item(
                rs.getString("external_item_id"),rs.getString("seller_sku_raw"),
                rs.getObject("quantity",Integer.class),rs.getString("data_origin"),
                rs.getString("internal_sku"),rs.getBigDecimal("unit_cost"),
                rs.getString("currency_code")==null?null:
                    rs.getString("currency_code").trim()),
            localDate==null?null:Date.valueOf(localDate),
            localDate==null?null:Date.valueOf(localDate),
            tenant,marketplace,order);
        return OrderCostEngine.evaluate("LIVE".equals(h.dataOrigin()),
            "OBSERVED".equals(h.observation()),h.createdAt(),h.currency(),items);
    }
}
