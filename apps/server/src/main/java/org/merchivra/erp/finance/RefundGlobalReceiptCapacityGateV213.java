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
 * V213 cross-order global receipt-capacity audit for V212 refund sale-lot claims.
 * V106 insertion trigger already locks each receipt and enforces the accepted
 * quantity. This independently recomputes the complete current population,
 * INCLUDING retracted historic claims; it does not create a second ledger.
 *
 * Source prices are ORIGINAL PURCHASE PO references, not landed unit costs.
 * This gate never performs FX, FIFO, refund-to-lot inference or COGS posting.
 */
public final class RefundGlobalReceiptCapacityGateV213 {
    private RefundGlobalReceiptCapacityGateV213() {}
    public static final String RULE="V213_GLOBAL_V106_RECEIPT_ALLOCATION_AND_LANDED_COST_HOLD_V1";

    /** One source-exact aggregate for each distinct original receipt ID. */
    public record Source(UUID receiptId,long globalClaimRows,long distinctOrderCount,
        long totalClaimedUnits,long snapshotAcceptedUnits,
        long acceptedSnapshotVersionCount,long originalCurrencyCount,
        long originalPriceCount,long invalidOrRetractedClaimRows,
        String referenceCurrency,BigDecimal referenceUnitPrice) {}

    public record Receipt(String originalPurchaseReceiptId,
        long thisOrderClaimedUnits,long allOrdersClaimedUnits,
        long originalQcAcceptedUnits,long unitsClaimedByOtherOrders,
        long locallyUnclaimedAcceptedUnits,int thisOrderClaimCount,
        long totalClaimRows,long distinctOriginalSaleOrders,
        long staleOrRetractedClaimRows,String originalCurrency,
        String originalPurchaseReferenceUnitPrice,
        String localCapacityState,boolean externallyVerifiedLandedUnitCost,
        String actualLandedUnitCost) {}

    public record Result(String marketplaceId,String orderId,String ruleVersion,
        String sourceBasis,int originalReceiptCount,long globalClaimRows,
        long sharedOriginalReceiptCount,long otherOrderReceiptMembershipCount,
        List<Receipt> receipts,List<String> blockerCodes,
        boolean completeGlobalLocalReceiptCapacity,
        boolean everyRefundUnitMappedToOriginalReceipt,
        boolean independentlyVerifiedActualLandedCosts,
        boolean physicalReturnToOriginalReceiptVerified,
        boolean originalPurchaseFxAndDutiesVerified,
        boolean crossPeriodCogsDoublePostingExcluded,
        boolean refundCogsReversalAllowed,String actualRefundCogsReversal,
        String realizedNetProfit) {}

