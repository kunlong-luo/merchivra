package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V202: read-only local purchase receipt/QC/cost provenance diagnostics.
 * A same-SKU local receipt is NEVER proof of which lot fulfilled an Amazon
 * order; manual local OUTBOUND lacks a source sale+lot attribution.
 * Cannot post COGS, invent FIFO, mix currencies or certify physical evidence.
 */
@Service
public class OrderLotProvenanceServiceV202 {
    private final JdbcTemplate jdbc;
    private final OrderCostReadinessService orderCosts;

    public OrderLotProvenanceServiceV202(JdbcTemplate jdbc,
        OrderCostReadinessService orderCosts) {
        this.jdbc=jdbc;
        this.orderCosts=orderCosts;
    }

    public record SkuEvidence(String internalSku,long priorLocalReceiptCount,
        long legacyNoQcBatchCount,long pendingQcBatchCount,
        long inspectedUnreleasedBatchCount,long releasedBatchCount,
        long receiptIdentityConflictCount,long qcIdentityConflictCount,
        long releaseLedgerConflictCount,long observedReleasedLocalUnits,
        long distinctPurchaseCurrencyCount,long distinctPurchaseUnitCostCount,
        String singlePurchaseCurrency,String singlePurchaseUnitCost,
        String status,boolean saleLotAttributed,String realizedBatchCogs) {}

    public record Preview(String orderId,String marketplaceId,
        String orderCostReadiness,String sourceScope,
        List<SkuEvidence> skuEvidence,List<String> blockerCodes,
        boolean saleLotAttributed,boolean actualBatchCogsVerified,
        String realizedBatchCogs) {}

    private record ReceiptRow(String sku,long receiptCount,long legacy,long pending,
        long inspected,long released,long receiptMismatch,long qcMismatch,
        long ledgerMismatch,long observedReleasedUnits,long currencies,long prices,
        String referenceCurrency,BigDecimal referencePrice) {}

