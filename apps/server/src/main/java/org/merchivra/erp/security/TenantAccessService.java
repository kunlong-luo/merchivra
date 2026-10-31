package org.merchivra.erp.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Always derives user from the authenticated server principal and verifies
 * membership/OWNER on EVERY access. Never accepts a caller-supplied tenant ID
 * as authoritative; active selection is persisted in the server-side session.
 */
@Service
public class TenantAccessService {
    private static final String TENANT_SESSION_KEY = "merchivra.activeTenantId";
    private final JdbcTemplate jdbc;

    public TenantAccessService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID authenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    public List<UUID> accessibleTenants(Authentication authentication) {
        UUID user = authenticatedUser(authentication);
        return jdbc.queryForList("""
            SELECT m.tenant_id FROM membership m
            JOIN tenant t ON t.id = m.tenant_id
            JOIN app_user u ON u.id=m.user_id
            WHERE m.user_id = ? AND m.status = 'ACTIVE'
              AND u.status='ACTIVE' AND t.status = 'ACTIVE'
              AND EXISTS (
                SELECT 1 FROM role_binding rb WHERE rb.tenant_id = m.tenant_id
                AND rb.user_id = m.user_id AND rb.role_code = 'OWNER'
                AND rb.seller_account_id IS NULL)
            ORDER BY m.tenant_id
            """, UUID.class, user);
    }

    public UUID currentTenant(Authentication authentication, HttpServletRequest request) {
        List<UUID> allowed = accessibleTenants(authentication);
        if (allowed.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Object chosen = request.getSession().getAttribute(TENANT_SESSION_KEY);
        if (chosen instanceof UUID tenant && allowed.contains(tenant)) return tenant;
        UUID first = allowed.getFirst();
        request.getSession().setAttribute(TENANT_SESSION_KEY, first);
        return first;
    }


    /**
     * V16 read-only workspace access. Does NOT change currentTenant's OWNER
     * gate used by sensitive existing controllers and write operations.
     */
    public List<UUID> readableTenants(Authentication authentication) {
        UUID user = authenticatedUser(authentication);
        return jdbc.queryForList("""
            SELECT DISTINCT m.tenant_id FROM membership m
            JOIN tenant t ON t.id=m.tenant_id
            JOIN app_user u ON u.id=m.user_id
            WHERE m.user_id=? AND m.status='ACTIVE'
              AND u.status='ACTIVE' AND t.status='ACTIVE'
              AND EXISTS (
                SELECT 1 FROM role_binding rb
                LEFT JOIN seller_account a ON a.tenant_id=rb.tenant_id
                  AND a.id=rb.seller_account_id
                WHERE rb.tenant_id=m.tenant_id AND rb.user_id=m.user_id
                  AND ((rb.role_code='OWNER' AND rb.seller_account_id IS NULL)
                    OR (rb.role_code='OPERATOR_READ'
                        AND a.id IS NOT NULL))
              )
            ORDER BY m.tenant_id
            """, UUID.class, user);
    }

    public UUID currentReadTenant(Authentication auth,HttpServletRequest request) {
        List<UUID> allowed=readableTenants(auth);
        if(allowed.isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Object selected=request.getSession().getAttribute(TENANT_SESSION_KEY);
        if(selected instanceof UUID tenant && allowed.contains(tenant))return tenant;
        UUID tenant=allowed.getFirst();
        request.getSession().setAttribute(TENANT_SESSION_KEY,tenant);
        return tenant;
    }

    public boolean isOwner(Authentication auth,UUID tenant) {
        return accessibleTenants(auth).contains(tenant);
    }

    public UUID switchTenant(Authentication authentication, HttpServletRequest request, UUID tenantId) {
        if (!readableTenants(authentication).contains(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        request.getSession().setAttribute(TENANT_SESSION_KEY, tenantId);
        return tenantId;
    }
}
