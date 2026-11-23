package org.merchivra.erp.finance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.merchivra.erp.security.TenantAccessService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner/Session read-only whole-order refund COGS conservation diagnostic.
 * No calculation of recognized profit, inventory posting or public download.
 */
@RestController
@RequestMapping("/api/v1/finance/marketplaces/{marketplaceId}/orders/{orderId}")
public class RefundOrderConservationControllerV210 {
    private final TenantAccessService access;
    private final RefundOrderConservationServiceV210 conservation;

    public RefundOrderConservationControllerV210(TenantAccessService access,
        RefundOrderConservationServiceV210 conservation) {
        this.access=access;this.conservation=conservation;
    }

    @GetMapping("/refund-cogs-conservation")
    public ResponseEntity<RefundOrderConservationEngineV210.Result> preview(
        Authentication auth,HttpServletRequest request,
        @PathVariable UUID marketplaceId,@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(conservation.preview(
                access.currentTenant(auth,request),marketplaceId,orderId));
    }
}
