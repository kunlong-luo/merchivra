package org.merchivra.erp.finance;

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
 * V212 read-only "could these quantities fit" intersection of existing V210,
 * V203/V106, V96 and V97. A return is NOT linked to a specific purchase lot.
 * Existing V210 and V203 probes each own their transactional boundaries; this
 * class rechecks BOTH after the bounded RR source snapshot and fails closed
 * on changes instead of combining two inconsistent financial snapshots.
 */
@Service
public class RefundBatchLineageServiceV212 {
    static final int MAX_ROWS=1000;

    static final String ORIGINAL_LOT_CLAIMS_SQL="""
        SELECT c.id AS claim_id,c.receipt_id,
          c.snapshot_external_item_id,c.assigned_quantity,
          v.integrity_state,c.snapshot_currency,c.snapshot_unit_cost,
          c.external_payload_sha256
        FROM finance_sale_lot_claim_v106 c
        LEFT JOIN finance_sale_lot_integrity_v106 v
          ON v.tenant_id=c.tenant_id AND v.claim_id=c.id
        WHERE c.tenant_id=? AND c.marketplace_id=? AND c.sales_order_id=?
        ORDER BY c.id LIMIT 1001
        """;

    static final String REFUND_ITEM_REVIEWS_SQL="""
        SELECT r.event_id,r.external_item_id,r.reviewed_quantity,r.disposition
        FROM finance_refund_item_review r
        WHERE r.tenant_id=? AND r.sales_order_id=?
        ORDER BY r.event_id,r.external_item_id LIMIT 1001
        """;

    static final String RETURN_INTAKES_SQL="""
        SELECT i.id,i.claim_id,i.event_id,i.external_item_id,
          i.received_quantity,i.state,i.condition_code,
          c.state AS receipt_claim_state,
          c.condition_code AS receipt_claim_condition,
          c.claimed_quantity AS receipt_claim_quantity
        FROM finance_customer_return_intake_v97 i
        LEFT JOIN finance_return_receipt_claim_v96 c
          ON c.tenant_id=i.tenant_id AND c.id=i.claim_id
          AND c.event_id=i.event_id AND c.sales_order_id=i.sales_order_id
          AND c.external_item_id=i.external_item_id
        WHERE i.tenant_id=? AND i.seller_marketplace_id=?
          AND i.sales_order_id=?
        ORDER BY i.id LIMIT 1001
        """;

    private record Source(List<RefundBatchLineageGuardV212.Reviewed> reviews,
        List<RefundBatchLineageGuardV212.LotClaim> claims,
        List<RefundBatchLineageGuardV212.Intake> intakes) {}

    private final JdbcTemplate jdbc;
    private final RefundOrderConservationServiceV210 refunds;
    private final OrderLotAssignmentIntegrityServiceV203 lots;
    private final TransactionTemplate readTx;

    public RefundBatchLineageServiceV212(JdbcTemplate jdbc,
        RefundOrderConservationServiceV210 refunds,
        OrderLotAssignmentIntegrityServiceV203 lots,
        PlatformTransactionManager transactionManager) {
        this.jdbc=jdbc;this.refunds=refunds;this.lots=lots;
        this.readTx=new TransactionTemplate(transactionManager);
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTx.setReadOnly(true);
    }

    private static void bounded(String kind,int count) {
        if(count>MAX_ROWS)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "V212 complete "+kind+" source exceeds 1000 records; "+
                    "partial quantity envelope cannot be shown");
    }

    private Source snapshot(UUID tenant,UUID mp,UUID order) {
        return readTx.execute(ignored->{
            var reviewed=jdbc.query(REFUND_ITEM_REVIEWS_SQL,
                (r,n)->new RefundBatchLineageGuardV212.Reviewed(
                    r.getObject("event_id",UUID.class),
                    r.getString("external_item_id"),
                    r.getInt("reviewed_quantity"),r.getString("disposition")),
                tenant,order);
            bounded("V34 refund item reviews",reviewed.size());

            var claims=jdbc.query(ORIGINAL_LOT_CLAIMS_SQL,
                (r,n)->new RefundBatchLineageGuardV212.LotClaim(
                    r.getObject("claim_id",UUID.class),
                    r.getObject("receipt_id",UUID.class),
                    r.getString("snapshot_external_item_id"),
                    r.getInt("assigned_quantity"),r.getString("integrity_state"),
                    r.getString("snapshot_currency")==null?null:
                        r.getString("snapshot_currency").trim(),
                    r.getBigDecimal("snapshot_unit_cost"),
                    r.getString("external_payload_sha256")),
                tenant,mp,order);
            bounded("original V106 sale-lot claims",claims.size());

            var intakes=jdbc.query(RETURN_INTAKES_SQL,
                (r,n)->new RefundBatchLineageGuardV212.Intake(
                    r.getObject("id",UUID.class),
                    r.getObject("claim_id",UUID.class),
                    r.getObject("event_id",UUID.class),
                    r.getString("external_item_id"),
                    r.getInt("received_quantity"),r.getString("state"),
                    r.getString("condition_code"),r.getString("receipt_claim_state"),
                    r.getString("receipt_claim_condition"),
                    r.getObject("receipt_claim_quantity",Integer.class)),
                tenant,mp,order);
            bounded("V97 local return intake",intakes.size());
            return new Source(List.copyOf(reviewed),List.copyOf(claims),
                List.copyOf(intakes));
        });
    }

    public RefundBatchLineageGuardV212.Preview preview(
        UUID tenant,UUID marketplace,UUID order) {
        if(tenant==null||marketplace==null||order==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Owner tenant, Marketplace and order required");
        // Existing order + marketplace isolation, V197/V210 snapshot, V203
        // verified V106 read-time local identity; no duplicate verification.
        var beforeRefund=refunds.preview(tenant,marketplace,order);
        var beforeLots=lots.preview(tenant,marketplace,order);
        Source data=snapshot(tenant,marketplace,order);
        var nowRefund=refunds.preview(tenant,marketplace,order);
        var nowLots=lots.preview(tenant,marketplace,order);
        if(!beforeRefund.equals(nowRefund)||!beforeLots.equals(nowLots))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "V210 refund or V203 local lot evidence changed during cross-check");
        // Also re-read V34/V106/V96/V97 as a complete, bounded second
        // source snapshot: newly reviewed/rejected local parcels or changed
        // original claims must not reuse a previous green quantity envelope.
        if(!data.equals(snapshot(tenant,marketplace,order)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Refund, original sale lot or local return intake changed during cross-check");

        try {
            return RefundBatchLineageGuardV212.calculate(marketplace,
                nowRefund,nowLots,data.reviews(),data.claims(),data.intakes());
        } catch(IllegalArgumentException inconsistent) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Original sale lots, refund event reviews or return intakes inconsistent",
                inconsistent);
        }
    }
}
