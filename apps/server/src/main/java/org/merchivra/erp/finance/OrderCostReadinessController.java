package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner-scoped COGS readiness only, not profit, ledger or Amazon transaction. */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/orders")
public class OrderCostReadinessController {
    private final TenantAccessService access;
    private final OrderCostReadinessService service;
    public OrderCostReadinessController(TenantAccessService access,
        OrderCostReadinessService service) {
        this.access=access;this.service=service;
    }
    @GetMapping("/{orderId}/cost-readiness")
    public ResponseEntity<OrderCostEngine.Preview> preview(Authentication auth,
        HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.preview(access.currentTenant(auth,request),marketplaceId,orderId));
    }
}
