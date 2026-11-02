package org.merchivra.erp.fba;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** V84 scoped permissions for existing active Owners only. */
@RestController
@RequestMapping("/api/v1/fba/marketplaces/{marketplaceId}/purchase-reconciliation/permissions")
public class FbaExceptionPermissionController {
    private final FbaExceptionPermissionService service;
    private final TenantAccessService access;
    public FbaExceptionPermissionController(FbaExceptionPermissionService service,
                                            TenantAccessService access) {
        this.service=service;this.access=access;
    }
    @GetMapping("/me")
    public ResponseEntity<FbaExceptionPermissionService.Profile> mine(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.mine(access.currentTenant(auth,request),marketplaceId,
                access.authenticatedUser(auth)));
    }
    @GetMapping("/owners")
    public ResponseEntity<List<FbaExceptionPermissionService.Profile>> owners(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.owners(access.currentTenant(auth,request),marketplaceId,
                access.authenticatedUser(auth)));
    }
    @PostMapping("/owners/{ownerId}")
    public FbaExceptionPermissionService.Profile update(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID ownerId,@RequestBody FbaExceptionPermissionService.Update body) {
        return service.save(access.currentTenant(auth,request),marketplaceId,
            access.authenticatedUser(auth),ownerId,body);
    }
    @GetMapping("/audit")
    public ResponseEntity<List<FbaExceptionPermissionService.Audit>> audit(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.audit(access.currentTenant(auth,request),marketplaceId,
                access.authenticatedUser(auth)));
    }
}
