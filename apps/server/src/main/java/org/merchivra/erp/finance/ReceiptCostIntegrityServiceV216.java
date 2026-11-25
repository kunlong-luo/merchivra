package org.merchivra.erp.finance;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** V216 read-only source audit; no V112/V91/V92 updates and no COGS writes. */
@Service
public class ReceiptCostIntegrityServiceV216 {
    // Whole-plan LATERAL sums are not limited to the requested receipt. Otherwise
    // a receipt could look balanced while the same source is overallocated
    // elsewhere. The outer LIMIT 51 never truncates a successful response.
    static final String RECEIPT_SCOPE_SQL = """
        SELECT p.id AS plan_id, p.source_id, p.state,
          s.review_state, btrim(s.currency_code) AS source_currency,
          s.category, s.amount AS source_amount,
          btrim(p.source_currency) AS snapshot_currency,
          p.source_amount AS snapshot_amount, l.amount AS receipt_amount,
          totals.allocated, totals.line_count, totals.currency_count,
          totals.valid_receipt_count,
          (SELECT count(*) FROM finance_cost_allocation_batch_v92 v92
           WHERE v92.tenant_id=p.tenant_id AND v92.source_id=p.source_id
             AND v92.state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED')) AS v92_count,
          (plan_audit.total_count=CASE WHEN p.state='PENDING_REVIEW' THEN 1 ELSE 2 END
            AND plan_audit.initial_count=1
            AND (p.state<>'PENDING_REVIEW' OR plan_audit.decision_count=0))
            AS initial_audit,
          (p.state<>'PENDING_REVIEW' AND p.reviewed_by IS NOT NULL
            AND p.reviewed_by<>p.proposed_by AND p.reviewed_by<>s.created_by
            AND plan_audit.total_count=2 AND plan_audit.decision_count=1)
            AS decision_audit,
          (s.review_state='REVIEWED_UNVERIFIED' AND s.reviewed_by IS NOT NULL
            AND s.reviewed_by<>s.created_by AND source_audit.total_count=2
            AND source_audit.stage_count=1 AND source_audit.review_count=1)
            AS review_audit
        FROM finance_receipt_cost_line_v112 l
        JOIN finance_receipt_cost_plan_v112 p
          ON p.tenant_id=l.tenant_id AND p.id=l.plan_id
        JOIN finance_cost_source_v91 s
          ON s.tenant_id=p.tenant_id AND s.id=p.source_id
        JOIN LATERAL (
          SELECT coalesce(sum(all_lines.amount),0::numeric) AS allocated,
            count(*)::bigint AS line_count,
            count(*) FILTER (WHERE po.currency_code=p.source_currency)
              ::bigint AS currency_count,
            count(pr.id)::bigint AS valid_receipt_count
          FROM finance_receipt_cost_line_v112 all_lines
          LEFT JOIN erp_purchase_receipt pr
            ON pr.tenant_id=all_lines.tenant_id AND pr.id=all_lines.receipt_id
          LEFT JOIN erp_purchase_order po
            ON po.tenant_id=pr.tenant_id AND po.id=pr.purchase_order_id
          WHERE all_lines.tenant_id=p.tenant_id AND all_lines.plan_id=p.id
        ) totals ON true
        JOIN LATERAL (
          SELECT count(*) AS total_count,
            count(*) FILTER (WHERE a.action='PENDING_REVIEW'
              AND a.actor_id=p.proposed_by) AS initial_count,
            count(*) FILTER (WHERE a.action=p.state
              AND a.action IN ('APPROVED_UNVERIFIED','REJECTED')
              AND a.actor_id=p.reviewed_by) AS decision_count
          FROM finance_receipt_cost_audit_v112 a
          WHERE a.tenant_id=p.tenant_id AND a.plan_id=p.id
        ) plan_audit ON true
        JOIN LATERAL (
          SELECT count(*) AS total_count,
            count(*) FILTER (WHERE a.action='STAGED'
              AND a.actor_id=s.created_by) AS stage_count,
            count(*) FILTER (WHERE a.action='REVIEWED_UNVERIFIED'
              AND a.actor_id=s.reviewed_by) AS review_count
          FROM finance_cost_source_audit_v91 a
          WHERE a.tenant_id=s.tenant_id AND a.source_id=s.id
        ) source_audit ON true
        WHERE l.tenant_id=? AND l.receipt_id=?
          AND s.seller_marketplace_id=?
        ORDER BY p.id LIMIT 51
        """;

    private final JdbcTemplate jdbc;
    private final CostIdentityReconciliationService reconciliation;
    private final TransactionTemplate tx;
    // Null in every normal deployment. Double-gated bean only in disposable CI.
    @Autowired(required=false)
    private ReceiptCostIntegrityCiBoundaryV222 ciReadBoundary;

