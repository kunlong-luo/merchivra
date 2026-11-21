package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * V186: one original V91 source -> at most one ACTIVE V92 allocation dimension.
 * Full-period READ-ONLY diagnostics, not a ledger, approved cost or V162 feed.
 *
 * A source may be allocated against SKU OR ORDER OR PERIOD, never summed as a
 * new debit in every view. This groups each source once before aggregating;
 * rejected history does not become another active or countable cost.
 */
@Service
public class CostOwnershipBridgeServiceV186 {
    private final JdbcTemplate jdbc;

    public CostOwnershipBridgeServiceV186(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public record Bucket(String category,String currency,String primaryDimension,
        long sourceCount,long approvedUnverifiedSourceCount,
        long pendingSourceCount,long unassignedSourceCount,
        long approvedMissingAuditSourceCount,
        String sourceClaimedAmount,String approvedUnverifiedAllocatedAmount,
        String approvedUnverifiedRemainderAmount,
        boolean derivativeSkuOrderPeriodProfitDeductionsAllowed,
        boolean externalChargeVerified,boolean netProfitEligible) {}

    public record Report(String marketplaceId,String periodStart,String periodEndExclusive,
        String sourceDateBasis,long sourceCount,List<Bucket> buckets,
        List<String> categoriesWithoutSourceClaims,List<String> blockerCodes,
        boolean doubleCountingAcrossDimensionsAllowed,
        boolean externalChargeVerified,boolean refundCogsReversalVerified,
        boolean fxConversionApproved,boolean bankReconciled,
        boolean ledgerPosted,boolean netProfitEligible,String realizedNetProfit) {}

    record Aggregate(String category,String currency,String dimension,
        long sources,long approved,long pending,long unassigned,
        long missingAudit,long malformed,
        BigDecimal claimed,BigDecimal approvedAllocated,
        BigDecimal approvedRemainder) {}

    static void validate(LocalDate start,LocalDate end) {
        if(start==null||end==null||!start.isBefore(end)
            ||ChronoUnit.DAYS.between(start,end)>366)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A bounded half-open source business-date period is required");
    }
    private static String money(BigDecimal x) {
        if(x==null||x.signum()<0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Original-currency cost aggregates are invalid");
        try {
            return x.setScale(6,RoundingMode.UNNECESSARY).toPlainString();
        } catch(ArithmeticException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Original-currency source precision is invalid");
        }
    }

    static Report summarize(UUID marketplace,LocalDate start,LocalDate end,
        List<Aggregate> groups) {
        if(marketplace==null||groups==null)
            throw new IllegalArgumentException("Missing cost ownership source scope");
        validate(start,end);
        var buckets=new ArrayList<Bucket>();
        var seen=new java.util.HashSet<String>();
        var present=EnumSet.noneOf(FullCostCoverageEngine.Category.class);
        long total=0;
        for(var g:groups) {
            if(g==null||g.category()==null||g.currency()==null||g.dimension()==null
                ||!g.currency().matches("[A-Z]{3}")
                ||!Set.of("SKU","ORDER","PERIOD","UNASSIGNED")
                    .contains(g.dimension())
                ||g.sources()<=0||g.approved()<0||g.pending()<0
                ||g.unassigned()<0||g.missingAudit()<0||g.malformed()!=0
                ||g.approved()+g.pending()+g.unassigned()!=g.sources()
                ||g.missingAudit()>g.approved()
                ||g.claimed()==null||g.approvedAllocated()==null
                ||g.approvedRemainder()==null)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "V92 source dimension uniqueness or scope is inconsistent");
            FullCostCoverageEngine.Category category;
            try {category=FullCostCoverageEngine.Category.valueOf(g.category());}
            catch(IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Unexpected cost category in source bridge");
            }
            if("UNASSIGNED".equals(g.dimension()) && g.unassigned()!=g.sources()
                ||!"UNASSIGNED".equals(g.dimension()) && g.unassigned()!=0
                ||!seen.add(g.category()+"|"+g.currency()+"|"+g.dimension()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A cost source appears in incompatible dimensions");
            // These are unverified source claims, not eligible expense entries.
            var claim=money(g.claimed());
            var assigned=money(g.approvedAllocated());
            var remainder=money(g.approvedRemainder());
            if(g.approved()==0 && (g.approvedAllocated().signum()!=0
                ||g.approvedRemainder().signum()!=0)
                ||g.approved()>0
                    &&g.approvedAllocated().add(g.approvedRemainder())
                        .compareTo(g.claimed())>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approved source allocation arithmetic is inconsistent");
            total=Math.addExact(total,g.sources());
            present.add(category);
            buckets.add(new Bucket(g.category(),g.currency(),g.dimension(),
                g.sources(),g.approved(),g.pending(),g.unassigned(),
                g.missingAudit(),claim,
                g.approved()==0?null:assigned,
                g.approved()==0?null:remainder,
                false,false,false));
        }
        var missing=new ArrayList<String>();
        for(var category:FullCostCoverageEngine.Category.values())
            if(!present.contains(category))missing.add(category.name());
        var blockers=new ArrayList<String>();
        blockers.add("INDEPENDENT_EXTERNAL_COST_SOURCE_ATTESTATION_MISSING");
        blockers.add("G1_MULTI_DIMENSION_COST_OWNERSHIP_POLICY_NOT_APPROVED");
        blockers.add("REFUND_COGS_REVERSAL_NEEDS_PHYSICAL_RECEIPT_AND_FINANCIAL_POLICY");
        blockers.add("DEC_08_FX_CONVERSION_NOT_APPROVED");
        blockers.add("BANK_RECONCILIATION_AND_LEDGER_NOT_VERIFIED");
        blockers.add("V162_V64_RECOGNIZED_PROFIT_NOT_AVAILABLE");
        if(!missing.isEmpty())blockers.add("COST_CATEGORIES_WITHOUT_SOURCE_CLAIMS_UNKNOWN_NOT_ZERO");
        if(groups.stream().anyMatch(g->g.pending()>0))
            blockers.add("PENDING_V92_ALLOCATION_REVIEW");
        if(groups.stream().anyMatch(g->g.unassigned()>0))
            blockers.add("SOURCE_WITHOUT_ACTIVE_V92_OWNER_DIMENSION");
        if(groups.stream().anyMatch(g->g.missingAudit()>0))
            blockers.add("INDEPENDENT_V92_APPROVAL_AUDIT_MISSING");
        if(groups.stream().anyMatch(g->g.approved()>0))
            blockers.add("APPROVED_V92_DISTRIBUTION_REMAINS_UNVERIFIED");
        return new Report(marketplace.toString(),start.toString(),end.toString(),
            "V91_SOURCE_BUSINESS_DATE_NOT_ACCOUNTING_RECOGNITION_DATE",
            total,List.copyOf(buckets),List.copyOf(missing),List.copyOf(blockers),
            false,false,false,false,false,false,false,null);
    }

    /**
     * The active-batch LATERAL is aggregated first so rejected proposals and
     * line-level fan-out cannot multiply the original source. V92's partial
     * unique index ensures <=1 active; a count or sum inconsistency still
     * causes a fail-closed response instead of a misleading profit figure.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report report(UUID tenant,UUID marketplace,LocalDate start,LocalDate end) {
        validate(start,end);
        var scoped=jdbc.query("""
            SELECT m.id
            FROM seller_marketplace m
            JOIN tenant t ON t.id=m.tenant_id AND t.status='ACTIVE'
            JOIN seller_account a ON a.tenant_id=m.tenant_id
              AND a.id=m.seller_account_id
            WHERE m.tenant_id=? AND m.id=?
            """,(r,i)->r.getObject(1,UUID.class),tenant,marketplace);
        if(scoped.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var rows=jdbc.query("""
            WITH source_claim AS (
              SELECT s.id,s.category,btrim(s.currency_code) AS currency,
                s.amount,active.active_count,active.batch_id,
                active.target_kind,active.state,
                coalesce(line_amount.allocated,0::numeric) AS allocated,
                coalesce(line_amount.line_count,0::bigint) AS line_count,
                EXISTS(
                  SELECT 1 FROM finance_cost_allocation_audit_v92 a
                  WHERE a.tenant_id=s.tenant_id
                    AND a.batch_id=active.batch_id
                    AND a.action='APPROVED_UNVERIFIED'
                    AND a.actor_id=active.reviewed_by
                ) AS approval_audited
              FROM finance_cost_source_v91 s
              LEFT JOIN LATERAL (
                SELECT count(*) AS active_count,
                  max(b.id::text)::uuid AS batch_id,
                  max(b.target_kind) AS target_kind,
                  max(b.state) AS state,
                  max(b.reviewed_by::text)::uuid AS reviewed_by
                FROM finance_cost_allocation_batch_v92 b
                WHERE b.tenant_id=s.tenant_id AND b.source_id=s.id
                  AND b.state IN ('PENDING_REVIEW','APPROVED_UNVERIFIED')
              ) active ON true
              LEFT JOIN LATERAL (
                SELECT coalesce(sum(l.expense_amount),0::numeric) AS allocated,
                  count(*) AS line_count
                FROM finance_cost_allocation_line_v92 l
                WHERE l.tenant_id=s.tenant_id
                  AND l.batch_id=active.batch_id
              ) line_amount ON true
              WHERE s.tenant_id=? AND s.seller_marketplace_id=?
                AND s.business_date>=? AND s.business_date<?
            )
            SELECT category,currency,
              coalesce(target_kind,'UNASSIGNED') AS dimension,
              count(*) AS source_count,
              count(*) FILTER(WHERE state='APPROVED_UNVERIFIED') AS approved_count,
              count(*) FILTER(WHERE state='PENDING_REVIEW') AS pending_count,
              count(*) FILTER(WHERE active_count=0) AS unassigned_count,
              count(*) FILTER(WHERE state='APPROVED_UNVERIFIED'
                AND NOT approval_audited) AS missing_audit_count,
              count(*) FILTER(WHERE active_count>1
                OR state='APPROVED_UNVERIFIED' AND
                  (allocated<=0 OR allocated>amount OR line_count=0)
                OR active_count=0 AND target_kind IS NOT NULL
                OR active_count=1 AND (target_kind IS NULL OR state IS NULL)
              ) AS malformed_count,
              sum(amount) AS claimed,
              sum(CASE WHEN state='APPROVED_UNVERIFIED'
                THEN allocated ELSE 0::numeric END) AS approved_allocated,
              sum(CASE WHEN state='APPROVED_UNVERIFIED'
                THEN amount-allocated ELSE 0::numeric END) AS approved_remainder
            FROM source_claim
            GROUP BY category,currency,coalesce(target_kind,'UNASSIGNED')
            ORDER BY category,currency,dimension
            """,(r,i)->new Aggregate(
                r.getString("category"),r.getString("currency"),
                r.getString("dimension"),r.getLong("source_count"),
                r.getLong("approved_count"),r.getLong("pending_count"),
                r.getLong("unassigned_count"),r.getLong("missing_audit_count"),
                r.getLong("malformed_count"),r.getBigDecimal("claimed"),
                r.getBigDecimal("approved_allocated"),
                r.getBigDecimal("approved_remainder")),
            tenant,marketplace,start,end);
        return summarize(marketplace,start,end,rows);
    }
}
