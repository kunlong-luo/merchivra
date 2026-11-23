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
 * V214 bounded, original-row read of V91/V92 candidate metadata. Reuses V213
 * before reporting receipts. V91/V92 have NO receipt FK or receipt target:
 * Marketplace-wide counts must never be attributed to an individual receipt.
 */
@Service
public class RefundReceiptCostProvenanceServiceV214 {
    static final String MARKETPLACE_CANDIDATE_SQL = """
        SELECT s.id, s.category, s.review_state,
               b.id AS active_allocation_id,
               b.state AS allocation_state, b.target_kind
        FROM finance_cost_source_v91 s
        LEFT JOIN finance_cost_allocation_batch_v92 b
          ON b.tenant_id=s.tenant_id AND b.source_id=s.id
         AND b.state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED')
        WHERE s.tenant_id=? AND s.seller_marketplace_id=?
          AND s.category IN ('INBOUND_FREIGHT','IMPORT_DUTY','PAYMENT_AND_FX')
        ORDER BY s.id, b.id LIMIT 1001
        """;

    private final JdbcTemplate jdbc;
    private final RefundGlobalReceiptCapacityServiceV213 receipts;
    private final TransactionTemplate sourceTx;

    public RefundReceiptCostProvenanceServiceV214(JdbcTemplate jdbc,
        RefundGlobalReceiptCapacityServiceV213 receipts,
        PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.receipts = receipts;
        this.sourceTx = new TransactionTemplate(manager);
        this.sourceTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.sourceTx.setReadOnly(true);
    }

    private List<RefundReceiptCostProvenanceGateV214.ManualSource> candidates(
        UUID tenant, UUID marketplace) {
        return sourceTx.execute(ignored -> {
            var rows = jdbc.query(MARKETPLACE_CANDIDATE_SQL,
                (r, n) -> new RefundReceiptCostProvenanceGateV214.ManualSource(
                    r.getObject("id", UUID.class), r.getString("category"),
                    r.getString("review_state"),
                    r.getObject("active_allocation_id", UUID.class),
                    r.getString("allocation_state"), r.getString("target_kind")),
                tenant, marketplace);
            if (rows.size() > RefundReceiptCostProvenanceGateV214.MAX_SOURCE_ROWS)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Marketplace manual cost candidate population > 1000");
            return List.copyOf(rows);
        });
    }

    public RefundReceiptCostProvenanceGateV214.Result preview(UUID tenant,
        UUID marketplace, UUID order) {
        if (tenant == null || marketplace == null || order == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Tenant, Marketplace and LIVE order required");
        var before = candidates(tenant, marketplace);
        var localReceipts = receipts.preview(tenant, marketplace, order);
        var after = candidates(tenant, marketplace);
        if (!before.equals(after))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Manual cost source or allocation changed during receipt audit");
        try {
            return RefundReceiptCostProvenanceGateV214.evaluate(
                marketplace, order, localReceipts, after);
        } catch (IllegalArgumentException inconsistent) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Manual source identity or receipt financial hold inconsistent",
                inconsistent);
        }
    }
}
