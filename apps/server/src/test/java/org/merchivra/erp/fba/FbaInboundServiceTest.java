package org.merchivra.erp.fba;

import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FbaInboundServiceTest {
    @Test void requiresPositiveUniqueShipmentLines(){
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->FbaInboundService.validateLines(List.of())).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->FbaInboundService.validateLines(List.of(
                new FbaInboundService.LineInput("SKU-1",2),
                new FbaInboundService.LineInput("SKU-1",3)))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->FbaInboundService.validateLines(List.of(
                new FbaInboundService.LineInput("SKU-1",0)))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->FbaInboundService.validateLines(List.of(
                new FbaInboundService.LineInput("SKU-1",1000001)))).getStatusCode());
        FbaInboundService.validateLines(List.of(
            new FbaInboundService.LineInput("SKU-1",1),
            new FbaInboundService.LineInput("SKU-2",1000000)));
    }
    @Test void reconciledShortfallsAreNotStillInTransit(){
        assertNull(FbaInboundService.estimatedInTransit("DRAFT",10,0));
        assertEquals(8,FbaInboundService.estimatedInTransit("IN_TRANSIT",10,2));
        assertEquals(0,FbaInboundService.estimatedInTransit("RECEIVING",10,12));
        assertNull(FbaInboundService.estimatedInTransit("REVIEWING",10,2));
        assertNull(FbaInboundService.estimatedInTransit("RECONCILED",10,2));
    }
    @Test void apiDerivesTenantAndActorFromOwnerSession(){
        TenantAccessService access=mock(TenantAccessService.class);
        FbaInboundService service=mock(FbaInboundService.class);
        HttpServletRequest request=mock(HttpServletRequest.class);
        Authentication auth=mock(Authentication.class);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID(),actor=UUID.randomUUID(),
            id=UUID.randomUUID(),key=UUID.randomUUID();
        when(access.currentTenant(auth,request)).thenReturn(tenant);
        when(access.authenticatedUser(auth)).thenReturn(actor);
        var api=new FbaInboundController(access,service);
        var create=new FbaInboundService.Create(key,"LOCAL-FBA-001","MOCK",
            List.of(new FbaInboundService.LineInput("SKU-1",5)));
        assertTrue(api.create(auth,request,mp,create).getHeaders()
            .getCacheControl().contains("no-store"));
        verify(service).create(tenant,mp,actor,create);
        api.dispatch(auth,request,mp,id);
        verify(service).dispatch(tenant,mp,id);
        var receipt=new FbaInboundService.Receipt(key,"SKU-1",2,"demo-receipt","MOCK");
        api.receipt(auth,request,mp,id,receipt);
        verify(service).receipt(tenant,mp,id,actor,receipt);
        api.review(auth,request,mp,id);
        verify(service).startReview(tenant,mp,id);
        var resolution=new FbaInboundService.Resolve("SKU-1","Reviewed local proof");
        api.resolve(auth,request,mp,id,resolution);
        verify(service).resolve(tenant,mp,id,actor,resolution);
        api.reconcile(auth,request,mp,id);
        verify(service).finalizeReview(tenant,mp,id);
        api.get(auth,request,mp,id);
        verify(service).detail(tenant,mp,id);
        api.list(auth,request,mp);
        verify(service).list(tenant,mp);
    }
}
