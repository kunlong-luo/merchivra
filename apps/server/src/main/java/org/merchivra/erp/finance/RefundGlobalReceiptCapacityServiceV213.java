package org.merchivra.erp.finance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
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
 * V213 global read-time scan: V106 enforces the receipt lock and quantity
 * at insert-time; this independently checks the entire same-receipt claim
 * population ACROSS OTHER LOCAL ORDERS and current V106 integrity status.
 *
 * The V212 source and the receipt aggregation are separate RR snapshots.
 * Re-check both after the first scan and return HTTP 409 on drift; this
 * is not a single signed/atomic proof or an external cost attestation.
 */
@Service
public class RefundGlobalReceiptCapacityServiceV213 {
    static final int MAX_ROWS=1000;

    // A left-joined ONE-ROW-PER-CLAIM V106 view detects retracted and stale
    // historical allocations; do not filter them out and recycle capacity.
    static final String GLOBAL_RECEIPT_SCOPE_SQL="""
        SELECT c.receipt_id,
          count(*)::bigint AS claim_count,
          count(DISTINCT c.sales_order_id)::bigint AS order_count,
          sum(c.assigned_quantity)::bigint AS assigned_units,
          max(c.snapshot_accepted_quantity)::bigint AS accepted_units,
          count(DISTINCT c.snapshot_accepted_quantity)::bigint AS accepted_versions,
          count(DISTINCT c.snapshot_currency)::bigint AS currency_count,
          count(DISTINCT c.snapshot_unit_cost)::bigint AS price_count,
          count(*) FILTER (WHERE v.claim_id IS NULL OR
            v.integrity_state IS DISTINCT FROM 'LOCAL_LINK_INTACT_UNVERIFIED')
            ::bigint AS stale_count,
          min(c.snapshot_currency) AS reference_currency,
          min(c.snapshot_unit_cost) AS reference_unit_cost
        FROM finance_sale_lot_claim_v106 c
        LEFT JOIN finance_sale_lot_integrity_v106 v
          ON v.tenant_id=c.tenant_id AND v.claim_id=c.id
        WHERE c.tenant_id=? AND c.receipt_id IN (%s)
        GROUP BY c.receipt_id
        ORDER BY c.receipt_id LIMIT 1001
        """;

    private final JdbcTemplate jdbc;
    private final RefundBatchLineageServiceV212 lineage;
    private final TransactionTemplate sourceTx;

    public RefundGlobalReceiptCapacityServiceV213(JdbcTemplate jdbc,
        RefundBatchLineageServiceV212 lineage,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.lineage=lineage;
        this.sourceTx=new TransactionTemplate(manager);
        this.sourceTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.sourceTx.setReadOnly(true);
    }

    private List<RefundGlobalReceiptCapacityGateV213.Source> query(UUID tenant,
        List<UUID> receiptIds) {
        if(receiptIds.isEmpty())return List.of();
        return sourceTx.execute(ignored->{
            var holders=String.join(",",
                Collections.nCopies(receiptIds.size(),"?"));
            var bind=new ArrayList<Object>();
            bind.add(tenant);bind.addAll(receiptIds);
            var rows=jdbc.query(GLOBAL_RECEIPT_SCOPE_SQL.formatted(holders),
                (r,n)->new RefundGlobalReceiptCapacityGateV213.Source(
                    r.getObject("receipt_id",UUID.class),
                    r.getLong("claim_count"),r.getLong("order_count"),
                    r.getLong("assigned_units"),r.getLong("accepted_units"),
                    r.getLong("accepted_versions"),r.getLong("currency_count"),
                    r.getLong("price_count"),r.getLong("stale_count"),
                    r.getString("reference_currency")==null?null:
                        r.getString("reference_currency").trim(),
                    r.getBigDecimal("reference_unit_cost")),bind.toArray());
            if(rows.size()>MAX_ROWS || rows.stream().mapToLong(
                RefundGlobalReceiptCapacityGateV213.Source::globalClaimRows).sum()>MAX_ROWS)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cross-order purchase receipt source > 1000 claims; "
                    +"cannot display truncated financial identity evidence");
            return List.copyOf(rows);
        });
    }
    private List<UUID> receipts(RefundBatchLineageGuardV212.Preview source) {
        var set=new LinkedHashSet<UUID>();
        if(source.orderItems().size()>MAX_ROWS)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Refund order item population exceeds complete evidence bound");
        for(var item:source.orderItems())for(var lot:item.saleLotClaims()) {
            set.add(UUID.fromString(lot.originalPurchaseReceiptId()));
            if(set.size()>MAX_ROWS)throw new ResponseStatusException(
                HttpStatus.CONFLICT,"More than 1000 original purchase receipts");
        }
        return List.copyOf(set);
    }

    public RefundGlobalReceiptCapacityGateV213.Result preview(UUID tenant,
        UUID marketplace,UUID order) {
        if(tenant==null||marketplace==null||order==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Tenant, Marketplace and LIVE order required");
        var before=lineage.preview(tenant,marketplace,order);
        var ids=receipts(before);
        var observed=query(tenant,ids);
        // Full-source re-evaluation across transaction boundaries. Never
        // substitute ZERO for a failed/stale V212 request.
        var current=lineage.preview(tenant,marketplace,order);
        if(!before.equals(current)||!ids.equals(receipts(current))
            ||!observed.equals(query(tenant,ids)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cross-order purchase receipt or refund lot evidence changed");
        try {
            return RefundGlobalReceiptCapacityGateV213.calculate(marketplace,
                order,current,observed);
        }catch(IllegalArgumentException inconsistent) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Local receipt identity or claimed unit conservation inconsistent",
                inconsistent);
        }
    }
}
