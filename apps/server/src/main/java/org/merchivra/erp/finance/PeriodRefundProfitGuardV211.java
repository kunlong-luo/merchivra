package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * V211: period refund recognition guard, NOT a second profit engine.
 *
 * V64 freezes orders by order_time; Finances refunds use posted_at. These
 * axes cannot be silently netted, nor can two V64 report snapshots be added
 * as if they were independent journal entries. Reuses V188 frozen cost
 * arithmetic and V210 all-event per-order evidence.
 *
 * Always read-only. No external provenance, actual COGS, refund posting,
 * FX conversion or realized net profit is certified by this calculation.
 */
public final class PeriodRefundProfitGuardV211 {
    private PeriodRefundProfitGuardV211() {}

    public static final String VERSION = "V211_V188_V210_PERIOD_REFUND_SOURCE_AXES_V1";

    public record Source(UUID eventId,UUID matchedOrderId,Instant postedAt,
        String currency,BigDecimal signedAmount,long matchedLinkCount,
        boolean inV64FrozenCohort) {}
    public record SnapshotOverlap(UUID otherJobId,UUID repeatedOrderId) {}
    public record CurrencyBucket(String currency,long sourceEventCount,
        String signedRefundSourceAmount,boolean fullyAttributedToV64Cohort,
        boolean actualRefundCostRecognized,String recognizedRefundCogs) {}
    public record Event(String eventId,String matchedOrderId,String postedAtUtc,
        String currency,String signedRefundSourceAmount,
        long matchedLinkCount,boolean inV64FrozenCohort,
        boolean currentV210SourceIdentity, List<String> blockerCodes) {}
    public record Order(String orderId,int sourceEventCount,
        long refundEventsOutsideV64OrderDateWindow,
        boolean allLocalManualRefundEvidenceConsistent,
        int crossEventQuantityConflictCount,
        boolean actualRefundCogsVerified,String actualRefundCogs) {}
    public record Result(String marketplaceId,String reportJobId,
        String periodStartUtc,String periodEndExclusiveUtc,String dateBasis,
        String ruleVersion,PeriodProfitWaterfallV188.Preview v188Baseline,
        int periodPostedSourceEventCount,int cohortRefundOrderCount,
        long refundEventsOutsideV64OrderDateWindow,
        int unmatchedPeriodSourceCount,int multiMatchedPeriodSourceCount,
        int periodRefundSourceOutsideFrozenCohortCount,
        int intersectingOtherFrozenReportCount,
        int repeatedFrozenOrderCount,
        List<CurrencyBucket> currencies,List<Event> periodPostedEvents,
        List<Order> reviewedCohortOrders,
        boolean originalCurrencySourceSnapshotConsistent,
        boolean allLocalManualRefundConservationComplete,
        boolean refundRecognitionPeriodPolicyApproved,
        boolean actualRefundCogsVerified,
        boolean independentAmazonRefundCoverageVerified,
        boolean doubleJournalDeductionAllowed,
        String refundAdjustedContribution,String realizedNetProfit,
        List<String> blockerCodes) {}

    private static String display(BigDecimal x) {
        return x.toPlainString();
    }
    private static BigDecimal monetary(String s) {
        try {return s==null?null:new BigDecimal(s);}
        catch(NumberFormatException invalid){return null;}
    }
    private static final class Bucket {
        long count;
        BigDecimal signed=BigDecimal.ZERO;
        boolean attributed=true;
    }

