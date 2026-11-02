package org.merchivra.erp.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V74 explainable read-only priority: historical observed OrderItems + V22
 * recent FBA fulfillable + V42 manual terms + explicit V37-sourced local POs.
 * NO forecast, Amazon in-transit assumption, projected cash, or auto purchase.
 */
@Service
public class ReplenishmentPriorityService {
    private final JdbcTemplate jdbc;
    private final ReplenishmentService replenishment;
    private final ReplenishmentDemandService demand;
    private final ReplenishmentSupplierQuoteService quotes;

    public ReplenishmentPriorityService(JdbcTemplate jdbc,ReplenishmentService replenishment,
        ReplenishmentDemandService demand,ReplenishmentSupplierQuoteService quotes) {
        this.jdbc=jdbc;this.replenishment=replenishment;this.demand=demand;this.quotes=quotes;
    }

    public record Priority(String sellerSku,String stockState,String demandStatus,
        String evidenceLevel,String riskCode,String priorityCode,
        String stockSourceMode,String demandSourceMode,
        String observationId,String policyUpdatedAt,Integer fulfillable,
        String observedAt,Integer policyLeadTimeDays,
        Long observedUnits28d,String observedDailyUnits28d,String illustrativeCoverageDays,
        Long illustrativePolicyLeadUnits,
        int readySupplierQuoteCount,Integer shortestSupplierTermDays,
        Integer longestSupplierTermDays,
        long localDraftOrSubmittedUnits,long localApprovedUnreceivedUnits,
        long localReceivedUnits,long localPendingOrderCount,
        boolean existingLocalOrderNeedsReview) {}

    public record Decision(String evidenceLevel,String riskCode,String priorityCode,
                           String coverageDays,Long policyLeadUnits) {}

    /** A quantity is merely illustrative; never copy it to purchase draft action. */
    static Decision classify(ReplenishmentService.Advice stock,
        ReplenishmentDemandService.Signal history,String observationMode) {
        if(!List.of("REORDER","HEALTHY").contains(stock.state())
            || stock.fulfillable()==null || stock.observationId()==null)
            return new Decision("STOCK_UNTRUSTED","STOCK_NOT_OBSERVED","BLOCKED",null,null);
        if(history==null || !List.of("MOCK_SAMPLE","OBSERVED_ONLY").contains(history.status()))
            return new Decision("DEMAND_INCOMPLETE","DEMAND_NOT_ESTABLISHED",
                "REVIEW_STOCK_ONLY",null,null);
        if(!List.of("MOCK","LIVE").contains(observationMode)
           || !observationMode.equals(history.sourceMode()))
            return new Decision("SOURCE_MISMATCH","SOURCE_MISMATCH","BLOCKED",null,null);
        if(history.observedUnits28d()==null || history.observedUnits28d()==0
            || history.observedUnits28d()<0)
            return new Decision("NO_SKU_RATE","RATE_NOT_ESTABLISHED",
                "REVIEW_STOCK_ONLY",null,null);
        long units=history.observedUnits28d();
        int lead=stock.policy().leadTimeDays();
        BigDecimal days=BigDecimal.valueOf(stock.fulfillable())
            .multiply(BigDecimal.valueOf(28))
            .divide(BigDecimal.valueOf(units),1,RoundingMode.HALF_UP);
        // Use unrounded days for the risk comparison. The displayed one-decimal
        // day count may round up across a threshold.
        boolean withinLead=BigDecimal.valueOf(stock.fulfillable())
            .multiply(BigDecimal.valueOf(28))
            .compareTo(BigDecimal.valueOf(units).multiply(BigDecimal.valueOf(lead)))<=0;
        BigDecimal leadUnits=BigDecimal.valueOf(units)
            .multiply(BigDecimal.valueOf(lead)).divide(BigDecimal.valueOf(28),0,RoundingMode.CEILING);
        Long leadDisplay=leadUnits.compareTo(BigDecimal.valueOf(1_000_000))>0
            ?null:leadUnits.longValueExact();
        return new Decision("MOCK".equals(observationMode)?"MOCK_SAMPLE":"OBSERVED_HISTORY",
            withinLead?"LEAD_WINDOW_EXPOSURE":
                "REORDER".equals(stock.state())?"POLICY_REORDER":"HISTORICAL_COVERAGE_OK",
            withinLead?"HIGH":"REORDER".equals(stock.state())?"MEDIUM":"LOW",
            days.toPlainString(),leadDisplay);
    }

