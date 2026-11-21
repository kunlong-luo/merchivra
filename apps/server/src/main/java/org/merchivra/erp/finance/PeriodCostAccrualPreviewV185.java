package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.UUID;

/**
 * V185 deterministic original-currency periodization SENSITIVITY preview.
 * Uses only existing V92 immutable manual PERIOD allocation amounts and dates.
 *
 * Straight-line per calendar day is an illustrative candidate, NOT the G1
 * approved accounting policy. It is never a ledger, FX conversion, physical
 * restock proof, COGS reversal, or a verified contribution/net-profit input.
 */
public final class PeriodCostAccrualPreviewV185 {
    private PeriodCostAccrualPreviewV185() {}
    private static final BigInteger SIX=new BigInteger("1000000");
    public static final String RULE="ILLUSTRATIVE_DAILY_STRAIGHT_LINE_MICRO_UNITS_V1";

    public record Month(String period,String currency,String projectedAmount) {}
    public record Preview(String sourceId,String allocationId,String allocationState,
        String ruleVersion,String currency,String sourceAmount,
        String allocatedAmount,String unallocatedAmount,
        String projectedAmount,List<Month> months,
        boolean overlappingServiceDays,
        List<String> blockerCodes,boolean externalSourceVerified,
        boolean accountingPolicyApproved,boolean currencyConverted,
        boolean refundCogsReversalVerified,boolean ledgerPosted,
        boolean netProfitEligible,String realizedNetProfit) {}

    private static BigInteger micro(String amount) {
        return CostAllocationEngine.positive(amount)
            .setScale(6,RoundingMode.UNNECESSARY)
            .movePointRight(6).toBigIntegerExact();
    }
    private static String decimal(BigInteger units) {
        return new BigDecimal(units,6).setScale(6,RoundingMode.UNNECESSARY)
            .toPlainString();
    }

    /**
     * Each V92 PERIOD line is independently divided over its half-open daily
     * service range. Residual micro-units go to earlier dates, so no floating
     * point, implicit currency conversion or "lost" cents/micro-units.
     * Same-source overlapping ranges are signaled for policy adjudication, not
     * double-posted or silently discarded. Months are sorted chronologically.
     */
    public static Preview calculate(UUID sourceId,UUID allocationId,
        String allocationState,String sourceAmount,String currency,
        List<CostAllocationEngine.Line> lines) {
        if(sourceId==null||allocationId==null||allocationState==null
            ||!Set.of("PENDING_REVIEW","APPROVED_UNVERIFIED","REJECTED")
                .contains(allocationState))
            throw new IllegalArgumentException("Missing or invalid V92 plan identity");
        var summary=CostAllocationEngine.evaluate(sourceAmount,currency,"PERIOD",lines);
        var buckets=new TreeMap<YearMonth,BigInteger>();
        var covered=new HashSet<LocalDate>();
        boolean overlap=false;
        for(var line:lines) {
            BigInteger units=micro(line.amount());
            long count=ChronoUnit.DAYS.between(line.periodAfter(),line.periodBefore());
            // Existing V164 validator already enforces a positive <=92-day
            // half-open window; reassert here to fail closed on any drift.
            if(count<=0||count>92)
                throw new IllegalArgumentException("Invalid V92 service window");
            BigInteger[] parts=units.divideAndRemainder(BigInteger.valueOf(count));
            for(long day=0;day<count;day++) {
                LocalDate date=line.periodAfter().plusDays(day);
                if(!covered.add(date))overlap=true;
                BigInteger share=parts[0].add(BigInteger.valueOf(day)
                    .compareTo(parts[1])<0?BigInteger.ONE:BigInteger.ZERO);
                buckets.merge(YearMonth.from(date),share,BigInteger::add);
            }
        }
        var result=new ArrayList<Month>();
        BigInteger total=BigInteger.ZERO;
        for(var bucket:buckets.entrySet()) {
            total=total.add(bucket.getValue());
            result.add(new Month(bucket.getKey().toString(),currency,
                decimal(bucket.getValue())));
        }
        BigInteger expected=micro(summary.allocatedAmount());
        if(!total.equals(expected))
            throw new IllegalStateException("V185 monthly and V92 expense mismatch");
        BigInteger source=micro(summary.sourceAmount());
        BigInteger unallocated=new BigDecimal(summary.unallocatedAmount())
            .movePointRight(6).toBigIntegerExact();
        if(!total.add(unallocated).equals(source))
            throw new IllegalStateException("V185 original currency money does not conserve");
        var reasons=new ArrayList<String>();
        reasons.add("EXTERNAL_EXPENSE_AUTHENTICITY_NOT_VERIFIED");
        reasons.add("G1_DAILY_PERIODIZATION_POLICY_NOT_APPROVED");
        reasons.add("DEC_08_FX_CONVERSION_POLICY_NOT_APPROVED");
        reasons.add("REFUND_COGS_REVERSAL_NOT_VERIFIED");
        reasons.add("BANK_RECONCILIATION_INCOMPLETE");
        if(overlap)reasons.add("OVERLAPPING_SERVICE_DAYS_REQUIRE_POLICY_REVIEW");
        if(unallocated.signum()!=0)reasons.add("ORIGINAL_COST_REMAINDER_UNALLOCATED");
        if(!"APPROVED_UNVERIFIED".equals(allocationState))
            reasons.add("INDEPENDENT_V92_ALLOCATION_APPROVAL_NOT_COMPLETE");
        return new Preview(sourceId.toString(),allocationId.toString(),
            allocationState,RULE,currency,summary.sourceAmount(),
            summary.allocatedAmount(),summary.unallocatedAmount(),
            decimal(total),List.copyOf(result),overlap,List.copyOf(reasons),
            false,false,false,false,false,false,null);
    }
}
