package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner Session-scoped, read-only local purchase delivery overview. */
@RestController
@RequestMapping("/api/v1/purchasing/delivery")
public class PurchaseDeliveryController {
    private final PurchaseDeliveryService delivery;
    private final TenantAccessService access;
    public PurchaseDeliveryController(PurchaseDeliveryService delivery,TenantAccessService access) {
        this.delivery=delivery;this.access=access;
    }
    @GetMapping("/overview")
    public PurchaseDeliveryService.Overview overview(Authentication auth,HttpServletRequest req) {
        return delivery.overview(access.currentTenant(auth,req));
    }
}
