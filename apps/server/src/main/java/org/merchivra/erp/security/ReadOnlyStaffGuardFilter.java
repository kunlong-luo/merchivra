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
          || path.equals("/api/v1/auth/csrf") || path.equals("/api/v1/auth/me")
          || path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/logout")
          || path.equals("/api/v1/auth/active-tenant")
          || path.equals("/api/v1/auth/invitations/accept")
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
             (path.matches("^/api/v1/seller-marketplaces/[0-9a-fA-F-]{36}/(orders|listings|inventory/fba)$")
              || path.matches("^/api/v1/seller-marketplaces/[0-9a-fA-F-]{36}/orders/[0-9a-fA-F-]{36}/items$")));
        if(!coreRead) {
            // Owner in another tenant cannot use that role to authorize a
            // privileged request while the current workspace selects a staff
            // role in this tenant.
            java.util.UUID current;
            try {
                current=access.currentReadTenant(auth,request);
            } catch(org.springframework.web.server.ResponseStatusException denied) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);return;
            }
            if(!access.isOwner(auth,current)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);return;
            }
        }
        chain.doFilter(request,response);
    }
}
