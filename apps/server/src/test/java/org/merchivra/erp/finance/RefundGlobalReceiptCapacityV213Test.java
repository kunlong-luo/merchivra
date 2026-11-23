package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Cross-order original receipt is NOT a certified landed-cost ledger. */
class RefundGlobalReceiptCapacityV213Test {
    static UUID id(int n) {
        return UUID.fromString("00000000-0000-4000-8000-"+
            String.format("%012x",n));
    }
    static final UUID MP=id(213),ORDER=id(214),RECEIPT=id(215);
    static final String ITEM="ORDER-ITEM-1";
    static final String INTACT="LOCAL_LINK_INTACT_UNVERIFIED";

    static RefundBatchLineageGuardV212.Preview v212(
        int saleQty,int localQty,boolean localConsistent,
        String currency,String reference) {
        var lot=new RefundBatchLineageGuardV212.Lot(
            id(300).toString(),RECEIPT.toString(),ITEM,localQty,
            INTACT,currency,reference,false,null);
        var line=new RefundBatchLineageGuardV212.Item(
            ITEM,saleQty,2,2,localQty,2,0,0,0,1,
            localConsistent,List.of(lot),List.of(),null);
        var result=mock(RefundBatchLineageGuardV212.Preview.class);
        when(result.marketplaceId()).thenReturn(MP.toString());
        when(result.orderId()).thenReturn(ORDER.toString());
        when(result.originalSaleLotClaimCount()).thenReturn(1);
        when(result.orderItems()).thenReturn(List.of(line));
        when(result.localAllItemQuantitiesConsistent()).thenReturn(localConsistent);
        when(result.blockerCodes()).thenReturn(List.of("EXTERNAL_AUDIT_MISSING"));
        return result;
    }
    static RefundGlobalReceiptCapacityGateV213.Source global(
        long count,long orders,long assigned,long accepted,
        long versions,long currencies,long prices,long stale,
        String currency,String price) {
        return new RefundGlobalReceiptCapacityGateV213.Source(
            RECEIPT,count,orders,assigned,accepted,
            versions,currencies,prices,stale,currency,new BigDecimal(price));
    }

    @Test void oneReceiptSharedWithAnotherOrderCountsLocalCapacityOnceNeverPostsCost() {
        var result=RefundGlobalReceiptCapacityGateV213.calculate(
            MP,ORDER,v212(5,2,true,"USD","7.50"),
            List.of(global(2,2,4,6,1,1,1,0,"USD","7.50")));
        assertTrue(result.completeGlobalLocalReceiptCapacity());
        assertEquals(1,result.originalReceiptCount());
        assertEquals(1,result.sharedOriginalReceiptCount());
        assertEquals(1,result.otherOrderReceiptMembershipCount());
        assertEquals(2,result.globalClaimRows());
        var r=result.receipts().getFirst();
        assertEquals(2,r.thisOrderClaimedUnits());
        assertEquals(4,r.allOrdersClaimedUnits());
        assertEquals(2,r.unitsClaimedByOtherOrders());
        assertEquals(2,r.locallyUnclaimedAcceptedUnits());
        assertEquals(2,r.distinctOriginalSaleOrders());
        assertEquals("7.50",r.originalPurchaseReferenceUnitPrice());
        assertEquals("LOCAL_RECEIPT_CAPACITY_WITHIN_QC_UNVERIFIED_EXTERNAL",
            r.localCapacityState());
        assertTrue(result.blockerCodes().contains(
            "ORIGINAL_RECEIPT_SHARED_ACROSS_LOCAL_SALE_ORDERS_NOT_DOUBLE_POSTING"));
        assertFalse(result.everyRefundUnitMappedToOriginalReceipt());
        assertFalse(result.independentlyVerifiedActualLandedCosts());
        assertFalse(result.physicalReturnToOriginalReceiptVerified());
        assertFalse(result.originalPurchaseFxAndDutiesVerified());
        assertFalse(result.crossPeriodCogsDoublePostingExcluded());
        assertFalse(result.refundCogsReversalAllowed());
        assertNull(result.actualRefundCogsReversal());
        assertNull(result.realizedNetProfit());
        assertThrows(UnsupportedOperationException.class,()->result.receipts().clear());
    }

    @Test void competingOrderClaimsCannotExceedOriginalAcceptedUnits() {
        var result=RefundGlobalReceiptCapacityGateV213.calculate(
            MP,ORDER,v212(5,2,true,"USD","7.50"),
            List.of(global(3,2,7,6,1,1,1,0,"USD","7.50")));
        assertFalse(result.completeGlobalLocalReceiptCapacity());
        assertEquals("GLOBAL_ORIGINAL_RECEIPT_CAPACITY_OR_REFERENCE_CONFLICT",
            result.receipts().getFirst().localCapacityState());
        assertTrue(result.blockerCodes().contains("CROSS_ORDER_ORIGINAL_LOT_CAPACITY_HOLD"));
        assertNull(result.actualRefundCogsReversal());
    }

    @Test void retractionPreservesHistoricalReceiptCapacityAndFailsLocalGate() {
        var result=RefundGlobalReceiptCapacityGateV213.calculate(
            MP,ORDER,v212(5,2,true,"USD","7.50"),
            List.of(global(2,2,5,5,1,1,1,1,"USD","7.50")));
        assertEquals(0,result.receipts().getFirst().locallyUnclaimedAcceptedUnits());
        assertEquals(1,result.receipts().getFirst().staleOrRetractedClaimRows());
        assertEquals("OTHER_OR_ORIGINAL_ORDER_HISTORICAL_LOT_CLAIMS_STALE",
            result.receipts().getFirst().localCapacityState());
        assertFalse(result.completeGlobalLocalReceiptCapacity());
        assertNull(result.actualRefundCogsReversal());
    }

