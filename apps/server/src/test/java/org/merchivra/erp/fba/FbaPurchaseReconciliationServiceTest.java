package org.merchivra.erp.fba;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FbaPurchaseReconciliationServiceTest {
    private static FbaPurchaseReconciliationService.Finding finding(
      String shipment,int plan,long reported,String variance,String link,
      String po,String asn,boolean hasAsn) {
        return FbaPurchaseReconciliationService.classify(
            shipment,plan,reported,variance,link,po,asn,hasAsn);
    }
    @Test void onlySameLocalPlanAndReportCreatesQuantityFinding() {
        var high=finding("RECEIVING",12,14,null,null,null,null,false);
        assertEquals("LOCAL_OVER_REPORT",high.code());
        assertEquals("HIGH",high.severity());
        assertEquals("REVIEW_LOCAL_VARIANCE",high.action());
        var accepted=finding("RECONCILED",12,14,"ACCEPT_VARIANCE",null,null,null,false);
        assertEquals("LOCAL_REPORT_WITHOUT_IDENTITY",accepted.code(),
            "Unlinked local report should remain visible even after variance acceptance");
        assertEquals("WARNING",accepted.severity());
        var acceptedLinked=finding("RECONCILED",12,14,"ACCEPT_VARIANCE",
            "CONFIRMED","APPROVED","SHIPPED",true);
        assertEquals("LOCAL_VARIANCE_ACCEPTED",acceptedLinked.code());
        assertEquals("INFO",acceptedLinked.severity());
    }
    @Test void pendingIdentityAndSourceChangeAreActionable() {
        var pending=finding("DRAFT",12,0,null,"PROPOSED","APPROVED","SHIPPED",true);
        assertEquals("PENDING_IDENTITY_REVIEW",pending.code());
        assertEquals("SECOND_OWNER_REVIEW",pending.action());
        var canceled=finding("IN_TRANSIT",12,3,null,
            "CONFIRMED","CANCELLED","SHIPPED",true);
        assertEquals("LINK_SOURCE_CHANGED",canceled.code());
        var recalledAsn=finding("RECEIVING",12,3,null,
            "CONFIRMED","RECEIVING","CANCELLED",true);
        assertEquals("LINK_SOURCE_CHANGED",recalledAsn.code());
    }
    @Test void unknownCrossStageQuantitiesNeverInventLoss() {
        assertEquals("CONFIRMED_PO_ONLY",finding("RECEIVING",20,2,null,
            "CONFIRMED","APPROVED",null,false).code());
        assertEquals("IDENTITY_CONFIRMED",finding("RECEIVING",20,0,null,
            "CONFIRMED","RECEIVED","SHIPPED",true).code());
        assertEquals("UNLINKED_DRAFT",finding("DRAFT",20,0,null,
            null,null,null,false).code());
        assertEquals("DISPATCHED_WITHOUT_LINK",finding("IN_TRANSIT",20,0,null,
            null,null,null,false).code());
        assertEquals("LOCAL_REPORT_WITHOUT_IDENTITY",finding("RECEIVING",20,5,null,
            null,null,null,false).code());
        assertEquals("LOCAL_VARIANCE_PENDING",finding("REVIEWING",20,5,null,
            "CONFIRMED","APPROVED","SHIPPED",true).code());
    }
}