    static Result calculate(UUID marketplace,UUID order,
        RefundBatchLineageGuardV212.Preview v212,List<Source> global) {
        if(marketplace==null||order==null||v212==null||global==null
            ||global.size()>1000||v212.orderItems()==null
            ||v212.originalSaleLotClaimCount()>1000
            ||!marketplace.toString().equals(v212.marketplaceId())
            ||!order.toString().equals(v212.orderId())
            ||v212.refundCogsReversalAllowed()
            ||v212.actualRefundCogsReversal()!=null
            ||v212.realizedNetProfit()!=null
            ||v212.independentlyVerifiedActualBatchCogs())
            throw new IllegalArgumentException("V212 current source or COGS trust boundary invalid");

        var blockers=new LinkedHashSet<String>();
        blockers.addAll(v212.blockerCodes());
        blockers.add("LOCAL_PURCHASE_RECEIPT_IS_NOT_EXTERNAL_AMAZON_FULFILLMENT");
        blockers.add("CUSTOMER_REFUND_UNIT_NOT_MATCHED_TO_INDIVIDUAL_ORIGINAL_LOT");
        blockers.add("ORIGINAL_PURCHASE_PO_PRICE_IS_NOT_FINAL_LANDED_UNIT_COST");
        blockers.add("INBOUND_FREIGHT_IMPORT_DUTY_TAX_AND_FX_SOURCE_NOT_ALLOCATED_TO_RECEIPT");
        blockers.add("G1_REFUND_COGS_RECOGNITION_AND_PERIOD_POLICY_NOT_APPROVED");
        blockers.add("REFUND_COGS_UNIQUE_LEDGER_POSTING_NOT_INDEPENDENTLY_VERIFIED");

        var thisOrder=new LinkedHashMap<UUID,Long>();
        var claimCount=new HashMap<UUID,Integer>();
        var claimIds=new HashSet<UUID>();
        var prices=new HashMap<UUID,BigDecimal>();
        var currencies=new HashMap<UUID,String>();
        int allClaims=0;
        for(var item:v212.orderItems()) {
            if(item==null||item.saleLotClaims()==null)
                throw new IllegalArgumentException("V212 item lacks original sale-lot claims");
            if(item.originalSaleQuantity()==null||item.originalSaleQuantity()<=0)
                blockers.add("ORIGINAL_SALE_ITEM_QUANTITY_UNKNOWN");
            long localQuantity=0;
            for(var lot:item.saleLotClaims()) {
                if(lot==null||lot.originalPurchaseReceiptId()==null||lot.claimId()==null
                    ||lot.locallyClaimedUnits()<=0
                    ||!claimIds.add(UUID.fromString(lot.claimId()))
                    ||!item.orderItemId().equals(lot.orderItemId()))
                    throw new IllegalArgumentException("Duplicate or cross-item original sale lot");
                var receipt=UUID.fromString(lot.originalPurchaseReceiptId());
                thisOrder.merge(receipt,(long)lot.locallyClaimedUnits(),Math::addExact);
                localQuantity=Math.addExact(localQuantity,lot.locallyClaimedUnits());
                claimCount.merge(receipt,1,Integer::sum);
                allClaims++;
                if(lot.purchaseReferenceCurrency()==null
                    ||!lot.purchaseReferenceCurrency().matches("[A-Z]{3}")
                    ||lot.purchaseReferenceUnitCost()==null)
                    throw new IllegalArgumentException("Missing source PO reference identity");
                BigDecimal price;
                try {price=new BigDecimal(lot.purchaseReferenceUnitCost());}
                catch(NumberFormatException invalid) {
                    throw new IllegalArgumentException("Malformed source purchase reference",invalid);
                }
                if(price.signum()<0||price.scale()>6)
                    throw new IllegalArgumentException("Invalid original purchase reference precision");
                if(prices.putIfAbsent(receipt,price)!=null
                    &&prices.get(receipt).compareTo(price)!=0)
                    blockers.add("SAME_ORIGINAL_RECEIPT_HAS_DIFFERENT_ORDER_CLAIM_PRICES");
                if(currencies.putIfAbsent(receipt,lot.purchaseReferenceCurrency())!=null
                    &&!currencies.get(receipt).equals(lot.purchaseReferenceCurrency()))
                    blockers.add("SAME_ORIGINAL_RECEIPT_HAS_DIFFERENT_ORDER_CLAIM_CURRENCIES");
                if(!"LOCAL_LINK_INTACT_UNVERIFIED".equals(lot.localIdentityState()))
                    blockers.add("V212_ORIGINAL_LOT_CLAIM_RETRACTED_OR_STALE");
            }
            if(localQuantity!=item.locallyAttributedSaleQuantity())
                throw new IllegalArgumentException("V212 item sale-lot arithmetic changed");
            if(item.originalSaleQuantity()!=null&&localQuantity>item.originalSaleQuantity())
                blockers.add("ORIGINAL_SALE_LOT_ALLOCATION_EXCEEDS_LIVE_SALE_ITEM");
        }
        if(allClaims!=v212.originalSaleLotClaimCount())
            throw new IllegalArgumentException("V212 original claim count changed");
        if(thisOrder.isEmpty())blockers.add("NO_ORIGINAL_SALE_LOT_RECEIPTS_TO_CHECK");
        if(!v212.localAllItemQuantitiesConsistent())
            blockers.add("V212_REFUND_RETURN_QUANTITY_ENVELOPE_INCOMPLETE");

        var observations=new LinkedHashMap<UUID,Source>();
        long globalRows=0;
        long shared=0;
        long otherOrders=0;
        var lines=new ArrayList<Receipt>();
        boolean consistent=!thisOrder.isEmpty()&&v212.localAllItemQuantitiesConsistent();
        for(var row:global) {
            if(row==null||row.receiptId()==null||observations.putIfAbsent(row.receiptId(),row)!=null
                ||!thisOrder.containsKey(row.receiptId()))
                throw new IllegalArgumentException("Unexpected or duplicated global receipt source");
        }
        if(observations.size()!=thisOrder.size())
            throw new IllegalArgumentException("Incomplete global receipt source population");

        for(var entry:thisOrder.entrySet()) {
            UUID rid=entry.getKey();
            Source src=observations.get(rid);
            long own=entry.getValue();
            globalRows=Math.addExact(globalRows,src.globalClaimRows());
            boolean ok=src.globalClaimRows()>0&&src.globalClaimRows()<=1000
                &&src.distinctOrderCount()>0
                &&src.globalClaimRows()>=src.distinctOrderCount()
                &&src.globalClaimRows()>=claimCount.get(rid)
                &&src.snapshotAcceptedUnits()>0
                &&src.totalClaimedUnits()>=own
                &&src.totalClaimedUnits()<=src.snapshotAcceptedUnits()
                &&src.acceptedSnapshotVersionCount()==1
                &&src.originalCurrencyCount()==1&&src.originalPriceCount()==1
                &&src.invalidOrRetractedClaimRows()>=0
                &&src.invalidOrRetractedClaimRows()<=src.globalClaimRows()
                &&src.referenceCurrency()!=null
                &&src.referenceCurrency().equals(currencies.get(rid))
                &&src.referenceUnitPrice()!=null
                &&src.referenceUnitPrice().compareTo(prices.get(rid))==0;
            String state;
            if(!ok) {
                state="GLOBAL_ORIGINAL_RECEIPT_CAPACITY_OR_REFERENCE_CONFLICT";
                blockers.add(state);
                consistent=false;
            } else if(src.invalidOrRetractedClaimRows()>0) {
                state="OTHER_OR_ORIGINAL_ORDER_HISTORICAL_LOT_CLAIMS_STALE";
                blockers.add(state);
                consistent=false;
            } else {
                state="LOCAL_RECEIPT_CAPACITY_WITHIN_QC_UNVERIFIED_EXTERNAL";
            }
            long other=src.totalClaimedUnits()-own;
            long free=src.snapshotAcceptedUnits()-src.totalClaimedUnits();
            if(src.distinctOrderCount()>1) {
                shared++;
                otherOrders=Math.addExact(otherOrders,src.distinctOrderCount()-1);
                blockers.add("ORIGINAL_RECEIPT_SHARED_ACROSS_LOCAL_SALE_ORDERS_NOT_DOUBLE_POSTING");
            }
            lines.add(new Receipt(rid.toString(),own,src.totalClaimedUnits(),
                src.snapshotAcceptedUnits(),Math.max(0,other),Math.max(0,free),
                claimCount.get(rid),src.globalClaimRows(),
                src.distinctOrderCount(),src.invalidOrRetractedClaimRows(),
                src.referenceCurrency(),
                src.referenceUnitPrice()==null?null:src.referenceUnitPrice().toPlainString(),
                state,false,null));
        }
        if(globalRows>1000)throw new IllegalArgumentException("Global receipt claim population too large");
        if(blockers.contains("SAME_ORIGINAL_RECEIPT_HAS_DIFFERENT_ORDER_CLAIM_PRICES")
            ||blockers.contains("SAME_ORIGINAL_RECEIPT_HAS_DIFFERENT_ORDER_CLAIM_CURRENCIES")
            ||blockers.contains("ORIGINAL_SALE_ITEM_QUANTITY_UNKNOWN")
            ||blockers.contains("ORIGINAL_SALE_LOT_ALLOCATION_EXCEEDS_LIVE_SALE_ITEM")
            ||blockers.contains("V212_ORIGINAL_LOT_CLAIM_RETRACTED_OR_STALE"))
            consistent=false;
        if(!consistent)blockers.add("CROSS_ORDER_ORIGINAL_LOT_CAPACITY_HOLD");
        return new Result(marketplace.toString(),order.toString(),RULE,
            "V106_IMMUTABLE_LOCAL_RECEIPT_CLAIMS_V212_REFUND_ORIGINAL_LOTS",
            thisOrder.size(),globalRows,shared,otherOrders,List.copyOf(lines),
            List.copyOf(blockers),consistent,false,false,false,false,false,
            false,null,null);
    }
}
