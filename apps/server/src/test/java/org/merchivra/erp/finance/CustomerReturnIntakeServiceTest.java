package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerReturnIntakeServiceTest {
    @Test void invalidSourceAndQuantityAreClientErrors() {
        var good=new CustomerReturnIntakeService.Request(UUID.randomUUID(),
            UUID.randomUUID(),"RETURN-PARCEL-20261116-001",2,"UNKNOWN",
            "Independent physically observed return parcel source");
        CustomerReturnIntakeService.validate(good);
        for(var bad:new CustomerReturnIntakeService.Request[]{
            new CustomerReturnIntakeService.Request(null,good.warehouseId(),
                good.receiptReference(),1,"UNKNOWN",good.note()),
            new CustomerReturnIntakeService.Request(good.claimId(),null,
                good.receiptReference(),1,"UNKNOWN",good.note()),
            new CustomerReturnIntakeService.Request(good.claimId(),good.warehouseId(),
                good.receiptReference(),0,"UNKNOWN",good.note()),
            new CustomerReturnIntakeService.Request(good.claimId(),good.warehouseId(),
                good.receiptReference(),1,null,good.note()),
            new CustomerReturnIntakeService.Request(good.claimId(),good.warehouseId(),
                good.receiptReference(),1,"SELLABLE",good.note()),
            new CustomerReturnIntakeService.Request(good.claimId(),good.warehouseId(),
                "x",1,"UNKNOWN",good.note())}) {
            assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                    ()->CustomerReturnIntakeService.validate(bad)).getStatusCode());
        }
        CustomerReturnIntakeService.validateCursor(null,null);
        CustomerReturnIntakeService.validateCursor(
            OffsetDateTime.parse("2026-11-16T12:00:00Z"),UUID.randomUUID());
        assertThrows(ResponseStatusException.class,()->
            CustomerReturnIntakeService.validateCursor(null,UUID.randomUUID()));
        assertThrows(ResponseStatusException.class,()->
            CustomerReturnIntakeService.validateCursor(
                OffsetDateTime.parse("2026-11-16T12:00:00Z"),null));
    }
    @Test void onlyCurrentV171ManualReviewCanRequestNewParcels() {
        var claim=new ReturnReceiptEvidenceService.Claim(
            UUID.randomUUID().toString(),UUID.randomUUID().toString(),"order-line",
            UUID.randomUUID().toString(),3,"UNKNOWN",null,"RETURN-CLAIM-20261116",
            "REVIEWED_MANUAL",2,UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),"2026-11-16T09:00:00Z",true,
            false,false,false,null);
        CustomerReturnIntakeService.eligible(claim,2);
        assertEquals(64,CustomerReturnIntakeService.hash(claim).length());
        for(int qty:new int[]{0,4,999})assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,()->
                CustomerReturnIntakeService.eligible(claim,qty)).getStatusCode());
        var stale=new ReturnReceiptEvidenceService.Claim(claim.id(),claim.eventId(),
            claim.itemId(),claim.orderId(),claim.claimedQuantity(),claim.condition(),
            claim.warehouseId(),claim.evidenceReference(),"STALE",claim.reviewRevision(),
            claim.requestedBy(),claim.decidedBy(),claim.requestedAt(),false,
            false,false,false,null);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,()->
                CustomerReturnIntakeService.eligible(stale,1)).getStatusCode());
    }
    @Test void ownerSessionCsrfAndNoStoreAreAuthoritative() {
        var access=mock(TenantAccessService.class);
        var svc=mock(CustomerReturnIntakeService.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),event=UUID.randomUUID(),
            actor=UUID.randomUUID(),id=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var controller=new CustomerReturnIntakeController(access,svc);
        assertTrue(controller.report(auth,req,mp,event,null,null)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(svc).report(tenant,mp,event,null,null);
        var input=new CustomerReturnIntakeService.Request(id,UUID.randomUUID(),
            "RETURN-PARCEL-20261116-001",1,"UNKNOWN",
            "Manually observed parcel evidence record");
        controller.create(auth,req,mp,event,input);
        verify(svc).request(tenant,mp,event,actor,input);
        controller.attest(auth,req,mp,event,id,new CustomerReturnIntakeService.Decision(
            "Independent local assessor reviewed source"));
        verify(svc).decide(tenant,mp,event,id,actor,true,
            "Independent local assessor reviewed source");
    }
    @Test void databaseConservesTenantParcelIdentityAndCannotPostCogs()
        throws Exception {
        String sql=Files.readString(Path.of(
            "src/main/resources/db/migration/V97__customer_return_local_intake.sql"));
        for(String s:new String[]{
            "UNIQUE(tenant_id,warehouse_id,receipt_reference)",
            "finance_return_receipt_claim_scoped_v97",
            "FOREIGN KEY(tenant_id,claim_id,event_id,sales_order_id,external_item_id)",
            "LOCAL_TWO_PERSON_UNVERIFIED",
            "CHECK(external_wms_verified=FALSE)",
            "CHECK(inventory_posted=FALSE)","CHECK(cogs_posted=FALSE)",
            "BEFORE UPDATE OR DELETE ON finance_customer_return_intake_v97",
            "BEFORE UPDATE OR DELETE ON finance_customer_return_intake_audit_v97"})
            assertTrue(sql.contains(s),s);
        String java=Files.readString(Path.of(
            "src/main/java/org/merchivra/erp/finance/CustomerReturnIntakeService.java"));
        for(String s:new String[]{
            "state IN ('PENDING','ATTESTED_LOCAL')",
            "reserved+input.quantity()>before.claimedQuantity()",
            "TransactionDefinition.ISOLATION_REPEATABLE_READ",
            "ORDER BY requested_at DESC,id DESC LIMIT 51",
            "SELECT id FROM sales_order",
            "FOR UPDATE",
            "return new Page(event.toString()"})
            assertTrue(java.contains(s),s);
        for(String unsafe:new String[]{
            "INSERT INTO erp_local_stock_movement","UPDATE erp_local_stock_movement",
            "INSERT INTO finance_profit_report","UPDATE finance_profit_report",
            "INSERT INTO finance_ledger","UPDATE finance_source_event"})
            assertFalse(java.contains(unsafe),unsafe);
    }
}
