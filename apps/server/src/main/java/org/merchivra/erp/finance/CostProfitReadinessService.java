package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V169: marketplace and business-date scoped, read-only manual cost exposure.
 * This is NOT the V162 reviewed-cost feed: V91 provenance remains unverified,
 * even with Owner approval or a V94 Settlement-line association.
 */
@Service
public class CostProfitReadinessService {
    private static final int PAGE_SIZE=50;
    private final JdbcTemplate jdbc;
    private final CostIdentityReconciliationService identity;

    public CostProfitReadinessService(JdbcTemplate jdbc,
        CostIdentityReconciliationService identity) {
        this.jdbc=jdbc;this.identity=identity;
    }

    public record Bucket(String category,String currency,long sourceCount,
        long independentlyReviewedMetadataCount,String claimedAmount,
        String reviewedUnverifiedAmount,String approvedUnverifiedAllocationAmount,
        String approvedUnverifiedUnallocatedAmount,long sharedLineSourceCount,
        boolean externallyVerified,boolean allowedToDeductFromProfit) {}
    public record Source(String sourceId,String businessDate,String category,
        String currency,String claimedAmount,String metadataReviewState,
        String activeAllocationState,String approvedUnverifiedAllocationAmount,
        String approvedUnverifiedUnallocatedAmount,
        String localIdentityStatus,boolean approvalHeld,
        long linkedSettlementLineCount,long sharedSettlementLineCount,
        boolean externallyVerified,boolean allowedToDeductFromProfit) {}
    public record Page(String marketplaceId,String periodStart,String periodEndExclusive,
        List<Bucket> buckets,List<Source> sources,
        boolean hasMore,String nextBeforeDate,String nextBeforeId,
        List<String> categoriesWithoutVerifiedExternalCoverage,
        boolean fullCostCovered,boolean bankReconciled,
        boolean realizedNetProfitVerified,String realizedNetProfit) {}
    private record Row(UUID id,LocalDate businessDate,String category,String currency,
        BigDecimal amount,String reviewState,String allocationState,
        BigDecimal approvedAmount) {}

