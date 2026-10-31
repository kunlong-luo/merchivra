package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Owner read only. No recognized revenue/settlements or Amazon writes.
 */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/orders")
public class OrderContributionController {
    private final TenantAccessService access;
    private final OrderContributionService contribution;
    public OrderContributionController(TenantAccessService access,
        OrderContributionService contribution) {
        this.access=access;this.contribution=contribution;
    }
    @GetMapping("/{orderId}/contribution-preview")
    public ResponseEntity<OrderContributionEngine.Preview> preview(
        Authentication auth,HttpServletRequest request,@PathVariable UUID marketplaceId,
        @PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            contribution.preview(access.currentTenant(auth,request),marketplaceId,orderId));
    }
}
