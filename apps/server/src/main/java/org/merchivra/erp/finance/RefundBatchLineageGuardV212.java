package org.merchivra.erp.finance;

import java.math.BigDecimal;
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
 * V212: reconcile existing V106/V203 local SALE-LOT claims with V34/V210
 * refunds and V96/V97 LOCAL return-intake claims, per original order item.
 * No lot is inferred for a returned unit. A purchase-unit price is only an
 * unverifiable reference and cannot become recognized refund COGS.
 */
public final class RefundBatchLineageGuardV212 {
    private RefundBatchLineageGuardV212() {}

    public static final String VERSION =
        "V212_SALE_LOT_REFUND_RETURN_INTAKE_QUANTITY_ENVELOPE_V1";

    public record LotClaim(UUID id,UUID purchaseReceiptId,String itemId,
        int assignedQuantity,String integrityState,String purchaseCurrency,
        BigDecimal referenceUnitCost,String sourceDigest) {}
    public record Intake(UUID id,UUID claimId,UUID eventId,String itemId,
        int quantity,String state,String condition,String receiptClaimState,
        String receiptClaimCondition,Integer manualClaimQuantity) {}
    public record Reviewed(UUID eventId,String itemId,int quantity,
        String disposition) {}
    public record Lot(String claimId,String originalPurchaseReceiptId,
        String orderItemId,int locallyClaimedUnits,String localIdentityState,
        String purchaseReferenceCurrency,String purchaseReferenceUnitCost,
        boolean actualBatchCostVerified,String actualBatchCogs) {}
    public record Item(String orderItemId,Integer originalSaleQuantity,
        long reviewedRefundQuantity,long restockClaimedQuantity,
        long locallyAttributedSaleQuantity,long attestedLocalReturnQuantity,
        long pendingLocalReturnQuantity,long unassignedRefundQuantity,
        long unattestedRestockQuantity,int distinctOriginalPurchaseReceipts,
        boolean localQuantityEnvelopeConsistent,List<Lot> saleLotClaims,
        List<String> blockerCodes,String actualRefundBatchCost) {}
    public record Preview(String orderId,String marketplaceId,
        String contractVersion,int refundEventCount,int reviewedItemRows,
        int originalSaleLotClaimCount,int returnIntakeCandidateCount,
        List<Item> orderItems,List<String> blockerCodes,
        boolean localAllItemQuantitiesConsistent,
        boolean externalAmazonRefundsComplete,
        boolean originalAmazonFulfillmentVerified,
        boolean physicalReturnReceiptVerified,
        boolean independentlyVerifiedActualBatchCogs,
        boolean accountingRecognitionApproved,
        boolean refundCogsReversalAllowed,
        String actualRefundCogsReversal,String realizedNetProfit) {}

    private static final int LIMIT=1000;
    private static String price(BigDecimal p) {
        return p==null?null:p.toPlainString();
    }

