package org.merchivra.erp.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V70: conservative observations of ordered item quantities over a bounded
 * 28-day window. This is NOT a demand forecast, replenishment order quantity,
 * supplier commitment, settlement or Amazon API write. Never fill missing
 * OrderItems or an incomplete sync window with synthetic zero-sales days.
 */
@Service
public class ReplenishmentDemandService {
    private static final Duration LOOKBACK=Duration.ofDays(28);
    private static final Duration LAG=Duration.ofMinutes(30);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public ReplenishmentDemandService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }
    ReplenishmentDemandService(JdbcTemplate jdbc,Clock clock) {
        this.jdbc=jdbc;
        this.clock=clock;
    }

    public record Signal(String sellerSku,String status,String sourceMode,
                         String windowStart,String windowEnd,int leadTimeDays,
                         Long observedOrderCount,Long incompleteOrderCount,
                         Long observedUnits7d,Long observedUnits28d,
                         String observedDailyUnits28d,Long illustrativeLeadTimeUnits) {}
    public record Calculated(String status,Long units7d,Long units28d,
                             String dailyUnits,Long leadTimeUnits) {}
    private record Row(String sku,int leadDays,boolean mapped,String mode,
                       String jobState,Instant after,Instant before,Instant watermark,
                       boolean hasPage,long orders,long missingOrders,long badLines,
                       long wrongSource,long units7,long units28) {}

    /**
     * "Full" means a committed job claims to have covered the WHOLE window.
     * This is necessary, not sufficient, for an accurate demand forecast:
     * cancellations, refunds and real-world latency are NOT subtracted.
     */
    static Calculated interpret(boolean mapped,boolean complete,String mode,
        long orders,long missingOrders,long invalidLines,long wrongSource,
        long units7,long units28,int leadDays) {
        if(!mapped)return new Calculated("UNMAPPED",null,null,null,null);
        if(!complete || (!"MOCK".equals(mode) && !"LIVE".equals(mode)))
            return new Calculated("INCOMPLETE_COVERAGE",null,null,null,null);
        if(missingOrders>0 || invalidLines>0 || wrongSource>0 || units7<0 ||
           units28<0 || units7>units28)
            return new Calculated("INCOMPLETE_ITEMS",null,null,null,null);
        if(orders==0)return new Calculated("NO_OBSERVED_ORDERS",0L,0L,null,null);
        BigDecimal daily=BigDecimal.valueOf(units28).divide(
            BigDecimal.valueOf(28),3,RoundingMode.HALF_UP);
        // Advisory quantity only, never passed into the V37 purchase draft flow.
        BigDecimal leadUnits=BigDecimal.valueOf(units28)
            .multiply(BigDecimal.valueOf(leadDays))
            .divide(BigDecimal.valueOf(28),0,RoundingMode.CEILING);
        Long display=leadUnits.compareTo(BigDecimal.valueOf(1_000_000))>0
            ?null:leadUnits.longValueExact();
        return new Calculated("MOCK".equals(mode)?"MOCK_SAMPLE":"OBSERVED_ONLY",
            units7,units28,daily.toPlainString(),display);
    }

    private static Instant instant(java.sql.ResultSet rs,String name)
        throws java.sql.SQLException {
        var value=rs.getObject(name,OffsetDateTime.class);
        return value==null?null:value.toInstant();
    }
    @Transactional(readOnly=true)
    public List<Signal> signals(UUID tenant,UUID marketplace) {
        Integer exists=jdbc.queryForObject("""
            SELECT COUNT(*) FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            WHERE m.tenant_id=? AND m.id=?
            """,Integer.class,tenant,marketplace);
        if(exists==null||exists!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Marketplace not found");

        Instant now=clock.instant();
        Instant end=now.minus(LAG);
        Instant start=end.minus(LOOKBACK);
        Instant lastWeek=end.minus(Duration.ofDays(7));
        // One bounded query for all policy SKUs; no per-SKU N+1 HTTP/API calls.
        List<Row> rows=jdbc.query("""
            SELECT p.seller_sku,p.lead_time_days,
              (s.id IS NOT NULL) AS mapped,
              job.mode,job.state AS job_state,job.window_after,job.window_before,
              job.last_safe_watermark,
              EXISTS(SELECT 1 FROM orders_sync_page_commit pg
                 WHERE pg.tenant_id=job.tenant_id AND pg.job_id=job.id
                   AND pg.generation=job.generation) AS has_page,
              observations.order_count,observations.missing_orders,
              observations.bad_lines,observations.wrong_source,
              observations.units_7d,observations.units_28d
            FROM erp_replenishment_policy p
            LEFT JOIN marketplace_listing l
              ON l.tenant_id=p.tenant_id AND l.seller_marketplace_id=p.seller_marketplace_id
              AND l.seller_sku=p.seller_sku AND l.mapping_status='ACTIVE'
            LEFT JOIN listing_sku_mapping mapping
              ON mapping.tenant_id=l.tenant_id AND mapping.listing_id=l.id
              AND mapping.status='ACTIVE' AND mapping.valid_to IS NULL
            LEFT JOIN product_sku s
              ON s.tenant_id=mapping.tenant_id AND s.id=mapping.product_sku_id
              AND s.status='ACTIVE'
            LEFT JOIN orders_sync_job job
              ON job.tenant_id=p.tenant_id
              AND job.seller_marketplace_id=p.seller_marketplace_id
            LEFT JOIN LATERAL (
              SELECT COUNT(DISTINCT o.id) AS order_count,
                COUNT(DISTINCT o.id) FILTER
                  (WHERE o.items_observation<>'OBSERVED') AS missing_orders,
                COUNT(*) FILTER
                  (WHERE i.id IS NOT NULL AND
                    (i.quantity IS NULL OR i.seller_sku_raw IS NULL OR
                     i.data_origin<>o.data_origin)) AS bad_lines,
                COUNT(DISTINCT o.id) FILTER
                  (WHERE o.data_origin IS DISTINCT FROM job.mode) AS wrong_source,
                COALESCE(SUM(i.quantity) FILTER
                  (WHERE i.seller_sku_raw=p.seller_sku
                     AND o.data_origin=job.mode AND i.data_origin=job.mode
                     AND o.items_observation='OBSERVED' AND o.order_time>=?),0)::bigint
                  AS units_7d,
                COALESCE(SUM(i.quantity) FILTER
                  (WHERE i.seller_sku_raw=p.seller_sku
                     AND o.data_origin=job.mode AND i.data_origin=job.mode
                     AND o.items_observation='OBSERVED'),0)::bigint AS units_28d
              FROM sales_order o
              LEFT JOIN order_item i
                ON i.tenant_id=o.tenant_id AND i.sales_order_id=o.id
                AND i.is_current=true
              WHERE o.tenant_id=p.tenant_id
                AND o.seller_marketplace_id=p.seller_marketplace_id
                AND o.order_time>=? AND o.order_time<?
            ) observations ON TRUE
            WHERE p.tenant_id=? AND p.seller_marketplace_id=?
            ORDER BY p.seller_sku LIMIT 500
            """,(rs,i)->new Row(rs.getString("seller_sku"),rs.getInt("lead_time_days"),
                rs.getBoolean("mapped"),rs.getString("mode"),rs.getString("job_state"),
                instant(rs,"window_after"),instant(rs,"window_before"),
                instant(rs,"last_safe_watermark"),rs.getBoolean("has_page"),
                rs.getLong("order_count"),rs.getLong("missing_orders"),
                rs.getLong("bad_lines"),rs.getLong("wrong_source"),
                rs.getLong("units_7d"),rs.getLong("units_28d")),
            OffsetDateTime.ofInstant(lastWeek,ZoneOffset.UTC),
            OffsetDateTime.ofInstant(start,ZoneOffset.UTC),
            OffsetDateTime.ofInstant(end,ZoneOffset.UTC),tenant,marketplace);
        return rows.stream().map(row->{
            boolean complete="SUCCEEDED".equals(row.jobState()) &&
                row.hasPage() && row.after()!=null && row.before()!=null &&
                row.watermark()!=null && !row.after().isAfter(start) &&
                !row.before().isBefore(end) && !row.watermark().isBefore(end);
            Calculated result=interpret(row.mapped(),complete,row.mode(),
                row.orders(),row.missingOrders(),row.badLines(),row.wrongSource(),
                row.units7(),row.units28(),row.leadDays());
            return new Signal(row.sku(),result.status(),row.mode(),
                start.toString(),end.toString(),row.leadDays(),
                complete?row.orders():null,complete?row.missingOrders():null,
                result.units7d(),result.units28d(),result.dailyUnits(),
                result.leadTimeUnits());
        }).toList();
    }
}