    public ReceiptCostIntegrityServiceV216(JdbcTemplate jdbc,
        CostIdentityReconciliationService reconciliation,
        PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.reconciliation=reconciliation;
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setReadOnly(true);
    }

    // V221 / Flyway V113: unlike result equality, a committed epoch cannot
    // silently return to its old value after an A->B->A source mutation.
    record Snapshot(List<ReceiptCostIntegrityGateV216.Source> sources,
        Map<UUID,CostIdentityReconciliationService.Preview> overlaps,
        long committedEvidenceRevision) {}

    // Package-local reuse by the V249 single-snapshot candidate calculator.
    Snapshot snapshot(UUID tenant,UUID marketplace,UUID receipt) {
        return tx.execute(ignored -> {
            // Scope and evidence must share the SAME RR snapshot. An outer
            // non-transactional scope check could be stale before the scan.
            Integer scope=jdbc.queryForObject("""
                SELECT count(*) FROM erp_purchase_receipt r
                JOIN tenant t ON t.id=r.tenant_id AND t.status='ACTIVE'
                WHERE r.tenant_id=? AND r.id=?
                  AND EXISTS(SELECT 1 FROM seller_marketplace m
                    WHERE m.tenant_id=r.tenant_id AND m.id=?)
                """,Integer.class,tenant,receipt,marketplace);
            if(scope==null || scope!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            var rows=jdbc.query(RECEIPT_SCOPE_SQL,(r,i)->
                new ReceiptCostIntegrityGateV216.Source(
                    r.getObject("plan_id",UUID.class),
                    r.getObject("source_id",UUID.class),
                    r.getString("state"),r.getString("review_state"),
                    r.getString("source_currency"),r.getString("category"),
                    r.getBigDecimal("source_amount"),
                    r.getString("snapshot_currency"),
                    r.getBigDecimal("snapshot_amount"),
                    r.getBigDecimal("receipt_amount"),
                    r.getBigDecimal("allocated"),r.getLong("line_count"),
                    r.getLong("currency_count"),r.getLong("valid_receipt_count"),
                    r.getLong("v92_count"),r.getBoolean("initial_audit"),
                    r.getBoolean("decision_audit"),r.getBoolean("review_audit")),
                tenant,receipt,marketplace);
            if (rows.size()>ReceiptCostIntegrityGateV216.MAX_PLANS)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Receipt cost identity population exceeds 50 plans");
            List<UUID> ids=rows.stream().map(
                ReceiptCostIntegrityGateV216.Source::sourceId).toList();
            Map<UUID,CostIdentityReconciliationService.Preview> flags=
                ids.isEmpty()?Map.of():reconciliation.previewBatch(
                    tenant,marketplace,ids);
            // Query after the evidence scan but INSIDE this RR transaction:
            // it must observe the same PostgreSQL visibility snapshot as all
            // V112/V91/V92/V165 SQL statements. The first mutation starts at 1;
            // historical tenants with no V113 event observe 0, NOT verified.
            Long epoch=jdbc.queryForObject("""
                SELECT coalesce((
                  SELECT revision FROM finance_receipt_read_epoch_v113
                  WHERE tenant_id=?
                ),0)::bigint
                """,Long.class,tenant);
            if(epoch==null || epoch<0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Receipt source revision unavailable");
            return new Snapshot(List.copyOf(rows),flags,epoch);
        });
    }

    public ReceiptCostIntegrityGateV216.Result preview(
        UUID tenant,UUID marketplace,UUID receipt) {
        if(tenant==null||marketplace==null||receipt==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Tenant, marketplace and receipt required");
        var before=snapshot(tenant,marketplace,receipt);
        // V222: no-op unless BOTH ci-v222 profile + explicit property
        // activate the test-only bean in disposable GitHub Actions PG18.
        // In production there is no I/O, hook or additional cost mutation.
        if(ciReadBoundary!=null)ciReadBoundary.afterFirstSnapshot(receipt);
        var after=snapshot(tenant,marketplace,receipt);
        // The V113 tenant epoch catches a committed A->B->A change whose
        // two readable source/collision populations are otherwise IDENTICAL.
        if(before.committedEvidenceRevision()!=after.committedEvidenceRevision())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Receipt cost evidence changed during independent read");
        if(!before.sources().equals(after.sources())
            || !before.overlaps().equals(after.overlaps()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Receipt allocation/source collision population changed");
        try {
            return ReceiptCostIntegrityGateV216.evaluate(marketplace,receipt,
                after.sources(),after.overlaps());
        }catch(IllegalArgumentException inconsistent) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Receipt cost source audit identity or amount inconsistent",
                inconsistent);
        }
    }
}
