package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReturnReceiptEvidenceServiceTest {
    static RefundRecoveryService.Status reviewed(RefundRecoveryEngine.Item item) {
        var p=new RefundRecoveryEngine.Preview(
            "MANUAL_EVIDENCE_BALANCED_UNVERIFIED","USD","-12.00","-12.00","0",
            "8.00","8.00",null,false,false,List.of(item));
        return new RefundRecoveryService.Status(UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),"REVIEWED",2,"MANUAL_UNVERIFIED",
            UUID.randomUUID().toString(),UUID.randomUUID().toString(),
            true,false,false,p);
    }
    @Test void quantityAndDispositionCannotBeAutoApproved() {
        var claim=new RefundRecoveryEngine.Item("order-line-1","SKU-1",2,
            "RESTOCKED_CLAIMED","-12.00","8.00","8.00");
        var ready=reviewed(claim);
        ReturnReceiptEvidenceService.requireReviewed(ready,claim,2);
        for(int quantity:new int[]{3,99})assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,()->
                ReturnReceiptEvidenceService.requireReviewed(ready,claim,quantity))
                .getStatusCode());
        var other=new RefundRecoveryEngine.Item("order-line-1","SKU-1",2,
            "UNKNOWN","-12.00","8.00","0");
        assertThrows(ResponseStatusException.class,()->
            ReturnReceiptEvidenceService.requireReviewed(ready,other,1));
        var pending=new RefundRecoveryService.Status(ready.eventId(),ready.orderId(),
            "PENDING",2,"MANUAL_UNVERIFIED",ready.requestedBy(),null,
            true,false,false,ready.exposure());
        assertThrows(ResponseStatusException.class,()->
            ReturnReceiptEvidenceService.requireReviewed(pending,claim,1));
    }
    @Test void evidenceReferencesAndUntrustedReceiptDataStrictlyValidated() {
        ReturnReceiptEvidenceService.validate(
            new ReturnReceiptEvidenceService.Request("line-1",1,"UNKNOWN",null,
                "LOCAL-RET-2026-TEST","Independently check physical goods and evidence"));
        for(var candidate:new String[]{"x","\nINVALID0000","with spaces 123"}) {
            assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,()->
                    ReturnReceiptEvidenceService.reference(candidate)).getStatusCode());
        }
        assertThrows(ResponseStatusException.class,()->
            ReturnReceiptEvidenceService.validate(
                new ReturnReceiptEvidenceService.Request("line-1",0,"UNKNOWN",null,
                    "LOCAL-RET-2026-TEST","Independent check physical goods")));
        assertThrows(ResponseStatusException.class,()->
            ReturnReceiptEvidenceService.validate(
                new ReturnReceiptEvidenceService.Request("line-1",1,
                    "SELLABLE",null,"LOCAL-RET-2026-TEST",
                    "Independent check physical goods")));
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->
                ReturnReceiptEvidenceService.validate(
                    new ReturnReceiptEvidenceService.Request("line-1",1,null,
                        null,"LOCAL-RET-2026-TEST","Independent check physical goods")))
                .getStatusCode());
        UUID maker=UUID.randomUUID();
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->
                RefundRecoveryService.requireIndependent(maker,maker)).getStatusCode());
    }
    @Test void ownerNoStoreAndServerActorOnEveryWrite() {
        var access=mock(TenantAccessService.class);
        var service=mock(ReturnReceiptEvidenceService.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        var mp=UUID.randomUUID();var event=UUID.randomUUID();
        var claim=UUID.randomUUID();var tenant=UUID.randomUUID();var actor=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var api=new ReturnReceiptEvidenceController(access,service);
        assertTrue(api.list(auth,req,mp,event).getHeaders().getCacheControl().contains("no-store"));
        verify(service).list(tenant,mp,event);
        var request=new ReturnReceiptEvidenceService.Request("line-1",1,
            "UNKNOWN",null,"LOCAL-RET-2026-TEST",
            "Independent check physical goods and evidence");
        api.create(auth,req,mp,event,request);
        verify(service).request(tenant,mp,event,actor,request);
        var note=new ReturnReceiptEvidenceController.Decision(
            "Independent second owner physically assessed the documents");
        api.approve(auth,req,mp,event,claim,note);
        verify(service).decide(tenant,mp,event,claim,actor,true,note.reason());
        api.reject(auth,req,mp,event,claim,note);
        verify(service).decide(tenant,mp,event,claim,actor,false,note.reason());
    }
    @Test void postgresForbidsClaimOrAuditMutationAndFinancialPosting() throws Exception {
        String sql=Files.readString(Path.of(
            "src/main/resources/db/migration/V96__manual_return_receipt_claims.sql"));
        for(var s:new String[]{
            "UNIQUE(tenant_id,id)","finance_refund_item_review_full_v96",
            "FOREIGN KEY(tenant_id,event_id,sales_order_id,external_item_id)",
            "REFERENCES finance_refund_item_review",
            "inventory_receipt_verified=FALSE","cogs_reversal_posted=FALSE",
            "RETURN NEW;","BEFORE UPDATE OR DELETE ON finance_return_receipt_claim_v96",
            "BEFORE UPDATE OR DELETE ON finance_return_receipt_audit_v96",
            "WHERE state IN ('PENDING','REVIEWED_MANUAL')"})
            assertTrue(sql.contains(s),s);
        String service=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/ReturnReceiptEvidenceService.java"));
        for(var bad:new String[]{
            "INSERT INTO erp_local_stock_movement","UPDATE erp_local_stock_movement",
            "INSERT INTO finance_profit","INSERT INTO finance_ledger",
            "UPDATE finance_source_event"})
            assertFalse(service.contains(bad),bad);
        assertTrue(service.contains("TransactionDefinition.ISOLATION_REPEATABLE_READ"));
    }
}