    /**
     * Aggregate the entire scoped receipt population once PER SKU; never join
     * onto an order item row (that would multiply quantities for multiple
     * sale lines). Each V20 receipt has 0/1 V46 batch, 0/1 V44 QC, and a
     * scalar V39/V46 QC_RELEASE ledger probe. Only receipts received before
     * the order source timestamp are candidates; even then none is sale proof.
     *
     * SQL kept visible to the isolated PG18 source-exact regression.
     */
    static final String RECEIPT_LINEAGE_SQL = """
        SELECT s.internal_sku,
          count(*)::bigint AS receipt_count,
          count(*) FILTER (WHERE b.receipt_id IS NULL)::bigint AS legacy_count,
          count(*) FILTER (WHERE b.status='PENDING_QC')::bigint AS pending_count,
          count(*) FILTER (WHERE b.status='INSPECTED')::bigint AS inspected_count,
          count(*) FILTER (WHERE b.status='RELEASED')::bigint AS released_count,
          count(*) FILTER (WHERE b.receipt_id IS NOT NULL AND (
            b.product_sku_id IS DISTINCT FROM l.product_sku_id
            OR b.received_quantity IS DISTINCT FROM r.quantity
            OR b.warehouse_id IS DISTINCT FROM r.warehouse_id
          ))::bigint AS receipt_conflicts,
          count(*) FILTER (WHERE b.status IN ('INSPECTED','RELEASED')
            AND (q.id IS NULL
              OR q.purchase_receipt_id IS DISTINCT FROM r.id
              OR q.product_sku_id IS DISTINCT FROM l.product_sku_id
              OR q.warehouse_id IS DISTINCT FROM r.warehouse_id
              OR q.inspected_quantity IS DISTINCT FROM r.quantity
              OR q.accepted_quantity+q.rejected_quantity IS DISTINCT FROM r.quantity
            ))::bigint AS qc_conflicts,
          count(*) FILTER (WHERE b.status='RELEASED'
            AND (q.id IS NULL
              OR (q.accepted_quantity=0 AND release_ledger.release_count<>0)
              OR (q.accepted_quantity>0 AND (
                release_ledger.release_count<>1
                OR release_ledger.released_quantity IS DISTINCT FROM q.accepted_quantity
                OR release_ledger.released_sku_id IS DISTINCT FROM l.product_sku_id::text
                OR release_ledger.released_warehouse_id IS DISTINCT FROM r.warehouse_id::text
              ))
            ))::bigint AS ledger_conflicts,
          COALESCE(sum(CASE WHEN b.status='RELEASED'
            AND q.id IS NOT NULL
            AND q.purchase_receipt_id=r.id
            AND q.product_sku_id=l.product_sku_id
            AND q.warehouse_id=r.warehouse_id
            AND b.product_sku_id=l.product_sku_id
            AND b.warehouse_id=r.warehouse_id
            AND b.received_quantity=r.quantity
            AND q.inspected_quantity=r.quantity
            AND q.accepted_quantity+q.rejected_quantity=r.quantity
            AND release_ledger.release_count=1
            AND release_ledger.released_quantity=q.accepted_quantity
            AND release_ledger.released_sku_id=l.product_sku_id::text
            AND release_ledger.released_warehouse_id=r.warehouse_id::text
            THEN q.accepted_quantity ELSE 0 END),0)::bigint
              AS observed_local_released_units,
          count(DISTINCT po.currency_code)::bigint AS currency_count,
          count(DISTINCT l.unit_cost)::bigint AS cost_count,
          min(po.currency_code) AS min_currency,
          min(l.unit_cost) AS min_unit_cost
        FROM erp_purchase_receipt r
        JOIN erp_purchase_order_line l
          ON l.tenant_id=r.tenant_id
          AND l.purchase_order_id=r.purchase_order_id
          AND l.id=r.purchase_order_line_id
        JOIN erp_purchase_order po
          ON po.tenant_id=l.tenant_id AND po.id=l.purchase_order_id
        JOIN product_sku s
          ON s.tenant_id=l.tenant_id AND s.id=l.product_sku_id
        LEFT JOIN erp_receipt_quality_batch b
          ON b.tenant_id=r.tenant_id AND b.receipt_id=r.id
        LEFT JOIN erp_purchase_receipt_qc q
          ON q.tenant_id=b.tenant_id AND q.id=b.inspection_id
        LEFT JOIN LATERAL (
          SELECT count(*)::bigint AS release_count,
            sum(m.quantity)::bigint AS released_quantity,
            min(m.product_sku_id::text) AS released_sku_id,
            min(m.warehouse_id::text) AS released_warehouse_id
          FROM erp_local_stock_movement m
          WHERE m.tenant_id=r.tenant_id
            AND m.inspection_id=q.id AND m.movement_type='QC_RELEASE'
        ) release_ledger ON TRUE
        WHERE r.tenant_id=?
          AND r.received_at<=?
          AND s.internal_sku IN (%s)
        GROUP BY s.internal_sku
        ORDER BY s.internal_sku
        """;

    static String state(long receiptCount,long legacy,long receiptMismatch,
        long qcMismatch,long releaseMismatch,long currencyCount,
        long priceCount,long released) {
        if(receiptCount==0)return "NO_PRIOR_LOCAL_RECEIPT";
        if(receiptMismatch>0||qcMismatch>0||releaseMismatch>0)
            return "LOCAL_RECEIPT_OR_QC_IDENTITY_CONFLICT";
        if(legacy>0)return "LEGACY_LOCAL_RECEIPT_NO_QC_BATCH";
        if(currencyCount!=1||priceCount!=1)
            return "MULTIPLE_LOCAL_PO_COST_REFERENCES";
        if(released==0)return "NO_RELEASED_LOCAL_BATCH";
        return "LOCAL_BATCHES_NOT_LINKED_TO_SALES";
    }

