package org.merchivra.erp.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadOnlyStaffGuardFilterTest {
    private final TenantAccessService access=mock(TenantAccessService.class);
    private final ReadOnlyStaffGuardFilter guard=new ReadOnlyStaffGuardFilter(access);
    private final FilterChain chain=mock(FilterChain.class);
    private final String id=UUID.randomUUID().toString();

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private static MockHttpServletRequest req(String method,String path) {
        var r=new MockHttpServletRequest(method,path);
        r.setServletPath(path);
        return r;
    }

    private Authentication staff() {
        var a=new UsernamePasswordAuthenticationToken(id,"n/a",List.of());
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    @Test void staffCanOnlyReachReadOnlyScopedApi() throws Exception {
        Authentication a=staff();
        when(access.accessibleTenants(a)).thenReturn(List.of());
        var req=req("GET","/api/v1/seller-marketplaces");
        var res=new MockHttpServletResponse();
        guard.doFilter(req,res,chain);
        assertEquals(200,res.getStatus());
        verify(chain).doFilter(req,res);
        verifyNoInteractions(access);
    }

    @Test void staffCannotReadUnscopedOrWriteScope() throws Exception {
        Authentication a=staff();
        when(access.accessibleTenants(a)).thenReturn(List.of());
        var req=req("GET","/api/v1/operations/summary");
        var res=new MockHttpServletResponse();
        guard.doFilter(req,res,chain);
        assertEquals(403,res.getStatus());
        verifyNoInteractions(chain);
    }

    @Test void sessionOwnerContinuesUsingLegacyRoutes() throws Exception {
        Authentication a=staff();
        when(access.accessibleTenants(a)).thenReturn(List.of(UUID.randomUUID()));
        var req=req("POST","/api/v1/operations/scan");
        var res=new MockHttpServletResponse();
        guard.doFilter(req,res,chain);
        assertEquals(200,res.getStatus());
        verify(chain).doFilter(req,res);
    }

    @Test void staffCannotTriggerDemoSyncOrAccountSetup() throws Exception {
        Authentication a=staff();
        when(access.accessibleTenants(a)).thenReturn(List.of());
        for(String path:List.of("/api/v1/demo/sync-runs","/api/v1/seller-accounts",
            "/api/v1/orders-sync/00000000-0000-4000-8000-000000000000/start")){
            guard.doFilter(req("POST",path),
                new MockHttpServletResponse(),chain);
        }
        verifyNoInteractions(chain);
    }
}
