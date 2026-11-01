package org.merchivra.erp.finance;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundRecoveryServiceTest {
    @Test void independentReviewMustNotBeSelfApproved() {
        UUID actor=UUID.randomUUID();
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->RefundRecoveryService.requireIndependent(actor,actor))
                .getStatusCode());
        RefundRecoveryService.requireIndependent(actor,UUID.randomUUID());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->RefundRecoveryService.reason("no")).getStatusCode());
    }
    @Test void evidenceFingerprintChangesWhenManualQuantitiesOrCostsChange() {
        String a=RefundRecoveryService.hash("event-1|original-cost=8.25|quantity=2");
        String b=RefundRecoveryService.hash("event-1|original-cost=8.25|quantity=3");
        assertEquals(64,a.length());
        assertNotEquals(a,b);
        assertNotEquals(a,RefundRecoveryService.hash("event-1|original-cost=9.00|quantity=2"));
    }
    @Test void moneyReviewCanOnlyStartOnceEveryRefundLineHasBeenAllocated() {
        var p=new RefundRecoveryEngine.Preview(
            "REFUND_ITEMS_OR_AMOUNT_INCOMPLETE","USD","-50","-20","-30",
            "10","0",null,false,false,java.util.List.of());
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->RefundRecoveryService.requireBalanced(p))
            .getStatusCode());
    }
    @Test void endpointEnforcesServerSideOwnerScopeAndNoStore() {
        var access=mock(TenantAccessService.class);
        var service=mock(RefundRecoveryService.class);
        var req=mock(HttpServletRequest.class);
        var auth=mock(Authentication.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),
            event=UUID.randomUUID(),actor=UUID.randomUUID();
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var api=new RefundRecoveryController(access,service);
        var response=api.preview(auth,req,mp,event);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).preview(tenant,mp,event);
        var reason=new RefundRecoveryController.Reason(
            "I checked the original item quantity and category amounts");
        api.request(auth,req,mp,event,reason);
        verify(service).request(tenant,mp,event,actor,reason.reason());
        api.approve(auth,req,mp,event,reason);
        verify(service).decide(tenant,mp,event,actor,true,reason.reason());
    }
}
