package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundBatchLineageV212Test {
    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-4000-8000-"
            +String.format("%012x",n));
    }
    private static final UUID MP=id(212),ORDER=id(220),E1=id(221),E2=id(222);
    private static final String ITEM="ORDER-ITEM-A",SHA="b".repeat(64);
    private static final String LOCAL="LOCAL_LINK_INTACT_UNVERIFIED";

    private static RefundRecoveryService.Status v63(UUID event,
        int qty,String disposition,String amount) {
        var cost=new BigDecimal("8").multiply(BigDecimal.valueOf(qty));
        var restock="RESTOCKED_CLAIMED".equals(disposition)
            ?cost.toPlainString():"0";
        var item=new RefundRecoveryEngine.Item(ITEM,"SKU-A",qty,
            disposition,amount,cost.toPlainString(),restock);
        var exposure=new RefundRecoveryEngine.Preview(
            "MANUAL_EVIDENCE_BALANCED_UNVERIFIED","USD",amount,amount,
            "0",cost.toPlainString(),restock,null,false,false,List.of(item));
        return new RefundRecoveryService.Status(event.toString(),ORDER.toString(),
            "REVIEWED",1,"MANUAL_UNVERIFIED","MAKER","CHECKER",
            true,false,false,exposure);
    }
    private static RefundOrderConservationEngineV210.Result refund() {
        var a=new RefundOrderConservationEngineV210.Source(
            E1,ORDER,Instant.parse("2026-11-10T10:00:00Z"),
            "USD",new BigDecimal("-20.00"));
        var b=new RefundOrderConservationEngineV210.Source(
            E2,ORDER,Instant.parse("2026-11-14T10:00:00Z"),
            "USD",new BigDecimal("-10"));
        return RefundOrderConservationEngineV210.calculate(ORDER,
            List.of(new RefundOrderConservationEngineV210.SaleItem(ITEM,5)),
            List.of(new RefundOrderConservationEngineV210.ReviewItem(
                    E1,ITEM,2,"RESTOCKED_CLAIMED"),
                new RefundOrderConservationEngineV210.ReviewItem(
                    E2,ITEM,1,"NOT_RESTOCKED")),
            List.of(new RefundOrderConservationEngineV210.Probe(
                a,v63(E1,2,"RESTOCKED_CLAIMED","-20")),
                new RefundOrderConservationEngineV210.Probe(
                b,v63(E2,1,"NOT_RESTOCKED","-10"))));
    }
    private static List<RefundBatchLineageGuardV212.Reviewed> reviews() {
        return List.of(
            new RefundBatchLineageGuardV212.Reviewed(
                E1,ITEM,2,"RESTOCKED_CLAIMED"),
            new RefundBatchLineageGuardV212.Reviewed(
                E2,ITEM,1,"NOT_RESTOCKED"));
    }
    private static RefundBatchLineageGuardV212.LotClaim claim(
        int n,int quantity,String state,String currency,String price) {
        return new RefundBatchLineageGuardV212.LotClaim(
            id(n),id(n+100),ITEM,quantity,state,currency,
            new BigDecimal(price),SHA);
    }
    private static OrderLotAssignmentIntegrityServiceV203.Claim independent(
        RefundBatchLineageGuardV212.LotClaim c,String state) {
        return new OrderLotAssignmentIntegrityServiceV203.Claim(
            c.id().toString(),ITEM,"SKU-A",c.assignedQuantity(),3,3,3,
            state,"synthetic-dispatch",SHA,c.purchaseReceiptId().toString(),
            false,false,null);
    }
    private static OrderLotAssignmentIntegrityServiceV203.Preview lots(
        List<RefundBatchLineageGuardV212.LotClaim> records,
        String overrideFirstState) {
        var list=new java.util.ArrayList<OrderLotAssignmentIntegrityServiceV203.Claim>();
        for(var c:records) {
            var state=(overrideFirstState!=null&&list.isEmpty())
                ?overrideFirstState:c.integrityState();
            list.add(independent(c,state));
        }
        return new OrderLotAssignmentIntegrityServiceV203.Preview(
            ORDER.toString(),MP.toString(),"COGS_READY_PREVIEW",
            "V1","LOCAL_OUTBOUND_UNVERIFIED_EXTERNAL",
            List.copyOf(list),List.of(),false,false,null);
    }
    private static RefundBatchLineageGuardV212.Intake receipt(
        int n,int qty,String state,String manualState) {
        return new RefundBatchLineageGuardV212.Intake(
            id(n),id(332),E1,ITEM,qty,state,"SELLABLE_CLAIMED",
            manualState,"SELLABLE_CLAIMED",2);
    }
    private static RefundBatchLineageGuardV212.Preview check(
        List<RefundBatchLineageGuardV212.LotClaim> claims,
        List<RefundBatchLineageGuardV212.Intake> intakes) {
        return RefundBatchLineageGuardV212.calculate(MP,refund(),
            lots(claims,null),reviews(),claims,intakes);
    }

    @Test void partialRefundsAcrossTwoSourceLotsAreQuantityPossibleButNeverAssignedCost() {
        var claims=List.of(claim(300,2,LOCAL,"USD","6.50"),
            claim(301,1,LOCAL,"EUR","9.25"));
        var result=check(claims,List.of(receipt(501,2,"ATTESTED_LOCAL","REVIEWED_MANUAL")));
        assertTrue(refund().completeLocalManualConservation());
        assertTrue(result.localAllItemQuantitiesConsistent());
        var item=result.orderItems().getFirst();
        assertEquals(5,item.originalSaleQuantity());
        assertEquals(3,item.reviewedRefundQuantity());
        assertEquals(2,item.restockClaimedQuantity());
        assertEquals(3,item.locallyAttributedSaleQuantity());
        assertEquals(2,item.attestedLocalReturnQuantity());
        assertEquals(0,item.unassignedRefundQuantity());
        assertEquals(0,item.unattestedRestockQuantity());
        assertEquals(2,item.distinctOriginalPurchaseReceipts());
        assertEquals("6.50",item.saleLotClaims().getFirst()
            .purchaseReferenceUnitCost());
        assertEquals("EUR",item.saleLotClaims().get(1)
            .purchaseReferenceCurrency());
        assertFalse(result.independentlyVerifiedActualBatchCogs());
        assertFalse(result.externalAmazonRefundsComplete());
        assertFalse(result.originalAmazonFulfillmentVerified());
        assertFalse(result.physicalReturnReceiptVerified());
        assertFalse(result.accountingRecognitionApproved());
        assertFalse(result.refundCogsReversalAllowed());
        assertNull(result.actualRefundCogsReversal());
        assertNull(result.realizedNetProfit());
        assertNull(item.actualRefundBatchCost());
        assertTrue(result.blockerCodes().contains(
            "REFUND_ITEMS_NOT_INDEPENDENTLY_MAPPED_TO_ORIGINAL_SALE_LOTS"));
        assertThrows(UnsupportedOperationException.class,
            ()->result.orderItems().clear());
    }

    @Test void returnedThreeUnitsButOnlyTwoSaleLotUnitsAreClaimed() {
        var result=check(List.of(claim(300,2,LOCAL,"USD","7")),
            List.of(receipt(501,2,"ATTESTED_LOCAL","REVIEWED_MANUAL")));
        assertFalse(result.localAllItemQuantitiesConsistent());
        var item=result.orderItems().getFirst();
        assertEquals(1,item.unassignedRefundQuantity());
        assertTrue(item.blockerCodes().contains(
            "REFUND_UNITS_EXCEED_ORIGINAL_LOCAL_LOT_CLAIMS"));
        assertNull(item.actualRefundBatchCost());
    }

    @Test void restockedTwoUnitsButOnlyOneLocalReceiptIsAttested() {
        var result=check(List.of(claim(300,3,LOCAL,"USD","7")),
            List.of(receipt(501,1,"ATTESTED_LOCAL","REVIEWED_MANUAL")));
        assertFalse(result.localAllItemQuantitiesConsistent());
        assertEquals(1,result.orderItems().getFirst().unattestedRestockQuantity());
        assertTrue(result.blockerCodes().contains(
            "RESTOCK_CLAIM_QUANTITY_EXCEEDS_LOCAL_ATTESTED_INTAKE"));
    }

    @Test void pendingLocalIntakeIsNotPhysicalReceiptOrRecognizedCogs() {
        var result=check(List.of(claim(300,3,LOCAL,"USD","7")),
            List.of(receipt(501,2,"PENDING","REVIEWED_MANUAL")));
        assertFalse(result.localAllItemQuantitiesConsistent());
        assertEquals(2,result.orderItems().getFirst().pendingLocalReturnQuantity());
        assertEquals(0,result.orderItems().getFirst().attestedLocalReturnQuantity());
        assertNull(result.actualRefundCogsReversal());
    }

    @Test void saleLotRetractionOrChangedSkuReevaluationStillBlocks() {
        var revoked=claim(300,3,"RETRACTED","USD","7");
        var result=check(List.of(revoked),
            List.of(receipt(501,2,"ATTESTED_LOCAL","REVIEWED_MANUAL")));
        assertFalse(result.localAllItemQuantitiesConsistent());
        assertTrue(result.blockerCodes().contains(
            "ORIGINAL_SALE_LOT_SOURCE_RETRACTED_OR_STALE"));
        var local=claim(300,3,LOCAL,"USD","7");
        var stricter=RefundBatchLineageGuardV212.calculate(MP,refund(),
            lots(List.of(local),"SALE_SKU_MAPPING_STALE"),reviews(),
            List.of(local),List.of(receipt(501,2,"ATTESTED_LOCAL","REVIEWED_MANUAL")));
        assertFalse(stricter.localAllItemQuantitiesConsistent());
        assertEquals("SALE_SKU_MAPPING_STALE",stricter.orderItems().getFirst()
            .saleLotClaims().getFirst().localIdentityState());
    }

    @Test void duplicateParcelsOrSpoofedV171ClaimCannotIncreasePhysicalCoverage() {
        var lot=claim(300,3,LOCAL,"USD","7");
        var over=check(List.of(lot),
            List.of(receipt(501,2,"ATTESTED_LOCAL","REVIEWED_MANUAL"),
                receipt(502,2,"ATTESTED_LOCAL","REVIEWED_MANUAL")));
        assertFalse(over.localAllItemQuantitiesConsistent());
        assertTrue(over.blockerCodes().contains(
            "RETURN_INTAKE_DUPLICATE_OR_EXCEEDS_REFUND_QUANTITY"));
        var unreviewed=check(List.of(lot),
            List.of(receipt(501,2,"ATTESTED_LOCAL","PENDING")));
        assertFalse(unreviewed.localAllItemQuantitiesConsistent());
        assertTrue(unreviewed.blockerCodes().contains(
            "RETURN_INTAKE_MANUAL_CLAIM_STALE_OR_INVALID"));
    }

    @Test void duplicateV106IdentityOrV34SnapshotDriftIsExplicitConflict() {
        var lot=claim(300,3,LOCAL,"USD","7");
        assertThrows(IllegalArgumentException.class,()->
            RefundBatchLineageGuardV212.calculate(MP,refund(),
                lots(List.of(lot,lot),null),reviews(),
                List.of(lot,lot),List.of()));
        assertThrows(IllegalArgumentException.class,()->
            RefundBatchLineageGuardV212.calculate(MP,refund(),
                lots(List.of(lot),null),
                List.of(new RefundBatchLineageGuardV212.Reviewed(
                    E1,ITEM,3,"RESTOCKED_CLAIMED"),
                    new RefundBatchLineageGuardV212.Reviewed(
                    E2,ITEM,1,"NOT_RESTOCKED")),
                List.of(lot),List.of()));
    }

    @Test void fullScopeSqlKeepsEveryOriginalClaimAndLocalReceipt() {
        var c=RefundBatchLineageServiceV212.ORIGINAL_LOT_CLAIMS_SQL;
        var v=RefundBatchLineageServiceV212.REFUND_ITEM_REVIEWS_SQL;
        var i=RefundBatchLineageServiceV212.RETURN_INTAKES_SQL;
        assertTrue(c.contains("finance_sale_lot_claim_v106"));
        assertTrue(c.contains("finance_sale_lot_integrity_v106"));
        assertTrue(c.contains("c.sales_order_id=?"));
        assertTrue(v.contains("finance_refund_item_review"));
        assertTrue(v.contains("r.sales_order_id=?"));
        assertTrue(i.contains("finance_customer_return_intake_v97"));
        assertTrue(i.contains("finance_return_receipt_claim_v96"));
        assertTrue(i.contains("i.seller_marketplace_id=?"));
        for(var sql:List.of(c,v,i)) {
            assertTrue(sql.contains("LIMIT 1001"));
            assertFalse(sql.contains("LIMIT 50"));
        }
    }

    @Test void scopedReadOnlyRouteNeverAllowsInventoryOrFinancePost() {
        var access=mock(TenantAccessService.class);
        var svc=mock(RefundBatchLineageServiceV212.class);
        var ctl=new RefundBatchLineageControllerV212(access,svc);
        var auth=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        UUID tenant=id(2122);
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        var response=ctl.preview(auth,request,MP,ORDER);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(svc).preview(tenant,MP,ORDER);
    }
}
