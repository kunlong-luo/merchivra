package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettlementSourceReviewControllerTest {
    @Test void actsOnlyInCurrentAuthenticatedOwnerTenantWithNoStore(){
        var access=mock(TenantAccessService.class);
        var reviews=mock(SettlementSourceReviewService.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),
            mp=UUID.randomUUID(),report=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var controller=new SettlementSourceReviewController(access,reviews);
        var note=new SettlementSourceReviewController.Reason("Independently reviewed origins");
        var response=controller.request(auth,req,mp,report,note);
        verify(reviews).request(tenant,mp,report,actor,note.reason());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        controller.approve(auth,req,mp,report,note);
        verify(reviews).decide(tenant,mp,report,actor,true,note.reason());
        controller.reject(auth,req,mp,report,note);
        verify(reviews).decide(tenant,mp,report,actor,false,note.reason());
        controller.status(auth,req,mp,report);
        controller.history(auth,req,mp,report);
        verify(reviews).status(tenant,mp,report);
        verify(reviews).history(tenant,mp,report);
    }
}
