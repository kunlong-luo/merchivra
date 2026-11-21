package org.merchivra.erp.purchasing;

import jakarta.servlet.http.HttpServletRequest;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    /** V119 true full-tenant aggregate; no legacy 300-line window. */
    @GetMapping("/summary")
    public ResponseEntity<PurchaseDeliveryService.FullSummary> summary(
        Authentication auth,HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(delivery.globalSummary(access.currentTenant(auth,req)));
    }

    /** V119 complete line directory with scoped deterministic keyset cursor. */
    @GetMapping("/lines/page")
    public ResponseEntity<LocalKeysetPage.Page<PurchaseDeliveryService.LineProgress>>
        linesPage(Authentication auth,HttpServletRequest req,
            @RequestParam(defaultValue="50") int limit,
            @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(delivery.page(access.currentTenant(auth,req),limit,cursor));
    }

    @GetMapping("/overview")
    public PurchaseDeliveryService.Overview overview(Authentication auth,HttpServletRequest req) {
        return delivery.overview(access.currentTenant(auth,req));
    }
}
