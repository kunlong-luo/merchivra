package org.merchivra.erp.spapi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Read-only status/audit + local revoke. No token submission or retrieval route. */
@RestController
@RequestMapping("/api/v1/seller-accounts/{accountId}/private-authorization")
@ConditionalOnProperty(prefix="merchivra.spapi.private",name="enabled",havingValue="true")
public class PrivateSellerController {
    private final TenantAccessService access;
    private final PrivateSellerGrantService service;
    public PrivateSellerController(TenantAccessService access,PrivateSellerGrantService service) {
        this.access=access;this.service=service;
    }
    @GetMapping("/status")
    public ResponseEntity<PrivateSellerGrantService.Status> status(
        Authentication auth,HttpServletRequest req,@PathVariable UUID accountId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.status(access.currentTenant(auth,req),accountId));
    }
    @GetMapping("/audit")
    public ResponseEntity<List<PrivateSellerGrantService.Audit>> audit(
        Authentication auth,HttpServletRequest req,@PathVariable UUID accountId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.history(access.currentTenant(auth,req),accountId));
    }
    @PostMapping("/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication auth,HttpServletRequest req,
                       @PathVariable UUID accountId) {
        service.revoke(access.currentTenant(auth,req),
            access.authenticatedUser(auth),accountId);
    }
}
