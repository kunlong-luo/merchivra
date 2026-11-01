package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankPayoutManualReviewServiceTest {
    @Test void bankerMakersAndRequestersCannotApproveTheirOwnEvidence() {
        UUID maker=UUID.randomUUID(),other=UUID.randomUUID(),third=UUID.randomUUID();
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->BankPayoutManualReviewService.ensureIndependent(maker,other,maker))
                .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->BankPayoutManualReviewService.ensureIndependent(maker,other,other))
                .getStatusCode());
        BankPayoutManualReviewService.ensureIndependent(maker,other,third);
    }
    @Test void differentReportScopeIsNotAReasonToApproveBankReceipt() {
        assertEquals(64,BankPayoutManualReviewService.digest("document:report:bank").length());
        assertNotEquals(BankPayoutManualReviewService.digest("report1"),
            BankPayoutManualReviewService.digest("report2"));
    }
    @Test void endpointUsesAuthenticatedOwnerAndNoStoreWithoutBankIdentifiers() {
        var owner=mock(TenantAccessService.class);
        var service=mock(BankPayoutManualReviewService.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),
            report=UUID.randomUUID(),actor=UUID.randomUUID();
        when(owner.currentTenant(auth,req)).thenReturn(tenant);
        when(owner.authenticatedUser(auth)).thenReturn(actor);
        var api=new BankPayoutManualReviewController(owner,service);
        var note=new BankPayoutManualReviewController.Reason(
            "Reviewed anonymized bank reference sources");
        var response=api.request(auth,req,mp,report,note);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).request(tenant,mp,report,actor,note.reason());
        api.approve(auth,req,mp,report,note);
        verify(service).decide(tenant,mp,report,actor,true,note.reason());
        api.reject(auth,req,mp,report,note);
        verify(service).decide(tenant,mp,report,actor,false,note.reason());
    }
}
