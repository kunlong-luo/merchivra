package org.merchivra.erp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * V16 denies every other authenticated API to read-only staff, even legacy
 * Demo or unscoped endpoints that were written before staff accounts existed.
 * This is a second boundary; MarketplaceReadController independently scopes
 * each query by current active membership + seller-account role.
 */
public final class ReadOnlyStaffGuardFilter extends OncePerRequestFilter {
    private final TenantAccessService access;
    public ReadOnlyStaffGuardFilter(TenantAccessService access) { this.access=access; }

    @Override protected void doFilterInternal(HttpServletRequest request,
        HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getServletPath();
        if(!path.startsWith("/api/v1/") || path.equals("/api/v1/ping")
          || path.startsWith("/api/v1/auth/")
          || path.equals("/api/v1/spapi/oauth/login")
          || path.equals("/api/v1/spapi/oauth/callback")) {
            chain.doFilter(request,response);return;
        }
        Authentication auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() ||
          !(auth.getName().matches("[0-9a-fA-F-]{36}"))) {
            // Spring Security still owns anonymous 401/403 handling.
            chain.doFilter(request,response);return;
        }
        // Staff can only GET tenant/scoped read facts; no POST, no unscoped APIs.
        boolean coreRead="GET".equals(request.getMethod()) &&
            (path.equals("/api/v1/seller-marketplaces") ||
             path.startsWith("/api/v1/seller-marketplaces/"));
        if(!coreRead && access.accessibleTenants(auth).isEmpty()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);return;
        }
        chain.doFilter(request,response);
    }
}