    private record Source(String sku,String observationId,String mode) {}
    private record LocalPo(String sku,long drafts,long approvedOpen,long received,long orders) {}
    private static final String LOCAL_PO_SQL = """
        SELECT source.seller_sku,
          COALESCE(SUM(l.ordered_quantity) FILTER
            (WHERE po.status IN ('DRAFT','SUBMITTED')),0)::bigint AS draft_units,
          COALESCE(SUM(GREATEST(l.ordered_quantity-COALESCE(rec.units,0),0))
            FILTER (WHERE po.status IN ('APPROVED','RECEIVING')),0)::bigint AS approved_open,
          COALESCE(SUM(COALESCE(rec.units,0))
            FILTER (WHERE po.status IN ('APPROVED','RECEIVING','RECEIVED')),0)::bigint
              AS received_units,
          COUNT(*) FILTER (WHERE po.status IN ('DRAFT','SUBMITTED','APPROVED','RECEIVING'))
            ::bigint AS pending_orders
        FROM erp_replenishment_purchase_draft_source source
        JOIN erp_purchase_order po ON po.tenant_id=source.tenant_id
          AND po.id=source.purchase_order_id
        JOIN erp_purchase_order_line l ON l.tenant_id=po.tenant_id
          AND l.purchase_order_id=po.id AND l.product_sku_id=source.product_sku_id
        LEFT JOIN LATERAL (
          SELECT COALESCE(SUM(r.quantity),0)::bigint AS units
          FROM erp_purchase_receipt r
          WHERE r.tenant_id=l.tenant_id AND r.purchase_order_id=po.id
            AND r.purchase_order_line_id=l.id
        ) rec ON TRUE
        WHERE source.tenant_id=? AND source.seller_marketplace_id=?
          AND po.status<>'CANCELLED'
        GROUP BY source.seller_sku
        """;

    @Transactional(readOnly=true)
    public List<Priority> priorities(UUID tenant,UUID marketplace) {
        // V22 and V70 check current ACTIVE tenant+marketplace, and each
        // source is tenant+site scoped. No user-provided supplier/tenant IDs.
        List<ReplenishmentService.Advice> current=replenishment.advice(tenant,marketplace);
        List<ReplenishmentDemandService.Signal> histories=demand.signals(tenant,marketplace);
        List<ReplenishmentSupplierQuoteService.Quote> options=quotes.quotes(tenant,marketplace);
        Map<String,ReplenishmentDemandService.Signal> bySku=new HashMap<>();
        for(var row:histories)bySku.put(row.sellerSku(),row);
        Map<String,String> stockOrigins=new HashMap<>();
        for(Source source:jdbc.query("""
            SELECT p.seller_sku,latest.id AS observation_id,
              latest.data_origin AS source_mode
            FROM erp_replenishment_policy p
            LEFT JOIN LATERAL (
              SELECT id,data_origin FROM fba_inventory_observation
              WHERE tenant_id=p.tenant_id
                AND seller_marketplace_id=p.seller_marketplace_id
                AND seller_sku_raw=p.seller_sku
              ORDER BY observed_at DESC NULLS LAST,ingested_at DESC,id DESC LIMIT 1
            ) latest ON TRUE
            WHERE p.tenant_id=? AND p.seller_marketplace_id=?
            ORDER BY p.seller_sku LIMIT 500
            """,(rs,i)->new Source(rs.getString("seller_sku"),
                rs.getString("observation_id"),rs.getString("source_mode")),tenant,marketplace)){
            // Match the exact observation that V22 validated, never another row.
            stockOrigins.put(source.sku()+"|"+source.observationId(),source.mode());
        }
        Map<String,LocalPo> local=new HashMap<>();
        for(LocalPo p:jdbc.query(LOCAL_PO_SQL,(rs,i)->new LocalPo(
            rs.getString("seller_sku"),rs.getLong("draft_units"),
            rs.getLong("approved_open"),rs.getLong("received_units"),
            rs.getLong("pending_orders")),tenant,marketplace))
            local.put(p.sku(),p);

        Map<String,List<ReplenishmentSupplierQuoteService.Quote>> optionsBySku=new HashMap<>();
        for(var q:options)optionsBySku.computeIfAbsent(q.sellerSku(),
            ignored->new ArrayList<>()).add(q);
        List<Priority> result=new ArrayList<>();
        for(var stock:current) {
            String sku=stock.policy().sellerSku();
            var history=bySku.get(sku);
            var sourceMode=stockOrigins.get(sku+"|"+stock.observationId());
            Decision decision=classify(stock,history,sourceMode);
            int quoteCount=0;
            Integer min=null,max=null;
            for(var q:optionsBySku.getOrDefault(sku,List.of())) {
                if("READY".equals(q.status())
                   && stock.observationId()!=null
                   && stock.observationId().equals(q.observationId())
                   && stock.policy().updatedAt().equals(q.policyUpdatedAt())) {
                    quoteCount++;
                    min=min==null?q.supplierLeadTimeDays():Math.min(min,q.supplierLeadTimeDays());
                    max=max==null?q.supplierLeadTimeDays():Math.max(max,q.supplierLeadTimeDays());
                }
            }
            LocalPo po=local.getOrDefault(sku,new LocalPo(sku,0,0,0,0));
            result.add(new Priority(sku,stock.state(),
                history==null?"NO_POLICY_SIGNAL":history.status(),
                decision.evidenceLevel(),decision.riskCode(),decision.priorityCode(),
                sourceMode,history==null?null:history.sourceMode(),
                stock.observationId(),stock.policy().updatedAt(),stock.fulfillable(),
                stock.observedAt(),stock.policy().leadTimeDays(),
                history==null?null:history.observedUnits28d(),
                history==null?null:history.observedDailyUnits28d(),
                decision.coverageDays(),decision.policyLeadUnits(),
                quoteCount,min,max,po.drafts(),po.approvedOpen(),po.received(),
                po.orders(),po.orders()>0));
        }
        return List.copyOf(result);
    }
}
