package org.merchivra.erp.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * V210: full-order, read-only cross-event refund quantity and signed-money
 * conservation. NOT a financial ledger or real COGS reversal.
 *
 * V201 guards individual V34 reviews, V197 catches source snapshot drift,
 * V63 estimates manually claimed cost. Here we reconcile ALL events of ONE
 * order in one bounded source snapshot; a missing event/item is UNKNOWN.
 * Neither a zero-source snapshot nor a review is official Amazon completeness.
 */
public final class RefundOrderConservationEngineV210 {
    private RefundOrderConservationEngineV210() {}

    public record Source(UUID eventId,UUID orderId,Instant postedAt,
        String currency,BigDecimal signedRefund) {}
    public record SaleItem(String itemId,Integer orderedQuantity) {}
    public record ReviewItem(UUID eventId,String itemId,int reviewedQuantity,
        String disposition) {}
    public record Probe(Source source,RefundRecoveryService.Status status) {}

    public record Quantity(String itemId,Integer originalOrderedQuantity,
        long manuallyReviewedQuantity,int distinctRefundEventCount,
        String state) {}
    public record Event(String eventId,String currency,String postedAtUtc,
        String signedSourceRefund,String manualReviewState,
        String historicalCostExposureUnverified,
        String claimedRestockCostSensitivityUnverified,
        boolean snapshotCurrent,boolean manualEvidenceBalanced,
        List<String> blockerCodes,boolean actualCogsReversalAllowed,
        String actualCogsReversal) {}
    public record Currency(String currency,long sourceEventCount,
        String signedSourceRefundTotal,
        String historicalCostExposureUnverified,
        String claimedRestockCostSensitivityUnverified,
        boolean allLocalManualEvidenceConsistent,boolean actualCogsReversalVerified,
        String actualCogsReversal) {}
    public record Result(String orderId,int sourceEventCount,
        int manualReviewRowCount,int crossEventQuantityConflictCount,
        List<Quantity> quantities,List<Event> events,List<Currency> currencies,
        boolean fullFinancesRefundCoverageVerified,
        boolean completeLocalManualConservation,
        boolean independentlyVerifiedActualBatchCogs,
        boolean physicalReturnReceiptVerified,
        boolean actualCogsReversalsAllowed,
        String actualCogsReversal,String realizedNetProfit,
        List<String> blockerCodes) {}

    private static String money(BigDecimal x) {
        return x==null?null:x.toPlainString();
    }
    private static BigDecimal amount(String input) {
        if(input==null)return null;
        try{return new BigDecimal(input);}catch(NumberFormatException ignored){
            return null;
        }
    }
    private static class Sum {
        long count;
        BigDecimal source=BigDecimal.ZERO;
        BigDecimal exposure=BigDecimal.ZERO;
        BigDecimal restock=BigDecimal.ZERO;
        boolean complete=true;
    }

