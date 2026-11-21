package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * V188 one READ-ONLY repeatable snapshot, combining V64 durable order extracts
 * with live V91/V92 ownership and V165/V168 risk. No new accounting records,
 * import credentials, G1 certification or V162 REVIEWED_COST promotion.
 */
@Service
public class PeriodProfitWaterfallServiceV188 {
    private final JdbcTemplate jdbc;
    private final ProfitReportJobService reports;
    private final CostOwnershipBridgeServiceV186 owners;
    private final CostCrossSourceRiskServiceV187 risks;

    public PeriodProfitWaterfallServiceV188(JdbcTemplate jdbc,
        ProfitReportJobService reports,CostOwnershipBridgeServiceV186 owners,
        CostCrossSourceRiskServiceV187 risks) {
        this.jdbc=jdbc;this.reports=reports;this.owners=owners;this.risks=risks;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PeriodProfitWaterfallV188.Preview preview(UUID tenant,UUID marketplace,
        UUID reportJobId) {
        if(reportJobId==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        // V64's existing scoped lookup is the ONLY way to discover this job.
        // Do not look up a report ID in another Marketplace or tenant.
        var job=reports.status(tenant,marketplace,reportJobId);
        LocalDate start=LocalDate.parse(job.after());
        LocalDate end=LocalDate.parse(job.before());
        CostOwnershipBridgeServiceV186.validate(start,end);
        var frozen=jdbc.query("""
            SELECT count(*) FILTER(WHERE state='ELIGIBLE') AS eligible_count,
              count(*) FILTER(WHERE state='BLOCKED') AS blocked_count,
              count(*) FILTER(WHERE state='PENDING') AS pending_count,
              count(*) FILTER(WHERE state='ELIGIBLE'
                AND (currency_code IS NULL
                  OR btrim(currency_code)<>?)) AS wrong_currency_count,
              count(*) FILTER(WHERE state='ELIGIBLE'
                AND (signed_sales IS NULL OR signed_amazon_fees IS NULL
                  OR signed_fba_fees IS NULL OR provisional_cogs IS NULL
                  OR estimated_contribution IS NULL)) AS missing_amount_count,
              -- V192: V62 checks each SKU and order's own arithmetic, but
              -- cannot constrain the SUM of SKU rows to its parent order.
              -- A missing SKU, an extra SKU on BLOCKED/PENDING, or opposite
              -- per-order errors must not cancel in the period aggregate.
              count(*) FILTER(WHERE
                (o.state='ELIGIBLE' AND
                  (sk.sku_count=0
                    OR sk.signed_sales IS DISTINCT FROM o.signed_sales
                    OR sk.signed_amazon_fees IS DISTINCT FROM o.signed_amazon_fees
                    OR sk.signed_fba_fees IS DISTINCT FROM o.signed_fba_fees
                    OR sk.provisional_cogs IS DISTINCT FROM o.provisional_cogs
                    OR sk.estimated_contribution IS DISTINCT FROM o.estimated_contribution))
                OR (o.state<>'ELIGIBLE' AND sk.sku_count<>0)
              ) AS sku_mismatch_count,
              coalesce(sum(o.signed_sales) FILTER(WHERE state='ELIGIBLE'),0::numeric)
                AS signed_sales,
              coalesce(sum(o.signed_amazon_fees) FILTER(WHERE state='ELIGIBLE'),0::numeric)
                AS signed_amazon_fees,
              coalesce(sum(o.signed_fba_fees) FILTER(WHERE state='ELIGIBLE'),0::numeric)
                AS signed_fba_fees,
              coalesce(sum(o.provisional_cogs) FILTER(WHERE state='ELIGIBLE'),0::numeric)
                AS provisional_cogs,
              coalesce(sum(o.estimated_contribution) FILTER(WHERE state='ELIGIBLE'),0::numeric)
                AS contribution
            FROM finance_profit_report_order o
            LEFT JOIN LATERAL (
              SELECT count(*) AS sku_count,
                sum(signed_sales) AS signed_sales,
                sum(signed_amazon_fees) AS signed_amazon_fees,
                sum(signed_fba_fees) AS signed_fba_fees,
                sum(provisional_cogs) AS provisional_cogs,
                sum(estimated_contribution) AS estimated_contribution
              FROM finance_profit_report_sku s
              WHERE s.tenant_id=o.tenant_id
                AND s.job_id=o.job_id AND s.order_id=o.order_id
            ) sk ON true
            WHERE o.tenant_id=? AND o.job_id=?
            """,
            (r,i)->new PeriodProfitWaterfallV188.FrozenAmounts(
                r.getLong("eligible_count"),r.getLong("blocked_count"),
                r.getLong("pending_count"),r.getLong("wrong_currency_count"),
                r.getLong("missing_amount_count"),r.getLong("sku_mismatch_count"),
                r.getBigDecimal("signed_sales").toPlainString(),
                r.getBigDecimal("signed_amazon_fees").toPlainString(),
                r.getBigDecimal("signed_fba_fees").toPlainString(),
                r.getBigDecimal("provisional_cogs").toPlainString(),
                r.getBigDecimal("contribution").toPlainString()),
            job.currency(),tenant,reportJobId);
        if(frozen.size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "V64 immutable report cohort aggregate not available");
        var owner=owners.report(tenant,marketplace,start,end);
        var risk=risks.report(tenant,marketplace,start,end);
        return PeriodProfitWaterfallV188.calculate(marketplace,job,
            frozen.getFirst(),owner,risk);
    }
}
