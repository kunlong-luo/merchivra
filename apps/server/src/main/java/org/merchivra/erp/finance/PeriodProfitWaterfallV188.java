package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * V188 original-currency arithmetic bridge: frozen V64 reviewed-source
 * order contribution + unique V91/V92 manual cost proposals + live V165/168
 * collision evidence. NEVER a V162 REVIEWED_COST, G1 accrual or V64 ledger.
 *
 * Different date axes are retained (V64 order UTC date vs V91 source business
 * date). Even the most complete sensitivity calculation is NOT net profit.
 */
public final class PeriodProfitWaterfallV188 {
    private PeriodProfitWaterfallV188() {}
    public static final String RULE =
        "V188_V64_ELIGIBLE_CONTRIBUTION_MINUS_V92_APPROVED_UNVERIFIED_ORIGINAL_CURRENCY_V1";

    public record FrozenAmounts(long eligibleRows,long blockedRows,long pendingRows,
        long wrongCurrencyRows,long missingAmountRows,
        String signedSales,String signedAmazonFees,String signedFbaFees,
        String provisionalCogs,String contribution) {}

    public record CostLine(String category,String currency,String primaryDimension,
        long sourceCount,long approvedUnverifiedSourceCount,long pendingSourceCount,
        long unassignedSourceCount,long missingApprovalAuditCount,
        String claimedAmount,String approvedUnverifiedAllocatedAmount,
        String approvedUnverifiedRemainderAmount) {}

    public record Preview(String marketplaceId,String reportJobId,
        String periodStart,String periodEndExclusive,String currency,
        String ruleVersion,String dateBasis,String sourceSnapshotBasis,
        String jobState,int totalOrders,int eligibleOrders,int blockedOrders,
        long manualCostSourceCount,long potentialCollisionSourceCount,
        long sharedSettlementLineSourceCount,
        String eligibleSignedSales,String eligibleSignedAmazonFees,
        String eligibleSignedFbaFees,String eligibleProvisionalCogs,
        String eligibleContribution,
        String approvedUnverifiedLocalCostExposure,
        String unverifiedCostSensitivityContribution,
        String sensitivityState,List<CostLine> costLines,
        List<String> categoriesWithoutSourceClaims,List<String> blockerCodes,
        boolean financeFeesExcludedFromAdditionalCosts,
        boolean additionalCostExternallyVerified,
        boolean accountingPolicyApproved,boolean refundCogsReversalVerified,
        boolean fxConversionApproved,boolean bankSettlementVerified,
        boolean ledgerPosted,boolean realizedNetProfitVerified,
        String realizedNetProfit) {}

