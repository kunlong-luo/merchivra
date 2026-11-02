package org.merchivra.erp.fba;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FbaPurchaseExceptionServiceTest {
    private static FbaPurchaseReconciliationService.Line line(
        String shipmentState,long reported,String linkState,String asnState) {
        return new FbaPurchaseReconciliationService.Line(
            "22222222-2222-4222-8222-222222222222","LOCAL-V77",
            shipmentState,"MOCK","33333333-3333-4333-8333-333333333333",
            "FICTIONAL-V77",10,reported,null,
            linkState==null?null:"44444444-4444-4444-8444-444444444444",
            linkState,"V77 proof",
            linkState==null?null:"55555555-5555-4555-8555-555555555555",
            linkState==null?null:"PO-LOCAL-V77","APPROVED",10,0L,
            asnState==null?null:"66666666-6666-4666-8666-666666666666",
            asnState,10,0L,
            linkState==null?"UNLINKED_DRAFT":"IDENTITY_CONFIRMED",
            "INFO","NONE");
    }
    @Test void stableFingerprintOnIdenticalLocalEvidence() {
        var before=line("DRAFT",0,null,null);
        assertEquals(FbaPurchaseExceptionService.fingerprint(before),
            FbaPurchaseExceptionService.fingerprint(line("DRAFT",0,null,null)));
        assertTrue(FbaPurchaseExceptionService.fingerprint(before).matches("[0-9a-f]{64}"));
    }
    @Test void evidenceChangeRequiresFreshAcknowledgement() {
        var before=line("DRAFT",0,"CONFIRMED","SHIPPED");
        assertNotEquals(FbaPurchaseExceptionService.fingerprint(before),
            FbaPurchaseExceptionService.fingerprint(line("DRAFT",1,"CONFIRMED","SHIPPED")));
        assertNotEquals(FbaPurchaseExceptionService.fingerprint(before),
            FbaPurchaseExceptionService.fingerprint(line("IN_TRANSIT",0,"CONFIRMED","SHIPPED")));
        assertNotEquals(FbaPurchaseExceptionService.fingerprint(before),
            FbaPurchaseExceptionService.fingerprint(line("DRAFT",0,"PROPOSED","SHIPPED")));
        assertNotEquals(FbaPurchaseExceptionService.fingerprint(before),
            FbaPurchaseExceptionService.fingerprint(line("DRAFT",0,"CONFIRMED","CANCELLED")));
    }
    @Test void fingerprintDoesNotUseCrossStageReceiptNumbersAsLoss() {
        var l=line("RECEIVING",2,"CONFIRMED","SHIPPED");
        var changedPoReceipt=new FbaPurchaseReconciliationService.Line(
            l.shipmentId(),l.shipmentReference(),l.shipmentState(),l.shipmentOrigin(),
            l.inboundLineId(),l.sellerSku(),l.plannedQuantity(),
            l.locallyReportedQuantity(),l.localVarianceDecision(),l.linkId(),
            l.linkState(),l.proofReference(),l.purchaseOrderId(),
            l.purchaseOrderNumber(),l.purchaseOrderState(),
            l.purchaseOrderedQuantity(),999L,l.asnId(),l.asnState(),
            l.asnReportedQuantity(),999L,l.findingCode(),l.severity(),
            l.recommendedAction());
        assertEquals(FbaPurchaseExceptionService.fingerprint(l),
            FbaPurchaseExceptionService.fingerprint(changedPoReceipt));
    }
}
