package org.merchivra.erp.spapi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** OWNER + session + CSRF; no unauthenticated verification trigger. */
@RestController
@ConditionalOnProperty(prefix="merchivra.spapi.verification",name="enabled",havingValue="true")
@RequestMapping("/api/v1/seller-accounts")
public class SellerVerificationController {
    private final TenantAccessService access;
    private final SellerConnectionVerificationService service;

    public SellerVerificationController(TenantAccessService access,
                                        SellerConnectionVerificationService service) {
        this.access=access; this.service=service;
    }

    @PostMapping("/{accountId}/oauth/verify")
    public ResponseEntity<SellerConnectionVerificationService.Result> verify(
        Authentication authentication, HttpServletRequest request, @PathVariable UUID accountId) {
        var result=service.verify(access.currentTenant(authentication,request),
            access.authenticatedUser(authentication),accountId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