    static Preview calculate(UUID marketplace,
        RefundOrderConservationEngineV210.Result refunds,
        OrderLotAssignmentIntegrityServiceV203.Preview saleLots,
        List<Reviewed> reviewed,List<LotClaim> claims,List<Intake> intakes) {
        if(marketplace==null||refunds==null||saleLots==null
            ||reviewed==null||claims==null||intakes==null
            ||reviewed.size()>LIMIT||claims.size()>LIMIT||intakes.size()>LIMIT
            ||!marketplace.toString().equals(saleLots.marketplaceId())
            ||!refunds.orderId().equals(saleLots.orderId())
            ||refunds.sourceEventCount()!=refunds.events().size()
            ||refunds.manualReviewRowCount()!=reviewed.size()
            ||saleLots.claims().size()!=claims.size()
            ||refunds.actualCogsReversalsAllowed()||refunds.actualCogsReversal()!=null
            ||refunds.realizedNetProfit()!=null
            ||saleLots.actualBatchCogsVerified()||saleLots.realizedBatchCogs()!=null)
            throw new IllegalArgumentException("V210/V203 scope or integrity drift");

        var blockers=new LinkedHashSet<String>();
        blockers.addAll(refunds.blockerCodes());
        blockers.addAll(saleLots.blockerCodes());
        blockers.add("V106_LOCAL_PURCHASE_PRICE_IS_NOT_LANDED_ACTUAL_BATCH_COST");
        blockers.add("REFUND_ITEMS_NOT_INDEPENDENTLY_MAPPED_TO_ORIGINAL_SALE_LOTS");
        blockers.add("V97_LOCAL_RETURN_INTAKE_NOT_EXTERNAL_PHYSICAL_RECEIPT");
        blockers.add("AMAZON_FULFILLMENT_AND_FINANCES_REFUND_COVERAGE_UNVERIFIED");
        blockers.add("G1_BATCH_COGS_REVERSAL_AND_PERIOD_POLICY_NOT_APPROVED");
        blockers.add("INVENTORY_REFUND_COGS_AND_REALIZED_PROFIT_POSTING_FORBIDDEN");

        var allEvents=new HashSet<UUID>();
        for(var e:refunds.events()) {
            if(e==null||e.eventId()==null||
                !allEvents.add(UUID.fromString(e.eventId())))
                throw new IllegalArgumentException("Duplicate/missing V210 refunded event");
        }
        var v203=new HashMap<UUID,OrderLotAssignmentIntegrityServiceV203.Claim>();
        for(var c:saleLots.claims()) {
            if(c==null||c.claimId()==null||v203.putIfAbsent(
                UUID.fromString(c.claimId()),c)!=null)
                throw new IllegalArgumentException("Duplicate V203 sale-lot identity");
        }
        var byItem=new LinkedHashMap<String,Long>();
        var original=new LinkedHashMap<String,Integer>();
        for(var i:refunds.quantities()) {
            if(i==null||i.itemId()==null||i.itemId().isBlank()
                ||byItem.putIfAbsent(i.itemId(),i.manuallyReviewedQuantity())!=null)
                throw new IllegalArgumentException("Duplicate V210 original sale item");
            original.put(i.itemId(),i.originalOrderedQuantity());
        }
        var seenReview=new HashSet<String>();
        var reviewedSum=new HashMap<String,Long>();
        var restockSum=new HashMap<String,Long>();
        var reviewedPerEvent=new HashMap<String,Integer>();
        var reviewDisposition=new HashMap<String,String>();
        for(var r:reviewed) {
            if(r==null||r.itemId()==null||r.eventId()==null||r.quantity()<1
                ||!seenReview.add(r.eventId()+"|"+r.itemId()))
                throw new IllegalArgumentException("Duplicate V34 event/item or invalid quantity");
            if(!allEvents.contains(r.eventId()))
                blockers.add("ORPHANED_V34_REFUND_REVIEW_NOT_IN_V210_SOURCE");
            reviewedSum.merge(r.itemId(),(long)r.quantity(),Math::addExact);
            reviewedPerEvent.put(r.eventId()+"|"+r.itemId(),r.quantity());
            reviewDisposition.put(r.eventId()+"|"+r.itemId(),r.disposition());
            if("RESTOCKED_CLAIMED".equals(r.disposition()))
                restockSum.merge(r.itemId(),(long)r.quantity(),Math::addExact);
            else if(!"NOT_RESTOCKED".equals(r.disposition()))
                blockers.add("UNKNOWN_REFUND_RESTOCK_DISPOSITION");
        }
        for(var entry:byItem.entrySet())
            if(!entry.getValue().equals(reviewedSum.getOrDefault(entry.getKey(),0L)))
                throw new IllegalArgumentException("V210 quantity changed since refund snapshot");
        for(var item:reviewedSum.keySet())
            if(!byItem.containsKey(item))
                throw new IllegalArgumentException("V34 item missing in V210 reviewed population");

        var byClaim=new LinkedHashMap<String,List<Lot>>();
        var claimCapacity=new HashMap<String,Long>();
        var distinctReceipts=new HashMap<String,Set<UUID>>();
        var seenClaims=new HashSet<UUID>();
        var receiptReference=new HashMap<UUID,String>();
        var globalClaimIssue=new HashSet<String>();
        for(var c:claims) {
            if(c==null||c.id()==null||c.purchaseReceiptId()==null
                ||c.itemId()==null||!seenClaims.add(c.id())
                ||c.assignedQuantity()<1||c.assignedQuantity()>1000000)
                throw new IllegalArgumentException("Invalid/duplicate V106 source claim");
            var checked=v203.get(c.id());
            if(checked!=null&&(checked.saleLotAttributed()
                ||checked.actualBatchCogsVerified()
                ||checked.realizedBatchCogs()!=null))
                throw new IllegalArgumentException("V203 cannot certify actual sale COGS");
            if(checked==null||!c.itemId().equals(checked.externalItemId())
                ||c.assignedQuantity()!=checked.assignedQuantity()
                ||(!"LOCAL_LINK_INTACT_UNVERIFIED".equals(c.integrityState())
                    &&!java.util.Objects.equals(c.integrityState(),checked.integrityState()))
                ||!c.purchaseReceiptId().toString().equals(checked.purchaseReceiptId()))
                throw new IllegalArgumentException("V203 independently recalculated lot proof changed");
            if(!original.containsKey(c.itemId()))
                throw new IllegalArgumentException("Claim references no V210 original item");
            var issues=new ArrayList<String>();
            // V203 additionally checks the current LIVE order SKU mapping.
            // Its effective state may be stricter than the PG V106 view;
            // in that case do NOT present the locally intact SQL claim as
            // currently usable for refund capacity.
            if(!"LOCAL_LINK_INTACT_UNVERIFIED".equals(checked.integrityState()))
                issues.add("ORIGINAL_SALE_LOT_SOURCE_RETRACTED_OR_STALE");
            if(c.referenceUnitCost()==null||c.referenceUnitCost().signum()<0
                ||c.referenceUnitCost().scale()>6||c.purchaseCurrency()==null
                ||!c.purchaseCurrency().matches("[A-Z]{3}")
                ||c.sourceDigest()==null
                ||!c.sourceDigest().matches("[0-9a-f]{64}"))
                issues.add("ORIGINAL_PURCHASE_REFERENCE_OR_DIGEST_INVALID");
            var identity=c.purchaseCurrency()+"|"+price(c.referenceUnitCost());
            if(receiptReference.putIfAbsent(c.purchaseReceiptId(),identity)!=null
                &&!receiptReference.get(c.purchaseReceiptId()).equals(identity))
                issues.add("SAME_PURCHASE_RECEIPT_HAS_INCONSISTENT_COST_REFERENCE");
            if(!issues.isEmpty())globalClaimIssue.addAll(issues);
            // Even STALE/RETRACTED historical claims reserve original lot
            // units in V106; never recycle them or infer FIFO on refunds.
            claimCapacity.merge(c.itemId(),(long)c.assignedQuantity(),Math::addExact);
            distinctReceipts.computeIfAbsent(c.itemId(),ignored->new HashSet<>())
                .add(c.purchaseReceiptId());
            byClaim.computeIfAbsent(c.itemId(),ignored->new ArrayList<>())
                .add(new Lot(c.id().toString(),c.purchaseReceiptId().toString(),
                    c.itemId(),c.assignedQuantity(),checked.integrityState(),
                    c.purchaseCurrency(),price(c.referenceUnitCost()),false,null));
        }

        var allIntakes=new HashSet<UUID>();
        var attested=new HashMap<String,Long>();
        var pending=new HashMap<String,Long>();
        var reservedByClaim=new HashMap<UUID,Long>();
        var countedByEventItem=new HashMap<String,Long>();
        var intakeIssues=new LinkedHashSet<String>();
        for(var i:intakes) {
            if(i==null||i.id()==null||i.claimId()==null||i.eventId()==null
                ||i.itemId()==null||!allIntakes.add(i.id())
                ||i.quantity()<1||i.quantity()>1000000)
                throw new IllegalArgumentException("Invalid/duplicate local return intake");
            var itemKey=i.eventId()+"|"+i.itemId();
            Integer v34=reviewedPerEvent.get(itemKey);
            if(v34==null||!allEvents.contains(i.eventId())) {
                intakeIssues.add("RETURN_INTAKE_SOURCE_NOT_IN_CURRENT_REFUND_REVIEW");
                continue;
            }
            if(!"RESTOCKED_CLAIMED".equals(reviewDisposition.get(itemKey)))
                intakeIssues.add("RETURN_INTAKE_WITHOUT_RESTOCKED_REFUND_DISPOSITION");
            if(!Set.of("PENDING","ATTESTED_LOCAL","REJECTED").contains(i.state())
                ||!"REVIEWED_MANUAL".equals(i.receiptClaimState())
                ||!java.util.Objects.equals(i.condition(),i.receiptClaimCondition())
                ||i.manualClaimQuantity()==null||i.manualClaimQuantity()<1)
                intakeIssues.add("RETURN_INTAKE_MANUAL_CLAIM_STALE_OR_INVALID");
            if("REJECTED".equals(i.state()))continue;
            // Both pending and attested claims reserve units. An acceptance
            // of two receipts for the same refund item cannot exceed V34.
            reservedByClaim.merge(i.claimId(),(long)i.quantity(),Math::addExact);
            countedByEventItem.merge(itemKey,(long)i.quantity(),Math::addExact);
            if(reservedByClaim.get(i.claimId())>i.manualClaimQuantity()
                ||countedByEventItem.get(itemKey)>v34)
                intakeIssues.add("RETURN_INTAKE_DUPLICATE_OR_EXCEEDS_REFUND_QUANTITY");
            if("ATTESTED_LOCAL".equals(i.state()))
                attested.merge(i.itemId(),(long)i.quantity(),Math::addExact);
            else if("PENDING".equals(i.state()))
                pending.merge(i.itemId(),(long)i.quantity(),Math::addExact);
        }
        blockers.addAll(globalClaimIssue);
        blockers.addAll(intakeIssues);
        if(!refunds.completeLocalManualConservation())
            blockers.add("V210_WHOLE_ORDER_REFUND_EVIDENCE_NOT_BALANCED");

        var output=new ArrayList<Item>();
        boolean consistent=!original.isEmpty()&&!allEvents.isEmpty()
            &&refunds.completeLocalManualConservation()
            &&globalClaimIssue.isEmpty()&&intakeIssues.isEmpty()
            &&!blockers.contains("ORPHANED_V34_REFUND_REVIEW_NOT_IN_V210_SOURCE")
            &&!blockers.contains("UNKNOWN_REFUND_RESTOCK_DISPOSITION");
        for(var entry:original.entrySet()) {
            String item=entry.getKey();
            var codes=new LinkedHashSet<String>();
            long refunded=byItem.get(item);
            long local=claimCapacity.getOrDefault(item,0L);
            long claimedRestock=restockSum.getOrDefault(item,0L);
            long accepted=attested.getOrDefault(item,0L);
            long waiting=pending.getOrDefault(item,0L);
            if(entry.getValue()==null||entry.getValue()<=0
                ||refunded>entry.getValue())codes.add("LIVE_ORIGINAL_ITEM_QUANTITY_UNKNOWN_OR_EXCEEDED");
            if(refunded>local)codes.add("REFUND_UNITS_EXCEED_ORIGINAL_LOCAL_LOT_CLAIMS");
            if(local==0)codes.add("NO_ORIGINAL_SALE_LOT_CLAIM_FOR_REFUND_ITEM");
            if(local>0&&byClaim.getOrDefault(item,List.of()).stream()
                .anyMatch(l->!"LOCAL_LINK_INTACT_UNVERIFIED".equals(l.localIdentityState())))
                codes.add("ORIGINAL_SALE_LOT_SOURCE_RETRACTED_OR_STALE");
            if(claimedRestock>accepted)
                codes.add("RESTOCK_CLAIM_QUANTITY_EXCEEDS_LOCAL_ATTESTED_INTAKE");
            if(accepted>claimedRestock||accepted+waiting>refunded)
                codes.add("LOCAL_RETURN_INTAKE_OVER_REFUND_QUANTITY");
            if(!intakeIssues.isEmpty())codes.add("LOCAL_RETURN_INTAKE_EVIDENCE_CONFLICT");
            if(!globalClaimIssue.isEmpty())codes.add("LOCAL_SALE_LOT_SOURCE_IDENTITY_CONFLICT");
            boolean ok=codes.isEmpty();
            if(!ok)consistent=false;
            blockers.addAll(codes);
            output.add(new Item(item,entry.getValue(),refunded,claimedRestock,
                local,accepted,waiting,Math.max(0,refunded-local),
                Math.max(0,claimedRestock-accepted),
                distinctReceipts.getOrDefault(item,Set.of()).size(),ok,
                List.copyOf(byClaim.getOrDefault(item,List.of())),
                List.copyOf(codes),null));
        }
        // Deliberately no multiplication of purchase price by refund units:
        // a customer-return unit is NOT attributed to a specific sale lot
        // just because some local claims have enough aggregate capacity.
        return new Preview(refunds.orderId(),marketplace.toString(),VERSION,
            refunds.sourceEventCount(),reviewed.size(),claims.size(),intakes.size(),
            List.copyOf(output),List.copyOf(blockers),consistent,
            false,false,false,false,false,false,null,null);
    }
}
