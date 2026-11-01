package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ItemAllocationServiceTest {
    @Test void validatesMakerCheckerAndReasonBeforeAnyMutatingSql(){
        UUID maker=UUID.randomUUID();
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->
                ItemAllocationService.requireIndependent(maker,maker)).getStatusCode());
        ItemAllocationService.requireIndependent(maker,UUID.randomUUID());
        assertThrows(ResponseStatusException.class,()->
            ItemAllocationService.reason("    "));
        assertThrows(ResponseStatusException.class,()->
            ItemAllocationService.reason("valid reason\nbut multiline"));
        assertThrows(ResponseStatusException.class,()->
            ItemAllocationService.reference("123"));
        var jdbc=mock(JdbcTemplate.class);
        var service=new ItemAllocationService(jdbc,
            mock(FinanceClassificationService.class),mock(OrderCostReadinessService.class));
        assertThrows(ResponseStatusException.class,()->
            service.save(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                maker,"item","PRODUCT_REFUND","-2","USD",
                "EVIDENCE-2026","A valid rationale for allocation"));
        verifyNoInteractions(jdbc);
    }
    @Test void scopeComesFromOwnerSessionNotFromBrowserAndAlwaysNoStore(){
        var tenant=UUID.randomUUID();var market=UUID.randomUUID();
        var event=UUID.randomUUID();var user=UUID.randomUUID();
        var access=mock(TenantAccessService.class);
        var service=mock(ItemAllocationService.class);
        var auth=mock(Authentication.class);
        var req=mock(HttpServletRequest.class);
        when(access.currentTenant(auth,req)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(user);
        var controller=new ItemAllocationController(access,service);
        var response=controller.preview(auth,req,market,event);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        controller.save(auth,req,market,event,new ItemAllocationController.LineChange(
            "item-1","PRODUCT_SALES","20","USD","REVIEW-2026-001",
            "Source reviewed against item evidence"));
        verify(service).save(tenant,market,event,user,"item-1","PRODUCT_SALES",
            "20","USD","REVIEW-2026-001","Source reviewed against item evidence");
        controller.approve(auth,req,market,event,
            new ItemAllocationController.Reason("Independent owner verified allocations"));
        verify(service).decide(tenant,market,event,user,true,
            "Independent owner verified allocations");
    }
}
