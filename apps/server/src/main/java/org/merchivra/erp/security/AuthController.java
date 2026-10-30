package org.merchivra.erp.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final TenantAccessService tenants;

    public AuthController(TenantAccessService tenants) { this.tenants = tenants; }

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) throw new IllegalStateException("CSRF protection not initialized");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
    }

    public record Viewer(String userId, String activeTenantId, List<String> availableTenantIds) {}

    @GetMapping("/me")
    public ResponseEntity<Viewer> me(Authentication authentication, HttpServletRequest request) {
        UUID user = tenants.authenticatedUser(authentication);
        UUID current = tenants.currentTenant(authentication, request);
        List<String> allowed = tenants.accessibleTenants(authentication).stream()
            .map(UUID::toString).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Viewer(user.toString(), current.toString(), allowed));
    }

    public record TenantSelection(UUID tenantId) {}

    @PostMapping("/active-tenant")
    public ResponseEntity<Map<String, String>> selectTenant(
        Authentication authentication, HttpServletRequest request, @RequestBody TenantSelection body) {
        UUID tenant = tenants.switchTenant(authentication, request, body.tenantId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Map.of("activeTenantId", tenant.toString()));
    }
}