    private static ResponseStatusException inconsistent(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT,detail);
    }
    private static BigDecimal money(String value,boolean signed) {
        if(value==null||!value.matches(signed
                ?"-?[0-9]{1,20}(\\.[0-9]{1,6})?"
                :"[0-9]{1,20}(\\.[0-9]{1,6})?"))
            throw inconsistent("Frozen original-currency money is invalid");
        try {return new BigDecimal(value).setScale(6,RoundingMode.UNNECESSARY);}
        catch(ArithmeticException bad){throw inconsistent("Invalid V188 money scale");}
    }
    private static String display(BigDecimal value) {
        return value.setScale(6,RoundingMode.UNNECESSARY).toPlainString();
    }

    static Preview calculate(UUID marketplace,ProfitReportJobService.Job job,
        FrozenAmounts frozen,CostOwnershipBridgeServiceV186.Report ownership,
        CostCrossSourceRiskServiceV187.Report risk) {
        Objects.requireNonNull(marketplace);
        Objects.requireNonNull(job);
        Objects.requireNonNull(frozen);
        Objects.requireNonNull(ownership);
        Objects.requireNonNull(risk);
        if(!job.currency().matches("[A-Z]{3}")
            ||!UUID.fromString(job.id()).toString().equals(job.id())
            ||!marketplace.toString().equals(ownership.marketplaceId())
            ||!marketplace.toString().equals(risk.marketplaceId())
            ||!job.after().equals(ownership.periodStart())
            ||!job.before().equals(ownership.periodEndExclusive())
            ||!job.after().equals(risk.periodStart())
            ||!job.before().equals(risk.periodEndExclusive())
            ||!ownership.sourceDateBasis().equals(
                "V91_SOURCE_BUSINESS_DATE_NOT_ACCOUNTING_RECOGNITION_DATE")
            ||!risk.dateBasis().equals(ownership.sourceDateBasis())
            ||ownership.sourceCount()!=risk.sourceCount()
            ||!List.of("READY","RETRY","PAUSED","SUCCEEDED").contains(job.state())
            ||job.totalOrders()<0||job.eligibleOrders()<0||job.blockedOrders()<0
            ||job.processedOrders()<0||job.processedOrders()>job.totalOrders()
            ||job.eligibleOrders()+job.blockedOrders()>job.processedOrders()
            ||frozen.eligibleRows()<0||frozen.blockedRows()<0||frozen.pendingRows()<0
            ||frozen.wrongCurrencyRows()<0||frozen.missingAmountRows()<0
            ||frozen.eligibleRows()!=job.eligibleOrders()
            ||frozen.blockedRows()!=job.blockedOrders()
            ||frozen.pendingRows()+frozen.eligibleRows()+frozen.blockedRows()
                !=job.totalOrders()
            ||frozen.wrongCurrencyRows()!=0||frozen.missingAmountRows()!=0
            ||risk.anyLocalCollisionSourceCount()<0
            ||risk.anyLocalCollisionSourceCount()>risk.sourceCount()
            ||risk.potentialIdentityOverlapSourceCount()>risk.anyLocalCollisionSourceCount()
            ||risk.sharedSettlementLineSourceCount()>risk.anyLocalCollisionSourceCount()
            ||job.financialSourceComplete()||job.bankVerified()||job.ledgerPosted()
            ||risk.duplicateDebitVerified()||risk.externalChargeVerified()
            ||risk.refundCogsReversalVerified()||risk.accountingPolicyApproved()
            ||risk.bankReconciled()||risk.netProfitEligible()
            ||risk.realizedNetProfit()!=null
            ||ownership.doubleCountingAcrossDimensionsAllowed()
            ||ownership.externalChargeVerified()
            ||ownership.refundCogsReversalVerified()||ownership.fxConversionApproved()
            ||ownership.bankReconciled()||ownership.ledgerPosted()
            ||ownership.netProfitEligible()
            ||ownership.realizedNetProfit()!=null)
            throw inconsistent("Frozen cohort, scoped cost or collision proof is inconsistent");

        BigDecimal sales=money(frozen.signedSales(),true);
        BigDecimal amazon=money(frozen.signedAmazonFees(),true);
        BigDecimal fba=money(frozen.signedFbaFees(),true);
        BigDecimal cogs=money(frozen.provisionalCogs(),false);
        BigDecimal contrib=money(frozen.contribution(),true);
        if(sales.add(amazon).add(fba).subtract(cogs).compareTo(contrib)!=0)
            throw inconsistent("V64 signed-sales, signed-fees and COGS do not conserve");

        var reasons=new ArrayList<String>();
        reasons.add("V64_ORDER_UTC_V91_SOURCE_BUSINESS_DATE_AXES_DIFFER");
        reasons.add("V64_BATCH_FROZEN_V91_V92_V165_V168_EVIDENCE_LIVE");
        reasons.add("MANUAL_V91_V92_COSTS_NOT_INDEPENDENTLY_EXTERNALLY_ATTESTED");
        reasons.add("G1_DEC_06_07_08_POLICY_NOT_APPROVED");
        reasons.add("REFUND_COGS_REVERSAL_NOT_VERIFIED");
        reasons.add("BANK_SETTLEMENT_AND_LEDGER_NOT_RECONCILED");
        reasons.add("V162_V64_REALIZED_NET_PROFIT_NOT_AVAILABLE");
        reasons.add("NO_DOUBLE_DEDUCTION_FROM_EXISTING_SIGNED_AMAZON_FBA_FEES");

        boolean fullCohort="SUCCEEDED".equals(job.state())
            &&job.processedOrders()==job.totalOrders()
            &&job.blockedOrders()==0 &&job.eligibleOrders()>0
            &&frozen.pendingRows()==0;
        String eligibleContribution=job.eligibleOrders()>0?display(contrib):null;
        if(!fullCohort)
            reasons.add(job.eligibleOrders()==0?"NO_ELIGIBLE_FINANCES_AND_COGS_BASELINE":
                "V64_COHORT_INCOMPLETE_OR_BLOCKED_ORDERS");

        var lines=new ArrayList<CostLine>();
        BigDecimal approved=BigDecimal.ZERO;
        long counted=0;
        boolean allApproved=true,allSameCurrency=true;
        for(var b:ownership.buckets()) {
            if(b==null||b.category()==null||b.currency()==null
                ||b.primaryDimension()==null
                ||!b.currency().matches("[A-Z]{3}")
                ||!List.of("SKU","ORDER","PERIOD","UNASSIGNED")
                    .contains(b.primaryDimension())
                ||b.sourceCount()<1||b.approvedUnverifiedSourceCount()<0
                ||b.pendingSourceCount()<0||b.unassignedSourceCount()<0
                ||b.approvedMissingAuditSourceCount()<0
                ||b.approvedUnverifiedSourceCount()+b.pendingSourceCount()
                    +b.unassignedSourceCount()!=b.sourceCount()
                ||b.derivativeSkuOrderPeriodProfitDeductionsAllowed()
                ||b.externalChargeVerified()||b.netProfitEligible())
                throw inconsistent("V186 category ownership facts were changed");
            try {FullCostCoverageEngine.Category.valueOf(b.category());}
            catch(IllegalArgumentException bad) {throw inconsistent("Unknown V162 cost category");}
            BigDecimal claimed=money(b.sourceClaimedAmount(),false);
            if(claimed.signum()<=0)throw inconsistent("Empty V91 source claim");
            counted=Math.addExact(counted,b.sourceCount());
            allSameCurrency&=job.currency().equals(b.currency());
            boolean isApproved=b.approvedUnverifiedSourceCount()>0;
            if(isApproved!=(b.approvedUnverifiedAllocatedAmount()!=null)
                ||isApproved!=(b.approvedUnverifiedRemainderAmount()!=null))
                throw inconsistent("V92 approved costs cannot be inferred from missing amounts");
            BigDecimal assigned=isApproved
                ?money(b.approvedUnverifiedAllocatedAmount(),false):BigDecimal.ZERO;
            BigDecimal remainder=isApproved
                ?money(b.approvedUnverifiedRemainderAmount(),false):BigDecimal.ZERO;
            if(assigned.add(remainder).compareTo(claimed)>0)
                throw inconsistent("V92 amount exceeds its original source claims");
            if(b.approvedMissingAuditSourceCount()>0
                ||b.pendingSourceCount()>0||b.unassignedSourceCount()>0
                ||b.approvedUnverifiedSourceCount()!=b.sourceCount()
                ||assigned.add(remainder).compareTo(claimed)!=0
                ||remainder.signum()!=0||assigned.signum()<=0)
                allApproved=false;
            // no cross-currency conversion, no fee double-counting with V33.
            if(job.currency().equals(b.currency()))approved=approved.add(assigned);
            lines.add(new CostLine(b.category(),b.currency(),b.primaryDimension(),
                b.sourceCount(),b.approvedUnverifiedSourceCount(),
                b.pendingSourceCount(),b.unassignedSourceCount(),
                b.approvedMissingAuditSourceCount(),display(claimed),
                isApproved?display(assigned):null,
                isApproved?display(remainder):null));
        }
        if(counted!=ownership.sourceCount())
            throw inconsistent("Cost owners must count each original V91 source once");
        if(!allSameCurrency)reasons.add("OTHER_ORIGINAL_CURRENCY_CANNOT_BE_CONVERTED");
        if(!allApproved)reasons.add("V92_DISTRIBUTION_OR_AUDIT_INCOMPLETE");
        if(ownership.sourceCount()==0)
            reasons.add("MISSING_MANUAL_COST_SOURCE_IS_UNKNOWN_NOT_ZERO");
        if(risk.anyLocalCollisionSourceCount()>0)
            reasons.add("V165_V168_CROSS_SOURCE_COLLISION_HOLD");
        if(!ownership.categoriesWithoutSourceClaims().isEmpty())
            reasons.add("EIGHT_COST_CATEGORIES_NOT_ALL_OBSERVED");

        String state;
        String scenario=null,expense=null;
        if(!fullCohort)state="ORDER_FINANCIAL_BASELINE_INCOMPLETE";
        else if(ownership.sourceCount()==0)state="ADDITIONAL_COST_SOURCES_UNKNOWN";
        else if(!allSameCurrency)state="MIXED_ORIGINAL_CURRENCY_BLOCKED";
        else if(risk.anyLocalCollisionSourceCount()>0)state="CROSS_SOURCE_COLLISION_BLOCKED";
        else if(!allApproved)state="V92_UNVERIFIED_COST_DISTRIBUTION_INCOMPLETE";
        else {
            state="UNVERIFIED_COST_SENSITIVITY_ONLY";
            expense=display(approved);
            scenario=display(contrib.subtract(approved));
        }
        return new Preview(marketplace.toString(),job.id(),job.after(),job.before(),
            job.currency(),RULE,
            "V64_ORDER_UTC_V91_COST_SOURCE_BUSINESS_DATE",
            "V64_IMMUTABLE_ORDER_EXTRACTS_V91_V92_V165_V168_LIVE_READ",
            job.state(),job.totalOrders(),job.eligibleOrders(),job.blockedOrders(),
            ownership.sourceCount(),risk.anyLocalCollisionSourceCount(),
            risk.sharedSettlementLineSourceCount(),
            job.eligibleOrders()>0?display(sales):null,
            job.eligibleOrders()>0?display(amazon):null,
            job.eligibleOrders()>0?display(fba):null,
            job.eligibleOrders()>0?display(cogs):null,eligibleContribution,
            expense,scenario,state,List.copyOf(lines),
            List.copyOf(ownership.categoriesWithoutSourceClaims()),
            List.copyOf(reasons),true,false,false,false,false,false,false,false,null);
    }
}