    @Test void originalReferenceCurrencyAndPriceAndAcceptedQuantityVersionsMustAgree() {
        for(var source:List.of(
            global(2,2,4,5,2,1,1,0,"USD","7.50"),
            global(2,2,4,5,1,2,1,0,"USD","7.50"),
            global(2,2,4,5,1,1,2,0,"USD","7.50"),
            global(2,2,4,5,1,1,1,0,"EUR","7.50"),
            global(2,2,4,5,1,1,1,0,"USD","7.60"))) {
            var result=RefundGlobalReceiptCapacityGateV213.calculate(
                MP,ORDER,v212(5,2,true,"USD","7.50"),List.of(source));
            assertFalse(result.completeGlobalLocalReceiptCapacity());
            assertEquals("GLOBAL_ORIGINAL_RECEIPT_CAPACITY_OR_REFERENCE_CONFLICT",
                result.receipts().getFirst().localCapacityState());
        }
    }

    @Test void v212LocalQuantityBreakOrOverAssignmentCannotBecomeGreenGlobally() {
        var incomplete=RefundGlobalReceiptCapacityGateV213.calculate(
            MP,ORDER,v212(5,2,false,"USD","7.50"),
            List.of(global(1,1,2,5,1,1,1,0,"USD","7.50")));
        assertFalse(incomplete.completeGlobalLocalReceiptCapacity());
        assertTrue(incomplete.blockerCodes().contains(
            "V212_REFUND_RETURN_QUANTITY_ENVELOPE_INCOMPLETE"));
        var over=RefundGlobalReceiptCapacityGateV213.calculate(
            MP,ORDER,v212(3,4,true,"USD","7.50"),
            List.of(global(1,1,4,6,1,1,1,0,"USD","7.50")));
        assertFalse(over.completeGlobalLocalReceiptCapacity());
        assertTrue(over.blockerCodes().contains(
            "ORIGINAL_SALE_LOT_ALLOCATION_EXCEEDS_LIVE_SALE_ITEM"));
    }

    @Test void changedOrDuplicatedGlobalReceiptPopulationFailsClosed() {
        var good=global(1,1,2,5,1,1,1,0,"USD","7.50");
        assertThrows(IllegalArgumentException.class,()->
            RefundGlobalReceiptCapacityGateV213.calculate(MP,ORDER,
                v212(5,2,true,"USD","7.50"),List.of()));
        assertThrows(IllegalArgumentException.class,()->
            RefundGlobalReceiptCapacityGateV213.calculate(MP,ORDER,
                v212(5,2,true,"USD","7.50"),List.of(good,good)));
        assertThrows(IllegalArgumentException.class,()->
            RefundGlobalReceiptCapacityGateV213.calculate(MP,id(900),
                v212(5,2,true,"USD","7.50"),List.of(good)));
    }

    @Test void noLocalReceiptCanNeverAuthenticateZeroCostOrNetProfit() {
        var p=mock(RefundBatchLineageGuardV212.Preview.class);
        when(p.marketplaceId()).thenReturn(MP.toString());
        when(p.orderId()).thenReturn(ORDER.toString());
        when(p.orderItems()).thenReturn(List.of());
        when(p.blockerCodes()).thenReturn(List.of());
        var result=RefundGlobalReceiptCapacityGateV213.calculate(
            MP,ORDER,p,List.of());
        assertFalse(result.completeGlobalLocalReceiptCapacity());
        assertTrue(result.blockerCodes().contains("NO_ORIGINAL_SALE_LOT_RECEIPTS_TO_CHECK"));
        assertNull(result.actualRefundCogsReversal());
        assertNull(result.realizedNetProfit());
    }

    @Test void sourceSqlReadsAllOrderClaimsNotOnlyCurrentOrderOrFilteredRetractions() {
        var sql=RefundGlobalReceiptCapacityServiceV213.GLOBAL_RECEIPT_SCOPE_SQL;
        assertTrue(sql.contains("FROM finance_sale_lot_claim_v106 c"));
        assertTrue(sql.contains("LEFT JOIN finance_sale_lot_integrity_v106 v"));
        assertTrue(sql.contains("count(DISTINCT c.sales_order_id)"));
        assertTrue(sql.contains("sum(c.assigned_quantity)"));
        assertTrue(sql.contains("v.integrity_state IS DISTINCT FROM"));
        assertTrue(sql.contains("c.tenant_id=? AND c.receipt_id IN (%s)"));
        assertTrue(sql.contains("LIMIT 1001"));
        assertFalse(sql.contains("c.sales_order_id=?"));
        assertFalse(sql.contains("RETRACTED'"));
    }

    @Test void tenantOwnerNoStoreControllerCannotPostCost() {
        var access=mock(TenantAccessService.class);
        var service=mock(RefundGlobalReceiptCapacityServiceV213.class);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        var controller=new RefundGlobalReceiptCapacityControllerV213(
            access,service);
        UUID tenant=id(333);
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        var response=controller.preview(auth,request,MP,ORDER);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).preview(tenant,MP,ORDER);
    }
}
