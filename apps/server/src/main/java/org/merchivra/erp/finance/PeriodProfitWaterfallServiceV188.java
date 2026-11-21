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
              coalesce(sum(signed_sales) FILTER(WHERE state='ELIGIBLE'),0::numeric)
                AS signed_sales,
              coalesce(sum(signed_amazon_fees) FILTER(WHERE state='ELIGIBLE'),0::numeric)
                AS signed_amazon_fees,
              coalesce(sum(signed_fba_fees) FILTER(WHERE state='ELIGIBLE'),0::numeric)
                AS signed_fba_fees,
              coalesce(sum(provisional_cogs) FILTER(WHERE state='ELIGIBLE'),0::numeric)
                AS provisional_cogs,
              coalesce(sum(estimated_contribution) FILTER(WHERE state='ELIGIBLE'),0::numeric)
                AS contribution
            FROM finance_profit_report_order
            WHERE tenant_id=? AND job_id=?
            """,
            (r,i)->new PeriodProfitWaterfallV188.FrozenAmounts(
                r.getLong("eligible_count"),r.getLong("blocked_count"),
                r.getLong("pending_count"),r.getLong("wrong_currency_count"),
                r.getLong("missing_amount_count"),
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
