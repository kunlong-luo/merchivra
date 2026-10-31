package org.merchivra.erp.inventory;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.merchivra.erp.purchasing.PurchasingService;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner + session CSRF. Draft creation is local; approval is a separate purchase action. */
@RestController
@RequestMapping("/api/v1/replenishment/marketplaces/{marketplaceId}/purchase-drafts")
public class ReplenishmentPurchaseDraftController {
    private final TenantAccessService access;
    private final ReplenishmentPurchaseDraftService service;

    public ReplenishmentPurchaseDraftController(TenantAccessService access,
                                                 ReplenishmentPurchaseDraftService service) {
        this.access=access;
        this.service=service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchasingService.Detail create(Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@RequestBody ReplenishmentPurchaseDraftService.Request body) {
        return service.create(access.currentTenant(auth,request),
            access.authenticatedUser(auth),marketplaceId,body);
    }

    @GetMapping
    public List<ReplenishmentPurchaseDraftService.Origin> history(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId) {
        return service.history(access.currentTenant(auth,request),marketplaceId);
    }
}