    static void validate(LocalDate start,LocalDate end,
        LocalDate beforeDate,UUID beforeId) {
        if(start==null||end==null||!start.isBefore(end)
            ||ChronoUnit.DAYS.between(start,end)>366
            ||(beforeDate==null)!=(beforeId==null)
            ||beforeDate!=null&&
                (beforeDate.isBefore(start)||!beforeDate.isBefore(end)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Require a bounded half-open period of at most 366 days and a valid cursor");
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(6).toPlainString();
    }
    private static String approved(BigDecimal amount,String state) {
        return "APPROVED_UNVERIFIED".equals(state)?money(amount):null;
    }
    private static String remaining(BigDecimal amount,BigDecimal approved,String state) {
        return "APPROVED_UNVERIFIED".equals(state)
            ?money(amount.subtract(approved)):null;
    }

    private static Row row(ResultSet r,int ignored)throws SQLException {
        return new Row(r.getObject("id",UUID.class),
            r.getObject("business_date",LocalDate.class),
            r.getString("category"),r.getString("currency_code").trim(),
            r.getBigDecimal("amount"),r.getString("review_state"),
            r.getString("allocation_state"),r.getBigDecimal("approved_amount"));
    }
    private static Bucket bucket(ResultSet r,int ignored)throws SQLException {
        return new Bucket(r.getString("category"),
            r.getString("currency_code").trim(),r.getLong("source_count"),
            r.getLong("metadata_reviewed_count"),money(r.getBigDecimal("claimed")),
            money(r.getBigDecimal("reviewed_claim")),
            money(r.getBigDecimal("assigned_claim")),
            money(r.getBigDecimal("unallocated_claim")),
            r.getLong("shared_source_count"),false,false);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page report(UUID tenant,UUID marketplace,LocalDate start,LocalDate end,
        LocalDate beforeDate,UUID beforeId) {
        validate(start,end,beforeDate,beforeId);
        var available=jdbc.query("""
            SELECT m.id FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            JOIN seller_account a ON a.tenant_id=m.tenant_id
                AND a.id=m.seller_account_id
            WHERE m.tenant_id=? AND m.id=?
            """,(r,i)->r.getObject(1,UUID.class),tenant,marketplace);
        if(available.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        // Aggregate source facts exactly once; a LEFT JOIN to raw allocation
        // lines would amplify source amounts, so collapse only approved plans
        // in a per-batch scalar subquery. Pending/rejected allocations NEVER
        // contribute any "assigned" amount.
        var buckets=jdbc.query("""
            WITH scoped AS (
              SELECT s.id,s.category,s.currency_code,s.amount,s.review_state,
                b.state AS allocation_state,
                COALESCE((SELECT sum(l.expense_amount)
                  FROM finance_cost_allocation_line_v92 l
                  WHERE l.tenant_id=b.tenant_id AND l.batch_id=b.id
                    AND b.state='APPROVED_UNVERIFIED'),0) AS approved_amount,
                EXISTS(
                  SELECT 1 FROM finance_cost_settlement_line_link_v94 x
                  WHERE x.tenant_id=s.tenant_id
                    AND x.seller_marketplace_id=s.seller_marketplace_id
                    AND x.source_id=s.id
                    AND EXISTS(
                      SELECT 1 FROM finance_cost_settlement_line_link_v94 y
                      WHERE y.tenant_id=x.tenant_id
                        AND y.seller_marketplace_id=x.seller_marketplace_id
                        AND y.report_id=x.report_id
                        AND y.line_number=x.line_number
                        AND y.source_id<>x.source_id)
                ) AS shared
              FROM finance_cost_source_v91 s
              LEFT JOIN finance_cost_allocation_batch_v92 b
                ON b.tenant_id=s.tenant_id AND b.source_id=s.id
                AND b.state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED')
              WHERE s.tenant_id=? AND s.seller_marketplace_id=?
                AND s.business_date>=? AND s.business_date<?
            )
            SELECT category,currency_code,count(*) AS source_count,
              count(*) FILTER(WHERE review_state='REVIEWED_UNVERIFIED')
                AS metadata_reviewed_count,
              sum(amount) AS claimed,
              coalesce(sum(amount) FILTER(
                WHERE review_state='REVIEWED_UNVERIFIED'),0) AS reviewed_claim,
              coalesce(sum(approved_amount) FILTER(
                WHERE allocation_state='APPROVED_UNVERIFIED'),0) AS assigned_claim,
              coalesce(sum(amount-approved_amount) FILTER(
                WHERE allocation_state='APPROVED_UNVERIFIED'),0) AS unallocated_claim,
              count(*) FILTER(WHERE shared) AS shared_source_count
            FROM scoped GROUP BY category,currency_code
            ORDER BY category,currency_code
            """,CostProfitReadinessService::bucket,tenant,marketplace,start,end);

        String sql="""
            SELECT s.id,s.business_date,s.category,s.currency_code,s.amount,
              s.review_state,b.state AS allocation_state,
              COALESCE((SELECT sum(l.expense_amount)
                FROM finance_cost_allocation_line_v92 l
                WHERE l.tenant_id=b.tenant_id AND l.batch_id=b.id
                  AND b.state='APPROVED_UNVERIFIED'),0) AS approved_amount
            FROM finance_cost_source_v91 s
            LEFT JOIN finance_cost_allocation_batch_v92 b
              ON b.tenant_id=s.tenant_id AND b.source_id=s.id
              AND b.state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED')
            WHERE s.tenant_id=? AND s.seller_marketplace_id=?
              AND s.business_date>=? AND s.business_date<?
            """+(beforeDate==null?"":"""
              AND (s.business_date,s.id)<(?,?)
            """)+"""
            ORDER BY s.business_date DESC,s.id DESC LIMIT 51
            """;
        List<Row> entries=beforeDate==null?
            jdbc.query(sql,CostProfitReadinessService::row,
                tenant,marketplace,start,end):
            jdbc.query(sql,CostProfitReadinessService::row,
                tenant,marketplace,start,end,beforeDate,beforeId);
        boolean more=entries.size()>PAGE_SIZE;
        List<Row> visible=entries.subList(0,Math.min(entries.size(),PAGE_SIZE));
        var rows=new ArrayList<Source>();
        for(Row r:visible) {
            var scan=identity.preview(tenant,marketplace,r.id());
            rows.add(new Source(r.id().toString(),r.businessDate().toString(),
                r.category(),r.currency(),money(r.amount()),r.reviewState(),
                r.allocationState(),approved(r.approvedAmount(),r.allocationState()),
                remaining(r.amount(),r.approvedAmount(),r.allocationState()),
                scan.status(),scan.approvalHeld(),
                scan.linkedSettlementLineCount(),scan.sharedSettlementLineCount(),
                false,false));
        }
        // None of V91/V92/V94 is an authenticated external line-item
        // attestation. Even all 8 categories having manual claims never proves
        // completeness; "no sources" is UNKNOWN, not a validated zero.
        var missing=new ArrayList<String>();
        for(var category:FullCostCoverageEngine.Category.values())
            missing.add(category.name());
        Row last=more?visible.getLast():null;
        return new Page(marketplace.toString(),start.toString(),end.toString(),
            List.copyOf(buckets),List.copyOf(rows),more,
            last==null?null:last.businessDate().toString(),
            last==null?null:last.id().toString(),
            List.copyOf(missing),false,false,false,null);
    }
}
