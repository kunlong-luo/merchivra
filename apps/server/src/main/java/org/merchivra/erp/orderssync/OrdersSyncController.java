package org.merchivra.erp.orderssync;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner Session + CSRF, never accepts tenant/account/token/window from browser. */
@RestController
@RequestMapping("/api/v1/orders-sync")
@ConditionalOnProperty(prefix="merchivra.orders-sync",name="enabled",havingValue="true")
public class OrdersSyncController {
    private final TenantAccessService access;
    private final OrdersSyncService service;
    public OrdersSyncController(TenantAccessService access, OrdersSyncService service) {
        this.access=access; this.service=service;
    }
    @GetMapping("/{marketplaceId}")
    public OrdersSyncService.Status status(Authentication auth,HttpServletRequest request,
                                           @PathVariable UUID marketplaceId) {
        return service.status(access.currentTenant(auth,request),marketplaceId);
    }
    @PostMapping("/{marketplaceId}/start")
    public OrdersSyncService.Status start(Authentication auth,HttpServletRequest request,
                                          @PathVariable UUID marketplaceId) {
        return service.start(access.currentTenant(auth,request),marketplaceId);
    }
    @PostMapping("/{marketplaceId}/step")
    public OrdersSyncService.Status step(Authentication auth,HttpServletRequest request,
                                         @PathVariable UUID marketplaceId) {
        return service.step(access.currentTenant(auth,request),marketplaceId);
    }
}
