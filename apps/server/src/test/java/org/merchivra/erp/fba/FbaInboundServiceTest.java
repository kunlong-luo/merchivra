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
    @Test void v92HistoryRejectsUnboundedPageBadStateAndMalformedCursor() {
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service=new FbaInboundService(jdbc);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID();
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.page(tenant,mp,0,"","ALL",null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.page(tenant,mp,101,"","ALL",null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.page(tenant,mp,50,"","IN_PLATFORM",null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.page(tenant,mp,50,"A".repeat(81),"ALL",null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.page(tenant,mp,50,"A"+(char)10+"B","ALL",null))
            .getStatusCode());
        verifyNoInteractions(jdbc);
        when(jdbc.queryForList(anyString(),eq(UUID.class),eq(tenant),eq(mp)))
            .thenReturn(List.of(mp));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.page(tenant,mp,50,"","ALL","bad*cursor")).getStatusCode());
    }
    @Test void v92HistoryFiltersAllLocalShipmentRecordsBeforeKeyset() {
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service=new FbaInboundService(jdbc);
        UUID tenant=UUID.randomUUID(),mp=UUID.randomUUID();
        when(jdbc.queryForList(anyString(),eq(UUID.class),eq(tenant),eq(mp)))
            .thenReturn(List.of(mp));
        var page=service.page(tenant,mp,50,"V92%_!","REVIEWING",null);
        assertEquals("v92%_!",page.searchPrefix());
        assertEquals("REVIEWING",page.state());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
        verify(jdbc).query(argThat((String sql)->
            sql.contains("FROM fba_inbound_shipment s")
            &&sql.contains("lower(s.reference) LIKE ? ESCAPE '!'")
            &&sql.contains("ORDER BY s.created_at DESC,s.id DESC")
            &&sql.contains("LIMIT ?")),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq(tenant),eq(mp),eq("v92!%!_!!%"),eq("REVIEWING"),eq("REVIEWING"),
            eq(false),isNull(),isNull(),eq(51));
    }
    @Test void v92ShipmentCursorBoundToMarketplaceSearchAndState() throws Exception {
        UUID marketplace=UUID.randomUUID();
        var row=new FbaInboundService.Shipment(UUID.randomUUID().toString(),
            "V92-HISTORY","MOCK","DRAFT",
            "2026-11-04T03:00:00Z",null,null,null);
        var encoder=FbaInboundService.class.getDeclaredMethod(
            "pageCursor",UUID.class,String.class,String.class,FbaInboundService.Shipment.class);
        encoder.setAccessible(true);
        String cursor=(String)encoder.invoke(null,marketplace,"v92","ALL",row);
        String decoded=new String(java.util.Base64.getUrlDecoder().decode(cursor),
            java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(5,decoded.split("\\n",-1).length);
        var parser=FbaInboundService.class.getDeclaredMethod(
            "parsePageCursor",String.class,UUID.class,String.class,String.class);
        parser.setAccessible(true);
        assertNotNull(parser.invoke(null,cursor,marketplace,"v92","ALL"));
        for(String different:List.of("DRAFT","IN_TRANSIT")) {
            var error=assertThrows(java.lang.reflect.InvocationTargetException.class,
                ()->parser.invoke(null,cursor,marketplace,"v92",different));
            assertInstanceOf(ResponseStatusException.class,error.getCause());
        }
        var error=assertThrows(java.lang.reflect.InvocationTargetException.class,
            ()->parser.invoke(null,cursor,UUID.randomUUID(),"v92","ALL"));
        assertInstanceOf(ResponseStatusException.class,error.getCause());
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
        assertTrue(api.page(auth,request,mp,50,"V92","ALL",null)
            .getHeaders().getCacheControl().contains("no-store"));
        verify(service).page(tenant,mp,50,"V92","ALL",null);
        api.transit(auth,request,mp);
        verify(service).transit(tenant,mp);
    }
}
