package org.merchivra.erp.catalog;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSRF-protected local placeholders only. No real Seller OAuth, tokens or connection.
 * Spring Security requires an authenticated session for every endpoint.
 */
@RestController
@RequestMapping("/api/v1/seller-accounts")
public class LocalSellerSetupController {
    private final TenantAccessService access;
    private final LocalSellerSetupService service;

    public LocalSellerSetupController(TenantAccessService access, LocalSellerSetupService service) {
        this.access = access;
        this.service = service;
    }

    public record CreateAccount(String displayName, String region) {}
    public record AddMarketplace(String marketplaceId) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LocalSellerSetupService.LocalAccount create(
        Authentication auth, HttpServletRequest request,
        @RequestHeader("Idempotency-Key") String key, @RequestBody CreateAccount body) {
        UUID tenant = access.currentTenant(auth, request);
        UUID actor = access.authenticatedUser(auth);
        return service.createAccount(tenant, actor, body.displayName(), body.region(), key);
    }

    @PostMapping("/{accountId}/marketplaces")
    @ResponseStatus(HttpStatus.CREATED)
    public LocalSellerSetupService.LocalMarketplace addMarketplace(
        Authentication auth, HttpServletRequest request, @PathVariable UUID accountId,
        @RequestBody AddMarketplace body) {
        UUID tenant = access.currentTenant(auth, request);
        UUID actor = access.authenticatedUser(auth);
        return service.addMarketplace(tenant, actor, accountId, body.marketplaceId());
    }

    @GetMapping("/audit")
    public List<LocalSellerSetupService.AuditEvent> recentAudit(
        Authentication auth, HttpServletRequest request) {
        return service.recentAudit(access.currentTenant(auth, request));
    }
}
