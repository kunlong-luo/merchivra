package org.merchivra.erp.spapi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Explicit OWNER action with session + CSRF; accepts no key or token input. */
@RestController
@ConditionalOnProperty(prefix="merchivra.spapi.oauth",name="enabled",havingValue="true")
@RequestMapping("/api/v1/seller-accounts")
public class SellerKeyRotationController {
    private final TenantAccessService access;
    private final SellerGrantKeyRotationService rotation;

    public SellerKeyRotationController(TenantAccessService access,
                                       SellerGrantKeyRotationService rotation) {
        this.access=access; this.rotation=rotation;
    }

    @PostMapping("/{accountId}/oauth/rotate-key")
    public ResponseEntity<SellerGrantKeyRotationService.Result> rotate(
            Authentication auth, HttpServletRequest request, @PathVariable UUID accountId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            rotation.rotate(access.currentTenant(auth,request),
                access.authenticatedUser(auth), accountId));
    }
}