    public static Result calculate(UUID orderId,List<SaleItem> sale,
        List<ReviewItem> rawReviews,List<Probe> probes) {
        if(orderId==null||sale==null||rawReviews==null||probes==null
            ||sale.size()>1000||rawReviews.size()>1000||probes.size()>1000)
            throw new IllegalArgumentException("Full-order refund scope must be bounded");
        var blockers=new LinkedHashSet<String>();
        blockers.add("OFFICIAL_AMAZON_REFUND_EVENT_COVERAGE_UNVERIFIED");
        blockers.add("EXTERNAL_REFUND_SOURCE_AUTHENTICITY_UNVERIFIED");
        blockers.add("PHYSICAL_RETURN_RECEIPT_AND_DISPOSITION_UNVERIFIED");
        blockers.add("G1_REFUND_COGS_REVERSAL_POLICY_NOT_APPROVED");
        blockers.add("INVENTORY_AND_ACCOUNTING_POSTING_NOT_AUTHORIZED");
        blockers.add("REALIZED_NET_PROFIT_NOT_AVAILABLE");

        var original=new LinkedHashMap<String,Integer>();
        for(var line:sale) {
            if(line==null||line.itemId()==null||line.itemId().isBlank()
                ||original.containsKey(line.itemId()))
                throw new IllegalArgumentException("Duplicate or ambiguous LIVE sale item");
            original.put(line.itemId(),line.orderedQuantity());
        }
        var sources=new LinkedHashMap<UUID,Source>();
        for(var p:probes) {
            if(p==null||p.source()==null||p.source().eventId()==null
                ||p.source().postedAt()==null||p.source().currency()==null
                ||!p.source().currency().matches("[A-Z]{3}")
                ||p.source().signedRefund()==null
                ||!orderId.equals(p.source().orderId())
                ||sources.putIfAbsent(p.source().eventId(),p.source())!=null)
                throw new IllegalArgumentException(
                    "Duplicated, incomplete or cross-order source event snapshot");
        }
        if(probes.isEmpty())blockers.add("NO_MATCHED_LIVE_PRODUCT_REFUND_EVENTS");

        var reviewedTotals=new LinkedHashMap<String,Long>();
        var distinctEvents=new HashMap<String,Set<UUID>>();
        var perEventReview=new HashMap<UUID,Map<String,ReviewItem>>();
        for(var r:rawReviews) {
            if(r==null||r.eventId()==null||r.itemId()==null||r.itemId().isBlank()
                ||r.reviewedQuantity()<1)
                throw new IllegalArgumentException("Invalid V34 reviewed refund row");
            var byItem=perEventReview.computeIfAbsent(
                r.eventId(),ignored->new HashMap<>());
            if(byItem.putIfAbsent(r.itemId(),r)!=null)
                throw new IllegalArgumentException("Duplicate V34 refund event/item review");
            reviewedTotals.merge(r.itemId(),(long)r.reviewedQuantity(),Long::sum);
            distinctEvents.computeIfAbsent(r.itemId(),ignored->new HashSet<>())
                .add(r.eventId());
            if(!sources.containsKey(r.eventId()))
                blockers.add("ORPHANED_OR_CHANGED_REFUND_REVIEW_SOURCE");
        }

        var quantities=new ArrayList<Quantity>();
        var identities=new LinkedHashSet<String>(original.keySet());
        identities.addAll(reviewedTotals.keySet());
        int quantityConflicts=0;
        for(String item:identities) {
            Integer ordered=original.get(item);
            long reviewed=reviewedTotals.getOrDefault(item,0L);
            String state;
            if(ordered==null||ordered<=0) {
                state="ORIGINAL_LIVE_ITEM_QUANTITY_UNKNOWN";
                quantityConflicts++;
                blockers.add("ORIGINAL_LIVE_ITEM_QUANTITY_UNKNOWN");
            } else if(reviewed>ordered) {
                state="CROSS_EVENT_REFUNDED_QUANTITY_EXCEEDS_ORIGINAL";
                quantityConflicts++;
                blockers.add("CROSS_EVENT_REFUNDED_QUANTITY_EXCEEDS_ORIGINAL");
            } else {
                state="MANUAL_QUANTITY_WITHIN_ORIGINAL_UNVERIFIED";
            }
            quantities.add(new Quantity(item,ordered,reviewed,
                distinctEvents.getOrDefault(item,Set.of()).size(),state));
        }
        if(original.isEmpty())blockers.add("LIVE_ORDER_ITEM_SET_UNAVAILABLE");

        var buckets=new LinkedHashMap<String,Sum>();
        var eventResults=new ArrayList<Event>();
        boolean allLocalComplete=!probes.isEmpty()&&!original.isEmpty()
            &&quantityConflicts==0
            &&perEventReview.keySet().stream().allMatch(sources::containsKey);
        for(var p:probes) {
            var src=p.source();
            Sum sum=buckets.computeIfAbsent(src.currency(),ignored->new Sum());
            sum.count++;
            sum.source=sum.source.add(src.signedRefund());
            var gate=RefundCogsReversalGateV197.assess(src.eventId(),p.status(),
                orderId,src.currency(),src.signedRefund());
            var perCodes=new LinkedHashSet<>(gate.blockerCodes());
            var status=p.status();
            var exposure=status==null?null:status.exposure();
            // Decimal scale is irrelevant: compareTo, not BigDecimal.equals.
            boolean complete=gate.sourceSnapshotConsistent() && status!=null
                && "MANUAL_UNVERIFIED".equals(status.evidenceOrigin())
                && "REVIEWED".equals(status.state()) && status.evidenceCurrent()
                && status.requestedBy()!=null && status.decidedBy()!=null
                &&!status.requestedBy().equals(status.decidedBy())
                && exposure!=null
                &&"MANUAL_EVIDENCE_BALANCED_UNVERIFIED".equals(exposure.status())
                && exposure.items()!=null &&!exposure.items().isEmpty()
                && src.signedRefund().signum()<0
                && Objects.equals(src.currency(),exposure.currency())
                && amount(exposure.signedRefundTotal())!=null
                && src.signedRefund().compareTo(amount(exposure.signedRefundTotal()))==0
                && amount(exposure.remainingRefund())!=null
                && amount(exposure.remainingRefund()).signum()==0
                && amount(exposure.allocatedRefund())!=null
                && src.signedRefund().compareTo(amount(exposure.allocatedRefund()))==0;

            if(src.signedRefund().signum()>=0) {
                perCodes.add("SOURCE_EVENT_NOT_NEGATIVE_PRODUCT_REFUND");
                blockers.add("SOURCE_EVENT_NOT_NEGATIVE_PRODUCT_REFUND");
            }
            var reviewed=perEventReview.getOrDefault(src.eventId(),Map.of());
            if(exposure==null||exposure.items()==null||exposure.items().size()!=reviewed.size()) {
                complete=false;
                perCodes.add("V34_V63_REVIEW_ITEM_COUNT_DRIFT");
            } else {
                var seen=new HashSet<String>();
                for(var item:exposure.items()) {
                    if(item==null||item.itemId()==null||!seen.add(item.itemId())) {
                        complete=false;
                        perCodes.add("V34_V63_REVIEW_ITEM_IDENTITY_DRIFT");
                        continue;
                    }
                    ReviewItem raw=reviewed.get(item.itemId());
                    if(raw==null||item.refundedQuantity()!=raw.reviewedQuantity()
                        ||!Objects.equals(item.disposition(),raw.disposition())) {
                        complete=false;
                        perCodes.add("V34_V63_REVIEW_QUANTITY_OR_DISPOSITION_DRIFT");
                    }
                    if("UNKNOWN".equals(item.disposition())) {
                        complete=false;
                        perCodes.add("RETURN_ITEM_DISPOSITION_UNRESOLVED");
                    }
                }
            }
            BigDecimal exp=exposure==null?null:amount(exposure.historicalCostExposure());
            BigDecimal restock=exposure==null?null:
                amount(exposure.claimedRestockCostSensitivity());
            if(exp==null||restock==null||exp.signum()<0||restock.signum()<0
                ||restock.compareTo(exp)>0) {
                complete=false;
                perCodes.add("MANUAL_COST_EXPOSURE_OR_RESTOCK_SENSITIVITY_INVALID");
            }
            // If another refund event claimed the same returned units,
            // every event-level provisional cost is held, not only the order
            // rollup. This prevents treating one 50-row page as independent.
            if(quantityConflicts>0 ||original.isEmpty()
                ||perEventReview.keySet().stream()
                    .anyMatch(event->!sources.containsKey(event))) {
                complete=false;
                perCodes.add("WHOLE_ORDER_CROSS_EVENT_QUANTITY_OR_SOURCE_HOLD");
            }
            if(!complete) {
                sum.complete=false;
                allLocalComplete=false;
                blockers.add("REFUND_REVIEW_STALE_INCOMPLETE_OR_CONFLICTING");
            } else {
                sum.exposure=sum.exposure.add(exp);
                sum.restock=sum.restock.add(restock);
            }
            eventResults.add(new Event(src.eventId().toString(),src.currency(),
                src.postedAt().toString(),money(src.signedRefund()),
                status==null?"UNAVAILABLE":status.state(),
                complete?money(exp):null,complete?money(restock):null,
                gate.sourceSnapshotConsistent(),complete,
                List.copyOf(perCodes),false,null));
        }
        if(!allLocalComplete)
            blockers.add("WHOLE_ORDER_MANUAL_REFUND_CONSERVATION_INCOMPLETE");

        var currencies=new ArrayList<Currency>();
        for(var entry:buckets.entrySet()) {
            var v=entry.getValue();
            // A conflict in ANY currency/item holds the ENTIRE order's
            // cost sensitivity, preventing partial cross-currency cherry-pick.
            boolean eligible=v.complete&&allLocalComplete;
            currencies.add(new Currency(entry.getKey(),v.count,money(v.source),
                eligible?money(v.exposure):null,
                eligible?money(v.restock):null,eligible,false,null));
        }
        return new Result(orderId.toString(),probes.size(),rawReviews.size(),
            quantityConflicts,List.copyOf(quantities),List.copyOf(eventResults),
            List.copyOf(currencies),false,allLocalComplete,
            false,false,false,null,null,List.copyOf(blockers));
    }
}
