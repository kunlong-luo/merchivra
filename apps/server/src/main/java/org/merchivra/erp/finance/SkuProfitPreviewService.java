package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant+Marketplace scoped bounded page, avoiding a second parallel
 * profitability model. Every eligible figure is from OrderContributionService,
 * never Orders estimated proceeds, Reports amounts, bank records or Ads API.
 */
@Service
public class SkuProfitPreviewService {
    public static final int PAGE_SIZE=30;
    public static final int MAX_OFFSET=3000;
    private final JdbcTemplate jdbc;
    private final OrderCostReadinessService costs;
    private final OrderContributionService contribution;
    private final Clock clock;
    private final MultiSkuContributionService multiSku;

    @Autowired
    public SkuProfitPreviewService(JdbcTemplate jdbc,
        OrderCostReadinessService costs,OrderContributionService contribution,
        MultiSkuContributionService multiSku) {
        this(jdbc,costs,contribution,multiSku,Clock.systemUTC());
    }
    SkuProfitPreviewService(JdbcTemplate jdbc,OrderCostReadinessService costs,
        OrderContributionService contribution,MultiSkuContributionService multiSku,
        Clock clock) {
        this.jdbc=jdbc;this.costs=costs;this.contribution=contribution;
        this.multiSku=multiSku;this.clock=clock;
    }
    private record Order(UUID id,String externalId,String createdAt) {}
    private record Market(String currency) {}
    public record Preview(String periodAfter,String periodBefore,
        String timezone,int offset,int pageSize,boolean hasMore,
        boolean periodTruncated,String unverifiedAdsSpendReference,
        long unverifiedAdsRecordCount,String adsSourceOrigin,
        SkuProfitPreviewEngine.Totals totals,
        List<SkuProfitPreviewEngine.SkuRow> skuRows,
        List<SkuProfitPreviewEngine.OrderRow> orders) {}

    static void validate(LocalDate after,LocalDate before,int offset,Clock clock) {
        if(after==null||before==null||!after.isBefore(before)
            ||ChronoUnit.DAYS.between(after,before)>92
            ||before.isAfter(LocalDate.now(clock).plusDays(1))
            ||after.isBefore(LocalDate.of(2000,1,1))
            ||offset<0||offset>MAX_OFFSET||offset%PAGE_SIZE!=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Use UTC date range 1..92 days and aligned page offset");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview preview(UUID tenant,UUID marketplace,
        LocalDate after,LocalDate before,int offset) {
        validate(after,before,offset,clock);
        var markets=jdbc.query("""
            SELECT m.currency_code FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            WHERE m.tenant_id=? AND m.id=?
            """,(r,n)->new Market(r.getString(1).trim()),tenant,marketplace);
        if(markets.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Marketplace unavailable in selected tenant");
        String currency=markets.getFirst().currency();
        OffsetDateTime start=after.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end=before.atStartOfDay().atOffset(ZoneOffset.UTC);
        List<Order> source=jdbc.query("""
            SELECT o.id,o.external_order_id,o.order_time
            FROM sales_order o
            WHERE o.tenant_id=? AND o.seller_marketplace_id=?
              AND o.data_origin='LIVE'
              AND o.order_time>=? AND o.order_time<?
            ORDER BY o.order_time,o.id
            LIMIT ? OFFSET ?
            """,(r,n)->new Order(r.getObject("id",UUID.class),
                r.getString("external_order_id"),
                r.getObject("order_time",OffsetDateTime.class).toInstant().toString()),
            tenant,marketplace,start,end,PAGE_SIZE+1,offset);
        boolean more=source.size()>PAGE_SIZE;
        List<SkuProfitPreviewEngine.Input> inputs=new ArrayList<>();
        Map<String,MultiSkuContributionService.Result> reviewedMulti=new HashMap<>();
        for(int i=0;i<Math.min(PAGE_SIZE,source.size());i++){
            Order order=source.get(i);
            var cost=costs.preview(tenant,marketplace,order.id());
            // Reuse the existing reviewed Finances/COGS calculator exactly.
            var financial=contribution.preview(tenant,marketplace,order.id());
            var distinct=cost.items().stream()
                .filter(line->line.internalSku()!=null)
                .map(OrderCostEngine.Line::internalSku).distinct().toList();
            String sku=distinct.size()==1?distinct.getFirst():null;
            boolean mixed=distinct.size()>1;
            int units=cost.items().stream().mapToInt(line->
                line.quantity()==null?0:line.quantity()).sum();
            inputs.add(new SkuProfitPreviewEngine.Input(order.id().toString(),
                order.externalId(),order.createdAt(),sku,units,cost.readiness(),
                mixed,financial));
            if(mixed)reviewedMulti.put(order.id().toString(),
                multiSku.preview(tenant,marketplace,order.id(),cost,financial));
        }
        var rollup=SkuProfitPreviewEngine.calculate(currency,inputs,more,offset>0,reviewedMulti);
        var ads=jdbc.query("""
            SELECT COALESCE(SUM(a.spend_amount),0) AS manual_spend,
                   COUNT(*) AS source_count
            FROM finance_ad_spend_reference a
            WHERE a.tenant_id=? AND a.seller_marketplace_id=?
              AND a.evidence_origin='MANUAL_UNVERIFIED'
              AND a.currency_code=?
              AND a.business_date>=? AND a.business_date<?
            """,(r,n)->new Object[]{r.getBigDecimal("manual_spend"),
                r.getLong("source_count")},tenant,marketplace,currency,after,before)
            .getFirst();
        return new Preview(after.toString(),before.toString(),"UTC",offset,
            PAGE_SIZE,more,more||offset>0,
            ((BigDecimal)ads[0]).toPlainString(),(Long)ads[1],
            "MANUAL_UNVERIFIED",rollup.totals(),rollup.skus(),rollup.orders());
    }
}