    private static SkuEvidence map(ReceiptRow r) {
        String referenceCurrency=r.currencies()==1?r.referenceCurrency():null;
        String referencePrice=r.currencies()==1&&r.prices()==1
            &&r.referencePrice()!=null?r.referencePrice().toPlainString():null;
        return new SkuEvidence(r.sku(),r.receiptCount(),r.legacy(),r.pending(),
            r.inspected(),r.released(),r.receiptMismatch(),r.qcMismatch(),
            r.ledgerMismatch(),r.observedReleasedUnits(),r.currencies(),
            r.prices(),referenceCurrency,referencePrice,
            state(r.receiptCount(),r.legacy(),r.receiptMismatch(),r.qcMismatch(),
                r.ledgerMismatch(),r.currencies(),r.prices(),r.released()),
            false,null);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview preview(UUID tenant,UUID marketplace,UUID order) {
        OrderCostEngine.Preview cost=orderCosts.preview(tenant,marketplace,order);
        var blockers=new ArrayList<String>();
        blockers.add("SALE_TO_LOCAL_RECEIPT_LOT_LINK_NOT_RECORDED");
        blockers.add("FBA_FBM_FULFILLMENT_AND_EXTERNAL_WMS_UNVERIFIED");
        blockers.add("G1_ACTUAL_BATCH_COGS_POSTING_NOT_AUTHORIZED");
        if(!"COGS_READY_PREVIEW".equals(cost.readiness())
            ||cost.orderCreatedAt()==null) {
            blockers.add("ORDER_HISTORICAL_SKU_COST_NOT_READY");
            return new Preview(order.toString(),marketplace.toString(),
                cost.readiness(),"LOCAL_PURCHASE_RECEIPTS_NOT_AMAZON",
                List.of(),List.copyOf(blockers),false,false,null);
        }

        // One receipt aggregate for all distinct, already date-mapped SKUs.
        // No per-line SQL, page-subtotals or speculative order-to-lot matches.
        Map<String,Boolean> skuNames=new LinkedHashMap<>();
        for(var line:cost.items()) {
            if(!"READY".equals(line.issue()) || line.internalSku()==null)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cost preview not safe for local receipt provenance");
            skuNames.put(line.internalSku(),Boolean.TRUE);
        }
        if(skuNames.isEmpty()||skuNames.size()>1000)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order SKU source set is empty or unbounded");
        String placeholders=String.join(",",
            java.util.Collections.nCopies(skuNames.size(),"?"));
        var args=new ArrayList<Object>();
        args.add(tenant);
        args.add(OffsetDateTime.ofInstant(
            Instant.parse(cost.orderCreatedAt()),ZoneOffset.UTC));
        args.addAll(skuNames.keySet());

        var found=jdbc.query(RECEIPT_LINEAGE_SQL.formatted(placeholders),
            (rs,n)->new ReceiptRow(rs.getString("internal_sku"),
                rs.getLong("receipt_count"),rs.getLong("legacy_count"),
                rs.getLong("pending_count"),rs.getLong("inspected_count"),
                rs.getLong("released_count"),rs.getLong("receipt_conflicts"),
                rs.getLong("qc_conflicts"),rs.getLong("ledger_conflicts"),
                rs.getLong("observed_local_released_units"),
                rs.getLong("currency_count"),rs.getLong("cost_count"),
                rs.getString("min_currency"),rs.getBigDecimal("min_unit_cost")),
            args.toArray());
        Map<String,SkuEvidence> bySku=new LinkedHashMap<>();
        for(var row:found) {
            if(bySku.put(row.sku(),map(row))!=null || !skuNames.containsKey(row.sku()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ambiguous local receipt source aggregation");
        }
        var lines=new ArrayList<SkuEvidence>();
        for(String sku:skuNames.keySet()) {
            SkuEvidence item=bySku.get(sku);
            lines.add(item==null
                ?new SkuEvidence(sku,0,0,0,0,0,0,0,0,0,
                    0,0,null,null,"NO_PRIOR_LOCAL_RECEIPT",false,null)
                :item);
            if(item!=null&&"LOCAL_RECEIPT_OR_QC_IDENTITY_CONFLICT".equals(item.status())
                &&!blockers.contains("LOCAL_RECEIPT_IDENTITY_INCONSISTENT"))
                blockers.add("LOCAL_RECEIPT_IDENTITY_INCONSISTENT");
            if(item!=null&&"MULTIPLE_LOCAL_PO_COST_REFERENCES".equals(item.status())
                &&!blockers.contains("LOCAL_PO_COST_REFERENCES_MULTIPLE"))
                blockers.add("LOCAL_PO_COST_REFERENCES_MULTIPLE");
        }
        return new Preview(order.toString(),marketplace.toString(),
            cost.readiness(),"LOCAL_PURCHASE_RECEIPTS_NOT_AMAZON",
            List.copyOf(lines),List.copyOf(blockers),false,false,null);
    }
}