    public static Result calculate(UUID marketplace,UUID jobId,
        PeriodProfitWaterfallV188.Preview baseline,
        List<Source> inPeriod, List<UUID> expectedCohortOrders,
        List<RefundOrderConservationEngineV210.Result> reviewedOrders,
        List<SnapshotOverlap> overlaps) {
        if(marketplace==null||jobId==null||baseline==null||inPeriod==null
            ||expectedCohortOrders==null||reviewedOrders==null||overlaps==null
            ||inPeriod.size()>1000||expectedCohortOrders.size()>100
            ||reviewedOrders.size()>100||overlaps.size()>100
            ||!marketplace.toString().equals(baseline.marketplaceId())
            ||!jobId.toString().equals(baseline.reportJobId())
            ||baseline.refundCogsReversalVerified()
            ||baseline.realizedNetProfitVerified()
            ||baseline.realizedNetProfit()!=null)
            throw new IllegalArgumentException("Invalid V188 source-bound refund period baseline");

        LocalDate first=LocalDate.parse(baseline.periodStart());
        LocalDate last=LocalDate.parse(baseline.periodEndExclusive());
        if(!first.isBefore(last))throw new IllegalArgumentException("Empty refund period");
        Instant start=first.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end=last.atStartOfDay().toInstant(ZoneOffset.UTC);

        var blockers=new LinkedHashSet<String>();
        blockers.addAll(baseline.blockerCodes());
        blockers.add("V64_ORDER_DATE_DIFFERS_FROM_FINANCES_REFUND_POSTING_DATE");
        blockers.add("V210_MANUAL_QUANTITY_AND_AMOUNT_ARE_NOT_EXTERNAL_REFUND_TRUTH");
        blockers.add("AMAZON_FINANCES_REFUND_COVERAGE_NOT_INDEPENDENTLY_VERIFIED");
        blockers.add("G1_REFUND_COGS_REVERSAL_AND_PERIOD_RECOGNITION_NOT_APPROVED");
        blockers.add("DO_NOT_DEDUCT_REFUNDS_AGAIN_FROM_V188_SIGNED_SALES");
        blockers.add("BANK_ADS_AND_ALL_OPERATING_COSTS_NOT_FINALLY_RECONCILED");
        blockers.add("NO_REALIZED_NET_PROFIT_LEDGER_AUTHORIZATION");

        var expected=new LinkedHashSet<UUID>();
        for(UUID id:expectedCohortOrders)
            if(id==null||!expected.add(id))
                throw new IllegalArgumentException("Duplicate or missing frozen refund order");
        if(!expected.isEmpty()&&expected.size()!=reviewedOrders.size())
            throw new IllegalArgumentException("Incomplete all-order V210 assessment set");

        var byOrder=new LinkedHashMap<UUID,RefundOrderConservationEngineV210.Result>();
        var checked=new ArrayList<Order>();
        long outside=0;
        boolean manual=!expected.isEmpty();
        for(var result:reviewedOrders) {
            if(result==null||result.orderId()==null)
                throw new IllegalArgumentException("Missing V210 order result");
            UUID oid=UUID.fromString(result.orderId());
            if(!expected.contains(oid)||byOrder.putIfAbsent(oid,result)!=null)
                throw new IllegalArgumentException("Wrong or repeated V210 order result");
            if(result.actualCogsReversalsAllowed()
                ||result.actualCogsReversal()!=null
                ||result.realizedNetProfit()!=null
                ||result.fullFinancesRefundCoverageVerified())
                throw new IllegalArgumentException("V210 external money claim not permitted");
            long outsideCount=0;
            Set<UUID> seen=new HashSet<>();
            for(var e:result.events()) {
                if(e==null||e.eventId()==null||e.postedAtUtc()==null
                    ||!seen.add(UUID.fromString(e.eventId())))
                    throw new IllegalArgumentException("Duplicate V210 event in order");
                Instant at=Instant.parse(e.postedAtUtc());
                if(at.isBefore(start)||!at.isBefore(end))outsideCount++;
            }
            outside=Math.addExact(outside,outsideCount);
            manual&=result.completeLocalManualConservation()
                &&result.crossEventQuantityConflictCount()==0;
            if(!result.completeLocalManualConservation()
                ||result.crossEventQuantityConflictCount()>0)
                blockers.add("V210_WHOLE_ORDER_REFUND_QUANTITY_OR_SOURCE_INCOMPLETE");
            if(outsideCount>0)
                blockers.add("COHORT_ORDER_REFUNDS_POSTED_OUTSIDE_V64_ORDER_DATE_PERIOD");
            checked.add(new Order(oid.toString(),result.sourceEventCount(),
                outsideCount,result.completeLocalManualConservation(),
                result.crossEventQuantityConflictCount(),false,null));
        }
        if(!byOrder.keySet().equals(expected))
            throw new IllegalArgumentException("V210 not checked for every refund order");

        var currencies=new LinkedHashMap<String,Bucket>();
        var events=new ArrayList<Event>();
        Set<UUID> sourceIds=new HashSet<>();
        int unmatched=0,multi=0,outCohort=0;
        boolean snapshotConsistent=true;
        for(var s:inPeriod) {
            if(s==null||s.eventId()==null||s.postedAt()==null
                ||s.currency()==null||!s.currency().matches("[A-Z]{3}")
                ||s.signedAmount()==null
                ||s.signedAmount().scale()>6
                ||s.matchedLinkCount()<0||!sourceIds.add(s.eventId())
                ||s.postedAt().isBefore(start)||!s.postedAt().isBefore(end))
                throw new IllegalArgumentException("Period event list is not a valid complete source snapshot");
            var reasons=new LinkedHashSet<String>();
            var bucket=currencies.computeIfAbsent(s.currency(),ignored->new Bucket());
            bucket.count++;
            bucket.signed=bucket.signed.add(s.signedAmount());
            if(s.signedAmount().signum()>=0) {
                reasons.add("NON_NEGATIVE_PRODUCT_REFUND_SOURCE");
                snapshotConsistent=false;
            }
            if(s.matchedLinkCount()==0||s.matchedOrderId()==null) {
                unmatched++;
                reasons.add("REFUND_EVENT_UNMATCHED_TO_LIVE_ORDER");
                snapshotConsistent=false;
            }
            if(s.matchedLinkCount()>1) {
                multi++;
                reasons.add("REFUND_EVENT_HAS_MULTIPLE_ACTIVE_ORDER_LINKS");
                snapshotConsistent=false;
            }
            if(!s.inV64FrozenCohort()) {
                snapshotConsistent=false;
                // A source WITHOUT a matched order is already counted under
                // unmatched; don't mislabel it as a valid but out-of-cohort
                // order. Preserve both independent blockers and source money.
                if(s.matchedOrderId()!=null&&s.matchedLinkCount()>0) {
                    outCohort++;
                    reasons.add("PERIOD_REFUND_ORDER_OUTSIDE_V64_FROZEN_COHORT");
                }
            }
            RefundOrderConservationEngineV210.Result checkedOrder=
                s.matchedOrderId()==null?null:byOrder.get(s.matchedOrderId());
            boolean exact=checkedOrder!=null && checkedOrder.events().stream().anyMatch(e->
                s.eventId().toString().equals(e.eventId())
                &&s.currency().equals(e.currency())
                &&s.postedAt().toString().equals(e.postedAtUtc())
                &&monetary(e.signedSourceRefund())!=null
                &&s.signedAmount().compareTo(monetary(e.signedSourceRefund()))==0);
            if(s.matchedLinkCount()!=1||!s.inV64FrozenCohort()||!exact) {
                reasons.add("PERIOD_V210_SOURCE_CHANGED_OR_NOT_IN_COHORT");
                snapshotConsistent=false;
                bucket.attributed=false;
            }
            events.add(new Event(s.eventId().toString(),
                s.matchedOrderId()==null?null:s.matchedOrderId().toString(),
                s.postedAt().toString(),s.currency(),display(s.signedAmount()),
                s.matchedLinkCount(),s.inV64FrozenCohort(),exact,
                List.copyOf(reasons)));
        }
        // V210 may see newly classified/matched events after the first
        // V211 snapshot. Those are NOT silently allowed into the old period.
        for(var e:byOrder.values())for(var event:e.events()) {
            Instant at=Instant.parse(event.postedAtUtc());
            if(!at.isBefore(start)&&at.isBefore(end)
                &&!sourceIds.contains(UUID.fromString(event.eventId()))) {
                snapshotConsistent=false;
                blockers.add("NEW_OR_CHANGED_PERIOD_REFUND_SOURCE_BETWEEN_SNAPSHOTS");
            }
        }
        if(unmatched>0)blockers.add("UNMATCHED_REFUND_EVENTS_IN_POSTING_PERIOD");
        if(multi>0)blockers.add("MULTIPLY_MATCHED_REFUND_EVENTS_IN_POSTING_PERIOD");
        if(outCohort>0)blockers.add("PERIOD_REFUNDS_BELONG_TO_ORDERS_OUTSIDE_V64_COHORT");
        if(!snapshotConsistent)
            blockers.add("PERIOD_V210_REFUND_SOURCE_SNAPSHOT_REVALIDATION_FAILED");
        if(inPeriod.isEmpty()&&expected.isEmpty())
            blockers.add("NO_CLASSIFIED_REFUND_ROWS_DOES_NOT_PROVE_ZERO_REFUNDS");
        if(!manual)blockers.add("ALL_V210_ORDER_REFUND_REVIEWS_NOT_AVAILABLE");

        var distinctOther=new HashSet<UUID>();
        var overlapIds=new HashSet<String>();
        Set<UUID> repeated=new HashSet<>();
        for(var o:overlaps) {
            if(o==null||o.otherJobId()==null||o.repeatedOrderId()==null
                ||jobId.equals(o.otherJobId())
                ||!overlapIds.add(o.otherJobId()+"|"+o.repeatedOrderId()))
                throw new IllegalArgumentException("Invalid duplicate frozen report snapshot overlap");
            distinctOther.add(o.otherJobId());repeated.add(o.repeatedOrderId());
        }
        if(!overlaps.isEmpty())
            blockers.add("OVERLAPPING_V64_SNAPSHOTS_ARE_NOT_INDEPENDENT_LEDGER_ENTRIES");
        if(outside>0)
            blockers.add("REFUND_POSTING_PERIOD_DIFFERS_FROM_ORDER_COHORT_PERIOD");
        var buckets=new ArrayList<CurrencyBucket>();
        for(var item:currencies.entrySet()) {
            var b=item.getValue();
            buckets.add(new CurrencyBucket(item.getKey(),b.count,
                display(b.signed),b.attributed&&snapshotConsistent&&manual,
                false,null));
        }
        return new Result(marketplace.toString(),jobId.toString(),
            start.toString(),end.toString(),
            "V64_ORDER_CREATED_AT_UTC_VS_FINANCES_REFUND_POSTED_AT_UTC",
            VERSION,baseline,inPeriod.size(),expected.size(),outside,
            unmatched,multi,outCohort,distinctOther.size(),repeated.size(),
            List.copyOf(buckets),List.copyOf(events),List.copyOf(checked),
            snapshotConsistent,manual&&snapshotConsistent,false,false,false,
            false,null,null,List.copyOf(blockers));
    }
}
